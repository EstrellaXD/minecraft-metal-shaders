# M3 sky, dimensions and XDR (branch `m3-skydims`, from `m2.1`)

What this branch adds (bible E04 aurora, E23 XDR, E24 Nether, E25 End, E26 camera media), how it plugs into the M2 base,
and what an integrator merging it with the other M3 branches has to watch. Everything is behind `mcopt.shade=true` plus
its own flags; with `mcopt.shade=false` nothing here loads (the mixin plugin, the registry entry and the MetalSurface hook
all test `Shade.ENABLED`, a `static final false`).

## Status (2026-10-07 18:15)

Resumed after the 15:06 pause (account switch). Done since: the WIP terrain refactor is complete (the WIP commit already held
the finished change: `shadeLit2` is called once for every dimension and `shadeLitDimsExtra` adds the Nether/End light, so
the overworld path's code size is unchanged); build + `validate.sh` ALL OK. New: XDR pauses its highlights while the
window's screen is SDR only (an external 1080p monitor), dev switch `mcopt.shade.xdrTest=sdr|flip`; live
`mcopt.shade.dims.pause` + mcbench DimShots in-session A/B phases (`nether/nether_off/nether2`, `end/end_off/end2`,
`xdr/xdr_off/xdr2`); XDR dumps capped at 24 (`MCOPT_XDR_DUMP_MAX`); `tools/shade/xdrdump/xdrdump.py` reads them.
Next: the in-game run with shots + A/B + XDR dump (label `sdr-r1`), judge the looks, fix, fill section 6.

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

Why the Nether cost +0.5 ms: Sodium stops drawing chunks where vanilla's fog ends ("fog occlusion", ~96 blocks in the
Nether). Our own fog replaced vanilla's, so Sodium drew the whole render distance (~7x the area). Fixed in `5d6b17f`:
Sodium's cut is kept at 1.3x (performance) / 1.7x (balanced) vanilla's fog end and our border fog closes the view there;
quality/ultra still draw everything (`mcopt.shade.netherView` overrides). The new price is in 6.2.

The End's +1.1 ms buys what the overworld already pays for: a shadow-casting key light (cascades, far one cached), the
sky (nebula, stars, beams) and per-pixel light. The End frame (4.2 ms, ~240 fps) stays far below the overworld's (~11.5 ms
at noon with all M2 effects), and below the bible's End budget ("like the overworld minus clouds").

### 6.2 (pending) after the fixes

