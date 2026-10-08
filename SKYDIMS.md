# M3 sky, dimensions and XDR (branch `m3-skydims`, from `m2.1`)

What this branch adds (bible E04 aurora, E23 XDR, E24 Nether, E25 End, E26 camera media), how it plugs into the M2 base,
and what an integrator merging it with the other M3 branches has to watch. Everything is behind `mcopt.shade=true` plus
its own flags; with `mcopt.shade=false` nothing here loads (the mixin plugin, the registry entry and the MetalSurface hook
all test `Shade.ENABLED`, a `static final false`).

## M3.3 Status (2026-10-08 11:50, resumed session, merged in m3-int) - DONE

- **R5** (`bench-results/*-dims-dh5`, DEV-DH, jar 252f407): End + Distant Horizons: v1 (= M3.2) coloured-noise rims and a black
  void; now the End sky (nebula, stars, beams) everywhere DH leaves the clear colour, island clean; Nether + DH unchanged.
  `work/release/M3.3-screenshots/dims_end_dh_cielo_antes_despues.jpg`.
- **R4** (`bench-results/*-dims-r4`, DEV, **battery**, in-session `abtable.py`): aurora: `aurora.half` 6.403 / 6.425 ms vs
  per-pixel (M3.2) 6.439 / 6.443 vs no aurora 6.027 / 6.041: the half-resolution pass saves only ~0.03 ms (the aurora is ~0.39 ms
  either way); same look (`aurora_snow` vs v1). Kept (harmless; with the WIP commit d25a90b its size follows the render scale).
  Nether: ours (lean) 4.62 ms, M2.1 look (`dims.pause`) 4.08, vanilla light 4.83 (no gain). XDR skip, where it engages (vanilla
  Nether light): 4.72 vs 4.80 ms = -0.08 ms. End blob (`end_mobs`): M3.2 drew the blob under the real shadow, now only the real
  shadow (`dims_end_mobs_blob_antes_despues.jpg`).
Defaults: all M3.3 dims flags on (motes, endBlob, dhClear, aurora.half, netherLean, xdrSkip).

## M3.3 Status (2026-10-08 11:20) — agent "dims", branch `m3.3-dims` (from m3-int 397e241)

Plan (in this order; each item ends done with in-game before/after evidence, or with a precise note):
1. Nether dark specks. 2. End blob shadow next to the End key light's shadows. 3. End + Distant Horizons broken.
4. Aurora price. 5. Nether +0.6 ms. 6. XDR skip when nothing is above the knee.

Runs so far (all AC power, `work/bench-results/`): `20261008-093853-dims-dh1` (End+DH diagnosis), `20261008-101036-dims-dh2`
(End+DH fix, jar `m3.3-dims-9b16fb3`), `20261008-104241-dims-r3` (DEV shots v1 = every M3.3 flag off, in-session prices; same
jar; table: `python3 work/notes/m33-2026-10-08/dims/abtable.py <run>`). Running now: `work/notes/m33-2026-10-08/dims/r45.sh`
with jar `m3.3-dims-252f407` (R5 = DEV-DH End/Nether, R4 = DEV shots + 16 price phases; log `r45.log` next to it).

- **1. Nether specks: DONE.** They are warped spores (`opaque_particle`, vanilla colour 0.1/0.1/0.3) and soul-valley ash,
  already lit by `shadeLitDims` and fogged: dark because vanilla made them navy for its black fog. `mcopt.shade.dims.motes`
  (live, default true): Nether particles' albedo luminance lifted to a floor of 0.16, hue kept. r3 shots: spores now blue
  specks instead of black dots (`work/release/M3.3-screenshots/dims_nether_warped_before.png` / `_after.png`, full-res crops);
  basalt's white ash unchanged (no dark specks in either).
- **2. End blob** (`mcopt.shade.dims.endBlob`, live, default true): r3's `end_mobs` could not show it (the mobs stood in a
  pillar's shadow, camera above them). mcbench a423879 moves them to x = -18 (out of the pillars' shadows under the key
  light from +x +z at 55 degrees), 3 blocks apart. Shots in R4.
