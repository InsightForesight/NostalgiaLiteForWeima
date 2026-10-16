package nostalgia.framework.bt;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
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
import android.widget.CompoundButton;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import nostalgia.framework.R;
import nostalgia.framework.base.ScaledAppCompatActivity;
import nostalgia.framework.controllers.KeyboardController;
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
        implements GamepadManager.DebugListener, GamepadManager.ConnStateListener {

    private static final String TAG = "BtGamepadActivity";
    private static final int REQ_LOCATION = 1001;

    private BluetoothAdapter adapter;
    private final Map<String, BluetoothDevice> found = new LinkedHashMap<>();
    private final List<BluetoothDevice> paired = new ArrayList<>();
    private final List<BluetoothDevice> available = new ArrayList<>();
    private DeviceAdapter pairedAdapter;
    private DeviceAdapter availableAdapter;
    private TextView statusView;
    private TextView debugView;
    private Button scanBtn;
    private CompoundButton btSwitch;
    private View scanProgress;
    private TextView logToggle;
    private final StringBuilder logBuf = new StringBuilder();

    private volatile boolean scanning;

    private final BroadcastReceiver btReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                // 只在发现新设备时刷新列表，避免扫描期间每个广播包都全量重建
                if (d != null && found.put(d.getAddress(), d) == null) {
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
                appendLog("PAIRING_REQUEST " + (d == null ? "?" : nameOf(d))
                        + " variant=" + variant + " key=" + key);
                try {
                    if (variant == BluetoothDevice.PAIRING_VARIANT_PASSKEY_CONFIRMATION
                            || variant == 0) {
                        if (d != null) {
                            d.setPairingConfirmation(true);
                            abortBroadcast();
                            appendLog("setPairingConfirmation(true) OK");
                        }
                    }
                } catch (Throwable t) {
                    NLog.e(TAG, "pairing confirm fail", t);
                    appendLog("setPairingConfirmation 失败: " + t);
                }
            } else if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                refreshAll();
            } else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
                BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                int prev = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1);
                int now = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1);
                // 隐藏 extra：bond 失败/解除原因（1=AUTH_FAILED 2=AUTH_REJECTED 3=AUTH_CANCELED
                // 4=REMOTE_DEVICE_DOWN 5=DISCOVERY_IN_PROGRESS 6=AUTH_TIMEOUT
                // 7=REPEATED_ATTEMPTS 8=REMOTE_AUTH_CANCELED 9=REMOVED）
                int reason = intent.getIntExtra("android.bluetooth.device.extra.REASON", -1);
                appendLog("BOND " + (d == null ? "?" : nameOf(d)) + " " + bondName(prev)
                        + " -> " + bondName(now) + (reason >= 0 ? " reason=" + reason : ""));
                refreshList();
            }
        }
    };

    private final ScanCallback bleScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice d = result.getDevice();
            // 配对模式下的手柄广播可能不带名字，不能按 getName() 过滤；
            // 同一设备的重复广播不触发刷新
            if (d != null && found.put(d.getAddress(), d) == null) {
                runOnUiThread(BluetoothGamepadActivity.this::refreshList);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            NLog.e(TAG, "BLE scan failed: " + errorCode);
            runOnUiThread(() -> appendLog("BLE 扫描失败 errorCode=" + errorCode
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
        btSwitch = findViewById(R.id.bt_switch);
        scanProgress = findViewById(R.id.bt_scan_progress);
        logToggle = findViewById(R.id.bt_log_toggle);
        ListView pairedList = findViewById(R.id.bt_paired_list);
        ListView availableList = findViewById(R.id.bt_available_list);

        pairedAdapter = new DeviceAdapter(paired, true);
        availableAdapter = new DeviceAdapter(available, false);
        pairedList.setAdapter(pairedAdapter);
        availableList.setAdapter(availableAdapter);
        pairedList.setOnItemClickListener((p, v, pos, id) -> onDeviceClick(paired.get(pos)));
        pairedList.setOnItemLongClickListener((p, v, pos, id) -> {
            onDeviceLongClick(paired.get(pos));
            return true;
        });
        availableList.setOnItemClickListener(
                (p, v, pos, id) -> onDeviceClick(available.get(pos)));

        btSwitch.setOnCheckedChangeListener((b, on) -> {
            if (adapter == null) {
                return;
            }
            try {
                if (on != adapter.isEnabled()) {
                    if (on) {
                        adapter.enable();
                    } else {
                        adapter.disable();
                    }
                }
            } catch (SecurityException e) {
                toast(R.string.bt_no_bt_perm);
            }
        });
        scanBtn.setOnClickListener(v -> toggleScan());
        logToggle.setOnClickListener(v -> {
            boolean show = debugView.getVisibility() != View.VISIBLE;
            debugView.setVisibility(show ? View.VISIBLE : View.GONE);
            if (show) {
                debugView.setText(logBuf);
            }
            logToggle.setText(show ? R.string.bt_hide_log : R.string.bt_show_log);
        });

        IntentFilter f = new IntentFilter();
        f.addAction(BluetoothDevice.ACTION_FOUND);
        f.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        f.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        f.addAction(BluetoothDevice.ACTION_PAIRING_REQUEST);
        f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        registerReceiver(btReceiver, f);
    }

    /** 调试：系统 HID 通路连上后手柄按键以 KeyEvent/MotionEvent 到达，日志区展开时显示 */
    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent e) {
        if (KeyboardController.isGamepadSource(e)
                && e.getKeyCode() != android.view.KeyEvent.KEYCODE_BACK) {
            if (isLogVisible() && e.getAction() == android.view.KeyEvent.ACTION_DOWN
                    && e.getRepeatCount() == 0) {
                appendLog("KEY " + android.view.KeyEvent.keyCodeToString(e.getKeyCode())
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
            if (isLogVisible()) {
                appendLog(String.format(Locale.US,
                        "AXIS x=%.2f y=%.2f hatx=%.1f haty=%.1f dev=%d",
                        e.getAxisValue(android.view.MotionEvent.AXIS_X),
                        e.getAxisValue(android.view.MotionEvent.AXIS_Y),
                        e.getAxisValue(android.view.MotionEvent.AXIS_HAT_X),
                        e.getAxisValue(android.view.MotionEvent.AXIS_HAT_Y),
                        e.getDeviceId()));
            }
            return true;
        }
        return super.dispatchGenericMotionEvent(e);
    }

    private boolean isLogVisible() {
        return debugView.getVisibility() == View.VISIBLE;
    }

    private static String bondName(int s) {
        switch (s) {
            case BluetoothDevice.BOND_NONE: return "NONE";
            case BluetoothDevice.BOND_BONDING: return "BONDING";
            case BluetoothDevice.BOND_BONDED: return "BONDED";
            default: return String.valueOf(s);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        GamepadManager.get().addDebugListener(this);
        GamepadManager.get().addConnStateListener(this);
        refreshAll();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 页面退到后台即停扫，避免 inquiry+BLE 循环耗电
        stopScan();
        updateButtons();
        GamepadManager.get().removeDebugListener(this);
        GamepadManager.get().removeConnStateListener(this);
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
            toast(R.string.bt_no_bt_device);
            return;
        }
        if (!adapter.isEnabled()) {
            toast(R.string.bt_enable_first);
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
            return;
        }
        if (!isLocationOn()) {
            toast(R.string.bt_location_off);
        }
        found.clear();
        refreshList();
        try {
            boolean inq = adapter.startDiscovery();
            appendLog("经典扫描已启动: " + inq);
            BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
            if (scanner != null) {
                scanner.startScan(bleScanCallback);
                appendLog("BLE 扫描已启动");
            } else {
                appendLog("BLE 扫描器不可用（蓝牙未就绪?）");
            }
            scanning = true;
            updateButtons();
        } catch (SecurityException e) {
            toast(R.string.bt_missing_perm);
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
                toast(getString(R.string.bt_pairing, nameOf(d)));
                d.createBond();
            }
            boolean ble = d.getType() == BluetoothDevice.DEVICE_TYPE_LE
                    || d.getType() == BluetoothDevice.DEVICE_TYPE_DUAL;
            GamepadManager.get().connect(d, ble);
            refreshList();
            toast(getString(R.string.bt_connecting, nameOf(d))
                    + (ble ? " (BLE)" : getString(R.string.bt_kind_classic)));
        } catch (SecurityException e) {
            toast(R.string.bt_missing_perm);
        }
    }

    /** 长按已配对设备：断开连接 / 取消配对 */
    private void onDeviceLongClick(BluetoothDevice d) {
        String[] items = {getString(R.string.bt_disconnect),
                getString(R.string.bt_unpair)};
        new AlertDialog.Builder(this)
                .setTitle(nameOf(d))
                .setItems(items, (dlg, which) -> {
                    if (which == 0) {
                        GamepadManager.get().disconnect();
                    } else {
                        unpair(d);
                    }
                    refreshList();
                }).show();
    }

    private void unpair(BluetoothDevice d) {
        try {
            d.getClass().getMethod("removeBond").invoke(d);
            toast(getString(R.string.bt_unpaired, nameOf(d)));
        } catch (Throwable t) {
            toast(getString(R.string.bt_unpair_failed) + ": " + t);
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
        scanBtn.setText(scanning ? R.string.bt_stop_scan : R.string.bt_scan);
        scanProgress.setVisibility(scanning ? View.VISIBLE : View.GONE);
        if (btSwitch != null && adapter != null) {
            btSwitch.setChecked(adapter.isEnabled());
        }
        updateStatus();
    }

    private void updateStatus() {
        StringBuilder sb = new StringBuilder();
        if (adapter == null) {
            sb.append(getString(R.string.bt_no_adapter));
        } else if (!adapter.isEnabled()) {
            sb.append(getString(R.string.bt_off));
        } else {
            sb.append(getString(R.string.bt_on));
            String g = GamepadManager.get().getStatus();
            if (g != null && g.length() > 0) {
                sb.append(" · ").append(getString(R.string.bt_pad_state, g));
            }
        }
        statusView.setText(sb.toString());
    }

    /** 追加一行调试信息（新行在前，上限 2000 字符），日志区可见时刷新显示 */
    private void appendLog(String line) {
        android.util.Log.i(TAG, line);
        logBuf.insert(0, line + '\n');
        if (logBuf.length() > 2000) {
            logBuf.setLength(2000);
        }
        if (isLogVisible()) {
            debugView.setText(logBuf);
        }
    }

    private void refreshList() {
        paired.clear();
        available.clear();
        if (adapter != null && adapter.isEnabled()) {
            try {
                paired.addAll(adapter.getBondedDevices());
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
            available.add(d);
        }
        pairedAdapter.notifyDataSetChanged();
        availableAdapter.notifyDataSetChanged();
        updateStatus();
    }

    /** 设备行适配器：名称 + 地址/类型，已配对行右侧显示连接状态 */
    private class DeviceAdapter extends ArrayAdapter<BluetoothDevice> {
        private final boolean showState;

        DeviceAdapter(List<BluetoothDevice> items, boolean showState) {
            super(BluetoothGamepadActivity.this, R.layout.bt_device_item, items);
            this.showState = showState;
        }

        @NonNull
        @Override
        public View getView(int position, View convertView, @NonNull ViewGroup parent) {
            View v = convertView == null ? getLayoutInflater()
                    .inflate(R.layout.bt_device_item, parent, false) : convertView;
            BluetoothDevice d = getItem(position);
            if (d == null) {
                return v;
            }
            ((TextView) v.findViewById(R.id.bt_device_name)).setText(nameOf(d));
            ((TextView) v.findViewById(R.id.bt_device_info))
                    .setText(typeName(d) + " · " + d.getAddress());
            TextView state = v.findViewById(R.id.bt_device_state);
            if (showState && d.getAddress().equals(GamepadManager.get().getConnectedAddr())
                    && GamepadManager.get().getState() == GamepadManager.ConnState.CONNECTED) {
                state.setText(R.string.bt_connected);
            } else {
                state.setText("");
            }
            return v;
        }
    }

    private String typeName(BluetoothDevice d) {
        try {
            BluetoothClass bc = d.getBluetoothClass();
            String major = bc != null
                    && bc.getMajorDeviceClass() == BluetoothClass.Device.Major.PERIPHERAL
                    ? getString(R.string.bt_dev_peripheral) : "";
            int res;
            switch (d.getType()) {
                case BluetoothDevice.DEVICE_TYPE_LE: res = R.string.bt_kind_ble; break;
                case BluetoothDevice.DEVICE_TYPE_DUAL: res = R.string.bt_dev_dual; break;
                default: res = R.string.bt_dev_classic; break;
            }
            String t = getString(res).trim();
            return major.isEmpty() ? t : major + " " + t;
        } catch (SecurityException e) {
            return "?";
        }
    }

    private void toast(int resId) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    /* ---------------- GamepadManager 回调 ---------------- */

    /** 连接状态翻转：刷新状态栏与"已连接"徽标 */
    @Override
    public void onConnState(GamepadManager.ConnState state) {
        updateStatus();
        if (pairedAdapter != null) {
            pairedAdapter.notifyDataSetChanged();
        }
    }

    @Override
    public void onStatus(String status) {
        updateStatus();
        appendLog(status);
    }

    @Override
    public void onRawReport(String hex) {
        appendLog("RAW " + hex);
    }

    @Override
    public void onState(GamepadState s) {
        appendLog(String.format(Locale.US, "axes=%d,%d,%d,%d hat=%d buttons=%08x",
                s.axes[0], s.axes[1], s.axes[2], s.axes[3], s.hat, s.buttons));
    }
}
