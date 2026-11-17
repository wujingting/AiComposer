"""
Extract the embedded JPEG preview from a Sony ARW raw file, without any third-party library.

ARW is TIFF-based. Sony stores a full-width JPEG preview (about 1616×1080 on the a6000) that is
referenced from an IFD by the tags JPEGInterchangeFormat (0x0201) / JPEGInterchangeFormatLength (0x0202).
We walk every IFD (the IFD chain plus SubIFDs) and return the largest JPEG found, together with the
TIFF orientation tag so the caller can rotate the image upright.

For scoring this preview is plenty: the aesthetic model only looks at 224×224 pixels.
"""
from __future__ import annotations

import struct
from dataclasses import dataclass
from typing import BinaryIO, Optional

TAG_COMPRESSION = 0x0103
TAG_STRIP_OFFSETS = 0x0111
TAG_ORIENTATION = 0x0112
TAG_STRIP_BYTE_COUNTS = 0x0117
TAG_SUB_IFDS = 0x014A
TAG_JPEG_OFFSET = 0x0201
TAG_JPEG_LENGTH = 0x0202

# TIFF field type -> size in bytes
_TYPE_SIZE = {1: 1, 2: 1, 3: 2, 4: 4, 5: 8, 6: 1, 7: 1, 8: 2, 9: 4, 10: 8, 11: 4, 12: 8, 13: 4}

JPEG_SOI = b"\xff\xd8"


@dataclass
class Preview:
    jpeg: bytes
    orientation: int  # TIFF orientation tag value (1 = normal), 1 when absent


class TiffError(ValueError):
    pass


def _read(f: BinaryIO, offset: int, n: int) -> bytes:
    f.seek(offset)
    data = f.read(n)
    if len(data) != n:
        raise TiffError("unexpected end of file")
    return data


def _values(f: BinaryIO, endian: str, typ: int, count: int, raw_value: bytes) -> list[int]:
    """Integer values of an IFD entry (only the integer types we need)."""
    size = _TYPE_SIZE.get(typ)
    if size is None or typ not in (1, 3, 4, 13):
        return []
    total = size * count
    if total <= 4:
        data = raw_value[:total]
    else:
        (offset,) = struct.unpack(endian + "I", raw_value)
        data = _read(f, offset, total)
    fmt = {1: "B", 3: "H", 4: "I", 13: "I"}[typ]
    return list(struct.unpack(endian + fmt * count, data))


def _read_ifd(f: BinaryIO, endian: str, offset: int) -> tuple[dict[int, list[int]], int]:
    (n,) = struct.unpack(endian + "H", _read(f, offset, 2))
    if n > 1000:
        raise TiffError("implausible IFD size")
    entries = {}
    for i in range(n):
        tag, typ, count, raw_value = struct.unpack(endian + "HHI4s", _read(f, offset + 2 + 12 * i, 12))
        if count > 100_000:
            continue
        entries[tag] = _values(f, endian, typ, count, raw_value)
    (next_ifd,) = struct.unpack(endian + "I", _read(f, offset + 2 + 12 * n, 4))
    return entries, next_ifd


def extract_preview(path: str) -> Optional[Preview]:
    """Return the largest embedded JPEG preview of a TIFF-based raw file, or None if there is none."""
    with open(path, "rb") as f:
        head = _read(f, 0, 8)
        if head[:2] == b"II":
            endian = "<"
        elif head[:2] == b"MM":
            endian = ">"
        else:
            raise TiffError("not a TIFF-based file")
        (magic, first_ifd) = struct.unpack(endian + "HI", head[2:8])
        if magic != 42:
            raise TiffError("not a TIFF-based file")

        orientation = 1
        candidates: list[tuple[int, int]] = []  # (offset, length)
        todo = [first_ifd]
        seen = set()
        is_ifd0 = True
        while todo:
            off = todo.pop(0)
            if off == 0 or off in seen or len(seen) > 64:
                continue
            seen.add(off)
            try:
                entries, next_ifd = _read_ifd(f, endian, off)
            except (TiffError, struct.error):
                continue
            if is_ifd0 and entries.get(TAG_ORIENTATION):
                orientation = entries[TAG_ORIENTATION][0]
            is_ifd0 = False

            jo, jl = entries.get(TAG_JPEG_OFFSET), entries.get(TAG_JPEG_LENGTH)
            if jo and jl:
                candidates.append((jo[0], jl[0]))
            # Some files store the preview as a single JPEG-compressed strip
            so, sc = entries.get(TAG_STRIP_OFFSETS), entries.get(TAG_STRIP_BYTE_COUNTS)
            if so and sc and len(so) == 1 and entries.get(TAG_COMPRESSION, [0])[0] in (6, 7):
                candidates.append((so[0], sc[0]))

            todo.extend(entries.get(TAG_SUB_IFDS, []))
            todo.append(next_ifd)

        best = None
        for offset, length in sorted(candidates, key=lambda c: -c[1]):
            try:
                data = _read(f, offset, length)
            except TiffError:
                continue
            if data[:2] == JPEG_SOI:
                best = data
                break
        return Preview(best, orientation) if best else None
