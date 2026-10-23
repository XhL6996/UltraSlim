# PR 设备回归追加记录（2026-10-24）

基于物理设备 Android API 37，使用隔离的 `app.ultraslim.verifyqa` 测试包。

已通过：时间/文件夹/文件名/原图保护 25 项；手势回归 6 项；AdaptiveUiTest 的折叠/书本、历史详情和 workspace 场景通过。后台回归中，服务超时、清空冷却队列、屏幕关闭编码均通过。

修复并重新编译：Android 17 兼容的 Espresso 3.7.0；测试 Provider 改为 Java，避免测试 APK 独立进程缺 Kotlin runtime；FileProvider 测试路径；QA 队列隔离；物理设备 UI 测试显式 opt-in；大窗口 hinge 尺寸；大型导入 fixture 的批量发布/删除；导入测试错误事件即时报告。

递归导入与 1001 项相册导入均通过，严重热状态等待单独通过（107.095 秒，1/1），冷却取消/恢复与旧 backoff 取消也通过。

focused UI group 最终 32/32 通过。

无 EXIF 图片的图库行为也已记录：该设备会将真实文件 mtime 投影为 MediaStore `DATE_TAKEN`。真实文件 mtime、字节内容和带 EXIF 输入的拍摄时间均单独通过；测试不再把 provider 控制的索引误判为文件 mtime。

Android 17 的 Espresso 修复依赖为 `androidx.test.espresso:espresso-core:3.7.0`，仅进入测试 APK；AAR SHA-256 为 `5dd90e366838bf044cb52eae06474debd285df18a7a77c40441ac8e8951bb00f`。
