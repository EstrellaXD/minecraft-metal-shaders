# gen_tour.py (2026-10-11): the preset tour (Gael: every preset at native, 1440 and 1080, F3 on, pretty scenes): the same three
# F3 clips for one preset/output pair, names suffixed with it.  gen_tour.py <preset> <output> -> specs/tour-<preset>-<output>.json
import json, sys
P, O = sys.argv[1], sys.argv[2]
A = {
 "lake": {"at": [-1100, 128], "y": 63},
 "cherry": {"at": [688, 168], "refine": "forest", "refineRadius": 64},
 "jagged": {"at": [-1456, 472], "refine": "high", "refineRadius": 64},
}
HOTBAR = ["diamond_sword", "torch", "cherry_log", "bread", "compass", "spyglass", "lantern", "oak_sapling"]
F3 = dict(hud="f3", gui=3, hand="diamond_pickaxe+air", hotbar=HOTBAR, props={"mcbench.bob": "0.06"}, pre=0.8, post=0.6)
sfx = f"_{P}_{O}"
items = [
 dict(type="clip", name="tour_lake" + sfx, anchor="lake", time=12350, y="ground", smooth=1.5,
      keys=[[0, 22, 3.2, 0, 90, -1], [10, -2, 3.6, -1, 90, 1]], **F3),
 dict(type="clip", name="tour_cherry" + sfx, anchor="cherry", time=11900,
      walk={"yaw": 80, "length": 20, "speed": 2.0, "canopy": True, "search": 40, "look": [10, -2], "clear": 2.2}, **F3),
 dict(type="clip", name="tour_peak" + sfx, anchor="jagged", time=23900, y="ground", smooth=1.0,
      keys=[[0, 0, 2.5, 0, -110, 2], [10, -2, 2.8, 2, -70, 0]], **F3),
]
json.dump({"anchors": A, "items": items}, open(f"specs/tour-{P}-{O}.json", "w"), indent=1)
