"""Verify 16 KB load segment alignment in an ELF64 library, APK, or Play bundle."""

import struct
import sys
import zipfile
from pathlib import Path


def verify(name, stream):
    header = stream.read(64)
    if len(header) != 64 or header[:6] != b"\x7fELF\x02\x01":
        raise ValueError(f"{name}: expected a little-endian 64-bit ELF library")
    table_offset = struct.unpack_from("<Q", header, 32)[0]
    entry_size, entries = struct.unpack_from("<HH", header, 54)
    if entry_size < 56:
        raise ValueError(f"{name}: invalid program header size")
    stream.seek(table_offset)
    loads = 0
    for _ in range(entries):
        kind, _, offset, address, _, _, _, alignment = struct.unpack_from("<IIQQQQQQ", stream.read(entry_size))
        if kind != 1:  # PT_LOAD
            continue
        loads += 1
        if alignment < 16384 or (address - offset) % 16384 != 0:
            raise ValueError(f"{name}: LOAD segment at {offset:#x} is incompatible with 16 KB pages (alignment {alignment:#x})")
    if not loads:
        raise ValueError(f"{name}: no LOAD segments")
    print(f"{name}: {loads} compatible LOAD segments")


def check(path):
    if path.suffix in (".apk", ".aab"):
        with zipfile.ZipFile(path) as apk:
            prefix = "base/lib/" if path.suffix == ".aab" else "lib/"
            libraries = [name for name in apk.namelist() if name.startswith(prefix) and name.endswith(".so")]
            if not libraries:
                raise ValueError(f"{path}: no packaged native libraries")
            for name in libraries:
                with apk.open(name) as stream:
                    verify(name, stream)
    else:
        with path.open("rb") as stream:
            verify(path.name, stream)


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit("Usage: verify-native-pages.py <library.so, app.apk, or app.aab>")
    try:
        check(Path(sys.argv[1]))
    except (OSError, ValueError, struct.error, zipfile.BadZipFile) as error:
        sys.exit(str(error))
