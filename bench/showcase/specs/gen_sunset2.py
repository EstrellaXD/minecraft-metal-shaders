# gen_sunset2.py (2026-10-11, Gael: more sunsets; the lake from higher up; a lake reflecting mountains; the sun lower and red;
# auroras). sunset2.json: run.sh ... --rec --rd=32 --out=1440 -Dmcopt.shade.preset=quality
import json
A = {
 "lake": {"at": [-1100, 128], "y": 63},
 "jagged": {"at": [-1456, 472], "refine": "high", "refineRadius": 64},
 "peaklake": {"at": [-1480, 472], "refine": "water", "toward": [1, 0], "min": 50, "max": 280, "side": 80},
 "frozen": {"at": [-1440, 200], "refine": "high", "refineRadius": 64},
 "frozenlake": {"at": [-1440, 200], "refine": "water", "toward": [1, 0], "min": 50, "max": 280, "side": 80},
}
C = []
def clip(name, anchor, time, **kw):
    d = {"type": "clip", "name": name, "anchor": anchor, "time": time, "pre": 0.8, "post": 0.6}
    d.update(kw); C.append(d)
clip("lake_high_sunset", "lake", 12500, y="abs", keys=[[0, 30, 77, -6, 90, 6], [16, 2, 74, 0, 90, 4]], stills=[2, 10])
clip("lake_wide_sunset", "lake", 12560, y="abs", keys=[[0, 60, 103, 20, 96, 12], [16, 32, 100, 8, 88, 10]], stills=[3, 12])
clip("lake_red_sunset", "lake", 12750, y="ground", smooth=1.5, keys=[[0, 24, 6, 2, 90, 0], [16, 6, 5, 0, 92, -1]], stills=[4, 12])
LOOK = [0, -25, 0]  # a bit below the summit: the mountain mid-frame, its reflection under it
clip("peak_lake_sunset", "peaklake", 12600, y="ground", smooth=1.5, lookAnchor="jagged", lookAt=LOOK,
     keys=[[0, 0, 4, -14], [16, -10, 4.5, 14]], stills=[3, 12])
clip("peak_lake_red", "peaklake", 12780, y="ground", smooth=1.5, lookAnchor="jagged", lookAt=LOOK,
     keys=[[0, -6, 3.5, 10], [16, -14, 4, -10]], stills=[8])
clip("frozen_lake_sunset", "frozenlake", 12650, y="ground", smooth=1.5, lookAnchor="frozen", lookAt=LOOK,
     keys=[[0, 0, 4, -12], [16, -8, 4.5, 12]], stills=[6])
json.dump({"anchors": A, "items": C}, open("specs/sunset2.json", "w"), indent=1)
print(len(C))
