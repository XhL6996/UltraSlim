# 优化待办

来源：2026-10-13 全项目优化审计。已完成的见 AGENTS.md「优化批次（2026-10-13）」与提交 ad47475。

## 原生层（需要结构性重构，单独一轮做）

1. **C1 内存峰值**：HEIC preserve 路径 base.rgba 与 gm.rgba 同时驻留（1 亿像素下峰值近 1GB）。base 转完 YUV 后即可释放 rgba——但 encodeSdrWithLibheif 的奇数尺寸 AOM 4:4:4 分支还需要 RGB，需重构其签名（SdrImage → 预转换平面 + 按需保留 RGB）。同时覆盖 D1（retrySmaller 重试重做整幅 RGBA→YUV，YUV 平面与 quality 无关可外提）。
2. **B1 输出校验整读输出文件**：transcode.cpp 每条成功路径 `probeImage(outFd)` 整读输出（readAll 有 256MB 上限，超大输出会误判 errorCode 25）。方向：header-only probe 或写盘时 tee。
3. **B2/B3 重复解析**：HEIF SDR 转码全程解析容器 3 次；probe 内 HEIF 双解析仅为刷新 hasExif；JPEG 增益图最多解析 4 次（jpegChannels/decodeJpegSdr/JpegDecoderHelper/muxJpegUltraHdr）。方向：probe 结果/handle 随调用链移交。
4. **C2**：encodeJxl 额外整幅 RGB 拷贝与 rgba 并存（7B/px），可流式 AddImageFrame 或移交所有权。
5. **C3**：orientPixels 整幅二次分配 + 标量转置；翻转可原地 swap，转置可分块+NEON。
6. **C4**：HDR 重建的 memVerified 整份拷贝输出仅为 probe——probeImageMemory 改接受指针+长度视图（签名链路较宽，机械改动）。
7. **D2**：色度子采样内环无 NEON（2x2 平均纯标量，Y 平面已有 NEON）。
8. **D3**：encodeJpegToMem 输出三段拷贝（mem_dest → assign → uhdr 内部）。
9. **E1**：parallelRows 每次临时 spawn 线程（std::async），可换进程级小线程池。
10. **E2**：增益图 grid tile 编码串行（libheif 内），多 encoder 并行需改 libheif 补丁，侵入性高。
11. **F2**：依赖库（x265/aom/dav1d/libde265/libheif/libjxl）未开 LTO，收益限于库内，构建时间翻倍。

## App 层（低优先级）

12. **#16** DetailScreen 缩放跨越 1.0× 触发 2048 档重解码；两张大 bitmap 与列表缓存互相挤出。可考虑 RGB_565 或分池。
13. **#20** queryImages 把整库 URI 物化成 List 再 filterHdr；可直接消费 Flow。
14. **#7** ConversionRunner 每张图 2 次 `batch()` 取消标志查询（PK 查询开销小，缓存有 stale 风险，需设计）。
15. **#21** PreviewCache LruCache 逐出时不 recycle（native heap 靠 GC，通常可接受）。

## 构建/发布

16. **Baseline Profile**：全仓库无 baselineprofile 模块；Compose 冷启动典型收益 20-30%，值得单独立项（profileinstaller 约 200KB）。
17. **release 无 signingConfig**：确认发布渠道是否需要。
18. **wrapper 对齐**：gradle-wrapper 固定 9.6.0，本机实际跑过 9.8.0/8.14.3，建议统一到 wrapper 版本。
19. **coil 后续 minor 升级** 沿用本次模式（只用到 AsyncImage/ImageRequest/crossfade 稳定 API，但升级后仍需真机过一遍列表/详情图片加载）。
