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
