# M1 "Shaders on Metal v0.1": status (branch `m1`)

**Branch:** `m1` = `shade` + `m1-shade` (infrastructure, lit terrain, sky) + `m1-shadow` (sun shadow pass), plus the
integration and tuning commits.

**Build:**

```
JAVA_HOME=<work>/tools/jdk-25.0.4.1+1/Contents/Home ./gradlew --no-daemon :metal:build
```

The output is `metal/build/libs/metal-0.2.0-alpha.3.jar`. Everything is behind `-Dmcopt.shade=true`, or
`mcopt.shade=true` in `config/mcopt.properties`.

**Result:** M1 runs at native Retina 3024x1964 at ~250 fps in the spin and ~210 fps in the flight, about twice the
120 fps target. That is about 11x Iris + Complementary at the same resolution, and 5x Iris at 1080p. With the flag off
the mod behaves like upstream.

## Numbers

Bench route of `work/bin/mcbench-run`: 20 s spin plus 20 s flight, RD 16, 3024x1964 exclusive fullscreen, M4 14", AC
power. The machine was in use during all runs (browsers, other agents), so treat values as ±10 %.

| Config | Spin avg fps | Spin 1% low | Flight avg fps | Flight 1% low | GPU ms/frame (spin / flight) | Runs |
|---|---|---|---|---|---|---|
| Baseline mcopt Metal (upstream, M0) | 528 | 109 | 407 | 87 | 1.85 / 2.36 | median of 3 (M0) |
| Iris + Complementary, GL, 3024x1964 | 22.4 | 14.0 | 21.4 | 10.5 | n/a | median of 3 (M0) |
| Iris + Complementary, GL, 1920x1080 windowed | 43.3 | 20.1 | 41.9 | 21.3 | n/a | median of 3 (M0) |
| **M1 shade ON** (final defaults) | **249** (246.5, 251.3) | 67 (63.8, 70.6) | **209** (201.8, 216.2) | 65 (61.9, 68.8) | **3.94 / 4.69** | m1-final3, m1-final4 |
| M1 shade ON, earlier build with camera casters | 263.8 | 93.8 | 218.5 | 65.3 | 3.76 / 4.49 | m1-final2 |
| **M1 shade OFF** (same jar, `-Dmcopt.shade=false`) | **578** (578.5, 578.3) | 214 | **460** (452.4, 467.1) | 193 | 1.72 / 2.17 | m1-off, m1-off2 |

- **Shade OFF:**
  - Same frame rate as upstream. These runs were measured later on a quieter machine, so they are faster than the M0
    baseline medians.
  - Screenshot differences against the M0 baseline screenshots are in the same range as baseline-vs-baseline (mean
    0.7–5.4 / 255, from moving clouds, water and rain).
  - No shade mixin applies, the shader registry is empty, and the bind hooks sit behind a `static final false`.
- **Shading cost:** about +2.1 ms (spin) and +2.5 ms (flight) of GPU per frame at full Retina. Breakdown in
  `OPTIMIZATION_LOG.md`:
  - terrain lighting maths: 0.7–1.3 ms;
  - shadow lookups: ~0.3 ms;
  - shadow passes: ~0.25 ms plus 0.15 ms for light-space casters;
  - sky: 0.2–0.4 ms;
  - water: ≤0.2 ms;
  - clouds, weather and LUT: small.
- **1% lows:** about 65 fps with shade ON, against about 200 with it OFF. Slow frames come in clusters at a few fixed
  view directions; there is no 4-frame cascade periodicity. This is the main open performance item (see
  OPTIMIZATION_LOG.md, "Open").

Result dirs, under `work/bench-results/`:
- `20261006-145945-m1-final3`
- `20261006-150157-m1-final4`
- `20261006-145357-m1-final2`
- `20261006-144806-m1-off`
- `20261006-150404-m1-off2`

M0 baselines are listed in HARNESS.md.

## Screenshots (final defaults, full 3024x1964)

All in `work/bench-results/20261006-150157-m1-final4/screenshots/`:
- `noon_overview.png`
- `forest_closeup.png`
- `sunset_water.png`
- `night_torches.png`
- `rain.png`
- Extra shots via `-Dmcbench.extraShots`: `morning_forest.png`, `afternoon_overview.png`, `underwater.png`

For comparison:
- shade OFF: `20261006-150404-m1-off2/screenshots/`
- Iris + Complementary: `20261006-131520-baseline-iris-gl-retina/screenshots/`
- shadow-cascade debug tiles (`-Dmcopt.shade.debug=1`, light-space casters): `20261006-145712-m1-light/screenshots/`

