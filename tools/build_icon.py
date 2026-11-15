"""Spatial Calculator icon (design appIcon) as a 512 PNG for the showcase / Vibe Lab: the same drawing as
res/drawable/ic_launcher_fg.xml on the graphite gradient of ic_launcher_bg.xml. Usage: python3 tools/build_icon.py OUT.png"""
import sys
from PIL import Image, ImageDraw
S = 512; SS = 4; N = S * SS; K = N / 108.0
img = Image.new("RGBA", (N, N))
d = ImageDraw.Draw(img)
fg = Image.new("RGBA", (N, N), (0, 0, 0, 0))
top, bot = (0x2E, 0x34, 0x3C), (0x23, 0x27, 0x2E)
for y in range(N):
    t = y / (N - 1)
    d.line([(0, y), (N, y)], fill=tuple(int(top[i] + (bot[i] - top[i]) * t) for i in range(3)) + (255,))
for r in range(3):
    for c in range(3):
        x, y, rad = 36 + c * 15, 30 + r * 15, 6
        col = (0x7C, 0xC6, 0xD8, 255) if (r, c) == (2, 2) else (255, 255, 255, 179)
        ImageDraw.Draw(fg).ellipse([(x - rad) * K, (y - rad) * K, (x + rad) * K, (y + rad) * K], fill=col)
img = Image.alpha_composite(img, fg)
d = ImageDraw.Draw(img)
d.rectangle([28 * K, 74 * K, 108 * K, 76.4 * K], fill=(255, 255, 255, 255))
d.rectangle([27 * K, 70 * K, 29.4 * K, 80 * K], fill=(255, 255, 255, 255))
for i, x in enumerate(range(30, 108, 8)):
    h = 7 if i % 2 == 0 else 4
    d.rectangle([x * K, 77 * K, (x + 1.6) * K, (77 + h) * K], fill=(255, 255, 255, 255))
img.resize((S, S), Image.LANCZOS).save(sys.argv[1])
