#!/usr/bin/env python3
"""Prints "light" or "dark" for a spot of a screenshot (a PNG as screencap writes it).

    pixel.py shot.png X% Y% [X% Y% ...]

The answer is what most of the given spots are. No image library needed.
"""
import struct
import sys
import zlib


def rows(path):
    data = open(path, "rb").read()
    assert data[:8] == b"\x89PNG\r\n\x1a\n", "not a PNG"
    pos, packed = 8, b""
    width = height = channels = 0
    while pos < len(data):
        size, kind = struct.unpack(">I4s", data[pos:pos + 8])
        body = data[pos + 8:pos + 8 + size]
        pos += 12 + size
        if kind == b"IHDR":
            width, height, depth, colour, _, _, interlace = struct.unpack(">IIBBBBB", body)
            assert depth == 8 and interlace == 0 and colour in (2, 6), "unexpected PNG kind"
            channels = 4 if colour == 6 else 3
        elif kind == b"IDAT":
            packed += body
    raw = zlib.decompress(packed)
    stride = width * channels
    before = bytearray(stride)
    out = []
    for y in range(height):
        start = y * (stride + 1)
        kind = raw[start]
        line = bytearray(raw[start + 1:start + 1 + stride])
        if kind == 1:
            for i in range(channels, stride):
                line[i] = (line[i] + line[i - channels]) & 255
        elif kind == 2:
            for i in range(stride):
                line[i] = (line[i] + before[i]) & 255
        elif kind == 3:
            for i in range(stride):
                left = line[i - channels] if i >= channels else 0
                line[i] = (line[i] + ((left + before[i]) >> 1)) & 255
        elif kind == 4:
            for i in range(stride):
                a = line[i - channels] if i >= channels else 0
                b = before[i]
                c = before[i - channels] if i >= channels else 0
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                line[i] = (line[i] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 255
        out.append(line)
        before = line
    return width, height, channels, out


def main():
    width, height, channels, lines = rows(sys.argv[1])
    spots = [float(v) for v in sys.argv[2:]]
    light = 0
    for x, y in zip(spots[0::2], spots[1::2]):
        px = min(width - 1, int(width * x / 100))
        py = min(height - 1, int(height * y / 100))
        r, g, b = lines[py][px * channels:px * channels + 3]
        print("%d%%,%d%%: %d %d %d" % (x, y, r, g, b), file=sys.stderr)
        light += (r + g + b) > 480
    print("light" if light * 2 > len(spots) // 2 else "dark")


if __name__ == "__main__":
    main()
