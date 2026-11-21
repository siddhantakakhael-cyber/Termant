#!/usr/bin/env python3
"""Termant's icons: drawn here, written out as Android vector drawables.

    python3 android/tools/icons.py                 writes android/res/drawable/ic_*.xml
    python3 android/tools/icons.py --preview x.png  also draws them all into one picture (needs Pillow)
    python3 android/tools/icons.py --js windows/ui/icons.js   also writes them for the Windows app

Every icon lives on a 24 x 24 grid and is a set of strokes (round ends, round
corners) plus, for a few, filled shapes. The paths are written as in SVG; the
files get only M, L, C and Z with plain numbers, so every Android version reads
them the same way.
"""
import math
import os
import re
import sys

STROKE = 1.9


def gear():
    """A cog wheel: eight teeth around a ring, and the hole in the middle."""
    teeth, outer, inner = 8, 9.3, 7.0
    points = []
    for i in range(teeth):
        mid = 2 * math.pi * i / teeth - math.pi / 2
        half = math.pi / teeth
        for radius, angle in ((inner, mid - half * 0.62), (outer, mid - half * 0.36),
                              (outer, mid + half * 0.36), (inner, mid + half * 0.62)):
            points.append((12 + radius * math.cos(angle), 12 + radius * math.sin(angle)))
    path = 'M' + ' L'.join('%.2f %.2f' % p for p in points) + ' Z'
    return [path, circle(12, 12, 3)]


def circle(cx, cy, r):
    return 'M%g %g a%g %g 0 1 0 %g 0 a%g %g 0 1 0 %g 0 Z' % (cx - r, cy, r, r, 2 * r, r, r, -2 * r)


def box(x, y, w, h, r):
    return ('M%g %g h%g a%g %g 0 0 1 %g %g v%g a%g %g 0 0 1 %g %g h%g a%g %g 0 0 1 %g %g v%g a%g %g 0 0 1 %g %g Z'
            % (x + r, y, w - 2 * r, r, r, r, r, h - 2 * r, r, r, -r, r, -(w - 2 * r), r, r, -r, -r,
               -(h - 2 * r), r, r, r, -r))


