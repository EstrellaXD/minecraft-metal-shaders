# gen_sunset3.py (2026-10-11, Gael's references): 1) the old bench "sunset_west" view (the overview point over the lake,
# looking west at sunset; his favourite); 2) a red sunset over a snowy spruce forest from a hill (his photo); 3) god rays in
# dense forests (giant spruce taiga, jungle), walking toward the morning sun.
# sunset3.json: run.sh ... --rec --rd=32 --out=1440 -Dmcopt.shade.preset=quality
import json
A = {
 "over": {"at": [-1124, 159], "y": 63},          # the bench overview point (feet y 96.38 -> eye 98)
 "snowy": {"at": [-72, -1624], "refine": "high", "refineRadius": 96},
 "grove": {"at": [-1192, -1760], "refine": "high", "refineRadius": 96},
 "spruce": {"at": [400, -1048], "refine": "forest", "refineRadius": 120},
 "jungle": {"at": [-320, 1080], "refine": "forest", "refineRadius": 120},
}
C = []
def clip(name, anchor, time, **kw):
    d = {"type": "clip", "name": name, "anchor": anchor, "time": time, "pre": 0.8, "post": 0.6}
    d.update(kw); C.append(d)
# 1) sunset_west, as in the tests (12400), a lower sun (12620) and a higher drift
clip("west_sunset", "over", 12400, y="abs", keys=[[0, 0.5, 98, 0, 90, 2], [16, -14, 96.5, 2, 92, 3]], stills=[1, 10])
clip("west_sunset_low", "over", 12620, y="abs", keys=[[0, 6, 99, -6, 86, 1], [16, -8, 98, 6, 94, 2]], stills=[2, 12])
clip("west_sunset_high", "over", 12450, y="abs", keys=[[0, 20, 118, 10, 90, 9], [16, 4, 112, 0, 90, 7]], stills=[8])
# 2) red sunset over snowy spruce forests, from a hill
clip("snowy_red_sunset", "snowy", 12780, y="ground", smooth=1.0, keys=[[0, 0, 4, 0, 80, 4], [16, -2, 5, 2, 100, 3]], stills=[2, 12])
clip("grove_red_sunset", "grove", 12800, y="ground", smooth=1.0, keys=[[0, 0, 4, 0, 82, 4], [16, -2, 5, 2, 98, 3]], stills=[6])
clip("snowy_sunset", "snowy", 12500, y="ground", smooth=1.0, keys=[[0, 0, 6, 0, 75, 5], [16, -3, 6.5, 3, 105, 4]], stills=[8])
# 3) god rays in dense forests, walking toward the low morning sun (east), looking a little up
BOB = {"mcbench.bob": "0.05"}
W = lambda yaw, length, look: {"yaw": yaw, "length": length, "speed": 1.6, "canopy": True, "search": 60, "look": look, "clear": 2.0}
clip("spruce_rays_morning", "spruce", 350, props=BOB, walk=W(-90, 24, [0, -12]), stills=[4, 10])
clip("spruce_rays_side", "spruce", 600, props=BOB, walk=W(-60, 24, [-25, -10]), stills=[6])
clip("jungle_rays_morning", "jungle", 450, props=BOB, walk=W(-90, 22, [0, -12]), stills=[5, 10])
json.dump({"anchors": A, "items": C}, open("specs/sunset3.json", "w"), indent=1)
print(len(C))
