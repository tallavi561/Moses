"""
Builds the idle loop that continues the intro logo video, from a loop video generated in Gemini
(frontend/resources/loop-from-gemini*.mp4). See make-hero-video.py for the intro.

  frontend/src/assets/hero-loop.mp4   the loop, processed exactly like the intro, seamless when repeated

The video is run through the same steps as the intro (same crop, black crushed to true black so
CSS `screen` blending makes the backdrop transparent, same size, no audio). A generated video never
returns to its starting frame exactly, so the two joins are crossfaded through E, the EXACT last
frame of the intro:

    E --(fade in)--> Gemini's frames --(fade out)--> E

so the loop's first frame is E (the intro's last frame, an invisible hand-off) and its last frame
fades back to E (so repeating it never jumps). The fades are short and sit where the source is
close to its resting pose, so they read as a gentle settle, not a ghosting dissolve.

Requires: pip install imageio-ffmpeg pillow numpy
Run: python scripts/make-hero-loop.py   (after make-hero-video.py)
"""
import subprocess
import sys
import tempfile
from pathlib import Path

import imageio_ffmpeg
import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
RESOURCES = ROOT / "frontend" / "resources"
ASSETS = ROOT / "frontend" / "src" / "assets"
INTRO = ASSETS / "hero-logo.mp4"
OUT = ASSETS / "hero-loop.mp4"

# Same framing and levels as the intro (keep in sync with make-hero-video.py).
CROP = "crop=1000:720:140:0"
BLACK_POINT = 0.055
OUT_WIDTH = 900

FPS = 24
FADE_IN_FRAMES = 18     # 0.75 s: from E into the generated motion
FADE_OUT_FRAMES = 24    # 1.00 s: from the generated motion back to E
CRF = 24

sources = sorted(RESOURCES.glob("loop-from-gemini*.mp4"))
if len(sources) != 1:
    sys.exit(f"expected exactly one loop-from-gemini*.mp4 in {RESOURCES}, found {len(sources)}")
src = sources[0]
ffmpeg = imageio_ffmpeg.get_ffmpeg_exe()

# --- E: the exact last frame of the intro ----------------------------------------------------
with tempfile.TemporaryDirectory() as tmp:
    png = Path(tmp) / "last.png"
    subprocess.run(
        [ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-sseof", "-0.5", "-i", str(INTRO),
         "-update", "1", str(png)],
        check=True,
    )
    E = np.array(Image.open(png).convert("RGB")).astype(np.float32)
H, W, _ = E.shape

# --- the generated frames, processed like the intro ------------------------------------------
video_filter = (
    f"{CROP},"
    f"colorlevels=rimin={BLACK_POINT}:gimin={BLACK_POINT}:bimin={BLACK_POINT},"
    f"scale={OUT_WIDTH}:-2:flags=lanczos"
)
raw = subprocess.run(
    [ffmpeg, "-hide_banner", "-loglevel", "error", "-i", str(src), "-vf", video_filter,
     "-an", "-f", "rawvideo", "-pix_fmt", "rgb24", "-"],
    check=True, capture_output=True,
).stdout
frames = np.frombuffer(raw, np.uint8).reshape(-1, H, W, 3)
N = len(frames)
print(f"source: {src.name}, {N} frames, {N / FPS:.2f} s, {W}x{H}")


def smoothstep(x):
    x = np.clip(x, 0.0, 1.0)
    return x * x * (3 - 2 * x)


# --- crossfade through E at both ends --------------------------------------------------------
proc = subprocess.Popen(
    [ffmpeg, "-hide_banner", "-loglevel", "error", "-y",
     "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}", "-r", str(FPS), "-i", "-",
     "-an", "-c:v", "libx264", "-preset", "slow", "-crf", str(CRF), "-pix_fmt", "yuv420p",
     "-movflags", "+faststart", str(OUT)],
    stdin=subprocess.PIPE,
)
first = last = None
steps = []
prev = None
for i in range(N):
    g = frames[i].astype(np.float32)
    if i < FADE_IN_FRAMES:                                   # E -> generated; frame 0 is exactly E
        w = smoothstep(i / FADE_IN_FRAMES)
        out = (1 - w) * E + w * g
    elif i >= N - FADE_OUT_FRAMES:                           # generated -> E; never quite reaches E,
        v = smoothstep((i - (N - FADE_OUT_FRAMES)) / FADE_OUT_FRAMES)  # the wrap to frame 0 finishes it
        out = (1 - v) * g + v * E
    else:
        out = g
    out = np.clip(np.rint(out), 0, 255).astype(np.uint8)
    if prev is not None:
        steps.append(float(np.abs(out.astype(np.float32) - prev).mean()))
    if i == 0:
        first = out
    last = out
    prev = out.astype(np.float32)
    proc.stdin.write(out.tobytes())
proc.stdin.close()
proc.wait()

wrap_step = float(np.abs(first.astype(np.float32) - last.astype(np.float32)).mean())
print(f"wrote {OUT.name}: {OUT.stat().st_size / 1024:.0f} KB, {N} frames")
print(f"first frame vs E (intro's last frame): {float(np.abs(first.astype(np.float32) - E).mean()):.3f}")
print(f"step across the loop point (last -> first): {wrap_step:.2f}   "
      f"typical frame-to-frame step: median {np.median(steps):.2f}, max {max(steps):.2f}")
