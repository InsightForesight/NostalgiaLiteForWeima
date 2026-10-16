package nostalgia.framework.bt;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.ParcelUuid;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;

import nostalgia.framework.utils.NLog;

/**
 * 经典蓝牙 HID 手柄连接。
 * <p>
 * 车机系统 Bluetooth.apk 未启用 HID host profile，这里通过反射调用
 * 隐藏的 {@code BluetoothSocket(type=L2CAP)} 构造，直接打开 HID 的
 * Control(PSM=0x11) 与 Interrupt(PSM=0x13) 通道读取输入报告。
 * </p>
 * 连接是双向的：
 * <ul>
 *   <li>主动连接：手柄处于可连接状态时主机侧发起</li>
 *   <li>监听回连：任天堂系等手柄平时拒绝主机连接，按手柄键唤醒后
 *       由手柄主动发起 L2CAP 回连，必须挂监听 socket 接收</li>
 * </ul>
 * 输入报告格式：HIDP DATA 消息，首字节 0xA1，第二字节 Report ID，其后为报告体。
 * 经典 HID 没有 GATT 的 Report Map 可用（描述符在 SDP 里，接口不公开），
 * 因此报告体走启发式解码（见 {@link #decodeClassic}），原始数据同时上报调试。
 */
@Deprecated // 保留参考：此栈 L2CAP 被 ERTM 阻塞不可用，经典通路实际走 SystemHidConnection
public class ClassicGamepadConnection implements GamepadConnection {

    private static final int PSM_CONTROL = 0x11;
    private static final int PSM_INTERRUPT = 0x13;
    private static final int TYPE_L2CAP = 3;
    /** 监听回连开关：MTK 栈上开过一次 JV L2CAP server 后整个 JV L2CAP 池会被耗尽，默认关闭 */
    private static final boolean LISTEN_ENABLED = false;

    private volatile boolean closed;
    /** 已上报过错误时 close() 不再用 "断开" 覆盖状态 */
    private volatile boolean errorReported;
    private BluetoothSocket controlSock;
    private BluetoothSocket interruptSock;
    private BluetoothServerSocket controlServer;
    private BluetoothServerSocket interruptServer;
    private final Object lock = new Object();
    private GamepadManager manager;
    private BluetoothDevice device;

    @Override
    public void connect(Context ctx, BluetoothDevice device, GamepadManager manager) {
        this.manager = manager;
        this.device = device;
        closed = false;
        errorReported = false;
        new Thread(() -> {
            try {
                // 先配对（HID 一般需要 bond 才允许 L2CAP）
                if (device.getBondState() != BluetoothDevice.BOND_BONDED) {
                    if (device.getBondState() == BluetoothDevice.BOND_NONE) {
                        manager.log("配对中... createBond=" + device.createBond());
                    }
                    // 等 bond 真正完成再连，未 bond 的 L2CAP(auth) 连接必被栈拒绝。
                    // bond 状态变化是异步的，createBond 刚返回时可能还是 NONE，
                    // 所以 NONE 状态也要容忍一小段时间（5s），BONDING 最多等 30s
                    int waited = 0;
                    while (!closed && waited < 30000) {
                        int bs = device.getBondState();
                        if (bs == BluetoothDevice.BOND_BONDED) {
                            break;
                        }
                        if (bs == BluetoothDevice.BOND_NONE && waited >= 5000) {
                            break;
                        }
                        Thread.sleep(500);
                        waited += 500;
                    }
                    if (closed) {
                        return;
                    }
                    if (device.getBondState() != BluetoothDevice.BOND_BONDED) {
                        errorReported = true;
                        manager.onError("配对失败/超时, bondState=" + device.getBondState()
                                + "（看上方 BOND 日志的 reason）");
                        close();
                        return;
                    }
                    manager.log("配对完成");
                }
                // 双向：监听手柄回连 + 同时尝试主动连接。
                // 监听是可选的：MTK 栈的 JV L2CAP server 只能开一个且关闭后不释放
                // （第二个 PSM 直接 l2cap_listen status 0x1），失败只记日志不中断主动连接
                try {
                    if (!LISTEN_ENABLED) {
                        throw new IOException("listen disabled");
                    }
                    controlServer = createL2capServer(PSM_CONTROL);
                    interruptServer = createL2capServer(PSM_INTERRUPT);
                    startAccept(controlServer, true);
                    startAccept(interruptServer, false);
                } catch (Throwable t) {
                    closeQuiet(controlServer);
                    closeQuiet(interruptServer);
                    controlServer = null;
                    interruptServer = null;
                    manager.log("监听回连不可用(" + rootCause(t) + ")，仅主动连接");
                }
                startOutgoing(device);
                manager.log("等待手柄连接（可唤醒手柄触发回连）...");
                if (!waitChannels()) {
                    return; // 已被 close
                }
                manager.onConnected(device, false);
                sendNintendoHandshake(device);
                readLoop();
            } catch (Throwable t) {
                if (!closed) {
                    errorReported = true;
                    manager.onError("L2CAP 连接失败: " + t);
                }
                close();
            }
        }, "bt-classic-conn").start();
    }

