package nostalgia.framework.bt;

import android.util.SparseIntArray;
import android.view.InputDevice;
import android.view.KeyEvent;

import nostalgia.framework.EmulatorController;
import nostalgia.framework.controllers.KeyboardController;
import nostalgia.framework.utils.KeyLabels;

/**
 * 手柄按键映射的辅助层。
 * <p>
 * 实际映射数据存于 {@link nostalgia.framework.KeyboardProfile}（预设名
 * switch/xbox/ps，可在键盘/手柄设置页编辑）；本类只负责：
 * <ul>
 *   <li>按 InputDevice 的 vid/pid 计算设备分组并自动选择预设</li>
 *   <li>预设表（presetTable）——KeyboardProfile 预设的单一数据源</li>
 *   <li>物理键显示名（Pro Controller 键码与键名错位，直接显示印刷键名）</li>
 * </ul>
 * 方向键（十字键 HAT 的 DPAD_* 与左摇杆斜向）为预设内置，左摇杆由
 * KeyboardController 的摇杆转方向逻辑处理。
 * </p>
 */
public class GamepadKeyMap {

    /** 预设方案标识（与 KeyboardProfile 预设同名） */
    public static final String PRESET_SWITCH = "switch";
    public static final String PRESET_XBOX = "xbox";
    public static final String PRESET_PS = "ps";

    private static final String VID_NINTENDO = "057e";
    private static final String VID_SONY = "054c";

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

    /** 按厂商自动选择预设：Nintendo→switch，Sony→ps，其它→xbox。 */
    public static String autoPreset(String deviceKey) {
        if (deviceKey.startsWith(VID_NINTENDO)) {
            return PRESET_SWITCH;
        }
        if (deviceKey.startsWith(VID_SONY)) {
            return PRESET_PS;
        }
        return PRESET_XBOX;
    }

    /** profile 名是否为手柄预设（决定设置页是否显示物理键名）。 */
    public static boolean isGamepadPreset(String profileName) {
        return PRESET_SWITCH.equals(profileName) || PRESET_XBOX.equals(profileName)
                || PRESET_PS.equals(profileName);
    }

    /**
     * 物理键显示名：按预设族而非设备区分（编辑 switch 预设即显示 Switch 键名）。
     * Pro Controller 的键码与键名错位，直接显示手柄上印的键名；
     * 未知名回退 Generic.kl 标签。
     */
    public static String labelForPreset(String preset, int keyCode) {
        SparseIntArray labels = PRESET_SWITCH.equals(preset) ? SWITCH_LABELS
                : PRESET_PS.equals(preset) ? PS_LABELS : XBOX_LABELS;
        int v = labels.get(keyCode, Integer.MIN_VALUE);
        if (v != Integer.MIN_VALUE) {
            return PHYS_NAMES[v];
        }
        return KeyLabels.getKeyLabel(keyCode);
    }

    /** 物理键名池：值为 PHYS_NAMES 下标 */
    private static final String[] PHYS_NAMES = {
            "A", "B", "X", "Y", "L", "R", "ZL", "ZR", "-", "+",
            "Home", "Capture", "L3", "R3",
            "×", "○", "△", "□", "Share", "Options", "PS",
            "LB", "RB", "LT", "RT", "View", "Menu", "Xbox",
            "Up", "Down", "Left", "Right",
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

    private static final SparseIntArray SWITCH_LABELS = switchLabels();
    private static final SparseIntArray PS_LABELS = psLabels();
    private static final SparseIntArray XBOX_LABELS = xboxLabels();

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

    /**
     * 预设映射表（KeyboardProfile 预设的单一数据源）。
     * 每个功能只绑一个物理键（与设置页 1:1 编辑模型一致）；
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
                // L3→BUTTON_11（L/ZL 无事件；肩键/摇杆键不预设，可自行绑定）
                map.put(KeyEvent.KEYCODE_BUTTON_X, EmulatorController.KEY_A);
                map.put(KeyEvent.KEYCODE_BUTTON_B, EmulatorController.KEY_B);
                map.put(KeyEvent.KEYCODE_BUTTON_A, EmulatorController.KEY_A_TURBO);
                map.put(KeyEvent.KEYCODE_BUTTON_C, EmulatorController.KEY_B_TURBO);
                map.put(KeyEvent.KEYCODE_BUTTON_L2, EmulatorController.KEY_SELECT);
                map.put(KeyEvent.KEYCODE_BUTTON_R2, EmulatorController.KEY_START);
                map.put(KeyEvent.KEYCODE_BUTTON_MODE, KeyboardController.KEY_MENU);
                map.put(KeyEvent.KEYCODE_BUTTON_THUMBL,
                        KeyboardController.KEY_FAST_FORWARD);
                break;
            case PRESET_PS:
            case PRESET_XBOX:
            default:
                // PS/Xbox/通用布局：南键=A 东键=B 西键=连发A 北键=连发B
                map.put(KeyEvent.KEYCODE_BUTTON_A, EmulatorController.KEY_A);
                map.put(KeyEvent.KEYCODE_BUTTON_B, EmulatorController.KEY_B);
                map.put(KeyEvent.KEYCODE_BUTTON_X, EmulatorController.KEY_A_TURBO);
                map.put(KeyEvent.KEYCODE_BUTTON_Y, EmulatorController.KEY_B_TURBO);
                map.put(KeyEvent.KEYCODE_BUTTON_SELECT, EmulatorController.KEY_SELECT);
                map.put(KeyEvent.KEYCODE_BUTTON_START, EmulatorController.KEY_START);
                map.put(KeyEvent.KEYCODE_BUTTON_L2, KeyboardController.KEY_FAST_FORWARD);
                map.put(KeyEvent.KEYCODE_BUTTON_MODE, KeyboardController.KEY_MENU);
                break;
        }
        return map;
    }
}
