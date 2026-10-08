# M3.3 wet ground: the whole open ground soaks in the rain (branch `m3.3-wet` from m3-int 397e241)

## M3.3 Status (2026-10-08 11:35, resumed session, merged in m3-int) - DONE

**wet3-veg** (`bench-results/20261008-112029-wet3-veg`, jar 077db60, AC): in-session price (wetprice.py, drift-corrected):
rain pan `wxrain` M3.2 8.625 ms, wetAll 8.835 (**+0.21 ms**), wetAll without film reflections (`wetSsr=0`) 8.747 (+0.12),
wetTex 3 +0.26, dry 8.154 (rain itself 0.47 ms in M3.2, 0.68 with wetAll); rain over sand `wetperf-sand`: +0.165 / -0.05.
Look (`wet_*_m32/new/nossr/veg04/veg07/tex3`): the whole meadow, paths and sand are darker and carry a soft sheen, the
puddles still stand out (deeper, mirror-like); without film reflections the ground shows a brighter, flatter sky sheen
(less natural), so **defaults stay as 077db60** (wetAll true, wetSsr on, wetVegFilm 0.55, wetTex off). Release images:
`work/release/M3.3-screenshots/wet_{ground,sand,storm}_antes_despues.jpg`, `wet_sunset_recorte_antes_despues.jpg`,
`wet_noche_antes_despues.jpg`. Cheaper option if needed: `-Dmcopt.shade.wetSsr=0` (+0.12 ms instead of +0.21).

## M3.3 Status (2026-10-08 11:00, resumed after the 10:52 usage stop)

