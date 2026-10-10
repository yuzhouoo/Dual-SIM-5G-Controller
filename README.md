# Dual SIM 5G Controller — Standalone LSPosed APK v0.2.0

Single APK. **No KernelSU module, no `su` command, no root shell in the APK.** Requires installed and working LSPosed framework (rooted phone). Targets `com.android.phone` using Legacy Xposed API.

## Behavior

- Registers broadcasts in `com.android.phone` after `Application.attach()`.
- Changes `TelephonyManager.setAllowedNetworkTypesForReason(USER, mask)` from the privileged phone process.
- Data SIM gets NR bit enabled; other active SIMs get NR bit cleared, preserving all other bits.
- Listens for data subscription/SIM changes; delays writes 1.5s, runs once on phone process startup.
- Main app communicates with hook over signature-protected broadcast; does not call `su`.
- Stores the first-seen user reason bitmask **inside the phone process** keyed by subId; restore does not imply factory defaults (you already changed both SIM masks manually).

## Build

GitHub Actions > Build Standalone LSPosed APK > Run workflow > Artifact `DualSim5G-Standalone-APK`.

## Install

1. Uninstall the previous `dev.dual5g` APK then install the new one. Disabling the old module before updating is recommended. No separate root module is needed.
2. LSPosed: enable module, select `com.android.phone` only. Reboot phone.
3. Open app, `读取状态` and `启用并立即同步`.
4. Switch default data SIM in ColorOS and verify the modes using `cmd phone get-allowed-network-types-for-users -s 0` and `-s 1`.

## Caveats

- **Not device tested.** ColorOS 16 may restrict access even from `com.android.phone` or override allowed network types. Review LSPosed log `Dual5G` if it fails. `MODIFY_PHONE_STATE` and `READ_PRIVILEGED_PHONE_STATE` are normally granted to the phone process, but this assumption needs testing on your ROM.
- The signature-protected runtime receiver accepts only requests from apps signed with this APK's key. Debug APK rebuilding with a different key changes the trusted sender.
- In case of permission denial, the app will show the reported error. There is no `su` fallback.
- Restarting `com.android.phone` can temporarily disconnect cellular service; prefer reboot.
- If the previous KernelSU module was installed, disable/uninstall it to prevent conflicting changes.
- No network setting can guarantee 5G radio registration; it only changes allowed RAT.


## v0.2.1 诊断说明

- 这是无 su 的独立 LSPosed APK；不需要安装 KernelSU 模块。
- 启动后的 LSPosed 模块日志应包含 `Dual5G STANDALONE v0.2.1 phone hook initialized`。
- 如果仍出现 `Dual5G immediate trigger unavailable (daemon remains active)`，说明运行中的仍是此前旧版 Hook；检查安装包版本、LSPosed 启用的模块并重启电话进程/设备。
- 建议在系统设置中卸载旧版 `dev.dual5g`，安装此 APK，重新启用 LSPosed 作用域 `com.android.phone` 后重启手机。
- 单 APK 方案在 ColorOS 16 的权限与广播交互仍需要实机验证。

## v0.2.2 调试面板

打开 APK 点击「刷新网络状态」可查看每张活动 SIM 的卡槽、运营商、订阅 ID、是否为默认数据卡、用户允许 NR 与否、实时数据/语音网络类型及最近同步结果。

注意：网络掩码表示允许的制式，不代表当前实际驻网；部分 5G NSA 场景数据网络类型也可能显示 LTE。电话进程需要读取电话服务状态的权限。该版只需要 APK + LSPosed，不需要额外 KernelSU 模块，也不会调用 su。
