#!/bin/bash
# Generates a Meson cross file for dav1d Android builds.
# usage: gen_meson_cross.sh <ndk_path> <api_level> <out_file>
set -euo pipefail
NDK="$1"
API="$2"
OUT="$3"
TC="$NDK/toolchains/llvm/prebuilt/$(uname -s | tr '[:upper:]' '[:lower:]')-x86_64"
cat > "$OUT" <<EOF
[binaries]
c = '$TC/bin/aarch64-linux-android$API-clang'
cpp = '$TC/bin/aarch64-linux-android$API-clang++'
ar = '$TC/bin/llvm-ar'
strip = '$TC/bin/llvm-strip'

[host_machine]
system = 'android'
cpu_family = 'aarch64'
cpu = 'aarch64'
endian = 'little'

[properties]
needs_exe_wrapper = true

[built-in options]
c_args = ['-fPIC']
cpp_args = ['-fPIC']
EOF
