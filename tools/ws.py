import sys, glob
from PIL import Image
import numpy as np
# window column / row extent: find from the image (glass is brighter than the room behind)
def measure(path, y0=862, y1=1700, x0=920, x1=1700, W0=850, W1=1712):
    im = np.asarray(Image.open(path).convert('L')).astype(int)
    reg = im[y0:y1, x0:x1]
    med = np.median(reg, axis=1, keepdims=True)
    content = (np.abs(reg - med) > 28).sum(axis=1) > 3
    H = W1 - W0
    ys = np.where(content)[0] + (y0 - W0)
    if len(ys) == 0: return None
    top = ys[0]; bot = H - 1 - ys[-1]
    gaps = np.diff(ys) - 1
    return top / H, bot / H, (top + bot) / H, (gaps.max() if len(gaps) else 0) / H
for p in sorted(sys.argv[1:]):
    r = measure(p)
    if r: print(f"{p.split('/')[-1]}\ttop {r[0]:.0%}\tbottom {r[1]:.0%}\touter {r[2]:.0%}\tinnerMax {r[3]:.0%}")