## What works

### Sun and moon shadows
- **Cascades:** 2 cascades at 2048² Depth32F in one 2D-array texture.
  - Near cascade: ±32 blocks, pushed 40% ahead of the view, redrawn every frame.
  - Far cascade: ±128 blocks, world-anchored, redrawn every 4 frames or when the light or camera moves.
- **Stability:** texel-snapped and stable when walking and turning.
- **Bias:**
  - casters: raster bias, constant plus slope-scaled, with pancaking;
  - receivers: offset along the normal plus toward the light, scaled by tanθ.
- **Lookup:** cascades are chosen by box with a blend band; the depth range falls back to the far cascade; filtering
  is a deterministic 3x3 tent PCF.
- **Casters (new default `shadowCull=light`):** every loaded section in the light box casts, so shadows behind or above
  the camera don't pop when turning.
- **Leaves:** alpha-tested in the near cascade, opaque in the far one.
- **Tests:** the headless test still gives 0 acne and 0 leaks (`tools/shade/shadowtest/run.sh`).

### Sun path tilt
- `mcopt.shade.sunTilt`, default 30°: the sun and moon orbit tilted about world x.
- Noon shadows show instead of falling straight down. Sunrise and sunset stay due east and west.
- Shading, the sky, the shadow map and the moon all follow the tilt.

### Lit terrain (plain and pulled Sodium paths)
- **Direct light:** sun or moon N·L × shadow.
- **Sky fill:** directional (up, toward the sun, away from it, ground bounce), scaled by sky light.
- **Torches:** warm light from block light.
- **AO:** vanilla's.
- **Foliage:** leaves get wrapped light (canopies glow instead of going flat); crossed plants are two-sided.
- **Finish:** eye-adapted exposure, tone curve, night "rods" desaturation.
- **Under water:** the camera under water filters daylight blue-green.
- **Other dimensions:** Nether and End fall back to vanilla's lightmap and face shade. This is untested in game.

### Atmosphere sky
- Rayleigh, Mie and ozone single scattering, through a 128x80 LUT rendered per frame.
- Round sun disc with limb darkening; sky saturation 1.3.
- Terrain fog blends into the same LUT, so the horizon is seamless.
- Vanilla's moon and stars are kept; the moon follows the tilt.

### Clouds and weather
- **Clouds:** both vanilla pipelines are re-lit (fragment override): sun, a forward glow toward the light, sky and ground
  fill, aerial perspective and the same tone curve. They no longer show as flat grey or black blocks.
- **Rain and snow:** streaks are re-lit grey-white in the scene's light instead of saturated blue, and rain scenes get
  a darker exposure.

### Water (an M1 stand-in; real water is M2)
- Top faces seen from above get a Fresnel reflection of the sky LUT, gentle procedural waves and a sun glint, gated by
  sky light.
- No SSR, so terrain is not reflected.

### Debug views and tooling
- **Debug views:** `mcopt.shade.debug` = 1 (shadow visibility tint plus cascade tiles), 2 (normals), 3 (lighting only).
- **`tools/shade/validate.sh`:** every MSL variant compiles, and real PSOs are built. It now also covers clouds and
  weather against vanilla's generated stages with `MCOPT_GEN_DIR=<dumpMsl dir>`.
- **`tools/shade/preview/run.sh`:** art preview. **`tools/shade/shadowtest/run.sh`:** shadow correctness.
- **In-game pricing:**
  - `-Dmcopt.shade.dev=<bits>`: features off, for pricing;
  - `-Dmcopt.metal.trace=spin+N`: per-encoder GPU profile on the bench route.
- **Harness:** `-Dmcbench.extraShots=name@time@x,y,z,yaw,pitch[@rain];...` gives opt-in extra screenshots
  (mcbench commit 940bca0).

## Flags (all also readable from `config/mcopt.properties`)

| Flag | Default | |
|---|---|---|
| `mcopt.shade` | false | The switch |
| `mcopt.shade.shadows` | true | |
| `mcopt.shade.shadowRes` | 2048 | |
| `mcopt.shade.shadowDist` | 128 | |
| `mcopt.shade.shadowNear` | dist/4 | |
| `mcopt.shade.shadowCull` | **light** | or `camera` |
| `mcopt.shade.shadowFarEvery` | 4 | |
| `mcopt.shade.shadowFarCutout` | opaque | |
| `mcopt.shade.shadowDepth` | 32 | |
| `mcopt.shade.shadowBias` | 1 | |
| `mcopt.shade.shadowBiasTexels` | 1 | |
| `mcopt.shade.shadowStats` | false | |
| `mcopt.shade.sunTilt` | 30 | |
| `mcopt.shade.debug` | 0 | |
| `mcopt.shade.dev` | 0 | |

