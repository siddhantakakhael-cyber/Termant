# Draws docs/banner.png from three screenshots of the app.
#   pip install pillow font-roboto
#   python3 docs/make-banner.py docs/screenshots docs/banner.png
import os, sys
from PIL import Image, ImageDraw, ImageFilter, ImageFont
import font_roboto

shots, out = sys.argv[1], sys.argv[2]
W, H, S = 1680, 640, 2          # drawn at twice the size, then reduced, for smooth edges
ORANGE, INK, SOFT = (255, 122, 69), (255, 255, 255), (201, 209, 227)

def font(face, size):
    return ImageFont.truetype(face, size * S)

def rounded_mask(size, radius):
    m = Image.new('L', size, 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, size[0] - 1, size[1] - 1], radius, fill=255)
    return m

# background: dark blue, lighter towards the lower right, an orange glow behind the phones
bg = Image.new('RGB', (W * S, H * S))
top, bottom = (11, 16, 27), (24, 30, 46)
grad = Image.linear_gradient('L').resize((W * S, H * S))
bg = Image.composite(Image.new('RGB', bg.size, bottom), Image.new('RGB', bg.size, top), grad)
glow = Image.new('L', bg.size, 0)
ImageDraw.Draw(glow).ellipse([980 * S, -260 * S, 1900 * S, 520 * S], fill=120)
glow = glow.filter(ImageFilter.GaussianBlur(170 * S))
bg = Image.composite(Image.new('RGB', bg.size, ORANGE), bg, glow.point(lambda v: int(v * 0.55)))
d = ImageDraw.Draw(bg)

# the phones
pw, ph, gap = 264, 528, 26
x0 = W - 84 - (3 * pw + 2 * gap)
for i, (name, dy) in enumerate([('chat', 72), ('code-and-rule', 40), ('terminal', 72)]):
    shot = Image.open(os.path.join(shots, name + '.png')).convert('RGB').resize((pw * S, ph * S), Image.LANCZOS)
    x, y = (x0 + i * (pw + gap)) * S, dy * S
    frame = 7 * S
    box = [x - frame, y - frame, x + pw * S + frame, y + ph * S + frame]
    shadow = Image.new('L', bg.size, 0)
    ImageDraw.Draw(shadow).rounded_rectangle([box[0], box[1] + 14 * S, box[2], box[3] + 22 * S], 23 * S, fill=170)
    shadow = shadow.filter(ImageFilter.GaussianBlur(22 * S))
    bg = Image.composite(Image.new('RGB', bg.size, (0, 0, 0)), bg, shadow)
    d = ImageDraw.Draw(bg)
    d.rounded_rectangle(box, 23 * S, fill=(5, 7, 12), outline=(58, 68, 92), width=2 * S)
    bg.paste(shot, (x, y), rounded_mask(shot.size, 16 * S))

# the app icon and the name
ix, iy, isz = 84, 138, 108
d.rounded_rectangle([ix * S, iy * S, (ix + isz) * S, (iy + isz) * S], 26 * S, fill=(8, 11, 19), outline=(50, 60, 84), width=2 * S)
u = isz / 108 * S   # the icon's drawing uses a 108 grid, as android/res/drawable/ic_launcher_foreground.xml
def at(px_, py_):
    return (ix * S + (px_ - 54) * 1.5 * u + 54 * u, iy * S + (py_ - 54) * 1.5 * u + 54 * u)
d.line([at(36, 40), at(52, 54), at(36, 68)], fill=INK, width=int(8 * u), joint='curve')
for p in (at(36, 40), at(52, 54), at(36, 68)):
    r = 4 * u; d.ellipse([p[0] - r, p[1] - r, p[0] + r, p[1] + r], fill=INK)
d.line([at(56, 69), at(74, 69)], fill=ORANGE, width=int(8 * u))
for p in (at(56, 69), at(74, 69)):
    r = 4 * u; d.ellipse([p[0] - r, p[1] - r, p[0] + r, p[1] + r], fill=ORANGE)

name_font = font(font_roboto.RobotoBold, 98)
d.text(((ix + isz + 28) * S, (iy + isz / 2) * S), 'Termant', font=name_font, fill=INK, anchor='lm')

line = font(font_roboto.Roboto, 37)
for n, text in enumerate(['An AI agent and a real Ubuntu', 'terminal on your Android phone.']):
    d.text((ix * S, (296 + n * 50) * S), text, font=line, fill=SOFT)

chip = font(font_roboto.RobotoMedium, 23)
x = ix
for text in ['No root', 'No server', 'Hindi + English']:
    w = d.textlength(text, font=chip) / S
    d.rounded_rectangle([x * S, 436 * S, (x + w + 40) * S, 484 * S], 24 * S, fill=(40, 28, 26), outline=ORANGE, width=int(1.5 * S))
    d.text(((x + 20) * S, 460 * S), text, font=chip, fill=(255, 160, 118), anchor='lm')
    x += w + 40 + 16
assert x < x0 - 30, 'the chips run into the phones'
assert (ix + isz + 28) + d.textlength('Termant', font=name_font) / S < x0 - 30, 'the name runs into the phones'

final = bg.resize((W, H), Image.LANCZOS).convert('RGBA')
final.putalpha(rounded_mask((W * 4, H * 4), 36 * 4).resize((W, H), Image.LANCZOS))
final.save(out, optimize=True)
print(out, final.size, os.path.getsize(out), 'bytes')
