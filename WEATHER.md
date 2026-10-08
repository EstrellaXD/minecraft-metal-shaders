# M3 weather: rain, wetness, puddles, ripples, lightning, snow cover, coloured shadows (branch `m3-weather` from `m2.1`)

Shader bible E13 (rain, wetness, puddles, ripples, snow) and E08's coloured shadows. Everything is behind
`mcopt.shade=true` and the module flag `mcopt.shade.weather` (default on); with `mcopt.shade.weather=false` the shaders
are M2.1's (the weather prelude is empty), and `mcopt.shade=false` is upstream.


## Status (2026-10-07 22:24)

**Fix round 22:00 (pre-integration, from the skeptic check `check:weather` in `work/notes/m3-2026-10-07/prefix-checks.json`):**
done and committed (builds, validate ALL OK, **not yet tested in game**):
- Coloured shadows leave water out when the water module is on (`ShadeWater.ON`): `shadow_tint_fs` discards water
  (`ShadowTintParams.mode.x`, params now 48 B), and `ShadowCasters` no longer lists translucent sections whose block palette
  has no translucent model (`LevelChunkSection.maybeHas` + `BakedQuad.FLAG_TRANSLUCENT`), so near plain water the tint pass
  draws nothing and the receivers skip the lookup (ssr.w = 0). Fixes the double absorption on lake floors (tint map + M2.1's
  dy term). With the water module off, water still tints. `ShadowCasters.tintBoxes/tintCount` = the near cascade's listed
  translucent sections (block coords).
- Light-space rectangle: `ShadowPass.publishTint` projects those section boxes through cascade 0's matrix into a clip-space
  rectangle (+8 texels) and writes it, with ssr.w, into **this frame's** ShadeLook slot after the shadow pass
  (`ShadeBindings.setShadowTint`; no longer one frame late; `ShadeWeather.tintCasters` is gone, `pack` writes "none").
  ShadeLook 608 `heldLight` (reserved, unused by every branch) is now **`tintRect`** (`ShadeLook.TINT_RECT`). Receivers
  (`shadeShadowTint`, now with `L`) return before the sample outside it, and `shadeShadowTintDelta` returns 0 for tint 1.

- Quad-shuffle guard: the quad-shared puddle SSR block now starts with `quadPuddle &&` (no shuffles over lanes that left).
- `COLOR_SHADOWS` needs `ShadeConfig.HDR` (LDR has no reader: no tint pass there).
- LDR variants (`mcopt.shade.hdr=false`, `mcopt.lod`: no ShadeResources, no baked tile) use a lite analytic field, only
  the one needed (3 value noises for puddles, 2 for snow, instead of 9).
- Snowfall: `ShadeLook.wind.w` is now the snowfall at the camera (0..1, smoothed 1.5 s; the gust only shaped the slant on
  the CPU); `shadeWeather3` gives flakes M2.1's alpha 0.55 and no torch heuristic while it snows. Not looked at in game yet.

