package nostalgia.framework.bt;

import java.util.Arrays;

/**
 * 手柄解析后的标准化状态。
 * <p>
 * 由 {@link HidReportParser} 或经典手柄解码器从 HID 报告填充，
 * 与具体传输层（BLE GATT / L2CAP）无关。
 * </p>
 */
public class GamepadState {

    /** 轴数：X/Y（左摇杆）、Z/Rz（右摇杆） */
    public static final int AXES = 4;

    /** 未按下时的 hat 中立值 */
    public static final int HAT_NEUTRAL = -1;

    /** 轴值，已归一化到 -128..127，中心为 0 */
    public int[] axes = new int[AXES];

    /** 十字键：0-7 顺时针（0=上），-1 中立 */
    public int hat = HAT_NEUTRAL;

    /** 按钮位掩码，bit n-1 = HID Button usage n */
    public long buttons = 0;

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof GamepadState)) {
            return false;
        }
        GamepadState s = (GamepadState) o;
        return hat == s.hat && buttons == s.buttons && Arrays.equals(axes, s.axes);
    }
}
