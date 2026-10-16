package nostalgia.framework.bt;

import java.util.ArrayList;
import java.util.List;

/**
 * 极简 HID Report Descriptor 解析器（HID 1.11）。
 * <p>
 * 解析 BLE HID（Report Map 0x2A4B）给出的报告描述符，
 * 生成字段表用于把原始报告字节解码为 {@link GamepadState}。
 * 覆盖 Gamepad/Joystick/Keyboard 常见描述符：USAGE_PAGE、USAGE(MIN/MAX)、
 * REPORT_ID/SIZE/COUNT、LOGICAL_MIN/MAX、INPUT、COLLECTION。
 * </p>
 */
public class HidReportParser {

    public static final int PAGE_GENERIC_DESKTOP = 0x01;
    public static final int PAGE_BUTTON = 0x09;
    public static final int PAGE_KEYBOARD = 0x07;

    public static final int USAGE_X = 0x30;
    public static final int USAGE_Y = 0x31;
    public static final int USAGE_Z = 0x32;
    public static final int USAGE_RX = 0x33;
    public static final int USAGE_RY = 0x34;
    public static final int USAGE_RZ = 0x35;
    public static final int USAGE_HAT = 0x39;

    /** 解析出的一个报告字段 */
    public static class Field {
        public int reportId;
        public int bitOffset;
        public int bitSize;
        public int usagePage;
        public int usage;      // variable 字段的 usage；array 字段为 -1
        public int usageMin;   // array 字段的值域
        public int usageMax;
        public int logicalMin;
        public int logicalMax;
        public boolean array;
    }

    private final List<Field> fields = new ArrayList<>();
    private boolean hasReportIds;
    private int keyboardPage;   // 键盘 collection 时记录其 usage page
    private int appUsagePage;   // 应用 collection usage page

    public List<Field> getFields() {
        return fields;
    }

    public boolean usesReportIds() {
        return hasReportIds;
    }

    public int getAppUsagePage() {
        return appUsagePage;
    }

    /**
     * 解析 HID Report Descriptor。
     *
     * @param desc 原始描述符字节
     * @return this
     */
    public static HidReportParser parse(byte[] desc) {
        HidReportParser p = new HidReportParser();
        p.parseInternal(desc);
        return p;
    }

    private static int signExtend(int v, int bits) {
        int shift = 32 - bits;
        return (v << shift) >> shift;
    }

    private void parseInternal(byte[] d) {
        int pos = 0;
        int usagePage = 0;
        long logicalMin = 0, logicalMax = 0;
        int reportSize = 0, reportCount = 0, reportId = 0;
        int usageMin = -1, usageMax = -1;
        List<Integer> usages = new ArrayList<>();
        List<Integer> usagePages = new ArrayList<>();
        int[] bitOffsets = new int[256];
        int[] collUsageStack = new int[16];
        int[] collPageStack = new int[16];
        int collDepth = 0;

        while (pos < d.length) {
            int prefix = d[pos++] & 0xff;
            if (prefix == 0xFE) {
                // long item: 跳过
                int dataLen = d[pos] & 0xff;
                pos += 2 + dataLen;
                continue;
            }
            int size = prefix & 0x03;
            if (size == 3) {
                size = 4;
            }
            int type = (prefix >> 2) & 0x03;
            int tag = (prefix >> 4) & 0x0f;

            long value = 0;
            for (int i = 0; i < size && pos < d.length; i++) {
                value |= ((long) (d[pos++] & 0xff)) << (8 * i);
            }

            switch (type) {
                case 0: // Main
                    switch (tag) {
                        case 0x08: { // Input
                            boolean constant = (value & 0x01) != 0;
                            boolean variable = (value & 0x02) != 0;
                            if (!constant) {
                                int usagePageForItem =
                                        collDepth > 0 && collPageStack[collDepth - 1] == PAGE_KEYBOARD
                                                ? PAGE_KEYBOARD : usagePage;
                                int usageCount = Math.max(usages.size(),
                                        usageMin >= 0 ? usageMax - usageMin + 1 : 0);
                                for (int i = 0; i < reportCount; i++) {
                                    Field f = new Field();
                                    f.reportId = reportId;
                                    f.bitOffset = bitOffsets[reportId];
                                    f.bitSize = reportSize;
                                    f.logicalMin = (int) logicalMin;
                                    f.logicalMax = (int) logicalMax;
                                    f.array = !variable;
                                    if (variable) {
                                        f.usagePage = usagePageForItem;
                                        if (i < usages.size()) {
                                            f.usage = usages.get(i);
                                        } else if (usageMin >= 0) {
                                            f.usage = usageMin + Math.min(i, usageCount - 1);
                                        } else {
                                            f.usage = -1;
                                        }
                                    } else {
                                        f.usagePage = usagePage;
                                        f.usage = -1;
                                        f.usageMin = usageMin;
                                        f.usageMax = usageMax;
                                    }
                                    fields.add(f);
                                    bitOffsets[reportId] += reportSize;
                                }
                            } else {
                                bitOffsets[reportId] += reportSize * reportCount;
                            }
                            usages.clear();
                            usagePages.clear();
                            usageMin = usageMax = -1;
                            break;
                        }
                        case 0x0A: // Collection
                            if (collDepth < collUsageStack.length) {
                                collUsageStack[collDepth] = usages.isEmpty() ? -1 : usages.get(0);
                                collPageStack[collDepth] = usagePage;
                                collDepth++;
                                if (collDepth == 1 && !usages.isEmpty()) {
                                    appUsagePage = usagePage;
                                }
                            }
                            usages.clear();
                            usagePages.clear();
                            usageMin = usageMax = -1;
                            break;
                        case 0x0C: // End Collection
                            if (collDepth > 0) {
                                collDepth--;
                            }
                            usages.clear();
                            usagePages.clear();
                            usageMin = usageMax = -1;
                            break;
                        case 0x09: // Output
                        case 0x0B: // Feature
                            bitOffsets[reportId] += reportSize * reportCount;
                            usages.clear();
                            usagePages.clear();
                            usageMin = usageMax = -1;
                            break;
                        default:
                            break;
                    }
                    break;
                case 1: // Global
                    switch (tag) {
                        case 0x0:
                            usagePage = (int) value;
                            break;
                        case 0x1:
                            logicalMin = signExtend((int) value, size * 8);
                            break;
                        case 0x2:
                            logicalMax = signExtend((int) value, size * 8);
                            break;
                        case 0x7:
                            reportSize = (int) value;
                            break;
                        case 0x9:
                            reportCount = (int) value;
                            break;
                        case 0x8:
                            reportId = (int) value;
                            hasReportIds = true;
                            break;
                        default:
                            break;
                    }
                    break;
                case 2: // Local
                    switch (tag) {
                        case 0x0:
                            usages.add((int) value);
                            usagePages.add(usagePage);
                            break;
                        case 0x1:
                            usageMin = (int) value;
                            break;
                        case 0x2:
                            usageMax = (int) value;
                            break;
                        default:
                            break;
                    }
                    break;
                default:
                    break;
            }
        }
    }

