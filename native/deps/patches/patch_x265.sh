#!/bin/bash
# Patches the extracted x265 4.1 source tree.
#   $1 = source dir
#   $2 = extra flags to bake into the ARM64 .S custom compile commands
#        (forwarded --target/--sysroot/-D defines when cross-compiling; may be empty)
set -euo pipefail
SRC="$1"
ASM_FLAGS="${2:-}"

python3 - "$SRC" "$ASM_FLAGS" <<'EOF'
import re
import sys
from pathlib import Path

src = Path(sys.argv[1])
asm_flags = sys.argv[2].strip()

p = src / "source/CMakeLists.txt"
t = p.read_text()
t = re.sub(r"cmake_minimum_required \(VERSION 2\.8\.8\)",
           "cmake_minimum_required (VERSION 3.10)", t)
t = t.replace("    cmake_policy(SET CMP0025 OLD)",
              "    # cmake_policy(SET CMP0025 OLD)")
t = t.replace("    cmake_policy(SET CMP0054 OLD)",
              "    # cmake_policy(SET CMP0054 OLD)")
if asm_flags:
    t = t.replace("ARGS ${ARM_ARGS} ${ASM_FLAGS}",
                  "ARGS ${ARM_ARGS} ${ASM_FLAGS} " + asm_flags)
p.write_text(t)

p = src / "source/common/aarch64/intrapred-prim.cpp"
t = p.read_text()
t = t.replace("g_intraFilterFlags[", "x265::g_intraFilterFlags[")
p.write_text(t)
EOF
