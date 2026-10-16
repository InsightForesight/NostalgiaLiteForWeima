package nostalgia.framework.utils;

import android.annotation.SuppressLint;
import android.util.SparseArray;
import android.view.KeyEvent;

import nostalgia.framework.controllers.KeyboardController;

/**
 * 按键码 → 显示标签的通用工具（Generic.kl 风格键名）。
 * <p>
 * 手柄物理键名（A/B/ZR/- 等）由 {@link nostalgia.framework.bt.GamepadKeyMap}
 * 按预设覆盖，本表是未覆盖键码的回退来源。
 * </p>
 */
public final class KeyLabels {

    private static final SparseArray<String> NON_PRINTABLE_KEY_LABELS = new SparseArray<>();

    static {
        initNonPrintMap();
    }

    private KeyLabels() {
    }

    @SuppressLint("InlinedApi")
    private static void initNonPrintMap() {
        NON_PRINTABLE_KEY_LABELS.put(KeyEvent.KEYCODE_ENTER, "Enter");
        NON_PRINTABLE_KEY_LABELS.put(KeyEvent.KEYCODE_SPACE, "Space");
        NON_PRINTABLE_KEY_LABELS.put(KeyEvent.KEYCODE_DPAD_LEFT, "Left");
        NON_PRINTABLE_KEY_LABELS.put(KeyEvent.KEYCODE_DPAD_RIGHT, "Right");
        NON_PRINTABLE_KEY_LABELS.put(KeyEvent.KEYCODE_DPAD_UP, "Up");
        NON_PRINTABLE_KEY_LABELS.put(KeyEvent.KEYCODE_DPAD_DOWN, "Down");
        NON_PRINTABLE_KEY_LABELS.put(KeyboardController.KEY_XPERIA_CIRCLE, "Circle");
        NON_PRINTABLE_KEY_LABELS.put(KeyEvent.KEYCODE_BUTTON_A, "A");
        NON_PRINTABLE_KEY_LABELS.put(KeyEvent.KEYCODE_BUTTON_B, "B");
        NON_PRINTABLE_KEY_LABELS.put(KeyEvent.KEYCODE_BUTTON_C, "C");
        NON_PRINTABLE_KEY_LABELS.put(KeyEvent.KEYCODE_BUTTON_X, "X");
        NON_PRINTABLE_KEY_LABELS.put(KeyEvent.KEYCODE_BUTTON_Y, "Y");
        NON_PRINTABLE_KEY_LABELS.put(KeyEvent.KEYCODE_BUTTON_Z, "Z");
        NON_PRINTABLE_KEY_LABELS.put(KeyEvent.KEYCODE_BUTTON_SELECT, "Select");
        NON_PRINTABLE_KEY_LABELS.put(KeyEvent.KEYCODE_BUTTON_START, "Start");
        NON_PRINTABLE_KEY_LABELS.put(KeyEvent.KEYCODE_BUTTON_MODE, "MODE");
        NON_PRINTABLE_KEY_LABELS.put(KeyEvent.KEYCODE_BUTTON_THUMBL, "THUMBL");
        NON_PRINTABLE_KEY_LABELS.put(KeyEvent.KEYCODE_BUTTON_THUMBR, "THUMBR");
        NON_PRINTABLE_KEY_LABELS.put(KeyEvent.KEYCODE_BUTTON_1, "1");
        NON_PRINTABLE_KEY_LABELS.put(KeyEvent.KEYCODE_BUTTON_2, "2");
        NON_PRINTABLE_KEY_LABELS.put(KeyEvent.KEYCODE_BUTTON_3, "3");
        NON_PRINTABLE_KEY_LABELS.put(KeyEvent.KEYCODE_BUTTON_4, "4");
        NON_PRINTABLE_KEY_LABELS.put(KeyEvent.KEYCODE_BUTTON_5, "5");
        NON_PRINTABLE_KEY_LABELS.put(KeyEvent.KEYCODE_BUTTON_6, "6");
        NON_PRINTABLE_KEY_LABELS.put(KeyEvent.KEYCODE_BUTTON_7, "7");
        NON_PRINTABLE_KEY_LABELS.put(KeyEvent.KEYCODE_BUTTON_8, "8");
        NON_PRINTABLE_KEY_LABELS.put(KeyEvent.KEYCODE_BUTTON_9, "9");
        NON_PRINTABLE_KEY_LABELS.put(KeyEvent.KEYCODE_BUTTON_10, "10");
        NON_PRINTABLE_KEY_LABELS.put(KeyEvent.KEYCODE_BUTTON_11, "11");
        NON_PRINTABLE_KEY_LABELS.put(KeyEvent.KEYCODE_BUTTON_12, "12");
        NON_PRINTABLE_KEY_LABELS.put(KeyEvent.KEYCODE_BUTTON_13, "13");
        NON_PRINTABLE_KEY_LABELS.put(KeyEvent.KEYCODE_BUTTON_14, "14");
        NON_PRINTABLE_KEY_LABELS.put(KeyEvent.KEYCODE_BUTTON_15, "15");
        NON_PRINTABLE_KEY_LABELS.put(KeyEvent.KEYCODE_BUTTON_16, "16");
        NON_PRINTABLE_KEY_LABELS.put(KeyEvent.KEYCODE_BUTTON_R1, "R1");
        NON_PRINTABLE_KEY_LABELS.put(KeyEvent.KEYCODE_BUTTON_R2, "R2");
        NON_PRINTABLE_KEY_LABELS.put(KeyEvent.KEYCODE_BUTTON_L1, "L1");
        NON_PRINTABLE_KEY_LABELS.put(KeyEvent.KEYCODE_BUTTON_L2, "L2");
    }

    /** 获取按键码的显示标签 */
    public static String getKeyLabel(int keyCode) {
        if (keyCode == 0) {
            return "";
        }
        String text = NON_PRINTABLE_KEY_LABELS.get(keyCode);
        if (text != null) {
            return text;
        }
        KeyEvent event = new KeyEvent(0, keyCode);
        char ch = (char) event.getUnicodeChar();
        return ch != 0 ? ch + "" : "key-" + keyCode;
    }
}
