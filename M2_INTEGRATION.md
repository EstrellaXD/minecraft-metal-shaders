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

## 4. Depth-based post (`m2-vl`, GTAO, atmosphere composite): before the tone map

Register once (e.g. from your module's init after `ShadePost.active()`):

```java
ShadePost.addBeforeTonemap(100, c -> {               // lower order first; VL = 100, AO = 50, clouds = 80 suggested
    // c.enc(): native Enc*, c.scene(), c.prevScene(), c.depth(), c.width() x c.height(), c.frame(), c.look()
    ShadeRender.pass(c.enc(), myHalfResPso, vlHalf, ShadeRender.DONT_CARE, new long[] {c.depth()}, params, "vl march");
    ShadeRender.pass(c.enc(), myCompositePso, c.scene(), ShadeRender.LOAD, new long[] {vlHalf, c.depth()}, params, "vl composite");
});
```

- This replaces `VlGameRendererMixin` (render3dHud wrap) on m2: the hook runs at the same point (render3dHud, world depth
  intact), but inside ShadePost so it is ordered with bloom/tone map. The composite adds **pre-exposed radiance** into the
  HDR scene (multiply your in-scattering by `ShadeGpu.exposure.x`), additive blend (`BLEND_ADD`) or programmable blending.
  Bloom and the fog glow then pick the shafts up automatically.
- Without HDR (`mcopt.shade.hdr=false`) the hooks don't run; keep your old path for that case if you want M1-style output.
- Sky pixels: depth == 0. Distant Horizons pixels are not in the main depth (they look like sky there) until FT2 adds a
  merged scene-distance input; treat depth 0 as "far" and cap the march by `L.vl.y`.

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

## 6. TAAU / dynamic resolution (`m2-taau`)

- Install a resolver: `ShadePost.setResolver(c -> { ...; return outputHdrTexture; })`. It runs after the before-tonemap
  hooks, gets the internal-resolution `c.scene()` and `c.depth()`, and returns the output-resolution pre-exposed HDR image
  (e.g. RGBA16F, your history). Bloom, the exposure meter and the final pass then work on that image (bloom is re-sized to
  it), and the final pass samples depth by uv (any size), writing the main target (output size).
- Jitter must stay inside the projection matrix (Distant Horizons copies the game's projection) and fill
  `ShadeLook.viewProjNoJitter/prevViewProjNoJitter` + `taa` + `renderScale` in `ShadeLook.pack` (or override after it).
- Fixed-size targets with a viewport sub-rect for DRS (DH reallocates its screen textures on every main-target resize). The
  HDR world target is allocated at the main render target's size; with a sub-rect, the before-tonemap hooks must restrict
  themselves to `renderScale.zw` and the resolver outputs the full output image.
- Rescale history by `exposure.x / exposure.z` (pre-exposure changes every frame).

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
| `mcopt.shade.preset` | performance | performance / balanced / quality / ultra (bloom levels; modules read `ShadeConfig.TIER`) |
| `mcopt.shade.dhBridge` | true | Distant Horizons through the HDR frame |
| `mcopt.shade.sunTilt` | 30 | sun path tilt (degrees) |
| `mcopt.shade.log` | 0 | every N s: elevation, exposure ref/gpu, metered log2, grade values |
| `mcopt.shade.debug` | 0 | 1 shadows, 2 normals, 3 lighting only, 4 log luminance (final pass), 5 water SSR/Fresnel, 6 water path/depth, 7 water footprint/flow |
| `mcopt.shade.water` | true | water module (false: M1's stand-in water, byte-identical shaders) |
| `mcopt.shade.ssr` / `.ssrQuad` / `.ssrSteps` / `.ssrDist` | true / true / 24 / 512 | screen-space reflections (quad-shared march) |
| `mcopt.shade.ssrPrev` | true | reflections read last frame's HDR world (clouds, sky, LODs in reflections) |
| `mcopt.shade.waterBake` | true | waves baked once per frame into a mipmapped texture (false: analytic per pixel) |
| `mcopt.shade.waterWaves` / `.caustics` / `.waterFoam` / `.waterReflect` / `.glossy` | 1 / true / true / 1 / false | see WATER.md |

## 9. Merge notes

- `ShadeMsl.prelude()` grew (shade_look + shade_common + shade_m2): module MSL that prepended `shade_common.metal` alone
  should use `ShadeRender.prelude()`.
- `MetalRenderPass` now calls `p.state(formats, hasDepth)` instead of `hasDepth ? p.withDepth : p.withoutDepth`; a module
  that sets pipelines itself (water's reopen path) must use the same call, or the HDR pass gets an RGBA8 PSO (Metal
  validation error / garbage).
- `MetalTerrain.drawPulled` uses `p.pulledState(encoder.passFormats)`.
- `ShadeBindings` slots are 2048 B (ShadeFrame at 0, ShadeLook at 1024).
