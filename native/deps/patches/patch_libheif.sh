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

# Android AVIF decoders can replace an ICC profile with the following NCLX
# association, then fall back to sRGB for SDR P3. Keep both profiles, but put
# ICC last for AV1 items. This changes only property order, never pixel values.
python3 - "$SRC/libheif/image-items/image_item.cc" <<'PYCODE'
from pathlib import Path
import sys
p = Path(sys.argv[1]); s = p.read_text()
marker = '// UltraSlim: AVIF ICC must follow NCLX for Android color management.'
if marker not in s:
    start = s.index('void ImageItem::add_color_profile(')
    end = s.index('\nvoid ImageItem::CodedImageData::append(', start)
    body = s[start:end]
    block = '''    if (icc_profile) {
      auto colr = std::make_shared<Box_colr>();
      colr->set_color_profile(icc_profile);
      inout_codedImage.properties.push_back(colr);
    }'''
    assert body.count(block) == 1
    body = body.replace(block, '''    ''' + marker + '''
    bool icc_last = false;
    for (const auto& property : inout_codedImage.properties) {
      if (property->get_short_type() == fourcc("av1C")) icc_last = true;
    }
''' + block.replace('if (icc_profile)', 'if (icc_profile && !icc_last)'))
    tail = '    }\n  }\n}\n'
    assert body.count(tail) == 1
    body = body.replace(tail, '    }\n' + block.replace('if (icc_profile)', 'if (icc_profile && icc_last)') + '\n  }\n}\n')
    s = s[:start] + body + s[end:]
    p.write_text(s)
PYCODE

# Operation-scoped cache for an identical lossless gain map during a base-quality retry.
# The caller must reset at both boundaries and never change the map/codec/options inside.
python3 - "$SRC/libheif/image-items/image_item.cc" <<'PYCODE'
from pathlib import Path
import sys
p=Path(sys.argv[1]); s=p.read_text()
marker='// UltraSlim: identical gain map retry cache'
if marker not in s:
    anchor='Error ImageItem::encode_to_item('
    cache='''// UltraSlim: identical gain map retry cache
namespace {
thread_local bool ultraslim_map_cache_enabled = false;
thread_local std::shared_ptr<ImageItem::CodedImageData> ultraslim_map_cache;
}
extern "C" void ultraslim_heif_gainmap_retry_cache(int enabled) {
  ultraslim_map_cache.reset();
  ultraslim_map_cache_enabled = enabled != 0;
}

'''
    s=s.replace(anchor,cache+anchor)
    old='  Result<CodedImageData> codingResult = encode_to_bitstream_and_boxes(image, encoder, options, input_class);'
    new='''  const bool cache_map = ultraslim_map_cache_enabled && input_class == heif_image_input_class_gain_map;
  Result<CodedImageData> codingResult = cache_map && ultraslim_map_cache
      ? Result<CodedImageData>(*ultraslim_map_cache)
      : encode_to_bitstream_and_boxes(image, encoder, options, input_class);
  if (cache_map && !codingResult.error && !ultraslim_map_cache)
    ultraslim_map_cache = std::make_shared<CodedImageData>(codingResult.value);'''
    assert old in s
    s=s.replace(old,new)
    p.write_text(s)
PYCODE
