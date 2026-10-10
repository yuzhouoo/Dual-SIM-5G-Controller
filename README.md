# Dual SIM 5G Controller v0.3.9 · Hardware Probe

基于 v0.3.8，沿用 v0.3.5 的稳定窗口和原双卡自动切换逻辑。无需 KernelSU 模块。

## 三种数据来源

1. **【设备读取】** 在已 Hook 的 `com.android.phone` 进程，用户点“检测设备无线能力”时调用 `TelephonyManager.getSupportedRadioAccessFamily()`。显示每张活动卡对应 Modem 的 NR/LTE 制式能力和原始 RAF 掩码。这不是 N/B Band 清单；若失败会显示受限和异常类型。无活动 SIM 时尝试默认 Modem。
2. **【官方公布】** 对设备型号匹配 PJD110 的 OnePlus 12，展示一加官网国行公开频段清单；不匹配型号不冒用国行规格。无法从 Android 通用接口读取完整硬件 Band 时使用此清单兜底。
3. **【当前连接】** 保留 v0.3.8 手动获取已注册 LTE/NR 小区频段的功能，NSA 可能只显示 LTE 锚点。

**当前启用的逐频段列表**：无通用公开 Android API，明确显示不可获取。`getAllowedNetworkTypesForReason()` 只能判断制式，不应误当作 Band。

## 构建

GitHub Actions → Build Standalone LSPosed APK。需在 LSPosed 启用 `com.android.phone` 作用域并重启。应用不执行 `su`。

## 测试

先检查旧有 SIM 切换功能；再按“检测设备无线能力”检查来源标签。设备查询不修改任何网络配置。此源码尚未进行 Android SDK 编译和真机验证。
