# Volumetric clouds (M3 module, branch `m3-clouds` from `m2.1`)

## M3.3 Status (2026-10-08 10:20) - branch `m3.3-clouds` from m3-int 397e241

Item 1 (comb): **fixed and confirmed in game** (b4ece4e, flag `mcopt.shade.clouds.stable`, default true, read every
frame; false = M3.2; dev bit 32768 = M3.2 way for in-session pricing). Run 1 (`bench-results/20261008-101319-m33c-1`,
`work/notes/m33-2026-10-08/clouds/run1.sh`, wind on, each scene shot with stable=false then true a few seconds apart):
noon_south still camera: M3.2 shows the vertical comb along the soft edges, M3.3 a clean edge
(`work/release/M3.3-screenshots/clouds_comb_quieto_antes_despues.jpg`); turning at 30 deg/s (shot mid-turn,
`turn_south`, `turn_sunset`): M3.2 diagonal hatching over whole clouds and combed sunset edges, M3.3 none (a fine random
grain remains where the turn just uncovered the sky, near the screen edge: `clouds_comb_girando_antes_despues.jpg`,
`clouds_comb_girando_atardecer_antes_despues.jpg`). Its in-session price in run 1 is unusable (whole-frame GPU ms jumped
between ~8.8 and ~10.9 ms between phases with nothing changed, residual sd 1.2 ms: GPU contention from outside the
game); run 3 repeats it with 24 short interleaved phases. Diagnosis (offline preview, which now turns / scales the
wind: `CLOUDS_YAWRATE`, `CLOUDS_WIND`; experiment shader kept as `work/notes/m33-2026-10-08/clouds/clouds_experiments.metal`):
- soft cloud parts are step-aliased: one march sample's result there depends almost entirely on its start offset (fixed
  offsets 0.1/0.3/0.5/0.7/0.9 give five different sets of lobes); only the average over visits is the soft cloud;
- the offset was IGN(pixel) + visit x golden ratio. IGN is linear in the pixel (mod 1), so a cloud carried across the
  pixels (wind, turning) met offsets stepping by a near-constant amount per visit that can cancel the golden step (one
  3-pixel block in -x per 9 frames: -0.665 + 0.618 = -0.047): it was marched at the same depth phase visit after visit,
  never averaged = the comb. Fixed offsets (no jitter) remove the comb (but alias); a per-pixel hash removes it too;
