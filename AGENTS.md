# UltraSlim

把 Ultra HDR (gain map) 照片转码为体积更小的 HEIC / AVIF / JXL（保留 gain map），也支持纯 SDR 图片转码。Android app：Kotlin + Jetpack Compose (Material 3)，原生核心为 libultrahdr v2.0.2。

## 结构

- `app/` — Android 应用模块（Compose / Room / WorkManager / DataStore / Coil3）
- `native/` — 原生代码
  - `jni/transcode.cpp` — 转码核心（平台无关）：probe + JPEG/HEIC/AVIF/JXL → JPEG/HEIC/AVIF/JXL（HDR 路径走 libultrahdr + ISO 21496-1 jhgm/tmap，SDR 路径走直编码；支持速度档位 speedLevel 0/1/2、分辨率缩放 resizePercent）
  - `jni/ultraslim_jni.cpp` — JNI 桥接（`app.ultraslim.bridge.UltraHdrBridge`）
  - `CMakeLists.txt` — App 原生构建（Gradle externalNativeBuild 入口；`ULTRASLIM_HOST_TEST=ON` 时构建主机测试程序）
  - `deps/` — 依赖超级构建：libjpeg-turbo 3.1.1 / x265 4.1 / aom 3.12.1(仅解码) / SVT-AV1 3.0.0(AV1 编码) / libde265 1.0.16 / libheif(4a3f74bc + ISO 21496-1 gain map patch) / libjxl 0.11.1 (含 highway, brotli, skcms)
- `third_party/libultrahdr` — 官方 google/libultrahdr v2.0.2（vendored，基线 `e5f5a022`）；来源与 5 个本地修改文件见 `third_party/libultrahdr/ULTRASLIM_UPSTREAM.md`。不是独立 Git 仓库。
- `native/prefix/<flavor>/` — 依赖产物（`host`、`android-arm64-v8a`），被 .gitignore 忽略

## 构建

前置：JDK 17（`/opt/homebrew/opt/openjdk@17`）、Android SDK（`~/Library/Android/sdk`，`local.properties` 指向它）、NDK 27.2.12479018、cmake + ninja（brew）。

```bash
# 1. 构建原生依赖（每 ABI 一次；产物进 native/prefix/）
native/deps/build_deps.sh android arm64-v8a ~/Library/Android/sdk/ndk/27.2.12479018 26

# 2. 构建 APK
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew :app:assembleDebug
```

## 主机冒烟测试（不依赖设备，改动 transcode.cpp 后必跑）

```bash
native/deps/build_deps.sh host                      # 主机依赖（一次性）
cmake -G Ninja -S native -B build-host-test -DULTRASLIM_HOST_TEST=ON -DCMAKE_BUILD_TYPE=Release
cmake --build build-host-test --parallel
build-host-test/transcode_test <输入图片> <输出.heic|.avif> [quality=90] [gainmapScale=4] [gainmapQuality=60]
```

测试样本：`third_party/libultrahdr/tests/data/`（Apple gainmap JPEG、SDR JPEG）。Google 风格 Ultra HDR 样本可用 `build-uhdr-app/ultrahdr_app` 生成（先 `-m 1` 解码成 raw rgba1010102，再 `-m 0 -a 5` 编码）。

## 关键实现说明

- **HEIC 相册兼容**：App 新任务默认开启 `heicGainmapCompatibility`，与 AVIF 开关独立；原生/JNI 默认 false，旧快照缺字段按 false。兼容增益图采用 HEVC YCbCr 4:2:0：RGB 转 BT.601 限定域，存在色度采样与舍入损失；单通道使用 full-range Y + 中性 U/V，保持灰度值。保留增益图尺寸和 HDR 参数，不能标为 RGB 逐值无损。严格关闭时仍用原有 GBR/mono；部分相册可能偏色或不识别 HDR。API 34+ 保存兼容 HEIC/AVIF 前必须由 `HdrOutputValidation` 确认平台读取增益图，否则不发布。主图边长 >4096 的 HEIC 也采用 2048 网格，解决单图解码失败；HEVC ICC 和 AV1 一样排在 NCLX 之后。tmap 的 HLG 标记不是本次偏色原因，不改为 PQ。
- **AVIF 相册兼容**：App 新任务默认开启 `avifGainmapCompatibility`；用户显式关闭仍保留，原生/JNI 默认 false，旧快照缺字段按 false。彩色增益图转换为实际 BT.601 限定域 YCbCr 4:4:4，再 AOM 无损编码；不缩小或重建，不保证 RGB 逐值无损。关闭开关时保留 Identity GBR，但必须提示部分 Apple / Android 相册会偏色。不要将兼容模式标为逐值无损。
- AVIF 增益图超过 512 像素时使用 512×512 HEIF grid，避免单包超出 MediaCodec 输入缓冲。主图任一边超过 4096 时用 2048×2048 grid，保持原分辨率、ICC/NCLX 和 EXIF。`patch_avif_grid.py` 同时修复 libheif grid 的内存 ISPE，防止后续 tmap 写成 0×0；分块不得复用单图 gainmap 缓存。
- 奇数尺寸 HDR AVIF 主图使用 AOM 4:4:4，避免 4:2:0 偶数画布及仅主图 clap 使系统丢失 gainmap。通常尺寸仍用 SVT。该例外比 SVT 慢，但不裁剪、不缩放原图或 gainmap。
- App 在 API 34+ 保存兼容 HDR AVIF 前检查平台能否解码主图并读出 gainmap；失败不保存，保留原图并明确提示。API 34 以下没有此系统检查能力。平台可读不代表色彩逐值无损或所有相册视觉验收通过。

