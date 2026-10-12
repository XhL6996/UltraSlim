# AVIF 最高质量档位保真修复

基于 Gitea `main` 提交 `34b4ae2`。用户报告 AVIF 已选质量 100，但同图 HEIC 仍明显更接近原 JPEG。本轮修复质量映射，不修改 gain map 保留策略、色彩矩阵、采样格式或速度档位。

## 根因与修改

原代码把 HEVC 和 AV1 都送入 `remapQualityForLibheif`。UI 质量 100 被压到 libheif 84；当前 libheif SVT 插件再按 `((100 - quality) * 63 + 50) / 100` 换算为 quantizer 10。92 则变为 68，再变为 quantizer 20。这个插件保持 adaptive quantization 默认值，不能把其参数简单称为恒定 CQP，也不能认为数值相同就与 x265 CRF 画质等价。

用户 AVIF 主图 AV1 frame header 的 `base_q_idx=40`，与最高档仍存在量化吻合；该文件并不包含完整的 App 任务设置，无法单凭文件确认速度档位。其主图数据 1,288,493 字节，对比 HEIC 的 4,881,565 字节，差距主要在主图而不是增益图。

AVIF 现在独立使用完整 1–100 质量范围，同时覆盖原生 SDR/preserve、AOM 奇数尺寸例外及 vendored rebuild 的 base/map 编码。HEVC 原有曲线保持不变；preserve 的 gain map 仍由独立无损编码器处理。质量 100 只表示此编码路径最高保真，RGB 到 8-bit YUV 的取整、常规主图的 4:2:0 采样仍有损失。既有 `retrySmaller` 若触发仍可能降低实际编码质量。

参数依据：[本项目固定版本的 libheif SVT 插件](https://github.com/strukturag/libheif/blob/4a3f74bc593ebfc29becc1ed5dd0a61cc66d40e1/libheif/plugins/encoder_svt.cc)。

## 同图受控验证

输入为用户提供的 4096×3072 JPEG（7,453,552 字节），SHA-256 `1e206caaf9e5d6a2588be6265f13b44684f7ab57f2527bb6b85c075eaf99443b`。私有照片不提交仓库。WSL Linux x86_64，仓库固定版本依赖；speedLevel=1、4 线程、原尺寸、preserve=true、兼容模式=true、retrySmaller=false。前后只改变质量映射。

PSNR 为原始 Display-P3 编码值 RGB 指标；SSIM 为 BT.601 加权亮度，11×11 高斯窗、sigma 1.5。所有图片使用同一 libultrahdr 2.0.2 解码器提取 SDR 底图。它们衡量相对原 JPEG 的保真，不是绝对画质分数。

| 编码 | 质量 | 文件字节数 | RGB PSNR | 亮度 SSIM |
| --- | ---: | ---: | ---: | ---: |
| 修改前 AVIF | 92 | 1,523,457 | 39.48 dB | 0.9740 |
| 修改后 AVIF | 92 | 2,756,953 | 44.28 dB | 0.9911 |
| 修改前 AVIF | 100 | 2,075,863 | 42.43 dB | 0.9862 |
| 修改后 AVIF | 96 | 3,385,500 | 45.13 dB | 0.9933 |
| 修改后 AVIF | 100 | 7,054,574 | 48.18 dB | 0.9989 |
| 原代码 HEIC | 100 | 5,804,472 | 47.27 dB | 0.9982 |

原用户 AVIF 为 2,016,477 字节 / 42.55 dB / 0.9866，与本机重现结果接近，但并非逐字节复现原设备编码。100 档损失明显降低，代价是体积接近 JPEG；不能承诺高保真仍能省掉 73% 体积。96 档在该样本上缩小约 54.6%，100 档缩小约 5.4%。

修改后 q100 SHA-256：`77b883912d692b828c169e2de771f37a531de5473000441987141e0cc2b45889`。

修改后 q96 SHA-256：`786bccfd087181c06cd8252bd79e0500e578b5b9bcb34ebe322cc094e2e9a4de`。

## 回归与真机验证

- 新增 `avif_quality`：生成带细纹理、梯度的灰度 JPEG，检查 SDR/HDR、偶数/奇数尺寸、SVT/AOM、三档速度下 q100 的实际解码 PSNR >46 dB，且高于 q92 至少 1 dB。参考为输入 JPEG 实际解码像素，不以被测实现生成预期值。
- 用旧 `transcode.cpp` 重编同一测试，q100/speed0 为 41.90 dB，触发质量门槛失败；修复后通过。完整 CTest 4/4 通过，含既有色彩、增益图保留、方向及封装回归。
- 主机构建补齐 `transcode.h` 的 `<cstdint>` 和 `gm_dump.cpp` 的 `<string>`，不再依赖 Apple 标准库的间接 include。
- 连接的 Android API 37 真机以仓库 `AndroidColorProfileTest` 的 `app_process` 方式读取修改后 q100/q96，sampleEdge=4096，完整原尺寸解码。两者均为 Display-P3、均能读取 HDR；2048×1536 增益图三个通道的绝对像素误差均为 0，元数据检查通过。
- 真机线性 HDR 各通道平均值变化：q100 为 +0.197%、-0.016%、+0.090%；q96 为 +0.292%、-0.015%、+0.234%，均通过已有 ±3% 约束。q100 的 SDR sRGB 平均绝对误差为 0.542/255。
- 实际 4096×3072 用户 JPEG 的显式 rebuild AVIF/quality100 冒烟编码成功；默认 preserve 已由上述质量与增益图回归覆盖。
- 仓库布局、链接、素材/许可证 hash 检查通过。Windows 首次 checkout 自动转换了固定 hash 的 fixture LICENSE 换行；从 Git blob 恢复原字节，并补充 `.gitattributes` 保持该文件原字节，不改许可证内容。

此次已验证原生主机编码与 Android 系统读取生成文件；未进行相册 UI 或实际 HDR 屏幕观感验收。

## Android APK 打包补充（2026-10-13）

- 已完成 ARM64 Debug APK 构建，版本 1.1.1（13），包名 `app.ultraslim.debug`，包含本次质量修复。Windows 中文路径下 CMake 配置失败后，使用英文路径源码副本构建；431 个源码文件通过 SHA-256 核对。
- JVM 单元测试 24 项通过、1 项跳过；APK v2 签名验证通过，三个原生库的 ELF LOAD、RELRO 和 APK 16 KB 对齐检查通过。
- APK 大小 43,246,452 字节，SHA-256 为 `e7bffe0df969d3910d9db966a28d271b46177cca65757629d73a69359fe80450`。APK 为本地交付产物，不提交 Git。
- 尝试用成品 APK 的 JNI 库进行额外 `app_process` 真机编码验证时进程被终止，未取得结果；未安装 APK，不能将前述主机编码及 Android 解码验证视为成品 App 安装运行验收。

本机诊断产物在忽略目录 `build/quality-audit/`。WSL 的构建副本在 `/tmp/ultraslim-quality-work`：Clang/lld 构建，NASM 2.16.03（规避 AOM 3.12.1 的 NASM 3 帮助文本检测问题），Meson 的 dav1d multiarch 静态库通过 `DAV1D_LIB` 显式传入。未改生产依赖版本。
