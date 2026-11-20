# Writes test pictures as request streams for the reader: <out>/<name>.req, and prints the names.
import sys
from PIL import Image
out = sys.argv[1]
def req(name, images):
    with open(f'{out}/{name}.req', 'wb') as f:
        for im in images:
            im = im.convert('RGB'); f.write(f'{im.width} {im.height}\n'.encode()); f.write(im.tobytes())
    print(name)
for shot in ['screen-control', 'question', 'chat', 'hindi-light']:
    im = Image.open(f'docs/screenshots/{shot}.png').convert('RGB')
    w, h = im.size
    req(f'{shot}-full', [im])
    req(f'{shot}-full-2x', [im.resize((w * 2, h * 2), Image.LANCZOS)])
    req(f'{shot}-halves', [im.crop((0, 0, w, 360)), im.crop((0, h - 360, w, h))])
    req(f'{shot}-thirds', [im.crop((0, 0, w, 256)), im.crop((0, 192, w, 448)), im.crop((0, h - 256, w, h))])
    big = im.resize((1080, 2160), Image.LANCZOS)   # as a 3.4x phone shows it
    req(f'{shot}-big-half', [big.crop((0, 0, 1080, 1215))])
    req(f'{shot}-big-half-down', [big.crop((0, 0, 1080, 1215)).resize((320, 360), Image.BILINEAR)])
    req(f'{shot}-wide-thirds', [im.crop((0, 0, 200, 256)), im.crop((120, 0, 320, 256))])