- 两条转码路径，按请求自动选择：
  - **preserve（原样保留 gainmap）**：默认且唯一的 HDR 路径；主图重压缩/缩放/换格式，严格模式下 gainmap 像素逐值保留（HEIF/AVIF 走无损 GBR/mono 直写）；显式相册兼容选项按上文规则转换，不承诺彩色样本逐值无损。旧 gainmapScale / gainmapQuality / multiChannelGainmap 参数对保留行为无影响（仅为接口/快照兼容保留）。*不要*对 gainmap 做下采样——实验证明即使 2x bilinear 下采样也会砍掉高光增益。
  - **rebuild（重建 gainmap）**：仅在显式传 `preserveGainMap=false` 时走的 libultrahdr 全解码重编码逃生通道（自一致、已验证）。preserve 不支持的情况（>8 位增益图、容器 HDR 转 JPEG）明确报错（errorCode 31）并保持输出为空，绝不静默回退 rebuild。
- 验证方法：rendition_dump/gm_dump（host_test/）把文件解出 SDR/HDR 渲染版，在线性域（hlgInvOetf 之后）对比均值——*必须*用线性域，任何非线性 tonemap 都会因 Jensen 不等式产生伪"亮度差"。真机照片（小米 5888x4416、多通道 gainmap）与样本均已验证 ±3%。
- Android 15 及更早的平台解码器不认识 ISO 21496-1 tmap HEIC，预览一律走 `nativeDecodePreview` 原生兜底（UltraAsyncImage，带 PreviewCache）。
- 编码输出固定 8-bit（base + gainmap），所以 x265 只编 8-bit；AV1 编码走 **SVT-AV1**（比 aomenc 快数倍），AV1 解码走 dav1d（aom 解码器在大图上有横向拖影，仅保留无损 gainmap 编码用途），HEVC 解码走 libde265（支持 HEIC/AVIF 输入）。
- 速度档位（speedLevel 0/1/2）：HEVC 映射 x265 preset slow/medium/veryfast，AV1 映射 "speed" 4/6/9；经 vendored libultrahdr 补丁注入（`heifultrahdr.cpp` / `avifultrahdr.cpp`，读取全局弱符号 `ultraslim_codec_speed_level`，强定义在 transcode.cpp）。svt 插件没有 "chroma" 参数，`set_internal_color_format` 已改为容错。
- 分辨率缩放：HDR 路径用 `uhdr_add_effect_resize`（解码时生效），SDR 路径用自带 bilinear（RGBA8888）。
- 预览兜底：平台解码器不认识 ISO 21496-1 tmap HEIC（Android 15 及更早）时，`UltraAsyncImage`（ui/components/Components.kt）会走 `nativeDecodePreview`（原生解码主图 → JPEG → Bitmap）显示预览；历史/队列/详情共用。
- x265 4.1 在 Clang + CMake≥4 下需要补丁（`native/deps/patches/patch_x265.sh`）；手写 aarch64 .S 汇编含绝对重定位，Android 共享库禁止（bionic 拒绝 textrel），故保持 `ENABLE_ASSEMBLY=OFF`，通过 `ULTRASLIM_ARM_INTRINSICS` 单独编译并注册 PIC-safe NEON intrinsics；不能仅设置 `HAVE_NEON`。16×16 DCT 使用 C++ intrinsic 实现，依赖汇编的 planar 预测保留 C 回退。
- HDR 路径：libultrahdr 解码为 RGBA1010102/HLG → 重新编码（重建 gainmap，可配置 scale/quality/多通道/速度/分辨率）。SDR 路径：libjpeg/libheif 解码 → BT.601 限定域 YUV420 → libheif 直编码（或 libjpeg 出 JPEG）。
- EXIF 透传：HDR 路径经 `uhdr_enc_set_exif_data`，SDR 路径经 APP1 marker + `heif_context_add_exif_metadata`（JPEG 输出经 `jpeg_write_marker`）。
- ABI 只出 arm64-v8a（见 `app/build.gradle.kts`）；加 x86_64 需要给依赖构建装 nasm。
- vendored libultrahdr 的本地修改：`cmake/Findlibheif.cmake`（多 define 的 -D 处理）、两个 encoder 文件（速度/线程 + chroma 容错 + 质量映射）、`ultrahdrcommon.h`（质量映射）、`jpegrutils.cpp`（Apple `use_base_cg` 初始化）。v2.0.2 之后官方 main 另有颜色与元数据修复，已选择性合入 RGB 绿通道、ARM NEON 系数、sRGB NCLX 三项修复（924967a759 / 2472ff7ad1 / b4880a1c21），ISO 布局未合入；审计记录见 `ULTRASLIM_UPSTREAM.md`，不能直接把官方新补丁视为当前 preserve 路径偏色的根因。

