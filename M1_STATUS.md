# M1 "Shaders on Metal v0.1": status (branch `m1`)

**Branch:** `m1` = `shade` + `m1-shade` (infrastructure, lit terrain, sky) + `m1-shadow` (sun shadow pass), plus the
integration and tuning commits.

**Build:**

```
JAVA_HOME=<work>/tools/jdk-25.0.4.1+1/Contents/Home ./gradlew --no-daemon :metal:build
```

The output is `metal/build/libs/metal-0.2.0-alpha.3.jar`. Everything is behind `-Dmcopt.shade=true`, or
`mcopt.shade=true` in `config/mcopt.properties`.

**Result:** M1 runs at native Retina 3024x1964 at ~215 fps in the spin and ~185 fps in the flight on a contended GPU
(other apps kept it 50-98 % busy during the final runs), and an estimated ~230 / ~195 on a quiet machine: well above
the 120 fps target, about 10x Iris + Complementary at the same resolution. Since the review round the 1% lows are
~1.5-2x better (present queue) and the look is clearly different from vanilla by day (mean |shade on - off| over the
terrain half of the frame: 26 / 20 at noon / forest, was 8 / 9). With the flag off the mod behaves like upstream.

## Numbers

Bench route of `work/bin/mcbench-run`: 20 s spin plus 20 s flight, RD 16, 3024x1964 exclusive fullscreen, M4 14", AC
power. **During the review round's final runs the GPU was 50-98 % busy with no game running** (Claude app and Chrome
GPU processes; `ioreg ... "Device Utilization %"`), so absolute numbers are low for every configuration, shade off
included; compare runs from the same session.

| Config | Spin avg fps | Spin 1% low | Flight avg fps | Flight 1% low | GPU ms/frame (spin / flight) | Runs |
|---|---|---|---|---|---|---|
| Baseline mcopt Metal (upstream, M0, quiet) | 528 | 109 | 407 | 87 | 1.85 / 2.36 | median of 3 (M0) |
| Iris + Complementary, GL, 3024x1964 | 22.4 | 14.0 | 21.4 | 10.5 | n/a | median of 3 (M0) |
| Iris + Complementary, GL, 1920x1080 windowed | 43.3 | 20.1 | 41.9 | 21.3 | n/a | median of 3 (M0) |
| **M1 final (review build), shade ON, contended GPU** | **214** (196-224) | **127** (85-129) | **184** (166-194) | **74** (64-92) | **4.65 / 5.39** | median of 6: fx-c, m1r-on1..3, i-on1..2 |
| M1 final, shade OFF, same contended session | 349-386 | 69-70 | 166-288 | 54-65 | 2.39-2.56 / 3.1-4.5 | i-off1, i-off2 |
| M1 integration build (m1-final4 jar), shade ON, same-day A/B session | 262-264 | 71-93 | 213-215 | 59 | 3.74 / 4.51 | ab-old1, ab-old2 |
| review build, same A/B session (before the tone-curve saving) | 217-222 | 104-126 | 190-192 | 71-108 | 4.48-4.60 / 5.19-5.22 | ab-new1, ab-new2 |
| M1 integration build, shade ON (quiet, earlier) | 249 | 67 | 209 | 65 | 3.94 / 4.69 | m1-final3, m1-final4 |
| M1 shade OFF (quiet, earlier) | 578 | 214 | 460 | 193 | 1.72 / 2.17 | m1-off, m1-off2 |

- **Cost of the review round:** about +0.45 ms of GPU per frame over the integration build: ~0.25 ms for the present
  queue's staging copy (which buys the 1% lows: same-session spin 1% low 71-93 -> 152 and flight 59 -> 111 with the old
  jar) and ~0.2 ms for the new look (hue-preserving tone curve, foliage, emissive, water). The vertex stage got cheaper
  (no quad-normal fetches). Details in OPTIMIZATION_LOG.md.
- **1% lows:** they were render-thread present stalls, not long GPU frames (see OPTIMIZATION_LOG.md); with the present
  queue they are 85-129 fps in the spin even on the contended GPU, better than shade OFF in the same session.
- **Shade OFF:** no shade mixin applies, the shader registry is empty, and the bind hooks sit behind a `static final
  false`; the unshaded terrain MSL preprocesses to the same tokens as upstream's. Screenshot differences of the earlier
  shade-off runs against the M0 baselines were in the baseline-vs-baseline range.

Result dirs, under `work/bench-results/`: `20261006-154634-fx-c`, `20261006-161304-m1r-on1`, `…-161516-m1r-on2`,
`…-161733-m1r-on3`, `…-164250-i-on1`, `…-164427-i-off1`, `…-164609-i-on2`, `…-164752-i-off2`, A/B `…-1549*-ab-old1`
to `…-160120-ab-new16b`, traces `…-160738-tr-new0b`; earlier: `20261006-150157-m1-final4`, `…-150404-m1-off2`.
M0 baselines are listed in HARNESS.md.

