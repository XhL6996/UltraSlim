# HEIC 偏色修复验证（1.0.1 / code 7）

## 修复内容

基于 f0b9ad9 的 ICC 排序修复，重新构建 host 与 Android libheif，确认两端 HEVC 的 ICC 均位于 NCLX 之后。原先只修 host 产物不能证明 APK 正确。

增加独立的 `heicGainmapCompatibility`：新任务默认开启，原生/JNI 默认关闭，旧任务快照缺字段按关闭读取，已有 AVIF 选项不受影响。HEIC 兼容模式使用真实 BT.601 YCbCr 4:2:0，x265 无损保存转换后的平面；单通道为 full-range Y + U/V 128，灰度值保留；RGB 有色度采样和舍入损失。增益图尺寸、HDR 参数保持不变，不重建增益图。UI 明示该差异。严格关闭时继续原来的逐值 GBR/mono 路径。

HEIC 主图大于 4096 像素时采用 2048×2048 网格。原来 case-119（8192×6144）在 Android 解码失败，分块后保持原尺寸并可读取 HDR。增益图未新增分块。

保存前检查统一为 `HdrOutputValidation`：API 34+ 兼容 HEIC 和 AVIF 都要求系统解码成功并读到 gainmap，否则不发布成品。API 34 以下无该检查能力。tmap 的 HLG 标记保持原样；它描述 HDR 派生图，不能通过盲改为 PQ 修复颜色。

## 验证结果

- 主机 CTest：2/2 套件通过。包括单通道/RGB、8 种方向、奇数增益图尺寸、主图缩放、兼容与严格切换、质量重试、HEIC 大图网格（偶数及奇数主图尺寸），原有 AVIF/JXL/严格保留回归继续通过。
- 同批 40 张真实原图，以质量 92、原尺寸、速度 2 转成最终 HEIC：macOS Core Image 40/40 通过 ±3% 线性 HDR 通道均值阈值，最大绝对偏差 1.836%。
- 同一批输出由连接的 Android API 37 真机 ImageDecoder 完整尺寸解码：40/40 通过 P3 色域、增益图存在、尺寸、HDR 参数和 ±3% 线性 HDR 均值检查；最大绝对通道均值偏差 0.527%。
- RGB 色度采样确有损失：该批 Android 增益图单通道平均绝对误差最高 4.463/255，不能把均值通过说成逐像素一致。
- 模拟器 instrumentation：`PersistenceTest` 和 `NativeCodecTest` 报告 OK（12 tests）；另对 case-157 彩色、case-118 单通道、case-119 超大图分别显式运行 APK JNI 导出测试。最终待安装 APK 也逐个运行这三个测试，不以不同构建目录的库哈希假定一致。
- App 兼容开关在小屏模拟器实际点击验证：开启显示兼容和有损说明，关闭显示逐值保留及相册限制。
- Gradle Debug 构建、JVM 单元测试完成；APK 3 个原生库均通过 16KB LOAD/RELRO/ZIP 对齐，apksigner 验签通过。

## 范围与使用

这些是系统解码和像素数值验证，未宣称 iPhone「照片」与 Google 相册 UI 已全量视觉验收。奇数尺寸等其他输入若平台无法读取 HDR，兼容模式会明确失败，不能静默保存 SDR。旧成品不会自动修正，需从原图新建转换任务，保留“HEIC 相册兼容”开启。

详细批次与 JNI 日志保存在本机 `build/validation-heic-color/2026-10-09-final/`。原图没有修改。

## 最终安装包复核与安装

最终 APK（非另一个 QA 包）经模拟器 JNI 导出的 case-157、118、119，均通过 Android 真机完整尺寸颜色/HDR 检查以及与 App 相同的 1600 边长采样读取检查；三者都读到 Display P3 和 gainmap。Apple 线性 HDR 通道均值最大绝对偏差分别为 0.344%、1.007%、1.835%。

已使用 `adb install -r` 覆盖更新连接的手机，安装返回 Success；读回版本 1.0.1 / code 7，应用已打开。没有卸载、清数据或改写相册旧文件。

APK：`build/releases/UltraSlim-1.0.1-arm64-debug.apk`。
SHA-256：`6d8ef65cc451ee92f4141258a314498023e67ffa8e79e0d29b807ca74148b5fc`。

## iPhone 用户反馈后的复核（尚未闭环）

用户反馈开启兼容、通过 LocalSend 传到 iPhone 自带「照片」后仍偏绿，并提供“下午 17:19 编辑”的时间。该时间不能替代文件内容或导出记录来匹配样本，不能据此认定用户看的是旧文件，也不能继续宣称 iPhone 显示已修复。

此次两次重新读取连接 Android 手机的历史与 MediaStore，最新三张 HEIC 为 15:04 批次（记录 247–249，媒体 772–774），该手机 1.0.1 安装时间为 15:20:52。批次快照只有 AVIF 兼容参数，没有 HEIC 兼容参数。实际拉取记录 247，确认增益图 hvcC chroma=3（4:4:4）、NCLX matrix=0/full-range，即旧 Identity GBR 路径。该文件在 macOS 仍复现严重 HDR 通道偏差。

用相同原图明确开启 HEIC 兼容另生成 `HEIC_compat_check_20261009.heic`，确认增益图 hvcC chroma=1（4:2:0）、matrix=6/limited。主机和 Android 数值验证通过，但这仍未覆盖用户所述 iPhone 文件的实际显示。当前缺少对 iPhone 上那份偏绿文件的精确匹配；需要用户提供实际通过 LocalSend 传输的 HEIC 文件，不能凭编辑时间或现有均值测试再修改编码参数。

诊断文件与日志：`build/validation-heic-color/iphone-followup/`。本轮未修改编码实现、用户设置或手机相册文件。

### 用户提供的实际 HEIC 已匹配

用户随后提供 `<local-samples>/Photo_20261009_150412_Slim_07ac9bea.HEIC`。SHA-256 为 `04ac34e7b64cfffa4f03266082a257ce5235d30e44b9d3a41d7eb6bda4854921`，与手机记录 247 拉取的文件完全一致（2,898,206 字节）。文件仍是 GBR 4:4:4/full-range/matrix 0 增益图，未经过 1.0.1 的 HEIC 兼容编码。当前可确认用户反馈的这一份是旧输出，不应以它判定新版兼容处理仍偏色，也不能把这次现象归咎于传输改变了文件或 iPhone 解码器。

为排除文件混淆，已把同原图开启兼容生成、并通过本轮 Android/macOS 数值检查的输出另存为 `<local-samples>/UltraSlim_1.0.1_HEIC_compatible_check.HEIC`（2,562,043 字节；SHA-256 `ccbc18b28f6f0d878379abb9593a9f5d405132f281e77702c321fddf499b2010`）。待用户以该明确命名的文件验证 iPhone「照片」显示。本轮无需再改动转码代码。

### 用户确认新版对照文件显示正确

用户随后明确反馈“这个对了”，对应前述单独命名的兼容 HEIC 对照文件。这补上了该样本的 iPhone「照片」用户视觉验证；不扩展为其他照片、iOS 版本或严格 GBR 模式的全面兼容结论。
