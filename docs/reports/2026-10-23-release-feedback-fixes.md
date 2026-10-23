# 公开版反馈与可靠性修复（2026-10-23）

基线：GitHub main `52953091847977fc2c605e43cf6506d1f87a96af`，公开版 1.0.0 / versionCode 23。修复版本为 1.0.1 / versionCode 24；正式安装包见 [GitHub Release](https://github.com/linyusenzz/UltraSlim/releases/tag/v1.0.1)。

## 温控等待

旧服务的 8 次指数退避累计等待 7,650 秒（127.5 分钟），等待中不复查是否已降温；界面仍显示转换中的旋转指示。温控检查还先于 DONE/SKIPPED 判断，可给已完成条目写入过热提示。这些是代码确认的问题，不代表已复现反馈手机为什么一开始就报告过热。

保留 Android `THERMAL_STATUS_SEVERE`（等级 3）门槛。新等待为 5、10、15、15、15、15、15、15 秒，总上限 105 秒；每约 5 秒复查热状态，每秒检查取消和队列是否已清空。等待期间释放唤醒锁，恢复后续转；超时停止服务，保留 READY 条目供用户继续。旧 WorkManager 任务遇到热限制直接暂停，避免继续长时间后台退避。

UI 显示系统热状态等级、剩余时间和停止等待/继续按钮。该等级是系统的热节流状态，不是摄氏温度，未替换为电池温度、未开放绕过热保护。语义依据 [Android PowerManager 文档](https://developer.android.com/reference/android/os/PowerManager#getCurrentThermalStatus())。

## EXIF 与照片时间

没有收到原图、输出文件或具体机型。主机测试未发现普通相机/时间 EXIF 字段在支持的跨格式转换中普遍丢失，不能把相册无法显示 EXIF 等同于文件没有 EXIF。

确认并补全的 Android 时间链路：

- 临时权限导入会复制图片到私有目录，来源仅存于 provider 的时间原先可能丢失。现在通过独立原子写入的 sidecar 保留，重启后仍可读取，与原图引用一并清理；不改图片字节。
- 优先使用来源拍摄时间，缺失时读取 EXIF DateTimeOriginal、OffsetTimeOriginal、SubSecTimeOriginal。无时区 EXIF 只能按本机时区解释；不把 DATE_ADDED 或文件修改时间假装为拍摄时间。
- 相册发布时分别传递拍摄时间和修改时间；发布后重新检查并补写已知拍摄时间，以处理扫描器不能识别输出 EXIF 的情况。修改时间缺失时仍可用已知拍摄时间作为回退。
- 保留/移除 EXIF 与 GPS 继续遵从原有选项，不通过重写输出 EXIF 来强迫相册排序。

MediaStore 索引和文件时间写入会受到设备 provider/FUSE 行为影响；拒绝写入时记录警告，不能承诺所有相册都按同一种时间排序。系统新增媒体时间不会伪装成原图时间。

## 其他修复

- 原图 URI 缓存按历史 ID 与当前 URI 绑定，忽略旧的异步解析结果；发起回收时重新读取数据库链接。
- 首页恢复与执行共用批次快照解析。损坏 JSON、空 ID 列表、混杂类型、重复 ID 显式失败，保留 DONE/SKIPPED，不影响其他批次。
- JXL `jhgm` 的 ISO、颜色描述、ICC 与码流边界共用校验，损坏 HDR 元数据不会静默降为 SDR；在移动元数据缓冲前读取增益数值。
- 检查 JXL 元数据写入返回值；JPEG 解码捕捉截断/损坏扫描警告，避免 libjpeg 自动补齐行后被当成成功。
- 统计文案说明压缩体积差不等于已释放存储空间。设置页可复制诊断信息并打开 GitHub 问题模板；复制内容不含图片、文件路径、GPS 或设备序列号，默认配置明确标注可能与当前批次不同。

## 发行身份

下载公开 1.0.0 APK 并验证签名与包信息：`app.ultraslim`、versionCode 23、versionName 1.0.0。

- APK SHA-256：`3e655478fd933c89733f60c62fc49fded6a636be0f678ad0e257848d2a2877d3`
- 证书 SHA-256：`e54990c754cbede4e7ab638d800a33b893f1d2f5f29bbacd11528905419a4d39`

`build_release.sh` 在签名验证后检查固定证书、包名、递增 versionCode 和源码版本。6 项 Python 回归覆盖正确下一版、错误证书、测试包名、旧版本、源码版本不一致及缺少/多个签名者。仅拒绝 Android Debug 证书不足以防止错用另一把正式密钥。

1.0.1 的已验证 Release 构建在保管正式密钥的 Mac 上完成签名，证书与 1.0.0 一致。签名前后的所有原 ZIP 条目逐字节相同；签名、包名 `app.ultraslim`、versionCode 24、versionName 1.0.1 与 16 KB 对齐检查通过。正式 APK SHA-256：`a139ecd1dc4bbadd92323ad840e90c7ca7f47e9ff7f06fc23fc371d6f3208826`。签名与包身份兼容不等于已完成真机覆盖升级验收。

GitHub v1.0.1 已作为最新正式版发布，tag 对应 `f9985125884fe673d77ca4a3ee14c8759caa06bf`。APK、对应源码 ZIP、许可声明及 SHA256SUMS 四个附件的远端摘要均与上传文件一致；从公开下载地址重新获取 APK 与校验文件，散列一致，并再次通过签名与包身份校验。随后将主分支的发行基线更新为 versionCode 24，供下一版防误发检查使用。

## 验证

- Linux/Clang 主机构建通过；CTest 6/6 通过：transcode_regression、gainmap_preservation、upstream_color、avif_quality、preview_probe、metadata_regression。
- metadata_regression 比较实际 EXIF 字节中的拍摄时间、数字化时间、时区、子秒、相机和镜头信息：SDR 四格式互转，HDR 全部支持的 preserve 组合；显式移除 EXIF；JXL 4× 增益回报；12 个损坏 jhgm 样本；6 个 baseline/progressive 截断 JPEG。还验证普通 libjpeg 对部分截断样本仍会返回完整行数，确认新增拒绝逻辑覆盖实际缺口。
- Android JVM 测试：45 项通过，1 项跳过（现有 Apple Live 真样本导出测试缺少外部样本目录）。新增覆盖热限制策略、EXIF 时间解析和关联缓存。
- Debug、未签名 Release、设备测试 APK 均构建成功；Debug/Release Lint 均为 0 错误（分别 47/45 条警告及 1 条提示，包含现有 API/依赖与界面规范建议，未在本轮升级依赖）。两个应用 APK 的全部 3 个原生库通过 16 KB LOAD、RELRO 与 ZIP 对齐检查；包名与版本检查通过。
- Python 发行身份回归 6/6、仓库布局/本地链接/fixture/许可哈希检查、发行脚本语法检查及 `git diff --check` 通过。
- 新增设备回归覆盖快照恢复、温控恢复/超时/取消/清空队列、临时导入与相册发布时间。设备测试包可编译，但当前无连接设备及已配置 AVD，未执行；AdaptiveUiTest、滑动/撤销/历史/自动替换及真实相册验收同样未运行。

视频瘦身只记录为独立待评估需求，未实现，也未承诺交付日期。
