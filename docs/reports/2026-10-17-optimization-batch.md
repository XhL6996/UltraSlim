# 2026-10-17 优化实施与验证

第二批次优化：在 2026-10-13 审计与 `docs/TODO-optimizations.md` 的 19 项基础上，逐项**先实测再决定**。
结论是本轮真正值得做的是内存与拷贝类改动（C1/C2/C4/C3/D2），而 D3、B2/B3、E1 三项经测量收益
远低于风险，已明确记录为「不做」并保留证据。过程中另发现两个真实缺陷并修复。
未提交、推送或发布。

## 实施内容

### 原生：转换与质量解耦（C1、C2）

新增 `PreparedBasePlanes` / `PreparedGainMapPlanes`，把「构建编码平面」（与质量无关的
RGBA→YUV 转换、`heif_image` 创建）与「编码」（与质量相关）拆开，前者提到 `encodeWithRetry`
之外，转换完成后立即 `clear() + shrink_to_fit()` 释放 RGBA。`retrySmaller` 重试因此只重跑编码器，
不再重做整幅色彩转换，同时消除了 base/gm 两份 RGBA 与 YUV 平面同时驻留的峰值。

结构安全性不是假设而是读 libheif 源码确认的：编码器签名收 `const heif_image*`、经
`heif_image_get_plane_readonly` 只读平面；x265/SVT 里的 `heif_image_extend_padding_to_size`
虽然 `const_cast`，但只增大容量且幂等，不修改可见像素；`heif_image_add_plane` 有重复断言，
说明按次重建图像既不可能也无必要。`heif_image_set_nclx_color_profile` /
`set_raw_color_profile` 均为深拷贝。C2 同理：`JxlEncoderAddImageFrame`（非流式）内部会
`CopyBuffers()`，RGB 缓冲只需活到调用返回，故 `prepareJxlRgb` 也能外提。

正确性由既有 `gainmap_preservation` 保证：它断言「缓存重试的输出」与「独立低质量编码的输出」
**逐字节相同**，另有兼容 YCbCr 与 grid 分块两个专项断言。

### 原生：去掉整幅输出拷贝（C4）与像素处理（C3、D2）

`probeImageMemory` 改为接受 `BytesView` 只读视图，消除 HDR 重建路径上「整份拷贝输出仅为 probe」
的开销（重建输出可达数百 MB）。`orientPixels` 翻转改原地 swap、转置改分块（1690 例等价性验证）；
色度子采样内环补 NEON（640 万例逐值一致）。

### 判空（encOpts）

libheif 编码选项分配失败时不再解引用空指针。

### 实测后决定不做

| 项 | 实测 | 结论 |
| --- | --- | --- |
| D3 JPEG 三段拷贝 | 26MP 噪声图 q90/95/100：接管缓冲仅快 1–2%（78.89→76.91ms） | 不做 |
| B2/B3 容器重复解析 | 单次解析 0.01–0.03ms；一次 26MP 转码 2126ms，省掉全部 4 次占 0.002% | 不做 |
| E1 线程池 | spawn+join 0.03ms/次 × 约 5 次 = 0.15ms，占 0.007% | 不做 |

D3 的价值判断还纠正了一处**正确性陷阱**：`jpeg_mem_dest` 的最终指针/长度只在
`term_mem_destination`（压缩结束）写回，若在 `jpeg_mem_dest` 返回后立即读取，会把输出截断到
初始缓冲大小。实测预分配方案在 q90 产出 13,000,704 B，而真实码流 23,254,732 B——而现有测试固件
都小于 256KB 初始缓冲，**测不出这个问题**。故保留原实现，并在代码注释中写明原因，避免后人重蹈。

B2/B3 另有一处认知修正：probe 内 HEIF 的「双解析」并非冗余。实测 `uhdr_dec_get_exif` 对
HEIF/AVIF 返回 null（`uhdr.hasExif=no`），而 `probeHeifDims` 能读到 `hasExif=yes`，
即那次解析是**唯一**的 EXIF 来源，删掉会静默丢失该字段。

## 顺带修复的真实缺陷

1. **`jniLibs.excludes` 的同步盘防御是空转**。原模式 `**/* [0-9].so` 在 Gradle 的 Ant 风格语义里，
   `[0-9]` 是「匹配 `0`、`-`、`9` 之一」的**字符类**而非数字范围——用独立 Gradle 工程实测，
   两个原模式排除掉的文件数为 0。后果是 Debug APK 实际打进了
   `libandroidx.graphics.path 2.so`（陈旧冲突副本）。改用 `**/* *.so`（冲突副本一律含空格），
   实测排除全部 4 个副本、对 6 个正常文件名零误伤；并清理 `native/prefix` 下 50 个陈旧副本。
   APK 内 `lib/` 从 4 项（含 1 个副本）变为 3 项，Debug 包 40,057,115 → 40,024,257 字节。
2. **`-ffile-prefix-map` 未归一，漏掉 vendored libultrahdr**。字面量
   `"${CMAKE_CURRENT_SOURCE_DIR}/.."` 不是 `.../third_party/libultrahdr/...` 的文本前缀，
   映射完全不生效，导致 debug `.so` 残留构建机路径
   （`.../third_party/libultrahdr/lib/src/dsp/arm/gainmapmath_neon.cpp`，来自 `assert` 的 `__FILE__`；
   release 因 `NDEBUG` 去掉 assert 才不可见）。改为 `get_filename_component(... REALPATH)`
   归一到仓库根后，debug `.so` 中 `/Users/<user>`、仓库目录名均 0 次，仅剩预期的
   `/ultraslim-src`。

## 验证结果

| 检查 | 结果 |
| --- | --- |
| host ctest（5 项） | 100% 通过（transcode_regression / gainmap_preservation / upstream_color / avif_quality / preview_probe） |
| `gainmap_preservation` 重试等价 | 通过（输出与独立低质量编码逐字节相同） |
| `:app:assembleDebug` | BUILD SUCCESSFUL |
| `:app:assembleRelease` | BUILD SUCCESSFUL，19,557,787 字节 |
| `:app:testDebugUnitTest` | 通过 |
| `:app:lintDebug` / `:app:lintRelease` | 通过 |
| APK 冲突副本 | Debug/Release 均 0 个含空格条目 |
| APK 对齐 | `zipalign -c -P 16` 两个包均 Verification successful |
| 路径泄漏 | debug `.so` 中 `/Users/<user>`、仓库目录名均 0 次 |

未做真机安装与相册视觉验收：本轮改动不触及色彩映射、增益图布局与发布门禁，主机侧逐字节断言与
平台构建已覆盖其行为；`docs/COMPATIBILITY.md` 的真机结论仍然有效。

## 后续（未做，需独立立项）

B1（输出校验整读输出文件，超大输出会误判 errorCode 25）、Baseline Profile、
详情页缩放档位重解码、PreviewCache 逐出 recycle 等见 `docs/TODO-optimizations.md`。
