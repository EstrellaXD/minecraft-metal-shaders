#!/usr/bin/env python3
# cut.py (2026-10-11): cuts a cine recording (run.sh --rec: rec/<label>.mp4 + .mp4.csv, the clips back to back) into one
# file per clip, by the bench phase of each recorded frame (CINE_<NAME>), as H.264 1920 wide (lanczos), aspect kept.
#   cut.py rec/<label>.mp4 [out dir (default clips/<label>)] [--size=1920x-2] [--crf=17] [--crop=w:h:x:y]
import csv, os, subprocess, sys

args = [a for a in sys.argv[1:] if not a.startswith("--")]
opts = dict(a[2:].split("=", 1) for a in sys.argv[1:] if a.startswith("--") and "=" in a)
src = args[0]
label = os.path.basename(src).rsplit(".", 1)[0]
out = args[1] if len(args) > 1 else os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(src))), "clips", label)
size = opts.get("size", "1920x-2")  # width x height; -2 keeps the aspect (2218x1440 -> 1920x1246)
crf = opts.get("crf", "17")
crop = opts.get("crop")  # ffmpeg crop=w:h:x:y applied first (show-ground: 2218:1440:171:0, the recorder had pillarboxed it)
fps = 60.0
os.makedirs(out, exist_ok=True)

runs = []  # [phase, first frame, last frame]
with open(src + ".csv") as f:
    for row in csv.DictReader(f):
        k, ph = int(row["frame"]), row["phase"]
        if runs and runs[-1][0] == ph and k <= runs[-1][2] + 30:
            runs[-1][2] = k
        else:
            runs.append([ph, k, k])
w, h = size.split("x")
for ph, a, b in runs:
    if not ph.startswith("cine_"):
        continue
    name = ph[5:]
    dst = os.path.join(out, name + ".mp4")
    t0, t1 = a / fps, (b + 1) / fps
    cmd = ["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-ss", f"{t0:.4f}", "-i", src, "-t", f"{t1 - t0:.4f}",
           "-vf", (f"crop={crop}," if crop else "") + f"scale={w}:{h}:flags=lanczos", "-c:v", "libx264", "-preset", opts.get("preset", "slow"), "-threads", opts.get("threads", "0"), "-crf", crf, "-profile:v", "high",
           "-pix_fmt", "yuv420p", "-color_primaries", "bt709", "-color_trc", "bt709", "-colorspace", "bt709",
           "-movflags", "+faststart", "-an", dst]
    subprocess.run(cmd, check=True)
    print(f"{name}: frames {a}..{b} ({t1 - t0:.1f} s) -> {dst}")