    /* ---------------- 主动连接 ---------------- */

    private void startOutgoing(BluetoothDevice device) {
        new Thread(() -> {
            int attempt = 0;
            while (!closed && attempt < 6) {
                attempt++;
                if (hasBothChannels()) {
                    return;
                }
                BluetoothSocket c = null, i = null;
                try {
                    c = openL2cap(device, PSM_CONTROL);
                    i = openL2cap(device, PSM_INTERRUPT);
                    synchronized (lock) {
                        if (closed) {
                            closeQuiet(c);
                            closeQuiet(i);
                            return;
                        }
                        if (controlSock == null) {
                            controlSock = c;
                            c = null;
                        }
                        if (interruptSock == null) {
                            interruptSock = i;
                            i = null;
                        }
                        manager.log("主动连接成功");
                        lock.notifyAll();
                        return;
                    }
                } catch (Throwable t) {
                    manager.log("主动连接#" + attempt + " 失败: " + rootCause(t)
                            + " bond=" + device.getBondState());
                } finally {
                    closeQuiet(c);
                    closeQuiet(i);
                }
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "bt-classic-out").start();
    }

    private static Throwable rootCause(Throwable t) {
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    private boolean hasBothChannels() {
        synchronized (lock) {
            return controlSock != null && interruptSock != null;
        }
    }

    private BluetoothSocket openL2cap(BluetoothDevice device, int psm) throws Exception {
        // 反射 BluetoothSocket(int type, int fd, boolean auth, boolean encrypt,
        //                   BluetoothDevice device, int port, ParcelUuid uuid)
        Constructor<BluetoothSocket> ctor = BluetoothSocket.class.getDeclaredConstructor(
                int.class, int.class, boolean.class, boolean.class,
                BluetoothDevice.class, int.class, ParcelUuid.class);
        ctor.setAccessible(true);
        BluetoothSocket sock = ctor.newInstance(TYPE_L2CAP, -1, true, true, device, psm, null);
        sock.connect();
        return sock;
    }

    /* ---------------- 监听手柄回连 ---------------- */

    private BluetoothServerSocket createL2capServer(int psm) throws Exception {
        // 反射 BluetoothAdapter.listenUsingL2capOn(psm[, mitm, min16Pin])：
        // 隐藏 API，返回真正处于 listen 态的 socket（直接反射
        // BluetoothServerSocket 构造器得到的对象不在 listen 态，accept 会失败）
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        for (java.lang.reflect.Method m : BluetoothAdapter.class.getDeclaredMethods()) {
            if (m.getName().equals("listenUsingL2capOn")
                    && m.getParameterTypes().length >= 1
                    && m.getParameterTypes()[0] == int.class) {
                Object[] args = m.getParameterTypes().length == 1
                        ? new Object[]{psm}
                        : new Object[]{psm, false, false};
                m.setAccessible(true);
                return (BluetoothServerSocket) m.invoke(adapter, args);
            }
        }
        // 兜底：直接反射构造（部分 ROM 可用）
        Constructor<BluetoothServerSocket> ctor =
                BluetoothServerSocket.class.getDeclaredConstructor(
                        int.class, boolean.class, boolean.class, int.class);
        ctor.setAccessible(true);
        return ctor.newInstance(TYPE_L2CAP, true, true, psm);
    }

    private void startAccept(BluetoothServerSocket server, boolean control) {
        new Thread(() -> {
            try {
                BluetoothSocket s = server.accept(); // 阻塞等手柄回连
                synchronized (lock) {
                    if (closed) {
                        closeQuiet(s);
                        return;
                    }
                    if (control) {
                        controlSock = s;
                    } else {
                        interruptSock = s;
                    }
                    manager.log("手柄回连" + (control ? "控制" : "中断") + "通道");
                    lock.notifyAll();
                }
            } catch (IOException e) {
                if (!closed) {
                    manager.log((control ? "控制" : "中断") + "监听异常: " + e.getMessage());
                }
            }
        }, control ? "bt-accept-ctrl" : "bt-accept-intr").start();
    }

    /** 等齐控制+中断两个通道；返回 false 表示连接已被关闭 */
    private boolean waitChannels() {
        synchronized (lock) {
            while (!closed && (controlSock == null || interruptSock == null)) {
                try {
                    lock.wait(1000);
                } catch (InterruptedException e) {
                    return false;
                }
            }
            return !closed;
        }
    }

    /* ---------------- 握手 / 读循环 ---------------- */

    /**
     * 任天堂系手柄（Pro Controller/Joy-Con 及克隆）连上 HID 通道后不会主动上报，
     * 需要主机在中断通道发握手序列 80 02 → 80 04，之后再请求完整输入报告模式。
     * 名字识别不到就不发，避免干扰普通 HID 手柄。
     */
    private void sendNintendoHandshake(BluetoothDevice device) {
        String name;
        try {
            name = device.getName();
        } catch (SecurityException e) {
            name = null;
        }
        if (name == null) {
            return;
        }
        String n = name.toLowerCase();
        if (!n.contains("pro controller") && !n.contains("nintendo")
                && !n.contains("joy-con") && !n.contains("joycon")) {
            return;
        }
        try {
            OutputStream out = interruptSock.getOutputStream();
            manager.log("Switch 系手柄，发送握手序列");
            out.write(new byte[]{(byte) 0x80, 0x02}); // 状态/设备信息请求
            out.flush();
            Thread.sleep(300);
            out.write(new byte[]{(byte) 0x80, 0x04}); // 进入输入上报
            out.flush();
            Thread.sleep(150);
            // 请求标准完整输入报告 0x30（output report 0x01 + 子命令 0x03）
            out.write(new byte[]{0x01, 0x00, 0x00, 0x00, 0x00, 0x00,
                    0x00, 0x00, 0x00, 0x00, 0x03, 0x30});
            out.flush();
            manager.log("握手序列已发送");
        } catch (Exception e) {
            manager.log("握手发送失败: " + e);
        }
    }

    private void readLoop() throws IOException {
        InputStream in = interruptSock.getInputStream();
        byte[] buf = new byte[256];
        while (!closed) {
            int n = in.read(buf);
            if (n <= 0) {
                continue;
            }
            byte[] data = new byte[n];
            System.arraycopy(buf, 0, data, 0, n);
            manager.onReport(data);
            GamepadState s = decodeClassic(data);
            if (s != null) {
                manager.onState(s);
            }
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            closed = true;
            lock.notifyAll();
        }
        closeQuiet(interruptSock);
        closeQuiet(controlSock);
        interruptSock = null;
        controlSock = null;
        closeQuiet(interruptServer);
        closeQuiet(controlServer);
        interruptServer = null;
        controlServer = null;
        if (manager != null && !errorReported) {
            manager.onDisconnected("closed");
        }
    }

    private void closeQuiet(BluetoothSocket s) {
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void closeQuiet(BluetoothServerSocket s) {
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * 经典 HID 报告启发式解码。
     * <p>
     * 覆盖最常见的"标准手柄"格式（ipega/Saitake/通用 D-input 模式）：
     * [0]=0xA1 [1]=ReportID，随后 X,Y,Z,Rz 各一字节（0..255，中心128），
     * 一字节 hat（低4位 0-7/8=中立），随后 1-2 字节按钮位掩码。
     * 不匹配的格式先返回 null——UI 里能看到原始 hex 便于补充格式。
     * </p>
     */
    static GamepadState decodeClassic(byte[] data) {
        if (data == null || data.length < 2) {
            return null;
        }
        // HIDP DATA input report: 0xA1 <reportId> <payload...>
        if ((data[0] & 0xF0) != 0xA0) {
            return null;
        }
        int len = data.length - 2;
        byte[] p = new byte[len];
        System.arraycopy(data, 2, p, 0, len);
        if (len < 6) {
            return null;
        }
        GamepadState s = new GamepadState();
        s.axes[0] = (p[0] & 0xff) - 128; // X
        s.axes[1] = (p[1] & 0xff) - 128; // Y
        s.axes[2] = (p[2] & 0xff) - 128; // Z
        s.axes[3] = (p[3] & 0xff) - 128; // Rz
        int hat = p[4] & 0x0f;
        s.hat = hat <= 7 ? hat : GamepadState.HAT_NEUTRAL;
        long buttons = p[5] & 0xffL;
        if (len >= 7) {
            buttons |= (p[6] & 0xffL) << 8;
        }
        if (len >= 8) {
            buttons |= (p[7] & 0xffL) << 16;
        }
        s.buttons = buttons;
        return s;
    }
}
