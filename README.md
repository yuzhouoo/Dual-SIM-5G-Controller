# Dual SIM 5G Controller v0.4.8 — Stable IPC

基线：**v0.3.5 的 PhoneHook 控制/回传广播、signature permission、自动切换逻辑**；沿用 v0.4.6 精简 UI、LSPosed 推荐作用域 `com.android.phone`、Root 重启电话服务与按钮冷却。

- **关键修复**：撤销 v0.4.2~v0.4.7 的接收者 UID 验证/空权限控制接收器，完全恢复 v0.3.5 的 `registerReceiver(..., PERMISSION, handler, RECEIVER_EXPORTED)`。
- **状态字段兼容**：保留 v0.3.5 的运营商、subId、网络模式、数据/语音制式结构；每个已激活 SIM 新增独立 `当前连接频段`。读取失败返回 `—`，不影响其它字段。
- **设备能力检测**：手动点击才读取 `getSupportedRadioAccessFamily()`；显示制式和原始 RAF，不冒充逐频段数据。
- **QCI/5QI / 签约速率**：仅显示 `—` 占位，**不运行** DataNetworkController 的实验性反射。
- **安全**：自动切换与控制命令均采用 v0.3.5 原签名权限机制；Root 重启电话服务仍需手动确认。

## 重要限制
开发环境没有 Android SDK 完整编译工具链，也无法在真机上确认 LSPosed 注入效果。源码 ZIP 完整性检查不等于 APK 编译/实测通过。请通过 GitHub Actions 编译 APK，安装后在 LSPosed 中勾选 `com.android.phone` 并重启/重载对应进程后测试。避免在通信异常时频繁重启电话进程。