# name: (strokes, fills)
ICONS = {
    # the bars at the top
    'menu': (['M4 8 h16', 'M4 16 h10'], []),
    'compose': (['M11 5 H7 a2 2 0 0 0 -2 2 v10 a2 2 0 0 0 2 2 h10 a2 2 0 0 0 2 -2 v-4',
                 'M17.2 3.8 l3 3 L12.5 14.5 l-3.6 0.6 l0.6 -3.6 Z'], []),
    'terminal': (['M5 8 l4 4 l-4 4', 'M12 16 h7'], []),
    'settings': (gear(), []),
    'back': (['M19 12 H5', 'M11 6 l-6 6 l6 6'], []),
    'close': (['M6 6 l12 12', 'M18 6 L6 18'], []),
    'plus': (['M12 5 v14', 'M5 12 h14'], []),
    'more': ([], [circle(12, 5, 1.4), circle(12, 12, 1.4), circle(12, 19, 1.4)]),
    'chevron_right': (['M9 6 l6 6 l-6 6'], []),
    'chevron_down': (['M6 9 l6 6 l6 -6'], []),
    'external': (['M10 6 H7 a2 2 0 0 0 -2 2 v9 a2 2 0 0 0 2 2 h9 a2 2 0 0 0 2 -2 v-3', 'M14 4 h6 v6', 'M20 4 l-8.5 8.5'], []),
    'check': (['M5 12.5 l4.5 4.5 L19 7.5'], []),

    # the message box
    'mic': (['M12 3 a3 3 0 0 0 -3 3 v5 a3 3 0 0 0 6 0 V6 a3 3 0 0 0 -3 -3 Z', 'M6 11 a6 6 0 0 0 12 0', 'M12 17 v4'], []),
    'arrow_up': (['M12 19 V5', 'M6 11 l6 -6 l6 6'], []),
    'stop': ([], [box(6.5, 6.5, 11, 11, 2.2)]),
    'folder': (['M3 7 a2 2 0 0 1 2 -2 h4 l2 2.5 h8 a2 2 0 0 1 2 2 V17 a2 2 0 0 1 -2 2 H5 a2 2 0 0 1 -2 -2 Z'], []),
    'sparkle': ([], ['M12 2.5 Q13.2 10.8 21.5 12 Q13.2 13.2 12 21.5 Q10.8 13.2 2.5 12 Q10.8 10.8 12 2.5 Z']),

    # chats and what the agent does
    'chat': (['M5 5 h14 a2 2 0 0 1 2 2 v8 a2 2 0 0 1 -2 2 h-8 l-5 4 v-4 H5 a2 2 0 0 1 -2 -2 V7 a2 2 0 0 1 2 -2 Z'], []),
    'clock': ([circle(12, 12, 9), 'M12 7 v5 l3 2'], []),
    'trash': (['M4 7 h16', 'M9 7 V5 a1 1 0 0 1 1 -1 h4 a1 1 0 0 1 1 1 v2',
               'M6 7 l0.9 12.2 a2 2 0 0 0 2 1.8 h6.2 a2 2 0 0 0 2 -1.8 L18 7', 'M10 11 v6', 'M14 11 v6'], []),
    'refresh': (['M19.4 9 A8 8 0 1 0 20 12', 'M20 4 v5 h-5'], []),
    'heart': (['M12 20 C7 16.5 4.5 13.6 4.5 10 a4 4 0 0 1 7.5 -2 a4 4 0 0 1 7.5 2 c0 3.6 -2.5 6.5 -7.5 10 Z'], []),
    'bolt': (['M13 3 L5 14 h6 l-1 7 l8 -11 h-6 Z'], []),
    'cloud': (['M7 18 a4 4 0 0 1 -0.5 -8 A6 6 0 0 1 18 11 a3.5 3.5 0 0 1 -0.5 7 Z'], []),
    'bulb': (['M9 17 h6', 'M10 20.5 h4', 'M9 17 c0 -2 -3 -3.2 -3 -7 a6 6 0 0 1 12 0 c0 3.8 -3 5 -3 7'], []),
    'edit': (['M4 20 l1 -4 L16 5 l3 3 L8 19 Z', 'M13.5 7.5 l3 3'], []),
    'box': (['M12 3 l8 4.5 v9 L12 21 l-8 -4.5 v-9 Z', 'M4 7.5 l8 4.5 l8 -4.5', 'M12 12 v9'], []),
    'phone': ([box(6, 3, 12, 18, 2.2), 'M11 17.5 h2'], []),
    'layers': (['M12 4 l9 5 l-9 5 l-9 -5 Z', 'M3 14 l9 5 l9 -5'], []),
    'branch': ([circle(7, 5.5, 2), circle(7, 18.5, 2), circle(17, 7.5, 2), 'M7 7.5 v9', 'M17 9.5 a5 5 0 0 1 -5 5 H7'], []),
    'file': (['M7 3 h7 l4 4 v12 a2 2 0 0 1 -2 2 H7 a2 2 0 0 1 -2 -2 V5 a2 2 0 0 1 2 -2 Z', 'M14 3 v4 h4'], []),
    'search': ([circle(11, 11, 6), 'M20 20 l-4.6 -4.6'], []),
    'globe': ([circle(12, 12, 9), 'M3 12 h18', 'M12 3 c3.5 3 3.5 15 0 18 c-3.5 -3 -3.5 -15 0 -18 Z'], []),
    'list': (['M9 7 h11', 'M9 12 h11', 'M9 17 h11'], [circle(4.8, 7, 1.2), circle(4.8, 12, 1.2), circle(4.8, 17, 1.2)]),
    'plug': (['M9 3 v5', 'M15 3 v5', 'M6 8 h12 v3 a6 6 0 0 1 -12 0 Z', 'M12 17 v4'], []),
    'eye': (['M2.5 12 C5 7.5 8.2 5.5 12 5.5 s7 2 9.5 6.5 c-2.5 4.5 -5.7 6.5 -9.5 6.5 s-7 -2 -9.5 -6.5 Z', circle(12, 12, 2.6)], []),
    'help': ([circle(12, 12, 9), 'M9.6 9.6 a2.5 2.5 0 1 1 3.6 2.2 c-0.8 0.4 -1.2 1 -1.2 1.9'], [circle(12, 17, 1.1)]),
    'download': (['M12 4 v11', 'M7 10 l5 5 l5 -5', 'M5 20 h14'], []),
    'alert': (['M12 4 l9 16 H3 Z', 'M12 10 v4'], [circle(12, 17, 1.1)]),

    # Settings and the ideas on the first screen
    'language': (['M4 6 h9', 'M8.5 4 v2', 'M11 6 c-0.8 3.5 -3 6 -6.5 7.5', 'M6 9 c1 2 3 3.6 5.5 4.5',
                  'M13 20 l4 -9 l4 9', 'M14.5 17 h5'], []),
    'theme': ([circle(12, 12, 9)], ['M12 3 a9 9 0 0 1 0 18 Z']),
    'key': ([circle(8, 15.5, 4), 'M10.9 12.6 L20 3.5', 'M16.5 7 l3 3'], []),
    'bell': (['M6 16 V11 a6 6 0 0 1 12 0 v5 l2 2 H4 Z', 'M10 21 h4'], []),
    'timer': ([circle(12, 13.5, 7), 'M12 13.5 V9.5', 'M10 3 h4'], []),
    'battery': ([box(3, 8, 15.5, 8, 1.6), 'M21 11 v2', 'M6.5 11 v2', 'M9.5 11 v2'], []),
    'code': (['M9 8 l-4 4 l4 4', 'M15 8 l4 4 l-4 4'], []),
    'message': (['M4 6 h16 a1 1 0 0 1 1 1 v10 a1 1 0 0 1 -1 1 H4 a1 1 0 0 1 -1 -1 V7 a1 1 0 0 1 1 -1 Z', 'M3.5 7 l8.5 6 l8.5 -6'], []),
    'info': ([circle(12, 12, 9), 'M12 11 v6'], [circle(12, 7.6, 1.1)]),
}

