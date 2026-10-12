# BSL Shaders v10.1.8 (Capt Tatsu): technical and artistic study (report `packs/bsl`)

Research only. All descriptions below are in my own words. No BSL code is reproduced: formulas are written as math, and
constants are given as concepts or as rounded numbers where the number is the point.

- **Pack studied:** BSL Shaders **v10.1.8** (published 2026-09-21), downloaded from the official Modrinth project
  `bsl-shaders` (`https://cdn.modrinth.com/data/Q1vvjJYV/versions/Y68yiql9/BSL_v10.1.8.zip`).
- **Local copy:** `work/packs/BSL_v10.1.8/shaders/` (zip kept at `work/packs/BSL_v10.1.8.zip`). 292 files, about 22.5k lines.
  All file references below are relative to `shaders/`.
- **Structure:** every `world0|world-1|world1/<program>.fsh|vsh` is an 11-13 line stub that sets `OVERWORLD`/`NETHER`/`END`
  plus `FSH`/`VSH` and includes `program/<program>.glsl`. All real code is in `program/*.glsl` and `lib/**`.

---

## 0. License verdict (read first)

| Item | Finding |
|---|---|
| License | **All Rights Reserved** (Modrinth license id `LicenseRef-All-Rights-Reserved`). No LICENSE file in the zip; the zip comment only credits Capt Tatsu and links capttatsu.com. |
| Terms (Modrinth page "AGREEMENT") | Allowed: use for screenshots/videos; **modify for personal use**; publish a *heavily modified* version **only with the author's permission**. Not allowed: redistribute without permission; claim or rename the pack **or its code** as your own. |
| Can we adapt code? | **No.** Our mod is distributed, so copying or translating BSL code into MSL would be redistribution of a derivative without permission. Every technique below must be re-implemented from the math and from the public sources BSL itself credits (section 9). |
| Context | Complementary Reimagined's credits say Capt Tatsu's generosity "made Complementary possible" (`ComplementaryReimagined_r5.9.3/shaders/lang/en_US.lang:60`); Complementary grew out of BSL, so many Complementary looks share BSL's foundations. |

---

## 1. TL;DR

**Why BSL looks good.**
- Every colour is authored in a perceptual "square-root" space and squared before use. The palette is warm and hand-tuned:
  - orange golden-hour sun;
  - cool blue-white noon sun with blue sky ambient;
  - cyan moonlight;
  - warm 255/212/160 block light.
- Lighting keeps a **softened vanilla face shading** on top of sun/sky light, so blocks stay readable and the look stays
  "Minecraft, but beautiful", not "photoreal".
- Sky, fog and clouds all come from one consistent artistic sky model. Fog always matches the horizon.
- The bloom has **no threshold**: 20 % of a wide multi-scale blur is mixed into everything, which gives BSL its soft, dreamy
  glow.
- The tonemapper is a **per-channel Reinhard-2 curve between a small channel-crosstalk matrix and its inverse**. Highlights
  roll smoothly to white and colours stay rich but never neon.
- Other signature details:
  - leaves glow when backlit;
  - stained glass casts coloured shadows;
  - water is clear and deep blue with strong Fresnel sky reflections and a small sharp sun glint;
  - god rays are strongest at sunrise and sunset;
  - a classic multi-ghost lens flare with an anamorphic streak.

**Why BSL is fast.**
1. **Forward shading.** All lighting runs in the `gbuffers_*` programs where the geometry is drawn. Without labPBR
   (`ADVANCED_MATERIALS` is **off by default**) there is no material G-buffer at all.
2. **Thin targets.** The main scene is `R11F_G11F_B10F` (4 B/px), stored as **sqrt(linear)** to protect dark precision.
   One RGB8 target is reused for four different jobs during the frame. AO is R8.
3. **About 8 full-screen passes, each light.** Low sample counts are made clean by dither plus TAA:
   - 7 volumetric-light steps;
   - 8 SSAO taps;
   - 9 hardware-PCF taps;
   - at most 32 cloud steps with early exit and no light-march;
   - 30 SSR steps with geometric growth.
4. **Bloom costs almost nothing.** It is one pass that reads hardware mips into a tile atlas capped at about 576-720p,
   whatever the screen resolution.
5. **One distorted shadow map**, no cascades, with hardware 2x2 compare filtering.

**For us.** Copy the *art direction and the cheapness philosophy*, not the GL plumbing. On the M4 the BSL frame maps onto
our TBDR plan with these changes:
- lighting in the geometry-bound opaque pass (nearly free);
- shadows from M1 cascades or M4 RT;
- clouds and VL at reduced resolution with temporal reuse;
- our TAAU in place of FXAA+TAA+sharpen.

Estimated BSL-equivalent frame at 3024x1964 output from 0.67x internal: **about 6-8.5 ms**, so 120 fps is within reach
(section 7.3).

---

## 2. Pipeline map

### 2.1 Programs in execution order (Overworld, default settings)

| # | Program (file) | What it does | Resolution | Enabled by |
|---|---|---|---|---|
| 1 | `shadow` (`program/shadow.glsl`) | Distorted sun/moon depth map (`shadowtex0` all, `shadowtex1` opaque only) plus `shadowcolor0` translucent tint; waving in the shadow pass; optional caustics; optional voxelization for coloured light | `shadowMapResolution` (2048 default) | `SHADOW` |
| 2 | `shadowcomp` (`program/shadowcomp.glsl`, compute, `#version 430`) | One flood-fill step per frame of the coloured-light voxel volume | 3D volume | `MULTICOLORED_BLOCKLIGHT` (off) |
| 3 | `gbuffers_skybasic` / `skytextured` | Analytic sky gradient, stars, aurora, sun glare into colortex0; vanilla sun/moon sprites scaled | full | always |
| 4 | `gbuffers_terrain`, `block`, `entities`, `hand`, `textured`, `basic`, `beaconbeam`, `spidereyes`, `armor_glint`, `damagedblock` | **Forward lighting**: shadows, sun/sky/block light, emissive, SSS, desaturation. Writes sqrt(color) to colortex0; with labPBR also smoothness/sky-occlusion (colortex3), encoded normal (gaux3) and Fresnel (gaux4) | full (jittered) | always |
| 5 | `deferred` (`program/deferred.glsl`) | SSAO (8 taps) into gaux1 R8 | full | `AO` |
| 6 | `deferred1` (`program/deferred1.glsl`) | Opaque post: rough SSR on labPBR surfaces, AO apply (depth-aware 4-tap blur), **fog**, sky for sky pixels (if `SKY_DEFERRED`), **volumetric/skybox clouds**, glowing-entity outline, writes the **reflection source image** (gaux2) and the cloud depth (gaux1) | full | always |
| 7 | `gbuffers_water` (+ `weather`, `clouds` for vanilla clouds, `entities_translucent`) | Forward translucents: water normals, SSR against gaux2, sky/cloud reflection, GGX glint, fog. Writes the translucent tint for VL into colortex1 | full | always |
| 8 | `composite` | Optional refraction, underwater fog, **volumetric light** (7 steps) encoded into colortex1 | full | always (VL if `LIGHT_SHAFT`) |
| 9 | `composite1` | 4-tap blur of VL and additive composite | full | `LIGHT_SHAFT` |
| 10 | `composite2` | Camera motion blur (5 taps) | full | `MOTION_BLUR` (off) |
| 11 | `composite3` | Bokeh DOF (60 taps, mip-LOD) | full | `DOF` (off) |
| 12 | `composite4` | **Bloom atlas**: 7 tiles, mip LOD 1-7, 6x6 Gaussian each | atlas ≤ ~576-720p | always |
| 13 | `composite5` | Bloom composite, auto-exposure (opt.), colour grading (opt.), vignette, **tonemap**, lens flare, gamma, saturation/vibrance, film grain, temporal scalars | full | always |
| 14 | `composite6` | FXAA 3.11 | full | `FXAA` (on) |
| 15 | `composite7` | **TAA** (post-tonemap) | full | `TAA` (on) |
| 16 | `final` | Chromatic aberration (opt.), **sharpen** with clamp | full | always |

### 2.2 Render targets (declared in `program/final.glsl:23-34`)

| Target | Format | Contents over the frame |
|---|---|---|
| colortex0 | **R11F_G11F_B10F** | Main scene, stored as sqrt(linear HDR). Mipmapped for bloom/DOF/auto-exposure. |
| colortex1 | RGB8 | Reused four times: translucent albedo for VL tint (gbuffers_water) → VL encoded as (vl/32)^¼ (composite) → bloom atlas encoded as (b/32)^¼ (composite4) → tonemapped LDR scene (composite5…final) |
| colortex2 | RGBA16 | TAA history (gba); r = per-pixel temporal scalars in the corner pixels (exposure, sun visibility for lens flare, DOF focus); not cleared |
| colortex3 | RGB8 | smoothness, sky occlusion, entity/glow mask |
| gaux1 (4) | R8 | AO, then cloud distance |
| gaux2 (5) | RGB10_A2 | Reflection source: lit opaque scene encoded as 0.5·c^(1/8) (covers 0…256 in 10 bits), alpha = has-geometry |
| gaux3 (6) | RGBA16 | Encoded opaque normal (spheremap), refraction vector |
| gaux4 (7) | RGBA16 | Fresnel colour; also the dirty-lens texture binding |
| colortex8/9 | RGB8 / RGB16F | Screen-space coloured light (only with `MCBL_SS`) |

