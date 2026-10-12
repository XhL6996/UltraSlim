# 2026-10-08：转码性能与历史页修复

## 实现

- SVT-AV1 取消 `COMPILE_C_ONLY`，启用 ARM 专用 NEON、DotProd、I8MM、SVE/SVE2 对象及运行时能力分派。是否使用某个指令集取决于 CPU 和具体算法；8-bit 编码不保证走 SVE2。
- x265 单独构建并注册 PIC-safe NEON intrinsics，保留 `ENABLE_ASSEMBLY=OFF`。16×16 DCT/IDCT 使用已有 C++ intrinsic 实现；两个依赖汇编的 planar 预测使用 C 回退。修复补丁重复运行造成的 `x265::x265::` 命名空间问题。
- 无损增益图响应速度档位：HEIC slow/medium/veryfast，AVIF AOM speed 4/6/8，像素、尺寸和 ISO 元数据仍原样保留。
- JPEG → JPEG 的增益图直接复用压缩数据，跳过不需要的像素解码。HEIF/AVIF 先读取位深和通道信息，常规输入只解码一次增益图；头信息不完整时仍进行原生精度检查，只有明确的 full-range mono/identity GBR 才直接复用平面。
- “仅保留更小”的一次降质重试移入原生编码阶段，复用解码、方向处理及缩放结果。HEIF 使用显式作用域内的同图增益图码流缓存，JXL 复用 jhgm，退出或异常时清理。缓存只允许同一图片、相同输出编码器和无损增益图参数；只改变主图质量。
- 增益图 GBR 拆分采用 NEON；百万像素以上的缩放、YUV 预处理使用最多四个工作线程，阶段结束后 join。保留原整数算法及取整规则，批次仍按单张顺序执行，未增加多图同时驻留的内存峰值。
- 历史按本地自然日分组。右滑日期标题或点删除图标后确认删除，范围包括分页之外同日期、同筛选条件的记录。确认框展示实际条数；原图与已保存相册图片保留。
- 选择模式统一标题计数、卡片高亮和勾选。底部操作栏撑满宽度，移除重复导航栏内边距，保存只计未保存项；已保存项仅提供分享/删除，空选择禁用操作。切换筛选清除隐藏选中项，全选可取消，返回键退出选择。

## 主机性能对比

基线为修改前 `14cbb0e` 的已构建主机程序，测试机 Apple M5 Max。使用同一张可复现合成 HDR JPEG：主图 4096×3072，多通道增益图 1025×769。quality=90、speedLevel=1、原尺寸、4 编码线程。每种格式预热一次，前后交替运行三次，取原生 `durationMs` 中位数（不含 CLI 启动和最外层文件读取/预检）。

| 格式 | 修改前 | 修改后 | 耗时变化 | 输出字节数 |
| --- | ---: | ---: | ---: | ---: |
| HEIC | 2693 ms | 2131 ms | -20.9% | 9,118,632 |
| AVIF | 3939 ms | 1653 ms | -58.0% | 6,545,369 |
| JXL | 740 ms | 746 ms | +0.8%，基本持平 | 6,677,133 |

此样本三种格式前后的输出文件均逐字节相同；这是 CPU 优化的对照，不是以降低画质换速度。JXL 的普通单次编码没有明显提速，改进主要针对重试复用。合成样本不代表手机真实照片或长时间热稳态表现，也不保证转换后一定更小。

生成样本与命令：

```bash
build-host-test/gainmap_preservation --benchmark-fixture /tmp/large-hdr.jpg
# 参数依次为 quality、legacy scale/quality、speed、resize、preserve、threads、retrySmaller
build-host-test/transcode_test /tmp/large-hdr.jpg /tmp/result.avif 90 4 60 1 100 1 4 0
```

## 验证

- 主机 `transcode_regression`、`gainmap_preservation` 通过：mono/RGB、8 个方向、跨容器往返、>8-bit 拒绝、EXIF/ICC、并发隔离。
- 新增大图奇数边长缩放/YUV 单线程与四线程解码像素一致测试；增益图速度优先和缓存重试测试。HEIC/AVIF/JXL 的重试输出与独立 quality=85 编码结果逐字节一致，并保持增益图逐值一致。
- JUnit 14 项通过，包括自然日边界、跨年以及夏令时 23 小时日期范围。
- ARM64 模拟器仪器测试运行 14 项并通过（其中已有 `selectedPhotoPreservation` 仅在显式传入 selectedPhotos=true 时执行，其余正常执行）。覆盖历史选择/筛选/实际右滑、删除取消与确认、108 条记录跨分页日期范围、JNI 转码与 4096×3072 AVIF 预览、存储与数据库迁移。
- 浅色/深色 320dp 窄屏截图已检查；选择栏没有右侧缺口，动作不会伸出屏幕。
- Debug/Release 构建、lint（无错误，保留现有建议类警告）、APK 的 16 KB LOAD/RELRO/ZIP 对齐检查通过。
- 连接手机 M154FF 安装独立测试包时被设备拒绝：`INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`。未宣称完成真机安装、SVE2 执行命中或热稳态验证。

## 独立 UI 测试包

```bash
ULTRASLIM_BUILD_ROOT=/tmp/ultraslim-qa JAVA_HOME=/opt/homebrew/opt/openjdk@17 \
  ./gradlew -PultraslimDebugSuffix=.perfqa -Pkotlin.incremental=false \
  :app:assembleDebug :app:assembleDebugAndroidTest
```

测试包为 `app.ultraslim.perfqa`，不与日常 Debug/Release 包共享数据。`PreviewDecodeTest` 需要预置专用测试图，可用下列命令生成，推到该测试包的 externalFiles 目录 `preview_probe.avif` 后再执行全部仪器测试：

```bash
build-host-test/gainmap_preservation --preview-fixture /tmp/preview-hdr.jpg
build-host-test/transcode_test /tmp/preview-hdr.jpg /tmp/preview_probe.avif 90 4 60 2 100 1 4
```
