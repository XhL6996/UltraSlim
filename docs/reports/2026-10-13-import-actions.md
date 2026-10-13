# 1.1.4：统一导入入口

首页的选择照片、相册导入、文件夹导入原先连续占据三行；队列里还会与试转按钮一起换行，主次不清晰。

改为共用 `ImportActions`：

- 空首页使用填色分体按钮，左侧「选择照片」直接打开系统照片选择器。
- 右侧箭头展开菜单，包含「相册导入」「文件夹导入」，沿用原有回调和文件夹递归选项。
- 非空队列使用相同的紧凑 tonal 分体按钮，配合独立的「先试转 1 张」操作。
- 导入或扫描中两部分同时禁用，并关闭已打开菜单；转换中继续隐藏追加入口。
- 使用当前 Material 3 1.5.0-beta01 的 `SplitButtonLayout`、`SplitButtonDefaults.LeadingButton/TrailingButton`，不是手工拼凑按钮圆角。参考 [Android 官方 SplitButtonLayout 文档](https://developer.android.com/reference/kotlin/androidx/compose/material3/SplitButtonLayout.composable)。

验证包括：240 dp 内容宽度、正常/两倍字体、首页/队列两种强调等级，三个入口分别触发正确回调，选项点击后关闭菜单，禁用时菜单消失；实际主页在手机、大字体和折叠屏下检查。截图产物位于 `build/ui-review-1.1.4/`。

本次没有更新截图测试参考图，不更改导入、转换或保存的数据流程。

## 验证和交付结果

- 设备 UI 回归 33 项：首次 32 项通过，相册手势测试仍直接寻找旧的首页按钮；更新为「更多导入方式 → 相册导入」后，该项复测通过。手势断言保持不变。
- JVM 24 项通过、1 项既有跳过；截图回归 5/5；Debug/Release 构建及 Lint 通过。
- 1.1.4 Debug APK（versionCode 16）签名及 16 KB ZIP 对齐通过，SHA-256：`6ef383ee9072681f7468f145d8ef21de4b25fa6f03803fe39aa6d8d1e201f4e8`。
- 用户手机已从 1.1.3 成功覆盖安装到 1.1.4，启动后 MainActivity 位于前台；已检查真实首页截图，未发现当前进程的启动崩溃日志。本次覆盖升级未卸载或清除数据。
