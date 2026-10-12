# 第三方代码

- `libultrahdr/`：固定 v2.0.2 源码快照和已记录的本地修改，不是子模块。上游来源与补丁见 [ULTRASLIM_UPSTREAM.md](libultrahdr/ULTRASLIM_UPSTREAM.md)。
- `androidx-graphics-path/`：为满足 APK 16 KB 对齐重建的 JNI 源码，版本须与 Kotlin/API 依赖保持一致。
- `licenses/`：构建时下载的主要原生依赖许可文本快照，不包含预编译库；`manifest.json` 记录来源版本、原始相对路径与 SHA-256。

项目所需两张公开测试图片单独存于 [tests/fixtures/libultrahdr](../tests/fixtures/libultrahdr/README.md)，不依赖上游目录内被 `.gitignore` 忽略的本机素材。

升级依赖时同步更新下载散列、本地补丁、许可文本和 manifest。保留原版权头，不把第三方代码改标为 UltraSlim 原创。完整清单见 [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md)。
