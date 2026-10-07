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

## 3. Water (`m2-water`): where water shading runs

- Water is drawn by `sodium:pipeline/translucent_terrain` inside the redirected world pass, so its fragment writes the HDR
  target. Compile it the M2 way: the translucent stage gets `#define MCOPT_HDR` in its HDR variant (format adapter); output
  through `shadeOut`/`shadeFinish2` and use `shadeExposure(sf, sg)` — never `shadeOutput` directly. Declare
  `constant ShadeLook& sl [[buffer(17)]], constant ShadeGpu& sg [[buffer(18)]]` like sodium_terrain.fs.metal does.
- The scene copy at the translucent switch (your `MetalRenderPass.setPipeline` hook + `mc_render_reopen`) works unchanged on
  m2, but **colour attachment 0 is now the RGBA16Float HDR target**: make the copy texture with the attachment's own pixel
  format (`[attachment pixelFormat]` in native, or `ShadeConfig.HDR_FORMAT`) — a blit between
  different formats is invalid. Values in it are pre-exposed linear (no sRGB decode).
- SSR: march your copy (this frame, opaque only) or read `ShadeResources.prevScene` (last frame, everything incl. water,
  rescale by `exposure.x / exposure.z`, reproject with `prevViewProj`). With TAAU, prefer TAAU's history (output res).
- The underwater fog colour: `ShadeFrame.fogColor` is display-referred; in HDR convert with `shadeLdrToHdr(c, L)`
  (shadeFinish2 already does).
- Keep water shading in a shared include (`water.metal`) callable by a future DH transparent override (FT2).

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
| `mcopt.shade.debug` | 0 | 1 shadows, 2 normals, 3 lighting only, 4 log luminance (final pass) |

## 9. Merge notes

- `ShadeMsl.prelude()` grew (shade_look + shade_common + shade_m2): module MSL that prepended `shade_common.metal` alone
  should use `ShadeRender.prelude()`.
- `MetalRenderPass` now calls `p.state(formats, hasDepth)` instead of `hasDepth ? p.withDepth : p.withoutDepth`; a module
  that sets pipelines itself (water's reopen path) must use the same call, or the HDR pass gets an RGBA8 PSO (Metal
  validation error / garbage).
- `MetalTerrain.drawPulled` uses `p.pulledState(encoder.passFormats)`.
- `ShadeBindings` slots are 2048 B (ShadeFrame at 0, ShadeLook at 1024).
