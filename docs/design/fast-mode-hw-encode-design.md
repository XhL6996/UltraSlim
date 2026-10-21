# 极速模式（硬件编码）设计稿 — 实测后暂缓

> 状态：**暂缓**（2026-10-07 实测后结论）。触发条件已实测：测试机（SM8975，平台 "art"）无硬件 AV1 编码器；
> 有 `c2.qti.hevc.encoder` / `c2.qti.hevc.encoder.cq` / `c2.qti.heic.encoder`（Qualcomm 硬件 HEIC 图像编码器）。

## 实测结论（NDK MediaCodec 探针，2026-10-07）

用独立可执行探针（`AMediaCodec` + NV12 ByteBuffer 输入，5888×4416 真实照片）在该机型上实测：

| 编码器 | 结果 |
|---|---|
| `c2.qti.av1.*` | **不存在**（vendor 配置只有 av1 解码器）→ AVIF 无法硬件加速 |
| `c2.qti.heic.encoder`（image/vnd.android.heic，CQ 0-100，≤16384²） | configure 成功、输入喂入成功，**输出永不产生**（60s 看门狗超时，0 字节）。该编码器为相机拍照链路设计，实际只支持 Surface/ImageWriter 输入，不支持裸 ByteBuffer |
| `c2.qti.hevc.encoder`（video/hevc，VBR/CBR，≤8192²） | configure 直接失败（-10000，vendor Cota 特性依赖，NDK 配置路径不满足） |
| `c2.qti.hevc.encoder.cq` | 尺寸上限仅 **512×512**（缩略图档），不可用于全尺寸 |

## 结论与建议

- 在这台设备上，**没有一条硬件编码路径能用合理工程量接入**：HEIC 硬编码需要 Java 侧 ImageWriter/Surface 原型验证（相机式用法，无法经 NDK ByteBuffer 驱动），即使通了还要绕开 libheif 自写 HEIF/tmap 组装，且同画质体积比 x265 大 10-30%。
- **暂缓投入**。线程 4→8（已上线）是当前性价比最高的提速手段。
- 若后续仍觉慢，优先评估两个低风险方向：① 小图双路并行转换（现串行）；② x265 preset 映射调快一档（代价体积 +5-10%）。硬件路径重新评估的触发条件：换用带硬件 AV1 编码器的设备，或 ImageWriter 原型在该设备验证可行。

## 结论先行（原始设计，保留备查）

## 结论先行

- **AVIF 无法硬件加速**（该芯片无 AV1 编码器，实测 2026-10-07），AVIF 保持 SVT-AV1（CPU）。
- **HEIC 可硬件加速**：MediaCodec `c2.qti.heic.encoder`（直接产 HEIF）或 `hevc.encoder.cq`（产 HEVC 码流）。
- 硬件编码的代价：同画质体积比 x265 大约 10-30%，与"保证更小"存在张力 → 只能作为**可选的极速模式**，默认关闭。

## 设计

1. **入口**：设置页新增"极速模式（HEIC · 省电快速，体积稍大）"开关，默认关。开启后仅影响 HEIC 输出；AVIF/JPEG 不受影响。
2. **范围**：只加速**主图**编码。增益图仍然 x265 无损（硬件编码器做不到无损 444，增益图必须逐值保留）。
3. **实现路径**（二选一，倾向 A）：
   - A. `c2.qti.hevc.encoder.cq`：喂 YUV420（我们已有 709 系数转换），取 HEVC 码流 + SPS/PPS 组 hvcC，走现有 libheif 封装（替代 x265 一层）。可控性最好。
   - B. `c2.qti.heic.encoder`：MediaCodec 直接出 HEIF——但相机链路的 HEIF 不带我们的 tmap/增益图封装，仍要拆包重灌，反而绕远。
4. **降级**：运行时查询 `MediaCodecList`，无硬件编码器（或初始化失败）→ 自动回退 x265，不报错。
5. **质量校验**：沿用现有输出重探测 + 解码 + 增益图逐像素校验；体积若 ≥ 原图，照走"仅保留更小"降档逻辑（降档对硬件编码意义有限，可直接判跳过）。
6. **兼容性红线**：16KB 对齐不受影响（MediaCodec 是系统组件）；输出必须通过现有 rendition/逐像素回归。

## 工作量与风险

- 工作量：2-4 天（MediaCodec 异步桥接、hvcC 提取、设备矩阵测试）。
- 风险：不同 SoC 硬件编码器行为差异（诺基亚式兼容坑）；CQ 质量档位与 x265 的主观画质对齐需要调参；收益仅限 HEIC + 支持 CQ 的设备。
- 建议：等线程 4→8 的实测数据出来后再决定是否值得投入——如果 8 线程后 HEIC 单张已进入可接受区间（<3s），极速模式优先级可以放低。
