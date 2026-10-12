# 网络切换控制台 v0.5.8 · NAS RF 只读测试版

基于 v0.5.7，保留已有 LSPosed/双卡策略、DMS 自动硬件频段、NAS 自动频段偏好读取，增加启动后自动执行一次 **NAS Get RF Band Information (QMI 0x0031)**。

- **独立单 APK**，使用内置 ARM64 探测组件和现有 KernelSU Root，无 Termux/qmicli/额外模块。
- DMS: Service 2, Node 0, Port 119, Message 0x0045（原样保留）。
- NAS selection preference: Service 3, Node 0, Port 82, Message 0x0034（原样保留）。
- NAS RF band info: Service 3, Node 0, Port 82, Message 0x0031（新加，只读）。
- 扩展列表 0x11 中的 Channel 是 32 位，传统列表 0x01 是 16 位；活动 Band Class 以**原始枚举值**显示，避免错误地把枚举数当成 B/N 频段号。后续依据真机输出进行精确映射。
- 单次自动查询，不放入 4 秒刷新或 `com.android.phone` Hook；10 秒应用层超时，失败不影响双卡切换。
- **当前活动 RF ≠ 所有启用频段 ≠ 运营商许可频段**，无法据此列出运营商全量清单。

## GitHub Actions 构建
上传全部文件（包含隐藏的 `.github/workflows/build-apk.yml`），运行 `Build Standalone LSPosed APK`。Actions 使用 NDK 27.2 编译 `native/qmi_nas_rf_probe.c` 并核验 APK 内 `assets/nas_rf_probe_arm64`。

## 测试
安装 APK，Root 授权后打开，复制 **当前实际射频频段（NAS RF · 实验性）** 区域的完整日志。最好分别在 SIM1、SIM2 是默认数据卡时各采集一次。**切换默认数据卡是可选的手动操作，不是探测程序会执行的行为。**

构建状态：源码完成、C 语法检查；未执行 Android NDK/Gradle 全量构建和真机测试。
