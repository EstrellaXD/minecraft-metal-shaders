#!/usr/bin/env python3
# perf_table.py (2026-10-11): the preset tour's FPS (average while recording, per clip) as a Markdown table, from the newest
# bench-results/*-tour-<preset>-<output>/results.json.   perf_table.py [--json]
import glob, json, os, sys

W = "/Users/gaelquintanilla/Documents/Minecraft Metal Shaders/work/bench-results"
PRESETS = ["performance", "balanced", "quality", "ultra"]
OUTS = [("native", "3024×1964 (native)"), ("1440", "2218×1440"), ("1080", "1662×1080")]
SCENES = [("tour_lake", "Lake sunset"), ("tour_cherry", "Cherry forest"), ("tour_peak", "Mountain sunrise")]
data = {}
for p in PRESETS:
    for o, _ in OUTS:
        runs = sorted(glob.glob(f"{W}/*-tour-{p}-{o}/results.json"))
        if not runs:
            continue
        r = json.load(open(runs[-1]))
        for c in r.get("cine", {}).get("clips", []):
            for s, _ in SCENES:
                if c["name"].startswith(s + "_"):
                    data[(p, o, s)] = c.get("avgFps")
if "--json" in sys.argv:
    print(json.dumps({f"{p}/{o}/{s}": v for (p, o, s), v in data.items()}, indent=1))
    sys.exit()
print("| Preset | Output | " + " | ".join(n for _, n in SCENES) + " |")
print("|---|---|" + "---|" * len(SCENES))
for p in PRESETS:
    for o, on in OUTS:
        vals = [data.get((p, o, s)) for s, _ in SCENES]
        if all(v is None for v in vals):
            continue
        print(f"| {p.capitalize()} | {on} | " + " | ".join("–" if v is None else f"{v:.0f}" for v in vals) + " |")
