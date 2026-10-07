# Shade water and reflections (M2 module, branch `m2-water`, merged into `m2`)

**M2.1 (branch `m2.1`, release `work/release/mcopt-shade-M2.1.jar`):** fine ripples (own faster baked layer) + pixel texture, straight refraction, SSR to the screen sides, real-time water
clock (flags `mcopt.shade.waterDetail`, `ssrEdge`, `waterClock`): see WATER_M21.md.

**Status:** merged into the M2 HDR base on branch `m2` (via `m2-wint`). What changed in the merge (HDR wiring, reflections
from last frame's HDR world, baked waves, coarse-depth SSR) is in "M2 integration (done)" below and in M2_INTEGRATION.md
section 3; the m2-water history before it is unchanged.

Shader bible E14 (water), E15 basics (glossy translucents), E26 surface (seen from below). Built on `m1` as a
self-contained, integration-ready module for the M2 HDR base (`m2`), TAAU (`m2-taau`) and far terrain (`ft0`/FT2).
All code here is original (techniques: Schlick 1994, GGX/Walter 2007, Beer-Lambert, McGuire & Mara 2014 screen-space
marching, Tessendorf 2001 dispersion, caustics as the inverse Jacobian of the refraction map). No pack code was copied.

## What it does

| Feature | How |
|---|---|
| Scene + depth copy | At the first `sodium:pipeline/translucent_terrain` of a main pass the render encoder is ended, colour attachment 0 and depth are blitted into private ShaderRead textures (losslessly compressed), and the pass is reopened with its contents loaded (`mc_render_reopen`). |
| Waves | 7 directional waves with sharpened crests, deep-water dispersion, each warping the next (no tiling), analytic slopes; LOD by pixel footprint, amplitude fades with distance (e^-d/90), grazing angles calm toward a mirror. Flowing water advects its waves along the current (from the flow sprite's uv orientation). Rain makes it choppier. |
| Fresnel | Schlick, n = 1.333 (f0 0.0204), wave-aware. |
| Sky reflection | M1 atmosphere LUT in the reflected direction, scaled by the sky light (no sky reflections under roofs). |
| Sun/moon glint | GGX, roughness grows with distance (long glitter roads at sunset) and in rain; hidden where SSR hit something. |
| SSR | View ray marched in screen space against the depth copy: 24 steps denser near the start + 4 bisection steps, thickness test in linear depth, screen-edge and far-end fade, sky fallback. **Quad-shared**: the 4 lanes of a 2x2 quad march one ray (their mean origin/direction) in four chunks and share the nearest hit (quad shuffles) = half-res SSR at 1/4 the cost, no extra pass. The ray follows a calmer normal than the shading (reflections wobble, don't shred). The march is in screen space, so a 512-block ray costs the same as a short one: distant shores mirror in open water at sunset. Skipped (faded) when looking within ~45-50 degrees of straight down (2 % reflectance, mirrored things are behind the camera). |
| Refraction | Bottom from the scene copy shifted by the waves' tilt x depth (view space), rejected when the shifted sample is in front of the water. |
| Absorption | Beer-Lambert from the biome water colour (vertex tint): pure-water red-first absorption + the tint's darkness; view path + the light's way down; single-scattering in-scatter. Swamps go green and murky, warm oceans clear cyan. |
| Caustics | On the bottom seen through the surface: 1/|det(I + k Hessian h)| of 6 short mutually warped waves, sampled up-sun of the bottom point; gives the bright cell network. Faded with depth, shadow, sky light and pixel footprint (no aliasing far away); skipped where invisible. |
| Foam | Thin shoreline foam from the bottom's distance to the surface, broken up by wave height. |
| Underwater | Snell's window (refracted world above from the copy, else the sky + the sun as a sparkling core + glare), total internal reflection outside (SSR into the underwater scene, else rippled water colour); vanilla underwater fog applies between the eye and the surface. |
| Glossy translucents (opt-in) | Stained glass, ice, slime, honey: n = 1.5 Fresnel mirror (sky + SSR + sun) over their lit alpha-blended colour, keeping transmittance (distant panes become mirror-like). Off by default until seen in game (the bench world has no ice or stained glass). |

## Files

| File | Role |
|---|---|
| `metal/src/main/resources/mcopt/shade/water.metal` | The whole shader module (include). Entry points: `shadeWaterSurface(ShadeWaterIn, ...)`, `shadeGlossy(...)`, `shadeSsr(...)`, `shadeWaterSprite`, `shadeWaterFlowDir`. |
| `metal/src/main/resources/mcopt/metal/msl/sodium_terrain.fs.metal` | (PolyForm Shield header kept) the translucent fragment's water branch under `SHADE_WATER`, glossy under `SHADE_GLOSSY`. |
| `metal/src/main/native/mcwater.m` | Copies, dummies, bindings (`mc_water_new/params/bind/capture`). |
| `metal/src/main/native/mcmetal.m` / `.h` | `mc_render_reopen` split out of `mc_render_resume`. |
| `metal/src/main/java/mcopt/metal/shade/water/ShadeWater.java` | Flags, MSL prelude (defines + water.metal), native state, per-frame params. Free of game types. |
| `.../shade/water/WaterFrame.java` | Game-typed per-frame part: water sprite uv rectangles (exact water detection on every face), surface height above the eye. |
| `.../shade/water/WaterNative.java` | FFM downcalls. |
| `MetalRenderPass.setPipeline` | The capture hook (once per pass, main passes with depth), then `restoreAfterReopen` (scissor, vertex buffers, push constants, shade globals). |
| `ShadeBindings.bind/rebind`, `ShadeFrameUpdater.update`, `ShadeMsl.terrain` | Bind the copies, feed the frame, assemble the translucent fragment with the module. |
| `tools/shade/validate.sh`, `ShadeMslDump.java` | Validates the translucent fragment with the module (opt-in glossy on) and without it (`shade_translucentnowater`). |

## Data contract (fragment stage, translucent terrain only)

| Slot | What |
|---|---|
| `texture(18)` `sceneCopy` `texture2d<half>` | Colour attachment 0 at the translucent switch (opaque scene: sky, terrain, entities, clouds if drawn before). Same format as the attachment: RGBA8 tone-mapped sRGB in M1, RGBA16F pre-exposed linear in M2 HDR. 1x1 dummy until the first capture. |
| `texture(19)` `depthCopy` `depth2d<float>` | Depth32Float reversed-Z at the same moment. |
| `texture(20)` `waterWaves` `texture2d<half>` | (m2) The baked wave field: RGBA16F 1024^2 + mips over a 64-block tile (slope xz, height, Laplacian). Dummy unless `state.w`. |
| `texture(21)` `waterCoarse` `texture2d<float>` | (m2) The SSR march's coarse depth: half-size R32F, nearest reversed-Z per 2x2 block, rendered after each copy. |
| `buffer(19)` `ShadeResources` (m2 HDR variant) | `prevScene`: last frame's finished HDR world, the colour source of SSR hits and of the rendered sky in reflections. |
| `buffer(23)` `ShadeWaterParams` (64 B, setFragmentBytes) | `still`, `flow` water sprite uv rects; `state` x copies valid this frame, y surface height above the eye, z wetness; `tune` x waves, y SSR distance, z reflection strength, w caustics. `state.x == 0` means never read the copies (they are dummies or last frame's). |

Slot notes: textures 16/17 are M1's shadow map and sky LUT; M2's buffers 17/18/19 (ShadeLook, ShadeGpu, ShadeResources) do
not collide (buffer vs texture namespaces). Far terrain's own pipelines use buffers 22-28 but rebind shade (and with it
the water bindings, `ShadeBindings.rebind`) after drawing.

## Flags (system properties or `config/mcopt.properties`; all need `mcopt.shade=true`)

| Flag | Default | |
|---|---|---|
| `mcopt.shade.water` | true | The module. `false` = M1's still-water stand-in with byte-identical M1 shaders (validated as `shade_translucentnowater`). `mcopt.shade=false` stays upstream (nothing here runs: every hook tests `ShadeWater.ON`). |
| `mcopt.shade.ssr` | true | Screen-space reflections (false: sky + sun only). |
| `mcopt.shade.ssrQuad` | true | Quad-shared SSR (false: one ray per pixel). |
| `mcopt.shade.ssrSteps` | 24 | March steps (4-64) + 4 bisection steps; shared by the quad (6 per lane). |
| `mcopt.shade.ssrDist` | 512 | Max ray length (blocks). Screen-space march: longer costs nothing. |
| `mcopt.shade.waterWaves` | 1.0 | Wave strength (0 = mirror). |
| `mcopt.shade.caustics` | true | Caustics on the bottom. |
| `mcopt.shade.waterFoam` | true | Shoreline foam. |
| `mcopt.shade.waterReflect` | 1.0 | Artistic scale on Fresnel (0-2). |
| `mcopt.shade.glossy` | false | Glossy stained glass / ice / slime / honey (opt-in). |
| `mcopt.shade.ssrPrev` | true | (m2 HDR) Reflections read last frame's HDR world: SSR hit colours with clouds / translucents / LODs, and the rendered sky (sun disc, clouds, stars, DH LODs) where the reflected direction is on screen. |
| `mcopt.shade.waterBake` | true | (m2) Waves baked once per frame into a mipmapped texture (false: the 7 analytic waves per pixel). |
| `mcopt.shade.debug` | 0 | 7 (was 4 before the m2 merge; 4 is the base's log luminance) = footprint / flow / distance, 5 = SSR hit weight (r) / Fresnel (g), 6 = refracted path (r) / depth (g) / no floor (b). |
| `mcopt.shade.dev` bit 8 | | Pricing: water module off but the copy still taken (= copy cost). |

## Performance (M4 10-core GPU, 3024x1964 native, mcbench perf scenes, GPU ms/frame, same-session A/B)

The perf "spin" scene looks over the bay from 33 blocks up: water is roughly half the screen (worst case for this
module). See the commit log and `bench-results/*-w5-*`, `*-w6-*` for the raw runs.

| Run | spin GPU ms | flight GPU ms | note |
|---|---|---|---|
| water off (`w5-off`) | 4.39 | 4.90 | M1 stand-in water |
| copy only (`w5-copy`, dev 8) | 5.23 | 5.95 | the pass split + blit of colour and depth: ~0.8 ms at native |
| per-pixel SSR, 16 steps (`w5-on`, `w5-on2`) | 8.3-8.9 | 7.0-7.8 | before optimisation |
| no SSR (`w5-nossr`) | 6.84 | 6.50 | SSR was ~1.8 ms |
| no caustics (`w5-nocaus`) | 8.32 | 7.28 | caustics were ~0.3-0.6 ms |
| quad SSR, 24 steps, Fresnel gate, caustic/wave early-outs (`w6-on`, `w6-on2`) | 6.85-6.94 | 6.39-6.43 | water off in the same session (`w6-off`): 4.28 / 4.84 |
| same, per-pixel SSR 16 steps (`w6-noquad`) | 7.64 | 6.64 | quad sharing saves ~0.8 ms |
| no waves, caustics or SSR (`w7-base`) | 5.34 | 5.59 | refraction, absorption, sky, glint, foam: ~0.1 ms over the copy |
| + waves (`w7-waves`) | 6.17 | 5.72 | the 7 analytic waves: ~0.8 ms (largest shader item) |
| everything (`w7-on`) | 6.96 | 6.43 | SSR + caustics: ~0.8 ms |
| + short-crested waves on all 7 (`w9-on`, `w9-on2`; `w9-off`, `w9-off2` = 4.21 / 4.20) | 7.23-7.44 | 6.54-6.79 | the crest sin cost ~0.4 ms; now applied to the 3 longest waves only (HEAD) |
| HEAD (`w10-on`, an "all" run with shots; `w10-off` = 4.13 / 4.72) | 8.37 | 8.73 | noisier session; see the note below |

Session-to-session spread: "water off" stays at 4.1-4.4 ms spin in every session, while "water on" with the same code
measured 6.85-6.94 (w6) and 7.2-7.4 (w9), and 8.4 in w10, with other agents' GPU work and builds running alongside.
Re-measure the HEAD in a quiet session before quoting one number. The attribution below comes from the same-session
matrices (w5, w7).

Cost split at native Retina in this worst-case view (~2.6 ms over water off): scene copy ~0.85, waves ~0.8, SSR
~0.5, caustics ~0.3, the rest ~0.1. Every item scales with the pixel count, so TAAU at 0.67x (0.45x pixels) brings it to
~1.2 ms. The shading costs nothing when no water is on screen; the copy (~0.85 ms native) runs whenever a main pass
uses Sodium's translucent pipeline, i.e. almost every frame. A possible saving: skip it when last frame drew no water
and no glossy translucent pixels.

## M2 integration (done, branch `m2`)

- HDR wiring exactly as planned below (steps 1-3, 5): `shadeLit2`, `exposure = ShadeGpu.exposure.x`, HDR scene copy in
  the attachment's format (RG11B10F by default), result through `shadeFogAir2` + `shadeFinish2`; the TIR fallback colour
  comes in as `ShadeWaterIn.fog` (`shadeLdrToHdr(fogColor, sl) / ex`, only computed under water).
- Step 4 + 6 done (`mcopt.shade.ssrPrev`): `shadeWaterHitColor` fetches SSR hit colours from `prevScene` (depth-copy point
  -> `invViewProj` -> `prevViewProj`, / `exposure.z`); `shadeWaterSkyRefl` reads the rendered sky in the reflected
  (calm-normal) direction where it is on screen and the depth copy holds sky or terrain beyond 160-320 blocks, faded into
  the LUT toward the screen edges. Clouds, the sun disc and aureole, stars and, with Distant Horizons, far LODs now
  mirror in open water.
- Wave bake done (`shade_water_bake_fs`, `mcopt.shade.waterBake`): the 7 waves on a 64-block lattice plus 7 off-axis
  extras (the two longest trains give part of their energy to them, which breaks up the diamond lattice in the glitter
  road), 1024^2 RGBA16F + mips, sampled with anisotropy. Caustics stay analytic.
- SSR: all of a lane's depth reads are issued before testing, against the half-size coarse depth (`shade_water_hz_fs`,
  rendered in `mc_water_capture` right after the blit); a crossing that only grazes the thickness limit fades instead of
  popping. ~0.6 ms saved at native in the bay view (same-session `wi4` vs `wi3`).
- Water fragments use M1's 4-tap tent shadow instead of PCSS (the shadow only gates glint and caustics).
- Numbers and shots: M2_STATUS.md (water section).

## Integration with the M2 HDR base (branch `m2`) — the plan, kept for reference

1. **Merge conflicts to expect:** `MetalRenderPass.java` (keep the capture in `setPipeline` + `restoreAfterReopen`; it
   must run on the redirected HDR pass, which it does since it acts on the open encoder's attachments),
   `ShadeBindings.java` (keep `if (ShadeWater.ON) ShadeWater.bind(ctx, enc)` in `bind` and `rebind`),
   `ShadeFrameUpdater.java` (keep `WaterFrame.update` before `f.pack`), `sodium_terrain.fs.metal` (m2 renamed `shadeLit`
   -> `shadeLit2(..., sl)`, `shadeFogAir2`, `shadeFinish2(..., ex)` and added `ShadeLook sl [[buffer(17)]]`,
   `ShadeGpu sg [[buffer(18)]]`), `ShadeMsl.java` (`terrain(source, water)` must also prepend shade_look/shade_m2 like
   m2's `prelude()`; ShadeWater.prelude goes last, after shade_m2.metal), `validate.sh`.
2. **In the translucent fragment's water branch:** `wi.lit = shadeLit2(half3(1.0h), up, ..., sf, sl)`;
   `wi.exposure = ex` (m2's pre-exposure, `sg.exposure.x`) instead of `sf.time.z`; the debug-4 line likewise. The
   result `radiance` then goes through `shadeFogAir2` + `shadeFinish2` like every other texel (the module stays in linear
   radiance, before exposure; it never tonemaps). Same for `shadeGlossy(..., ex, ...)`.
3. **HDR scene copy:** with `MCOPT_HDR` defined (m2 defines it for HDR variants) `shadeWaterSceneRadiance` reads the copy
   as pre-exposed linear radiance divided by the exposure; mcwater.m already allocates copies in the attachment's format
   (RGBA16F). Without HDR it undoes M1's tone curve. Fog colour (display-referred) always goes through
   `shadeWaterDisplayRadiance`; under the Filmic tonemap that inverse is approximate (only used for the TIR fallback).
4. **SSR source:** the bible wants SSR to sample last frame's TAA output (`ShadeResources.prevScene`, slot 1 of m2's
   argument buffer, reprojected with `prevViewProjNoJitter`, exposure ratio `sg.exposure.x / exposure.z`) so reflections
   include clouds, VL, translucents and fog. Today SSR samples this frame's scene copy (no translucents, no post VL).
   Switch inside `shadeSsr` where it samples `scene` (one line); the hit search keeps using the depth copy.
5. **Bloom/exposure:** the glint is clamped (60x sun colour) so bloom gets a strong but finite highlight; metering sees
   water like any other pixel.
6. **Clouds:** if M2 draws clouds after translucent terrain (vanilla order), sky reflections have no clouds. Cheap fix in
   the HDR base: sample `prevScene` at the SSR miss direction's screen position when it lies on screen and is sky.

## Integration with TAAU / dynamic resolution (branch `m2-taau`)

1. Define `SHADE_WATER_SUBRECT` in `ShadeWater.prelude()` once `ShadeFrame.screen` exists (m2-taau adds `float4 screen`
   = live render size): every NDC -> pixel mapping and uv in the module goes through `shadeWaterRenderSize`, so SSR and
   refraction stay inside the live sub-rect of the fixed-size targets and never read stale texels outside it.
2. The copies are allocated at the attachment size (the fixed maximum): no reallocation when the scale changes. Copy only
   the live sub-rect: in `mc_water_capture` use `copyFromTexture:sourceSlice:sourceLevel:sourceOrigin:sourceSize:` with
   (0, 0, render size) — saves bandwidth at lower scales (the copy is ~0.8 ms at native).
3. Temporal: pass the frame index to `shadeNoise` in `shadeSsr` (jitter per frame, TAA integrates more SSR steps) and
   write `reactive = 0.25` for water if the TAA has a reactive mask (animated normals).
4. Jitter lives in the projection matrix (`f.viewProj`, `f.proj`): SSR and refraction use the jittered matrices
   consistently, nothing else to do.

## Integration with far terrain (Distant Horizons, FT2)

1. **Scene distance:** all depth reads go through `shadeWaterSceneW` (one function). With DH, make it read the merged
   min(MC, DH) view-depth image (far-terrain-plan 3.2-2) and the module's SSR, refraction depth, absorption and foam all
   see LOD terrain (far shores reflect at sunset).
2. **LOD water:** water.metal is a plain include: DH's transparent override (material 12) can fill a `ShadeWaterIn`
   and call `shadeWaterSurface` with `SHADE_SSR` undefined (env + glint only, R9), absorption from DH opaque depth. Build
   that variant without `SHADE_SSR` (or pass `wp.state.x = 0` so no copy is read).

## Screenshots

`bench-results/*-w4*/screenshots`, `*-w6-on`, `*-w8-on` and `*-w10-on/screenshots` (`water_sunset`, `water_shore_noon`, `water_shore_evening`,
`water_shallows`, `water_underwater_up`, plus the standard `sunset_water`). The opt-in water scenes are in mcbench
(`-Dmcbench.water=true`, see HARNESS.md step 8b).

## Known issues / next

- (fixed on m2) Clouds in sky reflections and water reflecting water/glass: via `prevScene` (`ssrPrev`). The hit search
  still uses the opaque depth copy, so a translucent surface is only mirrored where something opaque is behind it.
- Distant water still shows a faint diamond pattern around the glint at sunset (less with the baked extra waves).
- The TIR region from below is the fog colour rippled (SSR into the underwater scene when it hits).
- Glossy translucents untested in game (no ice/stained glass in the bench world) -> opt-in.
- (done on m2: `mcopt.shade.waterBake`) **Bake the waves.** The analytic waves are the biggest shader item (~0.8 ms native worst case). Bake
  them once per frame into a tileable, mipmapped RGBA16F slope texture (e.g. 512² over a 32-block tile: long set in RG,
  a short set with an 8-block period in BA; wave vectors on the tile lattice; render it like the sky LUT in
  `mc_shade_lut_render`, then `generateMipmaps`), and sample it twice (scale/rotation offset octaves, anisotropic
  filtering) in the fragment. Expected ~0.6 ms saved at native plus less shimmer at distance (mips instead of the
  footprint LOD); the bake can afford 12+ waves. Bible E14 describes the same idea (FFT bake). Needs a texture slot:
  textures 17-19 are taken in this pass (sky LUT, copies), so either texture(20) (free in the Sodium terrain pipelines;
  far terrain's own pipelines use 20/21 but rebind shade afterwards) or an entry in M2's ShadeResources argument buffer.
- Short-crested waves reduce, but don't fully remove, a diagonal regularity in the distant glitter road.
- SSR is dithered per 2x2 quad without TAA: hit/miss edges of thin things (leaves) look speckled at 100 % zoom; temporal
  jitter under TAAU will resolve it (see TAAU step 3).

## M2 review changes (2026-10-07)

- `prevScene` is used only when `ShadeLook.ssr.x == 1` (it holds a finished frame); the first frame and the frame after
  an HDR or TAA reallocation use the scene copy and the LUT.
- Reflections read from `prevScene` get the final pass's height haze (`shadeWaterHazed`): SSR hits and the rendered sky at
  non-sky pixels by their reconstructed position; cloud pixels (absent from the opaque depth copy) by the distance to the
  cloud layer, weighted by how much the sample differs from the plain sky. Clouds now mirror as light clouds, not dark
  slabs; pixels above y 150 count as sky in the rendered-sky weight.
- The LUT sky in the reflection follows a calmer direction (60 % toward the SSR direction): no milky aureole veil on
  evening ripples; the glint carries the sun's sparkle.
- Foam: sparse patches (two-octave scrolling value noise, wave crests), only over up-facing floors (from the depth copy),
  within ~28 blocks, mix 0.3 of lit x 0.5: no white contour along every shore, no lilac band on submerged walls.
- Snell's window: a tight sun glare (exp((ct - 1) 400) x 0.35).
- Wave bake 512^2 for performance/balanced (`mcopt.shade.waterBakeSize`), skipped after a frame without translucent terrain.
