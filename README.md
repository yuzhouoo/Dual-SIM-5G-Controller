# Dual SIM 5G Controller v0.4.0 — 当前连接频段实时显示

- 基于 v0.3.9，保持独立 LSPosed APK，`com.android.phone` 为作用域。
- 在每张 SIM 卡状态卡内新增「频段」，由电话进程调用 `TelephonyManager.getServiceState()` 读取当前 `PS/WWAN` 注册小区信息；优先用 `NetworkRegistrationInfo` 反射获取 `CellIdentityNr/Lte.getBands()`，厂商限制时仅解析当前 `ServiceState.toString()` 快照。
- 页面前台每 4 秒刷新；离开页面停止定时请求；不存储任何历史频段。频段未上报时立即显示未知，不沿用旧值。
- 额外保留硬件能力检测及标注为「官方公布」的 PJD110 官方频段表、双卡切换策略等。
- 不在轮询过程中调用 `getAllCellInfo()`、基带 OEM 命令或 `su`。
- 当前状态是已注册主小区的系统上报频段，可能缺失 NSA NR 和载波聚合辅助频段。

> 注意：源码包未在本环境通过 Android SDK 构建或 OnePlus 12 真机验证。用仓库的 GitHub Actions 构建、测试，如出现编译失败请提供日志。
