# Volumetric clouds (M3 module, branch `m3-clouds` from `m2.1`)

## Status (2026-10-07 22:15) - fixing Gael's sunset report + check findings before M3 integration

Gael's report (`work/notes/m3-2026-10-07/gael-report-sunset-lines/`, sunset_west: "líneas raras de reflejo en el mar").
A/B runs at head 4611a6f (scene sunset_west + water, one flag per run; `work/bench-results/20261007-2149..2204-fx*`,
crops `ab_*.jpg` in that folder, scripts `prefix-scripts/clouds-fx/`):
1. **Thin light lines across the sea/land + bright vertical columns over the far terrain**: NOT VL (same with
   `vl=false`, `vl.ssTaps=0`), not the cloud shadow (`clouds.shadows=false` same), not SSR; gone with `clouds=false`.
   Cause: `shade_m2.metal shadeFogAir2` (m3-clouds code) looked the clouds up at the CLAMPED direction (horizon at the same
   azimuth) for terrain/water below the horizon, so every pixel under a horizon cloud/gap got its value = vertical streaks.
   **Fix written (WIP commit 22:15, not yet built/tested in game)**: look up at the pixel's own direction v;
   below the horizon only from under the layer (smoothstep(0,24, clouds0.x)).
2. **Bright rectangular patches on the water (bottom centre)**: identical with `ssr=false` and with `clouds=false` -> not
   clouds, not SSR. Probably real sand blocks at/just below the surface lit by the low sun; to confirm with
   `-Dmcopt.shade.water=false` (vanilla water) in the next batch.
3. **Horizontal stripes in the far clouds near the horizon**: same with `checker=1` and `wind=0` -> not the checkerboard /
   reprojection: the march itself at grazing angles (long steps over a long path; suspects: the loop cap
   `3n+4` iterations running out in dense far cloud, the start offset t0 + jitter*dt aliasing per row). Next: test
   `steps=60`, and a larger iteration cap / per-row jitter decorrelation.

Next steps (in order): build + validate; shot batch sunset_west,water with the fog fix (+ `water=false` for item 2,
+ `steps=60`); fix item 3; then the check findings (comb with a still camera: diagnose with wind=0/checker=1 crops at
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
| `water.metal` | `ShadeWaterIn.res` (ShadeResources pointer); `shadeWaterSkyRefl` / `shadeGlossy` take `constant ShadeResources *R`: the LUT sky gets the dome's clouds; the M2 hack that hazes vanilla clouds read back from `prevScene` is skipped while the volumetric clouds draw (they are hazed already) |
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
