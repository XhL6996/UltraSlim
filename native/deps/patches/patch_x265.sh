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
t = re.sub(r"(?:x265::)*g_intraFilterFlags\[", "x265::g_intraFilterFlags[", t)
p.write_text(t)
EOF

# Enable PIC-safe AArch64 intrinsics independently of handwritten assembly.
python3 - "$SRC" <<'PY'
from pathlib import Path
import sys
src = Path(sys.argv[1]) / 'source'
p = src / 'common/CMakeLists.txt'
s = p.read_text()
marker = '# UltraSlim: standalone ARM intrinsic primitives'
if marker not in s:
    s += '''
# UltraSlim: standalone ARM intrinsic primitives
if(ULTRASLIM_ARM_INTRINSICS AND NOT ENABLE_ASSEMBLY AND
   (ARM64 OR CROSS_COMPILE_ARM64 OR CMAKE_SYSTEM_PROCESSOR MATCHES "^(arm64|aarch64)$"))
    target_compile_definitions(common PRIVATE ULTRASLIM_ARM_INTRINSICS=1 HAVE_NEON=1 X265_ARCH_ARM64=1)
    target_sources(common PRIVATE
        aarch64/ultraslim-intrinsics.cpp aarch64/pixel-prim.cpp
        aarch64/filter-prim.cpp aarch64/dct-prim.cpp aarch64/loopfilter-prim.cpp
        aarch64/intrapred-prim.cpp aarch64/arm64-utils.cpp aarch64/sao-prim.cpp)
endif()
'''
p.write_text(s)
p = src / 'common/primitives.cpp'
s = p.read_text()
if 'void setupUltraSlimIntrinsics' not in s:
    s = s.replace('void setupCPrimitives(EncoderPrimitives &p)',
        'void setupUltraSlimIntrinsics(EncoderPrimitives &p);\n\nvoid setupCPrimitives(EncoderPrimitives &p)')
    s = s.replace('        setupCPrimitives(primitives);',
        '        setupCPrimitives(primitives);\n#if ULTRASLIM_ARM_INTRINSICS\n        setupUltraSlimIntrinsics(primitives);\n#endif')
p.write_text(s)
p = src / 'common/aarch64/ultraslim-intrinsics.cpp'
p.write_text('''// Baseline NEON is mandatory on AArch64; no optional ISA or .S references.
#include "common.h"
#include "primitives.h"
namespace X265_NS {
void setupPixelPrimitives_neon(EncoderPrimitives &);
void setupFilterPrimitives_neon(EncoderPrimitives &);
void setupDCTPrimitives_neon(EncoderPrimitives &);
void setupLoopFilterPrimitives_neon(EncoderPrimitives &);
void setupIntraPrimitives_neon(EncoderPrimitives &);
void setupSaoPrimitives_neon(EncoderPrimitives &);
void setupUltraSlimIntrinsics(EncoderPrimitives &p) {
    setupPixelPrimitives_neon(p);
    setupFilterPrimitives_neon(p);
    setupDCTPrimitives_neon(p);
    setupLoopFilterPrimitives_neon(p);
    setupIntraPrimitives_neon(p);
    setupSaoPrimitives_neon(p);
}
}
''')
PY

python3 - "$SRC" <<'PY'
from pathlib import Path
import sys
root=Path(sys.argv[1])/'source/common/aarch64'
p=root/'dct-prim.cpp';s=p.read_text()
for op in ('dct','idct'):
    old=f'    p.cu[BLOCK_16x16].{op} = PFX({op}16_neon);'
    replacement=f'#if ULTRASLIM_ARM_INTRINSICS\n    p.cu[BLOCK_16x16].{op} = {op}16_neon;\n#else\n{old}\n#endif'
    if replacement not in s: s=s.replace(old,replacement)
p.write_text(s)
p=root/'intrapred-prim.cpp';s=p.read_text()
s=s.replace('#if !HIGH_BIT_DEPTH\n    p.cu[BLOCK_8x8].intra_pred[PLANAR_IDX]', '#if !HIGH_BIT_DEPTH && !ULTRASLIM_ARM_INTRINSICS\n    p.cu[BLOCK_8x8].intra_pred[PLANAR_IDX]')
p.write_text(s)
PY
