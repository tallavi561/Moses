"""
Builds the idle loop that continues the intro logo video (see make-hero-video.py):

  frontend/src/assets/hero-loop.mp4   LOOP_SECONDS of gentle motion, seamless when repeated

It starts from the EXACT last frame of hero-logo.mp4, so the hand-off from the intro is invisible,
and every effect is exactly zero at t=0 and t=T, so the last frame of the loop equals its first and
repeating it never jumps. Nothing here is AI-generated video: the motion is computed from that one
frame:

  - currents     pulses of light leave the eagle's body and flow outward along the cyan lines of
                 the wings and circuits (a few per loop, each fading in and out on its way)
  - wing flap    the wings flex up and slightly inward and settle back (the body stays put)
  - glow pulse   the light-emitting parts swell a little and bloom, then settle
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
LOOP_SECONDS = 10
FRAMES = FPS * LOOP_SECONDS

# currents: pulses launched from the body, travelling outward along the lit lines
PULSE_LAUNCHES = (0.04, 0.27, 0.50)   # launch times as a fraction of the loop
PULSE_TRAVEL = 0.42                    # fraction of the loop one pulse takes to reach the wing tips
PULSE_WIDTH_PX = 62                    # thickness of the travelling band
PULSE_GAIN = 1.0                       # brightness added on lit pixels under the band (x original)
PULSE_WARP_PX = 34                     # irregular wavefront so it isn't a perfect circle

# wing flap
FLAPS = 2                              # up-and-down cycles per loop
FLAP_UP_PX = 11                        # how far the wing tips rise at the top of a flap
FLAP_IN_PX = 5                         # and how far they pull toward the body
BODY_HALF_WIDTH_PX = 80                # inside this the eagle's body does not move at all

# glow + particles
GLOW_GAIN = 0.12
BLOOM_GAIN = 0.22
PARTICLES = 40
PARTICLE_DRIFT_PX = 52
PARTICLE_GAIN = 0.5

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
cx, cy = W * 0.5, H * 0.50                                     # the eagle's chest

# distance from the chest, bent by a smooth static noise so wavefronts look organic
noise = ndimage.gaussian_filter(rng.standard_normal((H, W)).astype(np.float32), 38)
noise /= np.abs(noise).max()
dist = np.sqrt((xx - cx) ** 2 + (yy - cy) ** 2) + PULSE_WARP_PX * noise
R_MAX = float(np.sqrt((W / 2) ** 2 + (H / 2) ** 2))

# wing weights: 0 on the body, growing toward the tips; limited to the band where the wings are
side = np.sign(xx - cx)
across = np.clip((np.abs(xx - cx) - BODY_HALF_WIDTH_PX) / (W / 2 - BODY_HALF_WIDTH_PX), 0, 1)
across = across * across * (3 - 2 * across)                    # smoothstep
across = across ** 1.3
top = np.clip((yy - 10) / 50, 0, 1)
bottom = np.clip((H * 0.74 - yy) / 120, 0, 1)
wing_weight = (across * top * bottom * (top * (3 - 2 * top))).astype(np.float32)

# particles live in the central area where the art is; each gets its own phase/size/speed
px = rng.uniform(0.12 * W, 0.88 * W, PARTICLES)
py = rng.uniform(0.15 * H, 0.9 * H, PARTICLES)
p_phase = rng.uniform(0, 1, PARTICLES)
p_size = rng.uniform(1.1, 2.3, PARTICLES)
p_amp = rng.uniform(0.5, 1.0, PARTICLES)
p_sway = rng.uniform(2, 6, PARTICLES)
p_flicker_k = rng.integers(1, 4, PARTICLES)            # integer -> periodic over the loop
PARTICLE_COLOR = np.array([130, 225, 255], np.float32)


def swell(t, cycles=1):
    """0 at t=0 and t=1, peaks in between: 0.5*(1-cos). Keeps the first frame == the last."""
    return 0.5 * (1 - np.cos(2 * np.pi * cycles * t))


def pulse_amplitude(t, launch):
    """0 before launch and after the pulse has left the picture, smooth in between."""
    k = (t - launch) / PULSE_TRAVEL
    if k <= 0 or k >= 1:
        return 0.0, 0.0
    return float(np.sin(np.pi * k) ** 0.8), k


def warp_wings(frame, t):
    s = swell(t, FLAPS)
    if s <= 1e-4:
        return frame
    dy = -FLAP_UP_PX * s * wing_weight                       # up
    dx = -side * FLAP_IN_PX * s * wing_weight                # toward the body
    coords = [yy - dy, xx - dx]                              # backward map: where each pixel comes from
    out = np.empty_like(frame)
    for ch in range(3):
        out[..., ch] = ndimage.map_coordinates(frame[..., ch], coords, order=3, mode="nearest")
    return out


def render(t):
    """Frame for loop position t in [0, 1)."""
    env = swell(t)
    frame = base * (1 + GLOW_GAIN * env * glow_mask) + bloom * (BLOOM_GAIN * env)

    for launch in PULSE_LAUNCHES:                            # currents flowing out of the body
        amp, k = pulse_amplitude(t, launch)
        if amp == 0.0:
            continue
        front = k * (R_MAX * 1.15) - PULSE_WIDTH_PX * 0.5    # band starts inside, ends past the tips
        band = np.exp(-(((dist - front) / PULSE_WIDTH_PX) ** 2))[..., None]
        frame += base * glow_mask * band * (PULSE_GAIN * amp)

    for i in range(PARTICLES):
        life = (t + p_phase[i]) % 1.0                        # each dot floats up once per loop
        fade = np.sin(np.pi * life) ** 2
        flicker = 0.75 + 0.25 * np.sin(2 * np.pi * (p_flicker_k[i] * t) + p_phase[i] * 6.28)
        amp = PARTICLE_GAIN * p_amp[i] * fade * flicker * env
        if amp < 0.01:
            continue
        pcx = px[i] + p_sway[i] * np.sin(2 * np.pi * (t + p_phase[i]))
        pcy = py[i] - PARTICLE_DRIFT_PX * life
        x0, x1 = int(max(pcx - 8, 0)), int(min(pcx + 9, W))
        y0, y1 = int(max(pcy - 8, 0)), int(min(pcy + 9, H))
        if x0 >= x1 or y0 >= y1:
            continue
        d2 = (xx[y0:y1, x0:x1] - pcx) ** 2 + (yy[y0:y1, x0:x1] - pcy) ** 2
        blob = np.exp(-d2 / (2 * p_size[i] ** 2))[..., None]
        frame[y0:y1, x0:x1] += blob * PARTICLE_COLOR * amp

    frame = np.clip(frame, 0, 255)
    frame = warp_wings(frame, t)                             # the lit parts flex with the wings
    return np.clip(frame, 0, 255).astype(np.uint8)


if __name__ == "__main__":
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
