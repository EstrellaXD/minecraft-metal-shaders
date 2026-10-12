#!/usr/bin/env python3
# montage.py (2026-10-11): the showcase edit. Takes a JSON edit list and builds one video with ffmpeg: each shot trimmed from
# its clip, cross-faded into the next, an optional caption (PNG drawn here with PIL: ffmpeg has no drawtext on this Mac) faded
# in and out over it, optional title cards. Output 1920 wide, H.264 high, 60 fps, no audio.
#   montage.py edit.json out.mp4
# edit.json: {"width": 1920, "height": 1246, "fade": 0.6, "shots": [{"clip": "path.mp4", "start": 1.0, "dur": 5.0,
#   "caption": "Sunset reflections", "sub": "Balanced · 1440p"}, ...], "title": {"text": "...", "sub": "...", "dur": 3.5},
#   "end": {"text": "...", "sub": "...", "dur": 4}}
import json, os, subprocess, sys, tempfile
from PIL import Image, ImageDraw, ImageFont, ImageFilter

FONT = "/System/Library/Fonts/Avenir Next.ttc"


def font(size, bold=False):
    # Avenir Next.ttc: 0 Bold, 2 Demi Bold, 7 Regular, 5 Medium (indices on macOS 26)
    for idx in ([2, 0] if bold else [5, 7]):
        try:
            return ImageFont.truetype(FONT, size, index=idx)
        except Exception:
            pass
    return ImageFont.load_default()


def text_png(path, w, h, lines, pos="lower-left", card=0):
    """lines: [(text, size, bold)]; a soft shadow under the text; card: the alpha of a dark full-frame veil (title cards)."""
    im = Image.new("RGBA", (w, h), (0, 0, 0, card))
    layer = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    fonts = [(t, font(s, b)) for t, s, b in lines]
    sizes = [d.textbbox((0, 0), t, font=f) for t, f in fonts]
    heights = [b[3] - b[1] for b in sizes]
    gap = int(h * 0.018)
    total = sum(heights) + gap * (len(lines) - 1)
    if pos == "center":
        y = (h - total) // 2
    else:
        y = h - int(h * 0.08) - total
    shadow = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    sd = ImageDraw.Draw(shadow)
    for (t, f), b, hh in zip(fonts, sizes, heights):
        tw = b[2] - b[0]
        x = (w - tw) // 2 if pos == "center" else int(w * 0.045)
        sd.text((x - b[0], y - b[1] + 3), t, font=f, fill=(0, 0, 0, 200))
        d.text((x - b[0], y - b[1]), t, font=f, fill=(255, 255, 255, 255))
        y += hh + gap
    shadow = shadow.filter(ImageFilter.GaussianBlur(6))
    im = Image.alpha_composite(im, shadow)
    im = Image.alpha_composite(im, layer)
    im.save(path)


def main():
    edit = json.load(open(sys.argv[1]))
    out = sys.argv[2]
    W, H = edit.get("width", 1920), edit.get("height", 1246)
    F = edit.get("fade", 0.6)
    tmp = tempfile.mkdtemp(prefix="montage-", dir=edit.get("tmp"))
    inputs, filters = [], []
    segs = []  # (label, duration)
    n = 0

    def add_input(args):
        nonlocal n
        inputs.extend(args)
        n += 1
        return n - 1

    def card(spec, name):
        png = os.path.join(tmp, name + ".png")
        lines = [(spec["text"], int(H * 0.085), True)]
        if spec.get("sub"):
            lines.append((spec["sub"], int(H * 0.032), False))
        if spec.get("sub2"):
            lines.append((spec["sub2"], int(H * 0.026), False))
        text_png(png, W, H, lines, "center", card=spec.get("veil", 90))
        return png

    title = edit.get("title")
    shots = edit["shots"]
    for i, s in enumerate(shots):
        k = add_input(["-ss", str(s.get("start", 0)), "-t", str(s["dur"]), "-i", s["clip"]])
        lbl = f"v{i}"
        chain = f"[{k}:v]scale={W}:{H}:flags=lanczos,setsar=1,fps=60,format=yuv420p,setpts=PTS-STARTPTS"
        cap = s.get("caption")
        if (i == 0 and title) or cap:
            png = card(title, "title") if (i == 0 and title) else None
            if png is None:
                png = os.path.join(tmp, f"cap{i}.png")
                lines = [(cap, int(H * 0.042), True)]
                if s.get("sub"):
                    lines.append((s["sub"], int(H * 0.026), False))
                text_png(png, W, H, lines, "lower-left")
            c = add_input(["-loop", "1", "-t", str(s["dur"]), "-i", png])
            a0 = s.get("capIn", 0.5 if not (i == 0 and title) else 0.0)
            a1 = (title.get("dur", 3.5) if (i == 0 and title) else s.get("capOut", s["dur"] - 0.8))
            filters.append(f"[{c}:v]format=rgba,fade=t=in:st={a0}:d=0.5:alpha=1,fade=t=out:st={a1}:d=0.6:alpha=1,setpts=PTS-STARTPTS[c{i}]")
            filters.append(f"{chain}[b{i}]")
            filters.append(f"[b{i}][c{i}]overlay=0:0:shortest=1,format=yuv420p[{lbl}]")
        else:
            filters.append(f"{chain}[{lbl}]")
        segs.append((lbl, s["dur"]))
    end = edit.get("end")
    if end:
        # the end card over the last frames of a still-ish clip: a separate input looped
        png = card(end, "end")
        last = shots[-1]
        k = add_input(["-ss", str(last.get("start", 0) + last["dur"] - 0.05), "-t", "0.05", "-i", last["clip"]])
        c = add_input(["-loop", "1", "-t", str(end.get("dur", 4)), "-i", png])
        filters.append(f"[{k}:v]scale={W}:{H}:flags=lanczos,setsar=1,format=yuv420p,tpad=stop_mode=clone:stop_duration={end.get('dur', 4)},fps=60,setpts=PTS-STARTPTS[eb]")
        filters.append(f"[{c}:v]format=rgba,fade=t=in:st=0.2:d=0.8:alpha=1,setpts=PTS-STARTPTS[ec]")
        filters.append(f"[eb][ec]overlay=0:0:shortest=1,format=yuv420p,trim=duration={end.get('dur', 4)}[vend]")
        segs.append(("vend", end.get("dur", 4)))
    # cross-fades
    cur, t = segs[0][0], segs[0][1]
    for j, (lbl, d) in enumerate(segs[1:], 1):
        o = f"x{j}"
        filters.append(f"[{cur}][{lbl}]xfade=transition=fade:duration={F}:offset={t - F:.3f}[{o}]")
        cur, t = o, t + d - F
    filters.append(f"[{cur}]fade=t=out:st={t - 0.8:.3f}:d=0.8[out]")
    cmd = ["ffmpeg", "-hide_banner", "-loglevel", "error", "-y"] + inputs + ["-filter_complex", ";".join(filters), "-map", "[out]",
           "-c:v", "libx264", "-preset", "slow", "-crf", str(edit.get("crf", 18)), "-profile:v", "high", "-pix_fmt", "yuv420p",
           "-r", "60", "-color_primaries", "bt709", "-color_trc", "bt709", "-colorspace", "bt709", "-movflags", "+faststart", "-an", out]
    subprocess.run(cmd, check=True)
    print(f"{out}: {t:.1f} s")


main()