**Done:**
- 1cb8b14 .. 077db60: `mcopt.shade.wetAll` (default true, read every frame; false at startup = M3.2's shaders). Solid open
  ground soaks into a water film; vegetation keeps a share of it; a wetter rim into the puddles; film reflections on smooth
  wet ground; knobs all live (`ShadeLook.water` / `waterAbs`). Build OK, validate.sh ALL OK at every commit.
- mcbench 8e3bb62 + bcbb8eb: `WetShots` (A/B shots at frozen ticks; `-Dmcbench.wetPerf` recorded phases at its poses).
- **wet1** (`20261008-102055-wet1-look`, jar 1cb8b14, AC): first look + price. Film shading itself is ~free (+0.02 ms), film
  reflections on all wet ground +0.20 ms (too much). Look: the film at grass roughness 0.3 with k ~0.9 turned the meadow
  into a flooded marsh (a uniform grey sky sheet in rain, a glassy lake at sunset), puddles no longer stood out. -> 077db60:
  vegetation keeps 55 % of the film and a rougher one (~0.38: no film reflections on grass), extra darkening 0.2, the rim
  only turns into standing water near the puddle's edge.
- **wet2-tex** (`20261008-103258-wet2-tex`, jar 1ba36fc = wet1's film settings + wetTex/wetRim, **battery**, residual
  0.05 ms): the texel proxy wetTex 3 breaks the sheet a little (grass blades poke through) but with wet1's film the meadow
  still reads flooded; narrow rim (0.06) changes little. Price (battery, noisy): wetTex 4 +0.04 ms over new.

**Running / next:**
1. wet3-veg (jar `m3.3-wet-077db60`, launched 10:56, waiting for the lock; log `work/notes/m33-2026-10-08/wet/wet3.log`):
   all scenes x {m32, new, veg04, veg07, tex3, nossr}; ab wxrain m32/new/tex/dry/nossr (ABBA-ish); wetPerf sand m32/new/nossr.
   Read it with `python3 work/notes/m33-2026-10-08/wet/wetprice.py <dir>` and the images with sbs.py.
2. Pick the defaults from wet3 (if the pick is one of its variants, wet3's images are the final evidence: set the default in
   ShadeWeather.java line ~214-217, build, validate, copy jar). Else one final run (AC): M3.2 vs new at every pose + price.
3. Side-by-sides to `work/release/M3.3-screenshots/wet_*.jpg` (sbs.py), fill Prices/Screenshots below, report.

## Gael's request

"¿Por qué cuando llueve no se moja todo el suelo y quedan unas partes mojadas y otras como charcos? Yo creo que se vería mejor
que se moje todo el suelo del mapa, no solo tantitas partes, porque se ve raro."

Why it looked like that in M3.2: every open-sky surface did get wetness, but on grass (most of the ground) the film was
nearly invisible: vegetation added +0.25 roughness (soaked grass ~0.63: only a blurred mean-sky sheen) and the film's weight
was `wetness x 0.65 x (1 - 0.5 veg)` (~0.33 on grass). Only the puddles (~31 % of open ground, roughness 0.03, SSR mirror)
read as wet: matte dry-looking grass with mirror blobs.

## What changes (flag `mcopt.shade.wetAll`)

| | M3.2 (`wetAll=false`) | M3.3 (`wetAll=true`) |
|---|---|---|
| Soaked film roughness | 0.38 stone/paths, ~0.63 grass (+0.25 vegetation) | `wetRough` 0.2 stone/paths/wood/sand, + `wetVeg` 0.1 on vegetation (grass ~0.3); smoother in the puddle field's hollows (±0.07, blocks-wide patches) |
| Film weight k (Fresnel sheen + diffuse dimming) | wetness x 0.65 x (1 - 0.5 veg) | soak (wetness x 1.15, capped) x (1 - 0.12 veg) |
| Around puddles | a darker wet rim (field t-0.07..t) | the same, plus the film thickens toward the puddle over the field's last 0.12 (roughness -> 0.06, k -> 1, albedo x0.88): no hard "wet blob on dry grass" edge |
| Puddle albedo | x0.7 at the edge | x0.88 from the rim, x0.8 more in the puddle (x0.7 at the middle, reached gradually) |
| Reflections | puddles only (SSR where F x P >= 0.05) | also on up-facing soaked ground where F x k x gloss >= `wetSsr` (0.06; gloss = 0 above roughness 0.42), smeared along the screen's vertical by the roughness; same quad-shared march |
| Crossed plants, leaves, walls | | crossed plants and leaves keep M3.2's light film; walls take the film at 55 % wetness |
| Under cover / drying | | unchanged: rain only where sky light is 15, wetness soaks in (14 s) and dries (75 s), puddles lag and last longer (110 s), the rim stays wet longest |

## Flags

| Flag | Default | Read | |
|---|---|---|---|
| `mcopt.shade.wetAll` | true | startup + every frame | false at startup: weather_surface.metal compiles without the new code (M3.2's shaders); true: the property is read again every frame (false = M3.2's look, for in-session A/B) |
| `mcopt.shade.wetRough` | 0.2 | every frame | soaked film roughness of stone, paths, wood, sand |
| `mcopt.shade.wetVeg` | 0.18 | every frame | extra roughness on vegetation (grass tops: ~0.38, so no film reflections there) |
| `mcopt.shade.wetVegFilm` | 0.55 | every frame | vegetation's share of the film (k), by hue: grass blades stand out of the water |
| `mcopt.shade.wetSsr` | 0.06 where puddle reflections are on (balanced+), else 0 | every frame | film reflections' gate on F x k x gloss (0 = no film reflections) |
| `mcopt.shade.wetDark` | 0.2 | every frame | extra darkening exponent of the film surfaces (albedo^(1 + 0.2 wetness) on top of M3.2's law) |
| `mcopt.shade.wetTex` | 0 (being tuned) | every frame | texel height proxy: the texel's luminance against its sprite's mean (atlas mip 4) x this, -1..1: raised texels rougher (+0.18) and partly dry (k -35 %), crevices smoother; one extra texture sample per soaked pixel |
| `mcopt.shade.wetRim` | 0.12 | every frame | width of the wetter rim below the puddle threshold (puddle-field units; 0.12 = ~33 % of open ground in the band, 0.06 = ~17 %) |
| `mcopt.shade.wetness` | -1 | every frame (was: startup) | force wetness + puddles (bench: the wet sunset A/B) |

## Integration notes (for the orchestrator's merge)

All edits are local to the weather module except where noted. The m3.3-misc branch edits the same `weather_surface.metal`
(rain budget: SSR gate F x P 0.06 lines, ripple fade line, march template); the hunks are adjacent but do not overlap.

| File | Edit | Why |
|---|---|---|
| `weather_surface.metal` | new block `SHADE_WET_ALL_ON(L)` just before "wet surfaces and puddles" | the per-frame switch |
| `weather_surface.metal` | `ShadeWet` gets `film`, `low`, `var`, `dark` | the film's inputs from shadeWetSurface to shadeWetShade |
| `weather_surface.metal` | `shadeWetSurface`: init of the 4 fields; `w.film` after `w.omega`; `w.low`/`w.var` after `w.puddle = P` | film, rim, hollows |
| `weather_surface.metal` | `shadeWetAlbedo`: the film branch (rim darkening, puddle x0.8) before M3.2's puddle line (now its `else`) | gradual darkening into the puddle |
| `weather_surface.metal` | `shadeWxHistSsr`: new last parameter `float streak = 0.0` and 3-tap vertical smear after the `float3 c = hist.sample(...)` line | film reflections are rougher than a puddle's |
| `weather_surface.metal` | `shadeWetShade`: `quadFilm` next to `quadPuddle` (early return needs both false); film roughness/k block after M3.2's `rough` line; `filmW` after the `ssrW` line (misc's `SHADE_WX_B_GATE_ON` line is just above it); `(quadPuddle \|\| quadFilm)` in the SSR condition; `streak` before the `shadeWxHistSsr` call; `k` picks the film's | the film look and its reflections |
| `sodium_terrain.fs.metal` (shared, PolyForm) | 2273dd1: `#ifdef SHADE_WET_ALL` block (5 lines) just before `half3 wetAlbedo = ... shadeWetAlbedo(...)`: one atlas sample at mip 4 -> `wet.tex` (shadeWetTexHeight) | the film follows the texture (anti-varnish) |
| `ShadeWeather.java` | `WET_ALL`, `WET_SSR`; `pack` writes `ShadeLook.WATER` / `WATER_ABS` (also zeros when the module is off); `prelude` adds `#define SHADE_WET_ALL` (not for the translucent fragment); `step` reads `mcopt.shade.wetness` every frame; describe | flags, per-frame state |
| `shade_look.metal` (shared) | comments of `water` (576) and `waterAbs` (592) only: they were "reserved for the water module" and unused by it; now the M3.3 wet state | if the water module ever needs them, move the wet state |
| mcbench | `WetShots.java` (new), one line in `BenchDriver.buildPlan` | A/B shots |

## Prices

GPU ms per frame, in-session (MiscShots ab phases on WeatherShots' rain pan: 2.6 blocks above the meadow beside the torch
grid, +-25 degrees, 10 s each), balanced, TAAU 0.80, DRS off, drift-fitted (`work/notes/m33-2026-10-08/wet/wetprice.py`).

| Run | Power | M3.2 rain (vs terrain dry) | wetAll vs M3.2 | of which film reflections | Note |
|---|---|---|---|---|---|
| wet1 `20261008-102055-wet1-look` (1cb8b14) | AC | 0.70 | +0.22 | +0.20 | film shading alone +0.02; residual rms 0.017 ms |

## Screenshots

(pending)
