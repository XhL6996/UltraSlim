# 2026-10-14 优化实施与验证

按用户指定顺序完成 1、3、4、6，再完成 2、5。工作区原有改动保留；本报告仅说明本轮增量。未提交、推送或发布。

## 实施内容

1. **历史删除与转换协调**：统一使用前台 ConversionService 与旧 WorkManager 活跃状态；OutputStore 删除入口取得 conversionMutex 后再取得 saveLock，防止转换中删除正在使用的成品。批次内数据库删除、私有文件删除、队列清理及持久化在 NonCancellable 区段完成。详情页/历史页展示等待转换结束的消息。
2. **预览内存**：PreviewCache 在串行 ImageWork 真正开始执行时读取可用内存。预算保留系统余量；HEIF 在选定缩略图/主图后检查展示尺寸、原始 ISPE 尺寸和估计内存，再解码。直接从 libheif RGBA plane 缩放至目标缓冲，省掉中间全尺寸 RGBA 副本；JPEG/JXL 在像素分配前也检查预算。缓存命中不受新解码预算阻断。
3. **队列批处理**：导入使用 Set 去重（包含同一批内重复项）；失败、恢复、中断清理使用 updateAll。相同快照不发射、不唤醒持久化；过期唤醒和空数据库差异跳过。保留 revision/flush 完成屏障及写入失败重试。首页原图解析在 IO 线程增量执行，只缓存成功解析，显式刷新同步更新同一缓存；扫描仅暂存待导入的 HDR probe。
4. **历史查询**：全量/按保存状态筛选分开 SQL，保留调用接口。共享 HistoryRepository 在 Room invalidation 后、聚合 SQL 前按 500 ms 采样；首页和历史页共享结果，避免各自全表聚合。现有分页方式保留，本轮不做 Paging 改造，无需数据库迁移。
5. **扫描读取与诊断**：普通文件 HEIC/AVIF probe 和预览改用 16 KiB 缓存的 pread reader，避免先整读压缩文件；JPEG/JXL/非普通文件保留原解析路径和 256 MiB 输入上限。保留 JPEG 标记检测优先级；流式容器解析失败时回退原内存解析，兼容较大的 ftyp 头；预览回退仍受输入/像素预算约束，像素预算拒绝或解码失败不触发重复解码。普通 probe 持久化诊断每 5 秒采样，预览、编码和抛出的异常仍逐次记录。保持原生工作串行和后台线程优先级。
6. **启动清理**：查询仅取 id/inputUri/outputUri，引用集合用 Set。旧路径迁移通过条件 UPDATE 避免覆盖并发发布；条件更新失败则读取当前记录，防止错误保护已孤立的迁移副本。

原生测试另发现两个相邻问题并修复：probeWithUhdr 在读取元数据前移走 decoder；无效 tmap 元数据的 getter/分数转换失败后复制未初始化 boost。后者保留 gainmap 检出语义，并确定性保留零值元数据；来源记录更新于 third_party/libultrahdr/ULTRASLIM_UPSTREAM.md。

## 验证结果

使用 JDK 17、外置构建目录 `/tmp/ultraslim-opt-20261014` 和独立包 `app.ultraslim.optqa`。Android 测试在专用 ARM64 emulator-5580 执行。曾尝试物理设备安装，但设备锁屏导致安装等待，已停止；不计作真机验收。

| 检查 | 结果 |
|---|---|
| Debug、AndroidTest、Release 构建 | 通过 |
| JVM 单元测试 | 39 项：38 通过，1 跳过，0 失败；队列新增 7 项全通过，预算 4 项全通过 |
| 原生完整 CTest | 5/5 通过：preview_probe、transcode_regression、gainmap_preservation、avif_quality、upstream_color |
| 最终存储/预览/发布仪器测试 | 15 项：14 通过，1 跳过 |
| 最终 UI 回归 | 19/19 通过：HistoryUiTest、QueueUndoUiTest、GestureRegressionTest、AdaptiveUiTest、AutomaticReplacementUiTest |
| 流式兼容性修正后 Android JNI 复验 | 4/4 通过：HDR JPEG/HEIC/AVIF 两轮往返、预览预算、存储/缓存 |

流式兼容性修正后重新构建 Debug/Release，并重新编译全部主机目标；完整 CTest 再次 5/5 通过（15.75 秒）。UI/JVM 源码在其通过后未改动。

| 其他检查 | 结果 |
|---|---|
| lintDebug | 0 错误，41 警告，1 提示；不宣称无警告 |
| Debug/Release APK 对齐 | 两个 APK 各 3 个原生库，LOAD、RELRO、APK 16 KB 对齐均通过 |
| 仓库结构/本地链接/素材与许可证散列 | check_repository.py 通过，471 个源码候选 |
| git diff --check | 通过 |

最终仪器测试包括 HistoryRepositoryTest、HistoryDeletionCoordinationTest、ImageWorkTest、PreviewBudgetTest、StorageAndPreviewTest、OutputStoreTest、PublicationRegressionTest。删除测试检查服务启动窗口、转换锁、文件/历史/队列及 TaskDao 持久化一致性。统计测试检查共享查询、采样、停止订阅、重新订阅和最终状态到达。

跳过项：JVM 的 AppleLiveBundleTest.realHeicBundleForMacAcceptance 未配置外部 Live Photo 样本目录；设备的 PreviewDecodeTest.decodeBigAvifPreview 未开启专用诊断参数/样本。两者均为已有 opt-in 测试，不计为通过。中途新增队列测试声明缺少空格导致一次编译失败，已修复并重新执行全部 JVM 测试。

## 性能证据与边界

- 构造的 32 MiB HEIC/AVIF sparse-tail 文件，probe 实际读取 49,152 字节；这是该测试样本的 I/O 结果，不代表所有文件固定读取量。解析可能读取元数据、派生图描述及非连续范围。
- 97 像素缩放及不缩放测试中，新预览 JPEG 与全尺寸复制后缩放的参考结果逐字节一致；另覆盖 padded stride、严格/兼容 HDR HEIC/AVIF、异常元数据、预算不足及大主图/安全缩略图。
- 去掉一个 RGBA 中间副本节省 `宽 × 高 × 4` 字节；5888×4416 约为 99 MiB。该值是减少的缓冲分配，不是实测进程峰值 RSS。
- 预算是像素解码准入估算，不是 codec 内部及容器元数据分配的硬上限；容器解析发生在像素预算检查之前。低内存下可能拒绝新的预览解码；已有缓存仍可显示。
- 未做真机批量速度、耗电或峰值 RSS 对比。保留 gainmap 默认语义、兼容模式转换规则、原生串行处理及已有线程限制。
- 真正的单条队列修改仍会复制/比较列表，本轮优化批量调用与无效写入，没有引入索引化队列存储。

构建与测试产物保存在外置临时目录；此报告记录可复核结果，临时目录不保证长期保留。
