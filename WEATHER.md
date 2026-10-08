# M3 weather: rain, wetness, puddles, ripples, lightning, snow cover, coloured shadows (branch `m3-weather` from `m2.1`)

Shader bible E13 (rain, wetness, puddles, ripples, snow) and E08's coloured shadows. Everything is behind
`mcopt.shade=true` and the module flag `mcopt.shade.weather` (default on); with `mcopt.shade.weather=false` the shaders
are M2.1's (the weather prelude is empty), and `mcopt.shade=false` is upstream.


## Status (2026-10-07 23:20) — finished, ready to merge into M3

**Done in the last round (22:45-23:20, head after this doc commit):**
- The 22:22 WIP (2d55f33: dev bits 32/64/128 as budget candidates) was real work: built, validated, priced in game (wx19)
  and kept (80c385e). mcbench e5eac1b records the candidate phases and adds `wxrain_notint`.
- **Rain budget at balanced met within noise without touching the mirror:** new flag `mcopt.shade.weatherLite` (default on
  for performance/balanced, off for quality/ultra) turns on six look-preserving gates (Flags table). In-session rain cost
  (wxrain vs wxrain_dry, the bench's puddle-heavy ground pan, 37 % of the ground in view under puddles):
  **0.86 ms (wx19, before) -> 0.60 (wx20, 4 gates) -> 0.56 ms (wx21, all 6 gates, AC power, drift 0.004 ms)**.
  wx17 (AC, before any gate) was 0.80. Puddle reflections (SSR) are unchanged: still ~0.16-0.22 ms, still mirror-like at
  grazing views, torches and sky reflected (`work/release/M3-weather-screenshots/lite_*`). Quality/ultra keep the full look.
- The 22:00 fixes are now tested in game: coloured shadows still render (wx19/wx21 `wx_glass_noon`, `rect_wx_glass_noon.png`:
  the tint rectangle clips nothing visible); snowfall looked at (`wx_snowfall.png`: white flakes, M2.1-like opacity, no warm
  torch tint).
- Checked by reading (already committed before this round): water out of the tint pass with the water module on (663e6e7),
  quad-shuffle guard `quadPuddle &&` (ea7e071), `COLOR_SHADOWS` requires `ShadeConfig.HDR` (ea7e071), LDR lite field (756a5a6).
- Build OK, `validate.sh` ALL OK (also with `MCOPT_GEN_DIR` = the 402-stage dump: weather/HDR weather/lightning variants).

**What is left (not blocking the merge):**
1. **Dry cost near glass: ~0.18 ms** (wx19 0.14, wx20 0.20, wx21 0.18; budget ~0.1). The light-space rectangle did not lower
   it measurably in the bench view (the glass roof stands beside the pose; the tint pass itself, a separate render pass into
   the 1024^2 RGBA16F map, is probably most of it). Away from stained glass, ice, slime and honey there are no casters
   (plain water no longer counts with the water module on) and the receivers skip the lookup. Options: `mcopt.shade.colorShadows=false` on
   balanced (-0.18 near glass), or fold the tint draw into the cascade-0 pass / a smaller map (M4).
2. Rain 0.56 vs 0.50: within the ±0.05-0.1 session noise, in a worst-case view (37 % puddles; 31 % on average). Further
   options, each with a small look cost, for Gael to choose: raise the SSR gate to F x P 0.06 (puddles within ~3 blocks of
   the feet lose a 5 % reflection, ~-0.02), ripples fade out nearer (0.02-0.045 blocks/pixel: no rings beyond ~10 blocks,
   ~-0.03), 8 SSR steps on balanced (thin things like torches can be missed in the reflection, ~-0.05).
3. Not done: the moving-view video (`wxmove`, item 5 of the 21:55 list) and a DH re-run after this round (wx18 DH sanity
   was clean; this round changed only weather shaders and `ShadowPass`'s tint condition).

## What it does (in game)

| Effect | How |
|---|---|
| **Wet surfaces** | While it rains where rain falls (the camera's biome: rain, not snow, not deserts), `wetness` soaks in (rise τ 14 s) and dries slowly (τ 75 s). Surfaces under open sky (sky light 15; 14 at an overhang's edge gets a little) darken (porous look: albedo^(1+0.65ω)) and get a water film: Fresnel (f0 0.02, roughness-aware) reflection of the sky, the sun's GGX sheen. Walls get 55 %, undersides none, foliage 70 %, crossed plants 45 %. Vegetation (green albedo) holds less of a film than stone, wood and paths. |
| **Puddles** | `puddles` follow wetness with a lag (rise τ 10 s once the ground is wet, dry τ 110 s). Only on open, up-facing, non-foliage surfaces. Procedural world-space mask (value noise: 8/4/2-block cells on a 32-block swell, ~31 % of open ground at full), **baked once** into a 256-block tile (`ShadeResources` 4, RG16F 1024², 4 MB; the snow drifts in its green channel) instead of 16-24 hashes per pixel, a darker wet rim. Every lattice repeats every 256 blocks (invisible: the ground is never seen that far at once). In a puddle: albedo ×0.7, roughness 0.03, water Fresnel, **reflections**: screen-space from **last frame's image**, marched only where the water film's Fresnel makes them visible (F·P > 0.035, a ramp to 0.07: grazing views; straight down a puddle reflects 2 % and the sky reflection stands in) (the TAA history and its depth: the opaque pass has no copy of this frame), sky elsewhere (the rendered sky from the history where it is on screen, the atmosphere LUT beyond). |
| **Ripples** | Raindrop rings (two staggered 0.7-block grids, a drop every 0.45-0.85 s per cell; one grid with drops every 0.33-0.61 s on performance/balanced, rings at 0.42 blocks/s, analytic slope) on puddles and on water under open sky (water.metal, after the wave normal), scaled by rain intensity, faded out where they would alias. |
| **Rain streaks** | Lit from the sky in their own view direction (never darker than the sky behind), a forward-scattering sun glow, torch light from vanilla's lightmap, the lightning flash; mostly transparent; the columns right in front of the eye fade out; leaned with the wind (vertex patch: 0.12-0.34 blocks per block, more in thunder, gusting). |
| **Lightning** | Bolts near the camera (≤ 256 blocks) light the scene from their direction (terrain under open sky + the sky, a flickering envelope from the bolt's seed, then a 0.12 s afterglow); the bolt itself is written as bright HDR (bloom). The game's "Hide Lightning Flashes" turns the light off. |
| **Snow cover** | Where it snows at the camera, top faces under open sky (leaves included) get patchy snow (τ 30 s up, 240 s down). |
| **Coloured shadows** | The shadow pass draws Sodium's translucent terrain (stained glass and panes, ice, slime, honey, water) of the **near cascade** into a tint map (RGBA16F, 1024² default, 2048² on ultra): rgb = the light they let through (MIN blend), a = the light depth of the one nearest the light (MAX blend). Receivers more than a quarter block below it take the tint on their direct light. Water casters let through ~2 blocks of the biome water's absorption (deeper floors stay bluish-green). |
| **Water caustics** | Not in the shadow pass: M2's water module already draws physically based caustics (the refracted sun's inverse Jacobian) on the bottom seen through the surface (`mcopt.shade.caustics`, water.metal). A tint-map pattern was tried (2026-10-07) and dropped because it would double them. |

## Flags (system properties or config/mcopt.properties; read once at startup)

| Flag | Default | |
|---|---|---|
| `mcopt.shade.weather` | true | the module |
| `mcopt.shade.puddles` | true | puddles |
| `mcopt.shade.puddleSsr` | preset (off for performance) | puddle reflections from last frame's image (needs TAA: `mcopt.shade.taa`) |
| `mcopt.shade.puddleQuad` | true (false on ultra) | the 2x2 quad shares one reflection ray, each lane marching a quarter |
| `mcopt.shade.puddleSteps` | quad 12 / 12 / 20 / 24 (per pixel 8 / 10 / 14 / 20) | march steps per ray (+3 bisection) |
| `mcopt.shade.puddleDist` | 64 | max reflection ray length (blocks) |
| `mcopt.shade.ripples` | true | raindrop rings on puddles and water |
| `mcopt.shade.rippleLayers` | 1 (performance, balanced) / 2 | raindrop grids |
| `mcopt.shade.weatherLite` | true on performance/balanced, false on quality/ultra | budget gates that keep the look: no sun highlight on wet surfaces in heavy rain (rain 0.5-0.8 fade; the sun is at <= 32 % there), raindrop rings only where the puddle mirror shows them (flat Fresnel x puddle >= 0.02-0.035), rain streaks lit by the sky's mean light (no sky-view LUT per streak fragment), coloured shadows fade out in heavy rain (rain 0.5-0.8) and the tint pass is skipped above 0.8, wet films off the puddles see the overcast sky's mean light in heavy rain (no LUT lookup; fades in over rain 0.6-0.8), puddle reflections marched from F x P 0.05 (ramp to 0.09) instead of 0.035 (to 0.07): puddles within ~4.5 blocks of the feet (they reflect 2-5 %) keep the sky reflection alone. **The mirror at grazing views is untouched** |
| `mcopt.shade.snowCover` | true | snow on top faces while it snows |
| `mcopt.shade.rainSlant` | true | rain leans with the wind |
| `mcopt.shade.lightning` | true | flash light + HDR bolt |
| `mcopt.shade.colorShadows` / `.colorShadowRes` | true / 1024 (2048 ultra) | coloured shadows |
| `mcopt.shade.weatherTime` | 1 | time scale of wetting and drying (the bench uses 10) |
| `mcopt.shade.wetness`, `mcopt.shade.snowAmount` | -1 | force wetness+puddles / snow cover (look development) |
| `mcopt.shade.flashLight` | 5 | the flash's light (radiance on a white surface, before exposure) |
| `mcopt.shade.debug=8` | | debug view: red = wetness, green = puddles, blue = snow |
| `mcopt.shade.dev` bits (read every frame) | 0 | pricing: the weatherLite gates one by one where weatherLite is off (32 sun gate, 64 ripple gate, 128 lite rain streaks, 256 coloured shadows fade in heavy rain, 512 films' mean sky in heavy rain, 131072 SSR gate from F x P 0.05; with weatherLite they are always on), 1024 no puddle SSR, 2048 no ripples, 4096 dry terrain, 8192 M2 rain shading, 16384 no coloured shadows (tint pass and lookup), 32768 per-pixel noise instead of the baked tile, 65536 puddle SSR without the Fresnel gate |

## Integration (what changed outside the new files)

New files: `metal/src/main/resources/mcopt/shade/weather_surface.metal` (receiver side: wet layer, puddles, ripples,
history SSR, flash, shadow tint), `metal/src/main/java/mcopt/metal/shade/weather/ShadeWeather.java` (flags, CPU state,
ShadeLook packing, MSL prelude; no game types), `WeatherFrame.java` (game-typed per-frame update: precipitation at the
camera, lightning bolts).

| Where | Change |
|---|---|
| `shade_look.metal` | `ShadeLook.wet` (x wetness, y puddles, z snow, w rain intensity), `wind` (xy dir, z slant, w snowfall at the camera), `flash` (xyz dir, w light) now documented and written; `ssr.y` = 1 when `ShadeResources.prevDepth` is valid, `ssr.z` = 1 when the noise tile is baked, `ssr.w` = 1 when the tint map has casters and **608 `tintRect`** (was `heldLight`, reserved and unused by every branch) = their rectangle in the near cascade's light clip space, both written **after the shadow pass, the same frame** (`ShadeBindings.setShadowTint`; `ShadeWeather.pack` writes "none" first). `ShadeResources` entries **4 = `weatherNoise`** (baked puddle/snow tile), **5 = `prevDepth`** (TAA history linear view depth, R16F, output size) and **6 = `shadowTint`** (were `reserved4/5/6`). Entry 7 stays free. **Merge note:** m3-lights took none of these (its data is `buffer(24)` / `texture(23-25)`, BASE.md 8.3), so 4-6 stay weather's; with clouds' 12-14 the struct has 16 entries. If a later module needs slot 4, move the weather noise (it is only read through `sr.weatherNoise`). |
| `ShadeLook.pack` | calls `ShadeWeather.pack(b)` at the end (zeros when the module is off). |
| `ShadeFrameUpdater.update` | `WeatherFrame.update(...)` after `LOOK.update`, before packing; `ShadeWeather.bakeNoise(enc)` (once, HDR only) after the water bake; the `mcopt.shade.log` line gets the weather state. |
| `ShadePost.beginWorld` | publishes `ShadeResources.prevDepth` (5) = `taa.historyDepth()` when the history is valid, and `ShadeBindings.setPrevDepthValid` (`ssr.y`). General infrastructure: any pass can march last frame's image with it. |
| `ShadeMsl.terrain` | `ShadeWeather.prelude()` (defines + weather_surface.metal) in front of every terrain **fragment**, before the water module's prelude. |
| `ShadeMsl.weather` / `weatherVertex` / `lightning` | the weather fragment calls `shadeWeather3` (gets `[[position]]` and the sky LUT); the weather vertex stage is patched to lean the rain (`shadeRainSlant`); `minecraft:pipeline/lightning` is registered (bolt ×30-40 in HDR). |
| `sodium_terrain.fs.metal` (PolyForm) | `constant ShadeResources& sr [[buffer(19)]]` is now declared for HDR variants with the water **or** weather module; in the non-water branch: `shadeWetSurface` → wet albedo → `shadeLit2` → emissive → coloured-shadow delta (`shadeShadowTintDelta`) → flash (`shadeWxFlash`) → `shadeWetShade`; debug 8. |
| `water.metal` | ripples added to the top-face wave normal in `shadeWaterAbove` (`#if SHADE_WEATHER && SHADE_RIPPLES`). |
| `sky.fs.metal` | the flash lights the sky (`sl.flash`). |
| `weather.metal` | `shadeWeather3`, `shadeRainSlant` (M2's `shadeWeather` kept for the module off). |
| `shadow.metal`, `mcshadow.m`, `ShadowNative`, `ShadowCasters`, `ShadowPass` | coloured shadows: `shadow_tint_vs/fs`, `mc_shadow_tint_init/texture/render`; `ShadowCasters.KIND_TRANSLUCENT` lists (near cascade only); `ShadowPass` draws them after cascade 0, publishes the map as ShadeResources 6 and, via `publishTint`, ssr.w + `tintRect` (the listed sections' boxes through cascade 0's matrix): receivers outside the rectangle skip the lookup. With the water module on, the tint pass leaves water out (`ShadowTintParams.mode.x`, 48 B) and `ShadowCasters` skips sections whose palette has no translucent block model (`LevelChunkSection.maybeHas`, `BakedQuad.FLAG_TRANSLUCENT`: plain water draws nothing). |
| `ShadeBindings` | `setPrevDepthValid` (ssr.y), `setShadowTint` (ssr.w, tintRect): both write this frame's ShadeLook slot after the pack. |
| `ShadeLook.flash` (768) | **shared with m3-skydims** (`ShadeDims.pack` writes the End's flashes there, same meaning: xyz toward the flash, w its light). Order after the merge: `LOOK.pack` (ends with `ShadeWeather.pack`, which writes the lightning flash or 0) **then** `ShadeDims.pack` (overwrites it only while the Nether/End are lit): keep that order and both work. In the End the terrain's `shadeWxFlash` is gated by sky light (0 there) and `sky.fs.metal`'s weather flash term reads the same field. |
| dev bits | weather uses `mcopt.shade.dev` 32, 64, 128, 256, 512, 131072 (weatherLite gates), 1024-65536 (prices); M2 uses 1-16; no other M3 branch defines dev bits (checked m3, m3-lights, m3-clouds, m3-skydims 22:50). |
| `ShadowPass` (tint) | the tint pass is skipped while `ShadeWeather.tintHidden()` (weatherLite and rain >= 0.8). |
| `ShadeMsl.weather` | with weatherLite the HDR weather fragment gets `#define SHADE_WX_LITE` after `#define SHADE_WEATHER` (the terrain gets it from `ShadeWeather.prelude`). |
| `ShadeLook.java` | `HELD_LIGHT` (608) renamed `TINT_RECT` (m3's `shade_dynlight.metal` comment still names heldLight as reserved: update it at the merge). |
| `WaterFrame.rects` | the water sprites' atlas rectangles (for the tint pass). |
| `tools/shade` | `ShadeMslDump` writes the weather vertex, HDR weather and lightning variants (with `MCOPT_GEN_DIR`); `validate.sh` compiles them. The terrain variants include the weather prelude automatically. |

**For other M3 modules:** `shadeShadowTintDelta` duplicates `shadeLit2`'s direct-light term (wrap, gate); if the lighting
module changes that term, change it there too (or make `shadeLit2` take a tinted shadow). `shadeWxFlash` is an additive
light like a dynamic light; the dynamic-lights module can treat a flash as one more directional light instead. The puddle
SSR reads `ShadeResources.prevScene`/`prevDepth`: any module may use them (TAA on; `ssr.x`/`ssr.y` say when valid).

## Bench (mcbench `WeatherShots`, `-Dmcbench.weather=true|perf,rain,sunset,night,water,storm,glass,caustics`)

See `work/HARNESS.md` (8f). Typical look-dev run:
`mcbench-run --instance "mcopt DEV" --mods <jar> --mods <sodium> --jvm "-Dmcopt.shade=true -Dmcopt.shade.weatherTime=10
-Dmcbench.weather=true -Dmcbench.standardShots=false" --scenes all --seconds 15`.

## Results

All numbers: GPU ms per frame (mcopt `gpuTimes`, the union of the frame's command buffers on the GPU timeline), the mcbench
weather ground pose (2.6 blocks above a grassy plain with a 7x7 torch grid, panning +-25 degrees for 10 s), preset
**balanced** (TAAU 0.80, DRS off), 3024x1964 fullscreen, M4 on **AC power**. Each run prices every part **inside one
session** with the `mcopt.shade.dev` bits (`-Dmcbench.weather=price`): a phase with the part switched off, compared with
`wxrain` / `wxdry` of the same session (drift check: `wxrain2` at the end). cpu-load.txt stayed at 90-290 % during the
perf phases of every run listed (clean; the >350 % samples are the world loading before them). Provisional: clean final
numbers come after the M3 integration.

### Budget round: weatherLite (2026-10-07 22:45-23:15, the final balanced prices)

Same pose and phases as below (`-Dmcbench.weather=price,glass,...`, 10 s pans, glass roof in range, balanced, TAAU 0.80).
Prices are in-session differences against the mean of `wxrain` and `wxrain2` (wx19: drift-adjusted linearly, its drift was
+0.12 ms; wx20 and wx21 drifted -0.01 and +0.004 ms). Script: `work/notes/m3-2026-10-07/weather/wxprice.py <run dir>`.

| | wx19 22:49 (2d55f33, battery) | wx20 22:59 (80c385e, battery) | **wx21 23:08 (2799c87, AC)** |
|---|---|---|---|
| gates on at balanced | none (candidates priced by dev bit) | sun, ripple, lite rain, rain tint | **all six** |
| **Rain, everything** (wxrain vs wxrain_dry) | 0.86 | 0.60 | **0.56** |
| puddle reflections (SSR, quad, 12 steps) | 0.15 | 0.22 | 0.16 |
| saved by the SSR Fresnel gate | 0.09 | 0.08 | 0.14 |
| raindrop ripples (1 grid) | 0.12 | 0.08 | 0.08 |
| lit, slanted rain streaks (vs M2 rain) | 0.11 | 0.06 | 0.06 |
| coloured shadows in rain | 0.20 | 0 (skipped in heavy rain) | 0 |
| candidate: sun gate (dev 32) | 0.10 | (on) | (on) |
| candidate: ripple gate (dev 64) | 0.03 | (on) | (on) |
| candidate: lite rain streaks (dev 128) | 0.11 | (on) | (on) |
| **Dry: coloured shadows** (wxdry, wxdry2 vs wxdry_notint) | 0.14 | 0.20 | 0.18 |
| saved by the baked noise | 0.83 | 0.77 | 0.74 |

Plain words: in heavy rain at balanced the weather now costs **~0.56 ms** (was ~0.8-0.9) in a view where 37 % of the ground
is puddle, and the puddles still mirror the sky, torches and grass. Where the savings came from: the sun's glint on wet
ground (the sun is behind the clouds in heavy rain: ~0.1 ms), the rain streaks' per-pixel sky lookup (~0.1 ms), the coloured
shadows (they fade out in heavy rain, where the sun they colour is at a third or less: ~0.2 ms near glass), the wet ground's
sky lookup in heavy rain and a slightly later start of the puddle reflection near the feet (~0.04 ms together), the ripple
gate (~0.03 ms). Quality/ultra keep all of it (their dev bits still price each gate).

### What each part costs (ms of GPU time per frame)

Method: each run prices every part **inside one session** with the `mcopt.shade.dev` bits (a 10 s phase with that part
off, against `wxrain`/`wxdry` of the same session). Two biases showed up and are corrected with **a run of the same phases
with the module off** (`wx14`, `mcopt.shade.weather=false`: the dev bits do nothing there, so any difference between its
phases is pure bias): (1) the first phase (`wxdry`) is ~0.17 ms slower than the second in every run, module on or off
(warm-up; in wx16 only 0.04 ms, so this correction is unreliable for the tint column: wx17's bracketed A/B/A later measured
the coloured shadows at **0.19 ms**, see below); (2) the GPU drifts up
0.0-0.3 ms through a session (wxrain2 - wxrain), more in some sessions than others. Corrected = in-session difference
minus the same difference in the module-off run.

| Part | wx7 15:00 pre-pause (raw) | wx10 18:32 (corrected) | wx11 18:55 (corrected) | wx13 19:15 final (corrected with wx14) | wx15 19:38 final (corrected with wx16) |
|---|---|---|---|---|---|
| Puddles in the view (ground pose) | ~49 % (old pattern) | ~22 % | ~37 % | ~37 % | ~37 % |
| **Dry**: coloured shadows (tint pass + lookup, glass roof in range) | | -0.01 | 0.01 | -0.01 | 0.17 (?) |
| **Rain, everything** (vs the dev-bit "all off") | 1.16 | 0.94 | 1.09 | 0.77 | 0.61 |
| puddle reflections (SSR) | 0.39 | 0.22 | 0.31 | 0.24 | 0.06 |
| saved by the Fresnel gate | | 0.12 | 0.13 | 0.18 | 0.27 |
| saved by the baked noise | | 0.72 | 0.72 | 0.82 | 0.90 |
| raindrop ripples | 0.12 | 0.10 | 0.25 (2 grids) | ~0 (1 grid; drift-noisy) | ~0 (1 grid; drift-noisy) |
| lit, slanted rain streaks | 0.05 | 0.21 | 0.20 | ~0 (drift-noisy) | ~0 (drift-noisy) |
| drift left after correction | 0.13 | -0.12 | -0.11 | +0.16 | +0.29 |

**The module as a whole, on vs off** (four launches alternating on/off/on/off, wx13 / wx14 / wx15 / wx16, all on AC,
cpu-load 90-290 % during the phases; the same 10 s pans; wx14 had no glass roof, the others did):

| | on (wx13, wx15) | off = M2.1 (wx14, wx16) | module cost |
|---|---|---|---|
| dry (wxdry) | 6.90, 7.02 | 6.76, 6.68 | **+0.24** (+0.14 / +0.33 pairwise) |
| rain (wxrain) | 8.27, 8.38 | 7.30, 7.53 | **+0.91** (+0.98 / +0.85 pairwise) |

**Cleanest single session: wx17** (19:57, a 10 s unrecorded warm-up first, drift wxrain2 - wxrain only +0.04 ms, cpu-load
100-180 % during the phases), raw in-session differences, ms:

| Part | wx17 |
|---|---|
| **Dry: coloured shadows** (glass roof in range), bracketed wxdry 6.696 / wxdry_notint 6.508 / wxdry2 6.709 | **0.19** |
| **Rain, everything** (wxrain 8.010 vs wxrain_dry 7.212) | **0.80** |
| puddle reflections (SSR, 12 steps, quad) | 0.16 |
| saved by the Fresnel gate | 0.11 |
| saved by the baked noise | 0.75 |
| raindrop ripples (1 grid) | 0.08 |
| lit, slanted rain streaks | 0.08 |
| the rest (wet darkening and film, sky reflection, sun sheen, puddle mask, coloured shadows ~0.19) | ~0.48 |

So with glass nearby the coloured shadows cost ~0.19 ms (the receivers' lookup runs on every sunlit pixel once any
translucent caster is in the near cascade); away from glass and water the lookup is skipped and only the empty tint pass
(a clear) remains.

Reading: numbers move by ±0.1 ms between sessions on this shared machine (five agents, builds, thermal state), so trust
the big items: the **baked noise saves ~0.75 ms**, the **Fresnel gate ~0.15 ms**, puddle **reflections cost ~0.25 ms**,
coloured shadows **~0.19 ms** whenever translucent casters (glass **or water**: before the 22:00 fix any water within 32
blocks turned the lookup on for every sunlit pixel) were in the near cascade, and **rain costs ~0.8-1.0 ms
over a dry frame with ~37 % of the ground in view under puddles** (less with fewer puddles: 0.8 in wx10 at ~22 %).

### Before / after this session (plain words)

- **The puddle pattern is now baked** into a small texture once, instead of being computed for every pixel every frame:
  that alone saves **~0.75-0.9 ms** in rain (0.75 in the cleanest session; the biggest single win).
- **Puddle reflections are only traced where you can actually see them** (looking across a puddle, not straight down
  into it): saves **~0.1-0.2 ms**, no visible change (where the trace is skipped the puddle reflects 2-4 % of the light).
- **Soaked grass and damp ground no longer look up the sky** they would blur away anyway; with no glass or water near,
  the terrain skips the coloured-shadow lookup.
- **Balanced** uses one raindrop grid (drops come faster, so about as many rings) and 12 reflection steps instead of 16;
  quality/ultra keep two grids and 20/24 steps.
- With all that, **rain at balanced costs ~0.9 ms more than M2.1** in this puddle-heavy view (37 % of the ground in view
  is puddle; before the pause it measured 1.16 ms over a dry frame with the old pattern). **Dry: 0.19 ms** with the
  glass roof in the coloured-shadow range (wx17's bracketed A/B/A; the on/off launches agree within their ±0.1 ms noise).
- **Update 23:15: the weatherLite gates bring rain at balanced to 0.56 ms (wx21; see "Budget round" above) with the
  mirror untouched; dry near glass stays ~0.18 ms.** The paragraph below is the state before that round.
- **Budget (bible §7: ~0.1 ms dry, <= 0.5 ms in rain at balanced): not met (before weatherLite).** Rain is 0.3-0.4 ms over (0.80 ms in the
  cleanest session, ~0.9 ms on/off); dry is 0.19 ms with glass or water in range (the lookup was skipped only without either; since 22:00 water no longer
  counts and the lookup is limited to the casters' rectangle: see the wx19 prices).
  What would close it, in order of size, for the integrator / Gael to choose: puddle reflections off on balanced
  (`mcopt.shade.puddleSsr=false`, the bible's plan for P/B: -0.1 to -0.3 ms, puddles then mirror only the sky),
  a deferred puddle pass at half resolution (new work, M4), coloured shadows only on quality+ (`mcopt.shade.colorShadows`,
  -0.19 ms near glass) or a light-space rectangle of the casters so only receivers under them do the lookup (small change, untested).
  Gael loves reflections, so this branch keeps them on at balanced.

### How it looks (screenshots in `work/release/M3-weather-screenshots/`)

- **Puddles** (`wx_wet_sunset`, `wx_rain_ground`): after the rain clears at sunset, puddles are clear mirrors of the grass,
  torches and sky; in the rain they read as grey sheets reflecting the overcast sky with rings, and torches reflect in
  them. No puddles under cover (they need sky light 15; one block of overhang stops them, its edge gets only a little
  wetness). The puddle edges are soft over ~0.3 blocks (no shimmer in the stills; the history-based reflection can lag one
  frame when turning fast).
- **Wet ground**: darker and glossy, not black (`wx_rain_ground`); grass holds less of a water film than stone and paths.
- **Rain streaks**: thin, lit by the sky behind them (white-grey against the overcast sky, not dark lines), leaning with
  the wind; at night they almost vanish except near torches (like real rain).
- **Lightning** (`wx_storm`): a bright HDR bolt that blooms and reflects in the water as a long streak.
- **Snow cover** (`wx12-snowlook`, forced full snow): white tops, plants and tall grass stay green, shadows and coloured
  light on the snow.
- **Coloured shadows** (`wx_glass_noon`, `wx_glass_evening`): six stained-glass colours cast evenly coloured light on the
  ground, also on snow.
- **Not ours, noticed**: vanilla's rain splash particles show as small saturated blue dots on the dark wet ground (particle
  pipeline, M3 base / entity lighting).

### Runs

| Run | Build | What |
|---|---|---|
| `20261007-150059-wx7-price` | fa09002 | pre-pause prices (analytic noise, no gate) |
| `20261007-181640-wx8-price-shots` | d7c5823 | baked noise + Fresnel gate; shots showed fewer puddles in view (the 256-block lattice moved the pattern) |
| `20261007-183259-wx10-price-shots` | fb42a8c | puddle threshold 0.575 (31 % of open ground at full) |
| `20261007-185602-wx11-price-shots` | 4d5c812 | swell seed with puddles in the bench view; tint flag; rough films skip the sky lookup |
| `20261007-190041-wx12-snowlook` | 4d5c812 | `-Dmcopt.shade.snowAmount=1`: snow cover look |
| `20261007-191550-wx13-price-shots` | ccaad51 | final balanced prices + all shots (session drift +0.29) |
| `20261007-192037-wx14-off-price` | ccaad51 | `mcopt.shade.weather=false`, same phases (bias of each phase position), no glass roof |
| `20261007-193854-wx15-price` | ccaad51 | final balanced prices again (price + glass, no shots) |
| `20261007-194245-wx16-off-price` | ccaad51 | `mcopt.shade.weather=false`, price + glass |
| `20261007-195738-wx17-price` | ccaad51 | warm-up + dry A/B/A bracket of the coloured shadows (mcbench 6a2531f); cleanest session |
| `20261007-201040-wx18-dh-sanity` | ccaad51 | DH sanity (mcopt DEV-DH + Distant Horizons 3.3.4): glass, rain, wet sunset shots render, no errors in the log |
| `20261007-224923-wx19-price-cand` | 2d55f33 | prices of the budget candidates (dev 32/64/128/224) + coloured shadows in rain (mcbench e5eac1b); glass, rain, snowfall shots; battery |
| `20261007-225903-wx20-price-lite` | 80c385e | weatherLite with 4 gates: rain 0.60; glass, rain, wet sunset shots; battery |
| `20261007-230849-wx21-price-lite2` | 2799c87 | weatherLite with all 6 gates: **rain 0.56**, dry tint 0.18; glass, rain, wet sunset, night rain shots (`lite_*`); AC |
