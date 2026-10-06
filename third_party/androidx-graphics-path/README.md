# AndroidX graphics-path native sources

Upstream: https://android.googlesource.com/platform/frameworks/support/+/8a05a22af450d589ef911d772a001a49dcb05b71/graphics/graphics-path/src/main/cpp/

Pinned to the graphics-path 1.0.1 release revision (8a05a22af450d589ef911d772a001a49dcb05b71). Source files are unmodified; their Apache-2.0 / Skia notices are retained.

The Maven JNI binary has a 4 KB RELRO boundary even though its LOAD segments advertise 16 KB alignment. UltraSlim rebuilds this small native component with both page alignment flags. The original Maven Kotlin/API artifact remains at 1.0.1. Packaging selects the project-built JNI library; scripts/check_apk_alignment.py validates the resulting APK to prevent the old binary being selected silently.
