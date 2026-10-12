# 网络切换控制台 v0.5.0

基于 v0.4.9 的完整 Android 工程。

## 本次 UI 更改
- 应用名称改为「网络切换控制台」。
- 使用 5G SIM 专属图标（mipmap 多密度 PNG）。
- 去掉「频段实时诊断」和「设备硬件支持频段」两个独立 UI 模块与无线能力检测按钮。
- 保留 SIM 网络概览中的实时 Band 和 NR-ARFCN / EARFCN。
- 保留双卡并排、subId、状态时间、QCI/5QI 及上下行占位、Root 重启电话服务、按钮冷却、调试日志。
- LSPosed 推荐作用域仍为 com.android.phone。

## 风险说明
`PhoneHook.java` 原封未动，沿用 v0.4.9 行为，不意味着其读取问题已得到修复。
QCI/5QI、签约上下行暂无可靠读取，显示 —。
工程未通过 Android SDK 编译或真机测试；请自行使用 GitHub Actions 构建，并重新加载作用域进程。
