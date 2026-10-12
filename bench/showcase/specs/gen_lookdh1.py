# gen_lookdh1.py (2026-10-11): Distant Horizons look-dev stills (and LOD pre-generation: long holds) for the far views.
import json
A = {
 "stony": {"at": [4232, -1312], "refine": "high", "refineRadius": 96},
 "jagged": {"at": [-1456, 472], "refine": "high", "refineRadius": 64},
 "lake": {"at": [-1100, 128], "y": 63},
 "badlands": {"at": [-1008, 2240], "refine": "high", "refineRadius": 96},
}
S = []
def st(name, an, time, pose, y="anchor", hold=3):
    S.append({"type": "still", "name": name, "anchor": an, "time": time, "pose": pose, "y": y, "hold": hold})
st("lake_dh_high", "lake", 12300, [0, 140, 60, 90, 12], "abs", 70)
st("lake_dh_low", "lake", 12350, [10, 66.5, 0, 90, 0], "abs", 4)
st("jagged_dh_west", "jagged", 12300, [0, 6, 0, 90, 3], "ground", 60)
st("jagged_dh_east", "jagged", 23900, [0, 6, 0, -90, 3], "ground", 6)
st("badlands_dh", "badlands", 12100, [0, 30, 0, 90, 8], "anchor", 60)
st("stony_dh_sunset", "stony", 12200, [150, -10, 0, 90, 0], "anchor", 80)
st("stony_dh_side", "stony", 12000, [110, 5, 110, 135, 3], "anchor", 5)
st("stony_dh_morning", "stony", 600, [-150, -10, 0, -90, 0], "anchor", 5)
json.dump({"anchors": A, "items": S}, open("specs/lookdh1.json", "w"), indent=1)
print(len(S))
