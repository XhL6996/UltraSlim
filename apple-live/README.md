# UltraSlim Live（Mac）

把 UltraSlim Android 导出的动态 HEIC 导入包转换为 Apple Live Photo。
目前公开提供源码，需按下文在 Mac 上构建；GitHub v1.0.0 Release 没有 Mac 安装包。构建出的应用适用于 Apple Silicon，需要 macOS 15 或更新版本；运行时不需要额外安装 ffmpeg、ExifTool 或第三方服务。

## 使用

1. Android：打开历史中的 HEIC 成品详情 → 右上角更多 → **导出 Apple Live Photo…** → **导出给 Mac**，保存 ZIP。
2. 将 ZIP 传到 Mac，用 **UltraSlim Live.app** 的「打开导入包」选择它。
3. 工具识别成功后显示 LIVE 预览。按住播放检查，再点击 **导入「照片」**，按系统提示授权。
4. Mac 与 iPhone 使用同一 Apple 账户并开启 iCloud 照片后，由系统同步。也可使用经过验证支持 Live Photo 的 Apple 传输方式。

ZIP 是给此导入器使用的传输包，不是直接存进 iPhone 就能播放的 Live Photo。v1 在单张 HEIC 成品详情导出；静态成品、未附带视频的 HEIC 会明确报错。没有视频的旧成品需要从原始动态照片重新转换。

## 数据处理

- Android 使用生产 MotionPhotoContainer 检测和校验：从 HEIC 的末尾 mpvd 中提取视频，按块复制图片和视频，保存拍摄时间点。不会读取或删除系统相册中的原图。
- Mac 给 HEIC 添加 EXIF Apple 配对标识，保留原有图片、grid、ICC 和增益图的压缩数据；没有通过 ImageIO 重新编码照片。为兼容 Apple ImageIO，保留 16-bit item ID 的 iloc/iinf/iref 表达。多份 EXIF、超出支持范围的 item ID/offset 等情况拒绝处理。
- 视频通过 AVFoundation 直接重新封装到 MOV，保持 H.264/HEVC 视频、音频和方向；增加相同的配对标识与 still-image-time 轨道。其他视频编码不静默降级。
- 缺失拍摄时间点时使用视频中点，并在界面提示。已知但越界的时间点直接拒绝。
- 必须先通过 PhotoKit 的 Live Photo 资源检查才能导入。Photos 写入通过一次 `.photo` + `.pairedVideo` 创建请求完成。失败不会将半个资源发布到相册。
- ZIP 的固定文件名为 `manifest.json` / `photo.heic` / `video.mp4` / `README.txt`。固定名称逐项读取，不把归档中的任意路径展开到磁盘；图片上限 512 MiB、视频上限 1 GiB。处理在本机完成。
- 静态图 HDR 与视频自身是否为 HDR 是独立的，不会把 SDR 视频擅自变成 HDR。

## 构建和验证

```sh
./apple-live/script/build_and_run.sh --verify
# 只构建 app：--build-only；调试：--debug；日志：--logs
swift test --package-path apple-live --scratch-path /tmp/ultraslim-live-swift
# 可选真实 HDR fixture 回归：设置 ULTRASLIM_LIVE_HEIC_FIXTURE 为 HEIC 的绝对路径。
# 命令行检查资源配对并保存两份资源，不写入用户照片图库：
/tmp/ultraslim-live-swift/out/Products/Debug/livephoto-check input.zip output-directory
```

SwiftPM 输出路径由工具链决定，实际路径用 `swift build --package-path apple-live --scratch-path /tmp/ultraslim-live-swift --show-bin-path` 查询。
Mac ZIP 在 `apple-live/dist/UltraSlim-Live-Mac.zip`；解压后将 app 放入「应用程序」。开发时 app 在外置构建目录的 `dist/` 内，以避免同步目录的 FileProvider 元数据影响签名；本地临时签名版本，不是公证发行版。

已经验证的资源配对、数值/码流一致性，不等于所有 iPhone/iCloud 环境都已经完成视觉验收。用户需要在自己的图库授权导入后验证同步。
