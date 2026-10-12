# gen_lookdev1.py (2026-10-11): look-dev stills at golden hours for the showcase scenes (anchors from show-scout1).
import json
A = {
 "cherry": {"at": [688, 168], "refine": "forest", "refineRadius": 64},
 "dark": {"at": [-352, 512], "refine": "forest", "refineRadius": 96},
 "obirch": {"at": [400, -528], "refine": "forest", "refineRadius": 128},
 "spruce": {"at": [400, -1048], "refine": "forest", "refineRadius": 96},
 "pine": {"at": [-792, 0], "refine": "forest", "refineRadius": 64},
 "jagged": {"at": [-1456, 472], "refine": "high", "refineRadius": 64},
 "frozen": {"at": [-1440, 200], "refine": "high", "refineRadius": 64},
 "slopes": {"at": [-1464, 232]},
 "meadow": {"at": [-384, -440]},
 "lake": {"at": [-1100, 128], "y": 63},
 "badlands": {"at": [-1008, 2240], "refine": "high", "refineRadius": 96},
 "jungle": {"at": [-320, 1080]},
 "flower": {"at": [1152, -1032], "refine": "forest", "refineRadius": 64},
}
S = []
def st(name, an, time, pose, y="ground", weather="clear"):
    S.append({"type": "still", "name": name, "anchor": an, "time": time, "pose": pose, "y": y, "weather": weather})
st("cherry_sunset_ground", "cherry", 12000, [0, 1.62, 0, 90, -4])
st("cherry_morning_ground", "cherry", 300, [0, 1.62, 0, -90, -4])
st("cherry_sunset_high", "cherry", 12200, [30, 22, 0, 90, 8], "anchor")
st("dark_morning", "dark", 400, [0, 1.62, 0, -90, -12])
st("dark_morning_side", "dark", 600, [0, 1.62, 0, 180, -5])
st("obirch_morning", "obirch", 400, [0, 1.62, 0, -90, -10])
st("spruce_morning", "spruce", 300, [0, 1.62, 0, -90, -8])
st("pine_sunset", "pine", 11900, [0, 1.62, 0, 90, -8])
st("pine_sunset_high", "pine", 12100, [60, 30, 0, 90, 5], "anchor")
st("jagged_sunset", "jagged", 12300, [0, 3, 0, 90, 4])
st("jagged_sunrise", "jagged", 23800, [0, 3, 0, -90, 4])
st("frozen_sunset", "frozen", 12300, [0, 3, 0, 90, 4])
st("meadow_sunset", "meadow", 12000, [0, 1.62, 0, 90, -3])
st("lake_sunset", "lake", 12400, [0, 72.6, 0, 90, 6], "abs")
st("badlands_sunset", "badlands", 12100, [0, 4, 0, 90, 5])
st("jungle_sunset", "jungle", 12000, [40, 6, 0, 90, -2], "anchor")
st("flower_morning", "flower", 600, [0, 1.62, 0, -90, -3])
st("clouds_above_sunset", "lake", 12300, [0, 330, 0, 90, 8], "abs")
st("meadow_storm", "meadow", 6000, [0, 1.62, 0, 90, -2], "ground", "thunder")
# the aurora needs a property: a one-pose clip with a still
S.append({"type": "clip", "name": "aurora_slopes", "anchor": "slopes", "time": 18000, "y": "ground",
          "props": {"mcopt.shade.aurora": "always"}, "keys": [[0, 0, 3, 0, 180, -28], [1, 0, 3, 0, 180, -28]], "stills": [0.5]})
json.dump({"anchors": A, "items": S}, open("specs/lookdev1.json", "w"), indent=1)
print(len(S), "items")
