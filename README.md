# v0.4.1 Live Bands Fix（实验版）

修复方向：Android 16 上 `getServiceState()` 返回的位置脱敏数据可能缺失 `CellIdentity`。优先 `getServiceState(TelephonyManager.INCLUDE_LOCATION_DATA_FINE)`，失败回退。尝试 NetworkRegistrationInfo 直取和解析当前快照。

设备能力按钮增加明确的请求反馈；电话进程回传增加“最后收到指令”以排查广播通道。若显示为 hardware，说明按钮已经送达；若停留在 status，检查作用域/权限。

**限制**：未在 OnePlus 12 或 Android SDK 上验证；这不是已确认修复。如频段仍空白，需要调试信息中的当前连接频段返回提示与最后收到指令。

仍然是单 APK LSPosed，无 KernelSU 模块，不保存频段历史。原有 5G 切换逻辑未修改。
