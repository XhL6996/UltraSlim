> 后续已在 1.2.2 增加分块及批次回归，当前结果见 [批次报告](2026-10-08-color-batch-audit.md)。下文保留当时单张样本排查记录，不作为整批验收结论。

# AVIF 在 Google 相册偏红、偏绿：第二轮追查

> 后续纠正：用户报告 Apple 端也偏色，且同批其他照片仍有问题。下面的单样本通过
> 不能支持“Android 独有”或“已普遍修复”的结论。兼容模式默认开启已在源码撤回，
> 尚未再次覆盖手机安装。批量复验见 `2026-10-08-color-batch-audit.md`。

## 实际导出文件

直接读取连接真机中用户最新的导出文件（历史记录 158，01:21，质量 92，原尺寸），
而不是仅使用 host 生成的样本。文件为 3,361,973 字节，主图 4416 × 5888，
RGB gain map 2208 × 2944。

第一轮 ICC/NCLX 顺序修复已生效：源 JPG 与导出 AVIF 在平台 `ImageDecoder` 中
均为 Display P3，SDR 平均误差为 1.242/255。libheif 读取输出时，gain map 像素和
元数据与原图逐值一致。但是平台解码出的 gain map 通道误差为：

| 平台解码后的平均绝对误差 /255 | R | G | B |
|---|---:|---:|---:|
| 用户导出的原样 AVIF | 28.403 | 31.526 | 30.362 |
| 临时 YCbCr 4:4:4 兼容样本 | 0.943 | 0.537 | 1.735 |
| 原样保留 gain map 的 JPEG 输出 | 0 | 0 | 0 |

这些数值是在同一台 Android API 37 真机、相同解码采样倍率下比较得出。
兼容样本仍有解码缩放、RGB/YUV 量化和平台色度采样误差，不是逐像素无损。

## 解码路径证据

