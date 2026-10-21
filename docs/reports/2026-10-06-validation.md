# 2026-10-06 修复与验收

基于 Gitea main `f9f5b387a8171bd9ad1a2fcf4ea7c8ea31094d1b`，修改位于 `codex/reliability-ui-polish`。

## 已实施

| 范围 | 结果 |
|---|---|
| 构建 | 修复 gain map API 宏、libultrahdr include 路径和 void 指针运算；提供同步目录外构建选项和验证脚本。 |
| 数据保留 | Room v2 保存队列、批次、冻结参数；升级保留 v1 历史；仍存在的缓存输出迁移到持久目录，仅清理无引用文件。 |
| 任务恢复 | 转码逐项检查点，任务 ID 唯一；移除待处理项后不再执行，处理中项不能移除；失败重试不替换运行批次；只清成功项。 |
| 保存与分享 | 区分待保存、保存失败、已保存；失败后无需重编码；相册文件按任务 ID 幂等；FileProvider 私有文件分享、正确 MIME、文档导出和历史批量保存/分享。 |
| 输出真实性 | 输出重新 probe、解码主图，检查 HDR/EXIF；详情存实际输出分辨率、Gain Map 尺寸、亮度参数。统计允许负收益，并说明原图保留。 |
| 元数据 | HDR 容器 EXIF 提取；JPEG marker 在解码结束前复制；快速 JPEG 合成显式传 EXIF；关闭 EXIF 仍归一化方向；GPS 清理、缩图尺寸更新、旧缩略图解绑。 |
| 色彩 | JPEG/HEIF ICC 透传、HEIF NCLX 保留；支持的 SDR NCLX 在输出 JPEG 时转成 ICC，不支持的配置明确失败。尚未完成实拍色卡量化。 |
| 编码与资源 | 速度/线程设置改为线程局部，修正 SVT 并行等级映射并启用静态图片模式；修复 SVT 3 静态首帧变量及 EOS 拒绝，支持连续编码。单任务调度、内存预算、严重热状态退避、256 MB 文件/1 亿像素边界。 |
| 首页与参数 | 一个主要选图入口、队列前置、占布局空间的底部操作栏、独立本次参数和默认参数、可折叠高级项、宽屏队列/参数双栏、150% 字体验证。 |
| 对比与历史 | 同画布裁剪、统一 SDR 解码、可访问滑杆、按需高分辨率预览；加载/失败反馈、原图重新关联；历史分页加载、保存状态筛选、批次分组。 |
| 扫描与缓存 | 最近 500 张可访问图片的范围说明、进度/取消、部分照片权限、拒绝反馈；probe 复用；同源预览请求合并、解码限流、源版本 key、磁盘字节和数量双限制。 |

## 验收证据

- 主机 `ctest --test-dir build-host-test --output-on-failure`：通过。覆盖 Apple gain map JPEG → 三格式 → 再编码、EXIF 开关、SDR ICC 往返、旋转 6/8、并发转码、损坏文件、GPS 数据擦除和循环 GPS 指针。
- Android `assembleDebug` / `assembleRelease`：通过；Release 执行 R8 与资源收缩，输出为未签名包。
- JUnit：1 项通过，检查 MIME 与正负体积统计。
- Android 15 / API 35 ARM64 设备测试：4 项通过，覆盖 Room v1 升级、队列恢复/取消、JNI 三格式 HDR 连续往返、保存失败恢复/重复保存/MIME。
- `lintDebug`：0 Error；剩余 Warning 主要是可选依赖升级、旧资源和 API/style 提示。没有为消除升级提示而整体升级工具链。
- 交互：选入两张测试图片，强制停止再启动，队列两张仍在；试转一张成功保存，另一张保留就绪。
- 视觉：实际检查手机首页、队列、同标准对比、150% 字体、宽屏双栏；模拟器尺寸和字体已恢复。
- `git diff --check`：通过。添加 Gitea 工作流，但未在远程 runner 执行。

## 尚需目标硬件验证

不将这些内容计作已验收：真实 HDR 屏幕与相册兼容性、P3/BT.2020 色卡误差、48/200 MP 峰值内存、长时间热稳态与吞吐、Android 8/9/16 真机生命周期、TalkBack 完整人工走查。200 MP 原图会被当前 1 亿像素保护上限明确拒绝；比较预览不是原尺寸分块查看器。标准 GPS 清理不保证抹除厂家私有字段。

原生编码暂不支持中途抢占；点击停止后等待当前图片完成。已完成检查点不会重复编码。旧版本已删除的缓存成品无法重建。

## 真机部署补充：16 KB 对齐

测试机（`<device-model>`）/ Android 17 启动 Debug 包时发现兼容性警告。修复主库 LOAD 对齐的同时检查了所有 JNI 库的 RELRO，避免仅处理主库而遗留警告。

