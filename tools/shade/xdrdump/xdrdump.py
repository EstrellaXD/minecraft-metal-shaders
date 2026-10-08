# Copyright 2026 mcopt-shade contributors. Licensed under the Apache License, Version 2.0.
# Dev tool: reads MCOPT_XDR_DUMP files (raw RGB9E5 or RGBA16F, linear extended Display P3; 1.0 = SDR white) and reports
# how much of the frame is above SDR white, plus a small PNG per dump: the SDR-clipped image (left) and a map of the
# excess (right: black = SDR, red..yellow..white = 1x..peak). Usage: python3 -I xdrdump.py <out dir> <dump files...>
import sys, os, re, zlib, struct
import numpy as np

def load(path):
    m = re.search(r"-(\d+)x(\d+)\.(rgb9e5|rgba16f)$", path)
    w, h, kind = int(m.group(1)), int(m.group(2)), m.group(3)
    if kind == "rgb9e5":
        v = np.fromfile(path, dtype=np.uint32).reshape(h, w)
        e = ((v >> 27) & 31).astype(np.int32) - 15 - 9
        s = np.ldexp(1.0, e).astype(np.float32)
        return np.stack([(v & 511) * s, ((v >> 9) & 511) * s, ((v >> 18) & 511) * s], -1)
    return np.fromfile(path, dtype=np.float16).reshape(h, w, 4)[..., :3].astype(np.float32)

def png(path, rgb8):
    h, w, _ = rgb8.shape
    raw = b"".join(b"\0" + rgb8[y].tobytes() for y in range(h))
    def chunk(t, d):
        c = struct.pack(">I", len(d)) + t + d
        return c + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
    open(path, "wb").write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw, 6)) + chunk(b"IEND", b""))

out = sys.argv[1]
os.makedirs(out, exist_ok=True)
print("dump | max (x SDR white) | % px > 1.05 | % px > 2 | p99.9 luminance")
for p in sys.argv[2:]:
    img = load(p)[::4, ::4]  # quarter resolution is plenty for statistics and the preview
    lum = img @ np.array([0.2290, 0.6917, 0.0793], dtype=np.float32)  # Display P3 luminance weights
    print(f"{os.path.basename(p)} | {lum.max():.2f} | {100 * (lum > 1.05).mean():.2f} | {100 * (lum > 2).mean():.3f} | {np.percentile(lum, 99.9):.2f}")
    sdr = np.clip(img, 0, 1) ** (1 / 2.2)
    x = np.clip((lum - 1.0) / 3.0, 0, 1)[..., None]
    heat = np.concatenate([np.clip(x * 3, 0, 1), np.clip(x * 3 - 1, 0, 1), np.clip(x * 3 - 2, 0, 1)], -1)
    side = np.concatenate([sdr, heat], 1)
    png(os.path.join(out, os.path.basename(p) + ".png"), (side * 255).astype(np.uint8))
