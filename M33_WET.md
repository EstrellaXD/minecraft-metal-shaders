# M3.3 wet ground: the whole open ground soaks in the rain (branch `m3.3-wet` from m3-int 397e241)

## M3.3 Status (2026-10-08 10:10)

**Done:**
- 1cb8b14: `mcopt.shade.wetAll` (default true). Solid open ground soaks into a water film: darker (M3.2's porous law), a
  Fresnel sheen of the sky on every wet floor, the sun's highlight on it, a wetter rim that blends into the puddles, film
  reflections through the puddle march where F x k x gloss shows them (vertical streak), all read every frame from
  `ShadeLook.water` / `waterAbs` (so one game session can A/B everything). `mcopt.shade.wetness` is read every frame too.
  Build OK, validate.sh ALL OK.
- mcbench 8e3bb62: `WetShots` (`-Dmcbench.wet=...`, frozen-tick A/B shots at ground / ground_low / sunset / night / storm /
  path / sand / stone poses).

**Running / next:**
1. wet1-look (jar `m3.3-wet-1cb8b14`, queued 10:02): 6 look variants (m32, new, nossr, rough, glossy, dark) at 8 poses +
   in-session price (ab phases on `wxrain`).
2. wet2-tex (jar `m3.3-wet-1ba36fc`, queued 10:07): texel height proxy and rim width variants + price of the texel sample.
3. Tune defaults by the screenshots, then a final run: M3.2 vs new side by side (`work/release/M3.3-screenshots/wet_*.jpg`) + price.

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
| `mcopt.shade.wetVeg` | 0.1 | every frame | extra roughness on vegetation (grass tops) |
| `mcopt.shade.wetSsr` | 0.06 where puddle reflections are on (balanced+), else 0 | every frame | film reflections' gate on F x k x gloss (0 = no film reflections) |
| `mcopt.shade.wetDark`, `mcopt.shade.wetSheen` | 0, 1 | every frame | look development: extra darkening exponent of the film, film weight scale |
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

(pending: wet1)

## Screenshots

(pending)
