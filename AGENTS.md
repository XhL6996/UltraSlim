# UltraSlim

把 Ultra HDR (gain map) 照片转码为体积更小的 HEIC / AVIF / JXL（保留 gain map），也支持纯 SDR 图片转码。Android app：Kotlin + Jetpack Compose (Material 3)，原生核心为 libultrahdr v2.0.2。

## 结构

- `app/` — Android 应用模块（Compose / Room / WorkManager / DataStore / Coil3）
- `native/` — 原生代码
  - `jni/transcode.cpp` — 转码核心（平台无关）：probe + JPEG/HEIC/AVIF/JXL → JPEG/HEIC/AVIF/JXL（HDR 路径走 libultrahdr + ISO 21496-1 jhgm/tmap，SDR 路径走直编码；支持速度档位 speedLevel 0/1/2、分辨率缩放 resizePercent）
  - `jni/ultraslim_jni.cpp` — JNI 桥接（`app.ultraslim.bridge.UltraHdrBridge`）
  - `CMakeLists.txt` — App 原生构建（Gradle externalNativeBuild 入口；`ULTRASLIM_HOST_TEST=ON` 时构建主机测试程序）
  - `deps/` — 依赖超级构建：libjpeg-turbo 3.1.1 / x265 4.1 / aom 3.12.1(仅解码) / SVT-AV1 3.0.0(AV1 编码) / libde265 1.0.16 / libheif(4a3f74bc + ISO 21496-1 gain map patch) / libjxl 0.11.1 (含 highway, brotli, skcms)
- `third_party/libultrahdr` — google/libultrahdr v2.0.2（vendored；`cmake/Findlibheif.cmake` 有一处本地修复：多个 compile definitions 的 `-D` 前缀处理）
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

- 两条转码路径，按请求自动选择：
  - **preserve（原样保留 gainmap）**：默认且唯一的 HDR 路径；主图重压缩/缩放/换格式，gainmap 像素逐值保留（HEIF/AVIF 走无损 GBR/mono 直写）。旧 gainmapScale / gainmapQuality / multiChannelGainmap 参数对保留行为无影响（仅为接口/快照兼容保留）。*不要*对 gainmap 做下采样——实验证明即使 2x bilinear 下采样也会砍掉高光增益。
  - **rebuild（重建 gainmap）**：仅在显式传 `preserveGainMap=false` 时走的 libultrahdr 全解码重编码逃生通道（自一致、已验证）。preserve 不支持的情况（>8 位增益图、容器 HDR 转 JPEG）明确报错（errorCode 31）并保持输出为空，绝不静默回退 rebuild。
- 验证方法：rendition_dump/gm_dump（host_test/）把文件解出 SDR/HDR 渲染版，在线性域（hlgInvOetf 之后）对比均值——*必须*用线性域，任何非线性 tonemap 都会因 Jensen 不等式产生伪"亮度差"。真机照片（小米 5888x4416、多通道 gainmap）与样本均已验证 ±3%。
- Android 15 及更早的平台解码器不认识 ISO 21496-1 tmap HEIC，预览一律走 `nativeDecodePreview` 原生兜底（UltraAsyncImage，带 PreviewCache）。
- 编码输出固定 8-bit（base + gainmap），所以 x265 只编 8-bit；AV1 编码走 **SVT-AV1**（比 aomenc 快数倍），AV1 解码走 dav1d（aom 解码器在大图上有横向拖影，仅保留无损 gainmap 编码用途），HEVC 解码走 libde265（支持 HEIC/AVIF 输入）。
- 速度档位（speedLevel 0/1/2）：HEVC 映射 x265 preset slow/medium/veryfast，AV1 映射 "speed" 4/6/9；经 vendored libultrahdr 补丁注入（`heifultrahdr.cpp` / `avifultrahdr.cpp`，读取全局弱符号 `ultraslim_codec_speed_level`，强定义在 transcode.cpp）。svt 插件没有 "chroma" 参数，`set_internal_color_format` 已改为容错。
- 分辨率缩放：HDR 路径用 `uhdr_add_effect_resize`（解码时生效），SDR 路径用自带 bilinear（RGBA8888）。
- 预览兜底：平台解码器不认识 ISO 21496-1 tmap HEIC（Android 15 及更早）时，`UltraAsyncImage`（ui/components/Components.kt）会走 `nativeDecodePreview`（原生解码主图 → JPEG → Bitmap）显示预览；历史/队列/详情共用。
- x265 4.1 在 Clang + CMake≥4 下需要补丁（`native/deps/patches/patch_x265.sh`）；手写 aarch64 .S 汇编含绝对重定位，Android 共享库禁止（bionic 拒绝 textrel），故只用 NEON intrinsics。
- HDR 路径：libultrahdr 解码为 RGBA1010102/HLG → 重新编码（重建 gainmap，可配置 scale/quality/多通道/速度/分辨率）。SDR 路径：libjpeg/libheif 解码 → BT.601 限定域 YUV420 → libheif 直编码（或 libjpeg 出 JPEG）。
- EXIF 透传：HDR 路径经 `uhdr_enc_set_exif_data`，SDR 路径经 APP1 marker + `heif_context_add_exif_metadata`（JPEG 输出经 `jpeg_write_marker`）。
- ABI 只出 arm64-v8a（见 `app/build.gradle.kts`）；加 x86_64 需要给依赖构建装 nasm。
- vendored libultrahdr 的本地修改：`cmake/Findlibheif.cmake`（多 define 的 -D 处理）、上述两个 encoder 文件（速度档位 + chroma 容错）。
