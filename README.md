# 网络切换控制台 v0.6.0 · 精简版（基于 v0.5.9）

保留：双卡 SIM 卡信息（SIM 1/2、订阅 ID、运营商）、当前默认上网卡、5G/4G 优先状态、自动切换策略、立即同步、刷新、停用策略、恢复原始模式、Root 重启电话服务、LSPosed 作用域 `com.android.phone`。

删除：QCI/5QI 及上下行 QoS 速率、数据和语音网络状态、连接频段和 ARFCN、DMS 硬件频段、NAS 频段偏好、NAS RF 当前射频状态、Root QoS 诊断模块。不会再执行这些自动探测任务，也不再编译相关 NDK 探针。

使用 `.github/workflows/build-apk.yml` 在原有仓库编译 APK，不需要新建仓库。

**验证范围：** 已检查源码引用与 ZIP 完整性。未在 Android SDK 环境中执行完整 Gradle 构建，也未真机验证。
