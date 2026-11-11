"""
Builds the web assets for the animated logo from frontend/resources/*.gif.

  frontend/src/assets/logo-sprite.png   all frames side by side (square cells), played once by CSS
  frontend/public/favicon.png           last frame on a light rounded tile (black art is invisible
                                        on dark browser tabs otherwise)
  frontend/public/apple-touch-icon.png  same, 180px

Why a sprite instead of the GIF itself: browsers can't stop a GIF from JavaScript, and the GIF's
loop flag is handled inconsistently. A sprite + CSS steps() plays exactly once and holds the last
frame, everywhere. Requires Pillow:  python scripts/make-logo-assets.py
"""
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw
from scipy import ndimage

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "frontend" / "resources" / "Animated Eagle Emblem Evolution.gif"
CELL = 192          # px per frame in the sprite (shown at up to ~96px, so 2x for sharp screens)
TILE = (244, 244, 247, 255)

def keep_main_shape(frame, gap=12):
    """The source GIF has stray marks along its bottom edge (a thin line, partial shapes) that sit
    well apart from the emblem. Keep only the main connected shape; `gap` px of dilation lets
    pieces that belong together (ring + dot, loose feathers) count as one."""
    px = np.array(frame)
    # A full-width line runs across the bottom of every source frame; drop it first, or it
    # bridges the emblem to the loose fragments below it.
    height, width = px.shape[:2]
    wide = (px[..., 3] > 0).sum(axis=1) >= 0.45 * width
    in_bottom_strip = np.arange(height) >= 0.9 * height
    px[wide & in_bottom_strip] = 0
    ink = px[..., 3] > 0
    labels, count = ndimage.label(ndimage.binary_dilation(ink, iterations=gap))
    if count <= 1:
        return Image.fromarray(px, "RGBA")
    sizes = ndimage.sum(ink, labels, range(1, count + 1))
    keep = labels == (int(np.argmax(sizes)) + 1)
    px[~keep] = 0
    return Image.fromarray(px, "RGBA")


im = Image.open(SRC)
frames = []
for i in range(im.n_frames):
    im.seek(i)
    frames.append(keep_main_shape(im.convert("RGBA")))

# One crop box for all frames (union of what is drawn), padded to a square, so frames stay aligned.
boxes = [f.getchannel("A").getbbox() for f in frames]
left = min(b[0] for b in boxes)
top = min(b[1] for b in boxes)
right = max(b[2] for b in boxes)
bottom = max(b[3] for b in boxes)
side = max(right - left, bottom - top)
cx, cy = (left + right) / 2, (top + bottom) / 2
crop = (round(cx - side / 2), round(cy - side / 2), round(cx + side / 2), round(cy + side / 2))


def square(frame):
    canvas = Image.new("RGBA", (crop[2] - crop[0], crop[3] - crop[1]), (0, 0, 0, 0))
    canvas.paste(frame, (-crop[0], -crop[1]))
    return canvas.resize((CELL, CELL), Image.LANCZOS)


cells = [square(f) for f in frames]
sprite = Image.new("RGBA", (CELL * len(cells), CELL), (0, 0, 0, 0))
for i, cell in enumerate(cells):
    sprite.paste(cell, (i * CELL, 0))
out = ROOT / "frontend" / "src" / "assets"
out.mkdir(parents=True, exist_ok=True)
# 8-bit palette with alpha: ~4x smaller, no visible loss for this black-and-gray art
sprite.quantize(256, method=Image.Quantize.FASTOCTREE).save(out / "logo-sprite.png", optimize=True)
print("sprite", sprite.size, "frames", len(cells))


def on_tile(cell, size):
    tile = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    ImageDraw.Draw(tile).rounded_rectangle((0, 0, size - 1, size - 1), radius=size // 5, fill=TILE)
    inner = int(size * 0.84)
    art = cell.resize((inner, inner), Image.LANCZOS)
    tile.alpha_composite(art, ((size - inner) // 2, (size - inner) // 2))
    return tile


public = ROOT / "frontend" / "public"
on_tile(cells[-1], 128).save(public / "favicon.png", optimize=True)
on_tile(cells[-1], 180).save(public / "apple-touch-icon.png", optimize=True)
print("favicon + apple-touch-icon written")
