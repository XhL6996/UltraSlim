# SDR AVIF 解码兼容性修复

## 实际问题文件：错误的 tmap 格式标记

用户提供 `Photo_20261011_231653_Slim_5754f6ea.avif`，表示在 Android 上打不开。该文件为 3072×4536、8-bit SDR、Display P3，含 EXIF，不含增益图；191,783 字节，SHA-256 `6a58aae2759336034d36021618ee7cf2da35813542fbf0269b46b7855a5d5388`。

原文件可以由项目 libheif、macOS ImageIO、连接 Android 真机的 ImageDecoder 解码，但 Pillow 12.2.0 / libavif 1.4.1 报 `Failed to decode image: BMFF parsing failed`。文件 `ftyp` 的 compatible brands 包含 `tmap`，而 `iinf` 中仅有主图 grid、六个 AV1 tile 和 EXIF，没有对应 tone-map item。仅取消这个错误声明，libavif 就可完整解码。

根因在本地 libheif 实验性增益图补丁：`HeifFile::set_brand` 因启用了 `WITH_EXPERIMENTAL_GAIN_MAP`，无条件给所有 HEIC/AVIF 输出加 `tmap`，包括纯 SDR。修复将添加标记的位置移到真正创建 tone-map item 的 `HeifContext::add_tmap_item`。HDR 标记仍保留，SDR 不再虚报 HDR 内容；host 和 Android libheif 均已重建安装并重新链接。

这个缺陷与奇数尺寸问题独立，影响普通单图和网格 SDR。仅验证 Android ImageDecoder 或 Apple ImageIO 会漏检它。具体第三方 Android 应用名称仍未提供，因此本轮验证的是实际文件的严格解码器故障与修复，不宣称所有应用 UI 均已验收。

### 原文件的无重编码修复

桌面另存 `Photo_20261011_231653_Slim_5754f6ea_repaired.avif`，原文件不改。删除错误 compatible brand 并同步调整 `iloc` 中的绝对偏移，修复副本为 191,779 字节。全部 `mdat` 图像/EXIF 载荷逐字节不变，ICC、尺寸和网格布局保留；项目原生预览的 JPEG 字节也与原文件解码结果完全相同。

修复副本通过 libavif、macOS ImageIO 和 Android 真机完整解码，仍为 3072×4536 / Display P3。SHA-256：`65608c555f3f02d43e9b49a47b31b928182fa952eca21701e2f104f1a23b0889`。

## 同时修复的独立问题：奇数尺寸 SDR AVIF 网格

4097×3073 的 SDR AVIF 原先以 YCbCr 4:2:0 网格补成偶数画布，再用 `clap` 裁回原尺寸。libheif 和 macOS ImageIO 可读，但连接 Android 真机报 `getPixels failed with error invalid input`。它不是上述用户实际文件的原因。

奇数尺寸 SDR AVIF 需要网格时，改用已有奇数 HDR 例外采用的 AOM 4:4:4，直接写入准确画布尺寸，不补边再裁剪。普通 SDR 单图和偶数网格仍用 SVT。该少数尺寸例外可能较慢，但不改变图像尺寸。修复后的 4097×3073 文件已通过 Android 真机、macOS 和 libavif 解码。

HEIC 奇数大网格编码方式未修改；新增 SDR 平台验证可拦截系统解码失败。纹理较多的偶数 SDR AVIF 4096×3072（4,119,843 字节）与 5888×4416（8,583,052 字节）均通过 Android 真机完整解码。

## 防回归与验证

- 主机 CTest 3/3 套件通过。所有 HEIC/AVIF 转码回归检查 `tmap` brand 与实际增益图状态一致，覆盖 SDR/HDR；另验证奇数 SDR 网格准确尺寸，原有增益图保留、ICC、EXIF、方向与色彩测试继续通过。
- 用重建后的库重新导出普通 1920×1080、偶数网格 5888×4416、用户样本 3072×4536，以及 5001×513、5002×513、513×5001 三组奇数网格，libavif 1.4.1 完整解码全部通过。
- Android instrumentation 从最终 APK 内的 JNI 编码，检查五组 SDR AVIF 不带 `tmap`，再验证平台完整尺寸解码和损坏文件拒绝。并运行 NativeCodec / Live 导入回归：7 项通过、4 项需要额外相机样本参数的原有测试跳过。
- Gradle debug APK、instrumentation APK、JVM 单测、lint 通过；JVM 19 项通过、1 项原有跳过。
- `SdrOutputValidation` 在 API 29+ 且系统声明支持目标 MIME 时，在保存前调用 ImageDecoder；失败则不发布成品，清理失败输出并保留原图。系统不支持的格式（如 JXL）不由此否定有效性。该检查是补充，不能替代上面的封装结构回归。

手机日常版本已于 2026-10-12 覆盖更新为 1.0.3（versionCode 9），启动成功；42 条历史与 42 条队列记录的 ID 集合与更新前完全一致。最终 APK 另经模拟器回归、v2 签名和 16 KB 对齐验证。交付路径 `build/releases/UltraSlim-1.0.3-arm64-debug.apk`，SHA-256 `937c64dae8e03f7ba244ce4898458f85ec4b661b7112a9212d4b84a84a5c9ad6`，与手机实际安装的 APK 一致。此版本也包含导入时忽略实况照片的开关。诊断目录 `/tmp/ultraslim-sdr-audit`；主机测试 `/tmp/ultraslim-sdr-host`；Android 构建 `/tmp/ultraslim-live-import`（独立 `.importqa` 包）。
