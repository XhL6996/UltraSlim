# UltraSlim：Material 3 Expressive 与折叠屏

研究与实现日期：2026-10-12。设计语言覆盖应用内四个主要页面及共享控件，保留现有离线转码、图片校验和原图保护流程。

## 官方依据

- [Building with M3 Expressive](https://m3.material.io/blog/building-with-m3-expressive)：重点是层级、形状、色彩角色、强调文字、弹簧动效和适应场景的组件，而不是让每个元素都更大。当前文章列出 14 个新建或更新组件；突出少数关键操作，不同时堆砌所有组件。
- [Material 3 发布记录](https://developer.android.com/jetpack/androidx/releases/compose-material3)：2026-10-07 发布 1.5.0-beta01。此项目明确使用 Beta，以获得 Expressive 主题、导航和控件；不把它写成稳定版本。
- [Material Adaptive 发布记录](https://developer.android.com/jetpack/androidx/releases/compose-material3-adaptive) 与 [Navigation 3](https://developer.android.com/jetpack/androidx/releases/navigation3)：采用稳定的 Adaptive 1.3.0、Navigation 3 1.2.0。少数布局 API 仍带 ExperimentalMaterial3AdaptiveApi 注解。
- [自适应导航](https://developer.android.com/develop/ui/compose/layouts/adaptive/build-adaptive-navigation)：使用 NavigationSuiteScaffold 与 NavigationSuiteItem 自动切换窄屏导航栏、宽屏导航轨；窗口高度及桌面半折姿态也参与判断。
- [折叠感知](https://developer.android.com/develop/ui/compose/layouts/adaptive/foldables/make-your-app-fold-aware)：读取 FoldingFeature 的方向、分隔/遮挡属性与窗口坐标，不按型号猜折痕。
- [Navigation 3 列表详情示例](https://github.com/android/nav3-recipes/tree/main/app/src/main/java/com/example/nav3recipes/material/listdetail)：列表与详情交给 SceneStrategy，入口状态和 ViewModel 生命周期按 back stack 保留。
- [Android 开发工具更新](https://android-developers.googleblog.com/2026/06/android-developer-productivity-updates.html)：已使用本机 Android CLI 检索官方文档和安装 SDK，参考 Google 的 adaptive、navigation-3、android-cli、agp-9-upgrade 技能。CLI 是开发工具，不进入 APK。
- [Compose Preview 截图测试](https://developer.android.com/studio/preview/compose-screenshot-testing)：引入独立 screenshotTest 源集和多尺寸预览；运行中的窗口切换仍由 instrumentation 测试验证。

## 视觉系统

- 全局 MaterialExpressiveTheme + MotionScheme.expressive，延续绿系默认色与用户已有动态取色、深浅色偏好。
- 首页导入区与详情节省体积为主要视觉重点，使用主色容器、强调标题与大圆角；普通信息使用 surfaceContainer 系列。
- 格式与主题改为连体 ToggleButton：选中、按压都有形状反馈，同时保留单选语义和至少 48dp 触摸高度。窄区域或大字体会换行，不截断格式名。
- 扫描使用 LoadingIndicator，编码不确定进度使用波浪指示器；已有确定进度仍显示实际值，不伪造进度。
- 首页与设置滚动可收起各自的顶栏；成品库固定顶栏，让批量选择入口始终可见；手势提交规则仍为松手越过阈值才执行，取消不会删除。
- 不因新组件库存在就添加 FAB 菜单、轮播或额外操作入口，避免照片处理流程变复杂。

## 布局行为

| 页面 | 窄窗口 | 足够宽的窗口 | 桌面半折 |
| --- | --- | --- | --- |
| 首页 | 队列、折叠参数、底部转换操作 | 队列与参数区分开，操作紧邻参数 | 上方队列、下方参数与转换操作 |
| 成品库 | 筛选、统计与逐条虚拟化列表，进入详情 | 左列表、右详情；未选择时提示选图 | 单一导航场景，列表与统计分区；详情自行适配 |
| 详情 | 统计、画质对比、元数据依次滚动 | 单独打开时对比与信息分栏；嵌入成品库时使用所在栏宽度 | 对比与信息分置折痕两侧 |
| 设置 | 分组纵向排列 | 转码偏好与外观/维护分栏 | 偏好与其它设置上下分区 |

导航目的地使用 Navigation 3 ListDetailSceneStrategy；同一页面内的内容区域由 AdaptiveWorkspace 测量。宽度判断采用扣除导航与内边距后的真实约束，内容分区通常至少 720dp 宽、240dp 高。副区域随窗口宽度调整，上限 480dp；列表详情还需足够的可用高度。

铰链坐标转换为当前布局的本地坐标，再预留 24dp 间隙。空间不够容纳两个可用区域时选择较大的安全区域。系统没有上报折叠特征时按窗口尺寸重排。当前针对一个铰链的书本/桌面半折场景，未声称三折屏多铰链验收完成。

## 状态与兼容约束

折叠姿态在导航外统一监听，避免新页面短暂回到无铰链布局。两个顶级标签各自保存 back stack、SaveableState 与 ViewModel。成品库选另一张照片替换当前详情，不累积无穷详情栈。单栏/双栏由同一个 SceneStrategy 接管，避免窗口变化时反复挂载详情。双栏详情隐藏返回箭头，单栏保留返回；系统返回与历史选择模式继续生效。

全局 AutoReplacementHost 只保留一个。原生转码实现、转换服务、数据库版本与原图回收规则没有因布局迁移而改动。真实折叠设备的姿态报告、OEM 多窗口和触屏体验还需设备验收；模拟器截图不等同于这些结论。

验证记录见 [1.1.0 UI 回归](../reports/2026-10-12-expressive-adaptive.md)。

## 1.1.1 视觉修订

状态栏背景统一、分组设置项和更窄展开屏的分栏规则，见 [1.1.1 设计修订与验证](../reports/2026-10-12-expressive-polish.md)。原有照片工作区阈值保持，设置页独立采用 600dp 内容宽度与 280dp 最小栏宽，大字体时增加栏宽需求。
