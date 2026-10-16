package nostalgia.framework.bt;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.SparseIntArray;
import android.view.InputDevice;
import android.view.KeyEvent;

import java.util.Map;

import nostalgia.framework.EmulatorController;
import nostalgia.framework.controllers.KeyboardController;

/**
 * 手柄按键映射（按设备型号存储）。
 * <p>
 * 系统 HID 通路下，手柄按键以 Android KeyEvent 到达；不同手柄的物理键
 * 对应的 keyCode 差异很大（如 Pro Controller 经 Generic.kl 后严重错位），
 * 因此映射按设备 vendorId/productId 分组持久化，同类手柄共用一份。
 * </p>
 * 每张映射表 = 预设方案（switch/xbox/ps 三选一，按设备自动识别）
 * + 用户自定义覆盖；方向键（十字键/HAT/摇杆，含斜向）为内置预设，
 * 不参与用户绑定。
 */
public class GamepadKeyMap {

    private static final String PREF_PREFIX = "gamepad_keys_";
    private static final String KEY_PRESET = "_preset";

    /** 预设方案标识 */
    public static final String PRESET_SWITCH = "switch";
    public static final String PRESET_XBOX = "xbox";
    public static final String PRESET_PS = "ps";

    public static final String[] PRESETS = {PRESET_SWITCH, PRESET_XBOX, PRESET_PS};
    public static final String[] PRESET_LABELS = {"Switch", "Xbox", "PlayStation"};

    /** 可由用户绑定的模拟器功能码（顺序即设置页行顺序；方向键内置不列出） */
    public static final int[] FUNCTIONS = {
            EmulatorController.KEY_A,
            EmulatorController.KEY_B,
            EmulatorController.KEY_A_TURBO,
            EmulatorController.KEY_B_TURBO,
            EmulatorController.KEY_SELECT,
            EmulatorController.KEY_START,
            KeyboardController.KEY_FAST_FORWARD,
            KeyboardController.KEY_MENU,
            KeyboardController.KEY_BACK,
    };

    /** 与 {@link #FUNCTIONS} 一一对应的功能名 */
    public static final String[] FUNCTION_NAMES = {
            "A", "B", "连发 A", "连发 B",
            "SELECT", "START",
            "快进", "菜单", "退出游戏",
    };

    /**
     * 计算设备分组 key。同类手柄（相同 vid/pid）共用一份映射；
     * 无 vid/pid 时退化为设备名，再退化为 "default"。
     */
    public static String deviceKey(InputDevice device) {
        if (device == null) {
            return "default";
        }
        if (device.getVendorId() != 0 || device.getProductId() != 0) {
            return String.format("%04x_%04x", device.getVendorId(), device.getProductId());
        }
        String name = device.getName();
        return name != null ? "n_" + name : "default";
    }

    /**
     * 物理键显示名（按设备而非预设）：Pro Controller 的键码与键名错位，
     * 直接显示手柄上印的键名；PS/Xbox 用常见物理名；未知名回退 Generic.kl 标签。
     */
    public static String keyLabel(String deviceKey, int keyCode) {
        SparseIntArray labels = deviceKey.startsWith("057e")
                ? switchLabels() : deviceKey.startsWith("054c")
                ? psLabels() : xboxLabels();
        int v = labels.get(keyCode, Integer.MIN_VALUE);
        if (v != Integer.MIN_VALUE) {
            return PHYS_NAMES[v];
        }
        return nostalgia.framework.ui.preferences.KeyboardSettingsActivity
                .getKeyLabel(keyCode);
    }

    /** 物理键名池：值为 PHYS_NAMES 下标 */
    private static final String[] PHYS_NAMES = {
            "A", "B", "X", "Y", "L", "R", "ZL", "ZR", "-", "+",
            "Home", "Capture", "L3", "R3",
            "×", "○", "△", "□", "Share", "Options", "PS",
            "LB", "RB", "LT", "RT", "View", "Menu", "Xbox",
            "十字↑", "十字↓", "十字←", "十字→",
            "L1", "L2", "R1", "R2",
    };