Bytes per pixel touched by the core passes are very low: 4 B for the scene and 3 B for the multiplexed buffer.
This is the main "how it stays fast" lesson for a bandwidth-bound GPU like the M4.

### 2.3 Key global conventions
- **Time of day.** A custom uniform `timeAngle` (`shaders.properties`, "Custom Time Uniform") warps vanilla `sunAngle`.
  It remaps the day and night halves piecewise-linearly, then eases them with a smoothstep blend, so dawn and dusk last a
  pleasant length. `timeBrightness = max(sin(2π·timeAngle), 0)`.
- **shadowFade.** Shadows fade to zero in a window of about ±0.0025 of sun angle around the sun/moon handover, so shadows
  never pop when the light source swaps.
- **Sun direction** (all vertex shaders):
  - the angle gets vanilla's celestial easing, a = (a + (½ − ½cos πa − a)/3)·2π;
  - it is rotated by `sunPathRotation = −40°`, so the sun never passes straight overhead and noon shadows keep shape.
- **lightVec** is the sun, or the moon (negated sun) during roughly timeAngle ∈ (0.5325, 0.9675).
- **Visibility factors:**
  - `sunVisibility = sat(10·(sun·up) + 0.5)` is a sharp horizon gate;
  - `sunSkyVisibility = sat(2·(sun·up) + 0.5)` is a soft one used by the sky.
- **Gamma.** Textures are linearized with pow 2.2. Light colours are authored as sqrt values and squared. The scene is stored
  as sqrt and squared again on read.
- **Dither.** A Bayer 8x8 ordered dither, plus a 512² noise texture `tex/noise.png`:
  - R: smooth Perlin-like fBm;
  - G: Worley;
  - B: **blue noise** (uniform histogram, high frequency);
  - A: blocky coverage map.
  All stochastic loops add `frameCounter·0.618` (golden ratio) to the dither so TAA integrates them.
- **TAA jitter.** 8-phase subpixel pattern applied in every gbuffers vertex shader (`lib/util/jitter.glsl`, credited to
  Chocapic13). Fragment shaders un-jitter reconstructed positions by applying −½ of the jitter.
- **Materials.** Block ids from `block.properties` (format AAABB: category plus coloured-light index; categories at
  `block.properties:9-47`) become a small float `mat` class in the vertex shader. Fragment shaders branch on it:
  - foliage, leaves;
  - emissive, lava, candle, ores;
  - water, glass, translucent, portal.

---

## 3. Effects in detail

### 3.1 Colour system (`lib/color/*.glsl`, settings in `lib/settings.glsl` "Color")
- **Authoring.** Every light colour is an sRGB-ish triple divided by 255, times an intensity. It is treated as the *square
  root* of the linear colour, so the shader uses c². Defaults:

  | Light | sqrt-space RGB × intensity |
  |---|---|
  | Sun, morning and evening | (255,160,80) × 1.2 |
  | Ambient, morning and evening | (255,204,144) × 0.35 |
  | Sun, day | (196,220,255) × 1.4. **The noon sun is slightly blue-white, not yellow.** |
  | Ambient, day | (120,172,255) × 0.6 (sky blue) |
  | Moon and night ambient | (96,192,255) × 1.0 / 0.6, × 0.3 × moon phase (1.0 at full moon down to 0.5 at new moon) |
  | Block light | (255,212,160) × 0.85 |
  | Minimum light | grey 128 × 0.5, then × 0.04 (very small) |
  | Water | (64,160,255) × 0.35, alpha 0.7 |
  | Aurora | low (80,255,180), high (80,80,255) |
  | Nether per biome | wastes orange-red (255,96,32); soul valley cyan; crimson deep red; warped pink-violet; basalt lavender |
  | End | lavender (192,196,255) × 0.9 |
- **Time blending** (`lib/color/lightColor.glsl`):
  - `mefade` picks the morning or evening tint from timeAngle;
  - `dfade = 1 − (1 − timeBrightness)^1.5` blends towards the day colour;
  - `sunVisibility` blends day towards night.
- **Rain** pulls each colour towards its own luminance times a **per-biome weather tint**: cold, desert, badlands, swamp,
  mushroom, savanna and jungle each have a tint, weighted by smoothed biome uniforms (`isCold`, `isDesert`, …).
- The final colour is the square of that mix, so the blending happens in perceptual space.
- **Why it matters.** Authoring in sqrt space and mixing there gives smooth, pleasing transitions, and makes the palette easy
  to tune. This is a large part of the "BSL warmth".

### 3.2 Forward lighting model (`lib/lighting/forwardLighting.glsl`, used by all gbuffers programs)
For a pixel with linear albedo A, lightmap (b = block, s = sky), vanilla AO `ao_v` (from `color.a`, because
`separateAo=true` in `shaders.properties`), sun term `N·L` and shadow S (RGB):

- **Direct + sky ambient (Overworld):**
  `scene = mix(ambient·s, lightCol, sat(N·L)·S·(1 − 0.95·rain)·shadowFade) · s² · (1 + scatter·S)`
  - Direct light is also scaled by s² (sky light squared), and ambient is effectively ∝ s³. **Caves never receive sun even
    if the shadow map leaks.** This is a cheap, robust anti-light-leak rule.
- **Block light:**
  `B = blockCol · (b^10·1.6 + 0.6·b)²`
  - The curve is mostly quadratic falloff, with a hot core next to the light source (the b^10 term).
- **Emissive:**
  `E = mix(normalize(A), 1, e/2) · e · EMISSIVE_INTENSITY`, with a default intensity of 4.
  - Emissive pixels also *flatten* lighting: vanilla diffuse and AO are pushed to 1 by `1 − (1−e)^128`.
- **Minimum light:** `minLight·(1 − s²)`. Night vision adds a constant.
- **Vanilla-style directional factor** (applied to *all* light):
  `vd = (0.75 + 0.25·N·up + 0.15·(0.667 − |N·east|)·(1 − |N·up|))²`
  - Approximate values: top 1.0, bottom 0.25, N/S faces ≈ 0.72, E/W faces ≈ 0.53.
  - Foliage uses an "up" normal instead (`NORMAL_PLANTS`), so grass is lit like the ground.
- **Albedo balancing:** A /= 1 + 0.25·max(A). Bright albedos (snow, sand, quartz) are compressed so they don't clip.
- **Composition:**
  `color = A · max(scene + B + E + nightVision + minLight, 0) · vd · ao_v²`
- **Darkness desaturation** (`DESATURATION`, on). In places that get neither sky nor block light, the colour is mixed towards
  `luminance · tint`, with a mix factor of about 0.4 at the default setting. The tint is:
  - the night light colour at night (Purkinje-like blue shift);
  - the weather tint in rain;
  - the dimension colour in the Nether and End.
- **Cost.** About 100-200 ALU operations plus the shadow taps per fragment, all in the geometry pass.

### 3.3 Shadows (`program/shadow.glsl`, `lib/lighting/shadows.glsl`)
- **One orthographic map** covering `shadowDistance` (256 by default, 128-512 depending on profile).
  - **Radial distortion**, to put more texels near the player:
    `p.xy /= d·k + (1 − k)`, where d = |p.xy| in clip space and `k = shadowMapBias = 1 − 25.6/shadowDistance`
    (the same formula family as Complementary).
  - **Clip z is scaled by 0.2** in the shadow vertex shader and at lookup time. Casters up to 5× deeper along the light
    direction stay inside the clip volume, so tall mountains behind you still cast.
- **Hardware compare** (`shadowHardwareFiltering = true`): each tap is a free 2x2 bilinear PCF.
- **Filter** (`SHADOW_FILTER`): 9 taps, the centre plus 8 on a unit-texel ring with 0.7 on the diagonals. That is about a
  4x4-texel soft edge. There is no PCSS and no rotation noise; the soft look comes from texel size × distortion.
- **Bias.** Normal-angle bias times the squared distortion factor, plus a distance term, plus a constant floor:
  `bias = (8·(f·D/256)²·tanθ + 0.005·|worldPos| + 0.05) / res`
  - An alternative mode offsets the sample position along the normal instead.
- **Edge fade.** Shadows fade out within the outer 1 % of the map. In the Overworld they are forced off where the sky
  lightmap is 0 (caves keep no stray shadows).
- **Coloured shadows** (`SHADOW_COLOR`, on at HIGH):
  - `shadowcolor0` holds translucent albedo turned into a transmittance tint: towards white as alpha → 0, black as alpha → 1;
  - `shadowtex1` holds opaque-only depth.
  - The result is `tint²·(1 − S0) + S0`: the coloured tint shows only where the all-geometry depth occludes and the opaque
    depth does not.
  - The extra texture reads happen **only when S0 < 1** (a cheap branch).
  - Water is discarded from the shadow map unless `WATER_SHADOW_COLOR` or caustics are on.
- **Pixelated shadows option** (`SHADOW_PIXEL`) snaps the world position to a 1/N-block grid before lookup.
- **Distant Horizons / Voxy LOD shadows.** A 16-step screen-space ray march towards the light with exponential steps
  (`GetLODShadows`, `program/deferred1.glsl`).
