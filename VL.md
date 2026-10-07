# Volumetric light / god rays (M2 module, branch `m2-vl`)

Shader bible E07: sun shafts through the M1 sun shadow map, crepuscular streaks toward the sun, caustic light shafts
under water, faint moon shafts at night. Self-contained module on top of `m1`, integration-ready for the HDR base (`m2`).
All code is original (techniques from public papers, see the header of `vl.metal`); no shader pack code, so no NOTICE
entry is needed. New files are Apache-2.0.

## 1. Files

| File | What |
|---|---|
| `metal/src/main/resources/mcopt/shade/vl.metal` | All MSL: `VlParams` (256 B), canopy kernel, march, temporal, composite (LDR and HDR variants via function constant 0 `VL_HDR`) |
| `metal/src/main/native/mcvl.m` | Pipelines, VL-resolution targets (private RenderTarget\|ShaderRead RGBA16F, compressible), per-frame encoding |
| `metal/src/main/native/mcshade.m` | + `mc_shade_bind_render` / `mc_shade_bind_compute`: M1 globals (ShadeFrame 16, shadow map 16 + compare sampler 12, LUT 17) on our own encoders |
| `metal/src/main/java/mcopt/metal/shade/vl/ShadeVl.java` | CPU side: medium, light colour, strength, canopy smoothing factors, water surface; packs `VlParams`; calls native |
| `.../vl/VlConfig.java` | Flags (below) |
| `.../vl/VlNative.java` | JNI-free FFM bindings to `mc_vl_*` |
| `.../vl/mixin/VlGameRendererMixin.java`, `VlMixinPlugin.java`, `resources/mcopt-metal-shade-vl.mixins.json` | Hook H7 on m1: `GameRenderer.renderLevel` INVOKE `render3dHud` (level drawn, main depth intact, hand not yet drawn). Applies only when `VlConfig.ENABLED` |
| `tools/shade/vlcheck.m` (+ `validate.sh`, `ShadeMslDump.java`) | Compiles `vl.metal` like mcvl.m and builds every pipeline: march, temporal, LDR composite (RGBA8), HDR composite (RGBA16F, One/SourceAlpha), canopy kernel |

## 2. Flags (system properties or `config/mcopt.properties`; read once)

| Flag | Default | |
|---|---|---|
| `mcopt.shade.vl` | true (with `mcopt.shade=true`) | god rays on. With `mcopt.shade=false` nothing of this module loads (mixin plugin) |
| `mcopt.shade.preset` | balanced | performance / balanced / quality / ultra: steps 12 / 14 / 20 / 28, screen taps 8 / 8 / 12 / 16, distance 160 / 160 / 224 / 224 |
| `mcopt.shade.vl.scale` | 0.5 | march resolution relative to the main target (0.25 = quarter res) |
| `mcopt.shade.vl.steps`, `.ssTaps`, `.dist` | preset | override the preset |
| `mcopt.shade.vl.strength` | 1 | overall brightness |
| `mcopt.shade.vl.mist` | 1 | density of the canopy mist (what shows the shafts under trees / overhangs) |
| `mcopt.shade.vl.extinction` | true | the canopy mist also dims what is behind it (false: light only added) |
| `mcopt.shade.vl.moon` | 1 | moon shafts relative to sun shafts |
| `mcopt.shade.vl.underwater` | true | caustic shafts under water |
| `mcopt.shade.vl.temporal` | true | temporal accumulation (false: raw march, for comparisons) |
| `mcopt.shade.vl.canopy` | -1 | force the canopy factor 0..1 (debug; negative = measured on the GPU) |
| `mcopt.shade.vl.lossy` | false | lossy-compressed VL targets |
| `mcopt.shade.vl.debug` | 0 | 1 the light alone, 2 the marched distance, 3 the canopy mist's transmittance |
| `mcopt.shade.vl.log` | false | a line every 2 s: canopy (smoothed, raw shadowed fraction), haze, mist, canopy mist, strength, g, humidity, sun elevation |

## 3. Frame (m1)

