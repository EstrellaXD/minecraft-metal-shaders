# gen_rec2.py (2026-10-11): the recording batches after show-rec1t (Gael: high chunks for the aerial shots, 16 chunks and
# F3 for the "playing" shots on the ground).
#   rec2-air.json    -> run.sh ... --rec --rd=32 --out=1440 -Dmcopt.shade.preset=quality
#   rec2-ground.json -> run.sh ... --rec --rd=16 --out=1440 (balanced, his settings)
import json
A = {
 "lake": {"at": [-1100, 128], "y": 63},
 "cherry": {"at": [688, 168], "refine": "forest", "refineRadius": 64},
 "jungle": {"at": [-320, 1080]},
 "birch": {"at": [-352, 512], "refine": "forest", "refineRadius": 96},
 "pine": {"at": [-792, 0], "refine": "forest", "refineRadius": 64},
 "badlands": {"at": [-1008, 2240], "refine": "high", "refineRadius": 96},
 "flower": {"at": [1152, -1032], "refine": "forest", "refineRadius": 64},
 "meadow": {"at": [-384, -440]},
}
BOB = {"mcbench.bob": "0.06"}
HOTBAR = ["diamond_sword", "torch", "cherry_log", "bread", "compass", "spyglass", "lantern", "oak_sapling"]
def mk(items):
    return {"anchors": A, "items": items}
air, ground = [], []
def clip(lst, name, anchor, time, **kw):
    d = {"type": "clip", "name": name, "anchor": anchor, "time": time, "pre": 0.8, "post": 0.6}
    d.update(kw); lst.append(d)
# ---- aerial / far views (32 chunks)
clip(air, "lake_sunset", "lake", 12350, y="ground", smooth=1.5, keys=[[0, 26, 3.2, 0, 90, -1], [15, -12, 4.0, -2, 90, 1]], stills=[8])
clip(air, "cherry_high_sunset", "cherry", 12200, keys=[[0, 34, 24, -24, 90, 9], [14, 34, 22, 20, 96, 8]], stills=[7])
clip(air, "pine_high_sunset", "pine", 12100, keys=[[0, 90, 30, -10, 90, 6], [15, 40, 26, 6, 90, 5]], stills=[8])
clip(air, "jungle_sunset", "jungle", 12000, keys=[[0, 46, 6, -4, 92, -1], [13, 30, 7, 2, 86, -2]], stills=[6])
clip(air, "badlands_sunset", "badlands", 12100, y="ground", smooth=1.0, keys=[[0, 0, 4, 0, 62, 5], [14, -6, 5, 3, 116, 4]], stills=[7])
clip(air, "clouds_above_sunset", "lake", 12300, y="abs", keys=[[0, 40, 300, 0, 90, 6], [16, -60, 300, 10, 90, 4]], stills=[8])
clip(air, "storm_lake", "lake", 6000, weather="thunder", y="ground", smooth=1.5,
     keys=[[0, 10, 3, 0, 78, 0], [14, 0, 3, 2, 102, -3]],
     events=[[2.5, "execute positioned {ax} {ay} {az} run summon minecraft:lightning_bolt ~-70 ~ ~10"],
             [8, "execute positioned {ax} {ay} {az} run summon minecraft:lightning_bolt ~-55 ~ ~-25"],
             [11.5, "execute positioned {ax} {ay} {az} run summon minecraft:lightning_bolt ~-90 ~ ~5"]])
# ---- on the ground, as if playing (16 chunks); F3 ones with a hotbar and a pickaxe in hand
W = lambda yaw, length, look, canopy=True, search=40: {"yaw": yaw, "length": length, "speed": 2.0, "canopy": canopy, "search": search, "look": look, "clear": 2.2}
clip(ground, "cherry_walk_sunset", "cherry", 12000, props=BOB, walk=W(90, 26, [8, -3]), stills=[5])
clip(ground, "cherry_walk_morning", "cherry", 300, props=BOB, walk=W(-90, 24, [-8, -4]), stills=[6])
clip(ground, "birch_morning", "birch", 450, props=BOB, walk=W(-90, 26, [0, -8], search=48), stills=[6])
clip(ground, "flower_morning", "flower", 600, props=BOB, walk=W(-90, 22, [0, -3], canopy=False), stills=[5])
F3 = dict(hud="f3", gui=3, hand="diamond_pickaxe+air", hotbar=HOTBAR, props=BOB)
clip(ground, "f3_cherry_sunset", "cherry", 11900, walk=W(80, 26, [10, -2]), **F3)
clip(ground, "f3_birch_morning", "birch", 500, walk=W(-80, 26, [-6, -6], search=56), **F3)
clip(ground, "f3_meadow_sunset", "meadow", 12000, walk=W(90, 26, [0, -2], canopy=False), **F3)
json.dump(mk(air), open("specs/rec2-air.json", "w"), indent=1)
json.dump(mk(ground), open("specs/rec2-ground.json", "w"), indent=1)
print(len(air), "air,", len(ground), "ground")
