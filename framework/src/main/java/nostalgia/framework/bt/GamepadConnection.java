package nostalgia.framework.bt;

import android.bluetooth.BluetoothDevice;
import android.content.Context;

/** 手柄传输层连接抽象（BLE GATT / 经典 L2CAP） */
public interface GamepadConnection {

    /** 后台建立连接；状态通过 manager 回调上报 */
    void connect(Context ctx, BluetoothDevice device, GamepadManager manager);

    /** 关闭连接，释放资源 */
    void close();
}
