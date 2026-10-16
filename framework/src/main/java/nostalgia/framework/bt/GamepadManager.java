package nostalgia.framework.bt;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;

import nostalgia.framework.EmulatorController;
import nostalgia.framework.utils.NLog;

/**
 * 蓝牙手柄连接与输入分发中枢（单例）。
 * <p>
 * 车机系统蓝牙未启用 HID host profile，手柄输入无法走系统 InputDevice 通路，
 * 这里由应用内直连（BLE GATT / 经典 L2CAP）解码 HID 报告，
 * 再将按键事件转发给 {@link KeySink}（模拟器侧由 BtGamepadController 接收）。
 * </p>
 */
public class GamepadManager {

    public interface KeySink {
        void onKey(int emuKey, boolean pressed);
    }

    public interface DebugListener {
        /** 连接/输入诊断日志行，UI 线程 */
        void onStatus(String status);

        /** 收到原始报告（hex），UI 线程 */
        void onRawReport(String hex);

        /** 解析后的状态变化，UI 线程 */
        void onState(GamepadState state);
    }

    /** 连接状态变化（仅 ConnState 翻转时回调，UI 线程） */
    public interface ConnStateListener {
        void onConnState(ConnState state);
    }

    public enum ConnState {IDLE, CONNECTING, CONNECTED, ERROR}

    private static final String TAG = "GamepadManager";
    private static final String PREF = "bt_gamepad";
    private static final String KEY_ADDR = "last_addr";
    private static final String KEY_BLE = "last_ble";

    private static volatile GamepadManager instance;

    public static GamepadManager get() {
        if (instance == null) {
            synchronized (GamepadManager.class) {
                if (instance == null) {
                    instance = new GamepadManager();
                }
            }
        }
        return instance;
    }

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final List<DebugListener> debugListeners = new ArrayList<>();
    private final List<ConnStateListener> stateListeners = new ArrayList<>();
    private volatile KeySink keySink;
    private volatile ConnState state = ConnState.IDLE;
    private volatile String status = "";
    private volatile GamepadConnection connection;
    private volatile GamepadState lastState = new GamepadState();
    private volatile String connectedAddr;
    private String connectedName;
    private String lastError;
    private Context appContext;

    public synchronized void init(Context ctx) {
        if (appContext == null) {
            appContext = ctx.getApplicationContext();
        }
    }

    public ConnState getState() {
        return state;
    }

    public String getStatus() {
        return status;
    }

    public void setKeySink(KeySink sink) {
        keySink = sink;
    }

    public void addDebugListener(DebugListener l) {
        synchronized (debugListeners) {
            debugListeners.add(l);
        }
    }

    public void removeDebugListener(DebugListener l) {
        synchronized (debugListeners) {
            debugListeners.remove(l);
        }
    }

    public void addConnStateListener(ConnStateListener l) {
        synchronized (stateListeners) {
            stateListeners.add(l);
        }
    }

    public void removeConnStateListener(ConnStateListener l) {
        synchronized (stateListeners) {
            stateListeners.remove(l);
        }
    }

    /** 当前已连接手柄的 MAC，未连接为 null */
    public String getConnectedAddr() {
        return connectedAddr;
    }

    /** 连接手柄（后台线程执行） */
    public synchronized void connect(android.bluetooth.BluetoothDevice device, boolean ble) {
        disconnectInternal();
        connectedAddr = device.getAddress();
        connectedName = safeName(device);
        setState(ConnState.CONNECTING);
        // 经典蓝牙走系统 HID host；应用内 L2CAP（ClassicGamepadConnection）在此栈上被 ERTM 阻塞
        GamepadConnection conn = ble ? new BleGamepadConnection() : new SystemHidConnection();
        connection = conn;
        conn.connect(appContext, device, this);
    }

    public synchronized void disconnect() {
        disconnectInternal();
        connectedAddr = null;
        setState(ConnState.IDLE);
        postLog("已断开");
    }

    private void disconnectInternal() {
        if (connection != null) {
            connection.close();
            connection = null;
        }
    }