## Screenshots (final defaults, full 3024x1964)

All in `work/bench-results/20261006-161304-m1r-on1/screenshots/`: `noon_overview.png`, `forest_closeup.png`,
`sunset_water.png`, `night_torches.png`, `rain.png`, and the extra shots `morning_forest.png`, `afternoon_overview.png`,
`underwater.png`.

For comparison:
- the integration build: `20261006-150157-m1-final4/screenshots/`
- shade OFF: `20261006-150404-m1-off2/screenshots/`
- Iris + Complementary: `20261006-131520-baseline-iris-gl-retina/screenshots/`
- shadow-cascade debug tiles (`-Dmcopt.shade.debug=1`, light-space casters): `20261006-145712-m1-light/screenshots/`

Measured (region means, sRGB): noon sand (145,147,123), canopy (52,82,43), sunset horizon (238,205,152) with no clipped
channel, moonlit grass (24,29,24) vs Complementary (27,33,21), underwater mean (37,68,83) instead of (16,30,151).

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
- **Face normal:** from the screen derivatives of the camera-relative position in the fragment stage (exact for planar
  quads, snapped to the axis for axis-aligned faces); no corner fetches in the vertex stage (52 B of vertex outputs).
- **Direct light:** sun or moon N·L x shadow. The sun is ~4-5x the sky fill at noon (AMBIENT_BOOST 4), so lit and
  shaded faces read clearly apart; the fill is cooler (AMBIENT_DESATURATE 0.2), so shadows are bluish.
- **Sky fill:** directional (up, toward the sun, away from it, ground bounce), scaled by sky light.
- **Torches:** warm (2.4, 1.2, 0.48), steeper falloff (pools with edges). Bright warm texels at block light 14-15 (torch
  and lantern flames, glowstone, lava) glow: a heuristic until M2's material mask.
- **AO:** vanilla's (baked in the vertex colour with the biome tint; not separable in M1).
- **Foliage:** leaves get wrapped light, light transmitted through backlit faces and 1.35x sky fill; crossed plants are
  two-sided.
- **Finish:** eye-adapted exposure (KEY 0.61, adaptation 0.5, max 1.6, darker at sunset and twilight), then a
  hue-preserving tone curve: an exponential shoulder with a small toe on the brightest channel, the others keep their
  ratio, blending toward per-channel only near white. Night "rods" desaturation is driven by the scene's own luminance.
- **Under water:** the game's fog colour (also the frame's clear colour) becomes the shaded water colour
  (`ShadeFogMixin`, deep teal by day, near black at night); the fog takes red first, then green. Daylight on terrain under
  water is filtered blue-green.
- **Other dimensions:** Nether and End fall back to vanilla's lightmap; vanilla's face shade is put back in the fragment
  stage. Untested in game.

### Atmosphere sky
- Rayleigh, Mie and ozone single scattering, through a 128x80 LUT rendered per frame. Mie 0.75 on clear days.
- Sky saturation 1.3 with a gamut guard (no channel goes below half its value), and a golden-hour grade while the sun is
  low (warmer toward the horizon): the same in shade_common.metal and ShadeSky.java.
- Round sun disc with limb darkening.
- Terrain fog blends into the same LUT, so the horizon is seamless. Rain thickens the aerial haze (density x9, strength
  0.9).
- Vanilla's moon and stars are kept; the moon follows the tilt.

### Clouds and weather
- **Clouds:** both vanilla pipelines are re-lit (fragment override): per-face sun with real contrast (dark undersides,
  lit from below when the sun is low), a forward-scattering silver lining on the sides, sky and ground fill, aerial
  perspective that keeps near clouds white, the same tone curve.
- **Rain and snow:** streaks are re-lit grey-white in the scene's light instead of saturated blue.

### Water (an M1 stand-in; real water is M2)
- Top faces seen from above: a body colour from the (desaturated) biome tint lit like terrain, with only a trace of
  vanilla's texture; lower alpha than vanilla (the bottom shows near the shore); a Fresnel (power 4) reflection of the
  sky LUT; an energy-normalized sun glint that widens and dims with distance, clamped, with a softened shadow edge.
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
| `mcopt.metal.presentQueue` | **acquire** while `mcopt.shade=true` (upstream default off) | Set explicitly (`false`, `true`) to override |

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
- `MetalEncoder`: the phase-based trace start (`TracePhase`, whose offset parse no longer throws) and `println` for the
  GPU profile lines. Inert unless `-Dmcopt.metal.trace` is set.