    private static final int P_A = 0, P_B = 1, P_X = 2, P_Y = 3, P_L = 4,
            P_R = 5, P_ZL = 6, P_ZR = 7, P_MINUS = 8, P_PLUS = 9,
            P_HOME = 10, P_CAPTURE = 11, P_L3 = 12, P_R3 = 13,
            P_CROSS = 14, P_CIRCLE = 15, P_TRI = 16, P_SQUARE = 17,
            P_SHARE = 18, P_OPTIONS = 19, P_PS = 20,
            P_LB = 21, P_RB = 22, P_LT = 23, P_RT = 24, P_VIEW = 25,
            P_MENU = 26, P_XBOX = 27,
            P_DUP = 28, P_DDOWN = 29, P_DLEFT = 30, P_DRIGHT = 31,
            P_L1 = 32, P_L2 = 33, P_R1 = 34, P_R2 = 35;

    private static SparseIntArray switchLabels() {
        SparseIntArray m = new SparseIntArray();
        m.put(KeyEvent.KEYCODE_BUTTON_X, P_A);
        m.put(KeyEvent.KEYCODE_BUTTON_B, P_B);
        m.put(KeyEvent.KEYCODE_BUTTON_A, P_X);
        m.put(KeyEvent.KEYCODE_BUTTON_C, P_Y);
        m.put(KeyEvent.KEYCODE_BUTTON_L1, P_R);
        m.put(KeyEvent.KEYCODE_BUTTON_R1, P_ZR);
        m.put(KeyEvent.KEYCODE_BUTTON_L2, P_MINUS);
        m.put(KeyEvent.KEYCODE_BUTTON_R2, P_PLUS);
        m.put(KeyEvent.KEYCODE_BUTTON_MODE, P_HOME);
        m.put(KeyEvent.KEYCODE_BUTTON_THUMBL, P_CAPTURE);
        m.put(KeyEvent.KEYCODE_BUTTON_10, P_R3);
        m.put(KeyEvent.KEYCODE_BUTTON_11, P_L3);
        addDpadLabels(m);
        return m;
    }

    private static SparseIntArray psLabels() {
        SparseIntArray m = new SparseIntArray();
        m.put(KeyEvent.KEYCODE_BUTTON_A, P_CROSS);
        m.put(KeyEvent.KEYCODE_BUTTON_B, P_CIRCLE);
        m.put(KeyEvent.KEYCODE_BUTTON_X, P_TRI);
        m.put(KeyEvent.KEYCODE_BUTTON_Y, P_SQUARE);
        m.put(KeyEvent.KEYCODE_BUTTON_L1, P_L1);
        m.put(KeyEvent.KEYCODE_BUTTON_R1, P_R1);
        m.put(KeyEvent.KEYCODE_BUTTON_L2, P_L2);
        m.put(KeyEvent.KEYCODE_BUTTON_R2, P_R2);
        m.put(KeyEvent.KEYCODE_BUTTON_SELECT, P_SHARE);
        m.put(KeyEvent.KEYCODE_BUTTON_START, P_OPTIONS);
        m.put(KeyEvent.KEYCODE_BUTTON_MODE, P_PS);
        m.put(KeyEvent.KEYCODE_BUTTON_THUMBL, P_L3);
        m.put(KeyEvent.KEYCODE_BUTTON_THUMBR, P_R3);
        addDpadLabels(m);
        return m;
    }

    private static SparseIntArray xboxLabels() {
        SparseIntArray m = new SparseIntArray();
        m.put(KeyEvent.KEYCODE_BUTTON_A, P_A);
        m.put(KeyEvent.KEYCODE_BUTTON_B, P_B);
        m.put(KeyEvent.KEYCODE_BUTTON_X, P_X);
        m.put(KeyEvent.KEYCODE_BUTTON_Y, P_Y);
        m.put(KeyEvent.KEYCODE_BUTTON_L1, P_LB);
        m.put(KeyEvent.KEYCODE_BUTTON_R1, P_RB);
        m.put(KeyEvent.KEYCODE_BUTTON_L2, P_LT);
        m.put(KeyEvent.KEYCODE_BUTTON_R2, P_RT);
        m.put(KeyEvent.KEYCODE_BUTTON_SELECT, P_VIEW);
        m.put(KeyEvent.KEYCODE_BUTTON_START, P_MENU);
        m.put(KeyEvent.KEYCODE_BUTTON_MODE, P_XBOX);
        m.put(KeyEvent.KEYCODE_BUTTON_THUMBL, P_L3);
        m.put(KeyEvent.KEYCODE_BUTTON_THUMBR, P_R3);
        addDpadLabels(m);
        return m;
    }

