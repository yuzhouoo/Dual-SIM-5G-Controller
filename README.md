# 网络切换控制台 v0.5.6 — 自动读取基带频段

基于 v0.5.5（用户已在 OnePlus 12 PJD110 真机得到 `QUERY_OK`，NR 13 个、LTE 17 个频段）。

- 进入 APP 后自动读取一次 Qualcomm DMS 频段能力，独立后台线程，10 秒超时；不再提供手动检测按钮。
- 在 SIM 网络概览下方展示 NR 与 LTE 列表及「基带实测」来源标识；失败时显示 Root / QRTR 错误。
- **不会**加入每 4 秒网络刷新链路，也不会改动电话服务 Hook、切卡策略、QCI 检测、LSPosed 推荐作用域。
- 仍然通过 GitHub Actions NDK 构建 `assets/dms_probe_arm64` 并校验 APK 中存在该组件，非外部 qmicli。
- 首次进入 APP 会请求 KernelSU Root 授权；在同一次 Activity 生命周期中不会重复请求。Root 不可用或拒绝时显示失败信息。再次重新打开 Activity 可重试。
- DMS 是基带返回的能力数据，不等同当前启用频段或整机 RF 前端能力。软件不写入 Modem 配置。

**构建**：完整上传包括 `.github/workflows` 的工程到 GitHub Actions 后运行 `Build Standalone LSPosed APK`。原生组件需要 Android NDK 编译。本环境未编译 APK 或真机验证 v0.5.6。
