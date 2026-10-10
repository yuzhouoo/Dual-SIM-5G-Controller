# Dual SIM 5G Controller v0.4.4 — Safe Rollback

This is an emergency recovery build based on the user-verified v0.4.2 IPCRepair source.

- PhoneHook.java is **byte-for-byte identical to v0.4.2**.
- Reverted all v0.4.3 SignalStrength and DataNetworkController/QoS reflective probing.
- Existing automatic SIM RAT policy, IPC transport, hardware radio capability probe, and live bands are preserved from v0.4.2.
- There are **no added signal metrics or QCI/5QI / subscribed rates** in this recovery version. Those require a separate opt-in, isolated experiment.
- Only bumped APK versionCode/versionName and UI version string. No other behavior changes.

## Recovery
1. Disable v0.4.3 module in LSPosed if it is destabilizing telephony; reboot as needed.
2. Build/install this replacement APK (same applicationId `dev.dual5g`).
3. Verify LSPosed scope includes `com.android.phone`; reboot so phone process loads updated hook.
4. Verify SIM cards, 5G switching and live bands again.

Build has NOT been verified with an Android SDK or on-device. Please provide `logcat -b crash -d` and LSPosed module logs if instability persists.
