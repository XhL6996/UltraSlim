# 发布准备

本文件说明准备流程，不会自动创建远程仓库、推送代码或发布 Release。

## 源码检查

1. 查看 `git status`，确认当前功能修复、新测试、Room schema 和本次文档/素材调整均纳入计划提交；不要只提交本次目录文件而漏掉此前未提交源码。
2. 运行 `python3 scripts/check_repository.py` 和适用的构建/回归；使用干净检出确认没有依赖本机未跟踪文件。原生依赖需要按配置下载构建。
3. 检查项目许可证、[依赖声明](../THIRD_PARTY_NOTICES.md)、第三方完整版权头及测试素材来源。GPLv3 原文在根目录 `LICENSE`。
4. 不提交 `local.properties`、keystore、环境凭据、构建目录、APK、个人样本和设备备份。忽略规则不会移除已进入 Git 历史的内容；公开现有历史前另外检查历史对象和提交信息。本次目录整理不改写 Git 历史。

## APK 和配套工具

1. 更新 `app/build.gradle.kts` 的版本与发布说明，运行 Debug/Release、单测、lint、主机测试及本次变更涉及的设备回归。
2. 使用发行环境中的 keystore 签名 Release APK；不要把开发机 Debug 签名当成长期公共发行密钥。保存好密钥供后续覆盖升级。
3. 签名后检查 APK 对齐、`apksigner verify --verbose` 和 SHA-256，并在测试设备验证安装/升级、队列与历史保留。
4. 二进制、源代码归档和校验文件作为发行附件，不放入源码目录。对应源码应与发行 APK 的提交、依赖版本和补丁一致，包含构建脚本；准备所用依赖的对应源码和许可材料，不能仅提供私有路径或一个失效的上游下载链接。
5. 每次构建自动将 GPL、原生许可快照、运行时依赖清单和 AAR/JAR 中的许可/NOTICE 汇入 APK 的 `assets/open-source-notices.txt`；设置 → 引擎与关于 → 开源许可与声明可离线阅读。它记录已解析依赖与包内告知，不能替代对应源码归档或完整版权/专利审查。
6. Mac 工具按 [独立说明](../apple-live/README.md)构建；公开发行的签名/公证流程另行配置，本机临时签名不代表已公证。

## 结果边界

报告分别注明主机测试、模拟器、真机和特定相册验收。不要用「系统可读」代替色彩逐值一致，也不要用单张样本结果保证所有图库/设备兼容。历史记录集中在 [reports/](reports/README.md)，设计草案集中在 [design/](design/README.md)。

## 外部密钥签名入口

版本 `1.0.0` / `23` 是首个公开发行版，已提供发行签名配置。通过 CI 的秘密变量或当前终端环境提供以下四项，不写进仓库或命令历史：

- `ULTRASLIM_KEYSTORE`：发行 keystore 的绝对路径。
- `ULTRASLIM_STORE_PASSWORD`：keystore 密码。
- `ULTRASLIM_KEY_ALIAS`：发行密钥别名。
- `ULTRASLIM_KEY_PASSWORD`：密钥密码。

四项全未设置时，普通 `assembleRelease` 仍生成 unsigned APK；只设置部分会明确失败。全部配置后运行：

```bash
scripts/build_release.sh
```

此脚本关闭 Gradle 配置缓存，构建并执行 Release lint、16 KB 对齐与签名验证，拒绝常见 Android Debug 证书，再在 APK 同目录生成 `SHA256SUMS` 和 `OPEN_SOURCE_NOTICES.txt`。它不会生成密钥、提交、推送或发布。正式密钥应由维护者稳定保管；覆盖升级必须使用同一发行证书。

签名机制参考 [Android 官方签名文档](https://developer.android.com/studio/publish/app-signing)。源代码归档、完整测试、真机验收仍按上文执行，脚本成功不等于所有发行条件已满足。
