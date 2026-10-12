# 1.1.1：系统栏与 Expressive 视觉完善

本轮针对 1.1.0 的状态栏断层、设置页大卡片堆叠、部分展开屏仍单栏，以及视觉层级不足进行修正。

## 工具与设计依据

- Android CLI 原版本 `1.0.15857036`；执行官方 `android update` 升级到 `1.0.16500706`，再次检查显示 Already up-to-date。上轮更新了 SDK 和构建依赖，但没有更新 CLI。
- Material 3 仍为 `1.5.0-beta01`。2026-10-12 检查官方发布页，最新更新为 2026-10-07；稳定版仍是 1.4.0，Expressive 当前依赖 Beta。
- 使用 Google 的 edge-to-edge、adaptive、android-cli 技能；重新查阅官方组件实现和 Android CLI 文档。CLI 的 skills 搜索未找到独立命名为 expressive 的技能，因此以官方文档和组件源码为依据。
- 更新工具或依赖不会自动完成应用的排版、分组、色彩与动作层级；这次调整的是实际页面结构。

来源：[Expressive 设计介绍](https://m3.material.io/blog/building-with-m3-expressive)、[Material 3 发布记录](https://developer.android.com/jetpack/androidx/releases/compose-material3)、[列表规范](https://m3.material.io/components/lists/overview)、[顶栏规范](https://m3.material.io/components/app-bars/)、[Android CLI 更新](https://developer.android.com/tools/agents/android-cli/commands/update)。组件参数以 Google Maven 的 1.5.0-beta01 sources.jar 为准。

## 具体改动

- 顶栏默认的 scrolledContainerColor 会随滚动改变，但导航轨道保持原色，导致状态栏在轨道和内容之间被切成两种背景。首页、历史、详情、设置共用一致的顶栏颜色，导航轨道明确采用相同 surface。图标颜色继续跟随应用实际主题，关闭系统导航栏额外对比度遮罩。
- 设置页使用 MediumFlexibleTopAppBar 与副标题；滚动后收拢，同时保持背景连续。内容拆为输出方案、保存与原图、画面兼容、照片隐私、外观、缓存和关于。
- 设置开关行使用官方 SegmentedListItem 的分组形状与按压反馈，增加对应图标和开关勾选态。整行是一个具有 Switch 语义的触控目标，内部 Switch 不再额外响应点击。
- 外观区展示当前主题的主色/辅助色/第三色关系；尊重原有动态取色开关，不强制覆盖用户壁纸配色。
- 设置内容宽度达到 600dp 且两栏各能保留至少 280dp 时分栏。大字体会提高每栏最低宽度而退回单栏；照片查看和转码区域继续使用更宽的原有阈值。折叠铰链仍优先决定安全区域。
- 手机首页采用明确的标题、照片图形与主要按钮层级；Live 过滤置于导入动作之前，输出方案独立于主操作卡片。详情保存/分享按钮采用形状反馈版本。

## 组件采用情况

| 组件 / 区域 | 当前实现 |
| --- | --- |
| 全局主题与动效 | MaterialExpressiveTheme + MotionScheme.expressive |
| 设置顶栏 | MediumFlexibleTopAppBar，收拢行为及统一色彩 |
| 设置列表 | SegmentedListItem + ListItemDefaults.segmentedShapes；不是手画圆角行 |
| 格式 / 主题选择 | 官方 ToggleButton + connected shape factories；按实际宽度换行，自定义 Row 布局，不宣称 ButtonGroup 的邻项挤压动画 |
| 主要导入 / 转换 / 保存 / 分享按钮 | Button / FilledTonalButton / OutlinedButton 的 shapes 版本 |
| 扫描与转换进度 | LoadingIndicator / LinearWavyProgressIndicator；其他有明确进度的场景保留标准进度组件 |
| 导航 | NavigationSuiteScaffold / NavigationSuiteItem，宽屏导航轨道与窄屏底部导航 |
| 开关、筛选 Chip、对话框、菜单、滑块 | 当前 Material 3 官方组件；并非每一种控件都有独立的新 Expressive 造型 |
| 转码参数、照片比较、队列、统计卡 | 应用定制布局组合官方组件；未声称整个应用就是官方演示的复刻 |

## 验证

- Debug / R8 Release、Debug / Release lint、JVM 和截图检查通过。
- JVM 共 25 项：24 通过，1 项既有条件测试跳过；新增设置窄双栏/大字体回退几何测试。
- 既有转换参数预览截图 5/5 通过，未更新参考图。设置和首页改动通过下面的运行截图与仪器测试验证，不能把这 5 张预览视为所有页面覆盖。
- Android 15 独立 `.uiqa` 包：21/21，包含 ChromeUiTest 2、自适应 5、手势 6、Live 导入 1、历史 3、相册手势 1、撤销 1、自动替换 2。
- Android 16 独立 `.uiqa` 包：7/7，复测系统栏 2、自适应 5。
- ChromeUiTest 截取 UiAutomation 完整屏幕，包括状态栏图标；比较状态栏横跨导航和内容区的背景，以及顶栏背景。覆盖深色展开屏滚动前后、浅色手机与自动替换取消。
- 截图等待 WindowManager 缩放动画结束；Compose idle 本身不保证系统窗口动画已经结束。
- APK 签名、ZIP 16KB 对齐，以及 3 个 ARM64 JNI 库的 ELF LOAD 对齐检查通过。
- 未修改原生转码核心，未重跑 host CTest。本次没有安装到真实手机，没有验证厂商折叠屏系统栏、真实铰链传感器和三键导航。没有提交或推送。

## 本地产物

- 可安装包：`build/releases/UltraSlim-1.1.1-arm64-debug.apk`，`app.ultraslim.debug`，versionCode 13；Debug 签名。
- SHA-256：`0e9b12451af2872fb1037da593801b330d055698c3ca4c73b843caae37e4aa85`。
- 独立测试包：`build/releases/UltraSlim-1.1.1-arm64-uiqa.apk`。
- 未签名 Release：`build/releases/UltraSlim-1.1.1-arm64-release-unsigned.apk`。
- 实际运行截图与日志：`build/ui-review-1.1.1/index.html`、`build/ui-review-1.1.1/verification/`。原始用户截图仅放在被 Git 忽略的本地产物目录，用于对照。