    private static int extractBits(byte[] data, int bitOffset, int bitSize) {
        int v = 0;
        for (int i = 0; i < bitSize; i++) {
            int idx = bitOffset + i;
            int b = (data[idx >> 3] >> (idx & 7)) & 1;
            v |= b << i;
        }
        return v;
    }

    private static int normalizeAxis(int v, int logicalMin, int logicalMax) {
        if (logicalMax <= logicalMin) {
            return 0;
        }
        double t = (v - logicalMin) / (double) (logicalMax - logicalMin);
        return (int) Math.round(t * 255 - 128);
    }

    /**
     * 解码一条 Input Report。
     *
     * @param data 报告数据；若描述符使用 Report ID，第一个字节为 ID
     * @return 解码结果，非游戏手柄字段忽略
     */
    public GamepadState decode(byte[] data) {
        GamepadState s = new GamepadState();
        int reportId = 0;
        int baseBit = 0;
        if (hasReportIds && data.length > 0) {
            reportId = data[0] & 0xff;
            baseBit = 8;
        }
        for (Field f : fields) {
            if (f.reportId != reportId || f.bitSize <= 0) {
                continue;
            }
            if ((f.bitOffset + baseBit + f.bitSize) / 8 > data.length) {
                continue;
            }
            int raw = extractBits(data, f.bitOffset + baseBit, f.bitSize);
            if (f.logicalMin < 0 && f.bitSize < 32) {
                raw = signExtend(raw, f.bitSize);
            }
            if (f.array) {
                // array 字段值为 usage 索引，键盘按键场景按 keycode 处理
                s.buttons |= mapArrayValue(f, raw);
                continue;
            }
            if (f.usagePage == PAGE_BUTTON && f.bitSize == 1 && f.usage > 0 && f.usage <= 32) {
                if (raw != 0) {
                    s.buttons |= 1L << (f.usage - 1);
                }
            } else if (f.usagePage == PAGE_GENERIC_DESKTOP) {
                switch (f.usage) {
                    case USAGE_X:
                        s.axes[0] = normalizeAxis(raw, f.logicalMin, f.logicalMax);
                        break;
                    case USAGE_Y:
                        s.axes[1] = normalizeAxis(raw, f.logicalMin, f.logicalMax);
                        break;
                    case USAGE_Z:
                        s.axes[2] = normalizeAxis(raw, f.logicalMin, f.logicalMax);
                        break;
                    case USAGE_RZ:
                        s.axes[3] = normalizeAxis(raw, f.logicalMin, f.logicalMax);
                        break;
                    case USAGE_HAT:
                        s.hat = (raw >= 0 && raw <= 7) ? raw : GamepadState.HAT_NEUTRAL;
                        break;
                    default:
                        break;
                }
            }
        }
        return s;
    }

    /** array 字段映射：Button 页数组值=按下的按钮编号；键盘按键映射到高位保留区 */
    private long mapArrayValue(Field f, int keycode) {
        if (keycode <= 0) {
            return 0;
        }
        if (f.usagePage == PAGE_BUTTON && keycode >= 1 && keycode <= 32) {
            return 1L << (keycode - 1);
        }
        // HID keyboard: 79-82 为方向键 → 虚拟按钮 33..36；Z(29)=btn37 X(27)=btn38
        switch (keycode) {
            case 79: return 1L << 32; // right
            case 80: return 1L << 33; // left
            case 81: return 1L << 34; // down
            case 82: return 1L << 35; // up
            case 29: return 1L << 36; // z → B
            case 27: return 1L << 37; // x → A
            case 40: return 1L << 38; // enter → start
            case 41: return 1L << 39; // esc → select
            default: return 0;
        }
    }
}