- `MetalTerrain.split`: binds the shade globals right after the occlusion split reopens the encoder (the newly visible
  pulled draws read them); this moved from `MetalRenderPass.rebind`. Dead code with shade off (`ShadeBindings.ON`).
- `MetalPipeline.compile`: a registry (shade) variant that fails to build is logged and rebuilt from the game's own
  shaders. With shade off the registry is empty and the path is upstream's.
- `MetalOccPick`: its decision line uses `println` (the game's log drops `printf`), so each run shows the path chosen.

### Review fixes (M1 review round)
- Face shade is removed only in Sodium's light pipelines (`SodiumFlatFaceShadeMixin`, `SodiumSmoothFaceShadeMixin`),
  and only while the Metal backend is the one running (`ShadeFrameUpdater.faceShadeOff`): falling blocks, pistons and an
  OpenGL fallback keep vanilla's shade. `CardinalLightingShadeMixin` is gone.
- Setup is staged (`ShadeBindings.init`): if the sky LUT pass fails to build, valid resources stay bound and every frame
  is packed as `DIM_VANILLA` (3): the shaded pipelines take their vanilla paths, without shadows. A total failure
  (Throwable) turns vanilla face shade back on.
- One parser for `mcopt.shade`, `mcopt.shade.shadows` and `mcopt.metal` (`Boolean.parseBoolean`, like
  PreferredGraphicsApiMixin); Shade logs ShadowConfig's (clamped) values.
- Improved Transparency (vanilla's OIT option) is not shaded: a one-time log line says so.

## Known issues / not done

- **Bench noise:** during the review round other apps kept the GPU 50-90 % busy with no game running; some shade-off
  runs halved. Compare only interleaved runs from the same session.
- **Cost:** shade on is ~4.0-4.6 ms of GPU per frame at 3024x1964 (shade off ~1.7-2.2 on a quiet machine), including
  ~0.25 ms for the present queue.
- **Far cascade spike:** the light-space far cascade redraws 250-460k quads every 4th frame (+0.3-0.5 ms on that frame).
  SHADOW.md section 9 has the options.
- **Not shaded:** entities, block entities, particles and the hand keep vanilla lighting and fog and are not tone-mapped;
  they don't cast shadows (M3). Translucent blocks don't cast. Improved Transparency (OIT) is not shaded.
- **Water is a stand-in:** sky-only reflection, no SSR, refraction or depth fog (M2). The water surface seen from below
  is lit like terrain. A faint edge of the far shore's shadow can still show in the sunset glint.
- **Leaves:** the alpha-tested leaf shadows can shimmer slightly in motion (no TAA until M2).
- **Untested in game:** the Nether and End (vanilla fallback paths), caves (no bench scene; shadow lookups are skipped
  below sky light ~4), the GUI or hand with shading, far terrain (`mcopt.lod`, not shaded: keep it off), the `DIM_VANILLA`
  fallback (needs a broken LUT shader to trigger). A Metal API validation run (`MTL_DEBUG_LAYER=1`) could not check the
  occlusion-split fix: in assert mode it stops first on an upstream `contents` call on a private buffer, and in log mode
  the messages don't reach the game log.
- **Clouds:** the face direction is derived from vanilla's per-face shade value in the vertex colour.
- **Rain:** `ShadeMsl.weather` patches vanilla's generated weather fragment textually; if its shape changes it falls back
  to vanilla.
- **Art sign-off pending:** the knobs are `ShadeSky` (`AMBIENT_BOOST` 4, `AMBIENT_DESATURATE` 0.2, `SKY_SATURATION` 1.3,
  `KEY` 0.61, `ADAPTATION` 0.5, `EXPOSURE_MAX` 1.6, `MOON_INTENSITY`), `ShadeFrame.blockLight`, `shadeGoldenHour`,
  `Shade.SUN_TILT`.
- **User's Iris instance:** "mcopt Shaders 26.3" will crash with Iris under mcopt's alpha profile (see HARNESS.md); use
  `mcopt.chunk.mesh=air,bounds,vis,biome` or `profile=none` there.

## Next steps

1. Show the user the screenshot set for sign-off; tune the knobs above.
2. Ship as a new Prism instance, e.g. "mcopt Shaders Metal (M1)": a copy of "mcopt Metal 26.3" with this jar and
   `mcopt.shade=true` in `config/mcopt.properties`. Needs the user's OK.
3. Bench scenes for a cave and the Nether or End.
4. Far cascade: camera-frustum-extruded culling or amortized redraw.
5. M2: HDR target and post tonemap, real water (SSR, refraction), TAA/TAAU, a material mask (leaves, emissives, water),
   shaded entities, OIT variants.
