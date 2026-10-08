# M3.3 shadow passes (branch `m3.3-shadow` from `m3-int` 397e241)

## M3.3 Status (2026-10-08 11:45, resumed session, merged in m3-int) - DONE

Run 3 (`bench-results/20261008-110711-sh3-shadow`, jar 88326c8, AC; `sh3-framespread.txt`, `sh3-tintrounds.txt`):
- Coloured shadows, 5 clean rounds (M3.2 path / new path / none): **M3.2 +0.142 ms, new +0.111 ms** (median +0.126 / +0.104):
  the tight rectangle + sub-rectangle pass save ~0.03 ms; the feature stays above the 0.1 ms budget by a hair. Defaults kept.
- Far spread, route ABBA in session: spike (heaviest frame of every 4 minus the others) off 0.50 / 0.32 ms, on 0.035 / 0.15 ms;
  whole-frame GPU off 10.63 / 10.77, on 10.83 / 10.82 ms (+0.05-0.2: within this run's drift; runs 1-2 had no average change).
- Far shots with the clouds paused (`sh_far_{eve,strafe,turn}_{off,on}`): the difference image holds only TAAU edge jitter and
  water waves (mean 1.1/255 still): same shadows, no seam, no missing part.
- Run 5 (recording strafe/turn off vs on, `sh5-rec`) is in the resumed chain; the release does not depend on it.
Defaults: `shadowFarSpread` true, `tintTight` true, `tintSub` true.

## M3.3 Status (2026-10-08 11:05)

- **Resumed 10:55** after the 10:52 pause (usage limit); run 3 had never started. Code head 88326c8 (build OK, `validate.sh`
  ALL OK), jar `jars/m3.3-shadow-88326c8.jar`.
- **Done:** (1) far spread built and measured in runs 1-2 (spike 0.2-0.6 ms -> 0.05-0.15 ms, average GPU unchanged, jitter
  -15-35 %, table in section 1); (3) blob verified against M2.1 (section 3, no code change).
- **Run 2's coloured-shadow rounds:** rounds 1-2 clean (feature +0.044 / +0.052 ms for M3.2, +0.032 / +0.049 for the new
  path, `tintrounds.py`), rounds 3-4 contaminated (another process at 377-456 % CPU during them).
- **Running:** run 3 (`run3.sh`, label `sh3-shadow`, queued 10:56 behind 2 other agents): far spread spin x4 (CPU listing
  with pooled lists), coloured shadows 5 rounds x 10 s (M3.2 / new / none), far shots with the clouds paused.
- **Next:** run 3 prices -> defaults; far-spread release images (clouds paused); maybe a recording run (strafe off/on video);
  Spanish summary; final report.

Plan (in this order; every new behaviour behind a flag whose "off" = M3.2 exactly):

1. **Far cascade spike** (SHADOW.md §9 "Far cascade spike": 250-460k quads redrawn every 4th frame, +0.3 / +0.5 ms on
   that frame).
2. **Coloured-shadow cost near glass** (WEATHER.md "What is left" 1: ~0.18 ms dry, budget ~0.1).
3. **Blob shadow in caves**: the blob must be exactly M2.1's where our shadows can't reach, gone where they do.

Not in this round: M4 (ray tracing), FT1 (DH lit by our shaders).

## 1. Far cascade spread (`mcopt.shade.shadowFarSpread`)

M3.2 redraws the whole far cascade (half-width 128 blocks, 250-460k quads with light-space casters) every 4th frame, so
one frame in four carries +0.3-0.5 ms of GPU (`framespread.py` on an M3.2 run: per-frame GPU by `submitIndex % 4`, flight
10.39 / 10.42 / 10.47 / **10.83** ms, spin 11.24 / 11.27 / 11.33 / **11.51**).

What the flag does (read every frame; `false` = M3.2 exactly):

- The map gets a twin of the same shape (`mc_shadow_spread_init`, made on first use: +32 MB at 2048 Depth32, both slices,
  because the near cascade is drawn into whichever map is bound).
- A new far map is **fitted once** (one light direction, one texel-snapped origin: `ShadowPass.farBack`) and drawn in
  `shadowFarEvery` (4) parts on 4 consecutive frames, into the far slice of the map receivers are **not** reading
  (`mc_shadow_render_ex` target 1; the first part clears the slice, the others load it). Parts are section columns on a
  lattice (`ShadowCasters.part`: x + 2z mod 4, so every 2x2 columns hold one of each part): each frame draws a quarter of
  the casters, spread evenly over the map.
