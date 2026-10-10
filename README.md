# Dual SIM 5G Controller v0.4.6

以 v0.4.5 UX Safe 为基线，只修改 SIM 网络概览和服务状态行的文本与排版。保留 v0.4.2 验证过的 PhoneHook.java 原样。

- 状态：自动策略已启用（最后更新时间：HH:mm:ss），同一行。
- 网络概览：SIM 1 (subId 17)、数据：LTE 语音：LTE 同一行。
- 连接频段：频段：B3 EARFCN：1350；频段：N78 NR-ARFCN：627264，同一行，窄屏可能换行。
- 下一行：4G QCI：— 上行：— 下行：—；5G 5QI：— 上行：— 下行：—。数值仅占位，不执行危险的 QoS 扫描。
- 保留 Root 电话服务重启按钮（需要确认）、各操作按钮 5 秒禁用、LSPosed 推荐作用域 com.android.phone。

**作用域提示：** manifest 元数据 `xposedscope=com.android.phone`，不同 LSPosed 版本界面展示方式可能不同。

Github Actions 继续按 `.github/workflows` 编译。此处未进行 Android 真机验证。
