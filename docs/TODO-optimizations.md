# 优化待办

来源：2026-10-13 全项目优化审计；2026-10-17 第二批次已按实测数据收敛。

## 已完成（2026-10-17 批次）

- **C1 内存峰值 / D1 重试重做转换**：新增 `PreparedBasePlanes` / `PreparedGainMapPlanes`，
  把 RGBA→YUV 转换（与质量无关）提到 `encodeWithRetry` 之外，转换后立即 `clear + shrink_to_fit`
  释放 RGBA；retrySmaller 只重跑编码器。结构安全性由 libheif 源码确认：编码器只读平面
  （`heif_image_get_plane_readonly`），`extend_padding_to_size` 的 `const_cast` 只增容量不改可见像素，
  `heif_image_add_plane` 有重复断言（故不可能也无需按次重建图像）。正确性由
  `gainmap_preservation` 的「缓存重试 == 独立低质量编码」逐字节断言保障。
- **C2 JXL 整幅 RGB 拷贝**：改为 `prepareJxlRgb` 在重试外转换一次；libjxl 的
  `JxlEncoderAddImageFrame`（非流式）内部会 `CopyBuffers()`，缓冲无需活过调用。
- **C4 输出校验整读**：`probeImageMemory` 接受 `BytesView` 视图，去掉重建路径的整份输出拷贝。
- **C3 orientPixels**：翻转改原地 swap，转置分块处理；1690 例等价性验证。
- **D2 色度子采样 NEON**：640 万例逐值一致。
- **encOpts 判空**：libheif options 分配失败不再解引用空指针。

## 已实测并决定不做（收益低于风险）

- **D3 encodeJpegToMem 三段拷贝**：实测 26MP 噪声图 q90/95/100 下，接管 libjpeg 缓冲
  仅比现有 `out.assign` 快 1–2%（78.89ms → 76.91ms）。用预分配 vector 交给 `jpeg_mem_dest`
  的方案**有正确性陷阱**：`term_mem_destination` 只在压缩结束才写回最终指针/长度，提前读取会把
  输出截断到初始缓冲大小（实测 B 方案在 q90 产出 13,000,704 B，而真实码流 23,254,732 B；
  现有测试固件都小于 256KB 初始缓冲，测不出来）。收益与风险不成比例，**保留原实现**，
  已在代码注释中记录原因。
- **B2/B3 容器重复解析**：实测单次容器解析 0.01–0.03 ms（HEIF/AVIF/JPEG 均如此；最大的
  32MB 文件 2.5 ms 也主要是磁盘读）。一次 26MP 真实转码耗时 **2126 ms**，故即便省掉全部
  4 次解析也只占 **0.002%**。另外 probe 里 HEIF 的「双解析」并非冗余：实测
  `uhdr_dec_get_exif` 对 HEIF/AVIF 返回 null（`uhdr.hasExif=no`）而 `probeHeifDims` 能读到
  `hasExif=yes`，即那次解析是**唯一**的 EXIF 来源，删掉会丢失该字段。**不做**。
- **E1 parallelRows 线程池**：实测 3 线程 spawn+join 开销 **0.03 ms/次**，每次转码约 5 次调用
  （≈0.15 ms），占 2126 ms 的 **0.007%**。换进程级线程池会引入静态析构顺序、fork 安全、
  JNI 环境等新风险。**不做**。
- **E2 增益图 grid tile 并行**：需改 libheif 补丁，侵入性高，维持不做。
- **F2 依赖库 LTO**：收益限于库内，构建时间翻倍，维持不做。

## 顺带修复的真实缺陷（本批次发现）

- **`jniLibs.excludes` 的同步盘防御其实是空转**：原模式 `**/* [0-9].so` 与
  `**/* [0-9].*[0-9].so` 在 Gradle 的 Ant 风格语义里，`[0-9]` 是「匹配 `0`、`-`、`9` 之一」
  的字符类，**不是数字范围**，实测两个模式一个文件都排除不掉。后果：Debug APK 里实际打进了
  `libandroidx.graphics.path 2.so`（陈旧冲突副本，来自 `app/build/intermediates/cxx/.../obj`）。
  改用 `**/* *.so`（冲突副本一律含空格；实测排除 4 个副本、对 6 个正常文件名零误伤），
  并清理了 `native/prefix` 下 50 个陈旧副本。**注意：项目在同步目录期间此防御不能撤。**
- **`-ffile-prefix-map` 源路径未归一，漏掉 vendored libultrahdr**：原写法
  `"-ffile-prefix-map=${CMAKE_CURRENT_SOURCE_DIR}/..=/ultraslim-src"` 里的字面量
  `.../native/..` 并不是 `.../third_party/libultrahdr/...` 的文本前缀，所以对 vendored 源码
  完全不生效——实测 debug `.so` 里残留
  `.../third_party/libultrahdr/lib/src/dsp/arm/gainmapmath_neon.cpp`（104 字节，来自
  `assert` 里的 `__FILE__`；release 因 `NDEBUG` 去掉 assert 才看不到）。改为先
  `get_filename_component(... REALPATH)` 归一到仓库根再映射，复测 debug `.so` 中
  `/Users/<user>` 与仓库目录名 均 **0 次**，仅剩预期的 `/ultraslim-src`。

## 仍未做（需独立立项）

1. **B1 输出校验整读输出文件**：成功路径 `probeImage(outFd)` 整读输出（`readAll` 有 256MB 上限，
   超大输出会误判 errorCode 25）。方向：header-only probe 或写盘时 tee。
2. **#16 DetailScreen 缩放跨 1.0× 触发 2048 档重解码**；两张大 bitmap 与列表缓存互相挤出。
   可考虑 RGB_565 或分池。
3. **#20 queryImages 物化整库 URI**：当前只在「整个图库扫描」入口使用（一次性遍历+逐张 probe），
   改为直接消费 Flow 收益有限。
4. **#7 ConversionRunner 每张图 2 次 `batch()` 取消标志查询**：PK 查询开销小，缓存有 stale 风险。
5. **#21 PreviewCache LruCache 逐出不 recycle**：native heap 靠 GC，通常可接受。
6. **#16(构建) Baseline Profile**：全仓库无 baselineprofile 模块；Compose 冷启动典型收益
   20–30%，值得单独立项（profileinstaller 约 200KB）。
7. **coil 后续 minor 升级**：沿用本次模式（只用到 AsyncImage/ImageRequest/crossfade 稳定 API，
   升级后仍需真机过一遍列表/详情图片加载）。

## 已确认不再需要

- **release signingConfig**：`app/build.gradle.kts:83` 已有 `publicRelease` 配置。
- **wrapper 对齐**：`gradle-wrapper.properties` 已固定 9.6.0，与 AGP 9.4.1 一致。
