# M3 base + entities (branch `m3`, the M3 integration branch)

## Status (2026-10-07 20:10)

- **Done.** All features (section 1), re-verified after the pause: build + `validate.sh` ALL OK (also with `MCOPT_GEN_DIR` =
  the 402-stage dump: 36 entity/item/particle pipelines build). Fresh shots: entities noon/evening/night, hand, moving mobs,
  DH sanity: all right (section 7.3).
- Costs (section 7): M3 base = **0 within noise** vs M2.1 (zoo of ~12 mobs: 6.41 vs 6.42 ms GPU, ~156 fps, balanced, DRS
  off); entity shadows priced in one session: < 0.05 ms. New per-frame switch `mcopt.shade.entityShadows.pause` + mcbench
  phases `zoo / zoo_noshadow / zoo2`.
- Look fix: evening rose warmer (upper sky warm rose instead of mauve, which read lilac-grey next to Complementary).
- Section 8: integration checklist for the M3 merge (re-checked 20:08; three REAL collisions open in m3-skydims, one design
  decision for dynamic lights on entities).
- Next: the integration workflow (vl-stable, then the modules, following section 8); `taaDebug` views in the HDR path
  (7.3, pre-existing).


Branch `m3` starts from `m2.1` (water M2.1) and is where the M3 modules (dynamic lights `m3-lights`, weather/clouds
`m3-weather`, sky/dimensions `m3-skydims`, ...) merge. Everything here is behind `mcopt.shade=true` and the HDR target; with
`mcopt.shade=false` the backend is upstream's (the two backend seams added, `MetalDrawTap` and redirect-format tags, are inert
without a listener / tagged redirect). Read `M2_INTEGRATION.md` first: this file only adds what M3 base changed.

## 1. What this branch adds

