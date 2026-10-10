# Dual SIM 5G Controller v0.3.2

在 v0.3.1 基础上仅重排 SIM 网络概览：两个等宽卡片并排展示，适配双卡、单卡和无卡。

- 有卡：显示运营商、默认数据卡、5G/4G 允许模式、当前数据/语音网络。
- 空卡槽：显示“未插卡 / 无 SIM”，保留占位卡片。
- 两卡均无：顶部显示“未检测到活动 SIM”，两张空卡片。
- 实际数据网络 UNKNOWN 不等于无信号。
- 保留 v0.3.1 的状态栏安全边距和 LSPosed PhoneHook，不修改核心切换逻辑。

使用仓库内 GitHub Actions 构建 APK。注意：未在真实 Android 16 设备中编译/实测。


## v0.3.3：显示注册小区频段

- 在两个并排 SIM 卡片中分别显示 `NR N78 / N41` 和 `LTE B3 / B1`（真实值来自系统，不是示例值）。
- 只展示 `CellInfo.isRegistered()` 的 LTE / NR 小区频段，不将邻区频段误报为当前驻留频段。
- 双卡、单卡、无卡布局保持不变，空卡位显示 `—`。
- Android 16 / ColorOS 可能不提供 NSA NR 小区详情。此时显示 `未上报`，不会从 LTE 频点推测 NR 频段。
- 详情日志会同时显示 EARFCN / NR-ARFCN，用于进一步诊断。
- 需要 LSPosed 作用域 `com.android.phone`，不需要额外 KernelSU 模块。
- 本版本未完成 SDK 编译或真机验证。
