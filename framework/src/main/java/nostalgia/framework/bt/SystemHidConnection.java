package nostalgia.framework.bt;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Method;

import nostalgia.framework.utils.NLog;

/**
 * 经典蓝牙 HID 手柄连接：复用系统蓝牙栈自带的 HID host（HidService）。
 * <p>
 * 这台车机的 Android 6 蓝牙栈里，应用层能拿到的隐藏 L2CAP socket 被写死为
 * ERTM 模式（btif_sock_l2cap.c obex_l2c_etm_opt），而 HID 设备只支持 Basic 模式，
 * 栈在发连接请求前就以 "Peer does not support our desired channel types" 放弃，
 * 所以应用内自建 L2CAP 不可行（见 {@link ClassicGamepadConnection}）。
 * </p>
 * Bluetooth.apk 里其实带着 {@code com.android.bluetooth.hid.HidService}，只是被资源开关
 * {@code profile_supported_hid=false} 禁用。一次性 {@code pm enable} 该组件后，
 * 这里在每次连接前用显式 Intent 把它拉起（蓝牙重启后 AdapterService 不会自动启动它），
 * 再通过 {@code BluetoothProfile.INPUT_DEVICE} 代理发起 HID 连接。
 * 连上后手柄就是标准 Android InputDevice，按键走 KeyEvent → KeyboardController，
 * 不再需要应用层解码报告。
 */
public class SystemHidConnection implements GamepadConnection {

    private static final String TAG = "SystemHidConn";
    /** BluetoothProfile.INPUT_DEVICE（hidden） */
    private static final int PROFILE_INPUT_DEVICE = 4;
    /** BluetoothInputDevice.ACTION_CONNECTION_STATE_CHANGED（hidden） */
    private static final String ACTION_HID_STATE =
            "android.bluetooth.input.profile.action.CONNECTION_STATE_CHANGED";
    private static final ComponentName HID_SERVICE = new ComponentName(
            "com.android.bluetooth", "com.android.bluetooth.hid.HidService");

