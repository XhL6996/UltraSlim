#!/bin/bash
# SVT 3.0.0 uses a process-global first-picture flag in AVIF mode and rejects EOS.
# Use its existing per-encoder frame_received flag; an EOS buffer is not a picture.
set -euo pipefail
python3 - "$1/Source/Lib/Globals/enc_handle.c" <<'PY'
from pathlib import Path
import sys
p=Path(sys.argv[1]); s=p.read_text()
s=s.replace('    enc_handle_ptr->frame_received = true;\n\n    static bool is_first_picture_sent = 0;', '    // UltraSlim: frame_received is per encoder, unlike a static local flag.')
s=s.replace('static_config.avif && is_first_picture_sent', 'static_config.avif && enc_handle_ptr->frame_received && !(p_buffer->flags & EB_BUFFERFLAG_EOS)')
s=s.replace('    is_first_picture_sent = 1;', '    if (!(p_buffer->flags & EB_BUFFERFLAG_EOS)) enc_handle_ptr->frame_received = true;')
if p.read_text() != s: p.write_text(s)
PY
