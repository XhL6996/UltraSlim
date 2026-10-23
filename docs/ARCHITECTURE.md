# 架构与目录约定

## 模块边界

| 位置 | 职责 |
| --- | --- |
| `app/src/main/java/app/ultraslim/ui/` | Compose 页面、状态展示和用户操作 |
| `app/src/main/java/app/ultraslim/convert/` | 队列、服务、逐项转换、输出校验、相册保存与替换协调 |
| `app/src/main/java/app/ultraslim/data/` | Room 数据库、任务快照、历史和 DataStore 默认设置 |
| `app/src/main/java/app/ultraslim/bridge/` | Kotlin/JNI 接口与结果类型 |
| `native/jni/` | 平台无关转码核心与 JNI 适配 |
| `native/host_test/` | CTest 回归、增益图/色彩诊断工具 |
| `native/deps/` | 固定版本的依赖超级构建和本地补丁 |
| `third_party/` | 少量内置上游源码及来源、许可记录 |
| `apple-live/` | 独立 SwiftPM 项目：HEIC/MOV 配对和 PhotoKit 导入 |
| `tests/fixtures/` | 主机/Android 共用、有明确来源和许可的固定输入 |

## Android 数据流

导入 → 持久化读取权限或复制临时授权输入 → probe/队列 → 批次参数快照 → `ConversionService` → `ConversionRunner` → JNI/native 转码 → 成品校验 → 私有持久化输出/历史 → 可选保存系统相册。

新任务由用户在界面启动直接前台服务；保留 `ConversionWorker` 作为升级前已调度任务的兼容入口，两者共享互斥锁。已完成项逐项写入检查点。进程中断后未完成项恢复为可继续状态，不把半成品当作成功。

自选输出目录使用系统 `OpenDocumentTree` 与持久读写授权，URI/名称写入方案和批次快照，可保存为默认设置，不增加文件浏览器。关闭自动保存时隐藏目录选择但保留选择结果。自动替换临时覆盖输出位置，`outputToFolder` / `effectiveOutputDirectoryUri` 统一解析实际目的地；保存的目录 URI/名称保留，关闭替换后恢复。`FolderOutput` 经 DocumentsContract 发布、回读校验，成功后历史保存文档 URI；失败保留私有成品供重试，不静默改存相册。重试仅凭完整字节指纹复用已有成品，重名但内容不同则新建。目录授权保留供旧批次重试和导入共用；历史删除不删除已发布的目录文件。

自动替换默认关闭，单独保存校验与系统确认状态；后台完成后仅在应用回到前台、批次停止且校验通过时请求系统回收站操作。不能依赖内存中的一个布尔值作为原图删除依据。

本地 SAF 输出的修改时间可能被提供者 fd 权限限制；`OutputFileTime` 首先通过已授权 fd 设置、关闭后读取实际文件时间校验，避免使用过期的 DocumentsProvider 索引。失败后，尝试 `MediaStore.getMediaUri` 携带已有文档授权获得对应媒体 fd，再尝试系统 ExternalStorageProvider 输出文件的 StorageManager 卷目录映射路径。由系统按当前授权决定是否允许，不声明或请求所有文件访问权限；用户已在关闭该权限时验证保存成功。拒绝路径穿越，不遍历存储空间，也不修改输入。日志区分各条路径与 errno / 文件 UID / 调用 UID。保存原因写入私有 `.save-error` 文件并显示于队列/详情，成功或删除历史时清除。

`keepFilename` 默认关闭，并存入 DataStore 和批次快照。保留文件名时输出只换扩展名；目标目录冲突则添加成品 SHA-256 前 8 位，短摘要冲突继续延长，完整摘要仍冲突才加编号。原名无任务标识，不能仅凭同名、同内容复用外部照片；`PublicationReceipt` 在写入前原子记录本次创建的 URI，重试必须同时命中该凭据与完整字节指纹，成功写入历史后清除凭据。成品历史及分享使用提供者返回的实际文件名。

## 核心约束

- 严格 HDR 路径保留增益图样本和参数，无法满足时明确拒绝。显式兼容模式的 RGB/YUV 转换误差见 [兼容性文档](COMPATIBILITY.md)。
- 原生编码按图片串行，图片内部允许受控并行；不得把 UI 生命周期作为编码生命周期。
- 相册输出、应用私有成品、导入副本和预览缓存用途不同；清理必须先检查引用关系。
- `app/schemas/` 是版本化迁移依据，属于源码资产；不应当成构建缓存忽略。
- JNI 和原生参数/结果结构变动须同步维护桥接、快照默认值和迁移测试。

## 新文件放在哪里

长期有效的开发说明放 `docs/`；有日期、样本或设备边界的历史结论放 `docs/reports/`；未实现的方案放 `docs/design/`。历史报告中的版本号和本地诊断命令描述当时状态，不能替代当前构建入口。

用户样本、截图、设备数据库、打包 APK、测量输出放被忽略的 `build/`。仅有明确再分发许可、体积合理且回归必需的固定素材才加入 `tests/fixtures/`，附来源、许可证和散列。不要修改第三方源码的目录结构或删除其版权头；来源和补丁见 [第三方说明](../third_party/README.md)。