NUMBER = re.compile(r'[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?')


def arc(x0, y0, rx, ry, turn, large, sweep, x, y):
    """An SVG arc as cubic curves (the usual end point to centre conversion)."""
    if rx == 0 or ry == 0 or (x0 == x and y0 == y):
        return [(x0, y0, x, y, x, y)]
    phi = math.radians(turn)
    cos, sin = math.cos(phi), math.sin(phi)
    dx, dy = (x0 - x) / 2, (y0 - y) / 2
    x1, y1 = cos * dx + sin * dy, -sin * dx + cos * dy
    rx, ry = abs(rx), abs(ry)
    scale = x1 * x1 / (rx * rx) + y1 * y1 / (ry * ry)
    if scale > 1:
        rx, ry = rx * math.sqrt(scale), ry * math.sqrt(scale)
    top = rx * rx * ry * ry - rx * rx * y1 * y1 - ry * ry * x1 * x1
    factor = math.sqrt(max(0, top) / (rx * rx * y1 * y1 + ry * ry * x1 * x1))
    if large == sweep:
        factor = -factor
    cx1, cy1 = factor * rx * y1 / ry, -factor * ry * x1 / rx
    cx, cy = cos * cx1 - sin * cy1 + (x0 + x) / 2, sin * cx1 + cos * cy1 + (y0 + y) / 2

    def angle(ux, uy, vx, vy):
        a = math.atan2(ux * vy - uy * vx, ux * vx + uy * vy)
        return a

    start = angle(1, 0, (x1 - cx1) / rx, (y1 - cy1) / ry)
    delta = angle((x1 - cx1) / rx, (y1 - cy1) / ry, (-x1 - cx1) / rx, (-y1 - cy1) / ry)
    if not sweep and delta > 0:
        delta -= 2 * math.pi
    if sweep and delta < 0:
        delta += 2 * math.pi
    pieces = max(1, int(math.ceil(abs(delta) / (math.pi / 2) - 1e-9)))
    step = delta / pieces
    k = 4 / 3 * math.tan(step / 4)
    out = []
    for i in range(pieces):
        a, b = start + i * step, start + (i + 1) * step

        def at(t, px, py):
            return (cx + cos * rx * px - sin * ry * py, cy + sin * rx * px + cos * ry * py)

        p1 = at(a, math.cos(a) - k * math.sin(a), math.sin(a) + k * math.cos(a))
        p2 = at(b, math.cos(b) + k * math.sin(b), math.sin(b) - k * math.cos(b))
        p3 = at(b, math.cos(b), math.sin(b))
        out.append((p1[0], p1[1], p2[0], p2[1], p3[0], p3[1]))
    return out


