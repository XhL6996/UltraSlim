# 1.2.2：AVIF 偏色与 HDR 读取失败的批次修复

## 样本与用户反馈的对应关系

手机历史批次 `da1182f1-4dbc-413e-8a7b-1c1ac96cdf7e` 共 40 张（118–157），
均为旧版逐值保留输出；历史 161 的 1,919,124 字节文件才是新兼容输出。
用户确认“整批旧输出错误，只有新 1.9 MB 正常”，不是“新版重导整批后出现回归”。
此前据此撤回默认推荐的解释不准确，且把记录 119 的解码失败误记成原图失败；
现在按源文件和目标文件的调用位置确认：119 是旧 AVIF 主图解码失败。
升级 App 不会重写既有相册文件，旧输出需从原图重新转换。

本次使用已有授权的这 40 组原图，在临时目录生成新输出，未覆盖相册、原图或历史。
18 张为 RGB 增益图，22 张为单通道增益图。相机原文件没有加入 Git。

## 修复内容

1. 彩色 AVIF 增益图采用实际 BT.601 限定域 YCbCr 4:4:4，编码器无损保存这些平面。
   原始 Identity GBR 在 Android 和 Apple 解码链路均已复现错误，不能只归因于 Google 相册。
   RGB/YUV 转换有量化误差，不能称为 RGB 逐值无损。原始严格模式仍可显式选择，UI 说明风险。
2. 大增益图使用 512×512 HEIF grid，保持完整尺寸、像素位置和 HDR 元数据。
   记录 155 原增益图单包 5,357,638 字节，Android 软件解码器只分配 4,945,920 字节输入缓冲。
   新分块避免把整个增益图塞进一个解码包。主图重试期间分块绕过原单图缓存，避免串块。
3. AVIF 主图超过 4096 像素时用 2048×2048 grid，保持原分辨率与 ICC/NCLX。
   这同时解决记录 119（8192×6144）旧主图无法被系统解码的问题。
4. 修正 libheif 新建 grid 仅写文件 ISPE、未更新内存属性的问题，否则后续 tmap 会写成 0×0。
5. 奇数尺寸 HDR AVIF 主图使用 AOM 4:4:4，避免 4:2:0 画布补边加 clap 后，
   主图与增益图的变换属性不一致导致系统忽略 HDR。常见偶数尺寸继续使用 SVT。
6. API 34+ 在保存兼容 HDR AVIF 前，由系统 ImageDecoder 确认主图和增益图可读。
   不支持时明确失败并清理未完成输出，不把平台只读到 SDR 的结果直接保存到相册。
   API 34 以下没有这项系统验证。此检查不等于逐像素颜色验证。
7. App 新任务默认使用相册兼容；用户已明确保存的开关值、旧队列快照保持原含义。
   历史 UI 的日期右滑删除、跨分页日期删除、选择状态和底栏宽度一并回归。

Grid 与 tone map 都是 AVIF 容器已有结构，参见 [AVIF 1.2](https://aomediacodec.github.io/av1-avif/v1.2.0.html)。
本地补丁已从干净上游压缩包重新应用，并与实际构建源码逐文件比对一致；超级构建跟踪补丁文件变化。

## 验证结果与边界

| 检查 | 结果 |
| --- | --- |
| 40 张原生增益图像素、尺寸、HDR 元数据 | 全部通过；mono 逐值一致，RGB 每通道每像素误差不超过 3/255 |
| Android 真机平台 HDR 存在性与参数 | 40/40 通过，没有一张再丢失 gainmap |
| Android 平台解码后线性 HDR 通道均值 | 40/40 在原有 ±3% 标准内，最大绝对偏差 0.9814% |
| macOS ImageIO/Core Image 线性 HDR | 40/40 保留 headroom，最大通道均值偏差 1.8614% |
| 原 Android 像素差阈值（SDR MAE≤3、map MAE≤2） | 39/40；记录 143 仍超阈值，没有删掉失败记录或放宽原像素阈值 |
| 主机 CTest | 2 套通过，包括边缘分块、奇数尺寸、旋转、跨格式、重试、元数据和严格无损 |
| Android UI/持久化/原生测试 | 12 项报告通过（可选相机测试另行执行） |
| Debug/Release、14 项 JVM 单测、Lint、16 KB 对齐 | 通过 |

记录 143 的原尺寸增益图 MAE 为 2.60 / 1.62 / 4.48（/255），有符号均值偏差为
+0.21 / -0.11 / +0.65（/255）。不能据此说局部颜色或细节完全一致。
其线性 HDR 通道均值偏差为 -0.245% / -0.040% / -0.299%，逐像素归一化 MAE
最大 7.11%。这是保留的质量限制，不应掩盖成“所有数值检查通过”。
高频合成彩色图的系统增益图像素误差也会超过 2/255，兼容模式不是严格无损的替代品。

Android 线性检查按 [Gainmap API 公式](https://developer.android.com/reference/android/graphics/Gainmap)，
在原始位深解码的 SDR 主图上使用原色空间 EOTF，结合平台解出的增益图和参数，
按全 HDR headroom 重建；按固定网格采样并报告均值与 MAE。仅支持 SDR base-space 增益图，
其他方向/计算色域明确拒绝。它不经过显示器 tone mapping，也不是 Google 相册 UI 截图或 GPU 验收。
旧错误输出作为负对照仍会失败：记录 157 的绿色通道均值偏差约 -32.57%。
原 `pixels` 模式与阈值保留，另以显式 `linear` 模式执行已有线性域标准，二者不能混淆。

macOS 检查使用浮点 extended linear sRGB；此证据不等于 iPhone「照片」视觉验收。
Android 15 模拟器的相机样本 JNI 编码测试通过，记录 155 输出与主机产物 SHA-256 完全一致。
该模拟器系统不识别其 HDR；平台拒绝路径测试也通过，新保存检查会阻止静默降为 SDR。
不能将一台设备的通过表述成所有系统和所有相册全部兼容。

## 复验命令

```sh
cmake --build build-host-test --parallel 4
ctest --test-dir build-host-test --output-on-failure
build-host-test/gainmap_preservation --compare-compatible source.jpg output.avif
scripts/check_android_color.sh DEVICE source.jpg output.avif 12000 pixels
scripts/check_android_color.sh DEVICE source.jpg output.avif 12000 linear
xcrun swift native/host_test/AppleColorAudit.swift source.jpg output.avif
```

逐张日志及 JSON 位于忽略目录 `build/validation-color-batch/1.2.2/`。
`build/delivery/UltraSlim-1.2.2-hdr-grid-debug.apk` 为日常 debug 包的升级 APK。
手机拒绝安装新 QA 测试包（INSTALL_FAILED_USER_RESTRICTED），没有绕过；
读取确认手机仍为 1.2.1，不能声称已安装 1.2.2。
