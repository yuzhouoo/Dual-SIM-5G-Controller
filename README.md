# 网络切换控制台 v0.5.2 · QCI 诊断版

基于 v0.5.1，仅修复 QCI/5QI 按钮反馈与无结果时的诊断说明。

- 点击 QCI 后，按钮下方立即展示请求已发送；电话服务回传后展示检测中、超时、完成或者具体不可用原因。
- 只读检测仅按需运行；不接入 4 秒状态刷新或自动切卡策略。
- 兼容尝试 `getDataNetworkList` / `getDataNetworks`，但 ColorOS 16 并不保证公开底层 QCI 数据。无法获取时显示原因，不能伪造值。
- 保留应用名、图标、LSPosed 推荐作用域 com.android.phone、双卡实时频段、Root 重启按钮。
- 更新 APK 后重新加载 com.android.phone 中的 LSPosed Hook（正常重启通常最可靠）。
- 若点击后长期仅显示“请求已发送”，先检查广播/Hook 通信；若显示“系统未开放数据网络控制器”等，则按钮通信正常、数据接口不可用。

注意：此工程未在当前环境执行 Android SDK 编译与真机测试。请用原 GitHub Actions 构建并验证。
