# gen_night.py (2026-10-11): night scenes: aurora over the snowy slopes, the starry lake, a torch-lit walk in the birch forest.
# Run with few clouds: run.sh specs/night.json show-night --rec --rd=24 --out=1440 -Dmcopt.shade.preset=quality -Dmcopt.shade.clouds.coverage=0.15
import json
A = {
 "slopes": {"at": [-1464, 232]},
 "lake": {"at": [-1100, 128], "y": 63},
 "birch": {"at": [-352, 512], "refine": "forest", "refineRadius": 96},
 "jagged": {"at": [-1456, 472], "refine": "high", "refineRadius": 64},
 "peaklake": {"at": [-1480, 472], "refine": "water", "toward": [1, 0], "min": 50, "max": 280, "side": 80},
}
items = [
 dict(type="clip", name="aurora_slopes", anchor="slopes", time=18000, y="ground", smooth=1.0, props={"mcopt.shade.aurora": "always"},
      keys=[[0, 0, 3, 0, 170, -4], [14, 0, 3.5, 2, 190, -32]], stills=[7, 14]),
 dict(type="clip", name="night_lake_stars", anchor="lake", time=17500, y="ground", smooth=1.5,
      keys=[[0, 20, 3, 0, -120, -10], [14, 4, 3.5, 4, -60, -18]], stills=[7]),
 dict(type="clip", name="aurora_peak_lake", anchor="peaklake", time=18000, y="ground", smooth=1.5, props={"mcopt.shade.aurora": "always"},
      keys=[[0, 0, 4, 0, 160, -14], [16, -8, 4.5, 6, 120, -20]], stills=[4, 12]),
 dict(type="clip", name="aurora_lake", anchor="lake", time=18000, y="ground", smooth=1.5, props={"mcopt.shade.aurora": "always"},
      keys=[[0, 10, 3, 0, 200, -12], [16, 0, 3.5, 4, 160, -18]], stills=[8]),
 dict(type="clip", name="night_torch_walk", anchor="birch", time=18000, hand="torch+soul_lantern", props={"mcbench.bob": "0.06"},
      walk={"yaw": -90, "length": 22, "speed": 2.0, "canopy": True, "search": 48, "look": [0, 6], "clear": 2.2}, stills=[5]),
]
json.dump({"anchors": A, "items": items}, open("specs/night.json", "w"), indent=1)
print(len(items))
