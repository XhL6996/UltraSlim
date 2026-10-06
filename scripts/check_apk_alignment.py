#!/usr/bin/env python3
"""Fail builds with non-16-KB ELF LOAD/RELRO or unaligned uncompressed JNI entries."""
import struct
import sys
import zipfile
from pathlib import Path

PAGE = 16384

def verify(apk: Path):
    count = 0
    with zipfile.ZipFile(apk) as archive, apk.open('rb') as source:
        for entry in archive.infolist():
            if not entry.filename.startswith('lib/') or not entry.filename.endswith('.so'):
                continue
            name = entry.filename
            assert name.startswith('lib/arm64-v8a/'), f'{name}: unsupported ABI packaged'
            data = archive.read(entry)
            assert data[:6] == b'\x7fELF\x02\x01', f'{name}: expected little-endian ELF64'
            phoff = struct.unpack_from('<Q', data, 32)[0]
            entsize, phnum = struct.unpack_from('<HH', data, 54)
            loads = relros = 0
            for i in range(phnum):
                kind, flags, offset, address, _, filesz, memsz, alignment = struct.unpack_from('<IIQQQQQQ', data, phoff + i * entsize)
                if kind == 1:
                    loads += 1
                    assert alignment >= PAGE and (address - offset) % PAGE == 0, f'{name}: LOAD is not 16 KB aligned'
                if kind == 0x6474e552:
                    relros += 1
                    assert (address + memsz) % PAGE == 0, f'{name}: RELRO end is not 16 KB aligned'
            assert loads and relros, f'{name}: missing LOAD/RELRO'
            if entry.compress_type == zipfile.ZIP_STORED:
                source.seek(entry.header_offset)
                header = source.read(30)
                name_len, extra_len = struct.unpack_from('<HH', header, 26)
                assert (entry.header_offset + 30 + name_len + extra_len) % PAGE == 0, f'{name}: APK entry is not 16 KB aligned'
            count += 1
            print(f'PASS {name}: LOAD, RELRO and APK alignment')
    assert count, 'APK contains no native libraries'
    print(f'PASS {apk.name}: {count} libraries')

if __name__ == '__main__':
    if len(sys.argv) < 2:
        raise SystemExit('usage: check_apk_alignment.py <apk> [<apk> ...]')
    for name in sys.argv[1:]:
        verify(Path(name))