- **Cloud shadows** (`SHADOW_CLOUD`, off). Project the point along the light onto the cloud plane and sample the same 2D
  base noise used by the clouds.
- **Water caustics** (`WATER_CAUSTICS`, off). Computed **inside the shadow pass**, so lighting gets them for free:
  - when water writes `shadowcolor0`, its tint is brightened by a curvature estimate of the water height map, using the
    product of opposite finite differences;
  - the tint is stored at ¼ scale over a cleared 0.25 grey, giving HDR headroom in RGB8.

### 3.4 Subsurface scattering for leaves and plants
- **Basic SSS** (`BASIC_SSS`, always available). Foliage and candles get 0.5, leaves get 1.0:
  - N·L is pushed towards 1 by `0.7·sqrt(sss)`, so leaves are lit from both sides;
  - a forward-scatter lobe `((V·L + 1)/2)^16 · (1 − rain) · shadowFade` multiplies the lighting by (1 + lobe·S).
    **Leaves glow when you look towards the sun through them.**
  - Shadow lookups for SSS pixels use a wider kernel (offset about 0.0007, growing at grazing angles) and a softer
    combination.
- **labPBR SSS** (`ADVANCED_MATERIALS`):
  - 12 shadow taps spiralling inwards with interleaved-gradient noise, at increasing depth offsets. This gives an estimate of
    how much light reaches *through* the material;
  - the transmitted colour is the normalized albedo, whitening as transmission grows, plus the same forward lobe.

### 3.5 Ambient occlusion (`program/deferred.glsl`, `lib/lighting/ambientOcclusion.glsl`, apply in `deferred1`)
- **"Modern" method** (default):
  - reconstruct the view-space normal from depth with 4 neighbours, picking the closer side on each axis to avoid edge
    errors;
  - then 4 directions × 2 symmetric samples at radius 0.25 blocks, rotated 45° per step with a blue-noise start angle and
    per-frame golden-ratio rotation;
  - occlusion is a horizon-style dot of the normal with the sample direction, attenuated by distance;
  - a **"pointiness"** term (samples *below* the tangent plane) cancels darkening on convex edges, so block edges are not
    haloed;
  - the first-person hand uses a simpler depth test.
- **Apply** in `deferred1`: a 4-tap rotated-grid, depth-weighted blur, then raised to `AO_STRENGTH`, multiplying the lit
  colour (after the forward lighting). It sits on top of vanilla vertex AO, which is applied squared.
- **Cost.** About 12 depth reads per pixel at full resolution, plus 8 in the apply pass.

### 3.6 Block light extras, hand light, emissives, coloured light
- **Dynamic hand light** (`DYNAMIC_HANDLIGHT = 2`):
  - held light h = (heldLightValue − 2·distance)/15, capped at 0.93;
  - merged with the block lightmap by a **soft maximum**, b' = log₂(2^(32b) + 2^(32h))/32, which avoids seams;
  - the hand itself uses a plain max.
- **Hard-coded emission** (`lib/surface/hardcodedEmission.glsl`). Applies only to blocks in the emissive classes (glowstone,
  lava, torches …). Per texel it takes the larger of:
  - "saturated and bright": sat(3.125·s − 0.125)·v⁴;
  - "near white": sat(7v − 6);
  then halves it.
  - Only the flame or bright pixels of an emissive texture glow, not the whole block.
  - Optional glowing ores use an HSV threshold.
- **Emissive recolour** (off) remaps emissive albedo towards the block-light colour.
- **Multicoloured block light, voxel version** (`MULTICOLORED_BLOCKLIGHT`, off; needs Iris custom images and compute):
  - the shadow vertex shader writes an `r8ui` 3D voxel map (128-512 voxels per side), storing each block's light colour or
    tint index from `block.properties` (50 light colours, 25 tints);
  - `shadowcomp` (8³ workgroups) does **one 6-neighbour diffusion step per frame**: average of neighbours × 0.995 × tint,
    plus emission;
  - it ping-pongs two RGBA16F 3D textures and shifts by the camera delta;
  - light converges over frames (amortized flood fill);
  - shading samples it trilinearly (manually, because of an Iris filtering bug) at position + ½ normal;
  - volumetric light also integrates it as **coloured light fog** (`MCBL_FOG`).
- **Multicoloured block light, screen-space version** (`MCBL_SS`, off):
  - emissive pixels write normalized albedo to colortex8;
  - `composite` blurs it with 4 jittered taps against the *reprojected* previous result (colortex9), with depth-aware
    anti-bleed and an exponential history of 0.9;
  - lighting reads a mip-2 version to tint the block light.

### 3.7 Materials (labPBR / old PBR), specular, reflections on opaque (only with `ADVANCED_MATERIALS`, off by default)
- **Formats** (`lib/surface/materialGbuffers.glsl`). `MATERIAL_FORMAT 1` is labPBR:
  - specular R: perceptual smoothness;
  - G: f0, with 230-237 as hard-coded metals using real n/k (complex Fresnel, `lib/reflections/complexFresnel.glsl`);
  - B: porosity (≤ 64) or SSS;
  - A: emission;
  - normals RG, with AO in B and height in A.
- **Normal dampening.** Normal maps are faded towards flat by mip level, computed from UV derivatives × atlas size. This
  avoids sparkle in the distance.
- **Parallax occlusion** (`lib/surface/parallax.glsl`): linear search of up to 128 steps within the sprite rectangle,
  wrapped with fract inside the atlas tile. Self-shadow uses 8 steps towards the light. Faded out after 64 blocks.
- **Directional lightmap.** Builds a direction from screen-space derivatives of the lightmap and bends the lightmap value by
  N·dir. Gives fake normal-mapped block light.
- **Sun specular** (`lib/surface/ggx.glsl`):
  - GGX/Trowbridge-Reitz D with the **Decima (Guerrilla) area-light NoH trick**, which treats the sun as a disc of angular
    radius 0.05-0.075 so the highlight has a real disc shape;
  - Smith-Schlick G and the spherical-Gaussian Fresnel approximation;
  - the total is soft-clamped as x/(1 + x/128) so it never overflows R11G11B10;
  - optional square sun shape;
  - removed in rain (×(1 − rain)²).
- **Opaque reflections** (`deferred1` → `lib/reflections/roughReflections.glsl`):
  - SSR from the colortex3/6/7 G-buffer, at most 30 steps, step growing ×1.8 per step, 6 refinements on hit;
  - *roughness* comes from sampling the scene **mip LOD** proportional to (1 − smoothness)² × hit distance, plus a 5x5 tap
    loop when the LOD is ≥ 1;
  - misses fall back to the sky model, plus clouds (even volumetric clouds traced from a reflected camera), times sky
    occlusion²;
  - Fresnel-weighted add.

### 3.8 Rain, wetness, puddles, weather
- `wetness` half-life is 30 s and `dryness` 5 s (`program/final.glsl`).
- **Puddles** (`lib/reflections/rainPuddles.glsl`, labPBR only):
  - two octaves of world-space noise, plus the labPBR height (puddles collect in low texels), plus wetness;
  - smoothstep(0.4, 0.6);
  - only on up-facing surfaces with full sky light, and not in cold or dry biomes;
  - puddles raise smoothness to about √(1 − 0.75·porosity), set f0 ≥ 0.02 and darken albedo by (0.5·porosity + 0.15).
- **Ripples.** Cells in a rotated grid. Each cell spawns a ring at a random position and phase: sin(6π·r) inside a
  ring that grows over time, fading by (1 − t)². Over two noise octaves, turned into a normal by central differences.
- **Rain particles** (`program/gbuffers_weather.glsl`): alpha 0.35 × rain × luminance, lit by ambient plus block light.
  No streaks or refraction.
- **The world in rain:**
  - lights desaturate to weather-tinted luminance;
  - direct light ×0.05;
  - the sky becomes a flat weather gradient;
  - fog ×1.5 and denser;
  - god-ray falloff ×8 wider;
  - clouds cover more and become darker.

### 3.9 Sky (`lib/atmospherics/sky.glsl`, drawn in `gbuffers_skybasic` or in `deferred1` with `SKY_DEFERRED`)
An **artistic analytic model**: no Rayleigh/Mie, no LUTs, all ALU per pixel. Let u = V·up and l = V·sun.
1. **Zenith-to-horizon gradient:**
   g = exp(−(1 − (1 − max(u,0))^c) / ρ_day), with ρ_day = 0.35.
   - The exponent c changes from 1.5 (away from the sun) to 1.0 (towards it), so the horizon brightens more near the sun.
2. **Base sky:** skyCol × g, soft-clamped by x/√(x² + 1), times an exposure 2^(0.75·(timeBrightness − 1)), times
   sunSkyVisibility.
3. **Sunlight scattering colour:**
   - the sun-side mix is sunMix = (((l + 1)/2)·(1 − u))^(2 − sunSkyVis) · (1 − 0.6·timeBrightness)³, so it is strong at
     dawn and dusk;
   - the horizon band is (1 − |u|)^2.5 / 8;
   - the target colour is the sun colour raised to the power (4 − sunSkyVis) times g. **Raising the orange morning colour to
     a power of 3-4 is what makes BSL sunsets deep and saturated.**
   - The mix happens in sqrt space.
