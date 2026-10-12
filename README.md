# 网络切换控制台 v0.5.4 — DMS Asset Fix

本版以 v0.5.2 为基础，仅在 APP UI 侧新增 **检测基带硬件支持频段（Root · 实验性）**，保持 `PhoneHook.java` 完全不变。

## 打包
请使用仓库内 `.github/workflows/build-apk.yml` 的 GitHub Actions。工作流现在显式安装 Android NDK 27.2，并在 Gradle 之前将 `native/qmi_dms_probe.c` 编译为 APK 内置 `assets/dms_probe_arm64`，并在构建结束后校验 APK 确实包含该资产。若漏打包，Actions 将直接失败。无需安装 qmicli、Termux 或额外 KernelSU 模块。APK 安装后使用 KernelSU 授予本 APP Root。

## 查询内容
DMS service 2 / QRTR node 0 / port 119，**只读** DMS Get Band Capabilities (QMI msg 0x0045)。接收 TLV 0x10 (LTE mask)、0x12 (extended LTE)、0x13 (NR5G)。其他响应仅打印 TLV 编号，不写入基带。成功查询的列表属于「基带报告」，不是「当前已启用频段」。

## 安全与限制
- 不执行 NAS 修改，不使用 DIAG，不读写 NV/EFS，不重启基带。
- 单独 Root 子进程，10 秒超时，原有电话进程 Hook 和实时刷新逻辑不变。
- QRTR 可能被 SELinux 阻止，QMI 查询可能报 `Not supported` 或超时；未实机验证。
- `QCI/5QI` 属于此前实验性独立功能，其稳定性/兼容性未得到证明。
- 本环境没有 Android NDK，不能声称已编译 APK；本 ZIP 是可供 Actions 构建的源码。
- **强烈建议测试前备份稳定 APK**。若网络服务异常，立即禁用测试模块并恢复稳定版。

## 修复原因与验证
旧版工作流没有 NDK 编译步骤，因此 APK 不含 `assets/dms_probe_arm64`，点击按钮必然报组件缺失。现在 Actions 编译 ARM64 ELF，且执行 `unzip` 校验。注意：组件成功打包并不等于已经取得 QRTR/基带读取权限；实际 QMI 读取仍须真机验证。
