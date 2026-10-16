package nostalgia.framework.bt;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import nostalgia.framework.utils.NLog;

/**
 * BLE HID 手柄连接（HID over GATT，HOGP）。
 * <p>
 * 车机系统蓝牙没有 HID host profile，这里直接在应用内走 GATT：
 * 发现 HID Service(0x1812) → 读 Report Map(0x2A4B) 得描述符 →
 * 订阅所有 Report(0x2A4D) 输入通知 → {@link HidReportParser} 解码。
 * </p>
 */
public class BleGamepadConnection extends BluetoothGattCallback implements GamepadConnection {

    private static final UUID SVC_HID = uuid16(0x1812);
    private static final UUID CHR_REPORT_MAP = uuid16(0x2A4B);
    private static final UUID CHR_REPORT = uuid16(0x2A4D);
    private static final UUID CHR_PROTOCOL_MODE = uuid16(0x2A4E);
    private static final UUID DESC_CCCD = uuid16(0x2902);
    private static UUID uuid16(int v) {
        return UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", v));
    }

    private GamepadManager manager;
    private BluetoothGatt gatt;
    private BluetoothDevice device;
    private Context ctx;
    private Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean closed;

    private HidReportParser parser;
    private final List<BluetoothGattCharacteristic> pendingSubscribe = new ArrayList<>();
    private int bondedRetry;

    @Override
    public void connect(Context ctx, BluetoothDevice device, GamepadManager manager) {
        this.ctx = ctx.getApplicationContext();
        this.device = device;
        this.manager = manager;
        closed = false;
        bondedRetry = 0;
        doGattConnect();
    }

    private void doGattConnect() {
        if (closed) {
            return; // retryConnect 的延迟回调：期间用户已断开则不再重连
        }
        try {
            gatt = device.connectGatt(ctx, false, this);
        } catch (SecurityException e) {
            manager.onError("无蓝牙权限");
        }
    }

    @Override
    public void close() {
        closed = true;
        try {
            if (gatt != null) {
                gatt.disconnect();
                gatt.close();
                gatt = null;
            }
        } catch (SecurityException ignored) {
        }
    }

    @Override
    public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
        if (closed) {
            return;
        }
        if (newState == BluetoothProfile.STATE_CONNECTED) {
            manager.onConnected(device, true);
            try {
                g.discoverServices();
            } catch (SecurityException e) {
                manager.onError(e.getMessage());
            }
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            try {
                if (gatt != null) {
                    gatt.close();
                    gatt = null;
                }
            } catch (SecurityException ignored) {
            }
            manager.onDisconnected("GATT " + status);
        }
    }

    @Override
    public void onServicesDiscovered(BluetoothGatt g, int status) {
        if (closed) {
            return;
        }
        if (status != BluetoothGatt.GATT_SUCCESS) {
            // 未配对时服务发现会被拒绝 → 先 createBond 再重连
            if (bondedRetry++ == 0 && device.getBondState() != BluetoothDevice.BOND_BONDED) {
                try {
                    device.createBond();
                } catch (SecurityException ignored) {
                }
                retryConnect();
                return;
            }
            manager.onError("GATT 服务发现失败 " + status);
            return;
        }
        BluetoothGattService hid = g.getService(SVC_HID);
        if (hid == null) {
            manager.onError("该设备没有 HID 服务(0x1812)，不是 BLE 手柄");
            close();
            return;
        }
        parser = null;
        pendingSubscribe.clear();
        bondedRetry = 0;
        // 先读 Report Map
        BluetoothGattCharacteristic map = hid.getCharacteristic(CHR_REPORT_MAP);
        if (map != null) {
            try {
                g.readCharacteristic(map);
            } catch (SecurityException e) {
                manager.onError(e.getMessage());
            }
        } else {
            subscribeReports(hid);
        }
    }

    @Override
    public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
        if (closed || c == null || c.getValue() == null) {
            return;
        }
        if (CHR_REPORT_MAP.equals(c.getUuid())) {
            byte[] desc = c.getValue();
            try {
                parser = HidReportParser.parse(desc);
                NLog.i("BleGamepad", "report map parsed, fields=" + parser.getFields().size()
                        + " useReportIds=" + parser.usesReportIds());
            } catch (Throwable t) {
                NLog.e("BleGamepad", "report map parse fail: " + t);
                parser = null;
            }
            BluetoothGattService hid = g.getService(SVC_HID);
            if (hid != null) {
                subscribeReports(hid);
            }
        }
    }

    /** 订阅 HID service 下所有 input report 特性 */
    private void subscribeReports(BluetoothGattService hid) {
        for (BluetoothGattCharacteristic c : hid.getCharacteristics()) {
            if (!CHR_REPORT.equals(c.getUuid())) {
                continue;
            }
            BluetoothGattDescriptor cccd = c.getDescriptor(DESC_CCCD);
            if (cccd == null) {
                continue;
            }
            pendingSubscribe.add(c);
        }
        // 协议模式切到 Report Protocol（可选）
        BluetoothGattCharacteristic pm = hid.getCharacteristic(CHR_PROTOCOL_MODE);
        if (pm != null) {
            try {
                pm.setValue(new byte[]{0x01});
                gatt.writeCharacteristic(pm);
            } catch (SecurityException ignored) {
            }
        }
        subscribeNext();
    }

    private void subscribeNext() {
        if (closed || gatt == null) {
            return;
        }
        if (pendingSubscribe.isEmpty()) {
            return;
        }
        BluetoothGattCharacteristic c = pendingSubscribe.remove(0);
        try {
            BluetoothGattDescriptor cccd = c.getDescriptor(DESC_CCCD);
            cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            gatt.setCharacteristicNotification(c, true);
            gatt.writeDescriptor(cccd);
        } catch (SecurityException e) {
            subscribeNext();
        }
    }

    @Override
    public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
        subscribeNext();
    }

    @Override
    public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
        if (closed || c == null || c.getValue() == null) {
            return;
        }
        byte[] data = c.getValue();
        manager.onReport(data);
        if (parser != null) {
            try {
                GamepadState s = parser.decode(data);
                manager.onState(s);
            } catch (Throwable ignored) {
            }
        }
    }

    private void retryConnect() {
        close();
        closed = false;
        main.postDelayed(this::doGattConnect, 1500);
    }
}