4. **Night sky:** night light² × exp(−u/0.65) × 2^−3.5, blended in by max(sunVis, sunSkyVis)².
5. **Rain sky:** weather colour² normalized to the ambient luminance, × exp(−u/1.5).
6. **Ground:** below the horizon, darkening 1 − exp(−density·fogDensity/(−u)).
7. **Underground fade** by camera y relative to bedrock (and optionally by eye brightness).
- **Vanilla sky textures** (`gbuffers_skytextured`) are scaled; the vanilla sunset gradient and stars are suppressed
  (`gbuffers_skybasic` vertex: alpha 0 for those render stages). Custom skyboxes can be desaturated at night.

### 3.10 Sun, moon, glare (`lib/atmospherics/sunmoon.glsl`)
- **Shader sun/moon** (`SHADER_SUN_MOON`, off; the default keeps the vanilla sprites ×SUN_INTENSITY²):
  - the disc is smoothstep(1 − size, 1, |l|)³, with size 0.007 for the sun and 0.003 for the moon;
  - "mini glare" is |l|^192 × 0.05;
  - optional square shape via an SDF in the sun's tangent frame;
  - the moon has phases: a sphere normal reconstructed in the disc's tangent frame is lit by a per-phase light vector and
    smoothstep range;
  - sun colour is raised to a power that rises sharply near the horizon (redder disc at sunset);
  - fades below the horizon (`SUN_MOON_GROUND`).
- **SunGlare.** A wide additive halo around the light, 0.25 × lightCol × visibility.
  - visibility = f/(1 − (1 − f)·x) − f with x = (V·L + 1)/2, a hyperbolic falloff whose width f depends on time of day:
    morning 0.25, day 0.10, night 0.50, rain ×8 (`LIGHT_SHAFT_*_FALLOFF`).
  - Used on the sky when VL is off, and underwater. **The same falloff shapes the VL phase**, so glare and god rays match.

### 3.11 Stars, aurora, night (`lib/atmospherics/clouds.glsl`)
- **Stars:**
  - the view direction is projected onto a plane using wpos/(y + |xz|), so density stays roughly even towards the horizon;
  - quantized to a 1024 grid that drifts slowly with time and camera;
  - product of three hash noises, thresholded at 0.8125, × 5·u^¼ × moonVisibility × (1 − rain);
  - hidden by the moon; coloured by night light^0.8.
- **Aurora** (`AURORA`, 0 by default, 1 = snowy biomes, 2 = everywhere):
  - ray-march of 24 samples (12 in reflections) through slabs at heights 8-15 on a projected plane;
  - two blue-noise octaves give a **ridge function** max(1 − 4·((u + 1)/2)·|n − 3|, 0), which produces thin curtains;
  - modulated by two more noises and a radial fade;
  - colour goes from low green-cyan to high blue with pow(step, 0.4);
  - intensity decays as 2^(−6·i/N) with height;
  - only at night, outside rain.

### 3.12 Clouds (`lib/atmospherics/clouds.glsl`, drawn in `deferred1`, reflected in water)
Modes (`CLOUDS`): 0 off, 1 skybox (2D layered), **2 volumetric (default)**, 3 vanilla mesh lit by forward lighting
(`gbuffers_clouds`). The base noise (`CLOUD_BASE`) is Perlin (default), Worley or blocky.

- **Volumetric slab:**
  - between `cloudHeight` (an Iris uniform; 192 otherwise) and +thickness × scale (5 × 12 = **60 blocks**);
  - ray-plane intersection gives the entry and exit distance;
  - step length is half the slab thickness (30 blocks), shrunk when looking steeply from outside the slab;
  - **at most 32 samples** (64 for blocky), starting at a dithered offset.
- **Density per sample:** 2D noise only.
  - base = Perlin at a large scale with wind;
  - detail = blue noise **pseudo-3D**: two 2D lookups at UV offsets chosen by the integer layer floor(h·thickness)·0.04,
    linearly interpolated by the fractional layer;
  - **vertical profile**: coverage term ∝ (|h − 0.125|·k)², with k = 8 below 12.5 % height (a flat-ish base) and a gentler
    k above (rounded tops; `CLOUD_AMOUNT` shifts it);
  - density = max(21·mix(base, detail, small) − profile − amount − sunReveal, 0), then **soft-saturated** as n/√(n² + 0.5);
  - rain raises coverage.
- **"Cloud reveal":** coverage is reduced in a cone around the sun or moon, so the light disc is not hidden. An art trick.
- **Lighting with no light-march:**
  - each sample's brightness is a function of **height inside the cloud** (h^(0.875 + 1.125·hv²)·0.8 + 0.2, with hv the
    half-VoL) times (1 − n^k): denser means darker, higher means brighter;
  - accumulated with front-to-back "over" weights;
  - final colour = mix(ambient × (0.5 + 0.3·sunSkyVis), lightCol × (0.85 + 1.15·scatter), lighting);
  - scatter = hv⁶ is a silver-lining lobe, and clear gaps get extra scatter;
  - a horizon sun colour (the sun colour raised to a power) tints clouds near sunset.
  - About 3 texture fetches and some ALU per sample.
- **Fades:**
  - horizontal distance fade from 32 to 240 blocks / fogDensity;
  - "far vanilla fog" mask;
  - soft intersection with terrain depth;
  - early exit at 99 % opacity.
- **Outputs:** colour and opacity (squared), plus **cloud distance** in gaux1 (R8, normalized to 2·far). Translucents
  farther than the cloud are discarded, which gives correct water and cloud ordering.
- **Skybox clouds** (mode 1): the same density function sampled on 10 stacked planes along the view ray. No slab geometry,
  so it is cheaper.
- **Cost drivers.** Full resolution and every sky pixel. In reflections the volumetric march runs **again per water pixel**.

### 3.13 Fog (`lib/atmospherics/fog.glsl`, applied in `deferred1` for opaque and in `gbuffers_water` for translucents)
- **Overworld density:**
  - base f = distance × fogDensity / 1024;
  - × (night: up to 4×, `FOG_DENSITY_NIGHT`), × (rain: 1.5×), × (per-biome cold/dry/damp factors), × (indoor factor, from
    eye brightness);
  - soft-capped;
  - × a height falloff 2^(−max(y − 62, 0)/2^7), i.e. halving every 128 blocks above sea level;
  - shaped as 1 − exp(−2·f^(1.25 + 0.35·clearDay·outdoors)).
- **Fog colour** = the same sky model evaluated with a gentler gradient (exp(−(u + 1)/4/0.4)) and a sun-side mix that grows
  with distance. **Fog therefore matches the sky near the horizon and glows orange towards a low sun.** Indoors it fades to
  the minimum light colour.
- **Far "vanilla" fog** (`FAR_VANILLA_FOG`, Nether/End by default; style 0 spherical): a linear border fog over the last 20 %
  of render distance, towards the *sky* colour, so chunks melt into the sky.
- **Underwater** (`composite`, `lib/atmospherics/waterFog.glsl`):
  - 1 − exp(−2·d/64), with water colour × lightCol × eye-brightness tint, mixed in sqrt space;
  - lava and powder snow use dense colour fogs;
  - blindness and darkness use a cubic black fog.
- **Cost.** ALU only (the sky model again).

### 3.14 Volumetric light / god rays (`lib/atmospherics/lightShafts.glsl` in `composite`, blur in `composite1`)
Credited to Robobo1221's approach.

- **7 samples**, placed at view distances 2^(i + dither) − 0.95 for i = 0…6. That covers 0.05 to 128 blocks with
  exponential spacing, so near detail is dense and far is sparse. Dither is blue noise + golden-ratio TAA rotation.
- **Each sample:**
  - reconstruct the world position *along the pixel ray* at that linear depth;
  - project it into the distorted shadow map;
  - hardware-compare `shadowtex0`, plus coloured `shadowtex1/shadowcolor0` (stained glass gives **coloured shafts**).
- **Stop conditions:** the opaque depth, or a solid translucent.
- **Tints along the ray:**
  - behind a translucent: that translucent's albedo, from colortex1;
  - underwater: water tint growing with distance.
- **Integration is an average, not an extinction model:** VL = mean(shadow·tint) × phase(V·L) × lightCol × 0.25 × strength
  × (1 − 0.875·rain·outdoors) × shadowFade.
  - The phase is the SunGlare hyperbola above (time-of-day width), mixed with 1 by eye brightness.
- **End:** "smoke" from pseudo-3D noise (2D layers every 3 blocks of height, sine-shaped), plus End flash direction and
  intensity with a slowly cycling HSV tint.
- **Nether:** VL exists only with voxel coloured light (block-light fog).
- **Storage:** (VL/32)^¼ in RGB8 plus dither.
  - `composite1` takes 4 rotated-grid taps around the pixel (offsets 1.5/0.5 px), sums the encoded values and raises them to
    the 4th power. This gives a cheap blur and decode in one step. Then it adds the result to the scene.
- **Cost.** 7 × (1-3 shadow compares) per pixel at full resolution. Very cheap per sample; resolution is what costs.

### 3.15 Water (`program/gbuffers_water.glsl`, forward)
- **Surface colour.** Default mode: constant water colour (sqrt (64,160,255) × 0.35, alpha 0.7). Other modes: vanilla
  texture, tinted vanilla, flat biome colour. The VL tint is sqrt(albedo).
