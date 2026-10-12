# Native core

- `jni/transcode.cpp`：平台无关 probe/转码入口；`transcode.h` 是主机与 JNI 共用接口。
- `jni/ultraslim_jni.cpp`：文件描述符、Kotlin 结果与 native 之间的桥接。
- `jni/image_metadata.h`：ICC、EXIF 等元数据处理。
- `host_test/`：主机回归及增益图、色彩诊断程序。
- `deps/CMakeLists.txt` / `deps/build_deps.sh`：依赖版本、获取和构建。
- `deps/patches/`：必须随源码分发的本地依赖补丁。

`prefix/` 和 `deps/build/` 为被忽略的本地生成目录，不能替代依赖源码。主机测试读取仓库里的 `tests/fixtures/libultrahdr/`。工具准备与命令见 [构建文档](../docs/BUILDING.md)，格式约束见 [兼容性说明](../docs/COMPATIBILITY.md)。
