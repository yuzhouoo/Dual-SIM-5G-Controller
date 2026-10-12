# 网络切换控制台 v0.5.5 — QRTR bind 修复

## 错误与修复
v0.5.4 真机运行报 `ERROR bind errno=22 Invalid argument`。旧版在 `bind()` 时硬编码本地 QRTR Node 0，Android 内核可能使用不同的本地 Node，导致 `EINVAL`。新版不调用显式 `bind()`，让内核在 `sendto()` 时自动分配本地 Node/临时端口，输出实际分配地址。目标 DMS 地址仍为 Node 0 / Port 119。

## 构建
GitHub Actions 工作流 `.github/workflows/build-apk.yml` 自动安装 NDK 编译 ARM64 原生程序、放入 APK assets，并校验 `assets/dms_probe_arm64`。解压 ZIP 时注意上传隐藏 `.github` 目录。无需外装 qmicli。

## 测试
安装、授予 Root，点击「检测基带硬件支持频段」。记录 `Local QRTR auto-bound` 后的输出。如果返回 sendto 错误/recvfrom 超时/QMI 错误，反馈完整输出以继续诊断。

## 风险与边界
本版只修改 `native/qmi_dms_probe.c` 和版本号，不修改 PhoneHook 或控制通信。不使用 DIAG、NV/EFS 写入、锁频、Modem 重启。查询成功不等于所有射频前端支持频段，亦不等于当前启用频段。未完成 Android NDK 编译与真机验证，请保留旧版 APK 以备回退。
