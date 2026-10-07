# M2 integration guide (branch `m2`)

How the parallel M2 modules (water `m2-water`, god rays `m2-vl`, TAAU `m2-taau`, later clouds/AO) plug into the M2 base.
Everything here is behind `mcopt.shade=true`; with it off the backend is upstream's (no redirector installed, no format
adapter, pipelines not retained, every hook below is skipped by a `static final` test).

## 1. The frame (what runs where)

```
GameRenderer.render
  clear main (deferred clear on the main color texture)
  LevelRenderer.render HEAD ......... ShadeFrameUpdater.update: ShadeFrame (16) + ShadeLook (17) packed, sky LUT,
                                      ShadePost.beginWorld -> the HDR world phase starts
    shadow pass (M1, own encoders)
    "Sky" pass ...................... redirected into the HDR target (RGBA16Float, pre-exposed linear Rec.709)
    Distant Horizons passes ......... NOT redirected: "DH bridge" (see 5)
    "Main"/"Solid"/... passes ....... redirected: Sodium terrain, entities, particles, clouds, weather, outlines...
      translucent terrain (H6) ...... water module's scene copy goes here (see 3)
  render3dHud INVOKE ................ ShadePostMixin -> ShadePost.beforeHand:
                                        before-tonemap hooks (VL composite, ...) -> resolver (TAAU) -> bloom down chain
                                        -> histogram (bloom L2) -> bloom up chain -> exposure reduce -> FINAL into main RGBA8
    hand, screen effects ............ vanilla, into main RGBA8 (display-referred)
  FogRenderer.endFrame .............. fallback for beforeHand (frames without render3dHud); MetalFX restore; GUI
```

"Redirected" = `MetalHooks.Redirect.formats(...)`: every render pass whose single color attachment is the game's main
color texture (mip 0) draws into `ShadePost.sceneTexture()` instead, keeping the main depth, through each pipeline's
**RGBA16Float variant** (`MetalPipeline.variant`, built lazily, pre-warmed on the compile worker for world pipelines).

## 2. Data available to modules