- **Vertex waves.** Two travelling sines in x/z give a small y displacement on top surfaces (`WAVING_WATER`).
- **Normals** (`WATER_NORMALS` 1 = Worley (default), 2 = Perlin, 3 = resource pack):
  - height = two octaves of noise texture (scales /256 and /48 blocks, moving in opposite wind directions, mixed by
    `WATER_DETAIL` 0.25), with the xz position sheared by y;
  - **4-step parallax** offsets the lookup along the tangent-space view vector;
  - normal by central differences (4 more height reads, offset `WATER_SHARPNESS`);
  - strength 0.35 × (1 − F⁸), so normals flatten at grazing angles. **This removes the "noisy mirror" look and keeps a calm
    horizon.**
- **Fresnel.** Schlick pow 5, remapped to 0.02-1.0, halved when viewed from underwater.
- **Reflection** (`REFLECTION = 2`):
  - SSR (`lib/reflections/raytrace.glsl`): starts slightly off the surface; the step grows ×2 per iteration; at most
    30 iterations; 4 binary refinements; screen-border fade;
  - it samples **gaux2, the lit opaque scene of the current frame** (including sky, fog and clouds), encoded as 0.5·c^(1/8)
    in RGB10A2;
  - misses use the sky model + aurora + **volumetric clouds traced from the reflected camera** (with the faster fade) +
    sky occlusion² from the lightmap.
- **Sun glint:** the same GGX area-light highlight with smoothness 0.9 and f0 0.02, divided by the specular alpha so blending
  does not dim it.
- **Blend:**
  - colour = mix(albedo, reflection, F);
  - alpha = mix(alpha, 1, F). **Reflections at grazing angles become fully opaque**, which gives the strong mirror-like
    distant water typical of BSL.
- **Optional:**
  - `WATER_FOG = 1`: depth-based absorption using opaque depth minus water depth;
  - refraction (`REFRACTION`, off): the normal delta is stored and `composite` re-reads scene colour and depths at the offset
    UV, scaled by 1/max(distance, 8);
  - caustics via the shadow map (3.3).
- **Translucent occlusion against clouds:** fragments beyond the stored cloud distance are discarded.
- **Cost.** About 10-12 noise fetches, a ≤ 30-step SSR, and the sky + cloud march on misses.

### 3.16 Glass and other translucents
- Same program as water. Glass and translucents with 0.01 < α < 0.95 get the same SSR + sky reflection with Fresnel
  (`REFLECTION_TRANSLUCENT`).
- Nether portal: 4-layer parallax using a max of offset samples. Lit like everything else.
- Coloured shadow and VL tint come from the same albedo.
- No real refraction unless `REFRACTION = 2`.

### 3.17 Waving foliage and animation (`lib/vertex/waving.glsl`, also used in the shadow pass so shadows match)
- **Motion:** smooth value noise sampled on the yz, xz and xy planes of world position × density + time × speed. That gives
  three independent offsets, scaled per class:
  - grass ±0.25/0.06;
  - leaves ±0.08;
  - vines, crops, tall plants, fire (vertical only).
  Only top vertices move (`istopv` from mid-texcoord), or lower-half vertices for two-block plants.
- **Interactive grass.** Grass and crops are pushed away from the player's position (relative eye position), with a
  falloff, so walking through grass bends it.
- **Lanterns** swing: three small sine rotations about the hanging point. **Lily pads** bob. **Lava** surface bobs gently.
  Water waves as above.
- `ANIMATION_STRENGTH` and `ANIMATION_SPEED` control it; world-time animation is optional.

### 3.18 Nether and End looks
- **Nether:**
  - no sky; the background is the per-biome Nether colour × 0.0425;
  - lighting is a constant ambient = biome colour (sqrt) × 0.07, plus warm block light;
  - fog = 2·(d·density/256)^1.5, plus a 4th-power border fog, towards the same colour;
  - darkness desaturation tints darks with the biome colour;
  - weather is disabled;
  - lens flare and rain reflections are undefined (off).
- **End:**
  - sky = a cube-mapped quantized blue-noise speckle (512 grid per face), shaped and tinted with the End colour. It gives a
    subtle grainy starfield;
  - lighting = End colour × (0.04 × shadowed sun + 0.015), with End flash support (MC ≥ 1.21.9);
  - fog = linear plus a border fog;
  - VL "smoke" with flash tint;
  - when VL is off, a sky glow lobe around the light.

### 3.19 Post-processing

- **Motion blur** (`composite2`, off): camera-only velocity from reprojection, soft-limited by v/(1 + |v|); 5 dithered taps;
  the hand is masked.
- **DOF** (`composite3`, off):
  - CoC = |z − focus|/(z·focus), soft-limited;
  - **60-tap concentric bokeh** (rings of 15/18/27 points) reading the scene mip at LOD log₂(CoC × height / 320). The mip
    LOD makes 60 taps look like a large blur;
  - focus from the smooth centre depth or from a temporally smoothed point stored in a corner pixel of colortex2.
- **Bloom** (`composite4` builds, `composite5` composites):
  - an atlas of **7 tiles** for LOD 1…7, laid out side by side, with `colortex0` mipmaps enabled;
  - each tile pixel takes a **6x6 separable Gaussian-like kernel** (weights ≈ 0.03/0.15/0.32/0.32/0.15/0.03) reading
    the hardware mip of that LOD;
  - the atlas pixel pitch is pinned to 0.8/min(720, H). **Bloom work is constant above 720p**, and at 1964 px height only
    about 1/2.2 of the screen in each axis holds the atlas;
  - pixels outside tiles skip work;
  - stored as (b/32)^¼ in RGB8.
  - Composite: the 7 levels are averaged with weights falling by about 2^(−⅓) per level (radius 3), then
    **color = mix(color, bloom, 0.2·strength)**. **No threshold**: the whole image gets a soft halo;
  - optional contrast: done in a powered space;
  - optional **dirty lens** (`tex/dirt.png`) multiplies the larger levels by 1 + k·dirt·|bloom6|.
- **Exposure:**
  - fixed by default (`EXPOSURE` 0 means ×4 before tonemap);
  - optional `CLASSIC_EXPOSURE`: brighten ×(4 − 3·eyeBrightness) indoors (a vanilla-style "eye adaptation");
  - optional `AUTO_EXPOSURE`: read the scene mip at LOD log₂(0.7·H), a centre-weighted average, store sqrt(lum) in a corner
    pixel with exponential adaptation 2^(−dt·speed), and divide colour by 2·exposure + 0.125.
- **Colour grading** (off): a 3x3 channel-mixing matrix with per-channel power curves and a black floor, plus tint by
  luminance.
- **Vignette:** multiply by 1 − r²·(0.35·r² + 0.75)·strength, *before* the tonemap so it rolls off naturally.
- **Tonemap** (`BSLTonemap`, `program/composite5.glsl`):
  1. Exposure ×2^(2 + EXPOSURE).
  2. Multiply by a symmetric **channel-crosstalk ("desaturation") matrix** with off-diagonals 0.03 (R-G, G-B) and 0.01
     (R-B), scaled by `TONEMAP_WHITE_PATH`. This makes saturated highlights drift towards white ("path to white").
  3. **Generalized Reinhard per channel:** x/(x^n + 1)^(1/n) with n = 2 (`TONEMAP_WHITE_CURVE`). It is linear near 0,
     approaches 1 asymptotically, and has a softer shoulder than n = 1.
  4. Contrast curve x^(mix(lower, upper, √x)). Defaults are 1,1, i.e. identity, but it is exposed to users.
  5. Multiply by the **inverse** crosstalk matrix (re-saturate the mid-tones), then clamp to [0, 1].
  6. Gamma 1/2.2.
  - The family is **per-channel Reinhard with AgX/ACES-like inset and outset matrices**: hue-stable mid-tones, highlights
    that desaturate to white, cheap.
- **Saturation and vibrance:** vibrance pushes low-saturation colours more than saturated ones; saturation is a linear
  mix from luma. Both are 1.0 by default.
- **Lens flare** (`lib/post/lensFlare.glsl`):
  - about 20 analytic ghosts along the sun-to-centre line: soft discs, overlapping-disc "lens" shapes, small points with
    halos, two thin rings, an **anamorphic horizontal streak** and two faint **rainbow rings**;
  - orange to purple to blue palette; moon flares desaturated and tinted to night light;
  - visibility = depth at the sun's screen position is sky, smoothed temporally (2^(−12.5·dt)) in a corner pixel;
  - the flare is mixed towards white after the tonemap, scaled by scene brightness;
  - cost is pure ALU over the whole screen.
- **Film grain:** ±0.5/256 blue noise after gamma. Its real job is to dither 8-bit banding.
- **FXAA 3.11** (`composite6`, `lib/antialiasing/fxaa.glsl`): luma edge detection, 12-step edge walk. Runs on the LDR
  tonemapped image.
- **TAA** (`composite7`, `lib/antialiasing/taa.glsl`):
  - **after tonemapping** (LDR, gamma space);
  - history reprojected with camera matrices and depth (camera-only; the hand is not offset);
  - history fetched with a **5-tap Catmull-Rom** (Filmic SMAA);
  - **YCoCg AABB clipping** against the 3x3 neighbourhood;
  - blend weight for history 0.7 + 0.2·e^(−|velocity_px|), i.e. **only 70-90 % history**. That is low, which favours
    sharpness and low ghosting over maximum AA;
  - out-of-screen history falls back to a small 4-tap blur;
  - "Coarse" mode uses 2-frame half-pixel jitter;
  - optional selective TAA skips entities.
