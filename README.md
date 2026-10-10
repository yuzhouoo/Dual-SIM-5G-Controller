# v0.4.3 — SIM 网络概览增加信号与 QoS 诊断

基于用户真机验证的 v0.4.2。保留双卡切换、通信、实时频段代码。

- 每张活动 SIM 的网络概览展示实时信号 RSRP / RSRQ / SINR（NR）或 RSRP / RSRQ / RSSNR（LTE），无效值显示“未上报”。
- 分卡只读尝试检查 com.android.phone 内存中已存在的 DataNetwork/DefaultQos；如果平台没有相关访问器，明确显示 QCI/5QI 未上报。
- 有 QoS 速率时单独标为“QoS最大速率”，绝不混同于运营商签约限速（APN-AMBR、Session-AMBR 或套餐封顶速率）；签约上下行速率暂显示未上报。
- 不在“当前上网卡”区域增加字段；无卡位不显示虚假数据；不存历史。
- 原有 LSPosed + APK 方案不需要 KernelSU 模块。安装后重启让 com.android.phone 重载 Hook。

提示：本工程未在 Android SDK 或设备上编译/运行验证，QoS 数据可能因厂商内部实现而缺失。
