# 1.1.6：后台发布、原相册替换与回收站修复

## 报告与修复

1. **小窗转换时外部应用反复出现截图分享选项**：生产代码没有自动发起分享或截屏的路径；成品原先逐张写入 MediaStore，且继承 Screenshot 等文件名前缀、缺少原图时间。外部面板的来源尚未在报告机型确认。现在后台/小窗/窗口失焦时继续转码，但成品留在应用的持久目录。回到完整且获得焦点的应用窗口、转换批次结束后，按原批次的自动保存设置继续入册。已进行中的单次写盘可以完成，后续图片延后。截图文件名改为中性 Photo 前缀；通知和队列说明待回应用保存。
2. **每次 100 张与已删除原图反复确认**：移除 `take(100)`。系统请求前重新查询原图（包含 IS_TRASHED），已回收标记完成、已删除/不可访问标记跳过，不再哈希或申请授权。按最多 2000 个去重 URI 发起一次系统请求，取消后取消剩余 READY 替换。Room 更新以 500 个 ID 分段，避免大量条目越过 SQLite bind 上限。手动入口也会重新过滤，并报告实际本次数量。
3. **ColorOS 17 回收站报错**：此前所有 createTrashRequest 异常均被吞成通用“系统不支持”。现在区分访问权限、系统不接受 URI 和其他系统异常，保留诊断日志。支持 Android 官方 ExternalStorageProvider / MediaDocumentsProvider 映射，以及验证为图片的 MediaStore Files URI。仍不猜测 Photo Picker/云端数字 ID；无法定位时提示通过相册导入授权。尚未拿到具体 ColorOS 机型，不能宣称完成该 ROM 真机验收。
4. **替换未回到原相册**：校验准备成功的成品，保存到原图实际 volume + RELATIVE_PATH；确认该位置及成品完整性后才申请回收原图。使用 QUERY_ARG_RELATED_URI 支持原图所在的特殊路径。无法写回时退回 UltraSlim 相册、注明失败且保留原图。旧版 READY 记录在校验时可把本应用成品移回同一存储卷上的原相册；失败不回收。文件时间从原图拍摄时间/修改时间读取，并在发布扫描前设置本应用成品的真实文件 mtime，避免仅写 DATE_TAKEN 后被系统扫描器覆盖。
5. **转换顺序**：新批次持久化列表使用队列倒序，从列表末尾开始；试转也选择最后一张 READY。界面列表顺序不变，旧批次恢复仍遵循原快照。拍摄时间保留与倒序发布共同减少相册排序颠倒，实际排序规则仍由相册决定。

## 官方依据

- [MediaStore](https://developer.android.com/reference/android/provider/MediaStore)：target Android 16+ 的回收站请求最多 2000 URI；getMediaUri 仅映射受支持的系统 DocumentsProvider；QUERY_ARG_RELATED_URI 用于向受限制的原图目录创建相关副本。
- [MediaColumns](https://developer.android.com/reference/android/provider/MediaStore.MediaColumns)：DATE_TAKEN 和 DATE_MODIFIED 由元数据/文件时间索引。测试发现通过 /proc/self/fd 直接 setLastModified 被 FUSE 拒绝；解析本应用持有的输出 FD 对应路径后设置成功，未修改图片字节或增益图。

## 验证

- API 35 ARM64 模拟器：存储与后台回归 18 项通过，UI/自适应/滑动撤销/历史回归 24 项通过，共 42 项。
- 新增覆盖：103 个真实 MediaStore 输入中 1 个已回收、1 个已删除，系统请求准确包含剩余 101 个；2100 行日志取消；Files URI 图片映射；真实系统允许/取消弹窗；同相册及 2020 年时间保留；旧 READY 原图外部删除/回收后不再弹窗；后台转换只留持久成品、返回自动发布；倒序快照且不打乱界面列表。
- JVM 25 项通过、1 项既有跳过。截图 5/5。保存说明文字变动导致 4 张参考图变化，已逐张查看差异，确认只涉及说明文字及正常换行后更新。
- Debug/Release 构建、Debug/Release Lint 通过；3 个原生库 ELF/ZIP 16 KB 对齐和 APK v2 签名校验通过。
- 本轮未改原生编码器或数据库表结构，不改变 HDR 增益图保留规则。
- 未在 ColorOS 17 / 微信 / 小红书上完成真实复现；小窗弹窗来源与厂商回收站实现仍需报告机型复测。

## 交付

- APK：`build/releases/UltraSlim-1.1.6-arm64-debug.apk`
- 包名 `app.ultraslim.debug`；versionCode 18；沿用当前 Debug 签名。
- SHA-256：`d50fdc286ccea9a23978666a95f431f1003f6c4a69bd950a8636b706f76d39ed`
- 验证日志：`build/ui-review-1.1.6/`。
- 本轮未更新用户手机、未提交或推送 Gitea。
