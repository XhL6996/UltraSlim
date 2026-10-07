# UltraSlim

Android 离线图片转码工具，支持 JPEG、HEIC、AVIF，以及 Ultra HDR / ISO 21496-1 Gain Map。原图始终保留。

## 构建

需要 JDK 17、Android SDK 36、NDK 27.2.12479018、CMake、Ninja、Python 3。当前 APK 仅含 arm64-v8a。

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export ANDROID_HOME="$HOME/Library/Android/sdk"
# 同步目录内推荐将 Gradle/NDK 中间产物放到本地缓存。
export ULTRASLIM_BUILD_ROOT="$HOME/.cache/ultraslim-build"
native/deps/build_deps.sh host
native/deps/build_deps.sh android arm64-v8a "$ANDROID_HOME/ndk/27.2.12479018" 26
scripts/verify.sh
# 连接 ARM64 设备或模拟器后再运行设备回归：
scripts/verify.sh --device
```

APK 在 `$ULTRASLIM_BUILD_ROOT/app/outputs/apk/`。Debug 包名为 `app.ultraslim.debug`，Release 包未签名；签名由发布环境提供。未设置构建目录变量时沿用 `app/build/`。

Gitea 工作流 `.gitea/workflows/verify.yml` 使用 `macos-arm64` 自托管 runner，需要按以上步骤预装工具。工作流运行主机回归、Debug/Release 构建、JUnit 和 lint；设备测试通过 `--device` 单独执行。当前没有代替用户注册或触发远程 runner。

构建验证还会检查最终 APK 的 16 KB LOAD/RELRO/ZIP 对齐。`graphics-path` 的 JNI 部分使用对应 1.0.1 上游源码重编，来源和许可证见 `third_party/androidx-graphics-path/README.md`。升级该 Kotlin 依赖时必须同步更新 JNI 源码并通过对齐检查。

## Gain Map 与编码质量

1.2 起，默认且固定采用原始 Gain Map：保持尺寸、通道、增益参数和像素，不因主图质量或预设重建、缩小。JPEG 方向归一化时主图与增益图同步变换；JPEG → JPEG 直接复用原始增益图压缩扫描数据并保留方向。

HEIC 增益图使用 x265 无损编码，AVIF 增益图单独使用 AOM 无损编码。彩色增益图采用 4:4:4 identity GBR，避免 YUV 4:2:0 引入通道或空间损失；AVIF 主图仍使用 SVT-AV1。这里的原样保留指解码像素和解释参数，不是不同容器的编码字节相同。HEIC/AVIF HDR → JPEG 无法无损承载增益图时明确拒绝，需选择 HEIC/AVIF；不会悄悄重建或降为 SDR。当前增益图编码链路为 8 位；输入超过 8 位时明确停止转换，避免截断精度。

界面不再提供 Gain Map 质量、缩放和通道开关。默认主图编码质量 82、原始分辨率、仅保留更小结果；既有用户明确设置仍保留。编码质量 100 是编码器参数，不等于无损，不保证比已经压缩过的原图更小。三个主图预设不再暗中缩小分辨率。

输出文件保留可读原名，附 `_Slim_` 和 8 位稳定标识，重复任务不覆盖；系统返回纯数字名时改用 `Photo_日期时间`。相册、记录和分享使用一致文件名。旧历史的发布恢复保留原命名策略。

## CPU 加速与重试

ARM64 构建启用 x265 的 PIC-safe NEON intrinsic 路径，SVT-AV1 编入 NEON、DotProd、I8MM、SVE/SVE2 优化，由库按 CPU 能力和编码路径选择；不等于每张 8-bit 图片都会使用 SVE2。未接入 MediaCodec 硬件编码。

“仅保留更小”需要重试时，原生层复用已准备的主图和无损增益图码流，只重新编码主图。速度档位同时影响主图和无损增益图的编码搜索量，不缩小或降低增益图精度。缩放和像素转换使用最多四线程，图片之间保持顺序处理。

## 使用与数据

- 历史页按日期分组，右滑日期标题或点删除图标可删除当天同筛选条件下的记录（包括分页外记录），确认框显示实际数量。已保存到相册的图片保留。选择模式提供分享、删除以及仅针对未保存项的保存操作。
- 首页参数只用于当前批次；设置页修改未来的默认参数。开始时锁定参数，支持先试转一张、仅保留更小结果。
- 队列、参数快照和处理结果保存在 Room。系统中断后重试使用逐项检查点；停止批次会等待当前原生编码结束。
- 待保存/保存失败成品保存在私有 `files/outputs`，不会按缓存期限清除；可重试保存、直接分享或导出到文件。保存相册采用任务 ID 去重。
- 临时分享授权会复制到私有 `files/inputs`；可持久授权的图片直接通过 fd 读取。无引用的私有文件在超过 24 小时后清理。
- 数据库 v1 → v2 自动保留历史，并迁移仍存在的旧缓存成品。已被旧版本删掉的文件无法恢复。
- Android 8/9 使用“导出到文件”或分享；自动写入相册适用于 Android 10+。
- 对比视图统一显示 SDR 主图，放大时最长边 4096 像素。HDR 显示效果由外部查看器、系统和屏幕支持决定。
- 关闭位置保留会清除标准 EXIF GPS 目录及其数据；不是对任意私有 MakerNote 的取证级匿名化。

## 验证范围

见 [性能与历史页修复记录](docs/2026-10-08-performance-history.md) 和 [原有验收记录](docs/2026-10-06-validation.md)。真机 HDR 显示、长时间热稳态、超高像素压力测试仍需目标硬件验证。
