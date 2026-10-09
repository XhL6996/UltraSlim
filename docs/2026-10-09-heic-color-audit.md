# HEIC 彩色增益图偏色：复现与范围确认

## 结论

当前 HEIC 输出存在两处与此前 AVIF 类似的遗漏：主图 P3 色域识别、彩色增益图互操作。
色域补丁在 `patch_libheif.sh` 中仅识别 `av1C`，HEVC 的 `hvcC` 没有覆盖。
手机实际 HEIC 的 ICC → NCLX 关联顺序会让本机 ImageDecoder 把 Display P3 识别成 sRGB。
对同一文件只交换 `ipma` 中这两项的顺序，文件长度、ICC/NCLX 内容和 `mdat` 编码数据
全部不变，系统便恢复识别 Display P3；随后仍检测不到增益图。这证明两个问题独立存在。

`attachGainMap` 的 `compatibleRgb` 条件包含 `avif`，因此 AVIF 的实际 YCbCr
兼容编码没有覆盖 HEIC。HEIC 彩色增益图仍将 G/B/R 原值存入三个平面，标记
Identity matrix、full range，并使用 x265 无损 4:4:4 编码。

原生 libheif 解出的样本逐值正确，不代表平台 HDR 渲染正确。此次 macOS 系统
解码复现了严重 HDR 色相变化，而 SDR 主图基本正常。只改变增益图颜色表示，
保持主图编码、增益图尺寸和 HDR 参数不变，即可消除样本中的严重通道均值偏差。
这是 HEIC 路径验证遗漏，不能归咎于 OPPO 原图损坏或某一个相册应用。

## 实际手机输出

- 代码基线：`e74b378`；连接手机安装 `app.ultraslim.debug`，版本 1.0.0 / code 6。
- 读取历史中最新 HEIC 记录 243 的原图和实际相册成品，未改写手机文件。
- 原图 SHA-256 与此前 40 张批次的 case-157 一致。
- 实际成品：4416×5888，RGB 增益图 2208×2944，2,898,206 字节。
- 原生逐像素增益图和 HDR 元数据检查通过。
- macOS Core Image 的线性 HDR 通道均值：

| 通道 | 原图 | 手机 HEIC |
| --- | ---: | ---: |
| R | 0.35203 | 0.98880 |
| G | 0.32611 | 0.19372 |
| B | 0.29184 | 1.04852 |

Android 真机 ImageDecoder 将实际成品主图识别为 sRGB，原始检查在色域断言处失败。
只交换色彩属性顺序后，主图恢复 Display P3，SDR MAE 为 0.453/255，但 `hasGainmap()`
仍为 false。增益图实际仍在文件内；这与编码时丢掉增益图是不同问题。

## 批次对照

使用之前保存的同批 40 张原图，按质量 92、原尺寸、速度档 2、无主图重试生成
HEIC。实验编码器在 `/tmp/ultraslim-heic-audit-20261009/` 单独编译；没有修改
生产转码代码。RGB 实验仅把 `compatibleRgb` 的格式限制移除，并显式传兼容参数。

构建产物存在差异：本机忽略目录中的 host libheif 已带 `av1C || hvcC` 的色彩排序
修改，而仓库可复现补丁和 Android 依赖仍仅含 `av1C`。因此 host 输出本来就是
NCLX → ICC，不能用 host 的 P3 通过替代安装包验收。此次已通过实际手机文件的
元数据顺序实验单独复现；正式修复须统一补丁并重建两端依赖。

| 样本和存储方式 | 原生验证 | macOS 线性 HDR 通道均值 |
| --- | --- | --- |
| 22 张单通道，现有严格 HEIC | 22/22 逐值及元数据通过 | 全部在 ±3% 内，最大绝对偏差 1.143% |
| 18 张 RGB，现有严格 HEIC | 18/18 逐值及元数据通过 | 18/18 至少一通道超出 ±3%，最大绝对偏差 259.269% |
| 同 18 张 RGB，实验 YCbCr 4:4:4 HEIC | 18/18 每样本每通道误差 ≤3/255、尺寸与元数据一致 | 全部在 ±3% 内，最大绝对偏差 1.118% |

YCbCr 实验采用 BT.601 限定域转换，x265 无损保存转换后的平面，仍有 RGB/YUV
量化误差。它不是原始 RGB 逐值无损。这里的均值统计不等于逐像素无差异或相册
UI 视觉验收；macOS Core Image 结果也不能代替 iPhone「照片」验收。

## Android 的额外问题

在同一台 API 37 真机使用完整尺寸 ImageDecoder 解码：

| 样本 | 平台读取增益图 |
| --- | --- |
| case-157 严格 Identity GBR 4:4:4 HEIC | false |
| case-157 实验 YCbCr 4:4:4 HEIC | false |
| case-157 实验 YCbCr 4:2:0 HEIC | true |
| case-118 严格单通道 HEIC | false |

case-157 的实验 4:2:0 输出通过平台 P3、HDR 参数及线性 HDR 均值检查，RGB 均值
偏差为 +0.183% / -0.185% / +0.468%。这是单样本诊断，不是整批 Android 修复验收。
对照定位到 HEVC 增益图编码形式的兼容差异；未追踪厂商解码器内部实现。
不能根据这组结果泛称 Android 不支持所有 HEIC HDR。

4:2:0 会对彩色增益图的色度进行下采样，不符合项目严格保留契约，因此只在
临时实验中使用，未进入产品。Android 摄像头的 HEIC HDR 实现也专门把单通道
增益数据放入 YUV420，并把 U/V 填为中性值，见
[AOSP HeicCompositeStream](https://android.googlesource.com/platform/frameworks/av/+/HEAD/services/camera/libcameraservice/api2/HeicCompositeStream.cpp)。
该源码仅说明上游使用的编码形式，不证明本机相册走相同内部调用链。

## 修复要求

1. 将色彩属性排序补丁覆盖 HEVC，统一 host/Android 依赖产物，使用安装包实际导出文件验证。
2. HEIC 的彩色增益图兼容策略必须独立覆盖，不能继续沿用“AVIF 已修复即全部格式已修复”的判断。
3. 保留严格模式语义；不得静默将增益图转为 4:2:0 或把有量化误差的路径标为原样保留。
4. 面向相册的 HEIC 输出也需要平台 HDR 可读性检查。当前 Worker 只对兼容 AVIF 调用
   `AvifOutputValidation`，HEIC 可能被当作保留 HDR 成功发布，但本机系统只能读 SDR。
5. 正式实现需覆盖 RGB/单通道、大图、奇数尺寸、方向及各速度档，再做 Android 与 Apple
   双端对照；不能仅将 AVIF 的 4:4:4 开关扩展到 HEIC 就宣称完成。

本次交付为排查结果，没有发布或安装新的修复版本。

## 证据与复验

日志、40 张统计 JSON 和临时编码器构建脚本保存在忽略目录
`build/validation-heic-color/2026-10-09/`。原图、导出图和手机数据库仅留在临时目录，
未加入仓库。

```sh
cmake --build build-host-test --parallel 4
build-host-test/transcode_test source.jpg strict.heic 92 4 60 2 100 1 2 0 0
build-host-test/gainmap_preservation --compare source.jpg strict.heic
xcrun swift native/host_test/AppleColorAudit.swift source.jpg strict.heic
scripts/check_android_color.sh DEVICE source.jpg strict.heic 12000 linear
```

注意：AppleColorAudit 打印统计，不因色差自动返回失败；批次脚本计算并报告 ±3%
均值标准。Android 检查必须读取实际退出码，不能因后续 `cat` 日志成功而当作检查通过。
