# 1.0.4：滑动交互与校验后自动替换

## 行为

- 相册导入面板固定展开，列表拥有自己的滚动空间，切换标签不改变面板高度。关闭使用「关闭」、返回或点击遮罩。
- 队列向左移除、历史日期向右请求删除：只在手指松开且滑过至少 45% 行宽（最少 96dp）时触发。快速短滑、滑回、系统取消均不触发动作。正在转换的条目不能滑动移除。
- 连续移除合并成一次撤销提示，撤销按原顺序恢复实际删除时的状态；原来正在分析的条目重新分析，不停留在 PROBING。
- 取消批量导入保留已完成导入的条目，并继续其必要的元数据分析。

- 历史页中文筛选标签统一映射到数据库保存状态，修复待保存/已保存/保存失败筛选后空白。

## 自动替换

设置页新增「校验后自动替换原图」，默认关闭，开启前弹窗说明。开启同时开启自动保存；关闭自动保存会关闭自动替换。旧批次快照缺字段按 false，不追溯替换旧成品；「先试一张」始终保留原图。

流程：转换前记录源文件 SHA-256 → 原有编码和输出验证 → 自动替换的额外验证 → 保存相册 → 从相册重新读取并核对 SHA-256 与系统解码 → 批次结束、应用在前台时再次核对 → 系统回收站请求。

额外验证包含：

1. 输出更小且不为空；原图仍与转换前一致，且可安全映射到真实 MediaStore 图片 URI。
2. 原生 probe 的尺寸、HDR/EXIF 状态与成品记录一致；系统 ImageDecoder 可解码且不接受部分图片。HDR 要求 API 34+ 并实际读到 gainmap。
3. 源图含实况时，成品必须保留实况时间点、视频长度等信息及视频 SHA-256。
4. 真正保存后的成品与验证过的私有文件逐字节一致；请求回收站前再次检查两端文件、设置、批次取消状态和历史记录。

新成品保存在相册 UltraSlim，原图通过系统回收站回收，不直接覆盖原文件。回收站保留期限由系统决定。系统没有支持的解码器（例如 JXL）、HDR 校验能力不足、仅有无法安全映射的 Picker/临时 URI、源文件变化或丢失、成品被编辑/删除、保存失败、实况丢失、批次取消等情况均保留原图。原因写入历史详情的参数摘要。

Android 11+ 支持回收站；使用官方 [MediaStore.createTrashRequest](https://developer.android.com/reference/android/provider/MediaStore#createTrashRequest(android.content.ContentResolver,%20java.util.Collection%3Candroid.net.Uri%3E,%20boolean))。不申请全盘访问或媒体管理特权；系统要求的确认仍由用户操作。取消后不自动重试同一批次；后台完成下次回前台继续，进程中断中的系统请求不重复弹出。

MediaStore URI 解析同时收紧：保留原 volume，不将 Photo Picker 的数字尾段猜成 MediaStore ID，避免定位到其他图片。

## 持久化与验证

Room 升级 4 → 5，新增 replacements 表记录 PREPARED / READY / REQUESTED / DONE / CANCELLED / SKIPPED。原历史和队列不修改结构。

设备回归包含 GestureRegressionTest、AlbumGestureTest、HistoryUiTest、AutoReplacementTest、AutomaticReplacementUiTest、PersistenceTest；系统确认与取消使用模拟器内临时创建的照片，测试不操作日常图片。新增功能不等于所有厂商相册的视觉/HDR/实况兼容验收。

### 本次结果（2026-10-12）

- Android 模拟器：上述回归加 QueueUndoUiTest、导入 Live 过滤与 SDR 输出兼容回归，共 28 项全部通过。真实系统回收站弹窗的确认/取消各有端到端测试。
- JVM：19 项通过、1 项跳过；主机 CTest 3/3 通过。Debug / Release 构建、Lint 检查通过。
- APK：arm64，3 个原生库均通过 ELF/RELRO/APK 的 16 KB 对齐检查，v2 签名验证通过，签名与已安装版本一致。
- 连接手机覆盖升级为 1.0.4（versionCode 10）；数据库 4→5，42 条历史逐字段未变，原有空队列未变。自动替换保持默认关闭，replacement 请求数为 0。进程启动正常，无该进程的 fatal / migration 错误；本次未在日常照片上执行替换。
- 交付 APK 与从手机拉取的已安装 APK SHA-256 相同：`08f7872936b0901fad8ff16e6b167df42b96e0c00ce3d7beb5a6a96ba258df8c`。
- 产物：`build/releases/UltraSlim-1.0.4-arm64-debug.apk`（41,483,476 字节），校验文件同目录 `.apk.sha256`。真机升级核对不等同于厂商相册 HDR/实况显示验收。
