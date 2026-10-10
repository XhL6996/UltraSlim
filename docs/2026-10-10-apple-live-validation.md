# Apple Live Photo 导出与三项上游修复（2026-10-10）

## 实现

- Android 1.0.2 / code 8：HEIC 成品详情 → 更多 → 导出 Apple Live Photo，通过 SAF 保存 ZIP。v1 单张导出，成品必须包含完整动态视频；失败保留原图和成品。包内保存 HEIC 静态数据、原视频和呈现时间戳；不是可直接保存进 iPhone 的 Live Photo。
- Mac 工具 `apple-live/`：纯 SwiftPM / 系统框架。图片只改 EXIF 配对元数据和容器索引，主图、grid、ICC、增益图编码数据不重新编码；H.264/HEVC 视频和音频 passthrough 封装成 MOV，添加配对 UUID 和 still-image-time。PhotoKit 识别成功才开放导入按钮。用户点击后一次创建 `.photo` + `.pairedVideo` 资产。
- 保持 Android Motion Photo 导出，未把原有 HDR 路径切换为 rebuild。
- 选择性移植 `924967a759`（RGB 绿通道）、`2472ff7ad1`（ARM NEON 系数/分派）、`b4880a1c21`（sRGB NCLX）。没有整体升级 main，没有更换 ISO 元数据布局。三项是颜色正确性修复，不承诺提速，也不等于覆盖全部历史偏色原因。

## 验证

日志/实验文件：`build/validation-live/`。

- 原生 CTest：3/3 通过。新增 `upstream_color` 验证三项补丁，原格式回环与增益图保留测试同时通过。
- Android：Debug 构建、单元测试、lint 通过（lint 0 error，46 warning）。新增 JVM 包拆分测试校验图片/视频逐字节一致、标准和扩展 mpvd、截断拒绝、时间戳。生产 Kotlin writer 生成真实 HEIC + 合成 H.264/AAC 片段的 ZIP，交给 Mac 导入器接受检查。
- Mac：4 个 Swift 测试通过，覆盖无 EXIF 文件、原 HDR 编码数据/增益图保持、损坏 box/iloc、不为非零起点重复添加时间偏移。恢复 EXIF 尺寸，避免临时元数据序列化图的 1x1 尺寸进入正式照片。
- Kotlin 导出 ZIP → Mac 配对 → `PHLivePhoto.request` 成功。这里用已确认兼容 HEIC 静态样本和生成的 2 秒测试视频验证封装流程，不是实拍动态内容一致性的验收。
- Apple Core Image 对同一 HEIC 配对前后进行浮点线性 SDR/HDR 与原增益图比较，三项每通道 MAE 均为 0。
- 视频/音频压缩数据 SHA256 配对前后分别一致：
  - 视频：`ecf961128f98cee2a7cc03d25c857a88ca8759fc44387111ef24f59c4ccd0832`
  - 音频：`5e92a624ab72dd3f5b37b04230bfca11a1719d0f8570363e7b902c7938eb957a`
- Mac 应用实际启动，通过文件选择器打开包；UI 显示 LIVE 预览和可用的「导入照片」按钮。未点击向用户图库加入合成测试资产。
- Android APK 的三个原生库通过 16 KB LOAD/RELRO/APK 对齐检查；apksigner v2 签名验证通过。
- Mac app 临时签名；在同步目录外打包，ZIP 解压后的 `codesign --verify --deep --strict` 通过。

## 未完成的设备验证

手机 `8c209733` 拒绝安装独立 `.liveqa` 测试包：`INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`。没有绕过限制或用其他安装方式重试。实际 Android UI → Mac 导入 → iCloud → iPhone 相册尚未完成端到端验收；不把主机或 PhotoKit 资源检查等同于该验收。日常 App 未被更新。

`AppleLiveIntegrationTest` 已提供真机验证入口，需在用户允许安装测试包后，放入 `files/apple-live-source.jpg` 与 `files/apple-live-clip.mp4`，运行参数 `appleLiveSample=true`；输出在隔离 QA 包的 `files/outputs/live-qa/AppleLive.zip`。

## 交付

- `build/deliverables/UltraSlim-1.0.2-debug.apk`：arm64-v8a，调试签名，包名 `app.ultraslim.debug`。
- `build/deliverables/UltraSlim-Live-Mac-1.0.0.zip`：Apple Silicon，macOS 15+，本地临时签名版本。
- 用法见 `apple-live/README.md`。未提交或推送 Git。
