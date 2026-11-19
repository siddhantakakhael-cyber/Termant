#!/usr/bin/env python3
"""Draws the app icon (the same ">_" as the Android launcher) as a 1024 px PNG without any image library."""
import math, struct, sys, zlib

SIZE = 1024
BG = (13, 17, 23)
WHITE = (255, 255, 255)
ORANGE = (255, 122, 69)
S = SIZE / 108.0 * 1.45          # the Android drawing is 108 units wide with a wide margin
OX, OY = SIZE / 2 - 55 * S, SIZE / 2 - 54.5 * S
W = 6 * S / 2                    # half the stroke width
SEGMENTS = [((36, 40), (52, 54), WHITE), ((52, 54), (36, 68), WHITE), ((56, 69), (74, 69), ORANGE)]
SEGMENTS = [((OX + a[0] * S, OY + a[1] * S), (OX + b[0] * S, OY + b[1] * S), c) for a, b, c in SEGMENTS]


def distance(px, py, a, b):
    ax, ay = a
    bx, by = b
    dx, dy = bx - ax, by - ay
    t = max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)))
    return math.hypot(px - (ax + t * dx), py - (ay + t * dy))


rows = []
for y in range(SIZE):
    row = bytearray([0])
    for x in range(SIZE):
        colour = BG
        best = 0.0
        for a, b, c in SEGMENTS:
            lo_x, hi_x = min(a[0], b[0]) - W - 2, max(a[0], b[0]) + W + 2
            lo_y, hi_y = min(a[1], b[1]) - W - 2, max(a[1], b[1]) + W + 2
            if x < lo_x or x > hi_x or y < lo_y or y > hi_y:
                continue
            cover = max(0.0, min(1.0, W - distance(x + 0.5, y + 0.5, a, b) + 0.5))
            if cover > best:
                best = cover
                colour = tuple(int(BG[i] + (c[i] - BG[i]) * cover) for i in range(3))
        row += bytes(colour)
    rows.append(bytes(row))


def chunk(kind, data):
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)


png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", SIZE, SIZE, 8, 2, 0, 0, 0)) \
    + chunk(b"IDAT", zlib.compress(b"".join(rows), 9)) + chunk(b"IEND", b"")
open(sys.argv[1], "wb").write(png)
