#!/bin/bash
# Applies the ISO 21496-1 gain map patch (libheif PR 1503) to the extracted
# libheif tree ($1 = source dir, $2 = patch file). Idempotent.
set -euo pipefail
SRC="$1"
PATCH_FILE="$2"
cd "$SRC"
if patch -p1 -N --dry-run -i "$PATCH_FILE" >/dev/null 2>&1; then
  patch -p1 -N -i "$PATCH_FILE"
fi

python3 - "$SRC/libheif/plugins/encoder_svt.cc" <<'PYCODE'
from pathlib import Path
import sys
p=Path(sys.argv[1]); s=p.read_text()
s=s.replace('svt_config.level_of_parallelism = encoder->threads;', 'svt_config.level_of_parallelism = encoder->threads <= 2 ? 1 : encoder->threads <= 4 ? 2 : 3;\n  svt_config.avif = true;')
if p.read_text() != s: p.write_text(s)
PYCODE

# A tmap without COLR must not dereference a null Box_colr while probing.
python3 - "$SRC/libheif/context.cc" <<'PYCODE'
from pathlib import Path
import sys
p = Path(sys.argv[1]); s = p.read_text()
old = '          auto colr = std::dynamic_pointer_cast<Box_colr>(derived_image_colr);\n          auto nclx ='
new = '          auto colr = std::dynamic_pointer_cast<Box_colr>(derived_image_colr);\n          if (!colr) continue;  // No derived color profile is available.\n          auto nclx ='
s = s.replace(old, new)
if p.read_text() != s: p.write_text(s)
PYCODE
