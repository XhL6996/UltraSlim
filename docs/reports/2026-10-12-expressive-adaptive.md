# 1.1.0：Expressive 与自适应 UI 验证

日期：2026-10-12。实现依据见 [设计记录](../design/2026-10-12-expressive-adaptive.md)。

## 已完成

- MaterialExpressiveTheme、Expressive motion、连体格式/主题按钮、加载与进度组件；保留默认绿系、深浅色与动态取色。
- NavigationSuiteScaffold / NavigationSuiteItem、Navigation 3 双 back stack、成品库列表详情场景；选中成品在列表中突出显示。
- 首页、设置、详情的约束驱动分区；书本/桌面姿态的铰链坐标避让。姿态在导航外统一监听，切换页面不会重新从空姿态开始。
- 历史选择入口固定在顶栏；保留滑动取消、撤销和自动替换流程。
- 大字体选择控件换行；用 Row 权重分配像素，修复非整数屏幕密度下最后一个格式按钮意外换行的问题。
- AGP 9.4.1 / Gradle 9.6.0 / compileSdk 37.0，Material 3 1.5.0-beta01；AndroidX 版本元数据保留在 APK 中，供 Layout Inspector 使用。构建依赖和 Beta 状态已写入构建说明。

## 验证

| 层级 | 结果 |
| --- | --- |
| Gradle help / build dry-run | 通过 |
| Debug / Release、Debug / Release lint | 通过 |
| JVM | 24 项，23 通过，1 项既有条件测试跳过；含 4 个分区几何用例 |
| Compose Preview 截图 | 5/5：手机、折叠宽度、平板、2 倍字体、深色；首批参考图随源码保留 |
| Android 15 模拟器 | 26/26：自适应 5、手势 6、撤销 1、相册滑动 1、Live 过滤界面 1、历史 3、自动替换界面 2、持久化 5、SDR 输出 2 |
| Android 16 模拟器 | 16/16：自适应 5、后台转换 4、自动替换界面 2、持久化 5 |
| APK | 签名验证通过；ZIP 与 3 个 ARM64 JNI 库的 LOAD segment 均满足 16KB 对齐 |
| 差异检查 | git diff --check 通过 |

自适应测试包括窗口缩放、详情保留、Activity 重建、横屏/大字体、模拟折叠区域以及 WindowLayoutInfoPublisherRule 上报姿态后的首页/设置/历史切换。回归捕获并修复了单栏/双栏切换的布局异常；历史顶栏滚动导致批量选择入口不可达，改为常驻。

本次未修改转码原生核心，因此未重跑 host CTest。设备测试只运行独立 `.uiqa` 包；没有覆盖日常使用手机上的安装或数据。真实折叠机/OEM 多窗口与多铰链设备尚未验收。

## 本地产物

- `build/releases/UltraSlim-1.1.0-arm64-debug.apk`：可安装的 Debug 签名包，`app.ultraslim.debug`，versionCode 12，43,214,320 字节。
- SHA-256：`0227ea70fc1381de09d80fa32346516d57bc9d8624d0650f85e6f640b01b7e2c`。
- Debug 证书 SHA-256：`365b703441ee9aea21527cb3d22bc823e73e3d0986de2e306668f5e6dc9aafcc`。
- 独立测试包：`build/releases/UltraSlim-1.1.0-arm64-uiqa.apk`。
- 未签名 Release：`build/releases/UltraSlim-1.1.0-arm64-release-unsigned.apk`。
- 运行截图与日志：`build/ui-review-1.1.0/`，打开 `index.html` 浏览。

这些产物被 Git 忽略；设计文档、测试源码及截图参考图可随开源仓库提交。本轮未提交、推送或发布远程版本。