    private static void addDpadLabels(SparseIntArray m) {
        m.put(KeyEvent.KEYCODE_DPAD_UP, P_DUP);
        m.put(KeyEvent.KEYCODE_DPAD_DOWN, P_DDOWN);
        m.put(KeyEvent.KEYCODE_DPAD_LEFT, P_DLEFT);
        m.put(KeyEvent.KEYCODE_DPAD_RIGHT, P_DRIGHT);
    }

    private static SharedPreferences pref(Context context, String deviceKey) {
        return context.getSharedPreferences(PREF_PREFIX + deviceKey, Context.MODE_PRIVATE);
    }

    /** 该设备当前选用的预设；未设置时按 vid/pid 自动识别。 */
    public static String presetFor(Context context, String deviceKey) {
        String p = pref(context, deviceKey).getString(KEY_PRESET, null);
        return p != null ? p : autoPreset(deviceKey);
    }

    public static void setPreset(Context context, String deviceKey, String preset) {
        pref(context, deviceKey).edit().putString(KEY_PRESET, preset).apply();
    }

    /** 按厂商自动选择预设：057e=Nintendo→switch，054c=Sony→ps，045e=Microsoft→xbox。 */
    private static String autoPreset(String deviceKey) {
        if (deviceKey.startsWith("057e")) {
            return PRESET_SWITCH;
        }
        if (deviceKey.startsWith("054c")) {
            return PRESET_PS;
        }
        return PRESET_XBOX;
    }

    /** 加载有效映射 = 预设表 + 用户覆盖（-1 条目表示显式解绑）。 */
    public static SparseIntArray load(Context context, String deviceKey) {
        SparseIntArray map = presetTable(presetFor(context, deviceKey));
        SparseIntArray ov = loadOverrides(context, deviceKey);
        for (int i = 0; i < ov.size(); i++) {
            int keyCode = ov.keyAt(i);
            int emu = ov.valueAt(i);
            if (emu < 0) {
                map.delete(keyCode);
            } else {
                map.put(keyCode, emu);
            }
        }
        return map;
    }

