# Dual SIM 5G Controller v0.3.5 (stability candidate)

基于 v0.3.2，保留 SIM 并排、单卡/无卡占位及 PhoneHook 双卡管理。

- 去除 Activity 启动时对 WindowInsetsController 的直接访问，以及强制边到边窗口切换。
- 保留 ScrollView 安全区域 Insets 处理。
- 动态广播注册添加异常保护，防止 onDestroy 未注册就注销。
- 增加 `Dual5G-UI` 日志，方便进一步定位。

**未在 Android SDK 编译及 OnePlus 12 真机验证，不能保证闪退已解决。**

建议用：`adb logcat -c; adb logcat -v time -s AndroidRuntime:E Dual5G-UI:E ActivityTaskManager:E`，然后打开 APP，复制崩溃日志。


## v0.3.7 Hardware band catalog

- Based on the user-tested v0.3.5 stable UI/Hook sources.
- Adds an expandable, read-only manufacturer-published frequency band table for **OnePlus 12 China model PJD110**.
- Detects `Build.MODEL` / `Build.DEVICE`; does not show China bands on unidentified or non-Chinese variants.
- Does not query cell info, radio HAL, or add permissions. SIM behavior is unchanged.
- Official reference: https://www.oneplus.com/cn/12/specs
- This is a published hardware compatibility listing, not a live modem configuration or connected-band report.
- The FDD count is 12 entries.
