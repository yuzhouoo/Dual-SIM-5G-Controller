# v0.5.7 — NAS 当前频段偏好只读测试版

以 v0.5.6 为基础，不改 PhoneHook.java、双卡自动切换及 DMS 探测协议。

- 打开 APP 自动启动 **DMS 0x0045** 与 **NAS 0x0034** 两项一次性只读探测，无需点按钮。
- NAS：Service 3，Node 0，Port 82（基于该机实测服务映射）。
- NAS 解析 LTE 旧位图 0x15、扩展 LTE 0x23、NR SA 0x2C、NR NSA 0x2D、模式 0x11、停用模式 0x22。
- 独立原生二进制；超时 10 秒；失败只显示错误，不影响电话 Hook。
- NAS 的 *Band Preference* 不一定代表射频前端最终启用清单，更**不代表运营商实际许可的全部频段**。
- 高通某些固件可能不返回相应 TLV、返回全 1 位掩码、或该接口只有单一 Modem 级视图；不能擅自按 SIM1/SIM2 区分。
- 严禁通过 NAS `Set System Selection Preference 0x0033` 修改配置来测试。

## 构建
完整上传含隐藏 `.github/workflows/build-apk.yml` 的目录，在 GitHub Actions 执行 `Build Standalone LSPosed APK`。必须在 APK 中验证同时包含 `assets/dms_probe_arm64` 和 `assets/nas_probe_arm64`。

## 真实验证
打开 APP，找到“基带当前频段偏好（NAS · 实验性）”，把完整文字发回。特别关注 `QMI result`、`Extended LTE`、`NR5G SA`、`NR5G NSA`。在真机数据验证之前，不宣称当前启用频段或运营商允许频段已成功读取。