## Integration notes (for later merges)

### Shared files
- `shade_common.metal`:
  - The infrastructure's file is kept, and the shadow contract is summarized in its header.
  - `shadeShadow()` now implements the shadow branch's receiver maths. `shade_shadow.metal` is kept for the headless
    test and holds the same maths.
  - `cascadeSplits.zw` = blocks per texel of each cascade.
  - `flags3` was renamed `sunPath` (x sin tilt, y cos tilt, z dev bits).
- `ShadeShadows.java` is the glue:
  - Before packing, it takes the matrices, splits and light direction from `ShadowPass`, so N·L uses the shadow pass's
    own light.
  - After the depth passes, it binds the map at texture(16) and turns sampling off for frames that weren't drawn.
- `ShadowPass` switches to the moon at the same tilted sun height as `ShadeSky` (-0.06).

### Edits to existing mcopt files beyond the two branches
- `MetalEncoder`:
  - the phase-based trace start (`TracePhase`);
  - `println` instead of `printf` for the GPU profile lines.
- Both changes are inert unless `-Dmcopt.metal.trace` is set.

## Known issues / not done

- **1% lows are about 3x lower than with shade OFF:** 60–95 vs about 200 fps. They come in clusters at fixed view
  directions. Not yet explained; next step in OPTIMIZATION_LOG.md.
- **Horizon haze:** at noon the band between distant terrain and the sky is pale grey-lavender. That is the
  physical horizon whitening plus render-distance fog. It is less saturated than vanilla's light blue; art call for the
  user.
- **Under water:**
  - The view is vanilla's vivid blue fog. The clear colour shows behind distant geometry, so the fog colour can't be
    changed alone.
  - Kelp is still a little bright.
- **Not shaded:**
  - Entities, block entities, particles and the hand keep vanilla lighting and fog, and are not tone-mapped. Not visible
    in the bench (spectator, no mobs).
  - They don't cast shadows either (M3).
  - Translucent blocks don't cast; coloured shadows are M3.
- **Water is a stand-in:** sky-only reflection with no SSR, refraction or depth fog (M2). Some glint aliasing appears
  near the sun at sunset.
- **Untested in game:**
  - Nether and End (vanilla fallback code paths);
  - caves (no bench scene; shadow lookups are skipped below sky light ~4);
  - the GUI or hand with shading;
  - far terrain (`mcopt.lod`), which is not shaded: keep it off with shaders.
- **Clouds:** the face direction is derived from vanilla's per-face shade value in the vertex colour. If a resource pack
  or a future version changes those values, cloud faces get the wrong light.
- **Rain:** `ShadeMsl.weather` patches vanilla's generated weather fragment textually. If the shape changes it falls
  back to vanilla, with no crash.
- **Art sign-off pending:** the user has not signed off on day, sunset and night. Tuning knobs:
  - `ShadeSky`: `AMBIENT_BOOST` 8, `AMBIENT_DESATURATE` 0.4, `SKY_SATURATION` 1.3, `EXPOSURE_MAX` 2.1, `KEY`,
    `ADAPTATION`;
  - `ShadeFrame.blockLight`;
  - `Shade.SUN_TILT`.
- **User's Iris instance:** "mcopt Shaders 26.3" will crash with Iris under mcopt's alpha profile (see HARNESS.md); use
  `mcopt.chunk.mesh=air,bounds,vis,biome` or `profile=none` there.

## Next steps

1. Investigate the 1% low clusters: `-Dmcopt.metal.trace=spin+<frame of a cluster>`, and check PSO variant
   compilation and upload timing.
2. Get the user's art sign-off on the screenshot set, then tune the ShadeSky knobs.
3. Ship as a new Prism instance, e.g. "mcopt Shaders Metal (M1)": a copy of "mcopt Metal 26.3" with this jar and
   `mcopt.shade=true` in `config/mcopt.properties`. Needs the user's OK.
4. M2: HDR target and post tonemap, real water (SSR, refraction), TAA/TAAU, a material mask for leaves, emissives and
   water, and shaded entities and clouds.