| Piece | Where | Flag (default) |
|---|---|---|
| Entities, block entities, dropped items, armour, moving blocks and particles lit by the shade model (bible E17) | `ShadeEntity.java`, `shade_entity.metal`, `ShadeMsl.FORMAT_ADAPTER` | `mcopt.shade.entities` (true) |
| First-person hand and held items lit the same way (tone-mapped in the fragment, no fog) | `ShadePost.handRedirect`, `ShadeEntity.HAND_FORMAT`, `MCOPT_HAND` in `shade_entity.metal` | `mcopt.shade.handLight` (true) |
| Entity sun shadows in the near cascade (bible E08 / M3c), vanilla blob shadow off | `MetalDrawTap`, `shadow/EntityCasters.java`, `mcshadow.m` `mc_shadow_entity_*`, `shadow.metal` `shadow_entity_vs/fs` | `mcopt.shade.entityShadows` (true), `.log` (false), `.pause` (read every frame: bench A/B) |
| Per-object motion for TAAU (moving entities don't smear) (bible E18 step 4) | `taa/ShadeMotion.java`, `taa.metal` `TaaMovers`, `mctaa.m` `mc_taa_movers` | `mcopt.shade.taaMotion` (true); `taaDebug=5` view (does not show in the HDR path: see 7) |
| **Dynamic-lights seam**: `shadeDynamicLights(ShadeDynIn, f, L, R)` called by terrain, water and entities (stub returns 0) | `shade_dynlight.metal` (in `ShadeMsl.prelude()`) | — (the module's) |
| Evening sky: rose / peach hue of the upper sky at golden hour (Complementary-like, our sun, rays and blue top kept) | `ShadeLook.SKY_ROSE` -> `skyGoldZ.w`, `sky_lut.metal` | look-scaled (epic 1, storybook 1.15, natural 0.6) |
| Underwater god rays: caustic curtains on the water's real-time clock (were on the game time, which the server corrects every second: the jumps smeared them through the 0.9 history), less history under water (0.8), stronger (HDR_WATER 0.45 -> 0.6, carve 0.55 -> 0.62) | `vl/ShadeVl.java` | `mcopt.shade.vl.underwater` (true) |
| Bench: entity scenes (`-Dmcbench.entities=true` or `perf,noon,evening,night,hand,motion`), zoo perf phase | `work/mcbench` `EntityShots.java` | see `work/HARNESS.md` 8f |

## 2. Dynamic lights: the seam (for `m3-lights`)

`shade_dynlight.metal` is prepended to every shaded stage after `shade_m2.metal`. **Replace the body of `shadeDynamicLights`**
(keep the signature and `ShadeDynIn`; add fields at the end of the struct if needed and default them in `shadeDynIn()`):

```metal
struct ShadeDynIn { float3 p; float3 n; float3 albedo; float2 pixel; half sky; half block; int kind; };
static inline float3 shadeDynamicLights(ShadeDynIn d, constant ShadeFrame &f, constant ShadeLook &L, constant ShadeResources *R);
```

- Returns **radiance to add** (linear, before exposure; same units as `shadeLit2`: E/pi x albedo). Callers do
  `radiance += shadeDynamicLights(...)` before fog and exposure.
- `p` camera-relative world position, `n` unit world normal (toward the camera for two-sided geometry), `albedo` linear,
  `pixel` fragment position, `sky`/`block` vanilla light levels 0..1, `kind` one of `SHADE_DYN_TERRAIN`, `_WATER` (albedo 1:
  return the light on the surface), `_ENTITY`, `_HAND`, `_PARTICLE`.
- `R`: the `ShadeResources` argument buffer (buffer 19) in HDR stages, `nullptr` in M1/LDR terrain. Free texture slots 4-7
  for a light list texture (`ShadeRender.resource(index, texture)`, ring-safe), or `ShadeLook.heldLight` (offset 608) for
  the held light. Test a light count first and return 0: it runs for every shaded fragment.
- Call sites today: `sodium_terrain.fs.metal` (terrain: after `shadeLit2`; water: added to `wi.lit`), `shade_entity.metal`
  (entities, hand, particles). Terrain now declares `constant ShadeResources& sr [[buffer(19)]]` under `MCOPT_HDR` for all
  passes (was water-only).

## 3. Entity lighting: how it works, how to extend

- The game's entity / item / particle / moving-block pipelines keep their own SPIRV-Cross MSL; only their **HDR variants**
  (`MetalPipeline.variant` through `ShadeMsl.FORMAT_ADAPTER`) are patched textually by `ShadeEntity`:
  vertex + 4 outputs at `locn12..15` (`mcoptPos` camera-relative = the expression the game projects, `mcoptNrm`, `mcoptColor`
  = raw vertex colour without vanilla's cardinal light, `mcoptLight` = UV2 / 240); fragment + shade globals and
  `[[position]]`/`[[front_facing]]`, and before its `return out;` the colour is replaced by `shadeEntityShade(...)`
  (alpha, cutout discard, dissolve stay the game's). The RGBA8 pipelines (GUI items, maps) are untouched.
- Patched set: `ShadeEntity.PIPELINES` (entity_solid/cutout*/translucent*, armor_*, banner_pattern, item_cutout /
  item_translucent, solid/cutout/translucent_block, opaque/translucent_particle). Not patched (M2 path, vanilla light
  converted to HDR): glint pipelines (`item_cutout` shares its name with its glint variants: logged once as "fragment
  shape"), emissive (eyes, energy swirl, entity_translucent_emissive), text, lines, leash, beacon, end portal/gateway.
- A fragment is patched only if its pipeline's vertex stage was (`VERTEX_PATCHED`), so the interfaces always match.
- Shading (`shade_entity.metal`): `shadeLit2` with `shadeShadow` (4-tap, cheaper than PCSS; skipped at sky light <= 0.3),
  + dynamic lights, `shadeFogAir2` + `shadeFinish2` like terrain. Particles at lightmap 15/15 (flames, sparks) glow.
  Outside the overworld the game's colour is kept (as terrain does). Debug views 1 (shadow), 2 (normals), 3 (grey albedo).
- Hand: `ShadePost` redirects the "Item in hand" pass (after the final pass) with format `RGBA8 | 1 << 16`
  (`MetalHooks.TAG_SHIFT`: a variant tag, the pipeline is built for the low bits); `MCOPT_HAND` then writes
  `sRGB(shadeDisplay(radiance))`, no fog.
- Validation: `MCOPT_GEN_DIR=<a -Dmcopt.metal.dumpMsl dir> tools/shade/validate.sh` patches the dumped stages and builds
  every pair as a real pipeline (36 = 18 world + 18 hand variants; `item_cutout` is skipped, see above). Re-dump after a Minecraft / mcopt update.

## 4. Entity shadows: how it works

1. `MetalDrawTap.listener` (backend seam): every `drawIndexed` of a QUADS pipeline with a uv at location 2 and a
   `Sampler0`, in a format-redirected pass (the HDR world), reports its vertex MTLBuffer, first-vertex byte offset, quads,
   stride, uv offset and texture.
2. `EntityCasters` keeps the caster pipelines' draws (`CASTERS`, with the alpha cutoff) natively
   (`mc_shadow_entity_record`, buffer + texture retained).
3. `ShadePost.beforeHand` -> `EntityCasters.commit`: one blit copies the recorded vertex ranges into the shadow pass's
   12 MB private buffer (the game's `StagedVertexBuffer` pools are per frame).
4. Next frame, `mc_shadow_render` for cascade 0 draws them after the terrain (`shadow_entity_vs`: vertex pulling, quads
   from `vertex_id`, shifted by camera(then) - camera(now); `shadow_entity_fs`: alpha test). One frame of latency.
   Only the near cascade (±32 blocks, ahead of the view).
- The vanilla blob shadow (`entity_shadow`) is dropped in the HDR variant when entity shadows are on (as Iris does).
- `-Dmcopt.shade.entityShadows.log=true`: a line every 4 s with the casters drawn.

## 5. Per-object motion (TAAU)

`ShadeMotion.update` (from `ShadeTaa.camera`, GameRenderer.renderLevel) puts each entity within 64 blocks at its render
position (`lerp(partial tick, xOld, x)`, exactly what EntityRenderer draws); those that moved since last frame become movers:
bounding box at the render position padded 0.3 (camera-relative) + the change. `taa.metal` reprojects a pixel whose
reconstructed surface is inside a mover from `world - delta`. Max 32 movers, nearest first, 1.5 KB of bytes at buffer(2).
Translation only (limb swing and turning stay with the variance clip). `mctaa.m` keeps `mc_taa_resolve`'s signature
(headless `taatest` unchanged; movers default to none).

## 6. Merge notes for the M3 modules

- Expect conflicts in `sodium_terrain.fs.metal` (dynamic-light call sites, `sr` binding), `ShadeLook` (offsets: M3 base
  uses only `skyGoldZ.w`; `heldLight` 608, `wet` 496, `wind` 512, `clouds0-2` 528-560, `dimTint` 752, `flash` 768 are still
  free for the modules), `ShadeMsl.prelude()` (one more file), `vl/ShadeVl.java` (temporal param line).
- Entities already call `shadeDynamicLights` with `kind`; a lights module needs no change in `shade_entity.metal`.
- Weather/wetness: entities go through `shadeLit2`; a wetness term added there reaches them too.
- Nether/End: entities keep vanilla colours outside the overworld (like terrain); a dimension module that lights terrain
  there should do the same in `shadeEntityShade` (one branch at its top).
- Cloud shadows: entities use `shadeShadow`; multiply the cloud shadow where terrain does.

## 7. Costs and tests

**In plain words (for Gael):** lighting the mobs, items and particles with our shader, their real sun shadows, the lit
hand and the anti-smear for moving mobs together cost **nothing measurable**: with ~12 mobs, a chest, a sign and floating
items filling the screen the GPU needs **~6.4 ms per frame (~155 fps) with M3, the same as with M2.1**. The differences
between runs (0.1-0.2 ms) are noise: two runs of the exact same M2.1 jar differed by more than that. Entity shadows alone,
switched on and off inside the same game session: **< 0.05 ms**.

### 7.1 How it was measured (provisional: clean numbers come after the integration)

- `mcbench-run --direct` on `mcopt DEV`, 3024x1964 fullscreen, preset balanced, **`-Dmcopt.shade.drs=false`** (fixed render
  scale 0.80). Dynamic resolution holds the GPU at ~7.2 ms (86 % of 1/120 s) by moving the scale, so every older A/B with
  DRS on (`m3-ab*`, `m3f-*`) measured scale changes, not cost: not used. `-Dmcbench.entities=perf
  -Dmcbench.standardShots=false --scenes all --seconds 12`: phases spin, flight (standard world, no mobs), then `zoo`,
  `zoo_noshadow`, `zoo2` (10 s each, the zoo panning, noon).
- Order (ABBA across launches, other agents' runs in between): on-1 18:21, off-1 18:39, m21-1 19:07, m21-2 19:35, off-2
  19:49, on-2 20:05. "off" = this jar with `mcopt.shade.entities=false`, `entityShadows=false`, `taaMotion=false`,
  `handLight=false`; "M2.1" = `release/mcopt-shade-M2.1.jar`. Jar `notes/m3base/m3-a8692a3.jar` (the costs are the same
  in b677538: only sky colours changed).
- All on AC power; CPU of other processes during the runs: median 60-110 % (clean; a gradle build is 400-1000 %).
  **m21-2 is drifted**: every phase ~0.7 ms slower, rising inside the run (zoo 6.97 -> 7.35 ms with an identical config),
  no CPU load to explain it (GPU clocks / another app's GPU work): kept in the table, left out of the means.

### 7.2 Results (GPU ms per frame; lower is better)

| Run | spin | flight | zoo | zoo_noshadow | zoo2 |
|---|---|---|---|---|---|
| on-1 (`20261007-182151-m3p-on-1`) | 9.387 | 8.885 | 6.522 | 6.351 | 6.347 |
| off-1 (`...183929-m3p-off-1`) | 9.365 | 8.868 | 6.497 | 6.388 | 6.386 |
| M2.1-1 (`...190718-m3p-m21-1`) | 9.234 | 8.926 | 6.418 | 6.361 | 6.486 |
| M2.1-2 (`...193559-m3p-m21-2`, drifted) | *10.033* | *9.992* | *6.965* | *7.120* | *7.350* |
| off-2 (`...194927-m3p-off-2`) | 9.161 | 8.783 | 6.417 | 6.457 | 6.480 |
| on-2 (`...200535-m3p-on-2`) | 9.315 | 8.636 | 6.448 | 6.446 | 6.482 |
| **mean M3 on** | **9.35** (107 fps) | **8.76** (114 fps) | | | **6.41** (156 fps) |
| **mean M3 flags off** | **9.26** | **8.83** | | | **6.43** |
| **M2.1** (run 1) | **9.23** | **8.93** | mean of its 3 zoo phases **6.42** | | |

M3 on minus M2.1: spin +0.12 ms, flight -0.17 ms, zoo -0.01 ms: **zero within the noise** (run-to-run spread of the same
config here is 0.1-0.2 ms). The spin/flight views have no mobs, so they show that nothing leaked into terrain, sky or post.

In-session price of the entity shadows (`zoo_noshadow` vs `zoo2`, same session): on-1 6.351 vs 6.347 (+0.00); on-2
6.446 vs 6.482 (+0.04 ms). The first `zoo` phase runs ~0.1-0.15 ms high in every run, the M2.1 jar included (it has no pause switch, so its
three phases are identical): warm-up, not cost.

### 7.3 Visual checks (screenshots in `work/release/M3-base-screenshots/`)

- `20261007-181121-m3r-shots` (b8bbf23) and `20261007-183759-m3r2-shots` (b677538): entities at noon, evening, night (torches),
  the lit hand at night, moving mobs. Mobs sit in the terrain's light: sunlit tops warm, sides facing away take the blue
  sky light like terrain sides, torch-lit at night like the grass around them; no shadow acne on mobs at 1:1
  (`entities_noon_1to1_M3_resume.jpg`); a villager, a horse and a pig moving at 4-6 blocks/s stay sharp, no ghost trail
  (`moving_mobs_M3_resume.jpg`); real mob shadows replace the blob.
- **Evening sky, warmer**: next to Complementary the upper sky read lilac-grey. The rose tint of the upper sky is now warm
  rose instead of mauve (`sky_lut.metal`: `(1.6, 0.88, 0.86)` was `(1.55, 0.86, 1.0)`) and ~12 % stronger around sunset
  (`ShadeLook.SKY_ROSE`); the gold horizon band, the sun, the rays and the blue zenith stay ours.
  `sunset_Complementary_vs_M3rose-before_vs_M3rose-after.jpg` (top Complementary, middle before, bottom after).
- DH sanity (`20261007-192612-m3r-dh`, DEV-DH, DH 3.3.4): loads, LODs drawn, zoo lit, no exceptions (only the offline
  login's 401s): spin 15.7 ms / 64 fps, flight 17.0 ms / 59 fps (DRS on; earlier m3-dh 18.5 ms, M2 final m2f-dh 16.4 ms:
  DH numbers swing with its LOD loading). `DH_sanity_M3_resume_1928.jpg`.
- `taaDebug=5` (`20261007-190614-m3r-taadbg5`): **the debug view does not appear** in the HDR path: the TAAU debug resolve
  writes into the output target `dst`, and since M2 folded the output pass into the final pass the HDR resolve passes
  `dst = 0` (`ShadeTaa` line ~213, `mctaa.m mc_taa_resolve`): the debug pipeline is never used. Pre-existing (all taaDebug
  views, M2 on); the motion was checked by the off/on shots instead (`moving_mobs_taau_motion_off_vs_on.jpg`).
- Validation: `validate.sh` ALL OK; with `MCOPT_GEN_DIR` (402-stage dump) "36 entity / item / particle pipelines lit by
  shade (ShadeEntity) build".

## 8. Integration checklist (for the M3 integration)

Written 2026-10-07 18:30, re-checked 20:08 from the docs and `git diff m2.1..HEAD` of each branch at these heads (the module
agents were still finishing them: **re-run the diffs at merge time** and check every row): `m3` (this branch), `vl-stable`
87f9fa2, `m3-lights` d4d1838, `m3-clouds` 9772fbb, `m3-weather` 37163d3, `m3-skydims` 2030a03. At 20:08 the REAL rows below
were all still open (skydims `FLAG_DIMS_LIT = 4096`, its else-branch `FLASH` write, `P.r.w > 0.5`). All five module branches start from
`m2.1`, not from `m3`: none of them knows the entity path, the draw tap or the dynamic-light seam of this branch.
Merge order (workflow plan): `vl-stable` first, then the modules into `m3`. Rows marked **REAL** are semantic collisions
(both sides compile, the result is wrong); the others are textual (union both sides).

### 8.1 Bit fields and shared words

| What | Who | Collision | Resolution |
|---|---|---|---|
| `ShadeFrame.sunPath.z` (dev bits, float) | M1/M2 dev bits 1-16; weather `SHADE_DEV_WX_*` 1024, 2048, **4096** (`WX_DRY`), 8192, 16384; skydims `SHADE_FLAG_DIMS_LIT` **4096** (OR-ed in by `ShadeDims.pack`) | **REAL**: in the Nether/End with dims lit, terrain also reads "stay dry" and weather's dev branch fires; with `-Dmcopt.shade.dev=4096` in the overworld, terrain takes the Nether/End path | Move `FLAG_DIMS_LIT` (Java `ShadeDims.FLAG_DIMS_LIT` and `shade_dims.metal SHADE_FLAG_DIMS_LIT`) to **65536** (1 << 16; a float holds it exactly). Convention: bits < 1 << 16 = dev toggles, >= 1 << 16 = per-frame state flags. Weather's `devBits()` reads `mcopt.shade.dev` every frame, so keep the OR after `ShadeFrame.pack` |
| `ShadeLook.tier.y` feature bits | M2: 1-64; clouds `SHADE_FEATURE_CLOUDS` 4096; skydims `DIMS` 1 << 20, `MEDIA` 1 << 21, `SHIMMER` 1 << 22 | none (free: 128-2048, 8192-524288) | Both clouds (`CloudParams`) and skydims (`ShadeDims.pack`) read-modify-write TIER+4 after `ShadeLook.pack`: keep both calls after the pack in `ShadeFrameUpdater` |
| `ShadeFinalParams.r.w` (final pass) | vl-stable: bit **2** = VL/depth unjitter; skydims: `bridgeUsed ? 1 : 0` (DH drew), read as `P.r.w > 0.5` / `< 0.5` | **REAL**: with TAAU on, vl-stable writes 2, skydims reads "DH drew" (End boss-fog sky skipped, Nether fog leaves LODs) | Make r.w a bit field: `(DH ? 1 : 0) + (unjitter ? 2 : 0)`; skydims tests `(int(P.r.w) & 1) != 0` (`shade_post.metal` lines ~209, 215) |
| `ShadeFinalParams` size | m2.1 80 B Java buffer / 4 float4 in MSL; skydims adds `float4 x` (XDR) -> 96 B | textual | Take skydims' 96 B + `x`; vl-stable does not change the size |
| `ShadeFrame.flags` (offset 960) read by the blob-shadow patch (`ShadeMsl.FORMAT_ADAPTER`, `mcoptShadeFrame[60]`) | m3: drops the blob when `flags.z != 0 && flags.x == 0` (overworld with a shadow map) | skydims adds End shadows (`ShadowPass` End key light): entity casters then also cast in the End, but the blob stays | With skydims: `flags.z != 0 && flags.x != SHADE_DIM_NETHER` (any dimension with a sun/key-light shadow map) |

### 8.2 ShadeLook offsets (`shade_look.metal` + `ShadeLook.java`)

| Offset | Field | Owner after merge | Notes |
|---|---|---|---|
| 192 | `cloudShadowVP` | clouds | |
| 496 / 512 | `wet` / `wind` | weather | |
| 528 / 544 | `clouds0` / `clouds1` | clouds | 560 `clouds2` still free |
| 608 | `heldLight` | free | m3-lights uses its own buffer(24), not this field |
| 656 `.w` | `skyGoldZ.w` evening rose | m3 | |
| 672 `.z` | `night.z` aurora | skydims | |
| 688 `.y` / `.z` / `.w` | `ssr.y` prevDepth valid / `ssr.z` weather noise baked / `ssr.w` shadowTint holds casters | weather | |
| 752 | `dimTint` | skydims | |
| 768 | `flash` | **weather** (lightning, overworld) **and skydims** (End flash) | **REAL**: `ShadeDims.pack` runs after `ShadeLook.pack` (which calls `ShadeWeather.pack`) and, when the dims are not lit, writes `flash = (0,1,0,0)`: the overworld lightning flash is erased every frame. Fix: skydims writes `flash` only while dims are lit (End); weather writes it only in the overworld (it already gets `dimension == OVERWORLD`). Consumers: weather's terrain `shadeWxFlash` only in the non-dims branch, skydims' `shadeLitDimsExtra` / End sky only in the End: one writer and one consumer per dimension |

### 8.3 ShadeResources (buffer 19 argument buffer)

| Entry | After merge | From |
|---|---|---|
| 0-3 | milkyWay (skydims: `.a` now holds its fbm noise), prevScene, scene, bloom | M2 |
| 4 | `weatherNoise` | weather (commit 3c93d58, 18:09; WEATHER.md's table still says 4 is free) |
| 5 | `prevDepth` | weather (written by `ShadePost.beginWorld` from `taa.historyDepth()`) |
| 6 | `shadowTint` | weather |
| 7, 8-11, 15 | free | |
| 12 / 13 / 14 | `cloudShadow` / `cloudDome` / `cloudScreen` | clouds |
| size | **16** entries: `mcshade.m SHADE_RESOURCES 16` (clouds) + the struct in `shade_look.metal` with weather's names in 4-6 and clouds' 8-15 | |

m3-lights uses none of these: its data is `buffer(24)` (`ShadeDynLights`, vertex + fragment), `texture(23)` visibility,
`texture(24/25)` irradiance volume, bound by `DynLights.bind` inside `ShadeBindings.bind/rebind`. Free in every shaded
stage today (entities: game slots from 0, m3's shade slots buffer 16-19, texture 16-17, sampler 12-13). m3's movers use
`buffer(2)` of the TAA resolve only (own encoder).

### 8.4 Prelude and per-stage code

- `ShadeMsl.prelude()` order: `#define MCOPT_SHADE` + shade_look + shade_common + **shade_clouds** (clouds: shade_m2's
  `shadeFogAir2` calls it) + shade_m2 + **shade_dims** (skydims) + **`DynLightMsl.prelude()`** (lights: `dynlight.metal`,
  empty when off) + **shade_dynlight** (m3's seam, last: its body may call the lights module). Weather's prelude stays
  per terrain fragment (`ShadeMsl.terrain`: prelude + weather + water + source).
- `shadeFogAir2` gets clouds' optional `constant ShadeResources *R = nullptr`: m3's `shade_entity.metal` calls it without
  R (fine); pass `&R` there to get the clouds in the entity fog too.

### 8.5 `sodium_terrain.fs.metal` (PolyForm Shield header kept): every branch edits it

1. `sr [[buffer(19)]]` declaration: m3 and clouds made the **same** change (declared for every HDR variant); weather made
   it `MCOPT_HDR && (SHADE_WATER || SHADE_WEATHER)`. Take m3/clouds' (a superset).
2. Shadow line: skydims' `endLit` / Nether / `shadowSky` version, then clouds' `shadow *= shadeCloudShadow(...)` (sky > 0.3,
   HDR).
3. Vanilla-colour branch (`dims && !shadeDimsLit`): skydims' condition + m3-lights' `SHADE_DYNLIGHT` colour raise inside it.
4. Non-water branch: weather's `shadeWetSurface` -> `wetAlbedo` -> skydims' `dims ? shadeLit2(sky 0, shadow 0) +
   shadeLitDimsExtra : shadeLit2` (with `wetAlbedo`) -> m3-lights' `shadeDynamicLightsVol` term (with `wetAlbedo`; skydims
   note: after the dims line too) -> emissive (skydims' Nether 3.1) -> weather tint delta -> weather flash (**only
   `!dims`**, see 8.2) -> `shadeWetShade`.
5. **Dynamic lights: drop m3's two seam call sites** in this file (terrain after `shadeLit2`, water `wi.lit +=`): the stub
   returns 0 and m3-lights' own calls (`shadeDynamicLightsVol`, `shadeDynamicLightsSpec` for the water glint) are the
   implementation; keeping both would add the light twice once the seam has a body.
6. Water: clouds' `wi.res = &sr` / `shadeGlossy(..., &sr, ...)`, weather's ripples (in water.metal), m3-lights' water lit
   and glint: all additive. Fog line: clouds' `&sr` variant, wrapped in skydims' `if (!dims)`.
7. `sodium_terrain.vs.metal`: skydims only.

### 8.6 Entities (this branch) x modules

| Module | What m3's entity path needs | Proposal |
|---|---|---|
| lights | **REAL**: m3 shades entity **fragments** (`ShadeEntity` patches the HDR variants; the colour is replaced by `shadeEntityShade`, built from the raw vertex colour and UV2/240), while m3-lights patches the same pipelines' **vertex** stages (`DynLightMsl.patchVertex`: `sample_lightmap` -> `shadeDynLightmap`). m3's fragment never reads the vanilla lightmap colour, so m3-lights' entity lighting would be computed and thrown away | (a) In `FORMAT_ADAPTER.vertex`: run `ShadeEntity.vertex` first; apply `DynLightMsl.patchVertex` only when it returned null (glint, emissive, text, beacon, leash...: pipelines m3 does not shade). (b) Give the seam a body for entities: `ShadeEntity.fragment` appends `constant ShadeDynLights& dl [[buffer(24)]], texture3d<half> dlIrr [[texture(24)]], texture3d<half> dlDir [[texture(25)]]` under `SHADE_DYNLIGHT` and `shadeEntityShade` adds `float3(albedo) * f.ambientBlock.xyz * shadeDynamicLightsVol(dl, dlIrr, dlDir, p, n, thin, block)` (per pixel, 2 fetches; the seam signature `(d, f, L, R)` cannot reach those bindings, so either extend it with a `ShadeDynCtx` struct of the three or call the module directly and leave the seam for terrain-less callers). (c) **Hand**: m3-lights' `DynLightEntityRendererMixin` raises the local player's packed light, which m3's lit hand already reads as block light (UV2): do not also add the volume for `SHADE_DYN_HAND` (double light) |
| clouds | entity sun term under cloud shadow | in `shadeEntityShade`: `shadow *= half(shadeCloudShadow(p, L, R.cloudShadow))` when sky > 0.3 (R is bound: `mcoptSR`). Hand: skip (hand-space p) |
| weather | lightning on mobs; wetness | optional: `shadeWxFlash` needs weather.metal/weather_surface in the entity prelude (today terrain only). No wet mobs (vanilla has none either) |
| skydims | Nether/End entities | `shadeEntityShade` returns the game's colour outside the overworld (top of the function, both world and hand variants): with dims lit, use skydims' path instead (`shadeLit2(albedo, n, 0, block, 0, ...) + shadeLitDimsExtra(...)`, no `shadeFogAir2`: the final pass fogs Nether/End), else entities stay vanilla-bright in a re-lit Nether. End shadows: entity casters then cast there too (8.1 blob row) |
| weather coloured shadows | casters | `mc_shadow_render`: m3 draws the entity casters into cascade 0 after the terrain; weather's tint pass runs after cascade 0 into its own map: order terrain -> entities -> tint pass. Union the two sets of native functions in `mcshadow.m` / `ShadowNative` / `ShadowPass` |

### 8.7 Other shared files (union both sides)

- `vl/ShadeVl.java`, temporal line: vl-stable and m3 both switch `w` to `f.waterTime`. Take vl-stable's line and keep
  m3's under-water history `VlConfig.TEMPORAL ? (f.underwater ? 0.8f : 0.9f) : 0f`, plus m3's `HDR_WATER 0.6` and
  `HDR_CARVE_WATER 0.62`. Re-shoot `vl_underwater` after the merge (vl-stable's new clamp box / history-at-depth may want a
  different under-water weight).
- `vl.metal`: vl-stable (113 lines, temporal) and clouds (`vl_march` takes ShadeLook 17 + ShadeResources 19, cloud shadow
  in the march, sky mask = cloud transmittance) edit `vl_march`: hand-merge; the VL encoder must bind 17 and 19.
- `shade_post.metal` / `ShadePost.java`: vl-stable (unjitter, r.w), skydims (XDR final, r.w, `shade_final` signature), weather
  (prevDepth publish in `beginWorld`), m3 (`handRedirect`, `EntityCasters.commit` in `beforeHand`).
- `ShadeBindings.java`: lights (`DynLights.init/bind` in bind/rebind), weather (`setPrevDepthValid`). `shade_common.metal`:
  weather's `SHADE_DEV_WX_*` defines (see 8.1).
- `ShadeFrameUpdater`: order weather update -> look/frame pack -> `ShadeDims.pack` -> clouds params/update -> `DynLights.frame`.
- `ShadowPass.java`, `mcshadow.m`, `ShadowNative.java`, `shadow.metal`: m3 (entity casters), weather (tint), skydims (End light).
- `MetalRenderPass.java`: m3 draw tap + redirect tags, clouds skip of vanilla clouds. `MetalSurface.java`, `mcmetal.m`:
  skydims (XDR present) only.
- `sky.fs.metal`: skydims aurora (+ `pixel` parameter), weather flash, clouds composite: aurora and flash before the clouds'
  `sky = sky * clouds.a + clouds.rgb`.
- `water.metal`: clouds (R pointer), weather (ripples). `shade_m2.metal`: clouds. `sky_lut.metal`: m3 only.
- `metal/build.gradle`: native sources `+ mcdynlight.m, mcclouds.m, mcxdr.m`, frameworks `+ AppKit` (skydims).
- `fabric.mod.json`: mixin configs `+ mcopt-metal-shade-light.mixins.json, mcopt-metal-shade-dims.mixins.json`.
- `tools/shade`: `validate.sh`, `ShadeMslDump.java`, `pipecheck.m` (m3 layout `auto`, skydims `postexcolor` and `mrt`):
  union, then run `MCOPT_GEN_DIR=<dump> validate.sh` (needs a fresh `-Dmcopt.metal.dumpMsl` dump: entity, DynLightMsl and
  weather variants are only checked against real game stages).
- mcbench (one shared checkout): nothing to merge; `REC_PHASES` already lists every module's phases.

### 8.8 After the merge, test

`validate.sh` ALL OK (with a dump) · every module flag off = m2.1 · `mcopt.shade=false` = upstream · entity shots
(noon, evening, night, hand, motion) · lights `dl_field_mobs` (mobs lit once, by the fragment path) · weather `wx_storm`
(flash visible, not erased) · dims `nether_*`, `end_island` (mobs lit like the terrain, blob vs entity shadows) · clouds
`clouds_*` · VL `vl_underwater` · DH sanity on DEV-DH (`shade_final` r.w bits).
