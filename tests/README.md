# 测试入口与素材

测试代码随模块存放：`native/host_test/`（CTest）、`app/src/test/`（JVM）、`app/src/androidTest/`（设备）、`app/src/screenshotTest/`（Compose Preview 截图）、`apple-live/Tests/`（SwiftPM）。这里只集中共享的固定输入，避免同一图片在多个模块重复。

`fixtures/libultrahdr/` 的两个 JPEG 来自上游固定提交，附 CC-BY-4.0 文本、来源与 SHA-256。CMake 和 Android test assets 都使用这个目录，克隆后即可获得所需素材。大图、损坏输入等场景尽量在测试中生成，不保存个人相机原片。

- 通常验证：`scripts/verify.sh`。
- 专用 ARM64 设备：`scripts/verify.sh --device`（独立 `.verifyqa` 包）。
- `PreviewDecodeTest` 是手动诊断，需推送 `preview_probe.avif` 到测试包的 external files 目录并传 `-e previewSample true`，默认跳过。
- `NativeCodecTest` 中相机样本、选定照片权限场景由 `avifCameraSample` / `heicCameraSample` / `selectedPhotos` 等 instrumentation 参数启用；`AppleLiveIntegrationTest` 使用 `appleLiveSample`。阅读对应测试中的准备条件后再运行，不能将未准备的外部样本测试视为自动回归。
- 后台服务测试包含测试设备温控/服务时限调整；不要把整套设备测试直接指向日常使用的手机。

完整命令和工具版本见 [构建文档](../docs/BUILDING.md)。

自适应 UI 回归：`AdaptiveUiTest` 覆盖窗口缩放、详情状态与 Activity 重建、模拟铰链、大字体和横屏；只在模拟器运行。`WorkspacePlanTest` 校验分区几何。运行方法与截图参考图流程见构建文档。
