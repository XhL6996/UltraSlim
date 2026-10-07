# OPPO JPG → AVIF：Android P3 色彩兼容性修复

## 复现与原因

用户样本为 4416 × 5888 的 Display P3 JPEG，包含 2208 × 2944 三通道 gain map。
质量 90、速度 1、原尺寸、preserve 转为 AVIF 后为 3,345,943 字节。

libheif 解码与 macOS ImageIO 均正确保留 P3，gain map 像素和 HDR 参数逐值一致。
但连接真机（Xiaomi，Android API 37）的 `ImageDecoder` 将原 JPG 识别为
Display P3，却将 AVIF 识别为 sRGB，且没有将 P3 像素转换成 sRGB。这会导致偏色。
用户报告的显示端是 Google 相册；本次直接验证的是 Android 系统解码器，未验证
Google 相册完整界面或云端处理链路。

AVIF 主图同时具有 ICC 和 NCLX `colr` 属性，旧输出的 `ipma` 关联顺序为
ICC → NCLX。在不修改编码数据、ICC 内容或 NCLX 数值的条件下，仅把关联顺序改为
NCLX → ICC，真机就恢复识别 Display P3。删除 ICC、改为 rICC、换用 BT.709
YUV 矩阵均不能解决；换矩阵的试验改动已撤回。

## 修改

`native/deps/patches/patch_libheif.sh` 在 AV1 图像项中将 ICC 放到 NCLX 之后，
同时保留两种色彩信息。通过已有的 `av1C` 属性识别 AV1 图像，HEIC 写入顺序不变。
补丁可重复执行，host 与 Android 依赖均已重建。

修改前后这张样本的 AVIF 文件长度相同，`mdat` 编码数据 SHA-256 也相同：

`c674d331abe49599acd8e4ffc2e23b8815e28f04e8a1b5c87633f4b9d9df4eb9`

因此该修复不改变图像编码、压缩质量或 gain map。

## 验证

- 同一张原图、同一台真机：旧 AVIF 因 P3 → sRGB 色域错认而回归测试失败；
  新 AVIF 为 Display P3，测试通过。
- 真机 SDR 统一到 sRGB 后平均绝对误差为 1.261/255（包含有损编码和缩放差异）。
- 新 AVIF 的 gain map 与原 JPG 逐像素一致，HDR metadata 一致。
- host 两套回归通过；增加 P3 AVIF 重编码后 ICC 字节不变的检查。
- Debug / Release 构建、单元测试、lint、APK 16 KB 对齐检查通过。

平台回归与 libheif 解码测试分开：只比较我们自己的解码结果无法覆盖此类问题。

```bash
# 需要 JDK 17、Android SDK platform/build-tools 36，以及支持 AVIF 的连接设备。
# 使用质量 90+、未缩放且已统一方向的源/输出对。
# 只写入并清理 /data/local/tmp 临时文件，不安装 App、不写相册。
scripts/check_android_color.sh <adb-serial> <source.jpg> <output.avif>
```

测试通过 `app_process` 调用平台 `ImageDecoder`，检查色彩空间和 SDR 像素误差。
HDR 增益图像素/参数由 `gainmap_preservation --compare` 独立检查。

验证日志位于忽略目录 `build/validation-oppo-color/`。
交付 APK 为 `build/delivery/UltraSlim-avif-color-fix-debug.apk`，
该样本新输出为 `build/delivery/OPPO-P3-fixed.avif`。旧导出文件不会因更新 App 自动改写。
