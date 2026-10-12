# gen_stills.py (2026-10-11): the photos: the same camera paths as rec2 (air + the ground walks without F3), shot still at
# chosen moments. Run at full resolution and the best preset: run.sh specs/stills.json show-stills --rd=32 --out=native -Dmcopt.shade.preset=ultra
import json
air = json.load(open("specs/rec2-air.json")); ground = json.load(open("specs/rec2-ground.json"))
T = {"lake_sunset": [2, 8], "cherry_high_sunset": [3, 12], "pine_high_sunset": [8], "jungle_sunset": [3, 7], "badlands_sunset": [2],
     "clouds_above_sunset": [4, 12], "cherry_walk_sunset": [5, 9], "cherry_walk_morning": [3, 6], "birch_morning": [2], "flower_morning": [6]}
items = []
for c in air["items"] + ground["items"]:
    if c["name"] in T:
        c = dict(c); c["stills"] = T[c["name"]]; c["pre"] = 0; c["post"] = 0
        items.append(c)
A = dict(air["anchors"]); A.update(ground["anchors"])
json.dump({"anchors": A, "items": items}, open("specs/stills.json", "w"), indent=1)
print(len(items), "clips,", sum(len(v) for v in T.values()), "stills")
