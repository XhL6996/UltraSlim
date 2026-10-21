# 1.1.5：Expressive 导入工作区

用户选择更明显的色彩和卡片层次。此次重做空首页，不再使用大宣传卡片包裹导入按钮。

- 照片入口使用主色大卡片；相册和文件夹使用 secondaryContainer / tertiaryContainer 两张配套卡片，文字使用对应 on-color，继续支持动态取色。
- 卡片外侧采用 28 dp 圆角、内部连接处采用 8 dp 圆角，组成一个完整的操作区域；整张卡片可点击。
- 忽略 Live 的筛选、输出方案分别成组，离线处理说明移至底部。
- 内容宽度不足或字体放大时，次卡片改为上下排列。宽屏继续沿用实际窗口约束和铰链分区，右侧显示本次参数。
- 非空队列保留紧凑的 M3 分体按钮，避免重复放置三张大卡片。导入/扫描中空首页的三个入口也会同时禁用。

设计依据：[Android 官方 Material 3 设计系统](https://developer.android.com/develop/ui/compose/designsystems/material3)。色彩按照主操作、次操作和补充色分配；截图展示的是模拟器的动态配色，用户手机会跟随自身主题。

## 验证

- API 35 模拟器 UI 回归 32/32：导入回调和禁用、大字体、窗口重排和铰链、系统栏、相册手势、参数按钮、队列操作、滑动撤销、历史和自动替换。
- 导入组件在 240 dp 宽度及 1×/2× 字体下验证文字单行和边界；允许 Compose 本征文字宽度取整产生的不足 1 px 差异。
- 实际截图检查：420×900 浅色/深色手机、360 dp 大字体、750 dp 导航栏布局及 900 dp 双栏布局。图片在 `build/ui-review-1.1.5/`。
- JVM 24 项通过、1 项既有跳过；截图回归 5/5。本次没有更新截图测试参考图。
- Debug/Release 构建、Debug/Release Lint 通过；APK 的 3 个原生库通过 16 KB ELF/ZIP 对齐检查，签名验证通过。

## APK

`build/releases/UltraSlim-1.1.5-arm64-debug.apk`，versionCode 17，包名 `app.ultraslim.debug`。

SHA-256：`604c383ccdcc3e5e0d67b91b617f16ffdc6cb41aae7da71b6fc55884d6161259`。

沿用当前本机 Debug 签名。用户确认后，已通过 `adb install -r` 将测试机（`<device-serial>`）从 1.1.4 覆盖更新至 1.1.5 / versionCode 17；未卸载或清除数据。启动命令成功、应用进程存在；手机当时处于系统遮罩界面，未据此宣称完成真机首页视觉验收。未提交或推送 Gitea。
