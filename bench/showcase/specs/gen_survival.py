# gen_survival.py (2026-10-11, Gael: "no estás grabando nada en survival"; more god rays in real forests): survival-mode walks
# with the full HUD (hearts, hunger, XP, hotbar, the hand), some with F3. survival.json: run.sh ... --rec --rd=16 --out=1440 (balanced)
import json
A = {
 "spruce": {"at": [400, -1048], "refine": "forest", "refineRadius": 120},
 "pine": {"at": [-792, 0], "refine": "forest", "refineRadius": 64},
 "jungle": {"at": [-320, 1080], "refine": "forest", "refineRadius": 120},
 "lake": {"at": [-1100, 128], "y": 63},
 "over": {"at": [-1124, 159], "y": 63},
 "birch": {"at": [-352, 512], "refine": "forest", "refineRadius": 96},
 "cherry": {"at": [688, 168], "refine": "forest", "refineRadius": 64},
}
HOT = ["iron_pickaxe", "torch", "oak_planks", "bread", "cooked_beef", "crafting_table", "compass", "water_bucket"]
S = dict(mode="survival", hand="iron_sword+shield", hotbar=HOT, props={"mcbench.bob": "0.06"}, pre=0.8, post=0.6)
def W(yaw, length, look, canopy=True, search=60, speed=1.8):
    return {"yaw": yaw, "length": length, "speed": speed, "canopy": canopy, "search": search, "look": look, "clear": 2.0}
C = []
def clip(name, anchor, time, **kw):
    d = {"type": "clip", "name": name, "anchor": anchor, "time": time}; d.update(S); d.update(kw); C.append(d)
clip("surv_spruce_rays", "spruce", 400, walk=W(-90, 22, [0, -12]))
clip("surv_pine_rays", "pine", 450, walk=W(-90, 22, [0, -10]))
clip("surv_jungle_rays", "jungle", 500, walk=W(-90, 20, [0, -10]))
clip("surv_lake_sunset", "lake", 12450, walk=W(90, 22, [0, 0], canopy=False, search=50))
clip("surv_cherry_sunset", "cherry", 12100, walk=W(90, 22, [6, -2]))
clip("surv_f3_spruce", "spruce", 600, hud="f3", gui=3, walk=W(-70, 22, [-10, -10]))
clip("surv_f3_west", "over", 12450, hud="f3", gui=3, walk=W(90, 20, [0, -1], canopy=False, search=40))
clip("surv_night_torch", "birch", 18000, hand="torch+shield", walk=W(-90, 20, [0, 8], speed=1.6))
json.dump({"anchors": A, "items": C}, open("specs/survival.json", "w"), indent=1)
print(len(C))