    /** 连接成功后由连接实现调用 */
    public void onConnected(android.bluetooth.BluetoothDevice device, boolean ble) {
        connectedName = safeName(device);
        if (device != null) {
            connectedAddr = device.getAddress();
        }
        setState(ConnState.CONNECTED);
        postLog("已连接: " + connectedName);
        if (appContext != null && device != null) {
            SharedPreferences.Editor e =
                    appContext.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit();
            e.putString(KEY_ADDR, device.getAddress());
            e.putBoolean(KEY_BLE, ble);
            e.apply();
        }
    }

    public void onDisconnected(String reason) {
        if (connection == null) {
            return; // 旧连接的迟到回调，不能覆盖新连接状态
        }
        connection = null;
        connectedAddr = null;
        setState(ConnState.IDLE);
        postLog("断开: " + reason);
    }

    public void onError(String err) {
        lastError = err;
        setState(ConnState.ERROR);
        postLog("错误: " + err);
    }

    /** 连接过程日志（显示在管理页调试区） */
    public void log(String msg) {
        postLog(msg);
    }

    private String safeName(android.bluetooth.BluetoothDevice d) {
        try {
            return (d.getName() != null ? d.getName() : "?") + " " + d.getAddress();
        } catch (SecurityException e) {
            return d.getAddress();
        }
    }

    /** 连接实现喂入一条输入报告 */
    public void onReport(byte[] data) {
        if (!hasDebugListeners()) {
            return;
        }
        postDebugRaw(toHex(data));
    }

    /** 连接实现喂入解析后的状态 */
    public void onState(GamepadState s) {
        if (s.equals(lastState)) {
            return;
        }
        dispatchDiff(lastState, s);
        lastState = s;
        postDebugState(s);
    }

    /** 记住的手柄信息，EmulatorActivity 启动时用于自动重连 */
    public String getSavedDeviceAddr() {
        return appContext == null ? null
                : appContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getString(KEY_ADDR, null);
    }

    public boolean isSavedDeviceBle() {
        return appContext != null && appContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getBoolean(KEY_BLE, false);
    }

    private void setState(ConnState s) {
        if (state == s) {
            return;
        }
        state = s;
        switch (s) {
            case CONNECTED: status = "已连接: " + connectedName; break;
            case CONNECTING: status = "连接中"; break;
            case ERROR: status = "错误: " + lastError; break;
            default: status = ""; break;
        }
        if (hasStateListeners()) {
            uiHandler.post(() -> {
                synchronized (stateListeners) {
                    for (ConnStateListener l : stateListeners) {
                        l.onConnState(state);
                    }
                }
            });
        }
    }

    private boolean hasDebugListeners() {
        synchronized (debugListeners) {
            return !debugListeners.isEmpty();
        }
    }

    private boolean hasStateListeners() {
        synchronized (stateListeners) {
            return !stateListeners.isEmpty();
        }
    }

    private void postLog(String msg) {
        android.util.Log.i(TAG, msg);
        if (!hasDebugListeners()) {
            return;
        }
        uiHandler.post(() -> {
            synchronized (debugListeners) {
                for (DebugListener l : debugListeners) {
                    l.onStatus(msg);
                }
            }
        });
    }

    private void postDebugRaw(String hex) {
        uiHandler.post(() -> {
            synchronized (debugListeners) {
                for (DebugListener l : debugListeners) {
                    l.onRawReport(hex);
                }
            }
        });
    }