def absolute(path):
    """An SVG path as a list of ('M', x, y), ('L', x, y), ('C', six numbers) and ('Z',)."""
    tokens = re.findall(r'[MmLlHhVvCcSsQqAaZz]|' + NUMBER.pattern, path)
    out, i = [], 0
    x = y = sx = sy = 0.0
    last_c = None  # the second control point of the cubic before, for S

    def take(n):
        nonlocal i
        values = [float(t) for t in tokens[i:i + n]]
        assert len(values) == n, 'numbers missing in: ' + path
        i += n
        return values

    command = None
    while i < len(tokens):
        if re.match(r'[A-Za-z]', tokens[i]):
            command = tokens[i]
            i += 1
        else:
            assert command and command not in 'Zz', 'a number without a command in: ' + path
            if command == 'M':
                command = 'L'
            elif command == 'm':
                command = 'l'
        rel = command.islower()
        c = command.upper()
        control = None
        if c == 'M':
            a, b = take(2)
            x, y = (x + a, y + b) if rel else (a, b)
            sx, sy = x, y
            out.append(('M', x, y))
        elif c == 'L':
            a, b = take(2)
            x, y = (x + a, y + b) if rel else (a, b)
            out.append(('L', x, y))
        elif c == 'H':
            a, = take(1)
            x = x + a if rel else a
            out.append(('L', x, y))
        elif c == 'V':
            a, = take(1)
            y = y + a if rel else a
            out.append(('L', x, y))
        elif c == 'C':
            v = take(6)
            if rel:
                v = [v[0] + x, v[1] + y, v[2] + x, v[3] + y, v[4] + x, v[5] + y]
            out.append(('C',) + tuple(v))
            control = (v[2], v[3])
            x, y = v[4], v[5]
        elif c == 'S':
            v = take(4)
            if rel:
                v = [v[0] + x, v[1] + y, v[2] + x, v[3] + y]
            first = (2 * x - last_c[0], 2 * y - last_c[1]) if last_c else (x, y)
            out.append(('C', first[0], first[1], v[0], v[1], v[2], v[3]))
            control = (v[0], v[1])
            x, y = v[2], v[3]
        elif c == 'Q':
            v = take(4)
            if rel:
                v = [v[0] + x, v[1] + y, v[2] + x, v[3] + y]
            out.append(('C', x + 2 / 3 * (v[0] - x), y + 2 / 3 * (v[1] - y),
                        v[2] + 2 / 3 * (v[0] - v[2]), v[3] + 2 / 3 * (v[1] - v[3]), v[2], v[3]))
            x, y = v[2], v[3]
        elif c == 'A':
            rx, ry, turn, large, sweep, a, b = take(7)
            ex, ey = (x + a, y + b) if rel else (a, b)
            for piece in arc(x, y, rx, ry, turn, bool(large), bool(sweep), ex, ey):
                out.append(('C',) + piece)
            x, y = ex, ey
        elif c == 'Z':
            out.append(('Z',))
            x, y = sx, sy
        last_c = control
    for segment in out:
        for value in segment[1:]:
            assert -0.5 <= value <= 24.5, 'outside the 24 x 24 grid (%.2f) in: %s' % (value, path)
    return out


def data(segments):
    return ' '.join(s[0] + ' '.join(('%.2f' % v).rstrip('0').rstrip('.') for v in s[1:]) for s in segments)


