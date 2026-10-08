# M3.3 shadow passes (branch `m3.3-shadow` from `m3-int` 397e241)

## M3.3 Status (2026-10-08 09:55)

- **Done (built, `validate.sh` ALL OK, not yet seen in game):** c023d12 = far cascade spread, shadow GPU timers, tight
  coloured-shadow rectangle, rectangle-only tint pass, tint pricing bits, blob decision counts. mcbench 9cd7025
  (`ShadowShots`).
- **Running:** game run 1 (`notes/m33-2026-10-08/shadow/run1.sh`, jar `jars/m3.3-shadow-c023d12.jar`): in-session A/B of the
  far spread (spin x4, route x4, timers x2 via MiscShots `-Dmcbench.ab`), coloured-shadow price phases (`shtint_*`), glass
  variant shots, far spread shots (still / strafe / turn), entity shots cave/noon/evening/night; then the same entity shots
  with `release/mcopt-shade-M2.1.jar` (blob reference).
- **Next:** read the numbers (`notes/m33-2026-10-08/shadow/framespread.py <run>`, timer lines `shadow gpu ms` in latest.log),
  look at the shots, decide the defaults, write the prices below.

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

## 2. Coloured shadows near glass

Pricing tools (all read every frame):

| Flag | Default | Meaning |
|---|---|---|
| `mcopt.shade.tintTight` | true | the tint rectangle (receivers outside skip the tint lookup) from the box of the section's translucent **blocks** (scanned once per section, cached by its translucent geometry, rescanned every ~240 frames), not the whole 16^3 section; sections holding only water are not drawn. Only with the water module on (water is a fluid, not a block model). false = M3.2 |
| `mcopt.shade.tintSub` | true | the tint pass clears, draws and stores only that rectangle, in the map's top-left corner at the map's own texel size (`ShadowPass.tintSubMatrix`); receivers map their light-space position into it when `ssr.w = 2` (`weather_surface.metal shadeShadowTint`). false = M3.2 (the whole 1024^2 map every frame) |
| `mcopt.shade.tintDev` | 0 | pricing bits: 1 no lookup (pass kept), 2 no pass (the last map is looked up), 8 a 512^2 map |
| `mcopt.shade.shadowTimes` | false | GPU timers (counter samples at the stage boundaries) on the near cascade, the far cascade (whole / part) and the tint pass; a line every ~2 s |

## 3. Blob shadow

`-Dmcopt.shade.entityShadows.log=true` now also counts `EntityCasters.dropBlob` decisions per 4 s (dropped / kept: no map,
moon, sky light < 5 with the highest sky light seen).

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
