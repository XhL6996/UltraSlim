# 开源准备：目录与构建整理

## 完成内容

- 原创代码按维护者选择加入 GPLv3（GPL-3.0-only）许可；第三方源码和素材保留原授权。增加贡献说明、依赖来源及主要原生许可快照。
- README 改为当前项目入口；开发文档在 `docs/`，历史记录迁入 `docs/reports/`，未实现设计迁入 `docs/design/`，各自提供索引。历史报告只将个人绝对路径和设备序列号替换为占位符，保留结论与验收边界。
- 根目录四个旧构建目录移入 `build/legacy/`（约 319 MB），没有删除已有二进制/诊断结果；现有 APK 留在 `build/releases/`。原有 App/native 功能修改按文件散列核对保留。
- `verify.sh` 使用外置主机测试目录；设备模式采用独立 `.verifyqa` 包。Gitea 自托管工作流不再自动运行外部 PR 的代码，产物收集路径与构建目录一致。
- 共享测试素材存入 `tests/fixtures/libultrahdr/`，修复源码克隆后缺少被上游忽略图片的问题；主机与 Android assets 共用两张未修改的上游图片，附许可、来源和 SHA-256。
- 需要手工提供大 AVIF 的预览诊断测试改为显式 `previewSample` 参数启用；自动回归不依赖个人素材。
- 增加 `scripts/check_repository.py`，检查源文件目录、主要生成/签名文件误入、明显凭据标记、文档本地链接及素材/许可散列。

## 验证

- 工作区与源码快照的结构/链接/散列检查均通过；改坏素材后检查器按预期失败，恢复后通过。
- 主机 CTest 3/3 通过；源码快照路径下重新编译并运行亦 3/3 通过。
- Debug / Release / lint 构建通过；JVM 单测 19 通过、1 跳过；Debug 和 Release APK 的 3 个原生库均通过 16 KB 对齐检查。
- 不含本地源码缓存/原有上游测试素材的快照成功构建 Android 测试包，包内两张图片和许可与固定散列完全一致。
- 快照验证复用了已编译的 host / Android 原生依赖，并非重新从网络下载全部依赖的冷构建。本轮为目录/构建验证，没有新增真机或相册显示结论。
- 此前交付的 1.0.5 APK 散列保持不变；未更新日常手机安装。

本机回溯备份在被忽略的 `build/project-layout-backup/`，包含整理前源码、文件散列和旧构建目录映射。验证日志位于 `/tmp/ultraslim-open-source-verify.log`、`/tmp/ultraslim-open-source-snapshot.log` 和 `/tmp/ultraslim-source-snapshot-android.log`；这些是本轮临时产物，不要求其他贡献者拥有它们。

本次未改写 Git 历史或进行远程发布；公开现有历史、对应源码/依赖材料和发行签名按 [发布准备](../RELEASING.md)另行执行。