- **3. End + DH: root cause and fix DONE** (dh2: `end_island` clean with the fix, the v1 = M3.2 shot shows the coloured-noise
  feedback; `work/release/M3.3-screenshots/dims_end_dh_before_after.jpg`). Root cause: the End with the dragon alive has no
  sky pass, so Distant Horizons' first pass was the first to touch main; `ShadePost.openBridge` dropped main's pending clear,
  the HDR target kept the last frame, and DH's TAA + sharpen re-processed it every frame (a feedback loop). Fix
  `mcopt.shade.dhClear` (live, default true): the clear is applied to the HDR target first. Left over in dh2: with DH the End
  void was plain dark (the final pass skipped its End sky wherever DH drew). 252f407 paints it where the pixel still holds
  the clear colour (luma <= 1.5 x the fog colour's): check in R5.
- **4. Aurora**: r3 showed the 2x2-shared aurora (`aurora.lean`) saves nothing in game (6.43 vs 6.47 ms; the aurora costs
  ~0.40 ms there: `aurora.pause` 6.05 ms), and XDR stayed on in all those phases (no side effect). Replaced (252f407) by a
  reduced-resolution pass (`mcopt.shade.aurora.half`, live, default true; false = M3.2): `ShadeAurora` renders M3.2's
  `shadeAurora` unchanged into an RGBA16F texture of 0.4 x the output (`mcopt.shade.aurora.scale`), the sky pass reads it with
  one tap and draws with its lean variant (no per-pixel aurora code). Price + look in R4.
- **5. Nether**: `mcopt.shade.dims.netherLean` (live, default true) = the Nether's own lean terrain variant: r3 in-session
  4.505 ms vs 4.786 ms with it off = **-0.28 ms**, same look (r3 diff nether_wastes vs v1: differences only on animated lava
  and particles). `mcopt.shade.dims.netherLight=vanilla` (lightmap + our fog) measured 4.493 ms = no further gain (and XDR
  was skipped during it: even less). R4 re-measures against the M2.1 look (dims.pause) with XDR skip off throughout.
- **6. XDR skip** (`mcopt.shade.xdrSkip`, live, default true): r3: it almost never engages in the bench scenes (lava, End
  crystals, the moon keep a pixel near the knee): end_island / overview A/B within noise. It engages in the vanilla-light
  Nether: R4 prices it there (dab13-16).

Flags (all live): `mcopt.shade.dims.motes`, `mcopt.shade.dims.endBlob`, `mcopt.shade.dhClear`, `mcopt.shade.aurora.half`
(+ static `mcopt.shade.aurora.scale` 0.4), `mcopt.shade.dims.netherLean`, `mcopt.shade.dims.netherLight` (option, default
ours), `mcopt.shade.xdrSkip` (+ `mcopt.shade.xdrSkip.log`). Off = M3.2 for each. Dev switches (off by default):
`mcopt.shade.passLog=<tag>`, `mcopt.shade.dhDebug=1`, `mcopt.shade.taa.cut=true`, `mcopt.shade.aurora.pause=true`.
mcbench (DimShots): `-Dmcbench.dimsVariants`, `-Dmcbench.dimsAb`, scene `end_mobs` (`-Dmcbench.dims=...,endmobs`).

Integration notes (shared files touched on this branch, for the orchestrator's merge):
- `shade_look.metal`: ShadeResources `reserved11` -> `aurora` (slot 11, M3.3 dims). Another module claiming slot 11 must move.
- `ShadeLean.java`: LEAN_NOAURORA also while `ShadeAurora.active()`; LEAN_NETHER bit (16) for the Nether variant (`update`).
- `ShadeMsl.java`: LEAN_NETHER = 16 (LEAN_MASK 31), variant defines, prewarm sets for the Nether's four variants.
- `sodium_terrain.fs.metal`: `SHADE_LEAN_NETHER` (shadow / cloud / overworld fog code compiled out in the Nether).
- `ShadeFrameUpdater.java`: one line after `renderSkyLut`: `ShadeAurora.render(enc)`.
- `ShadePost.java`: `openBridge` applies main's pending clear to the HDR target (dhClear); final pass `endSkyHere` (shade_post.metal).
- `ShadeBindings.java`: `takeXdrHot()` (ShadeGpu offset 464). `shade_entity.metal`: Nether motes floor. `ShadeTaa.java`: dev `taa.cut`.
- `sky.fs.metal`: the aurora read (`shadeAuroraTex`) compiled into both sky variants.

## Status (2026-10-07 22:10) — pre-merge fixes

Fixes from the skeptic check (`work/notes/m3-2026-10-07/prefix-checks.json`, check:skydims), built + validate ALL OK:
- **Merge collisions (BASE.md section 8) resolved on this side:** (a) the overworld no longer writes `ShadeLook.FLASH`
  (it belongs to the weather module's lightning there; this module writes it only while the dims are lit); (b)
  `FLAG_DIMS_LIT` moved from 4096 (weather's `SHADE_DEV_WX_DRY`) to `1 << 23` (convention: `mcopt.shade.dev` toggles count
  up from 1, per-frame state flags count down from `1 << 23`); (c) `ShadeFinalParams.r.w` is a bit field: 1 = DH drew,
  2 = M2.2's VL unjitter (vl-stable ORs 2 in at the merge); the final pass tests `(int(P.r.w) & 1)`.
- Nether heat shimmer keeps the TAAU sharpen (it was dropped for every Nether terrain pixel).
- End sky: nebula and stars fade below the horizon (15 % at 25 degrees down; the void no longer shows big teal clouds).
- Master switch `mcopt.shade.skydims` (true): false turns dims, aurora, media and XDR off (= M2.1), for the after-merge
  "every module flag off" check. Note: only run `sdr-dh-m21` (the M2.1 jar) is a true M2.1 baseline in 6.6.
- XDR highlights stay on with the chat open (it draws no background); other screens still drop them.
- Open, for the merge: **Nether particles** read as dark unfogged specks over the lit haze (vanilla lightmap at block
  light 0): m3 routes particles through `shadeEntityShade`, whose dims-lit branch must cover them (BASE.md 8.6 row);
  recheck nether_basalt / nether_warped after the merge. Aurora price unchanged (below).

## (previous) Status (2026-10-07 20:07)

**Module finished** (pending the merge into m3, which is the next workflow). Done today after the pause: WIP terrain
refactor completed; XDR: SDR-only-screen fallback (+ `mcopt.shade.xdrTest` dev switch), knee 3 -> 2 so torches/lava
really use the headroom (verified with dumps), frame generation keeps it off (verified); final pass split into an
overworld variant (no dims cost in the overworld) and a `_dims` variant; End toned down (-0.4 EV, sparse low beams,
fainter nebula), less lava glow in the Nether haze; Nether fog now also works with Distant Horizons; live
`mcopt.shade.dims.pause` + mcbench in-session price A/B; prices and looks in section 6; screenshots in
`work/release/M3-skydims-screenshots/`.

Open / next:
- End + Distant Horizons is broken (6.6): pre-existing (the M2.1 jar + DH shows the same), not this module; for FT1 /
  the integrator. Nether + DH now works (haze fixed and checked), except DH's far LODs are not fogged.
- Aurora costs ~0.15-0.25 ms on aurora nights (bible: 0.06-0.12). A 4-layer variant gave no gain (reverted). Next idea:
  evaluate it in the final pass only where depth is empty (the sky pass pays for pixels terrain covers later).
- Nether +0.6 ms vs M2.1 (per-pixel lighting replacing vanilla's lightmap). Possible performance-preset option: keep the
  lightmap path with our fog only.
- Entities in the Nether/End: after the merge, light them like terrain (section 5).
- XDR on a real SDR external monitor (XZ3020) not tested live (no monitor connected today); simulated with xdrTest.

## 1. Features

| Feature | Where | Flag (default) |
|---|---|---|
| **Aurora** (night, clear, cold biomes; hashed per night, ~35 %) | sky pass: `shadeAurora` in `shade_dims.metal`, called from `sky.fs.metal`; CPU state in `ShadeDims.overworld` | `mcopt.shade.aurora` = `auto` / `always` / `off` |
| **Nether look**: per-pixel lit terrain (biome ambient over a warm neutral, lava up-light from below, Nether lava glow x1.4), layered glowing fog from depth (5 biome palettes, smoothed), heat shimmer (quality/ultra) | `shadeLitDims` (terrain), `shadeDimsFog`, `shadeShimmer` (final pass) | `mcopt.shade.dims` (true), `mcopt.shade.netherShimmer` (quality/ultra) |
| **End look**: violet key light ("dead star", 55 degrees up) **with shadows**, violet ambient, thin haze, own sky (indigo gradient, turning nebula, stars, beams rising from the void, End flash) | `shadeLitDims`, `shadeEndSky` (`end_sky.{vs,fs}.metal` override of `minecraft:pipeline/end_sky`; painted by the final pass during the dragon fight, when vanilla draws no sky), `ShadowPass` End light | `mcopt.shade.dims`, `mcopt.shade.endShadows` (true) |
| **Camera media**: inside lava (glowing churning orange, dark edges), powder snow (frost) | `shadeMedia` (final pass) | `mcopt.shade.media` (true) |
| **XDR / EDR output** for the Liquid Retina XDR panel: highlights above SDR white up to the screen's live headroom, GUI/hand/screenshots stay SDR | `ShadeXdr` + `mcxdr.m`, `shade_final_xdr`, hooks in `mcmetal.m` (layer, present) | `mcopt.shade.xdr` = `auto` (on when a screen reports EDR potential) / `on` / `off`; `mcopt.shade.xdrPeak` (4) |

Underwater keeps the M2.1 water module's treatment (it already looked right; E26 water absorption is a later polish).

## 2. Files

New:
- `metal/src/main/resources/mcopt/shade/shade_dims.metal` (prelude, after `shade_m2.metal`): tileable noise lookups,
  `shadeLitDims`, `shadeEndSky`, `shadeAurora`, `shadeDimsFog`, `shadeShimmer`, `shadeMedia`, flags.
- `metal/src/main/resources/mcopt/shade/end_sky.{vs,fs}.metal`: the End sky box override.
- `metal/src/main/java/mcopt/metal/shade/dims/{DimsConfig, ShadeDims, ShadeXdr}.java`, `dims/mixin/{DimsMixinPlugin, DimsFogMixin}.java`,
  `metal/src/main/resources/mcopt-metal-shade-dims.mixins.json` (own mixin config, listed in `fabric.mod.json`).
- `metal/src/main/native/mcxdr.m` (added to `compileNative`; the build now links AppKit for `NSScreen`).

Touched (small, marked "M3" in comments):
- `ShadeMsl`: prelude + `shade_dims.metal`; registers `minecraft:pipeline/end_sky` (with `mcopt.shade.dims`).
- `ShadeFrameUpdater`: `ShadeDims.update` after the atmosphere update (before the shadow glue and the art layer),
  `ShadeDims.pack` after `ShadeLook.pack`, `ShadeXdr.frame`; a public `look()` accessor.
- `ShadePost`: final pass params grew to 96 B (`r.w` = Distant Horizons drew this frame, `x` = XDR); the XDR final pass
  (two targets) and its excess texture.
- `shade_post.metal`: `shade_final` split into `shadeFinalScene` + `shadeFinalEncode`, new `shade_final_xdr`; the dims fog,
  End sky (boss fog), media and shimmer are in `shadeFinalScene`; the Milky Way bake now writes a tileable fbm into alpha.
- `sodium_terrain.{vs,fs}.metal`: Nether/End lit per pixel when the sunPath.z bit says so (no lightmap in the vertex colour,
  `shadeLitDims` instead of `shadeLit2`, shadow lookups in the End, no overworld air fog there, Nether emissive x1.4).
- `sky.fs.metal`: `shadeAurora` added to the overworld sky.
- `ShadowPass.prepare`: in the End, the key light from `ShadeDims.endLight()` (constant: the far cascade stays cached).
- `MetalSurface.configure`: `ShadeXdr.configure` before `Native.layerConfigure`.
- `mcmetal.m`: EDR layer format; the present fill composes instead of blitting when XDR is on; RGBA16F present PSO.
- `tools/shade`: `ShadeMslDump` dumps the End sky variants; `pipecheck` has `postexcolor` and `mrt` layouts; `validate.sh`
  checks them and the XDR final pass.

## 3. Data contract additions

ShadeLook (no layout change; fields that were reserved):
- `night.z` = aurora strength 0..1.
- `dimTint` = (fog extinction 1/block, lava sea y, border-fog radius in blocks, 1 Nether / 2 End / 3 End without a sky pass).
- `flash` = (End flash direction, intensity).
- `tier.y` bits **1<<20** `SHADE_FEATURE_DIMS`, **1<<21** `SHADE_FEATURE_MEDIA`, **1<<22** `SHADE_FEATURE_SHIMMER` (chosen high
  to stay clear of other M3 modules counting up from 128).
- In the Nether/End: `exposureRef.y` (metering share) 0.3 / 0.4, `grade1.w` (Purkinje) 0, `bloom.x` +0.03 / +0.02.

ShadeFrame (no layout change):
- `sunPath.z` bit **4096** `SHADE_FLAG_DIMS_LIT`: Nether/End terrain lit per pixel (the vertex shader only binds ShadeFrame).
- Outside the overworld, `sunColor`/`lightDirWorld` = the End key light (0 in the Nether), `ambient*` = the dimension
  ambient, `horizon[0..7]` = the palette (`SHADE_DP_*`; M1's deprecated horizon table is unused there), `fogColor.a` = 0 in
  open air (our fog), `time.z` = 0.86 (the palettes' reference exposure).

ShadeResources: nothing new. `milkyWay.a` now holds a tileable fbm noise (1024x512, base lattice 16x8, 5 octaves),
read through `shadeDimsNoise` by the aurora, End sky, Nether fog and media: no extra texture or slot.

Vanilla fog: `DimsFogMixin` (FogRenderer.setupFog RETURN) pushes the environmental fog out of reach in the Nether and the
End in open air (no blindness/darkness), so vanilla pipelines (entities, particles) don't add vanilla's fog over ours; its
original end distance scales our density (`ShadeDims.vanillaFog`). The render-distance fog stays.

## 4. XDR, how it works

```
final pass (shade_final_xdr, MRT) --> main RGBA8: the same SDR image, alpha 0        --> hand, screen effects, GUI blend
                                  \-> excess RG11B10F: luminance above SDR white          over main (their alpha > 0)
present (mcmetal.m pqFill / mc_present): compose pass -> RGBA16F staging slot (or the drawable)
    out = M709->P3 * ( srgb->linear(main.rgb) + excess * (1 - main.a) )      layer: RGBA16Float, extendedLinearDisplayP3, EDR
```
- The SDR curve is untouched up to a knee (exposed scene luminance 2, `mcopt.shade.xdrKnee`: lit white walls, the sky); above it the brightness
  rises smoothly over 3.5 octaves to the peak, in the pixel's SDR hue. Peak = the screen's live EDR headroom
  (`NSScreen.maximumExtendedDynamicRangeColorComponentValue`, polled 4x/s, smoothed tau 0.5 s), capped by `mcopt.shade.xdrPeak`.
- The excess counts only for the present of the frame whose final pass wrote it (same command buffer), so menus never
  show stale highlights. GUI coverage comes from main's alpha: no second GUI draw.
- Screenshots (F2, the bench) read main: the SDR image. `MCOPT_XDR_DUMP=<prefix>` (environment) writes one composed frame
  every 8 s as raw RGBA16F for checking (`--env` in mcbench-run); `mcopt.shade.debug=8` shows the boost (red) and knee (green).
- Off with frame generation (its own present path).

## 5. Integration with the other M3 branches

- **Dynamic lights / held light**: Nether/End terrain calls the same single `shadeLit2(...)` as the overworld (sky light
  and sun shadow passed as 0 there) and then adds `shadeLitDimsExtra(...)` (the dimension ambient, lava up-light, End key
  light and flash). So a term added inside `shadeLit2`, or right after that call in `sodium_terrain.fs.metal`
  (`radiance += shadeDynamicLights(...)`), lights the Nether and the End with no extra work.
- **Entities (M3 base `shade_entity.metal`)**: the base keeps vanilla colours for entities outside the overworld. After the
  merge, `shadeEntityShade` should take the same branch as terrain when `shadeDimsLit(f)` is set (shadeLit2 with sky 0 +
  `shadeLitDimsExtra`), otherwise mobs in the Nether/End look flat next to the lit terrain. Not done here (this branch is
  from m2.1, without the entity lighting).
- **Clouds / weather**: aurora is added in `sky.fs.metal`'s `shadeSkyPixel` after `shadeSkyRadiance2`; a cloud layer drawn
  in the sky pass should composite over it. The dimension fog and media run in `shadeFinalScene` right after the overworld
  haze; anything else from depth there (rain fog, clouds composite) goes after it in the same order.
- **ShadeResources slots 4-7**: untouched here.
- **ShadeFinalParams** is 80 -> 96 bytes (`x`), `r.w` is now used: modules adding fields append after `x`.
- **mcbench**: `DimShots.java` (`-Dmcbench.dims`, `-Dmcbench.dimsPerf`); phases `nether`, `end` in REC_PHASES.
- **DH**: the Nether fog leaves empty-depth pixels alone when DH drew into the frame (`r.w`); End sky painting in the final
  pass is also skipped then. Distant Horizons in the overworld is unaffected except for XDR (DH pixels are SDR content).

## 6. Results

### 6.1 Price, in-session A/B (run `20261007-182336-sdr-r1`, AC power, balanced preset, DRS off, 3024x1964)

Each price is measured inside one game session: the same 10 s camera spin with the feature on, paused, and on again
(`mcopt.shade.dims.pause` / `mcopt.shade.xdrTest=sdr`, switched live by mcbench DimShots), so launch-to-launch drift and
other agents' load mostly cancel. GPU ms per frame (lower is better) and fps:

| Scene | On | Paused (= M2.1 look) | On again | Price |
|---|---|---|---|---|
| Nether (wastes, spin) | 4.65 ms (215 fps) | 4.10 ms (244 fps) | 4.60 ms (218 fps) | **+0.53 ms** (before the view-distance fix, see below) |
| End (main island, spin) | 4.20 ms (238 fps) | 3.10 ms (322 fps) | 4.21 ms (238 fps) | **+1.10 ms** (End shadows + sky + light) |
| Overworld noon, XDR highlights | 11.88 ms | 11.56 ms (SDR-screen mode) | 11.55 ms | **~0** (on-again equals paused; the first segment was still settling) |

Why the Nether costs +0.5 ms: M2.1 drew Nether terrain with vanilla's lightmapped colour (an early exit, almost free);
the dims look lights it per pixel like the overworld (block light, the biome ambient, lava up-light, no shadows) and adds
our fog in the final pass. First suspicion was Sodium's fog culling (it stops drawing chunks where vanilla's fog ends,
~96 blocks in the Nether, and our fog replaced vanilla's): a cut at 1.7x vanilla's fog end measured no gain (run
`sdr-r4`: on 4.54 / paused 4.02 / on 4.53 ms, the same +0.5 ms), so it stays off (`mcopt.shade.netherView` keeps it as an
option). The +0.5 ms is the lighting itself: the price of the look. The Nether frame is still ~4.5 ms (~220 fps).

The End's +1.1 ms buys what the overworld already pays for: a shadow-casting key light (cascades, far one cached), the
sky (nebula, stars, beams) and per-pixel light. The End frame (4.2 ms, ~240 fps) stays far below the overworld's (~11.5 ms
at noon with all M2 effects), and below the bible's End budget ("like the overworld minus clouds").

### 6.2 Final pass and XDR (GPU microbenchmark, `tools/shade/finaltime`, 3024x1964, every pixel on the haze path)

Measured with no game running (between other agents' runs), best of 8 rounds:

| Pass | m2.1 | this branch |
|---|---|---|
| final pass, overworld (`shade_final`) | 1.52 ms | 1.53 ms (+0.015) |
| final pass with XDR highlights (`shade_final_xdr`) | - | 1.62 ms (+0.10) |
| final pass in the Nether/End or inside lava/powder snow (`shade_final_dims`) | - | 1.68 ms (+0.16, worst case: every pixel fogged) |
| present copy: plain RGBA8 blit vs XDR compose into RGB9E5 | 0.133 ms | 0.150 ms (+0.017) |

Before the split into two variants the overworld final pass carried the dims code and cost +0.12 ms; now the overworld
pays nothing for it. In game the XDR highlights' on/off A/B (6.1, `sdr-r4`: 11.73 / 11.34 / 11.43 ms) is within
+0.1-0.4 ms: about +0.15 ms, inside the bible's E23 budget (+0.2-0.4 ms). The RGBA16F drawable itself (compositor side)
is not in these numbers.

### 6.3 XDR checks

- **Where it goes above SDR** (`MCOPT_XDR_DUMP`, 24 frames of run `sdr-r1`, analysed with `tools/shade/xdrdump/xdrdump.py`):
  with the first knee (3) nothing went above 1.2x SDR white in the Nether, the End, the aurora, lava or at noon, except
  one world-join frame while auto exposure was still adapting (27 % of the screen at up to 1.29x). That was too timid:
  lava, glowstone and torches never glowed. Knee lowered to 2 (3.5 octaves). Check (run `sdr-flip`, 9 dumps): at noon and
  in the evening fields nothing but small spots exceeds SDR white (lit snow, grass, sky stay at 0.5-0.66 of it); in the
  night-torches view the torch flames, and only they, go up to 2.8x (`work/release/M3-skydims-screenshots/xdr_map_night_torches.png`:
  left the SDR image, right the map of what is brighter than SDR white). The world-join frame (auto exposure still
  adapting) reaches 1.2x on a quarter of the screen for a moment; harmless.
- **GUI stays SDR**: main's alpha is the GUI coverage; while a screen is open no excess is written at all (the menu
  blur would otherwise show unblurred highlights). Screenshots read main: always SDR.
- **Frame generation**: run `sdr-fg` (`-Dmcopt.metal.framegen=true`, auto): log says "XDR output off (... frame
  generation: off)", perf segments completed with no error.
- **SDR-only screen** (Gael's XZ3020): no external monitor was connected today, so it was simulated: `auto` turns XDR on
  when any screen can show EDR (the built-in panel), then every 0.25 s checks the window's own screen; on an SDR-only
  screen the final pass goes back to the plain SDR variant (no excess written; the present is a plain conversion into the
  RGBA16F drawable, which an SDR screen shows exactly like the RGBA8 one). `mcopt.shade.xdrTest=sdr|flip` fakes it; the
  log shows "XDR paused (this screen is SDR only ...)" / "XDR resumed". Run `sdr-flip` flipped it every 10 s through
  the perf route and the shots: 8 transitions, no error, no hitch tied to them (spin 93 fps, flight 101 fps, normal). If the game starts with only the external monitor (lid closed), `auto` finds no EDR screen and the layer is
  the plain RGBA8 one from the start.
- **Never breaks presenting**: the EDR layer is used only when its compose/present pipelines built; otherwise the layer
  stays RGBA8 and the Java side turns XDR off.

### 6.4 Final in-session prices (run `20261007-191014-sdr-r6`, final build, AC power, balanced, DRS off)

| Scene | On | Paused (M2.1 look) | On again | Price | Bible budget |
|---|---|---|---|---|---|
| Nether (wastes, 360 spin) | 4.66 ms (215 fps) | 4.07 ms (246 fps) | 4.66 ms (215 fps) | **+0.60 ms** | P ~0, Q +0.2 (assumed per-pixel lighting already paid) |
| End (main island) | 4.41 ms (227 fps) | 3.26 ms (307 fps) | 4.44 ms (225 fps) | **+1.17 ms** | "like the overworld minus clouds" (shadows ~0.7-1.0 + VL beams 0.4-0.5) |
| Overworld noon, XDR highlights on / SDR-screen mode / on | 11.98 ms | 11.61 ms | 11.77 ms | **~+0.25 ms** | +0.2-0.4 (P) |
| Aurora (only on aurora nights) | - | - | - | **0.48 ms if every pixel were aurora sky**; ~0.15-0.25 ms in a real night view (30-50 % of the screen is sky above the horizon) | P 0.06 / Q 0.12: **over** |
| Camera in lava / powder snow | - | - | - | ~0 (one noise tap per pixel, only while inside) | ~0 |

Shader microbenchmarks (`tools/shade/dimstime`, every pixel of 3024x1964 paying, minus an empty pass, no game running):
aurora 0.48 ms, End sky 0.58 ms, Nether fog 0.14 ms, camera media ~0 ms.

Three runs (r1, r4, r6) gave the same prices within 0.1 ms. CPU load was normal during the perf segments (median 128 %; the
peaks are the game's own start-up). Every price is a difference inside one game session, so it is solid even though the
absolute numbers move between runs (other agents' games and builds share the Mac).

In plain words: **the Nether look costs about half a millisecond, the End look a bit over one millisecond, XDR a
quarter of a millisecond.** Both dimensions stay far faster than the overworld (Nether ~215 fps, End ~225 fps vs ~85 fps
at noon with everything on, DRS off), so nobody will feel it. The Nether is over the bible's line because the bible
assumed its terrain was already lit per pixel; in M2.1 it still used vanilla's flat lightmap.

### 6.6 Distant Horizons sanity (instance "mcopt DEV-DH", run `20261007-192932-sdr-dh`)

- Overworld shots with DH (noon, forest, sunset, night torches, rain): normal, no change from M2.1.
- **Nether with DH had no haze at all**: DH pushes vanilla's fog away itself, and our fog density followed vanilla's
  (now huge) fog end, so it went to zero. Fixed (`59a6d0d`): the density uses vanilla's own Nether distance whatever
  other mods did. Checked in run `20261007-195108-sdr-dh2`: all six Nether shots with DH now look like the ones
  without it. Remaining: DH's far LODs in the Nether are not fogged (our fog leaves the pixels DH drew, which have no
  depth for us), so a few distant shapes show as dark unfogged silhouettes (`dh_nether_soul.jpg`). Fogging them needs
  DH's depth: FT1 work.
- **End with DH is broken, but not by this module**: a grey checkerboard / coloured noise over the whole frame. A baseline
  with the End look off (`-Dmcopt.shade.dims=false`, run `20261007-194816-sdr-dh-base`, i.e. M2.1's End) is just as
  broken (worse: coloured noise over the sky, the island olive-green), so it comes from the M2 base + Distant Horizons
  in the End (FT0 only tested the overworld). Confirmed with the delivered M2.1 jar itself + DH (run
  `20261007-200443-sdr-dh-m21`, `dh_end_island_BROKEN_m21.jpg`): identical breakage. Gael's M2/M2.1 instances have no
  DH, so nobody sees it today. A temporary "End look off with DH" guard was reverted for that reason.
  Owner: the DH/FT1 work (or the M3 integrator). Repro: `mcbench-run --direct --instance "mcopt DEV-DH" ... --jvm
  "-Dmcopt.shade=true -Dmcbench.dims=end -Dmcbench.standardShots=false" --scenes shots`.

### 6.5 How it looks (work/release/M3-skydims-screenshots/)

- **Nether**: each biome has its own air: orange glowing haze over the wastes and the lava sea, deep red in the crimson
  forest, teal in the warped forest, blue-cyan smoke in the soul sand valley, grey ash in the basalt deltas. Distance
  dissolves into coloured smoke instead of vanilla's flat fog wall; ceilings glow warm from the lava below. The crimson
  forest is the most monochrome (orange-red everywhere, it is mostly above the lava sea).
- **End**: pale lilac island under a violet "dead star" with real shadows (the pillars cast them), a deep indigo sky with
  sharp stars, a faint nebula and a few groups of light beams rising from the void near the horizon. Tuned today: the
  first cut had a wall of beams all around and bright cyan nebula clouds; now -0.4 EV, sparse low beams, fainter nebula.
- **Aurora** (forced on for the shots): soft green curtains with violet lower edges and a faint crimson top, drifting
  slowly; no banding (6 dithered layers smoothed by TAA).
- **Inside lava**: a glowing, churning orange with darker edges; **inside powder snow**: bright cold frost.


