import json, os, subprocess, sys, time
from PIL import Image, ImageDraw, ImageOps
out = sys.argv[1]
OPTIONS = {
    'default': '{}',
    'boxes': '{"fields":["bbox"]}',
    'more': '{"fields":["bbox"],"conf":0.2,"max_objects":64}',
}
def ask(im, options):
    data = f'{im.width} {im.height}\n'.encode() + im.tobytes()
    t = time.time()
    r = subprocess.run(['work/reader', 'work/mirage.cact', options], input=data, capture_output=True)
    return r.stdout.decode('utf-8', 'replace').strip(), r.stderr.decode('utf-8', 'replace').strip(), time.time() - t
log = open(f'{out}/photos.log', 'w')
def say(*a):
    print(*a); print(*a, file=log); log.flush()
for name in sorted(os.listdir('photos')):
    try:
        im = ImageOps.exif_transpose(Image.open(f'photos/{name}')).convert('RGB')
    except Exception as e:
        say('cannot open', name, e); continue
    say(f'===== {name} {im.width}x{im.height}')
    for key, options in OPTIONS.items():
        answer, err, s = ask(im, options)
        say(f'--- {key} {options} : {s:.2f} s, {len(answer)} chars {err}')
        try: d = json.loads(answer)
        except Exception as e: say('  not JSON:', answer[:300]); continue
        open(f'{out}/{name}.{key}.json', 'w').write(answer)
        for k, v in d.items():
            if k == 'objects':
                for o in v:
                    rest = {a: b for a, b in o.items() if a not in ('mask', 'name', 'bbox', 'score', 'id')}
                    say(f"  object {o.get('id')} {o.get('name')!r} {o.get('score')} box {o.get('bbox')} {json.dumps(rest, ensure_ascii=False)}")
            elif k == 'text':
                say('  text: ' + ' · '.join(f"{t['string']} ({t['score']})" for t in v))
            else:
                say(f'  {k}: {json.dumps(v, ensure_ascii=False)[:1500]}')
        if key == 'boxes':
            small = im.copy(); small.thumbnail((900, 900))
            draw = ImageDraw.Draw(small)
            for o in d.get('objects', []):
                b = o['bbox']; box = [b[0] * small.width / 1000, b[1] * small.height / 1000, b[2] * small.width / 1000, b[3] * small.height / 1000]
                draw.rectangle(box, outline=(255, 40, 40), width=3)
                draw.text((box[0] + 4, box[1] + 3), f"{o.get('id')} {o.get('name')} {o.get('score')}", fill=(255, 255, 0), stroke_width=2, stroke_fill=(0, 0, 0))
            small.save(f'{out}/{name}.boxes.jpg', quality=80)
