import json, sys, os

PLAIN = {'left','right','center','centre','middle','top','bottom','upper','lower','edge','and','foreground','background','corner','side','far','of','the','in','at','on'}

def clean(s, limit):
    s = ''.join(' ' if ord(c) < 32 else c for c in s).strip(' ')
    return s[:limit]

def word(v, limit=80):
    if isinstance(v, str): return clean(v, limit)
    if isinstance(v, int) and not isinstance(v, bool): return str(v)
    return ''

def looks(o):
    d = o.get('description')
    if not isinstance(d, dict): return ''
    return ', '.join(x for x in (word(v) for v in d.values()) if x)

def cell(b):
    cx, cy = (b[0] + b[2]) * 3, (b[1] + b[3]) * 3
    col = 0 if cx < 2000 else 1 if cx < 4000 else 2
    row = 0 if cy < 2000 else 1 if cy < 4000 else 2
    return row, col

CELLS = [['top left', 'top', 'top right'], ['left', 'middle', 'right'], ['bottom left', 'bottom', 'bottom right']]

def place(b):
    row, col = cell(b)
    w, h = b[2] - b[0], b[3] - b[1]
    if w * h >= 600000: return 'all over'
    if w >= 800: return 'across the ' + ['top', 'middle', 'bottom'][row]
    if h >= 800: return 'down the ' + ['left', 'middle', 'right'][col]
    return CELLS[row][col]

def share(b):
    p = int((b[2] - b[0]) * (b[3] - b[1]) / 10000.0 + 0.5)
    return 'under 1%' if p < 1 else f'{p}%'

def point(b, W, H):
    return f'@{int((b[0] + b[2]) * W / 2000.0)},{int((b[1] + b[3]) * H / 2000.0)}'

def box(o):
    b = o.get('bbox')
    if not isinstance(b, list) or len(b) < 4: return None
    if not all(isinstance(x, (int, float)) and not isinstance(x, bool) for x in b[:4]): return None
    b = [float(x) for x in b[:4]]
    b = [min(1000.0, max(0.0, x)) for x in b]
    if b[2] <= b[0] or b[3] <= b[1]: return None
    return b

def sure(score): return int(score * 100 + 0.5)

def look(d, W, H, what, ms, device, tap):
    out = []
    out.append(f'{what} ({W}x{H}), seen by Mirage in {ms // 1000}.{ms % 1000 // 100} s.')
    out.append(f'Mirage is a small model on this {device}: the places are about right, but a name can be wrong even with a high score (say "it looks like"), it does not know who a person is, and it reads Latin letters only.')
    r = d.get('reasoning')
    if isinstance(r, str):
        parts = r.split(' | ')
        if len(parts) >= 2 and parts[0].startswith('lang:') and clean(parts[1], 300):
            out.append('In short: ' + clean(parts[1], 300))
    scene = d.get('scene')
    if isinstance(scene, dict):
        s = '; '.join(f"{clean(k, 40).replace('_', ' ')} = {word(v)}" for k, v in scene.items() if word(v))
        if s: out.append('Scene: ' + s)
    names = {}
    things = []
    for o in d.get('objects') or []:
        if not isinstance(o, dict): continue
        name = word(o.get('name')); b = box(o)
        if not name or b is None: continue
        if o.get('id') is not None: names.setdefault(str(o.get('id')), name)
        score = o.get('score'); score = float(score) if isinstance(score, (int, float)) else 0.0
        same = next((t for t in things if t['box'] == b), None)
        if same:
            if name not in same['name'].split(' or '): same['name'] += ' or ' + name
            continue
        pos = word(o.get('position')); words = pos.lower().split()
        if all(w in PLAIN for w in words):
            pos = 'foreground' if 'foreground' in words else 'background' if 'background' in words else ''
        things.append({'name': name, 'score': score, 'box': b, 'pos': pos, 'looks': looks(o), 'at': len(things)})
    groups = {}
    for t in things: groups.setdefault(t['name'], []).append(t)
    lines = []
    for name, g in groups.items():
        g.sort(key=lambda t: (-t['score'], t['at']))
        if len(g) < 4:
            for t in g:
                extra = ''.join('; ' + x for x in (t['pos'], t['looks']) if x)
                lines.append((t['score'], t['at'], f"- {name}, {sure(t['score'])}%: {place(t['box'])}, {share(t['box'])} of the picture{extra} {point(t['box'], W, H)}"))
        else:
            count = {}
            for t in g:
                r_, c_ = cell(t['box']); count[r_ * 3 + c_] = count.get(r_ * 3 + c_, 0) + 1
            where = ', '.join(f"{count[i]} {CELLS[i // 3][i % 3]}" for i in sorted(count, key=lambda i: (-count[i], i)))
            lines.append((g[0]['score'], g[0]['at'], f"- {name} x{len(g)}, {sure(g[-1]['score'])}-{sure(g[0]['score'])}%: {where}"))
    lines.sort(key=lambda l: (-l[0], l[1]))
    if lines:
        out.append(f'Things ({len(things)}), highest score first. Each line: name, score, where its middle is, how much of the picture it covers, how it looks, and its middle point{tap}:')
        out += [l[2] for l in lines[:30]]
        if len(lines) > 30: out.append(f'({len(lines) - 30} more left out)')
    also = []
    for o in d.get('regions') or []:
        if not isinstance(o, dict): continue
        name = word(o.get('name'))
        if not name: continue
        if o.get('id') is not None: names.setdefault(str(o.get('id')), name)
        if name in groups: continue
        extra = '; '.join(x for x in (word(o.get('count')), word(o.get('position')), looks(o)) if x)
        also.append(name + (f' ({extra})' if extra else ''))
    if also: out.append('Also there, place not marked: ' + ', '.join(also[:12]))
    texts = []
    for t in d.get('text') or []:
        if not isinstance(t, dict): continue
        s = word(t.get('string'), 120); b = box(t)
        score = t.get('score'); score = float(score) if isinstance(score, (int, float)) else 0.0
        if not s or b is None or (len(s) < 2 and score < 0.5): continue
        r_, c_ = cell(b)
        texts.append(f'- "{s}": {CELLS[r_][c_]} {point(b, W, H)}')
    if texts:
        out.append('Writing (letters can be wrong):')
        out += texts[:40]
    maybe = []
    def say(a, verb, b):
        a = names.get(str(a)); verb = word(verb).replace('_', ' ')
        if not a or not verb: return
        s = a + ' ' + verb
        if b is not None:
            b = names.get(str(b))
            if not b: return
            s += ' ' + b
        if s not in maybe: maybe.append(s)
    for e in d.get('events') or []:
        if isinstance(e, dict): say(e.get('actor'), e.get('action'), e.get('target'))
    for e in d.get('relations') or []:
        if isinstance(e, dict) and e.get('object') is not None: say(e.get('subject'), e.get('predicate'), e.get('object'))
    if maybe: out.append('Maybe (this part is often wrong): ' + '; '.join(maybe[:8]))
    if not lines and not also and not texts: out.append('Nothing was recognised in it.')
    return '\n'.join(out) + '\n'

if __name__ == '__main__':
    d = json.load(open(sys.argv[1], encoding='utf-8'))
    sys.stdout.write(look(d, int(sys.argv[2]), int(sys.argv[3]), sys.argv[4], int(sys.argv[5]), sys.argv[6], sys.argv[7] if len(sys.argv) > 7 else ''))
