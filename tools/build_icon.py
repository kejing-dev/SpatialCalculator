"""Spatial Calculator icon (design appIcon) as a 512 PNG for the showcase / Vibe Lab: same drawing as
res/drawable/ic_launcher_fg.xml on #1F4D3A. Usage: python3 tools/build_icon.py OUT.png"""
import sys
from PIL import Image, ImageDraw
S = 512; K = S / 108.0; SS = 4
im = Image.new("RGBA", (S * SS, S * SS), (0x1F, 0x4D, 0x3A, 255))
d = ImageDraw.Draw(im, "RGBA")
k = K * SS
def P(pts): return [(x * k, y * k) for x, y in pts]
# tape: a cubic from (30,78) via (30,64) (40,58) to (54,60) then a line to (66,62)
pts = []
for i in range(41):
    t = i / 40
    x = (1-t)**3*30 + 3*(1-t)**2*t*30 + 3*(1-t)*t*t*40 + t**3*54
    y = (1-t)**3*78 + 3*(1-t)**2*t*64 + 3*(1-t)*t*t*58 + t**3*60
    pts.append((x, y))
pts.append((66, 62))
d.line(P(pts), fill=(0xF2, 0xC2, 0x30, 255), width=int(9 * k), joint="curve")
for (x, y) in [pts[0], pts[-1]]:
    r = 4.5 * k; d.ellipse((x * k - r, y * k - r, x * k + r, y * k + r), fill=(0xF2, 0xC2, 0x30, 255))
for x0, y0, h in [(37, 64.5, 7), (45, 60.5, 6), (53, 59.5, 6)]:
    d.rectangle((x0 * k, y0 * k, (x0 + 2.6) * k, (y0 + h) * k), fill=(0x1B, 0x1F, 0x24, 255))
d.polygon(P([(58, 40), (72, 32), (86, 40), (86, 58), (72, 66), (58, 58)]), fill=(0xBF, 0xE3, 0xFF, 0x99))
d.polygon(P([(58, 40), (72, 32), (86, 40), (72, 48)]), fill=(0xBF, 0xE3, 0xFF, 0xCC))
d.line(P([(58, 40), (72, 32), (86, 40), (86, 58), (72, 66), (58, 58), (58, 40)]), fill=(255, 255, 255, 230), width=int(1.4 * k))
d.line(P([(58, 40), (72, 48), (86, 40)]), fill=(255, 255, 255, 255), width=int(2.2 * k))
d.line(P([(72, 48), (72, 66)]), fill=(255, 255, 255, 255), width=int(2.2 * k))
im.resize((S, S), Image.LANCZOS).save(sys.argv[1])