Android 的 [C2SoftGav1Dec](https://android.googlesource.com/platform/frameworks/av/+/refs/tags/android-17.0.0_r1/media/codec2/components/gav1/C2SoftGav1Dec.cpp)
将 8-bit 4:4:4 输出通过 `I444ToI420` 转成 4:2:0。
[CrabbyAvif 的 libyuv 适配](https://android.googlesource.com/platform/external/rust/crabbyavif/+/refs/tags/android-17.0.0_r1/src/reformat/libyuv.rs)
在 Android MediaCodec 配置下将 Identity matrix 替换为 BT.601。
我们的 gain map 将 G/B/R 原值存入三个平面，故这一转换会把 RGB 数据误当成 YUV，
改变 HDR 各颜色通道的增益。真机观测与该实现一致；没有声称已审计 Google 相册的私有实现。

进一步追溯到上游提交
[`95331007` — libyuv: Allow Identity for Android MediaCodec](https://github.com/webmproject/CrabbyAvif/commit/95331007dc4da0d86eb561f67f7bd61b2925eb16)：
它明确针对 MediaCodec 的固定 4:2:0 输出启用 Identity→BT.601 替换，并跳过
Identity 输入必须为 4:4:4/单通道的校验。Android 17 对应源码仍有这一分支。
`I444ToI420` 仅改变平面采样，不能把原来的 G/B/R 原值变成真正的 Y/Cb/Cr；
后续 BT.601 逆变换因此混合了错误的通道数据。这解释了为什么仅修正主图 P3
或其他容器标签无效，而把增益图实际转换为 YCbCr 的诊断样本明显改善。
这里定位的是已在真机平台解码中独立复现的机制，不等于已追踪 Google 相册内部调用栈。

排查过以下容器/标签改动，都不能解决：给 gain map 标注 sRGB primaries/transfer、
将 tmap 设为主图、将 gain map NCLX 标为 essential、改变主图的 YUV 矩阵。
试验性修改已从生产源码撤回。

## 验证与交付边界

- `AndroidColorProfileTest` 原先只验证主图，现增加 gain map 存在性、尺寸、HDR 参数和
  三通道误差检查；原样 AVIF 会明确失败，不再因 SDR 正常而通过。
- 平台测试允许每通道不超过 2/255 的缩略解码误差，它不是无损证明。
  `gainmap_preservation --compare` 才检查未缩放的像素逐值一致。
- JPEG 输出 2,694,987 字节，P3 和 gain map 通过平台检查；gain map 也通过 native
  逐像素和 metadata 检查。文件在 `build/delivery/OPPO-HDR-preserved.jpg`。
- 兼容 AVIF 临时样本 1,919,124 字节：只改变 gain map 的颜色表示（RGB→限定域
  BT.601 YCbCr 4:4:4），未缩放或重建 gain map，保持 HDR metadata；仍会有量化误差。
  文件在 `build/delivery/OPPO-Google-Photos-compatibility-demo.avif`。
- 兼容样本通过扩展后的平台检查，但必然不通过 native 像素逐值一致检查。
  它是供用户复核 Google 相册显示效果、评估取舍的诊断样本，尚未作为 App 默认策略发布。
- 主机两套回归通过。此轮没有发布新的 APK，也没有宣称 Google 相册界面验证完成。

项目 `AGENTS.md` 原先要求默认 preserve 路径的 gain map 像素逐值保留。
用户了解 RGB/YUV 量化取舍后要求修复，现加入明确区分的兼容选项；
不能将有量化损失的模式仍称为“原样保留”。
日志保存在忽略目录 `build/validation-oppo-hdr/`。

## 1.2.1 实现与复验

- App 新任务默认开启“AVIF 相册兼容”，首页和设置页均可关闭以恢复严格模式。
  增益图说明、任务快照和历史摘要区分兼容/逐值无损；旧快照缺字段仍按严格模式解释。
- 新字段 `avifGainmapCompatibility` 贯穿 DataStore、任务 JSON、Worker 和 JNI；
  原生/JNI 默认 false，App 显式传入。只作用于彩色 AVIF 增益图，其他格式、单通道不变。
- 兼容编码仍为原尺寸 8-bit 4:4:4，AOM 对转换后的 YCbCr 平面无损编码，
  ISO HDR 参数不变；只重试主图质量时缓存仍有效。没有启用 rebuild。
- 主机两套回归通过，新增所有 8 种 EXIF 方向、奇数尺寸、主图缩放、单/多通道、
  HEIC 不受开关影响、兼容模式重试一致性检查。严格模式仍逐值一致；兼容 RGB
  夹具逐像素误差不超过 3/255，且 HDR 参数一致（这是夹具结果，不是任意输入保证）。
- Debug/Release 构建、14 项 JVM 测试、Lint、APK 原生库 16 KB 对齐通过。
  独立 QA 包在模拟器跑 NativeCodecTest / PersistenceTest，包含相机样本 JNI 转码和
  新旧设置快照测试；原有需要用户选图的测试未启用。
- 模拟器设置页检查了兼容开关默认开启、关闭后切换为“原样保留”、重新开启及窄屏排版。
- OPPO 原图经 Android APK 中的 JNI 导出，4416×5888，增益图 2208×2944，
  6,118,536 → 1,919,124 字节；与同参数主机输出 SHA-256 一致。
  再用连接真机的平台解码检查：P3 正常，SDR MAE 1.242/255，
  增益图 RGB MAE 为 0.943 / 0.537 / 1.735（/255），HDR 参数一致。
  Google 相册内部显示尚未进行最终视觉确认，不把平台检查称为相册 UI 验证。
- 交付：`build/delivery/UltraSlim-1.2.1-avif-hdr-fix-debug.apk` 与
  `build/delivery/OPPO-1.2.1-compatible.avif`。旧 AVIF 文件不会因升级自动修复，需从原图重新导出。