```
render3dHud INVOKE (VlGameRendererMixin -> ShadeVl.beforeHand), on the frame's command buffer (mc_frame_cmd):
  vl_canopy    compute, 1 threadgroup x 64   far cascade at 4 rings x 16 points (r 3/7/11/15, eye+1.5/+5): shadowed
                                              fraction -> smoothstep(0.12, 0.5) -> smoothed (rise 1.5 s, fall 4 s)
                                              into a 16-byte state buffer (GPU only, no readback)
  vl_march     VL res (0.5 x main)            per pixel: shadow-map march of the medium to the scene depth (t = D((i+xi)/N)^2,
                                              IGN + R1 dither), near cascade (alpha-tested leaves) where it covers, else far;
                                              + screen-space march toward the sun on the depth buffer (crepuscular streaks);
                                              under water: caustic-patterned shafts through the water column instead.
                                              rgb = in-scattered radiance (linear, pre-exposure), a = marched distance
  vl_temporal  VL res                         3x3 depth-aware filter + reprojection (ShadeFrame.prevViewProj, camera delta),
                                              neighbourhood clamp, distance-mismatch rejection; history ping-pong (0.9)
  vl_composite full res, onto main RGBA8      depth-aware 2x2 bilateral upsample (thin foliage against the sky: nearest
               (programmable blending)        depth sample scaled by the marched length -> no halos); canopy mist
                                              transmittance T analytic at full res; result added BEFORE M1's output curve:
                                              e = curve^-1(dst); out = dst + curve(e*T + light) - curve(e)  (no light and
                                              T = 1 -> the exact same pixel); +-1 LSB triangular dither
```

### The look (what makes it pretty)

- **Medium** (1/block, camera-relative heights): thin haze everywhere (sea + 16, H 48), sea-level ground mist (thick at
  dawn, lighter at dusk), and the **canopy mist**: a camera-local layer `s * e^(-t/16) * e^(-max(y-4,0)/8)` scaled by the
  canopy factor, `s` 0.008 (noon) .. 0.03 (golden hour) x humidity x rain. The open-air layers are gated to 0.5, the
  canopy mist to 1.25: shafts are strong under trees, overhangs and cave mouths, subtle over open plains (Complementary's
  "scene-aware" behaviour, our own measure).
- The canopy mist also **dims the scene behind it** toward its sky-lit colour (0.2 x ambient sky + a little warm sun),
  with half its scattering coefficient (art: a light veil, bright shafts). Analytic, full resolution: crisp edges.
- **Phase**: HG forward lobe (g 0.5 at noon .. 0.72 at golden hour) + 6% back lobe + 20% isotropic (shafts stay visible
  from the side).
- **Light colour**: ShadeFrame's sun colour, saturated x(1 + 0.55 golden) toward the low sun (sunset orange shafts);
  moonlight at night x2 x `vl.moon` of the (dim) moon colour; fades through the sun/moon handover.
- **Golden hour** = sun elevation below ~30 degrees (`1 - smoothstep(0.05, 0.55, sunY)`; the default 30-degree sun-path
  tilt caps noon at sunY 0.87).
- **Crepuscular streaks**: 8 taps toward the sun's screen position count open sky (depth 0) with decaying weights;
  `pow(saturate(mu), 6)`, 0.1 + 0.15 golden, faded by how far the sun is off screen and by the eye's sky light. Clouds
  write depth, so rays fan out of cloud gaps at sunset too.
- **Under water**: extinction (0.34, 0.075, 0.058)/block, scattering 0.045; light reaches each sample through the water
  above it, patterned by two-scale procedural caustics (broad ~13-block cells x a ~5-block web, mean 1, gaps 0.1) that
  develop with depth (none right under the surface) -> curtains converging toward the sun; N + 4 steps, D <= 48.
- **No leaks**: below the far cascade's depth range = shadowed (underground); caves get no sun anyway (strength x eye
  sky light); the game's own fogs (lava, powder snow, blindness, darkness) switch VL off; Nether/End: off.

## 4. Measurements (MacBook Pro 14" M4, 10-core GPU, 3024x1964 fullscreen, harness `mcopt DEV`, Sodium)

