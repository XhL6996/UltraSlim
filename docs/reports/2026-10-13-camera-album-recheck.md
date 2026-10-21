# 相机相册自动替换复查

## 结论与范围

用户确认本次问题发生在 **ColorOS 17**，不是最初通过 ADB 连接的测试机。因此，该机的设置与任务快照不能解释此次反馈。此前“相机相册也支持”的答复仅有实现意图依据，表述过度。用户随后连接 ColorOS 设备，实机复查结果见下文。

## 当前实现

- `SourceMedia.read` 从原图的 MediaStore 行读取实际存储卷和 `RELATIVE_PATH`，没有排除 `DCIM/Camera/`。
- 自动替换必须先通过 `AutoReplacement.stage`。包括全局开关、成品更小、原图定位与哈希、系统解码、HDR 增益图、实况视频完整性等检查。
- 只有 PREPARED 替换记录才让 `OutputStore.save` 写回原目录；预检查失败则普通保存至 `Pictures/UltraSlim/`，原因写入历史的“替换结果”。
- 回原目录保存失败同样回退 UltraSlim，并保留原图。正常保存和系统回收站是两个阶段，必须区分。
- AVIF 不附带原实况视频，实况源转换为 AVIF 时不能通过实况保留检查。
- 当前 `prepareTrashRequest` 已分别处理无法定位/访问原图、SecurityException、IllegalArgumentException 和其他系统异常。仍需要 ColorOS 的具体失败结果，不能仅凭系统版本判定为权限问题。

## 验证

- 新增 `CameraAlbumPublicationTest`：创建 64×64 AVIF，使用生产 `SourceMedia` / `MediaStoreSaver` 和 related URI 写入 `DCIM/Camera/`，检查卷/目录和平台解码，并校验源文件哈希与未入回收站状态。只删除测试自己创建的 URI，不修改设置、任务、历史或既有照片。
- Android 测试 APK 构建成功；API 35 模拟器 **1 项测试通过**。这覆盖相机目录发布，不覆盖第三方相册分类、真实相机文件所有权下的回收站授权或 ColorOS。
- 已连接测试机的测试包安装被系统拒绝：`INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`。测试未在真机执行，不能声称真机通过。
- 只读核对的该机数据：设置文件没有 auto_replace_originals（默认关闭），最近三批均为 42 张且快照关闭替换；41 条保存记录中 12 条标记实况来源。这些事实仅适用于这台设备。
- 手机设置文件前后逐字节一致。此次没有修改应用生产逻辑，没有替换或回收用户照片。

## ColorOS 17 实机复查

- 设备型号（`<device-model>`），系统版本（`<build-number>`）/ Android 17。
- 原安装为 **1.1.5 / code 17**。拉取设备实际 APK 后检查 DEX：不存在 `SourceMedia`；`OutputStore$save$2$uri$1` 调用旧 `MediaStoreSaver.save` 签名，仅有默认相册参数，没有原目录、卷、日期或 related URI 参数。确认该安装包缺少存回原相册的修复，不是仅凭版本号推断。
- 原设备数据库完整性检查通过。只有一批 10 张的记录，快照 `autoReplaceOriginals=false`；5 条历史、10 个任务、0 条替换记录。设置文件尚未创建，自动替换使用默认关闭。所查一张旧记录来自 `Pictures/OPPO Share/`，这些记录不能证明之前执行过开启替换的相机任务。
- 使用现有已验证的 **1.1.8 / code 20** APK，重新验证 16 KB 对齐与 v2 签名后覆盖安装成功。安装前后 history/tasks/batches/replacements 全部行内容一致；没有清除数据或改变设置。
- 新版本实机 `avifCanBePublishedBesideCameraSource` 通过：使用真实相机原图的 related URI，将独立生成的 64×64 AVIF 写入 `external_primary/DCIM/Camera/`，平台读回成功，源文件哈希及未回收状态保持。清理范围仅为测试输出。
- 新增可选 `diagnoseCameraSampleWithoutReplacingOriginal`，指定 `cameraSourceUri` 时只读原片，使用内存预算检查及单线程原生编码，把结果写到应用缓存；不创建任务或替换记录，不开启自动替换，不发起回收站对话框。
- 相机样本为 3072×4096 HDR JPEG，有 EXIF，无实况视频。AVIF quality 92、兼容模式、retrySmaller 转换成功：**3,669,130 → 2,292,746 字节**。平台 HDR 增益图解码通过，`prepareTrashRequest` 成功返回 sender。未启动 sender，因此只证明可创建请求，不声称系统授权后回收已验证。
- 实机两项测试通过（56.647 秒），测试原图仍在相机目录且 `is_trashed=0`。应用启动成功，用户历史/任务/批次逐行一致。

当前明确解决的是旧安装包未包含原相册保存实现的问题。全流程自动替换仍需用户开启选项后开始新任务，并确认系统回收提示；本次没有代替用户回收真实原图。若某张照片仍失败，应读取该次任务的替换结果，不能把目录写入通过等同于所有照片、厂商相册显示及完整回收流程均通过。