**Next, in this order** (the 21:55 task list; item 3 done):
1. Game test of the above (glass shots: tint still there; price wxdry / wxdry_notint near glass and near water).
2. Budget in heavy rain at balanced (0.80 ms in wx17 incl. ~0.19 tint): price after item 1; candidates as dev bits 32-512.
4. Snowfall shot: mcbench WeatherShots `snowfall` pose (nearest snowy biome, like DimShots.aurora's search), look at it.
5. Moving view: a `wxmove` phase (walk across the puddles) recorded with `-Dmcopt.rec=... -Dmcopt.rec.start=wxmove`.
6. Integration notes (slot 4 vs lights, tint delta duplicate). 7. WEATHER.md prices and stale "~0" claims (skeptic finding 6).

**Earlier (resume session 18:06-20:50):** the WIP commit (baked puddle/snow noise tile) is finished and kept; puddle SSR only
where Fresnel shows it; terrain skips the coloured-shadow lookup when no translucent casters are near; rough films skip
the sky lookup; balanced uses one raindrop grid and 12 reflection steps; puddle cover back to ~31 %; dev bits and mcbench
price phases for every part; a module-off baseline; a DH sanity run (wx18) is clean. Screenshots:
`work/release/M3-weather-screenshots/` (`final_*` = build ccaad51).

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
| `mcopt.shade.snowCover` | true | snow on top faces while it snows |
| `mcopt.shade.rainSlant` | true | rain leans with the wind |
| `mcopt.shade.lightning` | true | flash light + HDR bolt |
| `mcopt.shade.colorShadows` / `.colorShadowRes` | true / 1024 (2048 ultra) | coloured shadows |
| `mcopt.shade.weatherTime` | 1 | time scale of wetting and drying (the bench uses 10) |
| `mcopt.shade.wetness`, `mcopt.shade.snowAmount` | -1 | force wetness+puddles / snow cover (look development) |
| `mcopt.shade.flashLight` | 5 | the flash's light (radiance on a white surface, before exposure) |
| `mcopt.shade.debug=8` | | debug view: red = wetness, green = puddles, blue = snow |
| `mcopt.shade.dev` bits (read every frame) | 0 | pricing: 1024 no puddle SSR, 2048 no ripples, 4096 dry terrain, 8192 M2 rain shading, 16384 no coloured shadows (tint pass and lookup), 32768 per-pixel noise instead of the baked tile, 65536 puddle SSR without the Fresnel gate |

## Integration (what changed outside the new files)

New files: `metal/src/main/resources/mcopt/shade/weather_surface.metal` (receiver side: wet layer, puddles, ripples,
history SSR, flash, shadow tint), `metal/src/main/java/mcopt/metal/shade/weather/ShadeWeather.java` (flags, CPU state,
ShadeLook packing, MSL prelude; no game types), `WeatherFrame.java` (game-typed per-frame update: precipitation at the
camera, lightning bolts).

| Where | Change |
|---|---|
| `shade_look.metal` | `ShadeLook.wet` (x wetness, y puddles, z snow, w rain intensity), `wind` (xy dir, z slant, w gust), `flash` (xyz dir, w light) now documented and written; `ssr.y` = 1 when `ShadeResources.prevDepth` is valid, `ssr.z` = 1 when the noise tile is baked, `ssr.w` = 1 when the tint map has casters (one frame late). `ShadeResources` entries **4 = `weatherNoise`** (baked puddle/snow tile), **5 = `prevDepth`** (TAA history linear view depth, R16F, output size) and **6 = `shadowTint`** (were `reserved4/5/6`). Entry 7 stays free. **Merge note:** BASE.md offered slots 4-7 to the dynamic-lights module; if it takes one, move the weather noise (any free slot works, it is only read through `sr.weatherNoise`). |
| `ShadeLook.pack` | calls `ShadeWeather.pack(b)` at the end (zeros when the module is off). |
| `ShadeFrameUpdater.update` | `WeatherFrame.update(...)` after `LOOK.update`, before packing; `ShadeWeather.bakeNoise(enc)` (once, HDR only) after the water bake; the `mcopt.shade.log` line gets the weather state. |
| `ShadePost.beginWorld` | publishes `ShadeResources.prevDepth` (5) = `taa.historyDepth()` when the history is valid, and `ShadeBindings.setPrevDepthValid` (`ssr.y`). General infrastructure: any pass can march last frame's image with it. |
| `ShadeMsl.terrain` | `ShadeWeather.prelude()` (defines + weather_surface.metal) in front of every terrain **fragment**, before the water module's prelude. |
| `ShadeMsl.weather` / `weatherVertex` / `lightning` | the weather fragment calls `shadeWeather3` (gets `[[position]]` and the sky LUT); the weather vertex stage is patched to lean the rain (`shadeRainSlant`); `minecraft:pipeline/lightning` is registered (bolt ×30-40 in HDR). |
| `sodium_terrain.fs.metal` (PolyForm) | `constant ShadeResources& sr [[buffer(19)]]` is now declared for HDR variants with the water **or** weather module; in the non-water branch: `shadeWetSurface` → wet albedo → `shadeLit2` → emissive → coloured-shadow delta (`shadeShadowTintDelta`) → flash (`shadeWxFlash`) → `shadeWetShade`; debug 8. |
| `water.metal` | ripples added to the top-face wave normal in `shadeWaterAbove` (`#if SHADE_WEATHER && SHADE_RIPPLES`). |
| `sky.fs.metal` | the flash lights the sky (`sl.flash`). |
| `weather.metal` | `shadeWeather3`, `shadeRainSlant` (M2's `shadeWeather` kept for the module off). |
| `shadow.metal`, `mcshadow.m`, `ShadowNative`, `ShadowCasters`, `ShadowPass` | coloured shadows: `shadow_tint_vs/fs`, `mc_shadow_tint_init/texture/render`; `ShadowCasters.KIND_TRANSLUCENT` lists (near cascade only); `ShadowPass` draws them after cascade 0, publishes the map as ShadeResources 6 and sets `ShadeWeather.tintCasters` when any were drawn (the terrain skips the lookup otherwise). |
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
`wxrain` / `wxdry` of the same session (drift check: `wxrain2` at the end). cpu-load.txt stayed at 90-200 % during the
perf phases of every run listed (clean; the >350 % samples are the world loading before them). Provisional: clean final
numbers come after the M3 integration.

### What each part costs (ms of GPU time per frame)

Method: each run prices every part **inside one session** with the `mcopt.shade.dev` bits (a 10 s phase with that part
off, against `wxrain`/`wxdry` of the same session). Two biases showed up and are corrected with **a run of the same phases
with the module off** (`wx14`, `mcopt.shade.weather=false`: the dev bits do nothing there, so any difference between its
phases is pure bias): (1) the first phase (`wxdry`) is ~0.17 ms slower than the second in every run, module on or off
(warm-up), so the "coloured shadows" price that looked like 0.10-0.18 ms is really **~0**; (2) the GPU drifts up
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
cpu-load 90-200 % during the phases; the same 10 s pans; wx14 had no glass roof, the others did):

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
coloured shadows **~0** at the receiver (the pass draws only the near cascade's glass/water), and **rain costs ~0.8-1.0 ms
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
- **Budget (bible §7: ~0.1 ms dry, <= 0.5 ms in rain at balanced): not met.** Rain is 0.3-0.4 ms over (0.80 ms in the
  cleanest session, ~0.9 ms on/off); dry is 0.19 ms with glass in range (the lookup is skipped without).
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
