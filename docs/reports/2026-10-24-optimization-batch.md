# 2026-10-24 优化记录

本轮在已有 10 月 23 日改动及本次审查修复之上继续优化。以下分别记录优化阶段与 1.0.2 发行阶段的验证。

## 改动

1. 导入：每 32 张批量写入探测开始/结束状态，相册游标复用名称与大小，其他 provider 入口保持查询兜底。结果只更新仍在 PROBING 的条目。
2. 预览：同 URI 请求等待完成后，完整重查缓存，允许小图复用先完成的大图。
3. 原图重关联：provider 检查统一在 IO 执行，结果绑定输入 URI，并取消旧查询的结果发布。
4. 待保存成品：共享批次投影查询，自动保存配置解析在仓库作用域执行；成品按 `(createdAt,id)` 游标分页，每页 32 条。保留同批次新增通知与取消检查，避免 OFFSET 在已保存条目移出结果后漏项。无需 Room 迁移。
5. 原生缓冲：HEIC/AVIF 主图直接从解码平面缩放至最终尺寸；JXL 及时释放编码器已复制的增益图像素及无需重试的主图 RGB，jhgm 重试缓存由复制改为移动。增益图尺寸和色彩规则保持不变。

## 已完成验证

- WSL x86-64 隔离构建，CTest **6/6 通过**。
- 新缩放路径与旧的全图复制后缩放比较：HEIC/AVIF、1/37/50/99/100% 五档、八种方向和奇数尺寸，像素一致；检查 EXIF/ICC/NCLX。
- JXL 提前释放后的解码、重试增益图像素与 ISO 元数据回归通过。
- 与本轮优化前二进制比较 **12 组完整输出，逐字节相同**，覆盖 SDR/HDR、跨格式缩放、JXL 重试开关。
- JVM 测试 **47 通过、1 跳过、0 失败**，包含 4096 条队列中 32 条批量状态更新、迟到结果不覆盖已领取/删除条目的回归。
- Android instrumentation 源码编译通过；新增相册导入、并发预览、重关联迟到结果和待保存分页/共享通知场景。
- Windows NDK ARM64 原生构建及 Debug APK、测试 APK 打包成功；3 个原生库的 ELF/RELRO/APK 16KB 对齐检查通过。英文路径构建副本中的本轮关键源码已与工作区核对 SHA256。
- 仓库布局、文档链接、fixture 与许可证校验通过。

本地日志在 `build/optimization-20261024/`，该目录不纳入版本控制。
优化阶段 Debug APK 位于外置构建目录的 `app/outputs/apk/debug/app-debug.apk`，当时未安装或发布。

## 1.0.2 发行验证补充

- versionCode 25，versionName 1.0.2。Windows 英文路径构建副本与工作区源码按 SHA-256 核对；Release/Debug/测试 APK 构建通过，JVM 47 通过、1 个外部样本测试跳过；Release/Debug Lint 均 0 错误（42/44 警告及各 1 提示）。本次 WSL CTest 6/6 通过。
- Mac 上现有 Android 16 / API 36 ARM64 AVD 以只读模式启动，使用独立 `app.ultraslim.verifyqa` 包。累计 **42 个不同场景通过**：首轮 30 个未受影响场景通过，修正测试设施后另跑 12 项，覆盖 AdaptiveUiTest、滑动、撤销、历史、自动替换、导入、预览、原图重关联、分页保存、时间与损坏快照恢复。
- 首轮没有全部通过：自动替换的 2 项测试使用了无 EXIF 的人工图片，却假设系统会接受 DATE_TAKEN 写入；时间测试 provider 在独立测试 APK 进程缺少 Kotlin runtime，导致挂起。provider 改为与既有 FolderTestProvider 相同的纯 Java 方式；自动替换样本写入真实 EXIF；时间回归拆为含 EXIF 与无 EXIF 两种情况。没有为迁就测试改写照片内容或修改正式 App 行为。
- 无 EXIF 的拍摄时间索引不能承诺保留。[Android 文档](https://developer.android.com/reference/android/provider/MediaStore.MediaColumns#DATE_TAKEN)将 DATE_TAKEN 定义为从媒体元数据提取的只读列。本次验证了已有 EXIF（时区、子秒）、临时副本的 provider 时间旁车、文件修改时间，以及保存前后照片字节不变；无 EXIF 时允许系统索引为空，并未把它标为已修复。
- 正式 APK 在 Mac 使用原正式密钥签名，证书 SHA-256 为 `e54990c754cbede4e7ab638d800a33b893f1d2f5f29bbacd11528905419a4d39`。签名前后全部原 ZIP 条目逐字节一致；签名、包名、递增版本和全部 3 个原生库的 16 KB LOAD/RELRO/ZIP 对齐校验通过。
- APK SHA-256：`ee27345a260ec343ff633c576643bb7e4bb1f26534c110d547f1a164ca8f8825`。发行日志保存在忽略目录 `build/release-1.0.2/`。
- 同一只读模拟器中安装正式 1.0.1，再用正式 1.0.2 覆盖安装成功；启动 MainActivity 返回 `Status: ok`，系统包信息为 `app.ultraslim` / 25 / 1.0.2。此项验证安装签名与启动，不等同于真实用户历史数据升级验收。
- GitHub [v1.0.2](https://github.com/linyusenzz/UltraSlim/releases/tag/v1.0.2) 已设为最新正式版，tag 对应 `b18f1dc6e83ca39c8b32145baf32f8b9a88e052c`。APK、源码 ZIP、许可与校验清单共 4 个附件的远端大小和 SHA-256 均与本地一致；从公开下载地址重新获取 APK 和 SHA256SUMS 后，散列、签名、包名与版本再次通过。主分支发行基线更新为 25 / 1.0.2，供下一版检查使用。

## 验证边界

优化阶段 Windows 没有连接 Android 设备及配置 AVD；发行阶段已在 Mac Android 16 模拟器补跑上述场景。模拟器回归、主机像素/文件一致性不能替代用户真机相册视觉验收或峰值内存、耗时测量。本轮未给出实测性能提升百分比。