    private final Handler main = new Handler(Looper.getMainLooper());
    private Context ctx;
    private BluetoothDevice device;
    private GamepadManager manager;
    private volatile boolean closed;
    private BluetoothProfile proxy;
    private boolean receiverRegistered;
    private static final int MAX_ATTEMPTS = 3;
    private int attempts;
    private boolean connectedOnce;

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (d == null || device == null || !d.getAddress().equals(device.getAddress())) {
                return;
            }
            int state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1);
            int prev = intent.getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, -1);
            manager.log("HID 状态 " + prev + " -> " + state
                    + " (0=断开 1=连接中 2=已连接 3=断开中)");
            if (state == BluetoothProfile.STATE_CONNECTED) {
                attempts = 0;
                connectedOnce = true;
                manager.onConnected(device, false);
            } else if (state == BluetoothProfile.STATE_DISCONNECTED && !closed) {
                // 连接中失败：典型是手柄按过 SYNC 丢了密钥 → 认证 Key Missing →
                // MTK 栈自动 remove_bond。重试走 waitBonded 重新 createBond 后再连
                if (!connectedOnce && attempts < MAX_ATTEMPTS) {
                    attempts++;
                    manager.log("HID 连接失败，重试 #" + attempts
                            + "（bond=" + device.getBondState() + "）");
                    new Thread(SystemHidConnection.this::run, "bt-hid-retry").start();
                } else {
                    manager.onDisconnected("HID 断开");
                }
            }
        }
    };

    @Override
    public void connect(Context ctx, BluetoothDevice device, GamepadManager manager) {
        this.ctx = ctx.getApplicationContext();
        this.device = device;
        this.manager = manager;
        closed = false;
        attempts = 0;
        connectedOnce = false;
        main.post(() -> {
            if (!receiverRegistered) {
                this.ctx.registerReceiver(stateReceiver, new IntentFilter(ACTION_HID_STATE));
                receiverRegistered = true;
            }
        });
        startHidService();
        new Thread(this::run, "bt-hid-conn").start();
    }

    /** 拉起系统 HidService（需已 pm enable；蓝牙每次重启后都要再拉一次） */
    private void startHidService() {
        try {
            Intent i = new Intent().setComponent(HID_SERVICE)
                    .putExtra("action", "com.android.bluetooth.btservice.action.STATE_CHANGED")
                    .putExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_ON);
            ComponentName r = ctx.startService(i);
            manager.log("拉起 HidService: " + (r != null ? "OK" : "失败(组件未启用? 需 pm enable)"));
        } catch (Throwable t) {
            manager.log("拉起 HidService 异常: " + t);
        }
    }

    private void run() {
        try {
            if (!waitBonded()) {
                return;
            }
            // 给 HidService 一点启动时间
            Thread.sleep(800);
            if (closed) {
                return;
            }
            main.post(() -> {
                if (proxy != null) {
                    connectViaProxy(proxy);
                } else {
                    openProxy();
                }
            });
        } catch (InterruptedException ignored) {
        }
    }

    private void connectViaProxy(BluetoothProfile p) {
        if (closed) {
            return;
        }
        try {
            int st = p.getConnectionState(device);
            manager.log("HID proxy 就绪, 当前状态=" + st);
            if (st == BluetoothProfile.STATE_CONNECTED) {
                connectedOnce = true;
                manager.onConnected(device, false);
                return;
            }
            Method m = p.getClass().getMethod("connect", BluetoothDevice.class);
            Object r = m.invoke(p, device);
            manager.log("HID connect() -> " + r + "，等待手柄响应...");
        } catch (Throwable t) {
            NLog.e(TAG, "hid connect", t);
            manager.onError("HID connect 失败: " + t);
        }
    }

    /** 等 bond 完成（状态异步，NONE 容忍 5s，BONDING 最多 30s） */
    private boolean waitBonded() throws InterruptedException {
        if (device.getBondState() == BluetoothDevice.BOND_BONDED) {
            return true;
        }
        if (device.getBondState() == BluetoothDevice.BOND_NONE) {
            manager.log("配对中... createBond=" + device.createBond());
        }
        int waited = 0;
        while (!closed && waited < 30000) {
            int bs = device.getBondState();
            if (bs == BluetoothDevice.BOND_BONDED) {
                manager.log("配对完成");
                return true;
            }
            if (bs == BluetoothDevice.BOND_NONE && waited >= 5000) {
                break;
            }
            Thread.sleep(500);
            waited += 500;
        }
        if (!closed) {
            manager.onError("配对失败/超时 bondState=" + device.getBondState()
                    + "（手柄若已配对过请勿再按 SYNC，直接按键唤醒）");
        }
        return false;
    }

    private void openProxy() {
        if (closed) {
            return;
        }
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        boolean ok = adapter.getProfileProxy(ctx, new BluetoothProfile.ServiceListener() {
            @Override
            public void onServiceConnected(int profile, BluetoothProfile p) {
                proxy = p;
                connectViaProxy(p);
            }

            @Override
            public void onServiceDisconnected(int profile) {
                proxy = null;
                manager.log("HID proxy 断开（HidService 退出?）");
            }
        }, PROFILE_INPUT_DEVICE);
        if (!ok) {
            manager.onError("getProfileProxy(INPUT_DEVICE) 失败：HidService 未启用");
        }
    }

    @Override
    public void close() {
        closed = true;
        main.post(() -> {
            if (receiverRegistered) {
                try {
                    ctx.unregisterReceiver(stateReceiver);
                } catch (Exception ignored) {
                }
                receiverRegistered = false;
            }
            if (proxy != null) {
                try {
                    Method m = proxy.getClass().getMethod("disconnect", BluetoothDevice.class);
                    m.invoke(proxy, device);
                } catch (Throwable ignored) {
                }
                try {
                    BluetoothAdapter.getDefaultAdapter()
                            .closeProfileProxy(PROFILE_INPUT_DEVICE, proxy);
                } catch (Throwable ignored) {
                }
                proxy = null;
            }
        });
        if (manager != null) {
            manager.onDisconnected("closed");
        }
    }
}