    private void postDebugState(GamepadState s) {
        if (!hasDebugListeners()) {
            return;
        }
        uiHandler.post(() -> {
            synchronized (debugListeners) {
                for (DebugListener l : debugListeners) {
                    l.onState(s);
                }
            }
        });
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 3);
        for (byte x : b) {
            sb.append(HEX[(x >> 4) & 0xf]).append(HEX[x & 0xf]).append(' ');
        }
        return sb.toString().trim();
    }

    /* ---------------- 状态差分 → 模拟器按键 ---------------- */

    private static final int DEADZONE = 40; // 轴阈值（-128..127）

    private void dispatchDiff(GamepadState oldS, GamepadState newS) {
        KeySink sink = keySink;
        // hat → 方向：新旧方向集合做差分，只发真正变化的边沿（避免保持住的方向抖断）
        if (oldS.hat != newS.hat) {
            int ob = hatDirs(oldS.hat);
            int nb = hatDirs(newS.hat);
            int changed = ob ^ nb;
            for (int i = 0; i < 4; i++) {
                if ((changed >> i & 1) != 0) {
                    emitKey(sink, dirKey(i), (nb >> i & 1) != 0);
                }
            }
        }
        // 左摇杆 → 方向（与 hat 合并生效：axis 也驱动方向键，摇杆优先作为方向输入）
        boolean[] oldD = axisDirs(oldS.axes[0], oldS.axes[1]);
        boolean[] newD = axisDirs(newS.axes[0], newS.axes[1]);
        for (int i = 0; i < 4; i++) {
            if (oldD[i] != newD[i]) {
                emitKey(sink, dirKey(i), newD[i]);
            }
        }
        // 按钮
        long diff = oldS.buttons ^ newS.buttons;
        for (int i = 0; i < 40; i++) {
            long mask = 1L << i;
            if ((diff & mask) != 0) {
                int emuKey = buttonToKey(i + 1);
                if (emuKey >= 0) {
                    emitKey(sink, emuKey, (newS.buttons & mask) != 0);
                }
            }
        }
    }

    /** hat 值 → 方向位掩码，bit0=up bit1=right bit2=down bit3=left（同 dirKey 序） */
    private static int hatDirs(int hat) {
        switch (hat) {
            case 0: return 1;
            case 1: return 1 | 2;
            case 2: return 2;
            case 3: return 2 | 4;
            case 4: return 4;
            case 5: return 4 | 8;
            case 6: return 8;
            case 7: return 8 | 1;
            default: return 0;
        }
    }

    private static boolean[] axisDirs(int x, int y) {
        // {up, right, down, left}，HID Y 轴向下为正
        return new boolean[]{y < -DEADZONE, x > DEADZONE, y > DEADZONE, x < -DEADZONE};
    }

    private static int dirKey(int i) {
        switch (i) {
            case 0: return EmulatorController.KEY_UP;
            case 1: return EmulatorController.KEY_RIGHT;
            case 2: return EmulatorController.KEY_DOWN;
            default: return EmulatorController.KEY_LEFT;
        }
    }

    private void emitKey(KeySink sink, int key, boolean pressed) {
        if (sink != null) {
            sink.onKey(key, pressed);
        }
    }

    /**
     * HID 按钮编号 → 模拟器键位。
     * 通用手柄约定：1=南(A) 2=东(B) 3=西(X) 4=北(Y) 5/6=肩键 9=Select 10=Start
     * FC 映射：B=连发/单发左键，A=单发右键
     */
    private static int buttonToKey(int hidButton) {
        switch (hidButton) {
            case 1: return EmulatorController.KEY_B;
            case 2: return EmulatorController.KEY_A;
            case 3: return EmulatorController.KEY_B_TURBO;
            case 4: return EmulatorController.KEY_A_TURBO;
            case 5: return EmulatorController.KEY_B;
            case 6: return EmulatorController.KEY_A;
            case 9: return EmulatorController.KEY_SELECT;
            case 10: return EmulatorController.KEY_START;
            case 13: return EmulatorController.KEY_UP;
            case 14: return EmulatorController.KEY_DOWN;
            case 15: return EmulatorController.KEY_LEFT;
            case 16: return EmulatorController.KEY_RIGHT;
            case 33: return EmulatorController.KEY_RIGHT; // 键盘方向键
            case 34: return EmulatorController.KEY_LEFT;
            case 35: return EmulatorController.KEY_DOWN;
            case 36: return EmulatorController.KEY_UP;
            case 37: return EmulatorController.KEY_B;     // z
            case 38: return EmulatorController.KEY_A;     // x
            case 39: return EmulatorController.KEY_START; // enter
            case 40: return EmulatorController.KEY_SELECT;// esc
            default: return -1;
        }
    }
}
