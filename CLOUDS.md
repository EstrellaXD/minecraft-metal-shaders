# Volumetric clouds (M3 module, branch `m3-clouds` from `m2.1`)

Shader bible E05: volumetric cumulus (multiple scattering with powdery cores, silver linings, warm sides at sunset, dark
storm bases), a cirrus layer, cloud shadows on terrain, water and god rays, a daily weather (coverage, cloud type, wind
change from day to day; rain and thunder close the sky in), rendered at a fraction of the output resolution with a
checkerboard + temporal reconstruction, composited behind the world in the sky pass (so Distant Horizons, glass and water
occlude them correctly), mirrored in water (a reflection dome for directions off screen), and a "blocky" style
(vanilla-like 12-block cells, ray-traced and lit) for the Storybook look. Replaces vanilla's clouds while it draws.

Code: original; parts adapted from Photon (MIT, commit 26c2906e, allowed by bible §8) with the attribution line in
`clouds.metal`'s header: the cumulus altitude shaping idea, the Worley "dampen" detail erosion, the powder shape, the cloud
phase mix, the multiple-scattering octave loop, the aerial-perspective blend and the checkerboard + reprojection upscaling.
The noise is generated on the GPU at start (no data files).

## 1. Files

| File | What |
|---|---|
| `metal/src/main/resources/mcopt/shade/clouds.metal` | All cloud MSL: `CloudParams` (432 B, `static_assert`ed), noise bakes (compute), density, light march, multiple scattering, cirrus, blocky style, aerial perspective, passes `cloud_march`, `cloud_resolve`, `cloud_dome`, `cloud_shadow`, `cloud_over` |
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
      cloud_shadow  256^2 R16F   every other frame (even)  transmittance toward the light on the plane base + 0.35 thickness
      cloud_march   hist/N       every frame               this frame's checkerboard slot: cumulus + cirrus, aerial perspective
                                                           -> (S, T) RGBA16F + apparent distance R32F
      cloud_resolve hist size    every frame               reprojection (camera + wind) + blend (1/age, age <= 6..8) -> RGBA16F + R8 age
      cloud_dome    128x64       every other frame (odd)   by direction, upper hemisphere, blended 0.3
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
| `.scale` | P 0.4, B 0.5, Q 0.5, U 0.67 | history size relative to the output (window framebuffer; independent of TAAU's render scale) |
| `.checker` | P 3, B 3, Q 2, U 2 | N: one pixel of each N x N block marched per frame |
| `.steps` / `.lightSteps` | P 24/4, B 32/4, Q 48/5, U 64/6 | primary / light samples (empty air crossed with double steps on the 2D footprint) |
| `.maxAge` | 6 (N=3), 8 | most march samples a history pixel averages |
| `.shadows` / `.shadowStrength` / `.shadowExtent` | true / 0.8 / 1024 | cloud shadow map (256^2 over the extent, oblique projection along the light onto the layer) |
| `.dome` / `.domeSteps` | true / P 8, B 10, Q 12, U 16 | reflection dome |
| `.cirrus` / `.cirrusAbove` | true / 900 | cirrus layer and its height above the cumulus base |
| `.base` / `.thickness` | vanilla cloud height + 8 / 150 | cumulus layer (absolute y / blocks) |
| `.coverage` / `.type` | -1 (daily) | force coverage 0..1 / type 0 flat .. 1 towering |
| `.wind` / `.seed` | 1 / 0 | wind speed multiplier / daily weather seed |
| `.density` / `.detail` | 1 / 1 | extinction / fine detail multipliers |
| `.altitudeScale` | 4 | blocks -> metres of the sky model's air for the light the layer gets (sunset light lingers on the clouds) |
| `.octaveWeight` / `.octaveExtinction` / `.skyExtinction` / `.sunsetFill` / `.apSunset` / `.lightStep` | 0.68 / 0.48 / 0.2 / 0.4 / 1.5 / 4 | art knobs (see CloudParams) |
| `.log` / `.gpuTimer` | false | a log line every 2 s (weather, sizes, light; with the timer: GPU ms per pass, upper bounds) |
