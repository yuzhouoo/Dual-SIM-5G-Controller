# Dual SIM 5G Controller (OnePlus 12 / ColorOS 16 / Android 16)

Prototype v0.1.0. **Source project + KernelSU module**; this package does **not** contain a compiled APK. Android Studio/Gradle and the device are needed to validate it.

## Architecture

- **KernelSU root module (primary)**: polls every 8 seconds (negligible idle work), reads default data subId and active slot→subId mappings, changes only NR bit (bit19) of the per-subscription USER network masks; writes only if needed. Backup original USER masks once, supports restore.
- **LSPosed Legacy API (optional fast path)**: Hook `Application.attach()` in `com.android.phone`; dynamically register for subscription change intents and trigger root helper. This hook is *best effort*; KernelSU permissions may deny `su` from the phone process, and some ColorOS builds may use different components. The root daemon works without the hook.
- **App UI**: uses explicitly approved KernelSU `su` for status/enable/disable/restore.

## Build APK

Open this directory in Android Studio (JDK 17, AGP 8.7.3, Gradle 8.9, compile SDK 36). Sync and build `app` debug APK. Depends on compile-only `de.robv.android.xposed:api:82` from `https://api.xposed.info/`. For some networks this Maven repository may be unreachable: manually add Xposed API 82 `jar` under `app/libs/` and change the `compileOnly` dependency accordingly.

## Install (recommended order)

1. Flash `DualSim5G-KernelSU.zip` through KernelSU Manager → Modules, and reboot. A standard Magisk-derived module zip may require manager-specific packaging; test first with KernelSU. Do not flash in recovery.
2. Root shell check: `su -c /data/adb/modules/dual_sim_5g/controller.sh status`.
3. Install compiled APK, authorize root in KernelSU. In LSPosed **legacy-compatible environment**, enable module with scope **Phone / com.android.phone**, reboot phone service/device.
4. Observe logs in `/data/adb/modules/dual_sim_5g/controller.log`. Switch default data SIM in ColorOS and verify within ~8 seconds (LSPosed path may be quicker).

## Operations

```sh
su -c /data/adb/modules/dual_sim_5g/controller.sh status
su -c /data/adb/modules/dual_sim_5g/controller.sh enable
su -c /data/adb/modules/dual_sim_5g/controller.sh disable
su -c /data/adb/modules/dual_sim_5g/controller.sh restore
```

`disable`: stops automatic changes, retains current network settings. `restore`: disables automation then restores exact USER masks saved before the first automatic run. Since earlier **manual testing already changed both masks**, those modified masks may be what the first-run backup captures. Manually restore the original masks BEFORE enabling if exact pre-test settings are important. **Do not mistake network mode NR allowance for actual 5G registration.** Other restriction reasons/ColorOS modem policy can still block 5G.

## Safety/limitations

- The root module runs only with two active subscriptions; cards with slotIndex -1 are ignored.
- It keeps GSM/LTE etc masks untouched, except the NR bit.
- It does **not** lock 5G-only, change phone numbers, IMS settings, airplane mode, or modem firmware.
- SIM switch may briefly interrupt service. Don't test during a call.
- Legacy hooks are not guaranteed compatible with every Android 16 LSPosed fork. Verify hook installation from the LSPosed log. No Android 16 device runtime verification is claimed.
- Root daemon is independent of LSPosed, so it remains active even if the LSPosed module is disabled. To stop it, use the app **停用** or delete `enabled`.

## GitHub Actions auto-build

This source archive includes `.github/workflows/build-apk.yml`.
Create a GitHub repository with the files in this project root and run **Actions → Build LSPosed APK → Run workflow**. After a successful build, download the `DualSim5G-debug-APK` artifact and extract `app-debug.apk`. Build status has not been tested here; internet access to Google Maven and Xposed's repository is required.
