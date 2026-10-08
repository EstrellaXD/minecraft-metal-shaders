# M3 weather: rain, wetness, puddles, ripples, lightning, snow cover, coloured shadows (branch `m3-weather` from `m2.1`)

Shader bible E13 (rain, wetness, puddles, ripples, snow) and E08's coloured shadows. Everything is behind
`mcopt.shade=true` and the module flag `mcopt.shade.weather` (default on); with `mcopt.shade.weather=false` the shaders
are M2.1's (the weather prelude is empty), and `mcopt.shade=false` is upstream.


## Status (2026-10-07 18:15)

Resumed after the account pause. Done since the pause: the WIP commit (baked puddle/snow noise tile, ShadeResources 4) is
finished and kept (`3c93d58`); the puddle SSR now marches only where the Fresnel term makes it visible (`d7c5823`); new dev
bits `WX_ANALYTIC` (32768) and `WX_NO_SSR_GATE` (65536) and the `NO_TINT` bit also skips the tint pass, so mcbench's
`price` phases (now `wxdry`, `wxdry_notint`, `wxrain`, `wxrain_nossr`, `wxrain_nogate`, `wxrain_analytic`,
`wxrain_noripple`, `wxrain_plainrain`, `wxrain_dry`, `wxrain2`) price every part inside one session.
Next: run `wx8-price-shots` (build d7c5823), read prices, cut cost toward the budget (0.1 ms dry, 0.5 ms rain at
balanced), judge the shots, fill Results.

## What it does (in game)

| Effect | How |
|---|---|
| **Wet surfaces** | While it rains where rain falls (the camera's biome: rain, not snow, not deserts), `wetness` soaks in (rise τ 14 s) and dries slowly (τ 75 s). Surfaces under open sky (sky light 15; 14 at an overhang's edge gets a little) darken (porous look: albedo^(1+0.65ω)) and get a water film: Fresnel (f0 0.02, roughness-aware) reflection of the sky, the sun's GGX sheen. Walls get 55 %, undersides none, foliage 70 %, crossed plants 45 %. Vegetation (green albedo) holds less of a film than stone, wood and paths. |
| **Puddles** | `puddles` follow wetness with a lag (rise τ 10 s once the ground is wet, dry τ 110 s). Only on open, up-facing, non-foliage surfaces. Procedural world-space mask (value noise: 8/4/2-block cells on a 32-block swell, ~30 % of open ground at full), a darker wet rim. In a puddle: albedo ×0.7, roughness 0.03, water Fresnel, **reflections**: screen-space from **last frame's image** (the TAA history and its depth: the opaque pass has no copy of this frame), sky elsewhere (the rendered sky from the history where it is on screen, the atmosphere LUT beyond). |
| **Ripples** | Raindrop rings (two staggered 0.7-block grids, a drop every 0.45-0.85 s per cell, rings at 0.42 blocks/s, analytic slope) on puddles and on water under open sky (water.metal, after the wave normal), scaled by rain intensity, faded out where they would alias. |
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
| `mcopt.shade.puddleSteps` | 8 / 10 / 14 / 20 | march steps (+3 bisection) |
| `mcopt.shade.puddleDist` | 64 | max reflection ray length (blocks) |
| `mcopt.shade.ripples` | true | raindrop rings on puddles and water |
| `mcopt.shade.snowCover` | true | snow on top faces while it snows |
| `mcopt.shade.rainSlant` | true | rain leans with the wind |
| `mcopt.shade.lightning` | true | flash light + HDR bolt |
| `mcopt.shade.colorShadows` / `.colorShadowRes` | true / 1024 (2048 ultra) | coloured shadows |
| `mcopt.shade.weatherTime` | 1 | time scale of wetting and drying (the bench uses 10) |
| `mcopt.shade.wetness`, `mcopt.shade.snowAmount` | -1 | force wetness+puddles / snow cover (look development) |
| `mcopt.shade.flashLight` | 5 | the flash's light (radiance on a white surface, before exposure) |
| `mcopt.shade.debug=8` | | debug view: red = wetness, green = puddles, blue = snow |

## Integration (what changed outside the new files)

New files: `metal/src/main/resources/mcopt/shade/weather_surface.metal` (receiver side: wet layer, puddles, ripples,
history SSR, flash, shadow tint), `metal/src/main/java/mcopt/metal/shade/weather/ShadeWeather.java` (flags, CPU state,
ShadeLook packing, MSL prelude; no game types), `WeatherFrame.java` (game-typed per-frame update: precipitation at the
camera, lightning bolts).

| Where | Change |
|---|---|
| `shade_look.metal` | `ShadeLook.wet` (x wetness, y puddles, z snow, w rain intensity), `wind` (xy dir, z slant, w gust), `flash` (xyz dir, w light) now documented and written; `ssr.y` = 1 when `ShadeResources.prevDepth` is valid. `ShadeResources` entries **5 = `prevDepth`** (TAA history linear view depth, R16F, output size) and **6 = `shadowTint`** (were `reserved5/6`). Entries 4 and 7 stay free. |
| `ShadeLook.pack` | calls `ShadeWeather.pack(b)` at the end (zeros when the module is off). |
| `ShadeFrameUpdater.update` | `WeatherFrame.update(...)` after `LOOK.update`, before packing; the `mcopt.shade.log` line gets the weather state. |
| `ShadePost.beginWorld` | publishes `ShadeResources.prevDepth` (5) = `taa.historyDepth()` when the history is valid, and `ShadeBindings.setPrevDepthValid` (`ssr.y`). General infrastructure: any pass can march last frame's image with it. |
| `ShadeMsl.terrain` | `ShadeWeather.prelude()` (defines + weather_surface.metal) in front of every terrain **fragment**, before the water module's prelude. |
| `ShadeMsl.weather` / `weatherVertex` / `lightning` | the weather fragment calls `shadeWeather3` (gets `[[position]]` and the sky LUT); the weather vertex stage is patched to lean the rain (`shadeRainSlant`); `minecraft:pipeline/lightning` is registered (bolt ×30-40 in HDR). |
| `sodium_terrain.fs.metal` (PolyForm) | `constant ShadeResources& sr [[buffer(19)]]` is now declared for HDR variants with the water **or** weather module; in the non-water branch: `shadeWetSurface` → wet albedo → `shadeLit2` → emissive → coloured-shadow delta (`shadeShadowTintDelta`) → flash (`shadeWxFlash`) → `shadeWetShade`; debug 8. |
| `water.metal` | ripples added to the top-face wave normal in `shadeWaterAbove` (`#if SHADE_WEATHER && SHADE_RIPPLES`). |
| `sky.fs.metal` | the flash lights the sky (`sl.flash`). |
| `weather.metal` | `shadeWeather3`, `shadeRainSlant` (M2's `shadeWeather` kept for the module off). |
| `shadow.metal`, `mcshadow.m`, `ShadowNative`, `ShadowCasters`, `ShadowPass` | coloured shadows: `shadow_tint_vs/fs`, `mc_shadow_tint_init/texture/render`; `ShadowCasters.KIND_TRANSLUCENT` lists (near cascade only); `ShadowPass` draws them after cascade 0 and publishes the map as ShadeResources 6. |
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

(filled in below)
