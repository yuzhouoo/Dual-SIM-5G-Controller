# Dual SIM 5G Controller v0.4.9 — Band Fallback

基于 v0.4.8，**完全保留其控制广播、签名权限、自动 NR 切换、主界面与 LSPosed 推荐作用域**，仅修补当前频段读取。

1. 优先使用原有 ServiceState 已注册 PS/WWAN 小区获取 N/B 频段和 NR-ARFCN/EARFCN。
2. ServiceState 没有返回频段时，尝试 `TelephonyManager.getAllCellInfo()` 中 `isRegistered()` 的 NR/LTE 小区，绝不读取邻区当作连接频段。
3. 兜底读取在独立单线程上执行，最多等待 650ms；队列限制 1 项，避免故障拖慢电话进程主控制线程。
4. 两条路径都失败时，在“当前连接频段”中返回失败原因，方便从调试面板判断是 API 数据缺失、权限还是超时。可能出现 Android 缓存中的最后注册小区，无法保证毫秒级切换同步。
5. QCI/5QI 与上下行签约速率仍是占位符，没有加入不稳定的 OEM QoS 反射扫描。

**风险与验证**：未在设备 Android SDK 编译或真机运行。必须用 GitHub Actions 编译；切换前备份好可用源码。安装后需重新加载 `com.android.phone` 使新 Hook 生效。若频段仍然显示“—”，请贴出 APP 调试区中的“当前连接频段”完整一行；切勿误以为基站频段可以用官方硬件规格代替。