    /** 仅用户自定义条目（含 -1 解绑标记），不含预设默认值。 */
    public static SparseIntArray loadOverrides(Context context, String deviceKey) {
        SparseIntArray map = new SparseIntArray();
        for (Map.Entry<String, ?> e : pref(context, deviceKey).getAll().entrySet()) {
            if (e.getValue() instanceof Integer) {
                try {
                    map.put(Integer.parseInt(e.getKey()), (Integer) e.getValue());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return map;
    }

    /** 仅预设表（含方向键内置映射）。 */
    public static SparseIntArray loadDefaults(Context context, String deviceKey) {
        return presetTable(presetFor(context, deviceKey));
    }

    /** 保存单个绑定；emuCode 传 -1 表示解绑该物理键。 */
    public static void bind(Context context, String deviceKey, int keyCode, int emuCode) {
        pref(context, deviceKey).edit().putInt(String.valueOf(keyCode), emuCode).apply();
    }

    /** 清除该设备的全部自定义与预设选择，恢复自动识别默认值。 */
    public static void reset(Context context, String deviceKey) {
        pref(context, deviceKey).edit().clear().apply();
    }

    /**
     * 预设映射表（可被 KeyboardProfile 的同名预设复用，保持单一数据源）。
     * 方向（含斜向）通过十字键 HAT 的 DPAD_* 键码预设，
     * 左摇杆由 KeyboardController 的摇杆转方向逻辑处理。
     */
    public static SparseIntArray presetTable(String preset) {
        SparseIntArray map = new SparseIntArray();
        map.put(KeyEvent.KEYCODE_DPAD_UP, EmulatorController.KEY_UP);
        map.put(KeyEvent.KEYCODE_DPAD_DOWN, EmulatorController.KEY_DOWN);
        map.put(KeyEvent.KEYCODE_DPAD_LEFT, EmulatorController.KEY_LEFT);
        map.put(KeyEvent.KEYCODE_DPAD_RIGHT, EmulatorController.KEY_RIGHT);
        switch (preset) {
            case PRESET_SWITCH:
                // Pro Controller 实测错位表（usage 按报告 bit 序编号）：
                // A→BUTTON_X B→BUTTON_B X→BUTTON_A Y→BUTTON_C，
                // R/ZR→L1/R1，-/+→L2/R2，Home→MODE，Capture→THUMBL，
                // L3→BUTTON_11（L/ZL 无事件，故肩键均不预设，可自行绑定）
                map.put(KeyEvent.KEYCODE_BUTTON_X, EmulatorController.KEY_A);
                map.put(KeyEvent.KEYCODE_BUTTON_B, EmulatorController.KEY_B);
                map.put(KeyEvent.KEYCODE_BUTTON_A, EmulatorController.KEY_A_TURBO);
                map.put(KeyEvent.KEYCODE_BUTTON_C, EmulatorController.KEY_B_TURBO);
                map.put(KeyEvent.KEYCODE_BUTTON_L2, EmulatorController.KEY_SELECT);
                map.put(KeyEvent.KEYCODE_BUTTON_R2, EmulatorController.KEY_START);
                map.put(KeyEvent.KEYCODE_BUTTON_MODE, KeyboardController.KEY_MENU);
                map.put(KeyEvent.KEYCODE_BUTTON_THUMBL, KeyboardController.KEY_FAST_FORWARD);
                map.put(KeyEvent.KEYCODE_BUTTON_11, KeyboardController.KEY_FAST_FORWARD);
                break;
            case PRESET_PS:
                // PS 布局：×=A ○=B △=连发A □=连发B
                map.put(KeyEvent.KEYCODE_BUTTON_A, EmulatorController.KEY_A);
                map.put(KeyEvent.KEYCODE_BUTTON_B, EmulatorController.KEY_B);
                map.put(KeyEvent.KEYCODE_BUTTON_X, EmulatorController.KEY_A_TURBO);
                map.put(KeyEvent.KEYCODE_BUTTON_Y, EmulatorController.KEY_B_TURBO);
                map.put(KeyEvent.KEYCODE_BUTTON_L1, EmulatorController.KEY_B);
                map.put(KeyEvent.KEYCODE_BUTTON_R1, EmulatorController.KEY_A);
                map.put(KeyEvent.KEYCODE_BUTTON_SELECT, EmulatorController.KEY_SELECT);
                map.put(KeyEvent.KEYCODE_BUTTON_START, EmulatorController.KEY_START);
                map.put(KeyEvent.KEYCODE_BUTTON_L2, KeyboardController.KEY_FAST_FORWARD);
                map.put(KeyEvent.KEYCODE_BUTTON_R2, KeyboardController.KEY_MENU);
                map.put(KeyEvent.KEYCODE_BUTTON_MODE, KeyboardController.KEY_MENU);
                break;
            case PRESET_XBOX:
            default:
                // Xbox/通用布局：A=A B=B X=连发A Y=连发B
                map.put(KeyEvent.KEYCODE_BUTTON_A, EmulatorController.KEY_A);
                map.put(KeyEvent.KEYCODE_BUTTON_B, EmulatorController.KEY_B);
                map.put(KeyEvent.KEYCODE_BUTTON_X, EmulatorController.KEY_A_TURBO);
                map.put(KeyEvent.KEYCODE_BUTTON_Y, EmulatorController.KEY_B_TURBO);
                map.put(KeyEvent.KEYCODE_BUTTON_L1, EmulatorController.KEY_B);
                map.put(KeyEvent.KEYCODE_BUTTON_R1, EmulatorController.KEY_A);
                map.put(KeyEvent.KEYCODE_BUTTON_SELECT, EmulatorController.KEY_SELECT);
                map.put(KeyEvent.KEYCODE_BUTTON_START, EmulatorController.KEY_START);
                map.put(KeyEvent.KEYCODE_BUTTON_10, EmulatorController.KEY_SELECT);
                map.put(KeyEvent.KEYCODE_BUTTON_11, EmulatorController.KEY_START);
                map.put(KeyEvent.KEYCODE_BUTTON_L2, KeyboardController.KEY_FAST_FORWARD);
                map.put(KeyEvent.KEYCODE_BUTTON_R2, KeyboardController.KEY_MENU);
                map.put(KeyEvent.KEYCODE_BUTTON_MODE, KeyboardController.KEY_MENU);
                break;
        }
        return map;
    }
}
