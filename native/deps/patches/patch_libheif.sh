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
