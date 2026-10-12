# 后台转换检查与修复（1.0.5）

## 定位与边界

用户反馈部分手机无法后台转换；暂未取得反馈用户的机型、系统版本、停止时机或日志，不能将全部报告归因于单个系统限制。

代码确认旧任务由 WorkManager / JobScheduler 执行，即便调用 setForeground 仍是 Job。Android 16 起这类前台长任务会消耗作业配额；[官方文档](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running)建议这类场景改为直接启动前台服务。

旧温控逻辑返回 Result.retry，结束服务后依靠后台调度重新启动；启动前还要异步读取数据库。调度等待、启动限制、OEM 后台限制可能表现为停住，原 UI 又可能在尚有 READY 项时显示完成汇总。

Android 15+ 的 mediaProcessing 和 dataSync 类型都受后台累计 6 小时/24 小时限制，[官方超时规则](https://developer.android.com/develop/background-work/services/fgs/timeout)。旧注释误以为只有 dataSync 有此限制，已修正。直接服务也不能绕过这项时限、强制停止和厂商进程管理。

## 实现

- HomeViewModel 将持久化批次交给 ConversionService；新任务不提交 JobScheduler。onCreate 在数据库等待之前进入前台。
- ConversionRunner 复用原转码、校验、保存和 checkpoint 逻辑；保留 ConversionWorker 兼容升级前已调度任务。共享互斥锁避免两条入口同时编码。
- 直接服务持有有时限的 PARTIAL_WAKE_LOCK，使熄屏后 CPU 可继续编码；完成、异常、超时、销毁释放；温控等待时不持锁。
- 温控采用原有有上限的指数退避，保持同一个前台服务，等待期间每秒响应取消；不从后台重启任务。
- onTimeout 立即退出前台并停止服务，取消后续工作。原生单张编码不能在调用中途强行中止；返回后不发布未完成结果，队列恢复 READY。进程被杀后的 checkpoint 恢复亦明确提示中断。
- 用户取消升级前正等待调度/退避的旧任务时，同步取消该 WorkRequest，不必等系统重新调度才能停止。
- 服务状态接入首页和自动替换协调器。服务彻底退出前不会出现替换确认；待处理项目仍存在时显示暂停信息。启动被系统拒绝时提示回到应用重试，保留队列。
- 进度通知可点击回到应用。没有修改用户的省电设置或申请豁免系统限制。

开发时发现 Core 1.17.0 的 ServiceCompat 仍将类型掩码限制在 API 34 集合，会剥离 mediaProcessing；使用平台 startForeground(id, notification, type)，API 29 以下使用双参数版本。该问题由设备测试发现并修正，不作为旧版后台失败原因。

## 验证

- 使用独立 `.backgroundqa` 包及测试生成图片，不触碰日常包的照片/历史。
- BackgroundConversionTest：系统短时限触发真实 onTimeout；持有编码互斥锁直到 Activity 退到后台且屏幕熄灭，然后释放并执行真实 AVIF 编码；模拟温控进入暂停，降温后后台恢复。检查没有 WorkManager 转换 Job，完成后不存在活动唤醒锁。
- 自动替换端到端测试已改走直接服务；验证系统确认批准/取消及输出、原图状态。
- Android 15（API 35）上述后台 3 项、自动替换 2 项、持久化 5 项通过。
- 最终 Android 15/API 35 通过 32 项（含交互、SDR AVIF、导入 Live、历史、自动替换和后台）；Android 16/API 36 通过 11 项（后台 4、自动替换 2、持久化 5）。新增第 4 项覆盖旧 WorkManager 延迟任务的立即取消。日志：`/tmp/ultraslim-background-final-api35.log`、`/tmp/ultraslim-background-final-api36.log`。
- JVM 单测 19 通过、1 跳过；Debug / Release / lint 构建通过（日志 `/tmp/ultraslim-background-final2-build.log` 和 `/tmp/ultraslim-release-105-final-build.log`）。本轮未改原生编码核心。
- APK：`build/releases/UltraSlim-1.0.5-arm64-debug.apk`，versionCode 11，41,499,912 字节；3 个 JNI 库 16 KB LOAD/RELRO/APK 对齐通过，v2 签名验证通过，与原 debug 包证书一致。
- SHA-256：`63579b321347f07a8611b0ffa761320688a30130e23cc48fad80fe21e090a72a`。
- 连接手机覆盖安装并启动成功，安装 APK 回读散列与交付物相同。42 条历史所有字段、0 条队列、0 条替换记录均与安装前相同。备份与核对：`/tmp/ultraslim-release-105-before`、`/tmp/ultraslim-release-105-after`。这是升级/数据保留验证，不等于全部厂商长时后台运行验证。
- 新建 API 36 AVD `ultraslim-background36` 用于验证；测试用温控覆盖和超时设置已恢复，测试照片已清理。