- libultraslim：显式启用 max-page-size/common-page-size=16384。
- DataStore 升至 1.2.1，使用 RELRO 已对齐的官方原生库。
- graphics-path：1.0.1 和 1.1.0 官方预编译库的 RELRO 仍未满足检查；从 1.0.1 对应上游提交重编 JNI，保留同版本 Kotlin API。源码来源和许可证位于 third_party/androidx-graphics-path。
- 限定最终 APK 为 arm64-v8a，避免附带不受支持的其他 ABI。
- scripts/check_apk_alignment.py 检查 APK 内所有 .so 的 LOAD、RELRO 末端以及未压缩 ZIP 项偏移，已接入验证脚本。Debug 和 Release 均通过，Android zipalign -c -P 16 4 也通过。
- 覆盖安装后，真机正常进入首页，未出现兼容性提示，原队列保留。该机 getconf PAGE_SIZE 返回 4096；这是 Android 17 兼容性检查通过，不等同于已在 16 KB 内核运行。

检查依据：[Android 官方 16 KB 指南](https://developer.android.com/guide/practices/page-sizes)。

真机自带样本回归：2 项通过（重编 graphics-path JNI 加载注册、JPEG/HEIC/AVIF HDR 连续往返），未使用或修改用户相册图片。安装的新 APK 已替换先前提供的下载文件。


## 1.2：原始 Gain Map 与界面重整

- 取消默认 Gain Map 降采样、按预设切换通道，以及复用失败后静默重建 HDR 的回退；旧 GM 质量参数只为接口/快照兼容保留，App 转换不使用它们。
- HEIC / AVIF 的增益图独立无损编码（x265 / AOM），保留单/多通道。RGB 用 identity GBR 4:4:4、全范围；原始 ISO 元数据直传，JPEG XMP / Apple 元数据按语义转为 ISO。JPEG 直接复用增益图扫描数据，封装元数据可能变化。
- 补齐 JPEG 派生 HDR 色域与原始 ICC 的对应，容器输入保留派生 NCLX。
- 修复 libultrahdr Apple XMP 解析分支未初始化 `use_base_cg`：主机与 Android 堆栈值不同会造成色彩空间解释不一致；增加显式哨兵回归。此处为实际定位到的问题，不代表已实测用户三张原图的视觉错位。
- 增加逐像素回归：灰度/RGB、8 个 EXIF 方向、奇数尺寸与非整数缩放比、HEIC/AVIF 互转、主图缩小、原始增益图尺寸不变、JPEG 扫描数据一致、HDR 参数一致、容器 ISO 元数据逐字节一致，以及真实 Apple 样本。
- 主图质量明确为“编码质量”，100 档提示不等于无损或体积保证；新默认 82、仅保留更小结果。预设不改增益图或分辨率。
- 文件名去掉完整 UUID，记录/相册/分享名称一致；保存回归新增 FileProvider 名称、相册名称、发布后 DB 检查点之前中断的幂等恢复；统一 MediaStore volume URI。
- UI：森林绿/米白配色、独立字体层级、紧凑处理卡片、专门的输出方案面板、结果体积对比卡、成品库及按需多选；操作菜单收纳删除/导出等次要操作。修复嵌套 Scaffold 重复系统栏留白。

验证：主机两个 CTest 通过；Debug/Release 构建、两个 JUnit、lint 通过；Android API 35 ARM64 常规 5 项测试通过（额外真实选图测试默认跳过），另有选图专项测试。最终两种 APK 的全部三个 JNI 库仍通过 LOAD / RELRO / APK 16 KB 对齐检查。

真机限制：1.2 APK 可以覆盖安装到该测试机，独立测试包安装返回 INSTALL_FAILED_USER_RESTRICTED / Install canceled by user，ADB 输入事件返回缺少 INJECT_EVENTS，截图为黑屏。没有绕过设备限制，没有更改用户相册、清空数据或重跑用户队列；本次不能把真机 UI、三张用户照片的转换效果计作通过。


补充回归：两张通过系统选图器导入的 HEIC 样本，经 Android JNI 转成 AVIF 后导出到主机，24×32 RGB Gain Map 逐像素、HDR 参数均一致。完整 UI 试转/保存流程得到 18,369 → 6,947 字节，仅为此测试样本，不能推断所有照片的节省比例。检查新版成品库、对比页、宽屏、150% 字体；修复未放大时预览吞掉垂直滚动，以及竖图比例突破高度限制的问题。模拟器屏幕与字体已恢复。

边界回归：>8 位 Gain Map 明确拒绝而不截断，HEIC/AVIF HDR 转 JPEG 明确拒绝而不重编码增益图；新增 10 位 AVIF 夹具验证。该夹具同时发现 libheif tmap 解析缺少 COLR 时空指针崩溃，已在幂等依赖补丁中修复并重编两个平台。

最终交付：UltraSlim 1.2（versionCode 3）已覆盖安装到该测试机，设备 base.apk SHA-256 与交付 APK 一致；所有现有数据保留。最终 lint 为 0 Error / 41 Warning，git diff --check 通过。测试包安装及真机自动输入限制不影响主 APK 安装成功，但仍不能据此宣称用户照片的真机 HDR 显示已验收。
