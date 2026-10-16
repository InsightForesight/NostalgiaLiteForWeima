package nostalgia.framework.controllers;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.util.SparseIntArray;
import android.view.View;

import nostalgia.framework.Emulator;
import nostalgia.framework.EmulatorController;
import nostalgia.framework.base.EmulatorActivity;
import nostalgia.framework.bt.GamepadManager;
import nostalgia.framework.ui.gamegallery.GameDescription;
import nostalgia.framework.utils.NLog;

/**
 * 蓝牙手柄输入控制器。
 * <p>
 * 车机系统蓝牙未启用 HID host profile，手柄按键无法走系统 InputDevice，
 * 这里把 {@link GamepadManager}（应用内 BLE GATT / 经典 L2CAP 直连）
 * 解码出的按键事件接入模拟器按键管线。
 * </p>
 * 键位约定（HID 通用）：南键=B、东键=A、西/北键=连发 B/A、
 * 肩键=B/A、Select=SELECT、Start=START、十字键/左摇杆=方向。
 */
public class BtGamepadController implements EmulatorController, GamepadManager.KeySink {

    private static final String TAG = "BtGamepadController";

    private final EmulatorActivity activity;
    private Emulator emulator;
    private int port;
    private SparseIntArray mapping;

    public BtGamepadController(EmulatorActivity activity) {
        this.activity = activity;
    }

    @Override
    public void connectToEmulator(int port, Emulator emulator) {
        this.emulator = emulator;
        this.port = port;
        mapping = emulator.getInfo().getKeyMapping();
        GamepadManager mgr = GamepadManager.get();
        mgr.init(activity);
        mgr.setKeySink(this);
        autoConnect(mgr);
    }

    /** 进入游戏时若手柄已配置过则自动重连 */
    private void autoConnect(GamepadManager mgr) {
        if (mgr.getState() != GamepadManager.ConnState.IDLE) {
            return;
        }
        String addr = mgr.getSavedDeviceAddr();
        if (addr == null) {
            return;
        }
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                return;
            }
            BluetoothDevice device = adapter.getRemoteDevice(addr);
            if (device != null) {
                NLog.i(TAG, "auto reconnect " + addr);
                mgr.connect(device, mgr.isSavedDeviceBle());
            }
        } catch (Throwable t) {
            NLog.e(TAG, "auto connect fail", t);
        }
    }

    @Override
    public void onKey(int emuKey, boolean pressed) {
        if (emulator == null || mapping == null || mapping.indexOfKey(emuKey) < 0) {
            return;
        }
        emulator.setKeyPressed(port, mapping.get(emuKey), pressed);
    }

    @Override
    public void onResume() {
    }

    @Override
    public void onPause() {
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
    }

    @Override
    public void onGameStarted(GameDescription game) {
    }

    @Override
    public void onGamePaused(GameDescription game) {
        // 游戏暂停时释放所有按键，避免恢复后按键卡死
        if (emulator != null) {
            emulator.resetKeys();
        }
    }

    @Override
    public View getView() {
        return null;
    }

    @Override
    public void onDestroy() {
        GamepadManager.get().setKeySink(null);
        emulator = null;
        mapping = null;
    }
}