- **Sharpen** (`final`): a 4-neighbour unsharp mask with weight 0.125 (auto when FXAA+TAA), clamped to the neighbourhood
  min/max.
- **Chromatic aberration** (`final`, off): 4 radially offset taps with R/RG/GB/B weights.
- **Extras:**
  - outline modes (black, coloured, inner/outer "1.12 art");
  - toon lightmap;
  - retro filter (pixelation + ordered-dither palette);
  - world curvature;
  - white-world (clay) debug.

---

## 4. Settings profiles, defaults, and macOS

### 4.1 Profiles (`shaders.properties`, "Profiles")

| Profile | Changes |
|---|---|
| MINIMUM | No AO, no light shafts, **no shadows**, no coloured shadows, no shadow filter, no TAA; map 512 / 128 blocks |
| LOW | Shadows on, 1024 / 128 |
| MEDIUM | + AO + shadow filter, 1536 / 192 |
| HIGH | + light shafts + coloured shadows + TAA, 2048 / 256 (**these are the shipped defaults** in `lib/settings.glsl`) |
| ULTRA | 3072 / 512 |

**Notes.**
- Profiles never touch clouds (volumetric is always the default), water, bloom or post.
- Profiles only scale the **shadow map** and toggle the heavier passes. BSL's author treats shadows, VL and AO as the cost
  centres, and everything else as cheap.

**Other defaults worth knowing:**
- `ADVANCED_MATERIALS` (labPBR) **off**, so no opaque SSR, no puddles, no parallax;
- `REFLECTION = 2` (water and glass SSR);
- `SPECULAR_HIGHLIGHT = 2`;
- `CLOUDS = 2`, `CLOUD_BASE = Perlin`;
- `FXAA` and `TAA` on;
- `SHARPEN` auto;
- `LENS_FLARE` and `VIGNETTE` on;
- `AUTO_EXPOSURE`, `DOF`, `MOTION_BLUR`, `REFRACTION`, `WATER_CAUSTICS`, `AURORA`, `MULTICOLORED_BLOCKLIGHT` and
  `SHADER_SUN_MOON` all off;
- `sunPathRotation −40`.

**Forced video settings:**
- `oldLighting=false` (BSL removes the baked vanilla face shading and applies its own softer version);
- `separateAo=true` (vanilla AO goes into colour alpha so the shader can apply it squared);
- `dynamicHandLight=true`.

### 4.2 macOS
- **No explicit OS gating.** A grep for `MC_OS_MAC`/vendor macros finds nothing. Compare Complementary, which explicitly
  disables custom images on Mac.
- Almost every program is `#version 120`. That is the main reason BSL runs everywhere, including macOS's legacy GL path.
- **Implicitly unavailable on macOS GL** (4.1 has no compute or image load/store):
  - `MULTICOLORED_BLOCKLIGHT` needs `shadowcomp.csh` (`#version 430 compatibility`), `writeonly uimage3D` in the shadow vertex
    shader, and Iris `CUSTOM_IMAGES` (declared optional in `shaders.properties`);
  - Iris skips the feature where unsupported. `MCBL_SS` is the Mac-friendly fallback.
- On Metal we have compute and 3D textures, so the voxel version becomes possible again (section 7).

---

## 5. How BSL stays fast (the principles, ranked)

1. **Shade where the geometry is.** Forward lighting avoids a fat G-buffer write and read (only 4 B/px for the scene).
   Deferred work is limited to sky, fog, clouds and AO, which need depth anyway.
2. **Minimal bytes per pixel.**
   - R11G11B10F + sqrt encoding for HDR;
   - RGB8 with 4th-root encoding for VL and bloom (dithered);
   - RGB10A2 with 8th-root encoding for the reflection source;
   - R8 for AO and for cloud distance;
   - one RGB8 target reused for four jobs.
3. **Few steps, stochastic, resolved by TAA.** Exponential step spacing (VL, SSR), blue noise + golden-ratio frame
   rotation, low step counts (7 / 8 / 9 / 30 / 32).
4. **Hardware does the filtering.** `shadow2D` compare gives 2x2 PCF per tap; hardware mips feed bloom, DOF, rough
   reflections and auto-exposure; the noise texture is bilinear-filtered.
5. **Resolution-independent bloom** (atlas pitch pinned to ≤ 720p) in a single pass.
6. **Early-outs and cheap branches:**
   - coloured shadow reads only in penumbra or shadow;
   - shadows skipped for back-facing pixels;
   - cloud loop stops at 99 %;
   - lens flare only when the factor is above 1e-4;
   - SSR only where the Fresnel mask is non-zero;
   - sky pixels skip lighting.
7. **Fake instead of simulate:**
   - clouds without light-marching;
   - VL as an average, not extinction;
   - analytic sky;
   - darkness desaturation instead of real low-light vision;
   - shadow-map caustics;
   - skylight² gating instead of real occlusion.
8. **Amortization across frames:** flood-fill coloured light (one step per frame); temporal scalars (exposure, sun
   visibility, DOF focus) stored in single pixels.

What is **expensive** in BSL, and what to avoid copying literally:
- the full shadow map every frame (all geometry within 256 blocks);
- full-resolution volumetric clouds;
- re-running the volumetric cloud march **per water pixel** for reflections;
- full-resolution VL and SSAO;
- three AA passes (FXAA + TAA + sharpen) at full resolution;
- full-screen ALU lens flare.

---

## 6. What we should emulate (the visual qualities that make BSL great)

1. **The palette and its time-of-day choreography.**
   - Orange, strongly saturated golden hours: sun colour raised to a power in the sky.
   - A slightly *cool* noon sun with blue ambient.
   - Cyan moonlight that dims with the moon phase.
   - Warm block light with a hot core.
   - Author every colour in sqrt space and blend there.
   - Keep `sunPathRotation` about −40°, so noon still has long, shaped shadows.
2. **Readable blocks.** Keep a softened vanilla face shading under all light (top bright, sides graded, bottom dark), plus
   vanilla AO squared, then SSAO without edge halos.
3. **Light only where the sky reaches.** Scale direct sun by sky-light² and ambient by about sky-light³. Darks desaturate
   towards a night or weather tint. Caves feel dark and warm-lit; the outdoors feels airy.
4. **Soft clean shadows** with a small fixed penumbra (no noisy PCSS). **Coloured stained-glass shadows and shafts.**
   Leaves lit from both sides that **glow when backlit**.
5. **Sky ↔ fog ↔ clouds coherence.**
   - One sky model drives the sky, the fog colour (including the orange sun side) and the border fog into the sky.
   - Clouds are lit with the same light and ambient colours.
   - Clouds are fluffy, with bright tops, darker bases and a silver lining towards the sun.
   - The "reveal" keeps the sun disc visible.
6. **God rays** dramatic at dawn and dusk, subtle at noon, wider in rain. Glare halo with the same falloff.
7. **Water:**
   - deep clear blue with alpha 0.7;
   - fine two-octave ripples that **flatten at grazing angles**;
   - strong Fresnel turning into a near-mirror at distance;
   - SSR + sky + cloud reflections;
   - a tight area-light sun glint;
   - optional caustics in shadows.
8. **The glow.** Non-thresholded wide bloom at about 20 %, plus Reinhard-2 per channel between crosstalk matrices. Brights
   bloom softly and roll to white with no harsh clipping and no neon. Add the vignette, the ghost-and-streak lens flare, and
   blue-noise grain against banding.
9. **Life:** waving grass, leaves and vines; **grass that bends away from the player**; swinging lanterns; bobbing lily pads.
10. **Weather mood:** per-biome rain tints (sandy deserts, pale tundra, green swamps), desaturated light, denser fog, puddles
    with ripple rings.
11. **Night magic:** sparse twinkly stars, optional aurora curtains (green to blue), moon phases.
12. **Dimension identities:** per-biome Nether fog colours; a lavender, speckled End with smoky god rays.

---

## 7. Port notes for our Metal/TBDR pipeline

Assumptions, from `apple-gpu.md` and `critique.md`:
- M4 10-core, about 83 GB/s; compressible fragment passes 2-3x cheaper than ShaderWrite or compute.
- The opaque pass is **geometry-bound**, so per-pixel lighting math inside it is close to free.
- Internal resolution 0.5-0.67x of 3024x1964 with our TAAU.
- Reversed-Z (sky = depth 0; BSL tests `z == 1.0`).
- Sodium's 20-byte vertex has **no normals, no block id, no mid-texcoord**.
- The main target is LDR until the M2 HDR redirect.

Bandwidth rule of thumb used below: at 83 GB/s, **1 MB ≈ 0.012 ms** uncompressed. An RG11B10F read+write at 0.67x
internal (2016x1309, 2.64 MP) moves about 21 MB, so about 0.25 ms uncompressed or 0.1 ms compressible.

### 7.1 Data we must synthesize to feed BSL-style shading

