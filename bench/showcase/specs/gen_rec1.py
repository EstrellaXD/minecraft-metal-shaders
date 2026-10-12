# gen_rec1.py (2026-10-11): first recording batch (no DH): the day scenes picked from show-look1.
import json, sys
A = {
 "lake": {"at": [-1100, 128], "y": 63},
 "cherry": {"at": [688, 168], "refine": "forest", "refineRadius": 64},
 "jungle": {"at": [-320, 1080]},
 "birch": {"at": [-352, 512], "refine": "forest", "refineRadius": 96},
 "pine": {"at": [-792, 0], "refine": "forest", "refineRadius": 64},
 "badlands": {"at": [-1008, 2240], "refine": "high", "refineRadius": 96},
 "flower": {"at": [1152, -1032], "refine": "forest", "refineRadius": 64},
}
BOB = {"mcbench.bob": "0.06"}
C = []
def clip(name, anchor, time, **kw):
    d = {"type": "clip", "name": name, "anchor": anchor, "time": time, "pre": 0.8, "post": 0.6}
    d.update(kw); C.append(d)
clip("lake_sunset", "lake", 12350, y="ground", smooth=1.5,
     keys=[[0, 26, 3.2, 0, 90, -1], [15, -12, 4.0, -2, 90, 1]], stills=[0, 8])
clip("cherry_walk_sunset", "cherry", 12000, props=BOB,
     walk={"yaw": 90, "length": 28, "speed": 2.0, "canopy": True, "search": 36, "look": [8, -3]}, stills=[5])
clip("cherry_high_sunset", "cherry", 12200, keys=[[0, 34, 24, -24, 90, 9], [14, 34, 22, 20, 96, 8]], stills=[7])
clip("cherry_walk_morning", "cherry", 300, props=BOB,
     walk={"yaw": -90, "length": 24, "speed": 2.0, "canopy": True, "search": 36, "look": [-8, -4]}, stills=[6])
clip("jungle_sunset", "jungle", 12000, keys=[[0, 46, 6, -4, 92, -1], [13, 30, 7, 2, 86, -2]], stills=[6])
clip("birch_morning", "birch", 450, props=BOB,
     walk={"yaw": -90, "length": 26, "speed": 2.0, "canopy": True, "search": 48, "look": [0, -8]}, stills=[6])
clip("pine_high_sunset", "pine", 12100, keys=[[0, 90, 30, -10, 90, 6], [15, 40, 26, 6, 90, 5]], stills=[8])
clip("badlands_sunset", "badlands", 12100, y="ground", smooth=1.0,
     keys=[[0, 0, 4, 0, 62, 5], [14, -6, 5, 3, 116, 4]], stills=[7])
clip("flower_morning", "flower", 600, props=BOB,
     walk={"yaw": -90, "length": 22, "speed": 2.0, "search": 40, "look": [0, -3]}, stills=[5])
clip("clouds_above_sunset", "lake", 12300, y="abs", keys=[[0, 40, 300, 0, 90, 6], [16, -60, 300, 10, 90, 4]], stills=[8])
clip("storm_lake", "lake", 6000, weather="thunder", y="ground", smooth=1.5,
     keys=[[0, 10, 3, 0, 78, 0], [14, 0, 3, 2, 102, -3]], stills=[],
     events=[[2.5, "execute positioned {ax} {ay} {az} run summon minecraft:lightning_bolt ~-70 ~ ~10"],
             [8, "execute positioned {ax} {ay} {az} run summon minecraft:lightning_bolt ~-55 ~ ~-25"],
             [11.5, "execute positioned {ax} {ay} {az} run summon minecraft:lightning_bolt ~-90 ~ ~5"]])
only = sys.argv[1:]  # optional clip names
if only: C = [c for c in C if c["name"] in only]
json.dump({"anchors": A, "items": C}, open("specs/rec1.json", "w"), indent=1)
print(len(C), "clips,", sum(c["keys"][-1][0] if "keys" in c else 12 for c in C), "s of motion")