| What | Where | Notes |
|---|---|---|
| ShadeFrame (M1, 1008 B) | `buffer(16)`, `constant ShadeFrame&` | frozen layout (shade_common.metal) |
| ShadeLook (M2 art layer, 1024 B) | `buffer(17)`, `constant ShadeLook&` | shade_look.metal / ShadeLook.java (offsets as constants) |
| ShadeGpu | `buffer(18)` | `exposure` = (applied this frame, target, previous frame's, log2); `scene.x` metered log2 L |
| ShadeResources (argument buffer) | `buffer(19)`, `constant ShadeResources&` | 0 milkyWay, 1 **prevScene**, 2 scene, 3 bloom, 4-7 free (dummy 1x1) |
| shadow map | `texture(16)` + `sampler(12)` | M1 |
| sky-view LUT | `texture(17)` + `sampler(13)` | M1 |
| HDR scene (this frame) | `ShadePost.sceneTexture()`, `Context.scene` | read it only outside the world passes (it is their attachment); format `ShadeConfig.HDR_FORMAT` (RG11B10F by default in performance/balanced: no alpha, no negatives) |
| HDR scene (last frame) | `ShadeResources.prevScene`, `Context.prevScene` | pre-exposed with `ShadeGpu.exposure.z`: rescale by `exposure.x / exposure.z`. After VL and every before-tonemap hook, before bloom. **SSR colour source.** |
| depth | `Context.depth` | the main Depth32Float, reversed Z (near 1, far 0, sky 0), GL row order; world only (the hand clears it later) |
| matrices | `ShadeFrame.viewProj/invViewProj/prevViewProj`, `ShadeLook.viewProjNoJitter/prevViewProjNoJitter` | camera-relative world; prev re-based on this frame's camera. `*NoJitter` = ShadeFrame's until TAAU fills them (TAAU module: write unjittered there) |

Row order: targets hold GL row order (row 0 = bottom of the view). A full-screen pass with `shade_fs_vs` gets
`uv = (pixel + 0.5) / size` and the NDC is `uv * 2 - 1` with no flip (`shadeWorldFromDepth` in shade_post.metal).

ShadeLook fields reserved for modules (written by the base, consumed by you; extend `ShadeLook.pack` for new values):
`vl` (x strength from the keyframes x look preset, y max distance 160, z HG g 0.7, w steps 12), `water`, `waterAbs`, `ssr`,
`taa`, `renderScale` (x scale, y mip bias, zw active size: base writes 1, 0, full size), `wet`, `wind`, `clouds0-2`,
`biomeA/B`, `heldLight`, `dimTint`, `flash`. Feature bits in `tier.y` (`SHADE_FEATURE_*`): HDR 1, BLOOM 2, AUTO_EXPOSURE 4,
PCSS 8, STARS 16, HAZE 32, LIGHT_V2 64; add yours from 128 up.

Useful MSL in the prelude (`ShadeMsl.prelude()` = shade_look + shade_common + shade_m2):
`shadeExposure(f, g)`, `shadeOut(radiance, f, ex)` (pre-exposed HDR with `MCOPT_HDR`, M1 tone curve without),
`shadeFinish2`, `shadeVanillaOut`, `shadeLdrToHdr`, `shadeDisplay` (the tone map), `shadeHazeTransmittance(p, f, L)` and
`shadeHazeInscatter(p, f, L, sunVis, T)` (E06 closed forms: **VL should scale its in-scattering with the same haze
coefficients `L.fogR`, `L.fogM` so shafts and haze agree**), `shadeLit2`, `shadeShadow2` (PCSS-lite), `shadeHG`,
`shadeSkyLookup`, `shadeNoise` (IGN), `shadeHash*`.

## 3. Water (`m2-water`, merged into m2): where water shading runs

**Merged** (branch `m2`, merge of `m2-water` + HDR wiring; module doc: `WATER.md`). What is wired:

- Water is drawn by `sodium:pipeline/translucent_terrain` inside the redirected world pass, so its fragment writes the HDR
  target. Its HDR variant (format adapter: `#define MCOPT_HDR`) assembles `ShadeMsl.prelude()` + `ShadeWater.prelude()`
  (SHADE_WATER/SSR/... defines + water.metal) + the stage. The water branch fills `ShadeWaterIn` with `shadeLit2(..., sl)`,
  `exposure = ex` (`ShadeGpu.exposure.x`), `prevExposure = ShadeGpu.exposure.z`, `fog = shadeLdrToHdr(fogColor, sl) / ex`,
  and the result goes through `shadeFogAir2` + `shadeFinish2` like every other texel (water.metal stays in linear
  radiance before exposure; it never tonemaps). Bloom, auto-exposure, haze (final pass, from the water's depth) and the
  tonemap then treat water like any other pixel; the sun glint is clamped at 60x the sun colour, a strong but finite
  bloom source.
- Scene copy (bible H6): `MetalRenderPass.setPipeline` at the first translucent terrain pipeline of a main pass ends the
  encoder, `mc_water_capture` blits colour attachment 0 (the HDR target, in its own format: RG11B10F/RGBA16F,
  pre-exposed linear) and depth into private ShaderRead copies, `mc_render_reopen` reopens the pass and
  `restoreAfterReopen` rebinds (pipeline state through `p.state(formats, hasDepth)`, so the HDR variant).
  Right after the blit `mc_water_capture` renders the **coarse depth** (`shade_water_hz_fs`: per 2x2 block the nearest
  reversed-Z, half-size R32F), which the SSR march steps read (bisection and thickness test stay full resolution).
  Bindings (fragment stage, by `ShadeWater.bind` from `ShadeBindings.bind/rebind`): `texture(18)` scene copy,
  `texture(19)` depth copy, `texture(20)` baked waves, `texture(21)` coarse depth, `buffer(23)` ShadeWaterParams
  (`state.x` copies valid this frame, `state.w` waves baked this frame). Far terrain's own pipelines also use 20/21 and
  rebind shade (and with it these) after drawing.
- **Wave bake** (`mcopt.shade.waterBake`, default on): before the world passes (`ShadeFrameUpdater`, after the sky LUT)
  `ShadeWater.bake` renders the wave field (`shade_water_bake_fs`: the 7 module waves + 7 off-axis extras, wave vectors on
  a 64-block lattice so it tiles) into a 1024^2 RGBA16F texture and blits its mips; the fragment samples it once with
  anisotropic trilinear filtering (`gradient2d` from the fragment's own `dfdx/dfdy` of the position) instead of 7
  analytic waves per pixel, and the mips replace the footprint LOD (less shimmer far away). Caustics keep their own
  analytic short waves.
- **Reflections read last frame's finished HDR world** (`ShadeResources.prevScene` via `constant ShadeResources& sr
  [[buffer(19)]]` in the HDR translucent fragment; `mcopt.shade.ssrPrev`, default on): SSR hits are found in this
  frame's depth copy, their colour is fetched from `prevScene` at the hit's reprojection (`invViewProj` -> `prevViewProj`,
  divided by `exposure.z`), so reflections contain clouds, other water/glass, rain and (later) VL; and the sky reflection
  reads the rendered sky where the reflected direction (calm normal) lands on screen on a pixel the depth copy holds as sky
  (sun disc and aureole, stars, Milky Way, clouds, Distant Horizons' LODs, which the depth copy doesn't hold), faded into
  the atmosphere LUT toward the screen edges. `ssrPrev=false` = colour from the opaque scene copy + LUT sky only.
- Underwater: `ShadeWaterIn.fog` carries the game's fog colour as HDR radiance (TIR fallback); the vanilla underwater fog
  then applies through `shadeFinish2`.
- Debug views: `mcopt.shade.debug` 5 = SSR hits (r) / Fresnel (g), 6 = refracted path / depth, **7** = footprint / flow /
  distance (was 4 on m2-water; 4 is the base's log-luminance view).
- For TAAU (`m2-taau` merge): define `SHADE_WATER_SUBRECT` in `ShadeWater.prelude()` (ShadeFrame.screen = live render
  size), copy only the live sub-rect in `mc_water_capture`, pass the frame index into the SSR jitter, and mark water
  reactive (WATER.md "Integration with TAAU"). `prevScene` is sampled by normalized uv, so a resolver whose history is a
  different size still works if `ShadeResources.prevScene` points at it (then rescale with its exposure).
- For FT2 (DH): `shadeWaterSceneW` (full-res) and `shadeWaterCoarseW` (march) are the only depth reads (the merged scene
  distance goes there, and into the coarse pass); `water.metal` is a plain include for DH's transparent override.
  Today, with DH on, far LODs already appear in water reflections through the rendered-sky path (they are sky in the
  depth copy, visible in `prevScene`).

## 4. Depth-based post (`m2-vl` merged; GTAO, atmosphere composite): before the tone map

Register once (e.g. from your module's init after `ShadePost.active()`):

```java
ShadePost.addBeforeTonemap(100, c -> {               // lower order first; VL = 100, AO = 50, clouds = 80 suggested
    // c.enc(): native Enc*, c.scene(), c.prevScene(), c.depth(), c.width() x c.height(), c.frame(), c.look()
    ShadeRender.pass(c.enc(), myHalfResPso, vlHalf, ShadeRender.DONT_CARE, new long[] {c.depth()}, params, "vl march");
    ShadeRender.pass(c.enc(), myCompositePso, c.scene(), ShadeRender.LOAD, new long[] {vlHalf, c.depth()}, params, "vl composite");
});
```

- The hook runs at render3dHud (world depth intact) inside ShadePost.beforeHand, ordered with bloom/tone map. A composite
  adds **pre-exposed radiance** into the HDR scene (multiply your in-scattering by `ShadeGpu.exposure.x`), fixed-function
  blend (`BLEND_ADD`, or One/SourceAlpha for scene*T + light; the RG11B10F target has no destination alpha). Bloom and the
  fog glow then pick it up.
- **Cheaper: fold into the final pass** (what VL does): `ShadePost.setVolumetric(lightT, dist, maxDist)` from the hook, with
  `lightT` = rgb light before exposure + a = transmittance, `dist` = same size, a = distance per texel (blocks, 1e4 sky),
  any resolution. `shade_final` upsamples it depth-aware and applies `c = (c*T_haze + S_haze)*a + rgb*exposure` before
  bloom's mix (and adds 1 − a to the fog glow). No full-resolution read-modify-write of the HDR target (VL: −0.4 ms at
  native); bloom, the exposure meter and `prevScene` don't see that light. One volumetric source per frame (reset in
  beginWorld); a second module would have to combine into the same textures.
- Without HDR (`mcopt.shade.hdr=false`) the hooks don't run; VL keeps its M1 path (render3dHud mixin, LDR composite).
- Sky pixels: depth == 0. Distant Horizons pixels are not in the main depth (they look like sky there) until FT2 adds a
  merged scene-distance input; treat depth 0 as "far" and cap the march by `L.vl.y`.

**Volumetric light as merged** (VL.md for the module itself): `ShadeVl` registers `addBeforeTonemap(100)` on its first
render3dHud with `ShadePost.active()`; per frame `mc_vl_render(c.enc(), state, c.scene(), c.depth(), ..., flags 1|4|16)`
encodes canopy (compute; with VL_HDR it writes `ShadeGpu.scene.y` = canopy factor 0..1, smoothed) → march (VL res, MRT
light+dist and R8 sky mask) → `vl_temporal_fold` (MRT history + lightT), then `mc_vl_outputs` → `ShadePost.setVolumetric`.
`mcopt.shade.vl.fold=false` (or any `vl.debug` view) uses `vl_composite_hdr` instead (blend One/SourceAlpha into the HDR
target). Its open-air medium reads the base's E06 numbers through `ShadeLook.hazeSigmaLuma/mistSigmaBlock/mistHeight` and
`SEA/HAZE_BASE/HAZE_H/MIST_BASE` (now public), its strength `ShadeLook.vlFactor()`. TAAU (`m2-taau`) still to do on VL:
NoJitter matrices in `vlDirection`/`vl_temporal`, VL size from `renderScale.zw`, and the fold samples by uv so it already
works at output resolution.

## 5. Distant Horizons (FT0) under the HDR frame

- `MetalHooks` redirectors are a **chain** now: `addRedirector(order, r)` / `removeRedirector(r)`; the first non-null
  Redirect wins, lower order asks first. ShadePost registers at order 0. FT2's DH redirector should use a negative order
  (e.g. -100) to take `distantHorizons:TerrainRenderer` before shade looks at it. `setRedirector` (pack runtime) still
  replaces the whole chain.
- Today ShadePost leaves `distantHorizons:*` passes alone and bridges them: before the first one it tone-maps the HDR
  world into main RGBA8 (`shade_bridge_encode`), DH draws/copies/fades into main as it does without shading, and before
  the next world pass the pixels DH changed come back into HDR through the inverse tone map (`shade_bridge_decode`,
  programmable blending; unchanged pixels keep their exact HDR value). So DH terrain is visible, fogged by DH, and gets
  bloom/exposure/tone map with the rest. `mcopt.shade.dhBridge=false` turns it off (DH then lands only in main and is
  overwritten by the final pass: debugging only).
- Border fog (E06) switches itself off when DH moves vanilla's fog away (`fogMisc.z = 0`), so vanilla chunks blend into LODs.

## 6. TAAU / dynamic resolution (`m2-taau`, merged)

**Merged** (merge `095ce42` + follow-ups; module doc `TAAU.md`). The frame with TAAU on (`mcopt.shade.taa`, default true):

```
Camera.extractRenderState TAIL .... ShadeTaa.jitter: DRS picks the scale, Halton(2,3) jitter into the camera projection
GameRenderer.render, 1st device call  ShadeTaa.beginWorld: the world target (allocated once at scaleMax x output) stands
                                    in for mainRenderTarget; MetalBridge.setSubRect -> live sub-rect (0,0,w,h)
LevelRenderer.render HEAD ......... ShadeFrameUpdater (ShadeFrame.screen = w x h, jitter, mip scale) -> ShadePost.beginWorld
                                    (HDR targets at the allocation size; prevScene = the TAA history)
  world passes .................... redirected into the HDR target AND clipped to the sub-rect (MetalEncoder: a redirected
                                    pass whose original attachments are the sub-rect textures gets the same viewport)
before render3dHud ................ ShadePost.beforeHand: hooks (VL) on the sub-rect -> ShadeTaa.resolveHdr (HDR world +
                                    history -> output-size history, exposure-rescaled on the GPU) -> bloom (L0 from the
                                    internal scene's sub-rect) -> exposure -> FINAL: history -> sharpen -> haze/VL/bloom/
                                    grade/tonemap -> the game's REAL main target (ShadeTaa.outputTarget)
render3dHud HEAD .................. ShadeTaaGameRendererMixin gives mainRenderTarget back (no second resolve: swapped()
                                    is false after resolveHdr); hand, screen effects and GUI at native resolution
```

Rules for modules under TAAU:
- **Sizes**: `Context.width/height` = the live render size (sub-rect), `Context.texWidth/texHeight` = the textures'
  allocated size. Size your own targets from the texture size (they then never reallocate when the DRS moves the
  scale) and map them onto the live sub-rect (VL: `VlParams.size.zw` = live size, its VL-resolution targets always
  cover exactly the live area). `ShadePost.liveWidth()/liveHeight()` give the same outside a hook.
- **Reading the internal scene/depth by uv**: multiply uv by `live / tex` (the bloom first level does this,
  `shade_bloom_down0` params). Reading by pixel: the live sub-rect is `(0,0)-(w,h)`; `ShadeFrame.screen.xy` = live size.
- **Matrices**: `ShadeFrame.proj/viewProj/invViewProj/prevViewProj` contain the jitter, which is exactly right for
  reconstructing positions from this frame's depth (the depth was rasterized with it). `ShadeLook.viewProjNoJitter /
  prevViewProjNoJitter` (now filled) are for passes that want a stable raster. `ShadeLook.renderScale` = (scale,
  mip bias log2, live w, live h).
- **Last frame's image** (`ShadeResources.prevScene`, SSR colour): the TAA history (RGBA16F, output size, whole texture
  = whole view, pre-exposed with `ShadeGpu.exposure.z`): sample by normalized uv as before.
- **Per-frame noise**: with TAA, vary noise per frame (`ShadeFrame.flags2.x`): the resolve integrates it (water's SSR
  step jitter does this under `SHADE_WATER_TAA`).
- The generic `ShadePost.setResolver` is still there for other upscalers (it is skipped while TAAU drives the frame).
- **Distant Horizons**: its passes draw into the world target (main to DH) and are clipped to the sub-rect like the game's;
  its own targets follow the constant allocation size, so a scale change never makes DH reallocate. The DH bridge
  encode/decode passes cover the whole allocation (correct, a little wasteful). See M2_STATUS for the DEV-DH run.

## 7. Adding a new full-screen pass

```java
long lib = ShadeRender.library(ShadeRender.prelude() + ShadeRender.postSource() + myMsl);   // prelude has every struct
long pso = ShadeRender.pipeline(lib, "shade_fs_vs", "my_fragment", ShadeRender.RGBA16F, ShadeRender.BLEND_NONE);
long tex = ShadeRender.texture(ShadeRender.RGBA16F, w, h, /*lossy*/ true, "mcopt shade: my target");
ShadeRender.pass(enc, pso, tex, ShadeRender.DONT_CARE, new long[] {input0, input1}, paramsOrNull, "label");
```

- Inputs at `texture(0..)`, params (<= 4 KB) at `buffer(0)`, globals at buffers 16-19, shadow map/LUT at textures 16/17.
- Fragment passes into private RenderTarget|ShaderRead targets (compressible); compute (`ShadeRender.dispatch`) only for
  reductions. Lossy compression works for RGBA16F/RGBA8/R16F/RG16F/R8, **not RG11B10F** (Metal aborts; mcshade.m now
  silently keeps RG11B10F lossless).
- A pass ends the backend's open render encoder; the backend reopens its own for its next pass (call passes from hooks, not
  in the middle of the game's render pass).
- Release textures with `MetalBridge.release` only after the frames in flight are done (ShadePost retires them 4 frames).
- New shade MSL resources: add them to `tools/shade/ShadeMslDump.java` + `validate.sh`.

## 8. Flags (system properties or `config/mcopt.properties`; read once at startup)

| Flag | Default | |
|---|---|---|
| `mcopt.shade` | false | everything below needs it |
| `mcopt.shade.hdr` | true | HDR world target + post chain (false: M1's in-shader tone curve, no bloom/auto-exposure) |
| `mcopt.shade.hdrFormat` | auto | auto = `rg11b10f` (half the bandwidth, no alpha) for performance/balanced, `rgba16f` for quality/ultra |
| `mcopt.shade.bloom` | true | natural bloom + fog glow |
| `mcopt.shade.exposure` | auto | `fixed` = the time-of-day reference only |
| `mcopt.shade.ev` | 0 | exposure compensation, EV (-4..4) |
| `mcopt.shade.pcss` | true | PCSS-lite near-cascade shadows |
| `mcopt.shade.stars` | true | procedural sun disc, moon phases, stars, Milky Way |
| `mcopt.shade.haze` | true | height haze, mist, border fog |
| `mcopt.shade.light2` | true | lighting model v2 |
| `mcopt.shade.look` | epic | epic / storybook / natural |
| `mcopt.shade.preset` | **balanced** | performance / balanced / quality / ultra (`Shade.TIER`, parsed once, read by every module): see the preset table below |
| `mcopt.shade.dhBridge` | true | Distant Horizons through the HDR frame |
| `mcopt.shade.sunTilt` | 30 | sun path tilt (degrees) |
| `mcopt.shade.log` | 0 | every N s: elevation, exposure ref/gpu, metered log2, grade values |
| `mcopt.shade.debug` | 0 | 1 shadows, 2 normals, 3 lighting only, 4 log luminance (final pass), 5 water SSR/Fresnel, 6 water path/depth, 7 water footprint/flow |
| `mcopt.shade.water` | true | water module (false: M1's stand-in water, byte-identical shaders) |
| `mcopt.shade.ssr` / `.ssrQuad` / `.ssrSteps` / `.ssrDist` | true / preset / preset / 512 | screen-space reflections (quad-shared march except ultra; steps 16/24/24/32) |
| `mcopt.shade.ssrPrev` | true | reflections read last frame's HDR world (clouds, sky, LODs in reflections) |
| `mcopt.shade.waterBake` | true | waves baked once per frame into a mipmapped texture (false: analytic per pixel) |
| `mcopt.shade.waterWaves` / `.caustics` / `.waterFoam` / `.waterReflect` / `.glossy` | 1 / true / true / 1 / false | see WATER.md |
| `mcopt.shade.vl` | true | volumetric light (god rays, canopy mist, crepuscular streaks, underwater shafts) |
| `mcopt.shade.vl.fold` | true | blend VL in the final pass (false: composite pass before bloom) |
| `mcopt.shade.vl.scale` / `.steps` / `.ssTaps` / `.dist` | preset | P 0.25/10/6/160, B 0.33/12/8/160, Q 0.5/16/10/224, U 0.5/24/12/224 |
| `mcopt.shade.vl.strength` / `.mist` / `.moon` / `.extinction` / `.underwater` | 1 / 1 / 1 / true / true | see VL.md |
| `mcopt.shade.vl.debug` / `.log` / `.gpuTimer` | 0 / false / false | 1 light, 2 distance, 3 mist T; log line every 2 s; per-pass GPU ms |
| `mcopt.shade.taa` | true | TAA / TAAU + dynamic resolution (false: native, unjittered, as before the merge) |
| `mcopt.shade.scale` / `.scaleMin` / `.scaleMax` | preset | render scale start / DRS range (world targets allocated at scaleMax; floor 0.60) |
| `mcopt.shade.drs` / `.drsFps` / `.drsLog` | true / preset / false | dynamic resolution, the frame rate it holds, a log line per change |
| `mcopt.shade.taaMode` / `.sharpen` / `.mipBias` / `.taaLossy` / `.taaDebug` | preset / by scale / true / false / 0 | see TAAU.md |
| `mcopt.shade.shadowRes` | preset | 2048 (3072 on ultra) |

Presets (`mcopt.shade.preset`; any flag above still overrides its part):

| Preset | Render scale (DRS range, holds) | HDR target | Bloom | VL (scale of the world target, steps, screen taps) | SSR | Shadows |
|---|---|---|---|---|---|---|
| performance | 0.67 (0.60-0.75, 120 fps) | RG11B10F | 6 levels | 0.25, 10, 6 | quad, 16 steps | 2048 |
| **balanced** (default) | 0.80 (0.67-0.90, 120 fps) | RG11B10F | 6 levels | 0.33, 12, 8 | quad, 24 steps | 2048 |
| quality | 1.00 (0.85-1.00, 80 fps) | RGBA16F | 7 levels | 0.5, 16, 10 (224 blocks) | quad, 24 steps | 2048 |
| ultra | 1.00 (0.90-1.00, 60 fps) | RGBA16F | 7 levels | 0.5, 24, 12 (224 blocks) | per pixel, 32 steps | 3072 |

## 9. Merge notes

- `ShadeMsl.prelude()` grew (shade_look + shade_common + shade_m2): module MSL that prepended `shade_common.metal` alone
  should use `ShadeRender.prelude()`.
- `MetalRenderPass` now calls `p.state(formats, hasDepth)` instead of `hasDepth ? p.withDepth : p.withoutDepth`; a module
  that sets pipelines itself (water's reopen path) must use the same call, or the HDR pass gets an RGBA8 PSO (Metal
  validation error / garbage).
- `MetalTerrain.drawPulled` uses `p.pulledState(encoder.passFormats)`.
- `ShadeBindings` slots are 2048 B (ShadeFrame at 0, ShadeLook at 1024).