| BSL input | Where BSL gets it | Our source |
|---|---|---|
| Face normal | `gl_Normal` | Quad corner fetch in the terrain vertex function (`mcopt-architecture.md` §4.5); M1 is doing this |
| Material class (foliage, leaves, emissive, lava, water, glass, portal, ores) | `mc_Entity` from `block.properties` | **Atlas material mask** R8Uint built at atlas stitch (critique M2 step 2); sample it in the vertex stage for waving and in the fragment stage for shading |
| "Top vertex" for waving | `mc_midTexCoord` | Per-quad: corner index plus UV v-ordering within the sprite rectangle (sprite bounds from the atlas mask or a sprite table) |
| Separated vanilla AO (`separateAo`) | `color.a` | Sodium bakes AO × face shade into RGB. Disable baked face shade (critique M1 step 3). For AO either (a) mixin Sodium to put AO in a spare channel, or (b) accept baked AO in RGB and skip the AO² term. Recommend (a) for the BSL look |
| Sky/block lightmap | `lmCoord` | `a_LightAndData.xy`, available |
| Hand-in-front detection | depth < 0.56 trick | Explicit hand stencil or flag; reversed-Z breaks the trick |
| Previous camera matrices | Iris uniforms | `ShadeFrame` UBO keeps previous view-projection (M1 step 1) |
| Held light value / item | `heldBlockLightValue` | Read in Java and put in the UBO |
| Biome weights | Iris `biome` uniforms smoothed | Java: the biome at the camera, exponentially smoothed per category (cold, dry, damp, …), into the UBO |
| Eye brightness | `eyeBrightnessSmooth` | Java: light level at the camera, smoothed |
| Wetness | `wetness` (30 s / 5 s half-life) | Java: smooth `rainLevel` with the same half-lives |

### 7.2 Effect → our stage → resolution → estimated M4 cost at 3024x1964 output