- the block's one march distance used to reproject all 9 pixels jumps between visits on soft clouds (slab entry for a
  clear ray, cloud depth for a hit): a band of speckle where the jumps were large. Fixed by carrying each pixel's own
  cloud distance in the history (as Photon's clouds_data) + nearest-cloud distance of the 2x2 footprint for reprojection;
- not the cause: the resolve's distance-jump clamp, the empty-space skip, Catmull-Rom vs bilinear, noiseDrift.
- what remains in motion is a fine random grain (the noise floor of 1/9-rate sampling under sub-pixel resampling; a
  2D model `sim_checker.py`/`sim2.py` shows the error grows with speed for any jitter: blur vs noise trade-off).

Item 2 (price), 10:15: the march is **latency-bound**, not fetch-bound: instrumented offline (march work counters,
`CLOUDS_RES`/`CLOUDS_DUMP`), an average ray does only ~4.5 loop iterations (~3 empty-air tests, ~2 in-cloud samples,
~1 light march; the clouds are dense: 2 samples reach T < 0.02), i.e. ~10 texture fetches, but in a chain of dependent
ones. Changes (each a mode bit, read every frame, with a dev bit back to M3.2's way for in-session pricing):
`halfLight` (light march on every other in-cloud sample, the ones between take the mean: look diff mean 0.5-0.7/255),
`batch` (a sample's fetches issued together incl. its light march and the cirrus' first fetch: same values, max diff
13/255 on a handful of pixels from float order), `pair` (empty-air tests two at a time: bit-exact). Run 2 (`run2.sh`,
m33c-2, jar symlink `jars/m3.3-clouds-run2.jar` -> the sha jar at its start) prices each one in session.

1. Comb / hatching on soft cloud edges (still camera + wind + 3x3 checkerboard). Reproduce offline first
   (`tools/shade/clouds/run.sh png` with a still camera over a few hundred frames, crops at full size), find the cause
   with a debug output, fix behind a flag (off = M3.2), confirm in game (noon_south, still camera, wind on; and turning).
   Evidence: `work/release/M3.3-screenshots/clouds_comb_*`.
2. Price <= 0.5 ms at balanced (now ~0.65-0.71): empty-space skip (max-reduced weather mip), cheaper light march,
   fewer resolve bytes; in-session pricing with `clouds-m31` tools; look kept (wind=0 shots old vs new, imgdiff).
3. Only if cheap: far horizon band layering; speckle on far clouds from inside.
Scripts/evidence: `work/notes/m33-2026-10-08/clouds/`.

## Status (2026-10-08 08:30) - M3.1 performance pass (branch `m3.1-clouds`), stopped by the usage limit

Done (all committed, builds, validate ALL OK): price breakdown tools + six savings behind `mcopt.shade.clouds.lean`
(default true; false = M3 exactly). Measured in session: the clouds cost **~0.65-0.71 ms** at balanced (was ~0.77-0.87
with the M3 path in the same sessions). **Target <= 0.5 ms NOT reached.** Details, numbers and next steps: section
"M3.1 performance" at the end. Older status below.

## Status (2026-10-07 23:25) - ready for M3 integration

Finished this round (runs `work/bench-results/20261007-2247..2319-cf*`, scripts `work/notes/m3-2026-10-07/prefix-scripts/clouds-fx/run7*.sh, run8.sh`):
- **Gael's sunset report** (`work/notes/m3-2026-10-07/gael-report-sunset-lines/README.md`, "Resultado final"), all three
  items: (1) light lines + columns fixed (81d0eb8, fog looks the clouds up at the pixel's own direction); (3) horizon
  stripes mostly fixed (e9fe3a0, march steps capped at ~48 blocks on long grazing stretches; a softer layering remains in
  the farthest band); (2) **bright rectangles on the water: the sun glint, fixed (fe29a8f, `water.metal`, shared)**.
  Attribution (sunset_west): same with `ssrPrev=false` (not the rendered-sky reflection), debug view 6 shows deep water
  there (not the floor), with `shadows=false` they merge into one broad glow under the sun (`ab_sea_head_noshadows.jpg`).
  So: M2.1 widened the glint with distance (alpha 0.035 + 0.10 x dist/110) for long glitter roads; from a high camera the
  near water is far but seen steeply, the widened lobe of a low sun lit it as a broad glow and the terrain's long sunset
  shadows cut that into bright blocky patches. Fix: the widening only at grazing views (x (1 - smoothstep(0.1, 0.4, -v.y))),
  flag `mcopt.shade.waterGlintGraze` (default true; false = m2.1). Low over the water the glitter road is unchanged
  (`work/release/M3-clouds-screenshots/water_glint_graze_off_vs_on.jpg`); the patches are now faint glints in the sunlit
  gaps (`fix2_sea_before_after_glint.jpg`). Final before/after of the view: `final_*_antes_despues.jpg` in that folder.
- **noiseDrift** (666b4b4 WIP): built, tested on the still-camera comb with the same clouds (cf7 drift 1 vs cf2 drift
  0.82, `work/release/M3-clouds-screenshots/comb_still_noiseDrift1_vs_082.png`): the comb is the same, so the default went
  back to 0.82 (458afd6; flag kept, documented).
- **Price** (7.3, new rows): +0.67 / +0.78 ms at head-equivalent jars, +0.83 ms before the dt cap (other power state):
  e9fe3a0's horizon step cap costs nothing measurable (< 0.15 ms, the run-to-run noise). The module is ~0.7-0.8 ms at
  balanced in this sky-heavy scene, about 2x the E05 budget (0.30-0.45): open, ideas in 7.3.
- build OK, `validate.sh` ALL OK at fe29a8f.

Still open (for review / after integration):
1. **Comb on soft cloud edges, also with a still camera** (check finding): needs the wind (wind=0 clean) and the 3x3
   checkerboard (checker=1 clean); not the boil (noiseDrift=1 same). Next suspects: the non-marched pixels reproject the
   wind at the block's one march distance (`cloud_resolve`: `dNow = curDist.read(mp)`, sky pixels at the slab's middle)
   -> try a per-pixel (bilinear) distance, a debug output of |puv - uv| and age per slot; or an always-on neighbourhood
   clamp to the current march.
2. Over budget (~2x), see 7.3. 3. Sunset away from the sun looks taupe (art). 4. Farthest horizon band: soft layering.

### Status (2026-10-07 22:15) - fixing Gael's sunset report + check findings before M3 integration

Gael's report (`work/notes/m3-2026-10-07/gael-report-sunset-lines/`, sunset_west: "líneas raras de reflejo en el mar").
A/B runs at head 4611a6f (scene sunset_west + water, one flag per run; `work/bench-results/20261007-2149..2204-fx*`,
crops `ab_*.jpg` in that folder, scripts `prefix-scripts/clouds-fx/`):
1. **Thin light lines across the sea/land + bright vertical columns over the far terrain**: NOT VL (same with
   `vl=false`, `vl.ssTaps=0`), not the cloud shadow (`clouds.shadows=false` same), not SSR; gone with `clouds=false`.
   Cause: `shade_m2.metal shadeFogAir2` (m3-clouds code) looked the clouds up at the CLAMPED direction (horizon at the same
   azimuth) for terrain/water below the horizon, so every pixel under a horizon cloud/gap got its value = vertical streaks.
   **FIXED (81d0eb8, built, validate ALL OK, in game run fx3-fogfix 22:08)**: look up at the pixel's own direction v;
   below the horizon only from under the layer (smoothstep(0,24, clouds0.x)). Lines and columns gone
   (`gael-report-sunset-lines/fix1_full_before_after.jpg`, `fix1_sea_before_after_vanillawater.jpg`).
2. **Bright rectangular patches on the water (bottom centre)**: identical with `ssr=false` and with `clouds=false` -> not
   clouds, not SSR, and NOT terrain: gone with `-Dmcopt.shade.water=false` (vanilla water, run fx3-vanillawater). So it is
   M2.1's water shader (shared code, `water.metal`): suspects the "rendered sky" path of `shadeWaterSkyRefl` (far terrain
   at the render-distance edge taken as sky, weight smoothstep(160, 320, linearW)) or the refraction of the bottom. Next:
   `-Dmcopt.shade.waterReflect=...`/`mcopt.shade.debug` views to pin it; fix belongs on m3 after integration (shared).
3. **Horizontal stripes in the far clouds near the horizon**: same with `checker=1` and `wind=0` -> not the checkerboard /
   reprojection: the march itself at grazing angles (long steps over a long path; suspects: the loop cap
   `3n+4` iterations running out in dense far cloud, the start offset t0 + jitter*dt aliasing per row). Result (runs fx4-budget /
   fx4-steps60 22:16, crop `gael-report-sunset-lines/fix3_horizon_clouds_fogfix_budget_steps60.jpg`): a bigger loop budget
   changed nothing (reverted), `steps=60` removed most stripes = step-size aliasing (100-200-block steps on long stretches).
   **Fix e9fe3a0 (in game run fx5-dtcap 22:18)**: steps on long stretches capped at ~48 blocks (n up to 3x steps; only the
   horizon band pays). Most see-through stripes gone, like steps=60 (`fix3_horizon_clouds_before_after.jpg`); a softer
   layering remains in the farthest band. Price NOT measured yet: next run `-Dmcbench.clouds=perf` A/B (e9fe3a0 vs 210a644).

Next steps (in order): price of e9fe3a0 (perf A/B); item 2 (pin the water path with `-Dmcopt.shade.debug` water views
5/6 = SSR hits+Fresnel / path+depth+floor; suspect `bottom` refraction of a shallow sunlit floor or the prev-frame sky
path; shared code -> integration note); then the check findings (comb with a still camera: diagnose with wind=0/checker=1 crops at
noon_south; inside/above re-shoot at 20 steps); then Status/Results + price. Previous status (20:15) below.

### Status (2026-10-07 20:15)

Done this session (resume after the 15:06 pause), all committed:
- WIP finished and kept: finer steps near the camera inside the cloud layer (the checkerboard seen from inside is gone);
  shadow map every 4th frame, which now keeps its own frame (origin, plane, light) between updates (a moving camera no
  longer shifts the shadows by a texel).
- Fixed: from above the clouds the deck was flat grey haze (horizon fade only for a camera below the layer).
- Distant Horizons: DH's own LOD clouds are switched off through DH's config API while ours draw (verified in DEV-DH, runs
  c13/c17); clouds also composite over DH's LODs when the camera is above them.
- VL: the cloud shadow per VL step is cheaper (affine along the ray, skipped inside terrain shadow).
- Balanced: 20 march steps (was 24).
- All 13 cloud scenes reviewed (7.1), a video of the spin (7.2), in-session prices (7.3), per-pass timer (5).

Open (next):
1. **Comb / hatching on cloud edges while turning** (7.2): needs the 3x3 checkerboard (checker=1 is clean); history clamp
   and motion blend did not help (tried, reverted). Suspect: the reprojection is off by ~a pixel per frame while turning;
   check `prevViewProj` / jitter with a debug output.
2. **Over budget**: +0.6-0.9 ms vs 0.30-0.45 (7.3); cloud shadows are the biggest identifiable part; ideas in 7.3.
3. Sunset away from the sun looks taupe (7.1); art pass.
4. Minor: speckle on far clouds seen from inside the layer; in DEV-DH from above, DH's near LOD square is unfogged (FT0/DH
   fog, not clouds).

