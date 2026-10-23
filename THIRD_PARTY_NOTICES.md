# 第三方来源与许可

UltraSlim 原创代码使用 [GPL-3.0-only](LICENSE)。本文件记录当前源码及主要原生依赖的来源，第三方版权和许可仍按其原文保留。原生库通过 `native/CMakeLists.txt` 静态链接；下载、版本/散列和本地补丁以 [依赖构建配置](native/deps/CMakeLists.txt)为准。

## 内置源码与素材

| 组件 | 固定来源 | 许可文本与本地变更 |
| --- | --- | --- |
| libultrahdr | Google v2.0.2 / `e5f5a022fe96fc4dc2ee35c19f733a50df807abe` | [MIT / Apache-2.0 原文](third_party/libultrahdr/LICENSE)；[补丁与来源](third_party/libultrahdr/ULTRASLIM_UPSTREAM.md) |
| AndroidX graphics-path JNI | 1.0.1 / `8a05a22af450d589ef911d772a001a49dcb05b71` | [Apache-2.0](third_party/androidx-graphics-path/LICENSE.txt)，源文件内的 Skia 等版权头保留；[来源](third_party/androidx-graphics-path/README.md) |
| libultrahdr 内置 image_io / modp_b64 | 随上述固定快照 | [image_io](third_party/libultrahdr/third_party/image_io/LICENSE)、[modp_b64](third_party/libultrahdr/third_party/image_io/src/modp_b64/LICENSE) |
| libultrahdr 测试图片（2 张） | 同一 v2.0.2 提交，未修改 | [CC-BY-4.0](tests/fixtures/libultrahdr/LICENSE)，[署名、来源和散列](tests/fixtures/libultrahdr/README.md) |

libultrahdr 自带的 [Adobe HDR Gain Map 声明](third_party/libultrahdr/adobe-hdr-gain-map-license/NOTICE)也予以保留。

## 构建时获取的原生依赖

下表许可证快照来自当前固定版本的构建源码，逐文件散列和原始路径见 [manifest.json](third_party/licenses/manifest.json)。保留这些文本不能替代对应源码；发行准备见 [RELEASING](docs/RELEASING.md)。

| 依赖 | 版本 / 用途 | 主要许可证及本地副本 |
| --- | --- | --- |
| [libjpeg-turbo](https://github.com/libjpeg-turbo/libjpeg-turbo) | 3.1.1 / JPEG | IJG、BSD 风格许可，[汇总](third_party/licenses/libjpeg-turbo/LICENSE.md)、[IJG](third_party/licenses/libjpeg-turbo/README.ijg) |
| [x265](https://bitbucket.org/multicoreware/x265_git) | 4.1 / HEVC 编码 | GPL-2.0-or-later（源码头）；[COPYING](third_party/licenses/x265/COPYING) |
| [libaom](https://aomedia.googlesource.com/aom/) | 3.12.1 / AV1 无损及特殊尺寸编码 | [BSD-2-Clause](third_party/licenses/aom/LICENSE)、[PATENTS](third_party/licenses/aom/PATENTS) |
| [SVT-AV1](https://gitlab.com/AOMediaCodec/SVT-AV1) | 3.0.0 / 通常尺寸 AV1 主图编码 | [BSD-3-Clause-Clear](third_party/licenses/svt-av1/LICENSE.md)、[BSD-2-Clause 部分](third_party/licenses/svt-av1/LICENSE-BSD2.md)、[PATENTS](third_party/licenses/svt-av1/PATENTS.md) |
| [dav1d](https://code.videolan.org/videolan/dav1d) | 1.5.1 / AV1 解码 | [BSD-2-Clause](third_party/licenses/dav1d/COPYING)、[PATENTS](third_party/licenses/dav1d/doc/PATENTS) |
| [libde265](https://github.com/strukturag/libde265) | 1.0.16 / HEVC 解码 | LGPL-3.0-or-later，[COPYING](third_party/licenses/libde265/COPYING) |
| [libheif](https://github.com/strukturag/libheif) | `4a3f74bc593ebfc29becc1ed5dd0a61cc66d40e1` / HEIF 容器 | LGPL-3.0-or-later（库）；封装/例程另有 MIT 条款，[COPYING](third_party/licenses/libheif/COPYING) |
| [libjxl](https://github.com/libjxl/libjxl) | 0.11.1 / JPEG XL | [BSD-3-Clause](third_party/licenses/libjxl/LICENSE)、[PATENTS](third_party/licenses/libjxl/PATENTS) |
| Brotli / Highway / skcms | libjxl 固定子模块 / 压缩、SIMD、颜色管理 | [Brotli MIT](third_party/licenses/libjxl/third_party/brotli/LICENSE)、[Highway](third_party/licenses/libjxl/third_party/highway/LICENSE)及 [BSD3 文本](third_party/licenses/libjxl/third_party/highway/LICENSE-BSD3)、[skcms BSD3](third_party/licenses/libjxl/third_party/skcms/LICENSE) |

x265 官方提供 GPL 与商业授权选择；本仓库按开源源码构建，不假设持有商业授权，参见 [x265 官方许可说明](https://x265.readthedocs.io/en/master/introduction.html#licensing)。软件版权许可与编解码专利授权并非同一事项，应以各组件原文为准。

## Android、构建工具与传递依赖

AndroidX/Compose/Room/WorkManager/DataStore、Kotlin/coroutines、Coil 等的直接版本见 [版本目录](gradle/libs.versions.toml) 和 [应用依赖](app/build.gradle.kts)。Gradle Wrapper 为其上游构建工具的一部分，保留原文件及版权头。

设备测试显式使用 AndroidX Test Espresso 3.7.0（Apache-2.0），修复 Android 17 上旧传递版本的输入注入反射失败。该依赖仅进入设备测试 APK，不进入应用运行时包；其固定版本见同一版本目录，许可条款同已有 [Apache-2.0 文本](third_party/androidx-graphics-path/LICENSE.txt)。Google Maven `espresso-core-3.7.0.aar` 的 SHA-256 为 `5dd90e366838bf044cb52eae06474debd285df18a7a77c40441ac8e8951bb00f`。

构建任务 `generateOpenSourceNotices` 按 Release 运行时解析结果生成依赖清单，提取 AAR/JAR 及嵌套 JAR 中的 LICENSE/NOTICE，连同本仓库 GPL 和原生许可文本打入 `assets/open-source-notices.txt`。应用设置中可离线阅读，发行脚本也会提取为独立附件。清单不是自动合规结论，发布时仍应提供准确对应源码；不能把整个 APK 简写成「所有依赖均为 GPLv3」。