| Effect (BSL) | Where it goes in our pipeline | Res | Est. GPU ms | Notes |
|---|---|---|---|---|
| Lighting model (3.2): palette, vd, skylight² gating, block curve, emissive flatten, albedo balance, desaturation | M1 terrain fragment (forward), later the M3 TBDR lighting draw | internal | **+0.0-0.1** | Pure ALU in a geometry-bound pass. Put palette scalars in the `ShadeFrame` UBO, precomputed on the CPU per frame (lightCol, ambientCol, sunCol, weather tint, desat tint) so the shader does no colour mixing |
| Sun shadows (3.3) | M1: 2 cached cascades, Depth16, sun-facing faces only; M4: inline RT any-hit ray | internal | M1 **0.7-1.0** avg; M4 **0.05-0.3** | BSL's single distorted 2048² at 256 blocks would cost 2.8-5.2 ms here (measured), so **do not copy**. Keep BSL's *look*: 9 hardware-compare taps on a 1-texel ring (or 4 rotated taps + TAA). With RT: a small cone jitter + TAA gives the same small fixed penumbra |
| Coloured shadows (3.3) | Shadow pass: translucent casters into an RGBA8 colour + second depth; or an RT any-hit intersection function multiplying tint | shadow / internal | +0.05-0.15 (map); +15-20 % of the RT shadow cost | Penumbra-only branch as in BSL. M3 item 4 |
| Leaves SSS (3.4) | Lighting: N·L lift + forward lobe pow16, wider shadow kernel | internal | ~0 | Needs the leaf/foliage class from the atlas mask |
| SSAO (3.5) | Fragment pass on depth (+ normals if stored) at **½ internal**, bilateral upsample inside lighting or the composite | ½ internal | **0.08-0.16** (measured 8 taps at 0.5x/0.67x) | Copy the "pointiness" convex-edge rejection idea. In TBDR, AO must come from *last frame* (reprojected) or from a pre-pass, because lighting runs before depth is resolvable. Simplest: apply AO in the post-opaque composite, like BSL's `deferred1`. RTAO is the 60 fps option |
| Sky (3.9) + sun/moon (3.10) + stars (3.11) | Sky pipeline override (M1 step 6): analytic per pixel; or a 128x64 sky-view LUT recomputed per frame | sky pixels / LUT | **<0.1** | BSL's model is all ALU and fine per pixel. A LUT also feeds fog colour, reflections and the cloud ambient for free. Shader sun disc + moon phases recommended (the BSL default uses sprites) |
| Aurora (3.11) | Into the sky LUT or the sky pass, 12-24 steps | sky LUT | ~0.02 in a LUT | Ridge-function curtains; LUT resolution 256x128 is enough for soft curtains |
| Volumetric clouds (3.12) | Fragment pass at **¼ internal pixels (½ per axis)**, 12-16 steps, BSL-style no-light-march lighting, temporal reprojection + depth-aware upsample; write cloud distance for translucent ordering | ½×½ internal | **0.3-0.6** (est.) | BSL's full-res ≤32-step march would be about 1.5-3 ms native (est.: ~2.4 MP of sky × ~60 fetches). Use 3D noise (a 64³ or 128³ tileable texture) instead of BSL's 2D layer trick. Keep: slab, profile, soft saturation n/√(n²+c), silver lining, sun reveal, distance fade |
| Cloud/sky in reflections | **Reflection probe**: tiny equirect/octahedral (128x64) of sky + clouds updated each frame (or every 2), sampled by water/SSR misses | probe | **~0.05** | Replaces BSL's per-water-pixel cloud march (the biggest BSL inefficiency) |
| Fog (3.13) incl. border fog, height fog, underwater fog | Opaque: in the lighting draw. Translucent: in the water fragment | internal | ~0 | Fog colour from the sky LUT (direction) so it matches the horizon, BSL style |
| Volumetric light (3.14) | Fragment pass at ½ internal, 7-8 exponential steps (BSL spacing), sampling the **far cascade / dedicated VL map** with hardware compare; tint by translucent albedo (needs a translucent-albedo RT or the coloured shadow map); depth-aware upsample + TAA | ½ internal | **0.2-0.35** (measured 16 taps 0.32 ms at 0.5x; 7-8 taps less) | With M4 RT: 4-6 any-hit rays at quarter resolution (est. 0.4-0.8). Keep BSL's hyperbolic time-of-day phase and the ×0.25·lightCol scale. Store in RG11B10F (lossy-compressible), not (x/32)^¼ RGB8 |
| Water (3.15) | M2: translucent pass at the `sodium:translucent_terrain` switch, after copying scene colour + depth (suspend/resume). Normals: 2-octave noise + 4 parallax steps + central differences, flattened at grazing angles | internal, water pixels only | **0.3-0.6** (est., incl. normals) | Exactly BSL's recipe. Water pixels are identified by the translucent pass + water sprite in the atlas mask |
| Water reflections | SSR on water pixels: ½ res, exponential steps + 4 binary refinements, fallback to the probe; M4: 1 RT ray per water pixel | ½ internal / water | **0.3-0.5** SSR; RT ≈ similar and handles off-screen | BSL samples a pre-encoded lit-opaque copy (gaux2). We have the HDR scene copy taken at the translucent switch, so sample that directly |
| Sun glint (GGX area light) | Water fragment + labPBR opaque | internal | ~0 | Re-derive the Decima NoH-for-disc approximation from the original slides; soft-clamp to stay in RG11B10F range |
| Refraction, caustics | Refraction: offset the read of the copied scene; caustics: in the shadow-colour pass (BSL's trick) or analytic in the lighting draw | internal | 0.05-0.1 | M3 |
| Puddles/ripples (3.8) | Terrain fragment, up-facing + skylight + wetness. Without labPBR, drive by world-space noise only | internal | ~0 | Ripple cells are ALU only; needs smoothness → SSR mask, so it only shows if opaque SSR exists (M3+) |
| Waving + grass bend (3.17) | Terrain vertex function (and the shadow vertex function, same code) | – | **+3-10 %** of the vertex-bound terrain pass (est.: one atlas-mask fetch + noise per vertex) | Only evaluate noise for waving classes. Keep varyings ≤ 40 B (`sodium_terrain.vs.metal:6-12`) |
| Hand light, emissive mask, glowing ores | Fragment ALU + UBO | internal | ~0 | Hard-coded emission from HSV of the texel is ideal for us: no labPBR needed, only the emissive class |
| Coloured block light (voxel flood fill) | **Metal compute**: R8Uint voxel ids + 2 ping-pong 3D textures (128×64×128 RG11B10F ≈ 4 MB each), one diffusion step per frame, written by compute (ShaderWrite is fine for volumes); emitters from a CPU-side block-state pass per section (we have no `mc_Entity` in the shadow pass) | volume | **0.05-0.15** (est.) | Impossible in BSL on macOS GL, natural on Metal. A distinctive "better than BSL on Mac" feature (M3+) |
| Bloom (3.19) | Build from the **internal-res HDR before TAAU** (critique: saves the largest full-res read): either BSL's single-pass mip atlas (generate mips with a blit, then one 7-tile pass) or the measured Jimenez 13-tap chain | pyramid | **0.4-0.6** (est. from internal) / 0.74 (measured at output) | Keep BSL's *look*: no threshold, mix about 0.15-0.2, level weights decaying about 2^(−⅓). Lossy-compressed RG11B10F pyramid |
| Auto exposure | Free from the smallest bloom level or the 1x1 mip; centre-weighted; exponential adaptation | – | ~0 | BSL default is fixed exposure; the BSL look is tuned for fixed exposure ×4. Offer auto as an option |
| Tonemap + grade + vignette + grain + saturation (3.19) | **One final fragment pass** to the drawable (or RGBA16F EDR layer) | output | **0.35** (measured) | Offer "BSL-style" (crosstalk matrix → per-channel Reinhard-2 → inverse matrix) **and** AgX. The BSL curve is what gives its signature roll-off; it is easy to re-derive from the description in 3.19. XDR mode: raise the Reinhard asymptote to the EDR headroom |
| Lens flare (3.19) | Draw ~20 additive **sprite quads** along the flare axis (or evaluate in a 256-px-tall buffer), visibility from a 1-texel depth probe at the sun position, smoothed | output | **~0.02** | BSL evaluates ~40 analytic shapes per screen pixel (est. 0.3-0.5 ms at native), so don't copy that literally |
| AA: FXAA + TAA + sharpen | **Our TAAU** (fragment, compressible history) with built-in RCAS-like sharpening; no FXAA | output | **0.62-0.72** (measured) | Borrow BSL's choices: YCoCg clip, Catmull-Rom history, **modest history weight** (sharp, low ghost); tonemapped/"Karis-weighted" resolve for highlight stability (BSL gets this by running TAA after the tonemap) |
| Motion blur, DOF | Optional post passes at internal res | internal | 0.2-0.5 each (est.) | Low priority; DOF's mip-LOD bokeh trick is a good cheap recipe |
| Nether/End | Dimension branch in the lighting draw, sky and fog | – | ~0 | Constants from the palette; End sky speckle as a cube-mapped noise lookup |

### 7.3 Frame budget for a "BSL-equivalent" frame (0.67x internal → 3024x1964, 120 Hz target 8.33 ms)

| Block | ms (est., built from the measured items in `apple-gpu.md` §7.1) |
|---|---|
| Opaque terrain + forward/TBDR lighting + fog (geometry-bound) | 1.5-1.9 |
| Entities, hand | 0.3-0.8 |
| Shadows: M1 cached cascades / M4 RT | 0.7-1.0 / 0.1-0.3 |
| Sky LUT + reflection probe | 0.1 |
| Clouds (¼ pixels) | 0.3-0.6 |
| SSAO (½) | 0.15 |
| VL (½) | 0.25 |
| Water + reflections | 0.6-1.0 |
| TAAU | 0.7 |
| Bloom (from internal) | 0.5 |
| Final (tonemap + grade + flare sprites) | 0.4 |
| GUI + present | 0.3 |
| **Total** | **≈ 6.1-8.5 ms (M1-style shadows) / 5.5-7.8 ms (RT shadows)** |

Use dynamic resolution between 0.5x and 0.67x to hold 120 Hz in heavy scenes (water + clouds + rain).

### 7.4 Milestone mapping

- **M1 (in progress, don't touch).** Adopt the following when tuning, because they are cheap:
  - the BSL lighting equation and palette (3.1-3.2): sqrt-space colours, skylight² gating, vd factor, block-light curve,
    darkness desaturation;
  - `sunPathRotation −40°` plus the sun-angle easing;
  - shadowFade at the sun/moon handover;
  - the sky model with matching fog colour.

  These are all UBO and ALU changes.
- **M2.** Must-haves for BSL parity:
  - HDR RG11B10F main scene (BSL proves 4 B/px is enough, with dithering);
  - the atlas material mask (unlocks waving, leaves SSS, emissive, water);
  - BSL water;
  - VL at ½ res;
  - SSAO;
  - non-thresholded bloom;
  - BSL-style tonemap option;
  - TAAU.
- **M3.** Volumetric clouds with a reflection probe, coloured shadows and VL tint, rain puddles and ripples, entities lit
  consistently, shader sun/moon with phases, aurora. Optional voxel coloured light.
- **M4.** RT shadows keep BSL's penumbra look (cone + TAA); RT water reflections; RTAO.

### 7.5 Pitfalls when re-implementing BSL ideas on our stack
- **Reversed-Z.** Every "sky if depth == 1" test becomes "depth == 0". Linearization differs. The hand range trick does not
  work.
- **Encodings.** BSL's 4th- and 8th-root encodings exist because of RGB8/RGB10 targets. In Metal, use RG11B10F or RGBA16F
  compressible targets and keep values linear, unless tile memory is the constraint.
- **Post-tonemap TAA.** BSL tonemaps *before* TAA. We put TAAU before tonemap (MetalFX and bloom need HDR), so use luma-
  weighted (Karis) accumulation to avoid highlight fireflies.
- **Ordering.** BSL's deferred1 (fog, clouds, AO apply) runs before translucents, and translucents apply their own fog. Our
  TBDR plan has the same order: opaque lighting + fog → clouds composite → translucent pass → VL → post.
- **Per-frame scalars in pixels.** BSL stores exposure, sun visibility and DOF focus in corner pixels of a history texture.
  On Metal, use a tiny buffer written by a 1-thread compute or fragment pass.
- **Waving parity.** Apply the same displacement in the camera, shadow and (later) BLAS paths, or shadows detach from
  plants. For RT, exclude waving foliage from the static BLAS, or accept static shadows for leaves.

---

## 8. Things not to copy (BSL limitations we can beat on Metal)
- Full-resolution volumetric clouds, VL and SSAO. Use reduced resolution plus temporal reuse instead.
- Per-pixel volumetric cloud marching inside water reflections. Use a probe.
- A single huge shadow map redrawn every frame. Use cached cascades or RT.
- Three serial AA passes. Use one TAAU.
- Camera-only reprojection: entities and water ghost. Use per-object motion (apple-gpu §3.3).
- Coloured light only via GL 4.3 compute, disabled on Mac. Use Metal compute.
- An LDR 8-bit final. Offer an EDR/XDR output.

---

## 9. Public techniques BSL credits or uses (safe to implement from the originals)
These are the *original public sources* behind BSL's building blocks. We can implement from them directly; we must not
transcribe BSL's versions.
- FXAA 3.11 (Timothy Lottes / NVIDIA; BSL cites Simon Rodriguez's write-up).
- Catmull-Rom 5-tap history sampling (Jimenez, *Filmic SMAA*, SIGGRAPH 2016).
- AABB clipping in YCoCg (Karis, *High Quality Temporal Supersampling*, 2014; Salvi).
- Camera reprojection and the 8-phase jitter (Chocapic13's community shaders).
- Light-shaft approach (Robobo1221).
- GGX area-light NoH approximation for a disc light (Guerrilla, *Decima Engine: Advances in Lighting and AA*, SIGGRAPH 2017).
- Interleaved gradient noise (Jimenez, *Next Generation Post Processing in Call of Duty: AW*, 2014).
- Bayer dithering ("from Jodie").
- Spheremap normal encoding (Aras Pranckevičius, *Compact Normal Storage*).
- Generalized Reinhard / matrix-sandwich tonemapping (Reinhard 2002; the inset/outset idea also appears in AgX and ACES fits).
- Complex Fresnel for conductors (standard optics: n, k per channel).

---

## Appendix: file index of the BSL pack (for later lookups)

| Topic | Files |
|---|---|
| Settings and options | `lib/settings.glsl`, `shaders.properties`, `lang/en_US.lang` |
| Formats and constants | `program/final.glsl:23-44` |
| Shadow pass | `program/shadow.glsl`; lookup `lib/lighting/shadows.glsl` |
| Lighting | `lib/lighting/forwardLighting.glsl`, `lib/color/{lightColor,blocklightColor,skyColor,lightSkyColor,dimensionColor,specularColor}.glsl` |
| Terrain / translucent / entity programs | `program/gbuffers_{terrain,water,entities,hand,block,textured,weather,clouds,skybasic,skytextured}.glsl` |
| AO | `program/deferred.glsl`, `lib/lighting/ambientOcclusion.glsl`; apply `program/deferred1.glsl` |
| Sky, sun, moon, stars, aurora, clouds | `lib/atmospherics/{sky,sunmoon,clouds}.glsl`, `lib/color/auroraColor.glsl` |
| Fog | `lib/atmospherics/{fog,waterFog,weatherDensity,chunkFade}.glsl` |
| VL | `lib/atmospherics/lightShafts.glsl`, `program/composite.glsl`, `program/composite1.glsl` |
| Reflections | `lib/reflections/{raytrace,simpleReflections,roughReflections,complexFresnel,rainPuddles}.glsl` |
| Materials | `lib/surface/{materialGbuffers,materialDeferred,parallax,directionalLightmap,ggx,hardcodedEmission}.glsl` |
| Coloured light | `program/shadowcomp.glsl`, `lib/lighting/{coloredBlocklight,voxelColorFetch}.glsl`, `lib/util/voxelMap*.glsl`, `block.properties` |
| Waving | `lib/vertex/waving.glsl` |
| Post | `program/composite{2,3,4,5,6,7}.glsl`, `program/final.glsl`, `lib/post/{lensFlare,outline}.glsl`, `lib/antialiasing/{taa,fxaa}.glsl`, `lib/util/jitter.glsl` |
| Noise texture | `tex/noise.png` (512², R Perlin, G Worley, B blue noise, A blocky), `tex/dirt.png` (dirty lens) |