## 1. Files

| File | What |
|---|---|
| `metal/src/main/resources/mcopt/shade/clouds.metal` | All cloud MSL: `CloudParams` (448 B, `static_assert`ed), noise bakes (compute), density, light march, multiple scattering, cirrus, blocky style, aerial perspective, passes `cloud_march`, `cloud_resolve`, `cloud_dome`, `cloud_shadow`, `cloud_over` |
| `metal/src/main/resources/mcopt/shade/shade_clouds.metal` | **New part of `ShadeMsl.prelude()`**: what every other shader reads: `shadeCloudShadow(p, L, map)`, `shadeCloudsScreen(dir, L, tex)`, `shadeCloudsDome(dir, L, dome)` |
| `metal/src/main/native/mcclouds.m` | Pipelines, bakes (64^3 RGBA8 3D noise, 512^2 RGBA8 weather map; once, on their own command buffer), targets, per-frame encoding on the frame's command buffer, GPU timer, `mc_clouds_over` |
| `metal/src/main/java/mcopt/metal/shade/clouds/ShadeClouds.java` | Module glue: per frame (from `ShadeFrameUpdater`, after the sky LUT, before the sky pass), weather/wind, params, native render, ShadeResources 12-14, ShadeLook fields; the before-tonemap hook (order 80) for clouds in front of the world |
| `.../clouds/CloudParams.java` | Pure maths: packs `CloudParams` and the ShadeLook cloud fields (cloud shadow projection, layer, strength, feature bit) |
| `.../clouds/CloudWeather.java` | The daily weather (pure maths) |
| `.../clouds/CloudConfig.java` | Flags and preset defaults |
| `.../clouds/CloudNative.java` | FFM downcalls to `mc_clouds_*` |
| `tools/shade/clouds/` | Offline preview without the game: `run.sh png` (real passes over N frames, PNGs per scenario: noon, sunset, dusk, night, rain, storm, daily weather samples...), `run.sh time` (GPU ms per pass at a given output size), `cloudpreview ... stats` (noise histograms) |

