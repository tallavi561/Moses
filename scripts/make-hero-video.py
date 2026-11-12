"""
Builds the web assets for the big intro logo (login / project picker) from the source video in
frontend/resources/*.mp4:

  frontend/src/assets/hero-logo.mp4         cropped, black-crushed, audio-free, H.264
  frontend/src/assets/hero-logo-first.webp  first frame  (poster while the video loads)
  frontend/src/assets/hero-logo-last.webp   last frame   (reduced motion, replays, autoplay blocked)

The source has an opaque near-black backdrop (about RGB 3-12, darker than the app's #121317), so
shown as-is it would look like a dark box. Here the blacks are pushed to true black and the margins
are cropped; in the page the video is blended with CSS `mix-blend-mode: screen`, which makes true
black fully transparent and keeps the glow. Audio is dropped: browsers block autoplay with sound.

Requires:  pip install imageio-ffmpeg pillow   (imageio-ffmpeg bundles an ffmpeg binary)
Run:       python scripts/make-hero-video.py
"""
import subprocess
import sys
import tempfile
from pathlib import Path

import imageio_ffmpeg
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
RESOURCES = ROOT / "frontend" / "resources"
OUT = ROOT / "frontend" / "src" / "assets"

# Where the action is in the 1280x720 source (measured over the whole clip), with some margin.
CROP = "crop=1000:720:140:0"
# Input black point: levels at or below ~5.5% (about 14/255) become 0, the rest is rescaled.
BLACK_POINT = 0.055
OUT_WIDTH = 900

sources = sorted(RESOURCES.glob("*.mp4"))
if len(sources) != 1:
    sys.exit(f"expected exactly one .mp4 in {RESOURCES}, found {len(sources)}")
src = sources[0]
ffmpeg = imageio_ffmpeg.get_ffmpeg_exe()
OUT.mkdir(parents=True, exist_ok=True)

video_filter = (
    f"{CROP},"
    f"colorlevels=rimin={BLACK_POINT}:gimin={BLACK_POINT}:bimin={BLACK_POINT},"
    f"scale={OUT_WIDTH}:-2:flags=lanczos,format=yuv420p"
)
dest = OUT / "hero-logo.mp4"
subprocess.run(
    [ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-i", str(src),
     "-vf", video_filter, "-an",
     "-c:v", "libx264", "-preset", "slow", "-crf", "24", "-movflags", "+faststart",
     str(dest)],
    check=True,
)
print("video", dest.name, round(dest.stat().st_size / 1024), "KB")

with tempfile.TemporaryDirectory() as tmp:
    for name, seek in (("first", ["-ss", "0"]), ("last", ["-sseof", "-0.05"])):
        png = Path(tmp) / f"{name}.png"
        subprocess.run(
            [ffmpeg, "-hide_banner", "-loglevel", "error", "-y", *seek, "-i", str(dest),
             "-frames:v", "1", str(png)],
            check=True,
        )
        webp = OUT / f"hero-logo-{name}.webp"
        Image.open(png).convert("RGB").save(webp, quality=88, method=6)
        print("still", webp.name, round(webp.stat().st_size / 1024), "KB")
