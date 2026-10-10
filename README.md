# v0.4.2 IPC 修复测试版

## 背景
v0.4.1 三项同时失败：概览频段为空、能力检测无反馈、调试页没有接收指令。优先修复 APP 到 com.android.phone 的 IPC；频段读取路径仍需真机验证。

## 更改
1. 控制广播接收端取消 APP 自定义 signature 权限过滤，改为 Android 16 getSentFromUid() 校验发送 UID 必须是 dev.dual5g（或系统 UID）。不允许其他第三方应用发出控制指令。
2. 增加 `ping` 心跳指令，收到时报告 `IPC PONG`。在发送硬件检测后 6 秒没有电话进程回包时显示明确错误。
3. 在前台首次定时查询仍无回包时，提示 LSPosed 作用域/重启检查。
4. 不触碰已验证工作的自动双卡 5G/4G 政策算法；实时频段仍采用现有读取方式，可能因 ColorOS 受限而为空。

## 测试
- 安装新 APK，确认 LSPosed 模块开启且作用域包含 `com.android.phone`，重启手机。
- 打开 APP 查看是否存在「电话进程没有返回状态」；点击「检测设备无线能力」检查结果。
- 如仍无响应，请获取 `adb logcat -d -v time | grep -iE 'Dual5G|FATAL EXCEPTION|BroadcastQueue' | tail -100`。

本工程未在当前环境编译或真机运行，仅完成源代码及 ZIP 检查。
