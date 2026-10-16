package nostalgia.framework.bt;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import nostalgia.framework.R;
import nostalgia.framework.base.ScaledAppCompatActivity;
import nostalgia.framework.utils.NLog;

/**
 * 蓝牙手柄管理界面。
 * <p>
 * 车机系统蓝牙设置只对手机配对（且系统无 HID host profile），
 * 这里提供完整的手柄发现→配对→连接能力，连接后由
 * {@link GamepadManager} 直接向模拟器分发按键。
 * </p>
 * <ul>
 *   <li>经典蓝牙手柄：startDiscovery + createBond + L2CAP 直连</li>
 *   <li>BLE 手柄：BluetoothLeScanner + GATT HID over GATT</li>
 * </ul>
 */
public class BluetoothGamepadActivity extends ScaledAppCompatActivity
        implements GamepadManager.DebugListener {

    private static final String TAG = "BtGamepadActivity";
    private static final int REQ_LOCATION = 1001;

    private BluetoothAdapter adapter;
    private final Map<String, BluetoothDevice> found = new LinkedHashMap<>();
    private final List<String> rows = new ArrayList<>();
    private final List<BluetoothDevice> rowDevices = new ArrayList<>();
    private ArrayAdapter<String> listAdapter;
    private TextView statusView;
    private TextView debugView;
    private Button scanBtn;

    private volatile boolean scanning;

    private final BroadcastReceiver btReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (d != null) {
                    found.put(d.getAddress(), d);
                    refreshList();
                }
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                // 经典 inquiry 窗口约 12s，扫描中自动续上一轮避免错过配对窗口
                if (scanning && adapter != null && adapter.isEnabled()) {
                    try {
                        adapter.startDiscovery();
                    } catch (SecurityException ignored) {
                        scanning = false;
                        updateButtons();
                    }
                } else {
                    scanning = false;
                    updateButtons();
                }
            } else if (BluetoothDevice.ACTION_PAIRING_REQUEST.equals(action)) {
                // 手柄一般是 just-works，自动确认避免依赖系统配对弹窗
                BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                int variant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT,
                        BluetoothDevice.ERROR);
                int key = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_KEY, -1);
                debug("PAIRING_REQUEST " + (d == null ? "?" : nameOf(d))
                        + " variant=" + variant + " key=" + key);
                try {
                    if (variant == BluetoothDevice.PAIRING_VARIANT_PASSKEY_CONFIRMATION
                            || variant == 0) {
                        if (d != null) {
                            d.setPairingConfirmation(true);
                            abortBroadcast();
                            debug("setPairingConfirmation(true) OK");
                        }
                    }
                } catch (Throwable t) {
                    NLog.e(TAG, "pairing confirm fail", t);
                    debug("setPairingConfirmation 失败: " + t);
                }
            } else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
                BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                int prev = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1);
                int now = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1);
                // 隐藏 extra：bond 失败/解除原因（1=AUTH_FAILED 2=AUTH_REJECTED 3=AUTH_CANCELED
                // 4=REMOTE_DEVICE_DOWN 5=DISCOVERY_IN_PROGRESS 6=AUTH_TIMEOUT
                // 7=REPEATED_ATTEMPTS 8=REMOTE_AUTH_CANCELED 9=REMOVED）
                int reason = intent.getIntExtra("android.bluetooth.device.extra.REASON", -1);
                debug("BOND " + (d == null ? "?" : nameOf(d)) + " " + bondName(prev)
                        + " -> " + bondName(now) + (reason >= 0 ? " reason=" + reason : ""));
                refreshList();
            }
        }
    };

    private final ScanCallback bleScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice d = result.getDevice();
            // 配对模式下的手柄广播可能不带名字，不能按 getName() 过滤
            if (d != null) {
                found.put(d.getAddress(), d);
                runOnUiThread(BluetoothGamepadActivity.this::refreshList);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            NLog.e(TAG, "BLE scan failed: " + errorCode);
            runOnUiThread(() -> debug("BLE 扫描失败 errorCode=" + errorCode
                    + " (1=已注册 2=内部错误 3=功能不支持 4=权限不足)"));
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        GamepadManager.get().init(this);
        adapter = BluetoothAdapter.getDefaultAdapter();
        setContentView(R.layout.bt_gamepad_activity);

        statusView = findViewById(R.id.bt_status);
        debugView = findViewById(R.id.bt_debug);
        scanBtn = findViewById(R.id.bt_scan_btn);
        Button enableBtn = findViewById(R.id.bt_enable_btn);
        ListView list = findViewById(R.id.bt_device_list);

        listAdapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_list_item_1, rows) {
            @NonNull
            @Override
            public View getView(int position, View convertView, @NonNull ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                TextView tv = v.findViewById(android.R.id.text1);
                tv.setTextColor(0xff33b5e5);
                tv.setTextSize(16);
                return v;
            }
        };
        list.setAdapter(listAdapter);
        list.setOnItemClickListener((p, v, pos, id) -> onDeviceClick(rowDevices.get(pos)));

        enableBtn.setOnClickListener(v -> {
            try {
                adapter.enable();
            } catch (SecurityException e) {
                toast("无蓝牙权限");
            }
        });
        scanBtn.setOnClickListener(v -> toggleScan());
        findViewById(R.id.bt_disconnect_btn).setOnClickListener(
                v -> GamepadManager.get().disconnect());
        findViewById(R.id.bt_keymap_btn).setOnClickListener(
                v -> startActivity(new Intent(this, GamepadMappingActivity.class)));

        IntentFilter f = new IntentFilter();
        f.addAction(BluetoothDevice.ACTION_FOUND);
        f.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        f.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        f.addAction(BluetoothDevice.ACTION_PAIRING_REQUEST);
        registerReceiver(btReceiver, f);

        dumpBtApiSurface();
        enableHciSnoop();
    }

    /** 调试：系统 HID 通路连上后手柄按键以 KeyEvent/MotionEvent 到达，这里直接显示 */
    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent e) {
        if (e.getDeviceId() > 0 && e.getKeyCode() != android.view.KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == android.view.KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                debug("KEY " + android.view.KeyEvent.keyCodeToString(e.getKeyCode())
                        + " dev=" + e.getDeviceId() + " src=0x"
                        + Integer.toHexString(e.getSource()));
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    public boolean dispatchGenericMotionEvent(android.view.MotionEvent e) {
        if ((e.getSource() & android.view.InputDevice.SOURCE_JOYSTICK) != 0) {
            debug(String.format(Locale.US, "AXIS x=%.2f y=%.2f hatx=%.1f haty=%.1f dev=%d",
                    e.getAxisValue(android.view.MotionEvent.AXIS_X),
                    e.getAxisValue(android.view.MotionEvent.AXIS_Y),
                    e.getAxisValue(android.view.MotionEvent.AXIS_HAT_X),
                    e.getAxisValue(android.view.MotionEvent.AXIS_HAT_Y), e.getDeviceId()));
            return true;
        }
        return super.dispatchGenericMotionEvent(e);
    }

    private static String bondName(int s) {
        switch (s) {
            case BluetoothDevice.BOND_NONE: return "NONE";
            case BluetoothDevice.BOND_BONDING: return "BONDING";
            case BluetoothDevice.BOND_BONDED: return "BONDED";
            default: return String.valueOf(s);
        }
    }

    /** 调试：反射开启 HCI snoop（BLUETOOTH_ADMIN 即可），输出到 bt_stack.conf 指定文件 */
    private void enableHciSnoop() {
        try {
            java.lang.reflect.Method m =
                    BluetoothAdapter.class.getMethod("configHciSnoopLog", boolean.class);
            Object r = m.invoke(adapter, true);
            debug("HCI snoop 开启: " + r);
        } catch (Throwable t) {
            debug("HCI snoop 开启失败: " + t);
        }
    }

    /** 反射 dump 框架蓝牙 API 面，便于确认 MTK 栈支持哪些隐藏 L2CAP 接口 */
    private void dumpBtApiSurface() {
        try {
            StringBuilder sb = new StringBuilder("API: ");
            for (java.lang.reflect.Method m : BluetoothAdapter.class.getDeclaredMethods()) {
                String n = m.getName();
                if (n.toLowerCase().contains("l2cap") || n.toLowerCase().contains("listen")) {
                    sb.append(n).append("() ");
                }
            }
            sb.append("| ServerSocket ctor:");
            for (java.lang.reflect.Constructor<?> c :
                    Class.forName("android.bluetooth.BluetoothServerSocket").getDeclaredConstructors()) {
                sb.append(" (").append(c.getParameterTypes().length).append("arg)");
            }
            debug(sb.toString());
        } catch (Throwable t) {
            debug("API dump 失败: " + t);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        GamepadManager.get().addDebugListener(this);
        refreshAll();
    }

    @Override
    protected void onPause() {
        super.onPause();
        GamepadManager.get().removeDebugListener(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopScan();
        try {
            unregisterReceiver(btReceiver);
        } catch (Exception ignored) {
        }
    }

    /* ---------------- 扫描 ---------------- */

    private void toggleScan() {
        if (scanning) {
            stopScan();
            scanning = false;
            updateButtons();
        } else {
            startScan();
        }
    }

    private void startScan() {
        if (adapter == null) {
            toast("设备无蓝牙");
            return;
        }
        if (!adapter.isEnabled()) {
            toast("请先打开蓝牙");
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
            return;
        }
        if (!isLocationOn()) {
            toast("系统定位服务未开启，蓝牙扫描需要定位（Android 6 限制）");
        }
        found.clear();
        refreshList();
        try {
            boolean inq = adapter.startDiscovery();
            debug("经典扫描已启动: " + inq);
            BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
            if (scanner != null) {
                scanner.startScan(bleScanCallback);
                debug("BLE 扫描已启动");
            } else {
                debug("BLE 扫描器不可用（蓝牙未就绪?）");
            }
            scanning = true;
            updateButtons();
        } catch (SecurityException e) {
            toast("缺少蓝牙/定位权限: " + e.getMessage());
        }
    }

    private void stopScan() {
        scanning = false; // 先置标志，防止 FINISHED 广播触发自动续扫
        try {
            if (adapter != null) {
                if (adapter.isDiscovering()) {
                    adapter.cancelDiscovery();
                }
                BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
                if (scanner != null) {
                    scanner.stopScan(bleScanCallback);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private boolean isLocationOn() {
        try {
            return Settings.Secure.getInt(getContentResolver(),
                    Settings.Secure.LOCATION_MODE) != Settings.Secure.LOCATION_MODE_OFF;
        } catch (Settings.SettingNotFoundException e) {
            return true;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOCATION && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startScan();
        }
    }

    /* ---------------- 配对 / 连接 ---------------- */

    private void onDeviceClick(BluetoothDevice d) {
        stopScan();
        try {
            if (d.getBondState() != BluetoothDevice.BOND_BONDED) {
                toast("配对中: " + nameOf(d));
                d.createBond();
                // 配对广播回来后由用户再点一次连接，或直接尝试连接
            }
            boolean ble = d.getType() == BluetoothDevice.DEVICE_TYPE_LE
                    || d.getType() == BluetoothDevice.DEVICE_TYPE_DUAL;
            GamepadManager.get().connect(d, ble);
            toast("连接中: " + nameOf(d) + (ble ? " (BLE)" : " (经典)"));
        } catch (SecurityException e) {
            toast("缺少蓝牙权限");
        }
    }

    /* ---------------- UI ---------------- */

    private String nameOf(BluetoothDevice d) {
        try {
            String n = d.getName();
            return n != null ? n : d.getAddress();
        } catch (SecurityException e) {
            return d.getAddress();
        }
    }

    private void refreshAll() {
        refreshList();
        updateButtons();
        updateStatus();
    }

    private void updateButtons() {
        scanBtn.setText(scanning ? "停止扫描" : "扫描手柄");
        updateStatus();
    }

    private void updateStatus() {
        StringBuilder sb = new StringBuilder();
        if (adapter == null) {
            sb.append("无蓝牙适配器");
        } else {
            sb.append(adapter.isEnabled() ? "蓝牙: 开" : "蓝牙: 关");
            try {
                // 20=不可见不可连 21=可连接 23=可连接+可发现
                sb.append(" scanMode=").append(adapter.getScanMode());
            } catch (SecurityException ignored) {
            }
        }
        if (scanning) {
            sb.append("   扫描中(").append(found.size()).append(")");
        }
        sb.append("   手柄: ").append(GamepadManager.get().getStatus());
        statusView.setText(sb.toString());
    }

    /** 追加一行调试信息到下方日志区 */
    private void debug(String msg) {
        android.util.Log.i(TAG, msg);
        String txt = msg + "\n" + debugView.getText().toString();
        if (txt.length() > 2000) {
            txt = txt.substring(0, 2000);
        }
        debugView.setText(txt);
    }

    private void refreshList() {
        rows.clear();
        rowDevices.clear();
        if (adapter != null) {
            try {
                for (BluetoothDevice d : adapter.getBondedDevices()) {
                    rows.add("已配对  " + nameOf(d) + "  " + typeName(d)
                            + (found.containsKey(d.getAddress()) ? "  [本次扫描到]" : ""));
                    rowDevices.add(d);
                }
            } catch (SecurityException ignored) {
            }
        }
        for (BluetoothDevice d : found.values()) {
            try {
                if (d.getBondState() == BluetoothDevice.BOND_BONDED) {
                    continue;
                }
            } catch (SecurityException ignored) {
            }
            rows.add("新设备  " + nameOf(d) + "  " + typeName(d));
            rowDevices.add(d);
        }
        listAdapter.notifyDataSetChanged();
        updateStatus();
    }

    private String typeName(BluetoothDevice d) {
        try {
            int devClass = d.getBluetoothClass() == null ? -1
                    : d.getBluetoothClass().getDeviceClass();
            String major = "";
            BluetoothClass bc = d.getBluetoothClass();
            if (bc != null && bc.getMajorDeviceClass() == BluetoothClass.Device.Major.PERIPHERAL) {
                major = "外设";
            }
            String t;
            switch (d.getType()) {
                case BluetoothDevice.DEVICE_TYPE_LE: t = "BLE"; break;
                case BluetoothDevice.DEVICE_TYPE_DUAL: t = "双模"; break;
                default: t = "经典"; break;
            }
            return major + t;
        } catch (SecurityException e) {
            return "?";
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    /* ---------------- GamepadManager.DebugListener ---------------- */

    @Override
    public void onStatus(String status) {
        updateStatus();
        debug(status); // 连接过程所有状态也进日志区
    }

    @Override
    public void onRawReport(String hex) {
        String s = "RAW " + hex + "\n" + debugView.getText().toString();
        if (s.length() > 2000) {
            s = s.substring(0, 2000);
        }
        debugView.setText(s);
    }

    @Override
    public void onState(GamepadState s) {
        String line = String.format(Locale.US,
                "axes=%d,%d,%d,%d hat=%d buttons=%08x",
                s.axes[0], s.axes[1], s.axes[2], s.axes[3], s.hat, s.buttons);
        String txt = line + "\n" + debugView.getText().toString();
        if (txt.length() > 2000) {
            txt = txt.substring(0, 2000);
        }
        debugView.setText(txt);
    }
}