def xml(strokes, fills):
    lines = ['<?xml version="1.0" encoding="utf-8"?>',
             '<!-- Made by android/tools/icons.py; change the icon there. -->',
             '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
             '    android:width="24dp" android:height="24dp"',
             '    android:viewportWidth="24" android:viewportHeight="24">']
    for path in fills:
        lines.append('    <path android:pathData="%s"\n        android:fillColor="#ffffff" />' % data(absolute(path)))
    for path in strokes:
        lines.append('    <path android:pathData="%s"\n        android:strokeColor="#ffffff" android:strokeWidth="%s"\n'
                     '        android:strokeLineCap="round" android:strokeLineJoin="round" />' % (data(absolute(path)), STROKE))
    lines.append('</vector>')
    return '\n'.join(lines) + '\n'


def polylines(segments):
    """The path as lists of points, curves cut into short lines; closed ones return to their start."""
    lines, current = [], []
    x = y = 0.0
    for s in segments:
        if s[0] == 'M':
            if current:
                lines.append(current)
            current = [(s[1], s[2])]
            x, y = s[1], s[2]
        elif s[0] == 'L':
            current.append((s[1], s[2]))
            x, y = s[1], s[2]
        elif s[0] == 'C':
            for n in range(1, 17):
                t = n / 16
                u = 1 - t
                current.append((u ** 3 * x + 3 * u * u * t * s[1] + 3 * u * t * t * s[3] + t ** 3 * s[5],
                                u ** 3 * y + 3 * u * u * t * s[2] + 3 * u * t * t * s[4] + t ** 3 * s[6]))
            x, y = s[5], s[6]
        elif s[0] == 'Z' and current:
            current.append(current[0])
            x, y = current[0]
    if current:
        lines.append(current)
    return lines


def preview(target):
    from PIL import Image, ImageDraw
    names = sorted(ICONS)
    scale, cell, columns = 6, 24 * 6 + 60, 9
    rows = (len(names) + columns - 1) // columns
    sheet = Image.new('RGB', (columns * cell, rows * (cell + 26)), (14, 17, 22))
    draw = ImageDraw.Draw(sheet)
    for n, name in enumerate(names):
        ox, oy = (n % columns) * cell + 30, (n // columns) * (cell + 26) + 24
        strokes, fills = ICONS[name]
        draw.rectangle([ox, oy, ox + 24 * scale, oy + 24 * scale], outline=(38, 44, 54))
        for path in fills:
            for line in polylines(absolute(path)):
                draw.polygon([(ox + px * scale, oy + py * scale) for px, py in line], fill=(232, 236, 241))
        width = STROKE * scale
        for path in strokes:
            for line in polylines(absolute(path)):
                points = [(ox + px * scale, oy + py * scale) for px, py in line]
                draw.line(points, fill=(232, 236, 241), width=int(round(width)), joint='curve')
                for px, py in (points[0], points[-1]):
                    draw.ellipse([px - width / 2, py - width / 2, px + width / 2, py + width / 2], fill=(232, 236, 241))
        draw.text((ox, oy + 24 * scale + 6), name, fill=(141, 150, 163))
    sheet.save(target)
    print('preview:', target)


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    folder = os.path.join(here, '..', 'res', 'drawable')
    for name, (strokes, fills) in sorted(ICONS.items()):
        with open(os.path.join(folder, 'ic_%s.xml' % name), 'w') as f:
            f.write(xml(strokes, fills))
    print('%d icons written to android/res/drawable' % len(ICONS))
    if '--preview' in sys.argv:
        preview(sys.argv[sys.argv.index('--preview') + 1])
    if '--js' in sys.argv:
        # the same icons for the Windows app, as SVG paths in a script
        import json
        target = sys.argv[sys.argv.index('--js') + 1]
        table = {name: {'s': strokes, 'f': fills} for name, (strokes, fills) in sorted(ICONS.items())}
        with open(target, 'w') as f:
            f.write('// Made by android/tools/icons.py --js; change the icons there.\n')
            f.write('window.ICONS = ' + json.dumps(table, indent=1) + ';\n')
        print('icons for the Windows app:', target)


if __name__ == '__main__':
    main()
