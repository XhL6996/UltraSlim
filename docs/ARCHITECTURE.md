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

自动替换默认关闭，单独保存校验与系统确认状态；后台完成后仅在应用回到前台、批次停止且校验通过时请求系统回收站操作。不能依赖内存中的一个布尔值作为原图删除依据。

## 核心约束

- 严格 HDR 路径保留增益图样本和参数，无法满足时明确拒绝。显式兼容模式的 RGB/YUV 转换误差见 [兼容性文档](COMPATIBILITY.md)。
- 原生编码按图片串行，图片内部允许受控并行；不得把 UI 生命周期作为编码生命周期。
- 相册输出、应用私有成品、导入副本和预览缓存用途不同；清理必须先检查引用关系。
- `app/schemas/` 是版本化迁移依据，属于源码资产；不应当成构建缓存忽略。
- JNI 和原生参数/结果结构变动须同步维护桥接、快照默认值和迁移测试。

## 新文件放在哪里

长期有效的开发说明放 `docs/`；有日期、样本或设备边界的历史结论放 `docs/reports/`；未实现的方案放 `docs/design/`。历史报告中的版本号和本地诊断命令描述当时状态，不能替代当前构建入口。

用户样本、截图、设备数据库、打包 APK、测量输出放被忽略的 `build/`。仅有明确再分发许可、体积合理且回归必需的固定素材才加入 `tests/fixtures/`，附来源、许可证和散列。不要修改第三方源码的目录结构或删除其版权头；来源和补丁见 [第三方说明](../third_party/README.md)。