## 性能与历史页（2026-10-08）

- SVT-AV1 使用 `COMPILE_C_ONLY=OFF`，ARM 优化由运行时 CPU 检测分派，禁止全局强制 SVE2 ISA。
- `retrySmaller` 在原生层仅重试一次主图质量（下降 5，最低 80）；复用解码/缩放结果。非分块 HEIF 增益图码流缓存限定在一个 `GainMapRetryScope` 内，图像/编码格式/增益图参数必须不变，退出或异常时清理；JXL 复用同一 payload 的 jhgm。此改动依赖重建 libheif 本地补丁。
- HEIC 增益图随速度档位采用 slow/medium/veryfast；AVIF 无损增益图采用 AOM speed 4/6/8，仍保持像素逐值一致。
- 百万像素以上的缩放/YUV 预处理最多 4 个工作线程，编码前 join；批次仍逐张转换，避免多张全分辨率 HDR 同时驻留。
- 历史按本地自然日分组，右滑日期标题确认删除；按日期查询包含当前分页外、同筛选条件的记录。只删除应用内成品及记录，已存相册图片不删除。
- 交互测试可用 `-PultraslimDebugSuffix=.perfqa` 构建独立测试包，避免污染日常使用的历史。同步目录建议外置 `ULTRASLIM_BUILD_ROOT` 并使用 `-Pkotlin.incremental=false`。

## 批量规模与防御性缺口（2026-10-10）

- probe 次数收敛：`transcodeImage` 的输入 probe 把已初始化的 uhdr decoder 直接移交 `transcodeUnchecked`（JXL 分支复用 probe 结果，SDR 不再建 decoder）；rebuild 路径从内存缓冲校验输出，不再整读输出文件；`TranscodeResult` 携带 hasGainMap/hasExif/maxContentBoost，worker 不再对输出做第二次 probe。preserve 路径的输出元数据由 `tryReuseGainMap` 直接报告。
- 所有解码路径共享 1 亿像素上限 `kMaxPixels`（JXL 此前缺失）；JXL box 上限为 `kMaxBoxBytes`。
- 批次快照解析（decodeDefaults/JSONArray）必须容错：旧快照缺字段按默认值，损坏批次显式置 FAILED，不得在 checkpoint 循环外抛异常静默死亡。仅两个兼容开关例外：缺字段按 false（像素精确语义）。
- 前台服务 API 35+ 用 `FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING`（manifest 同时声明 dataSync|mediaProcessing）；`setForeground` 失败只标记未完成项；温控重试有指数退避与 8 次上限，超限停回 READY 等手动继续。
- 历史页展开态按行虚拟化（每条记录独立 LazyColumn item，折叠态不发射行）；首页队列流经 150ms 采样后再进 UI 状态；回收站原图解析（逐条 MediaStore 查询）只在 Default 线程做，组合期只读缓存。
- HDR 扫描（整库/相册）经 `probePermits` 并发（4 路），probe 是轻量头部解析不是编码；PreviewCache 解码在 diskLock 之外、原生解码槽位 2。转码写盘路径必须检查 flush/close（stdio 缓冲写失败到 close 才暴露），readAll/writeAll 对 EINTR 重试。`apple-live` 本机跑 `swift test` 必须外置 scratch 目录（`--scratch-path /tmp/...`），iCloud 同步目录的 FileProvider xattr 会让 codesign 拒绝测试包；native/deps 依赖声明必须带 URL_HASH（改版本时重新计算 SHA256）。

## Apple Live Photo 导出（2026-10-10）

- Android HEIC 成品详情菜单导出 ZIP（AppleLiveBundle/AppleLiveExporter），包内为照片、原视频与时间点；本身不是 Apple Live Photo。保留 Android 原有动态导出。
- `apple-live/` 是 macOS 15+ SwiftPM 配套工具，`script/build_and_run.sh` 构建/启动。只修改 HEIC 配对元数据，视频 AVFoundation passthrough 成 MOV；PhotoKit 识别后用户点击导入「照片」。不能把只传 ZIP/HEIC 到 iPhone 说成可自动成为 Live Photo。
- HEIC 配对时不经过 ImageIO 重编码主图/增益图。改 iloc/iinf/iref 必须验证 Apple ImageIO；升级表版本可能导致 libheif 可读但 Apple 不可读。EXIF 序列化临时图的 1x1 尺寸必须还原。
- 原图保留/兼容仍为默认；用户允许研究重建，但尚未切换。详见 docs/2026-10-10-ultrahdr-rebuild-evaluation.md 与 apple-live/README.md。