Edits to shared files (all small, all behind the module's flags or no-ops when it is off):

| File | Change |
|---|---|
| `shade_look.metal` | `ShadeLook.cloudShadowVP`, `clouds0`, `clouds1` documented (were reserved); `SHADE_FEATURE_CLOUDS 4096`; **`ShadeResources` grows to 16 entries**: 8-11 reserved, **12 `cloudShadow`, 13 `cloudDome`, 14 `cloudScreen`**, 15 reserved |
| `native/mcshade.m` | `SHADE_RESOURCES 16` |
| `ShadeMsl.java` | prelude += `shade_clouds.metal` |
| `ShadeFrameUpdater.java` | calls `ShadeClouds.update` after `renderSkyLut` |
| `ShadeSky.java` | `transmittanceAbove(altitudeKm, mu, mie, out)` (public helper: the light the cloud layer gets) |
| `MetalRenderPass.java` | `drawIndexed`: skips vanilla's `clouds`/`flat_clouds` draw while `ShadeClouds.replacesVanilla()` |
| `sky.fs.metal` | HDR: `sky = sky * T + S` from `shadeCloudsScreen` (in front of sun, moon, stars; behind everything drawn later) |
| `sodium_terrain.fs.metal` (PolyForm, header kept) | `ShadeResources& sr` declared for every HDR variant (was water only); `shadow *= shadeCloudShadow(p, sl, sr.cloudShadow)` (sky light > 0.3); `wi.res = &sr` and `shadeGlossy(..., &sr, ...)` for the dome |
| `water.metal` | (fe29a8f, not clouds: Gael's sunset report item 2) the sun glint widens with distance only at grazing views (`SHADE_WATER_GLINT_GRAZE`, flag `mcopt.shade.waterGlintGraze` in `ShadeWater.java`); `ShadeWaterIn.res` (ShadeResources pointer); `shadeWaterSkyRefl` / `shadeGlossy` take `constant ShadeResources *R`: the LUT sky gets the dome's clouds; the M2 hack that hazes vanilla clouds read back from `prevScene` is skipped while the volumetric clouds draw (they are hazed already) |
| `vl.metal` | `vl_march` takes ShadeLook (17) + ShadeResources (19): HDR: the march's sun visibility x `shadeCloudShadow` (shafts from cloud gaps); the sky mask = the clouds' transmittance there (crepuscular streaks from the clouds around the sun) |
| `tools/shade/ShadeMslDump.java` | dumps `clouds_passes.metal` (validate.sh compiles it) |
| `metal/build.gradle` | `mcclouds.m` |
| `work/mcbench` (own repo) | `CloudShots.java`: `-Dmcbench.clouds=all` scenes (see HARNESS.md) |

## 2. Frame

```
LevelRenderer.render HEAD -> ShadeFrameUpdater.update
  ShadeFrame/ShadeLook packed -> sky LUT
  ShadeClouds.update (needs: overworld, clouds option not OFF, the dimension has clouds; else vanilla's draw as before)
    CPU: CloudWeather (day = clock ticks / 24000; hashed per day + mcopt.shade.clouds.seed; changes 19:00..sunrise;
         rain/thunder on top, smoothed tau 4 s), wind accumulation (cumulus, 3D noise x0.82, cirrus x1.7), CloudParams,
         ShadeLook.cloudShadowVP/clouds0/clouds1/feature bit
    GPU (mcclouds.m, frame command buffer, before the shadow pass and the sky):
      cloud_shadow  256^2 R16F   every 4th frame (3 mod 4) transmittance toward the light on the plane base + 0.35 thickness
      cloud_march   hist/N       every frame               this frame's checkerboard slot: cumulus + cirrus, aerial perspective
                                                           -> (S, T) RGBA16F + apparent distance R32F
      cloud_resolve hist size    every frame               reprojection (camera + wind, Catmull-Rom) + blend (1/age, age <= 10..12) -> RGBA16F + R8 age
      cloud_dome    128x64       every 4th frame           by direction, upper hemisphere, blended 0.4
    -> ShadeResources 12 shadow, 13 dome, 14 screen history
"Sky" pass (sky.fs.metal): sky * T + S                  (clouds behind DH / terrain / water / glass, in front of the sun)
terrain (H4): direct light x shadeCloudShadow            water: same shadow (glint, caustics), dome in sky reflections
render3dHud -> ShadePost.beforeHand hooks:
  80 clouds over the world (only when the camera is above base - 48): cloud_over into the HDR scene
  100 VL: shafts x cloud shadow; sky mask = cloud T (streaks)
```

## 3. Flags (`mcopt.shade.clouds.*`, read once; `CloudConfig`)

| Flag | Default | |
|---|---|---|
| `mcopt.shade.clouds` | true | needs `mcopt.shade=true` and HDR; false = vanilla's clouds lit by M2's `clouds.fs.metal`, exactly as m2.1 |
| `.style` | volumetric (`blocky` with `mcopt.shade.look=storybook`) | `blocky`: vanilla-style 12-block cells, `.blockThickness` (8) |
| `.scale` | P 0.4, B 0.45, Q 0.5, U 0.67 | history size relative to the output (window framebuffer; independent of TAAU's render scale) |
| `.checker` | P 3, B 3, Q 2, U 2 | N: one pixel of each N x N block marched per frame |
| `.steps` / `.lightSteps` | P 20/3, B 20/3 (was 24 until the 2026-10-07 evening pricing), Q 40/4, U 56/5 | primary / light samples (empty air crossed with double steps on the 2D footprint) |
| `.maxAge` | 10 (N=3), 12 | most march samples a history pixel averages |
| `.shadows` / `.shadowStrength` / `.shadowExtent` | true / 0.8 / 1024 | cloud shadow map (256^2 over the extent, oblique projection along the light onto the layer) |
| `.dome` / `.domeSteps` | true / P 8, B 10, Q 12, U 16 | reflection dome |
| `.cirrus` / `.cirrusAbove` | true / 900 | cirrus layer and its height above the cumulus base |
| `.base` / `.thickness` | vanilla cloud height + 8 / 150 | cumulus layer (absolute y / blocks) |
| `.coverage` / `.type` | -1 (daily) | force coverage 0..1 / type 0 flat .. 1 towering |
| `.wind` / `.seed` | 1 / 0 | wind speed multiplier / daily weather seed |
| `.noiseDrift` | 0.82 | speed of the billow noise relative to the cumulus (1 = rigid drift; < 1 "boil"). 1 was tried against the still-camera comb: no change (runs cf7/cf2), so 0.82 stays |
| `.density` / `.detail` | 1 / 1 | extinction / fine detail multipliers |
| `.altitudeScale` | 4 | blocks -> metres of the sky model's air for the light the layer gets (sunset light lingers on the clouds) |
| `.octaveWeight` / `.octaveExtinction` / `.skyExtinction` / `.sunsetFill` / `.apSunset` / `.lightStep` / `.odStep` / `.odMin` | 0.68 / 0.48 / 0.2 / 0.4 / 1.5 / 4 / 2.5 / 0.5 | art and sampling knobs (see CloudParams) |
| `.withDh` | true | with Distant Horizons (which sets the Clouds option OFF) the volumetric clouds still draw |
| `.log` / `.gpuTimer` | false | a log line every 2 s (weather, sizes, light; with the timer: GPU ms per pass, upper bounds) |

## 4. How it works (short)

- **Slab on a curved world**: base..top (vanilla cloud height + 8 .. +150), altitude along a ray `y0 + t dy + t^2 (1 - dy^2) / 2R`
  with R = 40 000 blocks, so the layer bends down to the horizon (clouds reach it) and a high camera sees over it.
- **Density** (blocks): the 512^2 weather map (tile 4096 blocks) gives regional coverage (r) and cumulus cells (g); with the
  day's coverage a column's "core" 0..1; the vertical profile has a flat base and a dome whose height grows with the core
  and the day's type; the 3D noise (64^3, tile 120 blocks, mipmapped, mip by footprint) erodes billows by Worley distance
  squared (more where the cloud is thin), a 60-block-tile octave adds detail (faded with step size); edges sharpen with
  height; wind shear leans the tops downwind. Noise is spread to ~uniform at bake time (logistic CDF with measured
  mean/deviation: `cloudpreview ... stats`), so coverage c covers about c of the sky.
- **Lighting**: light march 3-6 steps from 4 blocks growing (reach ~90-110 blocks; first two with billows),
  4 multiple-scattering octaves (weight 0.68^i, extinction 0.48^i, phase lobes narrowing), phase = forward HG + a silver
  lining peak (HG 0.97) + a back lobe, powder on the sunlit side, sky light from above through what is left above (one
  sample) and bounce from below, the light at the layer from the sky model at the cloud's altitude (sunset light lingers),
  then the moon. Aerial perspective toward the LUT sky by apparent distance (lighter at low sun so sunsets stay coloured).
- **Cirrus**: one sample on a curved plane 900 blocks above the cumulus base, streaks along a fixed world axis, broad lobes.
- **Reconstruction**: march 1/N^2 of the history pixels per frame (N = 3 balanced), Bayer-like slot order, jittered
  start (IGN + golden ratio per visit), empty air crossed at double steps on the 2D footprint, in-cloud steps limited to
  2.5 optical depths; resolve reprojects the history with this frame's distances and the wind (Catmull-Rom), blends 1/age.
- **Blocky style**: 2D DDA over 12-block cells between the slab planes (8 thick), first filled cell's face lit (wrap,
  3-cell self shadow, sky/ground fill, forward rim), T = 0.08; same history path, shadows from the cells.
- **Cloud shadows**: 256^2 R16F over 1024 blocks, oblique projection along the light onto base + 0.35 thickness, 6 samples
  through the slab; consumers fade it near the map edge and above the base; strength 0.8, faded below 10 degrees of sun.

## 5. Cost (balanced, 3024x1964, M4 10-core GPU)

Whole-frame numbers are in section 7 (in-session A/B). Per pass, from `-Dmcopt.shade.clouds.gpuTimer=true` (counter samples
at the passes' fragment start/end; upper bounds; runs c11 and c12, 2026-10-07 18:08-18:52, AC, the overview point at noon):

| Pass | Size | How often | GPU ms when it runs | Per frame |
|---|---|---|---|---|
| march (`cloud_march`) | 454x295 rays (1/9 of the history) | every frame | 0.30-0.38 | 0.30-0.38 |
| resolve (`cloud_resolve`) | 1362x885 | every frame | 0.18-0.22 | 0.18-0.22 |
| shadow map (`cloud_shadow`) | 256x256 | every 4th frame | 0.03-0.04 | ~0.01 |
| dome (`cloud_dome`) | 128x64 | every 4th frame | 0.10-0.17 | ~0.03 |
| consumers: sky composite (1 fetch per sky pixel), terrain/water/VL cloud shadow (1 fetch per lit pixel / VL step), water dome | full res | every frame | (not timed separately) | |

Notes: the timer did not change the whole-frame A/B measurably (c12 with it +0.83 ms, c19 without it +0.83 ms, same
evening); the timed passes add up to ~0.55 ms per frame, the rest of the whole-frame difference is the consumers and the
passes' overlap with the frame. A history scale of 0.4
instead of 0.45 (21 % fewer rays and history pixels) did not move the timed passes nor the whole frame measurably (c12:
+0.84 vs +0.83 ms, both with the timer): at this size the passes are not limited by their pixel count.
Offline (`tools/shade/clouds/run.sh time`, needs a quiet GPU) the passes scale with: march ~ rays x in-cloud samples;
resolve ~ history pixels (bandwidth).

## 6. Integration notes (for the M3 integrator)

- **ShadeResources** now has 16 entries; clouds own 12-14. Other branches that extend it: keep 12-14, renumber yours.
- **Prelude order** is now shade_look + shade_common + **shade_clouds** + shade_m2 (shade_m2's `shadeFogAir2` uses
  `shadeCloudsScreen`; new optional last parameter `constant ShadeResources *R = nullptr`).
- **Other lit things** (entities, hand, particles, DH's own shaders in FT1/FT2, glass) should multiply their sun/moon
  term by `shadeCloudShadow(p, sl, sr.cloudShadow)` (needs `constant ShadeResources& sr [[buffer(19)]]`, HDR variants).
- **Sky pass** (`sky.fs.metal`) gets the composite inside `shadeSkyPixel` (HDR only); the aurora / End sky work (m3-skydims)
  touches the same function: keep `sky = sky * clouds.a + clouds.rgb` after everything that is behind clouds (stars,
  Milky Way, aurora) and before what is in front (nothing today).
- **Rain/wetness** (m3-weather): `CloudWeather` already darkens/thickens with `f.rain`/`f.thunder`; puddles could read
  `ShadeLook.clouds0` / the shadow map. A shared "weather state" could replace the rain smoothing here later.
- **VL** reads the cloud shadow per step and uses the clouds' T as its sky mask (HDR only); with VL off nothing changes.
- **Distant Horizons**: our clouds draw behind DH's LODs (sky pass). DH's own LOD clouds would draw on top of everything:
  while ours draw, `ShadeClouds.dhClouds` switches them off through DH's public config API (reflection:
  `DhApi.Delayed.configs.graphics().genericRendering().cloudRenderingEnabled().setValue(false)`, an API override that
  DistantHorizons.toml keeps nothing of; `clearValue()` when ours stop: pause, other dimension, failure). DH turns the
  game's Clouds option OFF; we ignore that while DH is loaded (`.withDh`). DH's LODs are not in the world depth, so the
  "clouds over the world" composite (camera above the layer) takes empty-depth pixels below the horizon as far terrain
  when DH drew this frame (`mc_clouds_over` mode 1).
- **`water.metal` glint (fe29a8f, also matters for m3 / m3-weather, which share the file)**: in `shadeWaterAbove` the
  glint's `alpha` line became `far = saturate(dist / 110) * (1 - smoothstep(0.1, 0.4, -v.y))` under
  `#ifdef SHADE_WATER_GLINT_GRAZE` (else the m2.1 `far = saturate(dist / 110)`), then `alpha = 0.035 + 0.10 * far + 0.06 *
  wp.state.z`. `ShadeWater.java`: flag `GLINT_GRAZE` (`mcopt.shade.waterGlintGraze`, default true), the define in
  `prelude()` after SHADE_SSR_EDGE, and the setup log line. The old line is unchanged on m3, m3-weather, m3-lights and
  m3-skydims (checked 23:25), so this should merge cleanly; if a later change touches the rain term (`wp.state.z`) keep
  both: only `dist / 110` gets the grazing factor.
- `mcopt.shade=false`: nothing of this runs (MetalRenderPass's skip is behind `ShadeClouds.ON`, a static final false).
  `mcopt.shade.clouds=false`: m2.1 behaviour (vanilla clouds, `clouds.fs.metal`), the prelude helpers return "no cloud".

## 7. Results (2026-10-07, provisional: final numbers are measured after the M3 integration)

Screenshots (full size JPEG) and a video: `work/release/M3-clouds-screenshots/`.

### 7.1 How it looks (in-game shots, `-Dmcbench.clouds=all`, runs c11 18:08 and c12 18:30)

| Scene | Verdict |
|---|---|
| noon, toward / away from the sun (`noon_south`, `noon_north`) | Big rounded cumulus with bright sunlit tops and grey bases, smaller ones to the horizon, which they reach and melt into the haze. Mirrored in the lake with their real shapes (the M2 complaint, "flat peach clouds in the water", is gone: `water`, `noon_south`). |
| looking up (`up`) | Thick cloud edge in front of the sun: bright silver lining along the edge, soft grey body. (The round glow around the sun with a visible rim is the sky's sun halo from M2, not the clouds.) |
| morning toward the sun (`morning_east`) | Strong forward scattering: the cloud in front of the sun glows white, silver rims; reflections in the water. |
| sunset toward the sun (`sunset_west`), dusk (`dusk`) | The best scenes: gold-lit sides, darker violet/grey bodies, orange-red undersides at dusk, god rays through the gaps. Close to Photon's look. |
| sunset away from the sun (`sunset_east`) | Correct but dull: taupe/brown clouds against a blue-grey sky (Photon gets pinker tops here). A variant with more sky fill (`sunsetFill=0.75`, run c15) changed little; left for the art pass. |
| night (`night`) | Moonlit grey-white cumulus with stars and the Milky Way between them. Very nice. |
| rain, thunder (`rain`, `thunder`) | The sky closes in: dark, heavy storm bases with lighter tops, a few blue gaps. |
| above the clouds (`above`, 380 high) | **Fixed this session**: from above, the whole deck showed as flat grey haze (the "fade into the horizon" from the afternoon only makes sense for a camera below the layer). Now: white cauliflower tops in front of the hazy ground. `clouds_above_BEFORE-fix.jpg` vs `clouds_above.jpg`. |
| inside the cloud layer (`inside`, 250 high) | **Fixed by the WIP finished this session** (finer steps near the camera inside the layer): the checkerboard pattern on the clouds against the sky is gone; a little speckle remains on far clouds seen through near fog. `clouds_inside_BEFORE-fix.jpg` vs `clouds_inside.jpg`. |

### 7.2 While turning (video `nubes_giro_M3_vs_antes.mp4`: 12 s of our clouds turning a full circle, then 12 s of the old ones)

Recorded with the in-game recorder (run c16, 1512x982 60 fps; the bench turns 30 degrees per second). Most of the sky is
stable: no crawling noise, no smearing behind the clouds, the clouds keep their shapes. **Open problem:** some cloud edges
that move across the screen show a **comb / hatching pattern** (teeth ~6 history pixels apart, i.e. 2 march blocks), most
visible on bright edges against blue sky (frames `giro_peine_*.png`). The vanilla clouds in the same video are clean, so it
comes from our checkerboard reconstruction. Tried this session: clamping the reprojected history to this frame's march
range while the view moves (the usual TAA cure for resampling build-up): no visible change in the same frames (run c20),
so it was reverted. Diagnostics (same spin, same frame of the video, crops in `work/release/M3-clouds-screenshots/`):

| Run | Setting | Moving cloud edge |
|---|---|---|
| c16 / c20 | default (3x3 checkerboard) / + history clamp while moving | comb (`giro_peine_zoom.png`) / same comb |
| c22 | `checker=1` (every history pixel marched every frame) | **clean**, a little soft |
| c24 | non-marched pixels lean 60 % toward this frame's upsampled march while turning (tried, reverted) | same comb, hatching spreads inside the clouds |

Reading: the comb needs the checkerboard, and neither clamping nor feeding the current march into the history removes it.
That points at the **reprojection itself being off by a pixel or so per frame while turning** (under a pure rotation it
should be exact at any distance): each pixel is corrected only when it is marched (10 % weight, once every 9 frames), so
the error piles up differently per checkerboard slot = teeth; with checker=1 every pixel is corrected every frame, the
error stays small and uniform (soft edge instead of a comb). To check next: whether `prevViewProj` (ShadeFrameUpdater:
last frame's matrix shifted by the camera motion; with the TAA jitter removed in CloudParams.pack) really is the matrix
the history was rendered with on every frame (e.g. a frame where the level renders twice, or the jitter not being in
`f.viewProj`), e.g. with a debug mode that outputs |puv - true previous uv| while the bench turns.

### 7.3 Cost (in-session A/B: phases `clouds` / `clouds_off` / `clouds2`)

Balanced, DRS off, 3024x1964, noon, the overview point turning a full circle 8 degrees up in 12 s (about half the screen is
sky); `clouds_off` = `mcopt.shade.clouds.pause`, i.e. m2.1's vanilla clouds (which cost a little themselves). Whole-frame
GPU ms (`gpu.gpuMsPerFrame`).

| Run (time, power) | Build / flags | clouds | clouds_off | clouds2 | Cost (on - off) | Clean? (cpu-load during the phases) |
|---|---|---|---|---|---|---|
| c9 (14:38, battery) | 3e78c47-era, balanced | 8.38 | 7.83 | 8.33 | **+0.55 / +0.50** | yes (108-166 %) |
| c10 (14:58, AC) | same | 8.69 | 7.95 | 8.50 | **+0.74 / +0.54** | yes (112-195 %) |
| c11 (18:08, AC) | WIP + timer | 8.75 | 8.22 | 8.70 | +0.53 / +0.48 | no: 433-794 % in clouds_off |
| c12 (18:30, AC) | + fixes, timer | 8.97 | 8.14 | 8.97 | **+0.83 / +0.83** | yes except the first seconds |
| c12 (18:51, AC) | same, `scale=0.4` | 8.86 | 8.07 | 8.95 | +0.79 / +0.88 | partly (484 % at the start) |
| c19 (19:14, AC) | branch head code, no timer | 9.34 | 8.42 | 9.17 | **+0.92 / +0.74** | yes (101-117 %) |
| c19 (19:37, AC) | same, `steps=16` | (9.62) | 8.62 | 8.84 | (contaminated) / +0.22 | clouds phase no (544-761 %), rest yes |
| c19 (19:53, AC) | same, `shadows=false` (no cloud shadow map, no shadow fetch in terrain/water/VL) | 8.63 | 8.11 | 8.49 | **+0.52 / +0.38** | yes (106-137 %) |
| c23 (19:56, AC) | same, `steps=20` | 8.28 | 7.69 | 8.36 | **+0.58 / +0.66** | yes (101-141 %) |
| c23 (20:08, AC) | same, `steps=16` | 9.20 | 8.31 | 9.19 | **+0.89 / +0.89** | yes (106-189 %) |
| c26 (20:07, AC) | + VL cloud-shadow fetch along the ray / skipped in terrain shadow, `steps=24` | 8.64 | 7.97 | 8.67 | **+0.67 / +0.70** | yes (102-147 %) |
| c26 (20:12, AC) | **branch head** (+ VL change, 20 steps) | 9.13 | 8.24 | 8.86 | **+0.89 / +0.63** | yes (85-116 %) |
| fx6 (22:20, battery) | e9fe3a0 (fog fix + horizon dt cap), DRS off | 8.66 | 7.90 | 8.71 | **+0.76 / +0.80** | yes (120 %) |
| cf1 (22:48, battery) | 666b4b4 (+ noiseDrift 1; same cost) | 9.15 | 8.39 | 8.97 | **+0.76 / +0.58** | yes (109 %) |
| cf6 (23:07, AC) | 210a644 (fog fix, **before** the dt cap) | 9.10 | 8.25 | 9.05 | **+0.85 / +0.80** | yes (78 %) |

Reading, in plain words: the volumetric clouds cost **about 0.6-0.9 ms of GPU per frame at balanced** on this scene
(about 7-10 % of the frame; ~115 instead of ~125 fps here), against the bible's E05 budget of 0.30-0.45 ms: **over
budget, roughly by 2x**. Between runs the same build moves by +-0.15 ms (the whole frame itself drifted between 7.7 and
8.6 ms this evening with nothing changed: other agents, thermal state), so only the bracketed in-run deltas mean anything,
and differences under ~0.2 ms between runs are noise. What the variants say:
- the march's size does not drive it: history scale 0.4 vs 0.45 and 16 / 20 / 24 steps all land in the same 0.6-0.9 band
  (the one early "+0.22 ms at 16 steps" phase did not repeat: noise);
- the cloud shadows (the map plus one fetch per lit pixel in terrain/water and per VL step) are the biggest identifiable
  part: without them +0.38-0.52 ms. The VL fetch was made cheaper (no matrix, skipped inside terrain shadow; c26);
- the timed passes add up to ~0.55 ms per frame (section 5), the rest is the consumers and the extra render passes.
Balanced now marches 20 steps (same look in the shots, a little less work), but the saving is below the noise.
Ways to reach the budget (not done, for the integration/next step): shadow fetch only where the sun term is non-zero in
terrain (as done in VL), cloud shadow in VL every other step, the dome and shadow map in the same command encoder as the
march (fewer render passes), resolve + march at checker 3 with a smaller history for balanced, or accept ~0.6 ms as the
price (the sky is most of the screen in this scene: the worst case).

## M3.1 performance (2026-10-08, branch `m3.1-clouds` from m3-int 857bd0e)

### Tools (for pricing inside one session)
- `mcopt.shade.clouds.dev` (int bits, **read every frame**; 0 normally; `CloudConfig.DEV_*`): passes reach them in
  `CloudParams.lightDir.w`, consumers in `ShadeLook.clouds1.z` (`shadeCloudDev`, shade_clouds.metal).
  Pricing: 1 no march+resolve, 2 no shadow map/dome pass, 4 no sky composite, 8 no fog lookup, 16 no cloud-shadow use
  (clouds0.z = 0), 32 VL ignores clouds, 4096 history scale `mcopt.shade.clouds.scaleAlt` (0.4).
  M3 way back: 256 fog, 512 VL, 1024 resolve, 2048 march sky lookup, 8192 noise mips, 16384 dome cadence
  (28416 = all of them = M3; `mcopt.shade.clouds.lean=false` sets them all).
- mcbench (`CloudShots`): `-Dmcbench.cloudsDev=<bits|off>,...` adds phases `clouds_d1..d8` after `clouds` (each with
  those dev bits; `off` = paused like clouds_off) -> several A/B pairs per session. Script:
  `work/notes/m3-2026-10-07/clouds-m31/run.sh <jar> <label> <list> [jvm] [shots]`; analysis
  `clouds-m31/summary.py <result dir>` (per phase + least-squares fit gpu = a + b*phase + offset[dev]: drift-corrected
  price of each variant, with the residual sd); `clouds-m31/imgdiff.py` (PNG diffs).
- Offline: `CLOUDS_DEV=<bits> tools/shade/clouds/run.sh time 3024 1964 0.45 24` (real passes, quiet GPU).

### Where the cost is (run `20261008-075402-m31c-1`, balanced, DRS off, battery, perf spin 8 deg up)
Drift-fit offsets vs all on: no passes (dev 3) **-0.64 ms**; no sky composite +0.04, no fog -0.06 (lean fog already),
no cloud-shadow use -0.02, no VL clouds +0.02 (all noise); paused (vanilla clouds) -0.67. So **the price is the passes**;
the consumers are ~0 once gated (vanilla clouds in "off" cost about what the consumers do). In-game GPU timer per frame:
march **0.40-0.45**, resolve **0.19-0.21**, shadow 0.03-0.05 / 4, dome 0.11-0.12 per update.
Offline (quiet GPU): march 0.21-0.27, resolve 0.18 (= DRAM bandwidth: ~18 B/px at 1362x885), shadow 0.045, dome 0.10.
March split (offline experiments): pass overhead 0.04, primary density march ~0.11, light march ~0.10 (its 3D-noise
samples ~0.05), dUp ~0.01, fine detail ~0. The march does not scale with ray count (scale 0.36: -36 % rays, -20 % time;
checker 1: 9x rays, 4.8x time): fetch coherence and the long horizon rays dominate.

### What changed (each commit; flag `mcopt.shade.clouds.lean`, default true)
| Commit | Change | Dev bit (M3 way) | Measured |
|---|---|---|---|
| e8719e7 | `shadeFogAir2` (shade_m2.metal, shared): the clouds behind terrain are looked up only where their blend weight max(ap*0.6, border-fog edge) can show (faded in 1.5-3.5 %: terrain nearer than ~75 blocks skips the fetch; border fog keeps full clouds) | 256 | part of the run-2 OLD delta |
| e8719e7 | `vl.metal` (shared): cloud shadow fetched on at most every other lit VL step, held for the next | 512 | ~0 |
| e8719e7 | `cloud_resolve`: the 5 march distances by 2 gathers | 1024 | resolve -3..-6 % offline |
| e8719e7 | `cloudSky`: sky LUT lookup only where a cloud was hit (exact) | 2048 | march -2 % offline |
| 54d3c0c | **noise mips**: M3's `cloudRep` sampler had no mip filter, so `level(lod)` was ignored (level 0 at any distance). Now `cloudRepMip` (nearest level, lod dithered per sample; shadow map stays level 0) | 8192 | march and dome **-10..-15 % offline**; image diff mean 0.3/255 (far billows only) |
| b89cbb5 / 91fad2d | reflection dome every 8th frame at performance/balanced (`mcopt.shade.clouds.domeEvery`) | 16384 | ~-0.014 ms/frame |

### Prices (in session, drift-fit, balanced, DRS off, battery)
| Run | M3.1 (lean) vs paused | M3 path (dev bits) vs M3.1 | so M3 price |
|---|---|---|---|
| m31c-1 (07:54, jar 1) | +0.67 | +0.25 (dev 3840, one phase) | ~0.92 |
| m31c-2 (08:07, jar 2) | +0.65 | **+0.22** (3 old phases vs 4 new, sd 0.07) | ~0.87 |
| m31c-4 (08:14, jar 4, 3 off + 3 old + 4 new phases) | **+0.71** | **+0.06** (sd 0.07) | ~0.77 |
Reading: the clouds now cost **~0.65-0.71 ms** (before: ~0.77-0.92 in the same sessions). The two careful runs disagree
on the saving (0.22 vs 0.06 ms): the honest figure is **~0.1-0.2 ms saved**, target 0.5 not reached. History scale 0.4
(dev 4096, m31c-2): resolve timer 0.20 -> 0.15 but whole frame +0.03 +- 0.07 (no gain measured): not adopted.

### Look
Shots (`bench-results/20261008-080722-m31c-2` new, `20261008-081233-m31c-3-oldlook` M3; noon_south, sunset_west, water,
inside, above): same character, no new banding/comb/blocks; the sunset sea keeps Gael's fixes (no light lines, no
rectangles; the faint glints in the sunlit gaps are in both). The clouds moved between the runs (wind), so the pixel
diff is not meaningful: **next: re-shoot both with `-Dmcopt.shade.clouds.wind=0`** (same clouds) and diff.

### Tried and dropped (offline, run.sh time)
mip_filter::linear (no gain: two levels), far light samples with the noise mean (no gain), steps 12 (~0), 4x4 not tried
(comb risk), history scale 0.4/0.36 (resolve scales, march barely).

### Next steps (to reach <= 0.5 ms)
1. Empty-space skip in the march: a max-reduced mip of the weather map (g + 0.55 r) baked once, coarse steps of 64+ blocks
   where no cell can hold cloud (the primary density march is ~0.11 ms offline).
2. The light march (~40 % of the march): amortise (e.g. a low-res light-transmittance volume around the camera every few
   frames) instead of 3 samples per in-cloud step.
3. The resolve is DRAM-bound (18 B/px): only fewer pixels or fewer bytes help (R11G11B10 S + R8G8 T/age = 12 B/px, but
   watch requantisation banding).
4. Run the cloud passes off the critical path (async compute queue / the previous frame's history sampled with
   prevViewProj) so their latency-bound tails overlap terrain work.

### Shared files touched (integration)
`shade_m2.metal` (shadeFogAir2: `edge` computed before the clouds block, gate; behaviour otherwise identical),
`vl.metal` (cloud shadow hold, dev bits), `sky.fs.metal` (dev bit 4 only), `shade_clouds.metal` (dev helpers),
`clouds.metal`, `CloudConfig/CloudParams/ShadeClouds.java`, `tools/shade/clouds/*` (CLOUDS_DEV),
`work/mcbench` (`CloudShots.java`, `BenchDriver.REC_PHASES` + clouds_d1..d8; commits 862d838, a52ef55).
`ShadeLook.clouds1.z` now carries the dev bits (was reserved), `CloudParams.lightDir.w` too.
