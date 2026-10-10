# Dual SIM 5G Controller v0.4.7 · ReadOnly IPC Fix

基于 v0.4.6 UI + v0.4.2 实时频段读取。原有自动切换策略不修改。

## 修复

- 对 `BroadcastReceiver.getSentFromUid()` 返回 -1 或不可用的情况，允许仅 `status`、`ping`、`hardware`、`bands` **只读查询**继续执行。
- `enable`、`disable`、`restore`、`sync` 等会修改电话设置的命令，依然必须经真实发送方 UID 校验，否则拒绝，不能在未验证身份时执行。
- 保留自动策略切换、实时频段、UI 紧凑排版、Root 手动重启按钮、5秒点击等待、LSP 推荐作用域 `com.android.phone`。

这修复的是一种可能的广播拒绝情形，尚未有异常日志证明为真实根因；未编译、未实机验证。

安装升级后需重启设备/电话进程，才能确保新版 Hook 注入。若只读查询恢复但按钮控制仍被拒绝，需提取 `Dual5G unverified sender uid` 日志进一步定位真实调用方验证方法。严禁把来源未知的广播用于修改网络模式。
