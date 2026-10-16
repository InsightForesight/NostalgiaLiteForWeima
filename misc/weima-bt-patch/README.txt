威马车机（Android 6.0 / j6headunit）蓝牙栈补丁
==========================================

文件
----
- bluetooth.default.so.orig    原件  /system/lib/hw/bluetooth.default.so
                               md5: 97cad0691268a6d6658e852f105fd04d
- bluetooth.default.so.patched 补丁版 md5: d615660ab2984bde1a1f72a9e69bd09c
                               与原件仅差 3 字节

修复内容
--------
修复该栈 uhid_event() 对 UHID_OPEN 事件不置 ready_for_data 的 bug
（Android 6.0 bluedroid 的已知缺陷，Android 7 上游已修复）：
system_server 每次新建输入设备会对节点做 open→close→open，
CLOSE 后 ready_for_data=0，OPEN 不恢复，手柄报告永久被丢弃。

补丁 = 把 UHID_OPEN 分支的返回改为跳到 UHID_START 分支的 ready=1 逻辑：
  偏移 0x77b86:  52 d0 -> ed d0   （beq -> b 0x7bb64 直接置位，不依赖日志开关）
  偏移 0x77b90:  54 e0 -> e6 e7   （b 0x7bc3c 返回 -> b 0x7bb60 打日志+置位）

配套操作（需 root adb，一次性）
------------------------------
  pm enable com.android.bluetooth/.hid.HidService
然后：

  mount -o remount,rw /system
  cp bluetooth.default.so.patched /system/lib/hw/bluetooth.default.so
  chmod 644 /system/lib/hw/bluetooth.default.so; chown root:root ...
  mount -o remount,ro /system
  重启蓝牙（或重启车机）

还原：
  cp /sdcard/bluetooth.default.so.orig /system/lib/hw/bluetooth.default.so

注意：蓝牙每次重启后，需 app 内 startService 拉起 HidService
（framework.bt.SystemHidConnection.connect 已实现）。