See section 7 (filled from the A/B runs in this branch's last commit).

## 5. Moving to the HDR pipeline (m2)

The passes are the same; only the hook point, the exposure source and the blend change. `vl.metal` already contains the
HDR composite (`VL_HDR = true`): it returns `float4(light, T)` and reads the exposure from `ShadeGpu.exposure.x`
(`buffer(18)`, byte 176, a function-constant-gated argument, so the LDR variant doesn't need M2 structs). `mcvl.m`
builds it with blending `src * One + dst * SourceAlpha` (scene x T + light) when `mc_vl_render` gets flag 4.

Steps on m2:

1. **Hook**: drop `VlGameRendererMixin` (and its mixins json entry); register instead
   `ShadePost.addBeforeTonemap(100, c -> ShadeVl.render(c))` from ShadeVl's first use after `ShadePost.active()`. In the
   hook: `color = c.scene()` (RGBA16F HDR target), `depth = c.depth()`, size `c.width() x c.height()` (internal res with
   TAAU), encoder `c.enc()` (already a native Enc*: pass it where `MetalBridge.enc(encoder)` is used now) and
   `flags |= 4`. Keep the m1 mixin path for `mcopt.shade.hdr=false`.
2. **Bindings**: mcvl.m's `pass()` must also bind ShadeGpu at `buffer(18)` for the composite (on m2 `mc_shade_bind_render`
   binds slots 16-19 already: check, else `[r setFragmentBuffer:gpu offset:0 atIndex:18]`).
3. **Prelude**: `VlConfig.source()` -> `ShadeRender.prelude() + vl.metal` (shade_common alone is no longer the prelude).
   `ShadeFrame` (16) is unchanged.
4. **Haze coefficients**: take the open-air layers from ShadeLook so shafts and E06 haze agree: in `ShadeVl.pack` use
   `L.fogR` / `L.fogM` (and their heights) for `medium.x/y`, `haze.x/y`, `medium.z/w` instead of the local constants;
   the art gates (`gate.x/y`) and the canopy mist stay ours. `ShadeLook.vl` (x strength from the keyframes x look
   preset, y distance, z g, w steps) replaces `VlConfig.STRENGTH`/`DIST`/`g`/`STEPS` when present.
5. **Canopy factor**: also write it to `ShadeGpu.scene.y` (reserved for it) from `vl_canopy` (bind ShadeGpu as
   `device` there) so other passes (E06 mist, bloom fog) can use it.
6. **Rows**: m2's targets are GL row order like m1's main target; vl.metal maps pixel -> NDC with no flip already.
7. **TAAU** (`m2-taau`): the march's temporal pass reprojects with `ShadeFrame.prevViewProj`; with jitter in the
   projection use `ShadeLook.viewProjNoJitter/prevViewProjNoJitter` (or subtract `ShadeFrame.jitter`) in `vlDirection` and
   `vl_temporal`, otherwise the history wobbles by the jitter. With DRS, restrict the composite to `renderScale.zw`
   (viewport sub-rect) and size the VL targets from the active size, not the allocation.
8. **Distant Horizons**: DH pixels are depth 0 in the main depth (they look like sky): VL marches them to the max
   distance, which matches a far scene. When FT2 provides the merged scene distance, feed it to march/composite as
   `vlDistance` instead of the main depth.
9. **Bloom** then picks up the bright shafts automatically (they're in the HDR scene before the bloom chain).

## 6. Testing

```sh
W=".../work"; JAR=$W/wt/m2-vl/metal/build/libs/metal-0.2.0-alpha.3.jar
SOD="$HOME/Library/Application Support/PrismLauncher/instances/mcopt Metal 26.3/minecraft/mods/sodium-fabric-0.9.3-alpha.1+mc26.3.jar"
# god-ray scenes only (fast look-dev), with the VL log
$W/bin/mcbench-run --instance "mcopt DEV" --mods "$JAR" --mods "$SOD" --scenes shots \
  --jvm "-Dmcopt.shade=true -Dmcbench.godRays=true -Dmcbench.standardShots=false -Dmcopt.shade.vl.log=true" --label vl
# the light alone: add -Dmcopt.shade.vl.debug=1 ; without VL: -Dmcopt.shade.vl=false
```

mcbench god-ray scenes (`-Dmcbench.godRays=true`, mcbench `GodRayShots.java`, poses found in the loaded world):
`vl_forest_morning` (under the canopy, sun east, time 250), `vl_forest_side` (same spot looking south, 600),
`vl_forest_evening` (forest edge, sun west behind the trees, 11750), `vl_overhang` (under a natural roof / cave mouth
looking out into the low sun), `vl_shelter` (inside a village house looking out of an east/west door; skipped when none),
`vl_underwater` (7 blocks down looking up toward the sun, 2600).

`tools/shade/validate.sh` (needs a built metal module) checks every VL pipeline (`vlcheck`).
