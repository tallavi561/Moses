"""
Builds the idle loop that continues the intro logo video (see make-hero-video.py):

  frontend/src/assets/hero-loop.mp4   LOOP_SECONDS of very subtle motion, seamless when repeated

It starts from the EXACT last frame of hero-logo.mp4, so the hand-off from the intro is invisible,
and every effect is zero at t=0 and t=T, so the last frame of the loop equals its first and
repeating it never jumps. Nothing here is AI-generated video: the motion is computed from that
one frame:

  - glow pulse   the cyan, light-emitting parts brighten and bloom slightly, then settle
  - light sweep  one soft diagonal band of light travels across those same parts
  - breathing    the whole image scales up by well under 1% and back
  - particles    a few faint dots drift upward and fade in and out

Tune the constants below. Requires: pip install imageio-ffmpeg pillow numpy scipy
Run: python scripts/make-hero-loop.py   (after make-hero-video.py)
"""
import subprocess
import tempfile
from pathlib import Path

import imageio_ffmpeg
import numpy as np
from PIL import Image
from scipy import ndimage

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "frontend" / "src" / "assets"
INTRO = ASSETS / "hero-logo.mp4"
OUT = ASSETS / "hero-loop.mp4"

FPS = 24
LOOP_SECONDS = 8
FRAMES = FPS * LOOP_SECONDS

GLOW_GAIN = 0.10       # brighten glowing pixels by up to this much at the peak of the pulse
BLOOM_GAIN = 0.20      # how much of the blurred glow is added back (soft halo)
SWEEP_GAIN = 0.28      # extra brightness on glowing pixels under the travelling band
SWEEP_WIDTH = 0.07     # band half-width as a fraction of the image diagonal
BREATH = 0.006         # peak scale-up of the whole image (0.6%)
PARTICLES = 34
PARTICLE_DRIFT_PX = 46  # how far a particle floats upward over one loop
PARTICLE_GAIN = 0.45

rng = np.random.default_rng(7)
ffmpeg = imageio_ffmpeg.get_ffmpeg_exe()

# --- the exact last frame of the intro -------------------------------------------------------
with tempfile.TemporaryDirectory() as tmp:
    png = Path(tmp) / "last.png"
    subprocess.run(
        [ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-sseof", "-0.5", "-i", str(INTRO),
         "-update", "1", str(png)],
        check=True,
    )
    base = np.array(Image.open(png).convert("RGB")).astype(np.float32)
H, W, _ = base.shape
print("base frame", W, "x", H)

# --- static layers (computed once) -----------------------------------------------------------
r, g, b = base[..., 0], base[..., 1], base[..., 2]
glow_mask = np.clip((b - 0.9 * r - 25) / 110, 0, 1)            # cyan, light-emitting pixels
glow_mask = ndimage.gaussian_filter(glow_mask, 1.0)[..., None]
bloom = ndimage.gaussian_filter(base * glow_mask, sigma=(6, 6, 0))

yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
theta = np.deg2rad(28)
u = (xx * np.cos(theta) + yy * np.sin(theta)) / (W * np.cos(theta) + H * np.sin(theta))  # 0..1 across

# particles live in the central area where the art is; each gets its own phase/size/speed
px = rng.uniform(0.12 * W, 0.88 * W, PARTICLES)
py = rng.uniform(0.15 * H, 0.9 * H, PARTICLES)
p_phase = rng.uniform(0, 1, PARTICLES)
p_size = rng.uniform(1.1, 2.3, PARTICLES)
p_amp = rng.uniform(0.5, 1.0, PARTICLES)
p_sway = rng.uniform(2, 6, PARTICLES)
p_flicker_k = rng.integers(1, 4, PARTICLES)            # integer -> periodic over the loop
PARTICLE_COLOR = np.array([130, 225, 255], np.float32)


def smooth_env(t):  # 0 at t=0 and t=1, peak in the middle: keeps first == last frame
    return 0.5 * (1 - np.cos(2 * np.pi * t))


def render(t):
    """Frame for loop position t in [0, 1)."""
    env = smooth_env(t)
    frame = base * (1 + GLOW_GAIN * env * glow_mask) + bloom * (BLOOM_GAIN * env)

    centre = -0.25 + 1.5 * t                              # band is off-image at t=0 and t=1
    band = np.exp(-(((u - centre) / SWEEP_WIDTH) ** 2))[..., None]
    frame += base * glow_mask * band * SWEEP_GAIN

    for i in range(PARTICLES):
        life = (t + p_phase[i]) % 1.0                     # each dot floats up once per loop
        fade = np.sin(np.pi * life) ** 2
        flicker = 0.75 + 0.25 * np.sin(2 * np.pi * (p_flicker_k[i] * t) + p_phase[i] * 6.28)
        amp = PARTICLE_GAIN * p_amp[i] * fade * flicker * env
        if amp < 0.01:
            continue
        cx = px[i] + p_sway[i] * np.sin(2 * np.pi * (t + p_phase[i]))
        cy = py[i] - PARTICLE_DRIFT_PX * life
        x0, x1 = int(max(cx - 8, 0)), int(min(cx + 9, W))
        y0, y1 = int(max(cy - 8, 0)), int(min(cy + 9, H))
        if x0 >= x1 or y0 >= y1:
            continue
        d2 = (xx[y0:y1, x0:x1] - cx) ** 2 + (yy[y0:y1, x0:x1] - cy) ** 2
        blob = np.exp(-d2 / (2 * p_size[i] ** 2))[..., None]
        frame[y0:y1, x0:x1] += blob * PARTICLE_COLOR * amp

    frame = np.clip(frame, 0, 255)
    s = 1 + BREATH * env                                  # breathing, about the image centre
    if s > 1.00001:
        cx_, cy_ = W / 2, H / 2
        coeffs = (1 / s, 0, cx_ - cx_ / s, 0, 1 / s, cy_ - cy_ / s)
        img = Image.fromarray(frame.astype(np.uint8)).transform((W, H), Image.AFFINE, coeffs, Image.BICUBIC)
        return np.array(img)
    return frame.astype(np.uint8)


# --- encode ----------------------------------------------------------------------------------
proc = subprocess.Popen(
    [ffmpeg, "-hide_banner", "-loglevel", "error", "-y",
     "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}", "-r", str(FPS), "-i", "-",
     "-an", "-c:v", "libx264", "-preset", "slow", "-crf", "23", "-pix_fmt", "yuv420p",
     "-movflags", "+faststart", str(OUT)],
    stdin=subprocess.PIPE,
)
diffs = []
for n in range(FRAMES):
    frame = render(n / FRAMES)
    diffs.append(float(np.abs(frame.astype(np.float32) - base).mean()))
    proc.stdin.write(frame.tobytes())
proc.stdin.close()
proc.wait()
print("wrote", OUT.name, round(OUT.stat().st_size / 1024), "KB,", FRAMES, "frames,", LOOP_SECONDS, "s")
print("mean abs change vs the base frame: first %.3f, peak %.2f, last %.3f (gray levels 0-255)"
      % (diffs[0], max(diffs), diffs[-1]))