- On the 4th frame the maps swap before the near cascade is encoded (`mc_shadow_swap`): the last part goes into the
  now-bound map, receivers read the complete map with its own (copied) fit from that frame on, and the next map starts the
  frame after. `ShadeShadows.afterRender` already re-points texture(16) when `ShadowPass.texture()` changes.
- No seams: all parts of a map share one fit, so a texel means the same world spot in every part (each part's matrix is
  the fit re-expressed against that frame's camera, in doubles, as M3.2 does every frame for the cached far map). No
  swimming: the fit is snapped to whole texels exactly like M3.2's. A partly drawn map is never bound.
- Kept from M3.2: a camera jump (more than 16 blocks from the bound map's centre) or a light jump of more than 1 degree
  (`/time set`, sun to moon) redraws the far map whole in that frame. The day's 0.1-degree light steps no longer force a
  whole redraw: the next map picks them up (the bound one is at most ~8 frames older than the light; < 0.01 degree).
- Cost model: the same casters per 4 frames as M3.2, plus a depth load + store of the far slice on 3 of the 4 parts.

### Measured (in-session A/B, balanced, DRS off, AC power)

Method: MiscShots `-Dmcbench.ab` phases switching `mcopt.shade.shadowFarSpread` live, alternating off/on at one pose
(overview spin 360 degrees, and the perf route spin + flight), `notes/m33-2026-10-08/shadow/framespread.py <run>`:
per-frame GPU = end-to-end per command buffer (one per frame, GPU-bound); "spike" = the heaviest `submitIndex % 4` group
minus the mean of the other three; "jit" = mean |frame-to-frame change| (GPU and CPU frame times). Saved tables:
`notes/m33-2026-10-08/shadow/sh1-framespread.txt`, `sh2-framespread.txt`.

| | run 1 off | run 1 on | run 2 off | run 2 on |
|---|---|---|---|---|
| spin: whole-frame GPU ms (mean of phases) | 11.351 | 11.303 | 12.014 | 11.984 |
| spin: every-4th-frame spike, ms | 0.25, 0.20 | 0.04, 0.11 | 0.23, 0.51, 0.20 | 0.16, 0.05, 0.11 |
| spin: GPU / CPU frame-to-frame jitter, ms | 0.22 / 0.27 | 0.17 / 0.19 | 0.30 / 0.63 | 0.24 / 0.56 |
| spin: 1 % low fps | 69.6, 75.3 | 75.7, 75.8 | 69.1, 70.7, 70.5 | 71.5, 72.2, 68.7 |
| route: whole-frame GPU ms | 10.882 | 10.917 | 10.474 | 10.484 |
| route: spike, ms | 0.33, 0.30 | 0.10, 0.08 | 0.59, 0.34 | 0.06, 0.05 |
| route: GPU / CPU jitter, ms | 0.26 / 0.30 | 0.23 / 0.25 | 0.33 / 0.35 | 0.21 / 0.24 |
| route: 1 % low fps | 74.2, 76.1 | 73.5, 74.9 | 77.1, 78.1 | 80.0, 79.3 |

Plain words: the frame that redrew the whole far cascade was 0.2-0.6 ms slower than its neighbours; with the spread it is
0.05-0.15 ms (what is left is other periodic work: cloud passes every 4th / 8th frame). The average GPU time per frame does
not change (differences -0.05 .. +0.04 ms, inside the session noise), frame-to-frame jitter drops 15-35 %, 1 % lows are
equal or up to +3 fps. The far map is drawn with the same casters (overview spin: 310.5k quads whole = 4 x 77.6k parts).

CPU (render thread, `-Dmcopt.shade.shadowStats`): the caster listing ran every frame instead of every 4th: 0.22 -> 0.42
ms/frame in run 1; 0d872ba walks only the part's sections in far-only regions (run 2: 0.25-0.28 -> 0.36-0.43); 88326c8 reuses
the draw lists and looks a region's list up only among its own (it scanned all lists per region: quadratic). Run 3 checks it.

Looks (frozen ticks, same pose and time, off vs on: `sh_far_eve_*` still, `sh_far_strafe_*` strafing 8 blocks/s,
`sh_far_turn_*` turning 30 degrees/s, shot mid-motion): the terrain shadows are the same; no seam, no missing part, no
swimming. In runs 1-2 the volumetric clouds still moved between the shots (they follow real time), so their shadows differ;
run 3 pauses them for a clean difference image.

## 2. Coloured shadows near glass

Pricing tools (all read every frame):

| Flag | Default | Meaning |
|---|---|---|
| `mcopt.shade.tintTight` | true | the tint rectangle (receivers outside skip the tint lookup) from the box of the section's translucent **blocks** (scanned once per section, cached by its translucent geometry, rescanned every ~240 frames), not the whole 16^3 section; sections holding only water are not drawn. Only with the water module on (water is a fluid, not a block model). false = M3.2 |
| `mcopt.shade.tintSub` | true | the tint pass clears, draws and stores only that rectangle, in the map's top-left corner at the map's own texel size (`ShadowPass.tintSubMatrix`); receivers map their light-space position into it when `ssr.w = 2` (`weather_surface.metal shadeShadowTint`). false = M3.2 (the whole 1024^2 map every frame) |
| `mcopt.shade.tintDev` | 0 | pricing bits: 1 no lookup (pass kept), 2 no pass (the last map is looked up), 8 a 512^2 map |
| `mcopt.shade.shadowTimes` | false | GPU timers (counter samples at the stage boundaries) on the near cascade, the far cascade (whole / part) and the tint pass; a line every ~2 s |

## 3. Blob shadow: verified (no code change needed)

`-Dmcopt.shade.entityShadows.log=true` now also counts `EntityCasters.dropBlob` decisions per 4 s (dropped / kept: no map,
moon, sky light < 5, with the highest sky light seen). The decision itself is M3.2's, unchanged.

A/B at the same poses, same world, same session settings (balanced, DRS off): this branch
(`bench-results/20261008-095117-sh1-shadow`, jar c023d12) against `release/mcopt-shade-M2.1.jar`
(`bench-results/20261008-101749-sh1-m21-ent`), `-Dmcbench.entities=cave,noon,evening,night`:

- **Cave** (`ent_cave`, LightShots' dark cave, sky light 0, a torch beside a pig and a villager): the ground including both
  blobs is the same as M2.1 (beside the pig, where its blob lies: mean |difference| 1.2/255, max 4; the ground away from the
  mobs 1.2/255). Only the mobs differ (M3's entity lighting). The log during that shot: every piece kept, sky light < 5,
  highest 0. `release/M3.3-screenshots/shadow_cave_blob_M21_vs_M33.jpg`.
  The earlier "no blob visible" (BASE.md 22:21) was a reading problem: the blob is faint in that cave in M2.1 too (vanilla
  scales it by the light, and the pig's body hides most of its own).
- **Noon / evening** (sunlit grass, sky light 15): pieces dropped (log: ~28k dropped per 4 s), the mobs cast real shadows
  instead (`shadow_noon_mobs_M21_blob_vs_M33_real.jpg`).
- **Night**: every piece kept (log: "moon"), as M2.1; the moon's entity shadows are too faint to replace the blob (M3's
  decision). Tall grass hides the blob in both at that pose.
- Counting note: vanilla calls `extractShadowPiece` for the ground block itself too (sky light 0 inside it; vanilla drops
  those by its own brightness test), so the "kept, sky light < 5" count includes those no-op pieces.

## Flags (all new; every one off = M3.2)

| Flag | Default | Read | |
|---|---|---|---|
| `mcopt.shade.shadowFarSpread` | true | every frame | far cascade drawn a quarter per frame into a second map (section 1) |
| `mcopt.shade.tintTight` | true | every frame | block-level coloured-shadow rectangle (section 2) |
| `mcopt.shade.tintSub` | true | every frame | tint pass draws only the rectangle (section 2) |
| `mcopt.shade.tintDev` | 0 | every frame | pricing bits (section 2) |
| `mcopt.shade.shadowTimes` | false | every frame | shadow pass GPU timers in the log |

## Integration notes (shared files touched)

| File | What | Why |
|---|---|---|
| `ShadeBindings.java` | `setShadowTint(int mode, ...)` overload (the boolean one delegates: mode 1) | `ssr.w = 2` tells receivers the tint map holds only the rectangle |
| `weather_surface.metal` | `shadeShadowTint`: uv from the rectangle when `ssr.w > 1.5` | same |
| `shade_look.metal` | comment of `ssr.w` | same |
| mcbench `BenchDriver.java` | `REC_PHASES` += `shtint_*`; `ShadowShots.plan(steps)` before `MiscShots.plan` | new opt-in `-Dmcbench.shadow` |

Own files: `mcshadow.m`, `ShadowPass`, `ShadowCasters`, `ShadowCascade`, `ShadowConfig`, `ShadowNative`, `EntityCasters`,
mcbench `ShadowShots.java`.
