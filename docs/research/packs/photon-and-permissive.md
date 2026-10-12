# Photon Shaders (sixthsurge) + survey of permissively licensed packs — deep study for a native Metal pipeline

Report name: `photon-and-permissive`. Research only. Nothing in mcopt, the M1 worktree or the user's instances was touched;
Minecraft was not launched and no GPU benchmarks were run.

Sources (all official):
- Photon, current `main` @ `15458c09` (2026-07-26), cloned from `github.com/sixthsurge/photon` into
  `work/packs/photon/`.
- Photon, **last MIT-licensed commit** `26c2906e` (2025-01-09), downloaded from the same GitHub repository as a tarball
  into `work/packs/photon-mit-26c2906e/`.
- Spectrum (Zombye, MIT) @ `a1328f03` (2026-08-19), cloned from `github.com/zombye/spectrum` into
  `work/packs/spectrum/` (for the permissive survey).
- License metadata of the other packs: the Modrinth API (`api.modrinth.com/v2/project/<slug>`) and the LICENSE files of
  the authors' GitHub/Codeberg repositories, read through `gh api`.

All file paths below are relative to `work/packs/photon/shaders/` unless they start with another pack directory.

---

## 0. TL;DR

1. **Photon's current license is NOT permissive.** Since commit `2f54e66b` (2025-01-10) it is a custom
   "Photon Shaders License Agreement": all rights reserved, except that you may use it, modify it for personal use,
   learn from it, and redistribute modified or unmodified portions — but **not** on platforms that pay creators
   (Modrinth, CurseForge are named) and not for money, and the license text must accompany substantial copies.
2. **Photon was MIT from 2022-06 to 2025-01-09.** The tree at commit `26c2906e` (2025-01-09, "workaround for sun and
   moon issues in 1.21.4") ships an MIT `LICENSE` ("Copyright © 2025 Benjamin Stott 'sixthsurge'"). An MIT grant cannot
   be revoked for copies already published, so **that snapshot is legally adaptable with attribution** (keep the MIT
   notice). It already contains almost every core technique: precomputed atmosphere LUT, 5-layer volumetric clouds with
   checkerboard temporal upscaling, PCSS + coloured shadows + water caustics, GTAO with bent normals, ray-marched air and
   water fog, SSR, Gerstner water, TAA/TAAU, CoD-style bloom, Lottes tonemap + HSL grade, Purkinje shift, aurora, stars,
   LPV coloured lights. The atmosphere LUT binary `image/atmosphere/scattering.dat` is byte-identical between the MIT
   snapshot and today (md5 `d02a0e76…`), so even the data is MIT-available.
   Recommendation: **adapt from `photon-mit-26c2906e` only, and treat post-2025 Photon as study material** (re-implement
   in our own words). Exclude the pieces Photon itself only holds by permission (Complementary's shadow bias, Lux and
   Ozius tonemaps; §1.3).
3. **Best permissive alternative: Spectrum by Zombye (MIT, still actively developed in Aug 2026).** It is a
   high-end, physically based pack: Bruneton atmosphere, 2D+3D clouds, RSM GI, HBAO, PCSS, water caustics, a custom
   perceptual display transform, a physical camera (ISO/shutter/f-stop), histogram auto exposure. Other MIT packs (Mellow, Glimmer,
   Miniature, RenderPearl, Arc) are lower quality or stylised. The other top-quality open packs (Noble, Alpha Piscium,
   Sundial Lite) are **GPL-3**, so they are reference only for our Apache-2.0/PolyForm code. Bliss, Solas, Super Duper
   Vanilla, Nostalgia, Kappa, Lux, Shrimple and Visual Vibrance are all restrictive.
4. **What makes Photon beautiful** is a physically based core with deliberate art direction layered on top:
   - the working space is Rec.2020, and the sunlight colour comes from real transmittance;
   - skylight is an SH projection of the actual sky, so ambient light has direction and colour;
   - a procedural weather system changes the clouds every day;
   - clouds use an 8-octave multiple-scattering approximation with powder and aerial perspective;
   - shadows are soft (PCSS), grow softer under cloud shadow, and are tinted through stained glass;
   - water is nearly invisible: absorption-driven colour plus exact dielectric Fresnel;
   - leaves and grass get a backlit "sheen";
   - a calm Lottes tonemap is followed by a gentle teal-saturation grade;
   - many "magic" multipliers (blue-hour light ×40, evening fog glow, night Purkinje shift) are tuned by eye.
5. **Why Photon is slow on this Mac:** it runs about 27 full-screen passes and keeps about 60 B/px of full-resolution targets.
   The colour HDR alone is small (R11G11B10F), but the G-buffer is 2×RGBA16, and the cloud history (RGBA16F + RGB16F),
   TAA history and translucent layer are all full resolution. At 3024×1964 that is about 1–1.2 GB of traffic per frame,
   which is 11–14 ms on the M4 at 83 GB/s (apple-gpu §1.3). The README says plainly "NOT Apple Metal"; the user is told to
   disable *SH Skylight* (compute) and *Colored Shadows*.
6. **Port verdict:** every Photon effect maps cleanly onto our TBDR design. The full Photon look costs about 4–6.5 ms on
   top of vanilla geometry at 0.5–0.67x internal resolution with our TAAU (table in §6.3). That fits a 120 fps budget with
   dynamic resolution, as long as we:
   - render clouds at ¼ resolution and keep their history at ½;
   - put the lighting inline in the TBDR pass;
   - compute GTAO after the main pass and consume it reprojected next frame;
   - merge Photon's ~12 post passes into about 4 (TAAU, bloom down/up, final).

---

## 1. Licensing (verified from the official repositories)

### 1.1 Photon license timeline (`gh api repos/sixthsurge/photon/commits?path=LICENSE`)

| Commit | Date | License |
|---|---|---|
| `6e295f42` | 2022-06-15 ("let there be light") | MIT, © 2022 SixthSurge |
| `aa9627d3` | 2023-03-02 | MIT, © 2023 SixthSurge |
| `4d06de43`, `b7a37418` | 2024-08-12, 2025-01-08 | MIT, © 2025 Benjamin Stott "sixthsurge" |
| **`26c2906e`** | **2025-01-09** | **last tree with MIT `LICENSE`**, the parent of `2f54e66b` |
| `2f54e66b` | 2025-01-10 ("fix missing refractions, update license") | custom "License Agreement" |
| `f25d834c` | 2025-06-21 | the same custom license, reworded ("Photon Shaders License Agreement") |

Current terms (`photon/LICENSE`):
- **Allowed:** using it; modifying it for personal use; examining and learning from the code; redistributing modified
  or unmodified *portions*.
- **Not allowed without written permission:** redistributing on platforms that give creators direct or indirect money
  (Modrinth and CurseForge are named), or asking money for copies.
- **Also:** bundling in modpacks is allowed; the license text must be "included in all substantial copies".
- GitHub reports SPDX `NOASSERTION`. Modrinth lists `LicenseRef-` and links back to this file.

Implications for us:
- Our fork code is Apache-2.0, and mcopt-derived files are PolyForm Shield (critique §6.2). Code adapted from current
  Photon would carry the Photon terms, which forbid putting the result on Modrinth or CurseForge. That makes it unsafe for
  anything that might be published later.
- **Adapt only from the MIT snapshot `photon-mit-26c2906e`**, keeping its MIT notice in a `THIRD_PARTY_NOTICES` file
  and in file headers ("Portions adapted from Photon Shaders by sixthsurge, MIT, commit 26c2906e").
- Post-2025 additions (SSRT shadows, crepuscular rays, rainbows, the temperature/humidity/wind weather model, the
  precomputed cloud coverage map, compute SH skylight, Nvidia-phase sun halo) are described below only as techniques.
  Implement them fresh.

### 1.2 What the MIT snapshot contains (adaptable, with attribution)

Paths in `work/packs/photon-mit-26c2906e/shaders/`:

| Module | File(s) | Lines |
|---|---|---|
| Atmosphere: LUT lookup, Chapman transmittance, density/ozone | `include/sky/atmosphere.glsl` + `image/atmosphere/scattering.dat` (32×64×32 RGB16F) | 402 |
| Sky composition (sun disc + limb darkening, moon phases, galaxy, stars) | `include/sky/sky.glsl`, `include/sky/projection.glsl` | 294 + 57 |
| Clouds (cumulus, altocumulus, cumulus congestus, cirrus/cirrocumulus, noctilucent) | `include/sky/clouds/*.glsl`, `include/sky/clouds.glsl`, `program/d1_clouds.*`, `program/d2_clouds_upscaling.*` | about 1,330 |
| Daily weather table (older system) | `include/misc/weather.glsl`, `weather_struct.glsl` | 330 |
| Cloud shadows | `include/lighting/cloud_shadows.glsl` | 100 |
| Aurora | `include/sky/aurora.glsl` | 78 |
| Shadows (distortion, PCSS, coloured shadows) | `include/lighting/shadows.glsl`, `include/lighting/distortion.glsl` (**minus the Complementary bias function**) | 278 + 49 |
| Lighting (diffuse, SSS, bounced light, block-light falloff, hand light, cave light) | `include/lighting/diffuse_lighting.glsl`, `handheld_lighting.glsl`, `colors/*.glsl` | about 400 |
| BSDF and specular (GGX, Hammon diffuse, sphere-light NoH, SSR) | `include/lighting/bsdf.glsl`, `specular_lighting.glsl`, `include/misc/raytracer.glsl` | 81 + 331 + 73 |
| GTAO with bent normals, SSAO | `include/lighting/ao/gtao.glsl`, `ssao.glsl`, `program/d3_ao.*` | 186+ |
| Fog: air VL, water VL, End, simple | `include/fog/*.glsl`, `program/c0_vl.*` | about 650 |
| Water normals (Gerstner + parallax), puddles | `include/misc/water_normal.glsl`, `rain_puddles.glsl` | 122 + 81 |
| Hardcoded material table | `include/misc/material.glsl` | 643 |
| TAA/TAAU, exposure, bloom, grade + tonemap, FXAA, CAS | `program/c3_*`, `c4_*`, `c5_c10_*`, `c11–c13_*`, `c14_color_grading.*`, `c15_fxaa.*`, `final.*` | — |
| LPV flood-fill coloured lights | `include/lighting/lpv/*.glsl`, `program/lpv/*` | about 270 |
| SH utilities | `include/utility/spherical_harmonics.glsl` | 108 |
| Phase functions, colour matrices, random/blue-noise helpers | `include/utility/*.glsl` | — |

### 1.3 Third-party pieces *inside* Photon, and how to treat them

| Piece | Where | Status for us |
|---|---|---|
| Shadow bias and edge light-leak fix "from Complementary Reimagined by Emin… used with permission" | `include/lighting/shadows/distortion.glsl`, `get_shadow_bias`; `pcss.glsl` edge_factor (also in the MIT snapshot, `include/lighting/distortion.glsl:31`) | **Do not copy.** The permission was personal to Photon. Write our own normal-offset + slope bias (and use RT shadows later) |
| Fancy nether portal "inspired by Complementary… idea used with permission" | `program/gbuffers_all_translucent.fsh` | Skip, or design our own |
| `tonemap_tech` (Lux) and `tonemap_ozius` (Zombye, via Jessie) | `include/post_processing/tonemap_operators.glsl` | Do not use; provenance is unclear. Lottes 2016, Hejl, Uncharted 2 and ACES are published formulas |
| ACES RRT/ODT | `include/post_processing/aces/*` + `license.md` (AMPAS) | Permissive with a notice; we do not need it (use AgX or Lottes) |
| Bruneton 2017/2020 mapping functions | `atmosphere.glsl` comments | Bruneton's code is BSD-3; re-deriving the math is fine |
| Catmull-Rom 5-tap (MJP, MIT) | `include/utility/bicubic.glsl:51` | MIT, fine with a notice |
| Hardcoded metal f0/f82 table (Jessie) | `include/surface/material.glsl:40-60` | Physical constants (data). Fine to re-derive from refractive-index sources |
| Blue-noise texture `image/noise.png` (momentsingraphics.de) | `image/` | Christoph Peters' blue-noise textures are released as CC0. Fine; still credit them |
| Galaxy image (NASA SVS 4851) | `image/galaxy.png` | Credit NASA; NASA imagery is generally free to use |

---

## 2. Survey of permissively licensed packs (verified licenses)

"Quality" is my assessment from the feature lists, code and community standing; nothing was run in game.

| Pack (author) | License (source) | Quality level | Notable effects / why it matters to us |
|---|---|---|---|
| **Photon @ `26c2906e`** (sixthsurge) | **MIT** (repo LICENSE at that commit) | **Top tier** (27.7M Modrinth downloads today) | Everything in §3–4. The best adaptable code base for our goals |
| Photon @ `main` | custom; no monetised redistribution (repo LICENSE) | top tier | Study only (§1.1) |
| **Spectrum** (Zombye) | **MIT** (`zombye/spectrum/LICENSE`, © 2017-2018 Jacob Eriksson; commits up to 2026-08-19) | **High, physically based, "natural"**; alpha status; Nvidia-tested | Bruneton atmosphere (`include/shared/atmosphere/*`); 2D + 3D clouds plus a cloud shadow map; PCSS; RSM GI (`program/rsmCompute.glsl`); HBAO; water caustics (`include/fragment/waterCaustics.fsh`); LOD distant waves; physical camera (sensor, ISO, shutter, f-stop, iris blades, anamorphic); circular-kernel DOF; 128-bin histogram auto exposure; SH skylight compute; and a **custom perceptual display transform** with absolute-luminance colourfulness, LUT-accelerated (`include/color/display_transform/*`). Photon itself borrows Spectrum's nu-mapping. Contains a Dave Hoskins hash under CC-BY-SA (`include/utility/noise.glsl:74`, avoid it) and the Kleber Garcia circular DOF (MIT) |
| Mellow (TheCMK) | MIT (Codeberg `TheCMK/mellow-shader`, © 2026) | Low-end, pleasant stylised; 7.2M dl, updated for 26.3 | Cheap shading tricks for a "Performance" tier |
| Glimmer (jbritain) | MIT (repo, SPDX MIT) | Medium; "simple and performant"; presets from Potato to Dedicated | Hillaire sky (via a Shadertoy port), LabPBR 1.3, SSR, infinite ocean plane, 2D clouds. **Provenance caveat:** its README credits Complementary's shadow bias and "a decent amount of code reference from Photon". Do not copy those parts |
| Miniature (mateuskreuch) | MIT | Stylised, low-end, path-tracing tag | Minimal reference |
| RenderPearl (Luracasmus) | MIT | Lightweight, modern Iris features; updated for 26.3 | Not related to Mojang's renderpearl. Good LabPBR handling table |
| Arc (Null-MC) | MIT (© 2022) | Medium-high but **stale** (1.19.4, 2023) | RSM GI, atmosphere; older code |
| KUDA (Titan1um) | BSD-3-Clause (repo) | Medium; "inspired by DeDelner" | Coloured lighting, fog, grading |
| Musk Rose (Rinloid) | CC0-1.0 | Medium; last updated 2024 | Public-domain reference |
| TrueLightFX (OGCRAFTNET) | MIT | Unknown, 0 stars | Not worth studying |
| Vulkanite Demo Pack (BalintCsala) | MIT (2023) | Path-traced demo for the Vulkanite mod | **Hardware-RT shading reference** for our M4 tier (BLAS/TLAS-style traversal from Minecraft data) |
| Phoxel PT (Despatra) | Apache-2.0 on Modrinth, but **no LICENSE file in the repo** | Path tracing | Unclear; skip |
| Noble (Belmu) | **GPL-3.0** | High (DH/Voxy support, physically based) | Reference only (copyleft) |
| Alpha Piscium (Luna5ama) | **GPL-3.0** | **Very high**: ReSTIR SSGI + ReBLUR, Hillaire sky + epipolar sampling, Nubis-style clouds, RTWSM shadows | Reference only. Its technique list is a good roadmap for our Ultra tier |
| Sundial Lite (GeForceLegend) | GPL-3.0 | High-ish, realistic PBR | Reference only |
| Escheridia, VanillaPuddingTart, MakeUp (LGPL), Ebin Resurrected (AGPL) | copyleft | medium | Reference only |

Restrictive packs, verified, **not adaptable**:
- **Bliss** (X0nk): `LICENSE.md` = "© 2020 Chocapic13. All rights reserved".
- **Solas** (Septonious): custom "All Rights Reserved unless otherwise explicitly stated".
- **Super Duper Vanilla** (Eldeston): "FlameRender (C) Studios License v1.6"; modifications for personal use only.
- **Nostalgia, Kappa, Vanilla Plus, Potato** (RRe36): ARR, no public source.
- **Lux** (Tech): `AGREEMENT.txt`.
- **Shrimple** (Null-MC): "All Rights Reserved"; note that Null's older **Arc is MIT**.
- **Visual Vibrance** (jbritain): Photon-style custom license.
- **Complementary / Rethinking Voxels / Spooklementary / Allium / PixelCraft**: Complementary license.
- **BSL**: ARR. **SEUS**: covered by another report.

Bottom line: for code adaptation, use **Photon @ 26c2906e (MIT) as the primary donor and Spectrum (MIT) as the second**.
Spectrum is the donor for camera/exposure, the display transform, RSM and caustics. Everything else is reference only.

---

## 3. Photon architecture (current `main`)

### 3.1 Program graph and buffers

Iris loads the per-dimension wrappers `world0|world-1|world1/<stage>.{vsh,fsh,csh}`. Each one `#define`s
`WORLD_OVERWORLD` (etc.) plus `PROGRAM_*` and includes the shared source in `program/`. Frame order (Overworld):

| # | Stage → program | Resolution | Writes | Purpose |
|---|---|---|---|---|
| 1 | `prepare` → `p0_clouds_prep` | 512² (`colortex8`) | RGB8 | Cloud shadow map (xy) + precomputed cumulus coverage map (z) |
| 2 | `shadow` → `shadow.{vsh,fsh}` | 2048² default | shadowtex0/1, shadowcolor0 (RGB) | Distorted sun shadow map; tinted translucents; water absorption + caustics |
| 3 | `gbuffers_terrain/entities/hand/block…` → `gbuffers_all_solid` | internal | colortex1 (RGBA16), colortex2 (RGBA16) | Packed G-buffer |
| 4 | `deferred` → `d0_sky_map` | 192×108 (`colortex4`) | RGBA16F | Omnidirectional sky view map with clouds and fog; column 191 stores light colour, ambient colour, 9 SH coefficients and "skylight up" |
| 5 | `deferred1` → `d1_clouds` | ¼ × ¼ of internal (`colortex9/10`) | RGBA16F + RG16F | Clouds, crepuscular rays, aurora, ray-marched in a 4×4 checkerboard |
| 6 | `deferred2` → `d2_clouds_upscaling` | full (`colortex11/12`) | RGBA16F + RGB16F | Temporal reconstruction of clouds |
| 7 | `deferred3` → `d3_ao` | ½ (`colortex6/14`) | RGBA16 + RG16F | GTAO (AO, ambient-SSS, bent normal) with temporal accumulation |
| 8 | `deferred4_a` → `d4a_generate_sky_sh.csh` | 1 workgroup × 256 threads | image writes to colortex4 | Projects the sky map onto order-2 SH |
| 9 | `deferred4` → `d4_deferred_shading` | full | colortex0 (R11G11B10F) | Lighting of opaques; sky for depth = 1; fog; clouds in front |
| 10 | `gbuffers_water/textured/particles…` → `gbuffers_all_translucent` | full | colortex13 (premultiplied RGBA16F), colortex3 (refraction normal) | Forward-shaded translucents |
| 11 | `composite` → `c0_vl` | ½ (`colortex6/7`) | RGBA16 + RGB16F | Volumetric fog transmittance + in-scattering |
| 12 | `composite1` → `c1_blend_layers` | full | colortex0 | Refraction, translucent blend, fog behind translucents, clouds in front of translucents, VL application |
| 13 | `composite2` → `c2_dof` | full | | Optional DOF (Vogel disc, 40 taps) |
| 14 | `composite3` → `c3_taau_prep` | full | colortex1/2 | (TAAU only) per-pixel YCoCg AABB min/max |
| 15 | `composite4` → `c4_taa_exposure` | output | colortex5 (RGBA16F history; a = pixel age, texel (0,0).a = exposure) | TAA/TAAU + exposure (vertex stage) |
| 16–21 | `composite5..10` → `c5_c10_bloom_downsample` | 6 tiles in an atlas inside colortex0 | | 13-tap CoD downsample |
| 22 | `composite11` → `c11_bloom_merge_buffers` | | | Merges the odd and even tile ping-pong |
| 23–24 | `composite12/13` → `c12/c13_bloom_gaussian_*` | | | Separable 9-tap binomial blur on all tiles at once |
| 25–29 | `composite14..18` → `c14_c18_bloom_upsample` | | | Progressive upsample + blend |
| 30 | `composite19` → `c19_color_grading` | output | colortex0 | Bloom mix, exposure, vignette, grade in, tonemap, grade out |
| 31 | `composite20/21` | output | | Optional motion blur, FXAA |
| 32 | `final` | output | backbuffer | CAS sharpen (or Catmull-Rom upscale), sRGB, Bayer-16 dither to 8 bit |

Buffer layout documentation: `include/buffers.glsl`. Sizes and flips: `shaders.properties` "Buffer Sizes".

### 3.2 G-buffer packing (`program/gbuffers_all_solid.fsh`)

It is very compact, 16 B/px total, written to two RGBA16 targets:
- `colortex1`:
  - x = albedo.rg (2×8 bit);
  - y = albedo.b + material id/255;
  - z = flat normal, octahedral 2×8;
  - w = block light + sky light, 2×8, **dithered** with interleaved-gradient noise so TAA hides the banding.
- `colortex2` (only with normal or specular maps): the detailed normal (oct) + the LabPBR specular (4×8).

Vanilla AO is multiplied into the albedo at write time:
- the curve is `lift(ao, 0.5)·0.9 + 0.1` when shader AO is on;
- leaves get no vanilla AO.

The material id comes from `block.properties` (mc_Entity − 10000).

### 3.3 Units, colour and conventions

- **Working space Rec.2020 linear** (`include/utility/color.glsl`); textures go through sRGB EOTF⁻¹ then 709→2020. The
  output is converted back to 709 after tonemapping. This gives more saturated, cleaner sunsets and less hue skew in
  bright light than Rec.709 maths.
- Luminance weights are Rec.2020 (0.2627, 0.6780, 0.0593).
- Depth is standard (sky = 1.0). In our reversed-Z world, sky = 0.0, so every `depth == 1.0` test flips.
- The TAA jitter is the **R2 sequence**:
  - offsets `frac(1.3247·n + 0.5)·2−1` and `frac(1.7549·n + 0.5)·2−1`, divided by the viewport width (`shaders.properties` "Custom Uniforms");
  - scaled by 0.66 for native TAA, by 1 for TAAU.
- Noise: a 512² blue-noise texture decorrelated per frame with an R1 sequence (`r1(frameCounter, noise)`), plus
  interleaved-gradient noise.

---

## 4. Effect-by-effect deep dive

### 4.1 Atmosphere and sky LUTs (`include/sky/atmosphere.glsl`, `program/d0_sky_map.*`, `include/sky/projection.glsl`)

**Model.** Planet radius 6371 km, atmosphere from −1 km to +110 km.
- Rayleigh scale height 8.4 km, Mie 1.25 km.
- Coefficients are given per Rec.709 primary and transformed to Rec.2020:
  - Rayleigh β ≈ (8.06, 16.7, 40.8)×10⁻⁶ m⁻¹;
  - Mie ≈ 1.7–2.0×10⁻⁶;
  - ozone absorption ≈ (0.83, 1.31, 0.054)×10⁻⁶.
- Mie single-scattering albedo is 0.9.
- **Ozone density** is not the usual tent: it is a sum of four Gaussians in altitude (km), centred near 0, 18, 23.5 and 30 km with
  widths of about 8, 80, 50 and 150 (a fit by Jessie).
- **Sunlight in space** = (1.051, 0.985, 0.940), derived from the AM0 spectrum through CIE 2006 cone fundamentals.

**Scattering LUT.** It is a precomputed 3-D table shipped as data (`image/atmosphere/scattering.dat`, 393 KB, declared in
`shaders.properties` as `TEXTURE_3D RGB16F 32 64 32`). Axes:
- **ν** (view–sun cosine), 16 cells, with **two halves side by side**: x < 0.5 holds Rayleigh + multiple scattering
  (phase baked in), x ≥ 0.5 holds single Mie *without* phase. This lets the shader apply its own Mie phase per pixel.
- **μ** (view zenith), 64 cells.
- **μs** (sun zenith), 32 cells.

The parameterisation is Bruneton's:
- **μ** is mapped through the distance to the ground or to the top of the atmosphere, with separate halves for rays that
  hit the ground and rays that don't.
- **μs** uses Bruneton's ad-hoc `max(0, 1 − a/A)/(1 + a)` warp, with a minimum sun elevation μs = −0.35 so twilight is covered.
- **ν** uses Zombye's (Spectrum's) improved mapping: it is re-normalised to the range of ν that is reachable for the given μ and μs
  (ν ∈ μ·μs ± √((1−μ²)(1−μs²))), so no texels are wasted.

Two artistic tweaks happen before the lookup:
- **Horizon stretch:** for μ > 0, μ ← μ·μ^0.25. This pulls the colourful band upward "to admire the sunset without zooming in".
- **Horizon clamp:** μ is clamped to a small positive "horizon_mu" that rises from −0.01 to 0.03 as the sun rises. Without
  `SKY_GROUND`, the lower hemisphere copies the horizon colour instead of showing a dark planet.

**Transmittance** is not a LUT by default. It uses the analytic **Chapman-function approximation**
(thetenthplanet.de): airmass = H·ρ(r)·Ch(r/H, μ), with Ozone riding on the Rayleigh airmass, and T = exp(−β·airmass).
That is cheap enough to evaluate per cloud sample.

**Phase functions** (`include/utility/phase_functions.glsl`):
- **Sky Mie phase:**
  - for the pixel sky: **Klein–Nishina** (energy parameter e = 3000), which gives a tight, physically plausible forward
    peak and a very bright, tight glow hugging the sun;
  - for ambient queries: Henyey–Greenstein with g = 0.77.
- **Sun halo** (newer Photon): an "**area** Nvidia phase". It is a Cornette–Shanks-like lobe evaluated at the angle to
  the *edge* of the sun disc instead of its centre, so the glow starts at the limb. It uses per-channel g = 0.85/0.86/0.87,
  which makes the halo slightly bluer outside and warmer near the sun, and is scaled ×0.2.
- **Moon halo:** the same idea with g ≈ 0.90, modulated by moon phase.

**Post-processing of the atmosphere:**
- **saturation boost** of the atmosphere colour: (1 + 0.1 + 0.2·blue_hour). Blue hour is a Gaussian in sun height centred at
  sun_y ≈ −0.073, so the sky gets richer just after sunset;
- optional desaturation in rain.

**Sky view map (`d0_sky_map`, 192×108 RGBA16F; one column is reserved for constants).** The sky is rendered every frame
into Hillaire's (EGSR 2020) latitude-longitude parameterisation:
- the altitude coordinate is non-linear (v ∝ √(|altitude|/(π/2))), which concentrates texels near the horizon;
- there is a 2-pixel azimuth padding for wrap filtering.

Each texel contains the full sky: atmosphere + clouds (half step counts) + aurora + crepuscular rays + analytic fog. It is
used for:
1. sky reflections (bicubic sampled);
2. the border fog colour near the horizon;
3. **SH skylight**: a compute shader with 256 threads takes R2-distributed hemisphere samples of this map, projects them
   onto 9 SH coefficients (order 2) through a shared-memory parallel reduction, and stores them plus the up-facing
   irradiance in column 191 of the same texture.

The vertex stage of `d0` computes, once per frame:
- `light_color` = sunlight × transmittance(sun elevation) × "sun exposure" × tint;
- `ambient_color` = 2π·1.13 × the sky radiance in direction normalize(0, 1, −0.8). It is tilted away from the zenith to
  avoid the sun halo when sunPathRotation = 0.

**Sun/moon exposure "magic"** (`include/lighting/colors/light_color.glsl`). This replaces auto exposure, which is OFF by default:
- **Sun base:** 7 × SUN_I × (1 + 0.5·(sunrise + sunset) + **40·blue_hour**), where blue_hour = exp(−190·(sun_y + 0.096)²).
  Direct light is therefore boosted massively while the sun is just below the horizon, which keeps twilight bright and colourful.
- **Sun tint:**
  - morning/evening tint (1.05, 0.84, 0.93)·1.2, pulsed around sun_y 0.17–0.40;
  - blue-hour tint (0.95, 0.80, 1.0);
  - user tints per time of day.
- **Moon:** 0.66 × phase brightness (1.0 down to 0.5 at new moon) × (1 + 0.33/clamp(1.25·max(−sun_y, 0.1))), so it is brighter
  early in the night. The moon tint is (0.75, 0.83, 1.0).
- **Light colour fades** to 0 within 0.02 of the horizon and dips by 25% around |sun_y| ≈ 0.15. The transition is soft
  because the sun and the moon swap at sunAngle 0.5.

Time-of-day weights (`shaders.properties`): `time_sunrise/noon/sunset/midnight` come from sun_dir with a "me_weight"
bump around sun_y = 0.18. `world_age` (seconds over 128 days) drives every atmospheric animation.

### 4.2 Sun, moon, stars, galaxy, aurora, rainbows (`include/sky/sky.glsl`, `stars.glsl`, `aurora.glsl`, `rainbow.glsl`)

- **Sun disc**:
  - radius 2° (SUN_ANGULAR_RADIUS, intentionally larger than the real 0.27°);
  - luminance = 1/solid angle, so the disc integrates to the sunlight colour;
  - **limb darkening** with per-channel α = (0.429, 0.522, 0.614) applied to √(1 − (1 − d)²), so the edge is redder and darker.
- **Moon**:
  - 3° disc; the procedural surface comes from 3 blue-noise channels;
  - the phase terminator is a light direction orbiting the moon (moonPhase × 45°), shading a sphere normal;
  - an edge glow of r⁸ and a min-glow so the dark part stays faintly visible;
  - the result is squared and multiplied by 10;
  - the vanilla sun and moon textures are optional.
- **Stars** (after Shadertoy Md2SR3):
  - the direction is projected onto an octahedral-like plane and scaled by 600;
  - each integer cell hashes a star with threshold 1 − 0.05·coverage·night;
  - colour is a **blackbody at 4500–8500 K**;
  - **twinkle** is a per-star cosine;
  - "stable" sampling bilinearly blends the 4 neighbouring cells with a cubic weight to avoid shimmer;
  - stars are rotated with the shadow (celestial) matrix so they wheel with the sun;
  - they are hidden behind the vanilla sun/moon by testing the skytextured buffer.
- **Galaxy** (optional): an equirectangular NASA image, saturation ×2, tinted (0.75, 0.66, 1.0), visible at night.
  It also raises the star threshold where the galaxy is bright.
- **Aurora**:
  - a 64-step emission ray-march inside a slab 1000–3000 m high, limited to a 20 km cylinder;
  - the shape is two scrolling noise channels with linear_step(1, 2, n0 + n1), times a height fade (1 − h)³;
  - colour blends between bottom and top colours picked per night from 13 palettes (green/blue, red/purple…);
  - it appears in snowy biomes "rarely" (1 in 5 nights, hashed from worldDay) and lights clouds and the ground slightly.
- **Rainbows** (newer): primary at 42° (width 4°) and secondary at 47° (width 3°) around the anti-solar point. The colour
  comes from a 7-stop palette specified in **CIE L\*a\*b\*** and converted to Rec.2020. They are drawn 500–600 m out and
  hidden behind clouds or terrain using the clouds' apparent distance. The amount comes from the weather after rain.
- Composition order for sky pixels (`draw_sky`):
  1. galaxy + stars + sun + moon;
  2. × atmospheric transmittance (and × (1 − rain));
  3. + atmosphere;
  4. × cloud transmittance + cloud scattering;
  5. rainbow;
  6. "cave sky fix": fade the lower sky to black when underground.

### 4.3 Clouds (`include/sky/clouds/*`, `include/weather/*`, `program/d1_clouds.fsh`, `program/d2_clouds_upscaling.fsh`, `program/p0_clouds_prep.fsh`)

**World scale.** Clouds live on the planet sphere in metres. Minecraft blocks are scaled by **CLOUDS_SCALE = 10 m per
block**, and the camera altitude is taken from eyeAltitude − sea level. Cloud distances are therefore "epic": the cumulus
base is 1200 m in cloud space, i.e. 120 blocks above sea level visually, with up to 20 km of ray length.

**Layers (each one returns scattering, transmittance and apparent distance; they are composited front-to-back by distance):**

| Layer | Altitude / thickness | Type | Steps (primary H/Z, light, ambient) |
|---|---|---|---|
| L0 cumulus / stratocumulus / stratus | 1200 m, thickness = altitude × 1.0 | volumetric | 40/20, 6, 2 (half in the sky map) |
| L1 altocumulus / altostratus | 3200 m, thickness ×0.15 | volumetric | 12/6, 4, 2 |
| Cumulus congestus (towering clouds on the horizon, 30–50 km away, replaces L0 there) | 400 m base, thickness ×32 | volumetric | 20, 6, 2 |
| Cirrus / cirrocumulus | 6000 m, about 1500 m thick | **planar** (one density sample on the sphere) | 6 light steps |
| Noctilucent | 80 km | planar, emissive-ish, blue (1.5, 2, 9)·0.35, visible only at blue hour | — |

**Density (cumulus).** The 2D coverage comes from the blue-noise texture at two scales (2×10⁻⁶ and 2.7×10⁻⁵ per m),
blended between cumulus and stratus coverage (both driven by weather). In new Photon it is precomputed into a 512²
**distorted coverage map**:
- 150 km wide;
- radial warp uv/(0.8·|uv| + 0.2), which gives more resolution near the player;
- this saves a sample and maths per step.

Then:
- **altitude shaping:** stratus becomes a flat sheet; cumulus is carved into an "egg" (density −= smoothstep(0.2, 1, h)·0.6);
  the bottom fades in;
- **two 3D Worley textures** (64³ R8, "bubbly" at 0.0009 per m and "swirley" at 0.005 per m, scrolled with the wind) are
  subtracted, weighted by (1 − density) so they erode edges rather than cores;
- **edge sharpening by altitude:** density ← lift(density, k(h)) with k going from 3 to 12 for cumulus. The result is
  "wispy at the bottom, hard at the top", the classic cauliflower look.

**Lighting** (Wrenninge-style approximate multiple scattering, `clouds_cumulus_scattering`):
- Optical depth toward the light: 6 steps with a **geometric step growth ×2**, starting at 0.1·thickness/steps, dithered
  per sample with a 3D hash.
- Optical depth toward the sky: 2 steps straight up.
- Optical depth to the ground: guessed from altitude and density.
- **8 octaves:**
  - each octave scales scattering by a falloff and extinction by **0.4**, and anisotropy by 0.8;
  - octave 0 uses the single-scattering phase; the later octaves use a 3-lobe HG mix
    (0.65·HG(g) + 0.10·HG(g_peak) + 0.25·HG(−g_back)) with g = (0.6, 0.9, 0.3)^(1 + τ_light).
- **Single-scattering phase:** 0.8·max(Klein–Nishina(e = 2600), HG(0.8)) + 0.2·HG(−0.2). The author notes that taking
  the max of two lobes is "nonsensical but looks nice"; it gives a bright silver lining hugging the sun.
- **Powder:** π·d/(d + 0.15), blended to 1 toward the sun (dark edges facing away from the sun, bright cores).
- **Extra terms:**
  - ground bounce = planet albedo 0.4 × sun_y/π, isotropic, attenuated by ground optical depth;
  - fake light from the layer above when L1 is overcast;
  - sky ambient = isotropic × the sky colour.
- **Step integration:** the energy-conserving "(1 − T_step)/σ_t" integral.
- **Light colour:** sunlight × transmittance *from the cloud position*, so clouds turn orange at sunset while the ground
  is already dim.
- **Aerial perspective** is applied per layer:
  - the transmittance ratio between the viewer and the cloud comes from the Chapman model;
  - the path length is artistically boosted ×2^`CLOUDS_AERIAL_PERSPECTIVE_BOOST`;
  - the result is blended toward the clear-sky colour, which makes distant clouds hazy and blue.
- Early exits:
  - transmittance < 0.075, remapped so that 0.075 → 0;
  - a 20 km max length with a smooth fade;
  - empty coverage.
- In the sky map the step counts are halved and light-ray dithering is off.

**Weather system** (new Photon, `include/weather/core.glsl`, `clouds.glsl`, `fog.glsl`):
- three slow random signals (noise_1d of world_age at about 1/600 of the golden ratio): **temperature, humidity, wind**;
- biased by biome temperature/humidity, time of day (cooler at night and in the morning), and wetness (humidity, wind);
- they drive almost everything:
  - cumulus vs stratus blend (cold + calm → stratus);
  - coverage (humidity; very high temperature lowers it);
  - "wind-torn" detail weights;
  - altocumulus vs altostratus;
  - cirrus and cirrocumulus amounts;
  - congestus (hot + humid + windy, low L0 coverage);
  - L0 extinction (thinner near noon, darker under an overcast L1);
  - crepuscular ray amount;
  - fog Rayleigh/Mie densities;
  - rainbow amount;
  - waving-plant strength.

Every in-game day therefore looks different: hazy hot days, clear windy days, overcast cold mornings.

The MIT snapshot has an older **daily weather table** instead (`include/misc/weather.glsl`): per-day presets interpolated
between worldDay and worldDay + 1.

**Rendering and temporal reuse:**
- `d1_clouds` runs at **¼ × ¼ of internal resolution**. Each frame it renders one of 16 sub-pixel positions (4×4
  checkerboard, `include/utility/checkerboard.glsl`), so a full-res pixel is refreshed every 16 frames.
- Terrain occlusion: the max depth over a 4×4 gather block gives the distance to terrain, so clouds stop at mountains and
  clouds below the camera work.
- The dither is blue noise + R1 per checkerboard cycle.
- `d2_clouds_upscaling` (full internal resolution):
  - **reprojects with the cloud's own motion**: the cloud world position is rebuilt from the closest apparent distance
    (min over a 4×4 history gather) and advected by the layer's wind velocity × frameTime;
  - history is sampled with a 5-tap Catmull-Rom (sharpness 0.5);
  - disocclusion tests:
    - reprojection outside the screen;
    - terrain closer than the cloud in the previous frame;
    - hand;
    - NaN;
    - world-time jump.
  - neighbourhood clamp only when the camera moves fast relative to cloud distance, using a soft "HRAA" min/max (cross
    plus corners);
  - a pixel takes the new sample only when it is this frame's checkerboard slot;
  - blend weight = 1 − 1/max(age − 16, 1); age capped at 20;
  - off-centre rejection when not using TAAU;
  - new pixels are softened by sampling a higher mip of history (`read_clouds_and_aurora`).
- Consumers read colortex11 (scattering.rgb + transmittance) and colortex12 (apparent distance, age, ambient scattering
  for lightning flashes).

**Cloud shadows** (`include/lighting/cloud_shadows.glsl`, written in `prepare`):
- a 512² map in shadow-view space covering ±256 blocks, with radial warp p/(1 + |p|);
- for each texel, one density sample per layer at the layer mid-height along the light ray, turned into
  exp(−σ·thickness/|L_y|·density);
- sampled bicubically on terrain and faded when the sun is low or the fragment is above the cumulus layer;
- **cloud shadows also widen PCSS penumbrae ×(5 − 4·cloudShadow)**, which gives a soft overcast look under clouds.

**Crepuscular rays** (new, `include/sky/crepuscular_rays.glsl`):
- marched in the cloud pass (4–20 steps, more toward the horizon) through the slab below the cumulus;
- each step samples the cloud shadow map in shadow space; in-scattering ∝ cloudShadow³ with HG(0.5) forward
  + HG(−0.2) back, stronger at sunrise and sunset;
- the amount comes from weather (humidity × coverage);
- these are god rays *from clouds*, independent of terrain shadow maps.

### 4.4 Fog and atmospheric scattering (`include/fog/*`, `program/c0_vl.fsh`, `program/c1_blend_layers.fsh`)

**Air fog = two exponential height layers** with independent density curves:
- Rayleigh: falloff starts at sea + 30, half-life 30 blocks;
- Mie: falloff starts at sea + 7, half-life 7 blocks, i.e. low ground mist;
- volume between sea − 24 and y = 320.

Coefficients are per-biome Rayleigh colour × density (`settings.glsl` AIR_FOG_RAYLEIGH_*). Examples:
- normal (0.31, 0.67, 1.0)·5e-4;
- jungle denser and greener;
- swamp yellow-green;
- pale garden 0.03 (very dense);
- rain overrides.

Mie density depends on time of day: morning 0.007, noon 0.0001, evening 0.005, midnight 0.005, blue hour 0.002,
rain 0.03, snow 0.015. Its albedo drops from 0.9 to 0.5 in rain. A desert **sandstorm** adds an orange extinction.

Two evaluators:
- **Analytic** (`fog/overworld/analytic.glsl`):
  - the airmass integral of the exponential density along a straight ray is closed-form
    ((2^(a + t·d·m) − 2^a)/(ln2·m·d));
  - the result is shaded with a **4-iteration multiple-scattering series**: scatter ×0.5 and anisotropy ×0.7 per
    iteration, phase 0.7·HG(0.5·a) + 0.3·HG(−0.2·a);
  - plus 2 × isotropic sky ambient;
  - plus an **"evening glow" boost** of 0.75·exp(−300·(sun_y + 0.02)²).

  It is used on the sky map, in reflections, behind translucents, and as the fog when VL is off.
- **Ray-marched VL** (`fog/overworld/raymarched.glsl`, run by `c0_vl` at **VL_RENDER_SCALE = 0.5**):
  - 8 to 25 steps, growing with ray length at 0.1 per block;
  - one shadow-map fetch per step, done with texelFetch with no filtering (optionally the coloured shadow map: "coloured
    light shafts" through stained glass);
  - density is optionally modulated by a 2D cloudy noise ("AIR_FOG_CLOUDY_NOISE", Mie × 4n²);
  - the same 4-octave shading;
  - outputs transmittance (RGBA16) and scattering (RGB16F) at half resolution, upsampled in `c1` with the smooth-quintic
    "IQ texture" filter (`smooth_filter`);
  - no dedicated temporal pass: blue-noise + R1 dither is resolved by the main TAA.

Other fogs (`include/fog/simple_fog.glsl`):
- **Underwater** (`water_fog_vl.glsl`): 50-block ray-march with shadow-map distance-through-water (sun light attenuated by
  the depth of water above the sample), animated caustic modulation, and the same multi-octave phase.
  The analytic version (`water_fog_simple`) uses a **multiple-scattering energy term** (Jessie): albedo_ms = 0.84·σs/σt and
  energy = albedo_ms/(1 − albedo_ms).
- **Cave fog:** density 0.0033, colour 0.033, gated by the `biome_cave` uniform (eye below y ≈ 63 and low eye sky light).
- **Lava, powder snow, blindness, darkness:** spherical exponential fogs.
- **Nether:** exponential fog toward the biome fog colour (0.01 density).
- **End:** a ray-marched emissive fog with noise-driven purple and green clouds (`end_fog_vl.glsl`).
- **Border fog** (`border_fog`):
  - exp2(−8·(cubic_length(xz)/far)⁸), i.e. a quartic-norm "cubic length" so chunk corners fade evenly;
  - colour = the sky map in a horizon-clamped direction, blended toward the atmosphere colour except at sunset or in rain.
- **Bloomy fog** (optional): bloom strength scales with 1 − fog transmittance, a dreamy glow in fog.

### 4.5 Shadows (`include/lighting/shadows/*`, `program/shadow.*`)

- **One distorted orthographic map:**
  - 2048² default, 128 blocks (`shadowDistance`), sunPathRotation −35°.
  - Distortion factor = **quartic length** (fourth root of x⁴ + y⁴, smaller along diagonals than Euclidean)·0.85 + 0.15.
    Clip xy is divided by it, so the inner area gets about 6.7× the texel density of the edge.
  - Depth is scaled by 0.2 to stretch the depth range.
  - Iris renders the shadow map every frame (no caching).
- **Bias:**
  - a normal offset of 0.25·clamp(0.12 + 0.01·distance)·(2 − NoL) along the face normal, plus an "edge factor" that
    pulls samples toward the block interior when skylight is low (prevents underground leaks). **Both are the
    Complementary-derived method; do not copy them.**
  - the newest Photon adds no slope bias beyond this.
- **PCSS ("VPS", variable penumbra):**
  - blocker search: 3 Vogel-disc taps (12 when the material has SSS), radius 0.5 blocks × projection scale, halved when
    the sun is low;
  - texelFetch on the depth-only map gives the mean blocker depth *and* an SSS thickness (the sum of (receiver −
    blocker) over all taps);
  - penumbra = 16·(z_r − z_b)/z_b, × (5 − 4·cloudShadow), clamped to the search radius;
  - PCF: 6–12 taps (scaled by (penumbra/minRadius)²) on a Vogel disc rotated per pixel by blue noise × R1, using the
    hardware 2×2 compare (`shadowtex1` + shadowHardwareFiltering);
  - early exit after 4 taps if fully lit or fully shadowed;
  - **sharpening** for sub-texel penumbrae: a linear_step remap of the PCF result, so hard contact shadows stay crisp
    instead of blurry.
- **Coloured shadows:**
  - the shadow pass writes `shadowcolor0` = 0.25·linear(texture × tint) for translucent casters (alpha < 1);
  - water writes 0.25·exp(−absorption·5 m) × caustics;
  - when the translucent depth (shadowtex0) is in front of the receiver, the averaged colour ×4 tints the light;
  - a 3×3 average hides seams.
- **Water caustics in the shadow map** (`shadow.fsh`, `get_water_caustics`): for water fragments, compute the
  wave normal, refract the light direction (n = 1.333), move 5 m along the refracted ray, and compare the **screen-space
  derivative area** of the original vs refracted positions (|dFdx|²·|dFdy|²). Caustic intensity = 0.25·√(area ratio).
  This is the classic "photon area" trick done for free in a raster pass; the result lands in the coloured shadow.
- **Beyond the shadow distance** (new Photon, `ssrt.glsl`):
  - a 10-step screen-space ray-march toward the light with geometric step growth ×2;
  - assumed thickness 10 blocks; the direction is jittered by 0.03·random so the shadows are soft;
  - it also measures the SSS exit distance;
  - the shadow-map result fades into it.

  Fallback: lightmap shadows smoothstep(13.5/15, 14.5/15, skylight). Light leaks are also killed by smoothstep(0,
  2/15, skylight).
- Optional "pixelated shadows" snap the receiver to a 16 texel/block grid.
- Entity shadows are on from the High profile; block-entity shadows only on Ultra.

### 4.6 Lighting model (`include/lighting/diffuse_lighting.glsl`, `program/d4_deferred_shading.fsh`)

The per-pixel lit colour is (sum of the terms below) × albedo/π, with metals at 50% diffuse.

- **Sun/moon diffuse:**
  - lift(max(NoL, 0), 0.25/SHADING_STRENGTH): a lifted Lambert that is less contrasty on side faces;
  - × (1 − 0.5·SSS) × **AO²** (AO_IN_SUNLIGHT) × shadows × cloud shadows × light colour.
- **Bounced light** (fake GI):
  - 0.033·(1 − shadow)·(1 − 0.1·max(N_y, 0))·ao^1.5·skylight⁴ × light colour;
  - shadowed areas get a little sun-coloured fill, which makes shadows warm and alive instead of grey.
- **SSS:**
  - transmittance-based: exp2(−σ·sssDepth), where σ = (1 − 0.75·normalised albedo)·14/sss_amount, so light passes
    through thin leaves tinted by their albedo;
  - phase = mix(isotropic, HG(−LoV, 0.7), 0.33);
  - × 5·π.
  - **"Sheen"**: 0.8/albedo × exp2(−σ·d) × HG(−LoV, 0.5) × a back-light window. This is the bright rim on grass and
    leaves when looking toward the sun.
  - Beyond the shadow distance, SSS is approximated with AO + the GTAO "ambient SSS".
- **Skylight:**
  - SH irradiance of the actual sky map evaluated along the **GTAO bent normal**;
  - the SH is convolved with a **visibility-cone kernel whose aperture comes from AO**: sin²(aperture) = AO, zonal
    harmonics of a cone. This is a principled way to darken ambient light in crevices without double-darkening;
  - mixed with the up-facing irradiance by skylight² (indoors goes flat);
  - × skylight² falloff;
  - leaves get extra ambient SSS from GTAO's horizon (the amount that passes behind).
- **Block light:**
  - colour (1.0, 0.75, 0.63) sRGB × scale 6;
  - falloff f(b) = b⁸ + 0.18·b² + 0.16·(2b − b²);
  - AO applied as ao³ far from the light and none near it;
  - reduced by 20–40% in daylight;
  - a "hot spot" min(2.7·b¹², 0.9) right at the source that stays visible in daylight;
  - an edge fade below level 2;
  - the Darkness effect pulses it.

  There is no flicker in the base model.
- **Hand light:**
  - colour = held item light level/15 × the block-light colour (or the LPV palette colour);
  - falloff = lift(1/(d² + 1), 3), centred on the player eye, not the camera.
- **Emission:** material.emission × 40 (EMISSION_STRENGTH). Emission is computed from the albedo's HSL per material
  class: e.g. a torch flame is lit only where the texel lightness > 0.78; lava is masked by hue 30° ± 15°; redstone uses
  "redness" in AP1. So only the bright pixels of a texture glow, which looks good with stock textures.
- **Cave light:** 0.15 × directional × ao × (1 − skylight²), a minimum ambient underground. Night vision adds 1.5.
- **"Directional lighting":** (0.9 + 0.1·N_x)·(0.8 + 0.2·|N_y|), an arbitrary face shading so blocks read in flat ambient
  light. It replaces vanilla's face shade (vanilla's is removed by `oldLighting = false`).
- **Specular highlight:**
  - GGX D and Smith V on a **spherical area light** of the sun's angular radius, using the *Decima / Horizon Zero Dawn*
    "most representative point" NoH² with one Newton iteration;
  - Fresnel options: exact dielectric (from the f0 IOR), Schlick metal, or **Lazanyi 2019 f0/f82** for the 8 hardcoded
    LabPBR metals;
  - clamped to 4 so it does not overload bloom; no highlight on a new moon.
- **AO:**
  - vanilla AO in the albedo (lifted);
  - **GTAO** at ½ resolution: 2 slices × 3 horizon steps per side; radius 2 blocks, scaled down when close
    (0.25 + 0.75·smoothstep(0, 81, d²)); the multibounce approximation ao/(0.2·ao + 0.8);
  - it outputs AO, **ambient SSS** (the horizon angle beyond 90°) and a **bent normal**;
  - temporal accumulation up to 10 frames with depth rejection and off-centre rejection; bent normals are slerped;
  - upsampled in `d4` with a bilateral depth weight exp2(−10·|Δz|).

  SSAO (12 steps) is the alternative.
- **Coloured lights (Ultra, Iris only):**
  - the shadow vertex shader **voxelises** emitters and translucent tinters into a 128³ R8UI image around the camera;
  - a compute pass **flood-fills** light into ping-ponged 128³ RGBA16F volumes: 7-tap average (centre ×1.05) × the tint
    of translucent blocks + emission², shifted by camera movement;
  - shading reads √(volume) trilinearly at pos + 0.5·normal and blends with the vanilla lightmap far from coloured data;
  - optional "LPV VL" in fog.

  This is the same idea as Complementary's floodfill. Photon's version is MIT in the snapshot.

### 4.7 Materials, wetness and reflections (`include/surface/material.glsl`, `rain_puddles.glsl`, `lighting/specular_lighting.glsl`, `misc/raytracer.glsl`)

- **Hardcoded table** keyed by material id: a branchless-ish binary split over ids 0–63+. Highlights:
  - default smoothness 0.33·smoothstep(0.2, 0.6, lightness);
  - leaves: smoothness up to 0.5, SSS 1, sheen 0.5;
  - plants: SSS 0.5, sheen 1;
  - sand: smooth only for bright grains;
  - ice: specular and SSR;
  - metals and gems: f0;
  - wood and planks;
  - copper with oxidisation detected by hue;
  - emitters (white, golden and orange in strong/medium/weak strengths, redstone, lava, sculk…).

  LabPBR 1.3 parsing covers roughness = (1 − smoothness)², f0, the hardcoded metals 230–237, SSS/porosity in blue, and
  emission in alpha. The "old" (SEUS-style) format is also supported.
- **Puddles** (rain, outdoor only, up-facing):
  - a noise mask at 0.025 per block, gated by wetness and skylight ≥ 14/15 with no block light;
  - porous materials darken (×(1 − 0.67·porosity));
  - non-porous surfaces become a puddle: f0 0.02, roughness 0.002, SSR enabled;
  - **ripples**: normal from the finite difference of two scrolling noises, faded at grazing angles.
- **SSR:**
  - screen-space linear march (16 steps smooth, 8 rough) + 4 binary refinement steps;
  - depth tolerance max(3·|Δz_step|, 0.02/z²) (credited to DrDesten);
  - **it samples the previous frame's TAA output** (colortex5) at the reprojected hit, with mips for roughness, after
    subtracting the previous frame's fog and re-applying analytic fog for the reflected path;
  - fades at screen borders (the factor depends on camera pitch);
  - misses fall back to the **sky map** (bicubic) × skylight¹² so caves don't reflect the sky;
  - rough reflections: 4 GGX VNDF rays (the "improved" spherical-cap VNDF sampling by Dupuy and Benyoub 2023), weighted
    by 2·NoL·G2/G1.

  SSR runs only where `ssr_multiplier` > 0: water, puddles, ice, metals, smooth LabPBR.

### 4.8 Water (`program/gbuffers_all_translucent.fsh`, `include/surface/water_normal.glsl`, `program/c1_blend_layers.fsh`, `include/vertex/displacement.glsl`)

- **Surface material:**
  - albedo ≈ 0, alpha 0.01: the water surface is *only* Fresnel reflection plus what is seen through it;
  - the default "highlight underground" texture mode adds back only the bright texels of the vanilla water texture as
    roughness/albedo streaks, mainly in caves;
  - roughness 0.002, f0 0.02, exact dielectric Fresnel (n = 1.333).
- **Waves:**
  - 3 iterations of a **Gerstner-like** profile ((sin x·½ + ½)², deep-water dispersion ω = √(g·k)) with
    persistence 0.5, lacunarity 1.7 and wavelength ×1.5 per octave;
  - each octave's direction is rotated by the **golden angle**, and its phase is perturbed by a noise texture
    (frequency 0.007 per block, ×2.5 per octave), so the waves never look tiled;
  - a large-scale height variation noise (0.001 per block, min 0.4) creates calm and choppy patches;
  - normal = normalize(h(x + 0.1) − h, h(z + 0.1) − h, 0.1) with strength mix(0.01, 0.04 + 0.15·rain, skylight);
    flowing water is 0.1 along the flow direction;
  - normals are attenuated at grazing view angles to avoid sparkle noise;
  - **water parallax:** 4 steps of height-field marching, depth 0.2;
  - optional vertex displacement ±0.025 blocks.
- **Colour through water:**
  - absorption coefficients default to (0.39, 0.14, 0.07) per block plus scattering 0.01;
  - **biome water colour is turned into absorption**: σa += 0.33·(−0.15·ln(biomeColour) − −0.15·ln(forestColour));
    swamps get greener and murkier, warm oceans clearer and cyan;
  - in-scattering of sun (HG 0.4) + ambient + block light, with multiple scattering;
  - a minimum fog distance keeps shallow water visible;
  - a "brightness control" boost;
  - the surface alpha becomes 1 − transmittance, and colour is premultiplied in colortex13.
- **Edge highlight:** foam-like brightening where the water–terrain distance (layer_dist·|V_y|) is below 0.5 blocks: cube
  falloff, stronger where the texture highlight is.
- **Refraction** (`c1`):
  - uv offset = tangent normal.xy × 1/max(viewDist, 1) × min(layerDist, 8) × 0.1;
  - rejected if the refracted pixel is in front of the surface;
  - also applied to stained glass.
- **Reflections:** SSR (smooth, 16 steps) + sky map; specular sun glint via the sphere-light GGX.
- **Underwater:**
  - VL water fog with caustics;
  - optional **Snell's window** (alpha → Fresnel from below);
  - the vanilla underwater overlay is disabled.
- **Caustics on the floor:** via the coloured shadow map (§4.5). Off by default; on in the Ultra profile.
- Distant Horizons and Voxy water get an equivalent `draw_distant_water`.

### 4.9 Translucents and glass

- All translucents (water, stained glass, ice, slime, particles, `gbuffers_textured`) are **forward-lit** with the full
  model:
  - shadows (PCSS evaluated per fragment);
  - SH-less ambient (the up irradiance);
  - specular and SSR;
  - fog applied per surface.
- They are written premultiplied into colortex13 with blending ONE, ONE_MINUS_SRC_ALPHA. Most other blending is off.
- Compositing happens in `c1`: refracted opaque + analytic fog between the surface and the opaque behind it, then
  translucent layer, then clouds in front, then VL.
- The fallback for translucent entities on old versions is dithered alpha.
- Rain and snow (`gbuffers_weather`):
  - colour = weather colour (rain is bluish and dim at night), opacity 0.25 (snow 0.75);
  - **slanted rain**: a 0.25 tilt in a 30° wind direction with per-drop variation;
  - splash particles are killed;
  - "bloomy rain" is optional.

### 4.10 Waving foliage (`include/vertex/displacement.glsl`)

- **Wind** = a large-scale **gust** noise (0.05 per block, squared, scrolled along a 30° wind direction) + a **wobble**
  (sum of sines at 32·pos + 3t with octaves ½ and ¼).
- Strength = skylight² × (0.25 + 0.66·rain) × (0.5 + weather wind).
- Small plants move only their top vertices. Tall plants: the upper half moves fully, with a doubled gust.
- Leaves use half strength.
- **Player push:** plants bend away from the player with exp2(−|Δ·(6, 2, 6)|).
- The same displacement runs in the shadow pass, so shadows wave too.

### 4.11 TAA, TAAU and sharpening (`program/c3_taau_prep.fsh`, `c4_taa_exposure.fsh`, `final.fsh`)

- **Reprojection:** camera-only (no motion vectors), using the **closest depth of a 5-tap cross ±2 px**, with special
  handling for the hand.
- **History:**
  - Catmull-Rom 5-tap ("fast", sharpness 0.6);
  - **pixel age** stored in alpha;
  - α = max(1/age, mix(0.35, 0.10, distanceFactor)): fast convergence, then a floor that favours responsiveness near
    the camera and stability far away (distanceFactor = 1 − exp2(−0.025·dist)).
- **Clipping:**
  - colours go through **Reinhard** x/(1 + x) first (inverted afterwards), then **YCoCg**;
  - an HRAA-style soft min/max (average of the cross-min and box-min);
  - **variance clipping** (μ ± γσ, with γ from 0.75 near to 1.25 far), intersected with the AABB;
  - then **AABB clip** toward the centre ("INSIDE" style).
- **Off-centre rejection** (0.25), from Jessie/Zombye: lowers the history weight when the reprojected position is far
  from a texel centre, which reduces blur in motion.
- **TAAU** (optional, render scale 0.75 default):
  - the jittered internal image is reconstructed with Catmull-Rom;
  - the AABB is precomputed per internal pixel (`c3`) and bilinearly interpolated;
  - α is multiplied by **confidence^5**, where confidence is the Catmull-Rom weight at the sample position ("confidence
    of quality", a survey of TAA, §5.1);
  - Karis "distance-to-clamp" flicker reduction;
  - the texture LOD bias is log2(scale).
- **Final:**
  - **CAS** (AMD Contrast Adaptive Sharpening) at intensity 0.5 when the render quality is 1;
  - otherwise a Catmull-Rom resample;
  - sRGB encode;
  - **Bayer 16×16 dither** to 8-bit.
- FXAA 3.11 is a separate optional pass after grading.

### 4.12 Exposure, bloom, tonemap, grading, lens

- **Exposure** (computed in the `c4` *vertex* shader, so it costs nothing per pixel):
  - default **manual EV 0** (exposure = 2^−EV) because the light levels are pre-balanced by the sun/moon exposure terms (§4.1);
  - optional "simple" mode: the top mip of the scene;
  - optional histogram mode: 32×18 tiles sampled at the right mip into 32 log bins, median at 50% of the CDF;
  - calibration K = 12.5 at ISO 100 /1.2;
  - asymmetric adaptation: dim-to-bright 2/s, bright-to-dim 1/s;
  - stored in texel (0,0).a of the TAA history.
- **Bloom:**
  - 6 tiles packed in one atlas texture: tile i has scale ½·2^−i and offset (1 − 2^−i, (i&1)(1 − ½·2^−i));
  - downsample with the **13-tap CoD Advanced Warfare filter** (centre box 0.125×5, cross 0.0625×4, corners 0.03125×4);
  - a merge pass, because downsampling ping-pongs between odd and even tiles;
  - one **9-tap binomial Gaussian** horizontal + vertical over the whole atlas, bounded per tile;
  - upsample chain: dst·(1 − w) + upsampled(src)·w with w = mix(0.25, 0.9, ½·spread);
  - the final mix is **lerp(scene, bloom, 0.12)**, not added. This keeps energy and gives a soft, wide, non-blown glow.
- **Grade-in** (scene-referred, Rec.2020):
  - × 0.83 brightness;
  - log-contrast around 0.18;
  - saturation 0.98;
  - optional white balance (XYZ matrix from a temperature, built in the vertex stage).
- **Tonemap (default `tonemap_lottes`)**: Lottes 2016 generic curve, contrast a = 1.5, shoulder d = 0.91, white point
  8.0, midpoint 0.26 → 0.32, applied per channel. Alternatives: ACES RRT+ODT (full and fit), Hejl 2015, Hejl-Burgess,
  Uncharted 2, Reinhard, Reinhard-Jodie (+ Tech's and Ozius', which we skip).
- **Grade-out** (display-referred, Rec.709):
  - done in √ space (roughly perceptual);
  - **HSL hue-isolated grading**: oranges 30° ± 20, **teals 210° ± 20 +10% saturation by default**, greens 90° ± 44 with
    a hue shift;
  - a symmetric "gain" S-curve with k = 1.05 (a slight contrast increase around mid-grey);
  - then squared back.
- **Vignette:** very subtle: (16·u·v·(1−u)(1−v))^0.08, stronger in caves, with blindness and darkness.
- **Purkinje shift** at night and in low sky light:
  - computed in the lighting pass (d4/translucents) from XYZ via a scotopic approximation;
  - mixes toward a blue-grey tint (0.5, 0.7, 1.0) with weight exp2(−luminance/(0.05·intensity));
  - nights become desaturated and blue-shifted rather than just dark.
- **DOF** (optional): 40-tap Vogel disc, CoC from |depth − focus|.
- **Motion blur** (optional): camera-based.
- No lens flare, chromatic aberration or film grain in Photon. Spectrum has chromatic aberration, lens distortion and
  anamorphic bokeh.

### 4.13 Nether and End

- **Nether:**
  - no shadow pass; sky = ambient colour;
  - ambient = the biome fog colour normalised to unit luminance, desaturated by 0.75, ×0.05;
  - in lighting, ambient ×16 × directional, 50% desaturated;
  - exponential fog in the ambient colour;
  - bloomy fog option.

  The look comes from block light (lava emission ×2 with a hue mask) and the fog colour.
- **End:**
  - shadows from a fixed "sun" direction;
  - light (1.0, 0.5, 0.25)·0.5 and ambient (0.75, 0.33, 1.0)·0.75 (warm key, purple fill);
  - sky = gradient + Cornette–Shanks(0.6) glow around the sun direction + stars (swizzled) + an animated **solar flare**
    sun: a polar noise texture rotating with time, ×exp(−25·(r − radius));
  - **End fog VL:** an emissive noise volume (purple and green) up to y = 256, 16–25 steps, 4-octave scattering.

### 4.14 Settings profiles and macOS behaviour

From `shaders.properties`:

| Profile | Shadow res | PCF | Colored shadows | VPS (PCSS) | Entity / BE shadows | Env. reflections (SSR) | GTAO | VL | Coloured light shafts | Caustics | Water parallax | Coloured lights (LPV) | SSRT distant shadows |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| low | 1024 | on | off | off | off/off | off | off | off | off | off | off | off | off |
| medium | 2048 | on | off | on | off/off | off | off | **on** | off | off | off | off | off |
| high (default INFO=2) | 2048 | on | on | on | on/off | on | on | on | off | off | on | off | on |
| ultra | 2048 | on | on | on | on/on | on | on | on | on | on | on | on | on |

The MIT snapshot's Ultra used a 4096 map.

Clouds always render at ¼ (CLOUDS_TEMPORAL_UPSCALING 4). TAA + FXAA + CAS are on by default; TAAU is off (scale 0.75
when on); bloom on; DOF and motion blur off.

**macOS.**
- The README lists "**NOT** Apple Metal" and says it may work with *SH Skylight* and *Colored Shadows* disabled.
- The code has no `MC_OS_MAC` branches. The only vendor branches are `MC_GL_RENDERER_INTEL`, for SH array layout and
  barrier-in-loop workarounds.
- What breaks on Apple's GL 4.1 (4.1 is our inference; Photon does not say this): compute shaders (`d4a_generate_sky_sh.csh`
  SH skylight, the LPV `setup.csh`/`shadowcomp.csh`) and image load/store (`COLORED_LIGHTS`, which needs Iris custom
  images; Photon prints an error otherwise).
- The MIT snapshot kept SH_SKYLIGHT off by default and used an analytic `skylight_approx.glsl` instead.
- On our native Metal backend all of this is available: compute, image writes and 3-D textures.

---

## 5. What we should emulate (the visual qualities that make Photon great)

1. **A physically grounded sky that is art-directed toward drama.**
   - Use a real Rayleigh/Mie/ozone LUT for colour, then stretch the horizon band upward and boost saturation at blue hour.
   - Use the Klein–Nishina (or "area" HG) Mie lobe so the sun has a bright, tight halo.
   - Make the sunlight colour equal to AM0 sunlight × real transmittance, so golden hour gets the right warmth for free.
2. **Twilight that stays bright and colourful.** A huge direct-light boost and pink-violet tint around sun_y ≈ −0.09 keep
   blue hour luminous instead of dropping to grey. Add an "evening glow" in the fog. Turn the Purkinje shift on at night
   so nights read as blue moonlight, not darkness.
3. **Ambient light that comes from the sky you see:**
   - SH skylight from the rendered sky map, evaluated along GTAO bent normals with a visibility cone;
   - a warm "bounced light" fill in sun shadows.

   Shadows then pick up a blue tint from the sky, faces turned toward the sunset are warmer, and crevices get deep but
   soft occlusion.
4. **Shadows with character:**
   - contact-hard and distance-soft (PCSS) with a sharpening remap for crisp edges;
   - penumbrae widen under clouds (an overcast mood);
   - stained glass and water tint the light;
   - water throws caustics.
5. **Foliage that glows when backlit:** transmittance SSS through leaves and the "sheen" rim on grass toward the sun.
   Together with waving plants and player push, this is a big part of the "alive" feeling.
6. **Clouds as weather, not decoration:**
   - several layers at plausible altitudes, scaled ×10 so they feel enormous;
   - a daily weather state changes cloud type, coverage, haze and fog per day and biome;
   - multi-octave scattering with powder gives dark undersides, silver linings and bright cores;
   - aerial perspective makes distant clouds hazy;
   - cloud shadows sweep over terrain, with god rays from the clouds.
7. **Height fog with biome personality:** low Mie ground mist in the morning, jungle and swamp humidity tints, rain haze,
   desert sandstorms; volumetric light shafts through trees and caves.
8. **Water that is mostly physics:**
   - zero albedo, colour from absorption, and the biome water colour turned into absorption coefficients;
   - noise-perturbed rotated Gerstner octaves with calm and choppy patches;
   - exact Fresnel, SSR from the previous frame with a sky-map fallback, refraction, and a subtle edge highlight.
9. **Restrained, filmic image formation:**
   - Rec.2020 maths;
   - bloom *mixed* at 12% (wide but never blown out);
   - a gentle Lottes curve with a high white point (8);
   - a tiny teal-saturation grade and S-curve;
   - very subtle vignette, CAS sharpening, Bayer dither (no banding).

   The result is clean, high dynamic range and not over-processed.
10. **Night-sky delight:** blackbody-coloured twinkling stars that rotate with the sky; a moon with real phases and a
    proper halo; occasional auroras in snowy biomes; noctilucent clouds at blue hour; rainbows after rain.
11. **Stability:** blue-noise + R1 dithering everywhere, then TAA with variance clipping and pixel-age weighting. Every
    stochastic effect (shadows, AO, VL, SSR, clouds) converges to a clean image.

Tuning anchors to start from:
- sun 7 × SUN_I; block light ×6 with colour (1, 0.75, 0.63); emission ×40;
- bloom 0.12; tonemap Lottes (1.5, 0.91, 8, 0.26 → 0.32); brightness 0.83; teal saturation +0.10;
- fog Rayleigh 5e-4 and Mie 7e-3 (morning) with heights +30/+7 above sea level;
- water absorption (0.39, 0.14, 0.07).

---

## 6. Port notes for our Metal/TBDR pipeline

Assumptions from the background reports:
- The opaque pass is a single TBDR pass with a memoryless G-buffer and the lighting draw inline (apple-gpu §2.1).
- Internal resolution is 0.5–0.67x with our own TAAU to 3024×1964 (apple-gpu §3.2, §7.1).
- Sodium's 20 B vertex has no normals or block ids. The normal comes from corner fetch (mcopt-architecture §4.5), and the
  material comes from an **atlas material mask R8** (critique M2.2).
- Reversed-Z: sky depth is 0, and every Photon `depth == 1.0` becomes `== 0.0`.
- The main target is LDR until M2 adds the RGBA16F "Sky + Main" redirect.

### 6.1 Effect-by-effect mapping

**Atmosphere/sky (M1 step 6, M2):**
- Ship a 3-D scattering LUT: either reuse Photon's MIT `scattering.dat` (32×64×32 RGB16F, 393 KB, with attribution) or
  generate our own once at load with a compute kernel. A Bruneton-style multiple-scattering precompute takes a few ms
  once.
- Keep Photon's tricks:
  - split Rayleigh+MS and Mie-without-phase into the two x halves;
  - Zombye ν mapping;
  - horizon stretch;
  - Chapman transmittance (cheap enough for clouds).
- Render a **192×108 sky view map** every frame (or every 2 frames) as a fragment pass into a compressible RGBA16F. Cost:
  about 20k px, under 0.05 ms without clouds and about 0.1 ms with half-step clouds.
- Compute the **SH9** with a tiny compute kernel: 1 threadgroup, 256 threads, simdgroup reduction (`simd_sum`) instead of
  barrier loops. Cost about 0.01 ms. Write it into a small buffer bound at `buffer(16)` together with the ShadeFrame UBO,
  not into texture column 191.
- The vanilla `sky`/`sunrise_sunset`/`celestial` pipelines are overridden in M1. Read sky pixels from the same LUT so
  terrain fog and sky agree.
- Sun/moon/stars/aurora/rainbow are all analytic and cheap. Put them in the sky fragment, only on sky pixels.

**Sun/moon light colours:** compute them on the CPU, in Java, per frame (`ShadeFrame`):
- sunlight × Chapman transmittance at the sun elevation × Photon-style exposure/tint curves, and the ambient colour;
- or compute them in the SH kernel.

This removes Photon's need to recompute them in vertex shaders.

**Clouds (M3):**
- Use Photon's structure: ¼ × ¼ checkerboard ray-march + reprojection with layer wind velocity, early-out on terrain
  using a downsampled **max-depth** (min reversed-Z) pyramid level.
- Changes for Apple:
  1. **Keep the cloud history at ½ internal resolution**, not full. Photon's 2× RGBA16F at full resolution is about 1 ms
     of bandwidth at native. Upsample it bilaterally in the lighting/sky fragment. Use lossy compression (`MTLTextureCompressionTypeLossy`).
  2. Run the march as a **fragment** pass into compressible targets; the output is small, so compute would also be fine.
  3. Use `half` for density and scattering accumulators and `float` for positions in metres.
  4. Use 3-D Worley textures (64³ R8, 256 KB each): generate our own, or take Photon's MIT `worley_noise.dat` from the
     snapshot.
  5. Precompute the 512² coverage map + cloud shadow map in one fragment pass. Only the wind offset changes, so it can
     update every 2–4 frames.
- Cost (est., from apple-gpu §5.4 and the step counts): march 0.3–0.6 ms at 0.5x, 0.4–1.0 ms at 0.67x; reconstruction
  0.15–0.3 ms; coverage + shadow map 0.03 ms.
- Start with L0 cumulus + cirrus. Add altocumulus and congestus as a quality option.

**Weather system:**
- Pure CPU maths in Java: temperature/humidity/wind from hashed world time and biome.
- Pack about 20 floats of cloud/fog parameters into `ShadeFrame`. Zero GPU cost.
- Biome blends (`biome_arid`, etc.) come from the client biome at the camera with smoothing, as Iris's `smooth()` does.

**Shadows (M1 → M4):**
- M1 plan: 2 cascades, sun-facing faces only, cached far cascade. It differs from Photon's single distorted map.
  - Keep **PCSS-style variable penumbra + sharpening**. Measured: 16-tap PCF/PCSS is free inside the geometry-bound TBDR
    lighting pass (apple-gpu §2.1).
  - Hardware 2×2 compare through a `GreaterEqual` compare sampler (reversed-Z), Vogel disc rotated by blue noise.
- **Do not copy Photon's bias** (Complementary-derived). Use:
  - a normal offset scaled by texel world size × (1 − NoL);
  - slope-scaled `setDepthBias` in the shadow encoder;
  - snapping cascades to texels.
- Coloured shadows:
  - a second R11G11B10F/RGBA8 colour attachment in the shadow pass;
  - write the tint for alpha < 1 atlas texels (the material mask tells glass);
  - write absorption + caustic area ratio for water (`dfdx`/`dfdy` work in MSL fragment shaders).
  - Cost: +1 colour write in a geometry-bound pass, about +0.05–0.1 ms (est.).
- Beyond the cascades: Photon's SSRT needs a stored depth. In TBDR we don't have the current depth inside the pass.
  - Option A: use last frame's stored depth, reprojected (cheap, slightly laggy).
  - Option B: rely on the far cascade + a lightmap fallback.
  - **M4: inline RT shadows** replace all of this (0.05–0.3 ms inside the lighting pass, apple-gpu §4).
- Cloud shadows: one bicubic sample (4 bilinear taps) of the 512² map in the lighting pass, negligible. Widen the
  penumbra under clouds the way Photon does.
- Leaves' SSS thickness:
  - Photon gets it from the blocker search (receiver − blocker depth). With cascades we get it the same way.
  - With RT: one extra short ray from inside the leaf toward the light, or keep a tiny VL/SSS shadow map.

**Lighting (inline TBDR lighting draw):**
- G-buffer in tile memory, close to Photon's packing:
  - albedo RGB8 + material id (`[[color(1)]]` RGBA8);
  - octahedral flat normal RG8 + light levels RG8 (`[[color(2)]]` RGBA8);
  - linear distance R32F/R16F (`[[color(3)]]`).
  - With dithered 8-bit light levels as in Photon, this fits apple-gpu's recommended layout.
- The full Photon diffuse model is ALU-only except for the AO and cloud-shadow fetches:
  - lifted Lambert + AO² + bounced fill + SSS/sheen + SH skylight with the visibility cone + block-light falloff
    polynomial + hot spot + hand light + emission + cave light.
- Expected marginal cost: +0.2–0.5 ms at 0.67x over the geometry-bound pass (est.).
- **Vanilla face shade must be removed** (CardinalLighting mixin, M1). Then apply Photon's
  (0.9 + 0.1·N_x)(0.8 + 0.2|N_y|) "directional lighting" for readability.
- Vanilla AO is baked into Sodium's vertex colour:
  - divide out the face shade;
  - keep the AO and apply Photon's lift curve (0.9·lift(ao, 0.5) + 0.1 with GTAO on);
  - leaves need no vanilla AO, via the material mask.
- Emission: classify atlas sprites into Photon-like emitter classes (strong/medium/weak white, golden, orange, lava hue,
  redstone) when building the **atlas material mask**. Do the per-texel luminance and hue masking on the CPU at atlas
  stitch, so the runtime cost is one R8 fetch.

**AO (GTAO with bent normals):**
- The TBDR lighting draw cannot read neighbouring depth.
- Run GTAO as a **half-internal-resolution fragment pass after the main pass**, from the stored depth (the stored normal
  is optional; flat normals can be rebuilt from depth derivatives).
- Its temporally accumulated output (AO, ambient-SSS, bent normal in RGBA8) is **consumed by the next frame's lighting
  draw via reprojection** (Photon already accumulates 10 frames, so one frame of latency is invisible).
- Cost: 0.15–0.25 ms at 0.67x (apple-gpu SSAO 0.16 ms; GTAO has similar tap counts).
- Alternative: apply AO in the TAAU pass to a separately stored "ambient" term. That costs more bandwidth; not
  recommended.

**Specular + SSR:**
- The sun glint (sphere-light GGX) is ALU work in the lighting draw.
- SSR only for `ssr_multiplier > 0` pixels (water, puddles, ice, metals), half-resolution march with Hi-Z, sampling
  **last frame's TAAU output**. Photon's trick avoids a separate reflection-colour pass and includes translucents and fog
  for free.
- Cost: 0.3–0.5 ms for about 30% water coverage (apple-gpu §5.2).
- M4: RT reflections on water pixels only, measured cheaper than 24-step SSR.

**Water and translucents (M2.3):**
- At the switch to `sodium:pipeline/translucent_terrain`: suspend, copy the scene colour and depth (or have the lighting
  pass store them), and resume. The pattern exists in mcopt.
- The water fragment does Photon's whole surface model:
  - 3 Gerstner octaves × 3 height evaluations for finite differences = 9 noise fetches;
  - optional 4-step parallax;
  - exact Fresnel, biome absorption, refraction (tangent-normal offset, at most 8 blocks), edge highlight,
    SSR or sky-map reflection.
- Because we hold the opaque copy, refraction and depth fog happen **in the water fragment itself**. That folds Photon's
  `c1_blend_layers` full-screen pass into the translucent pass and saves a full pass (0.3–0.5 ms at native).
- Cost: 0.3–0.6 ms forward on 20–40% of pixels (est.) + SSR.
- The biome water colour arrives as the Sodium vertex colour (tint), the same as Photon's `tint`.

**Fog and VL:**
- Analytic height fog (closed-form airmass) inline in the lighting draw and the water/sky fragments. ALU only.
- **VL** as a half-internal fragment pass:
  - 12–16 steps against the cached far cascade, or a 1024² VL-only map;
  - blue noise + R1 + temporal;
  - output transmittance (lossy RGBA16F) + scattering.
- Apply VL in the TAAU input or in the translucent pass (fog in front of water).
- Cost: 0.3–0.5 ms (apple-gpu §5.3).
- Coloured light shafts: read the coloured shadow attachment in the same loop, +1 fetch per step.
- Cave, lava, blindness, Nether and End spherical fogs are inline formulas. The End emissive fog is a VL variant.

**Coloured lights (Ultra, later):**
- Photon voxelises in the shadow vertex shader; we can voxelise **from Sodium section data**: a compute kernel over the
  pulled arena that marks emitter/tinter quads using the atlas material mask.
- Then a flood-fill compute into two 128³ RGBA16F volumes (32 MB total; use RGBA8 or RG11B10 to halve it) at 0.3–0.8 ms
  (est., bandwidth-bound: 2.1M voxels × 7 taps from cache).
- Compute with ShaderWrite is fine here, because 3-D volumes are not full-screen render targets (apple-gpu §9).
- Coloured lights are where the Metal backend clearly beats macOS GL Photon, which cannot run them at all.

**TAAU:**
- Our M2 TAAU should take Photon's best ideas:
  - Reinhard-wrapped YCoCg;
  - HRAA soft AABB + variance clip;
  - pixel-age α with a distance-dependent floor;
  - off-centre rejection;
  - confidence^k from the Catmull-Rom reconstruction weight;
  - Karis flicker reduction.
- Add **real motion vectors** (Photon has none): per-object motion for entities and particles, plus a reactive mask for
  water animation.
- Use R2 jitter (Photon) or Halton(2,3) with 32 phases at 0.5x (apple-gpu §3.1).
- Mip bias log2(scale) − 1, milder for pixel art (apple-gpu).
- Cost: 0.62–0.72 ms (measured, fragment pass into compressible history).

**Bloom:**
- Photon's 6-level 13-tap CoD downsample + blur + progressive upsample, with a *lerp* composite at 0.12.
- Use a real mip pyramid (fragment passes into a lossy RG11B10F or RGBA16F chain) instead of Photon's atlas; mip chains
  avoid the merge pass and per-tile bound checks.
- Compute the first level from the *internal-resolution* image, before upscaling (saves about 0.2–0.3 ms).
- Cost: 0.5–0.74 ms.

**Final pass (one pass):**
- bloom lerp; exposure (fixed EV 0 to start, Photon-style; histogram auto exposure later, borrowable from Spectrum,
  MIT); vignette; grade-in (log contrast, saturation, white balance); tonemap; grade-out (HSL teal/orange/green, gain
  S-curve); CAS-like sharpen; Bayer/blue-noise dither to 8-bit.
- Cost: 0.35–0.45 ms.
- Photon's c19 + final + FXAA are three passes; ours is one.
- For the curve, offer **Lottes (Photon's parameters)** as the "Photon look" and AgX (apple-gpu §5.3) as the alternative.
  An XDR mode maps the same curve with the white point raised to the EDR headroom.

**Purkinje shift:** ALU only, inline in the lighting/sky fragments (as Photon does) or in the final pass.

**Waving foliage:**
- In `sodium_terrain.vs.metal`, using the material mask (leaves, plants, tall-plant halves) to choose the displacement.
- Photon's "top vertex" test needs the quad's uv relative to the sprite centre. Corner fetch gives the quad's 4 uvs, so
  the min-v corner is the top.
- The shadow vertex function must apply the same displacement.
- Player push needs the eye position from `ShadeFrame`.
- Keep the varyings small; the vertex stage limits terrain on Apple.

**Rain/snow:** override the weather pipeline with Photon-style tinted, slanted rain. Puddles and ripples go inline in the
lighting draw (2 noise fetches + 3 ripple fetches only where wetness > 0 and N_y > 0.99).

**Nether/End:** constants plus fog variants in the same shaders, keyed by `ShadeFrame.dimension`. The End sun flare is in
the sky fragment.

### 6.2 Data we must add that Photon gets from Iris

| Photon input | Our source |
|---|---|
| `mc_Entity` block ids (`block.properties`) | Atlas material mask R8, built at atlas stitch from block-to-sprite mapping, with Photon-like classes (water, leaves, plants top/bottom, sand, ice, metals, emitters by class, glass/tinted) |
| `at_tangent`, normals | Corner-fetch face normal; the tangent from the quad's uv gradient (only needed for LabPBR normal maps) |
| `mc_midTexCoord` | Corner fetch (sprite bounds from the 4 corners) |
| `gbufferPrevious*`, `previousCameraPosition` | `ShadeFrame` previous matrices (M1) |
| `sunPosition`, `shadowModelView`, `worldTime`, `moonPhase`, `rainStrength`, `wetness` | `ShadeFrame` (M1) + weather state (CPU) |
| `eyeBrightness` (smoothed), `biome_*` | Java: client light at the eye and biome at the camera, smoothed per frame |
| `heldItemId`, `heldBlockLightValue` | Java: main-hand/off-hand item light emission |
| `centerDepthSmooth` (DOF) | 1-texel readback of last frame's depth, or skip DOF |
| `colortex5` previous frame for SSR | Our TAAU history (compressible RGBA16F) |

### 6.3 Cost budget at 3024×1964 output on the M4

The "Photon-equivalent look" on our pipeline. "meas." means a measured number from apple-gpu; everything else is an
estimate. Columns are 0.5x (1512×982) and 0.67x (2016×1309) internal resolution.

| Pass | 0.5x ms | 0.67x ms | Notes |
|---|---|---|---|
| Vanilla-equivalent opaque geometry (TBDR, face culled) | 1.35 (meas.) | 1.42 (meas.) | baseline |
| Inline Photon lighting (PCSS 6–12 taps, SH, SSS, specular, fog, puddles) | +0.2–0.4 | +0.2–0.5 | hidden partly behind geometry (apple-gpu §2.1) |
| Shadow map: 2 cached cascades, sun-facing faces, + colour attachment | 0.7–1.0 | 0.7–1.0 | M1 budget; RT in M4 replaces it (inline RT about 0.05–0.3) |
| Sky view map + SH + coverage/cloud-shadow maps | 0.1–0.15 | 0.1–0.15 | small fixed-size passes |
| Clouds march (¼ res) + reconstruction (½-res history) | 0.45–0.9 | 0.55–1.3 | biggest variable item; quality-scaled step counts |
| GTAO half-res + temporal | 0.1–0.15 | 0.15–0.25 | consumed reprojected next frame |
| VL half-res 12–16 steps | 0.32 (meas.) | 0.47 (meas.) | |
| Water/translucent forward incl. refraction + fog | 0.3–0.5 | 0.4–0.6 | replaces Photon `c1` |
| SSR on reflective pixels | 0.25–0.4 | 0.3–0.5 | M4: RT reflections |
| TAAU → output | 0.62 (meas.) | 0.72 (meas.) | |
| Bloom (first level from internal) | 0.5 | 0.55 | measured 0.74 when built from the output image |
| Final grade/tonemap/sharpen/dither | 0.35 (meas.) | 0.35 (meas.) | |
| **Total GPU** | **≈ 5.3–7.0** | **≈ 6.0–8.3** | 120 fps budget = 8.33 ms; also entities, particles, GUI (+1–2) |

Conclusions:
- Photon's full look at 0.5–0.6x internal + TAAU holds about 120 fps with headroom only if the clouds are scaled.
  Dynamic resolution between 0.5 and 0.67 is the right control.
- "Quality" at 0.75–1.0x + TAA lands at 70–100 fps.
- Per pass, the native TBDR + compressible-target design is about 2–3× more efficient than Photon's GL structure:
  - Photon at native: about 27 passes × several full-resolution RGBA16 reads/writes ≈ 1+ GB per frame ≈ 11–14 ms of
    bandwidth before any shading;
  - ours: about 6 full-resolution-ish passes, mostly compressible.

### 6.4 Suggested order (fits critique §5)

- **M1 (in progress elsewhere).** Adopt now, at no extra cost:
  - Photon's sun/moon exposure and tint curves;
  - limb-darkened sun disc;
  - Chapman transmittance for the light colour;
  - analytic height fog (Rayleigh + Mie, 4-octave);
  - lifted Lambert + bounced fill;
  - block-light falloff polynomial + hot spot;
  - the directional face shading;
  - PCSS-lite (blocker search 3 + PCF 6–8, sharpening).
- **M2:**
  - HDR + Lottes/AgX final with Photon grade;
  - bloom (lerp 0.12);
  - TAAU with Photon's clip/age/off-centre/confidence logic;
  - water (Gerstner octaves, absorption from biome colour, Fresnel, SSR from history, in-pass refraction);
  - atlas material mask (SSS/sheen for foliage, emitter classes, waving);
  - sky LUT + sky view map + SH skylight;
  - VL;
  - GTAO.
- **M3:**
  - clouds (L0 + cirrus first, then altocumulus and congestus) with cloud shadows + crepuscular rays;
  - CPU weather system;
  - stars/moon phases/aurora/rainbows;
  - coloured shadows + caustics;
  - puddles;
  - Nether/End looks;
  - LPV coloured lights (Ultra).
- **M4:**
  - RT sun shadows (removes the cascade cost and the bias problems);
  - RT water reflections;
  - RT SSS thickness for leaves.

### 6.5 Concrete adaptation list (from the MIT snapshot `work/packs/photon-mit-26c2906e/shaders/`, attribution required)

Translate to MSL with `half` where possible. Keep a header: "Adapted from Photon Shaders (c) Benjamin Stott
'sixthsurge', MIT License, commit 26c2906e".

1. `include/sky/atmosphere.glsl` + `image/atmosphere/scattering.dat`: the LUT uv mapping, Chapman transmittance, ozone
   density.
2. `include/sky/projection.glsl`: the sky view map projection (Hillaire 2020 non-linear latitude).
3. `include/utility/spherical_harmonics.glsl`: SH9 projection and cone-convolved irradiance.
4. `include/sky/clouds/{common,cumulus,cirrus}.glsl` + `program/d1_clouds.fsh`, `d2_clouds_upscaling.fsh`: layer
   shaping, the 8-octave scattering, powder, aerial perspective, checkerboard reprojection.
5. `include/lighting/shadows.glsl` (PCSS/PCF/colour parts only; **not** `distortion.glsl:get_shadow_bias` or the
   edge-factor leak fix).
6. `include/lighting/diffuse_lighting.glsl`, `colors/light_color.glsl`, `handheld_lighting.glsl`: the lighting model and
   exposure curves.
7. `include/lighting/bsdf.glsl`, `specular_lighting.glsl`, `include/misc/raytracer.glsl`: GGX, Hammon, sphere-light
   NoH, SSR march.
8. `include/lighting/ao/gtao.glsl` + `program/d3_ao.fsh`: GTAO with bent normal and temporal accumulation.
9. `include/fog/air_fog_vl.glsl`, `water_fog_vl.glsl`, `simple_fog.glsl`, `end_fog_vl.glsl`.
10. `include/misc/water_normal.glsl`, `rain_puddles.glsl`, the water parts of `program/gbuffers_all_translucent.fsh`.
11. `program/c4_taa_exposure.*`, `c3_taau_prep.fsh`: TAA/TAAU logic.
12. `program/c5_c10_bloom_downsample.fsh`, `c12/c13` (as a mip chain), `c14_color_grading.fsh` (Lottes + grade; **not**
    `tonemap_tech` or `tonemap_ozius`).
13. `include/misc/purkinje_shift.glsl`, `include/sky/aurora.glsl`, the stars/moon/sun functions in `include/sky/sky.glsl`.
14. `include/vertex/displacement.glsl`: waving plants.
15. `include/lighting/lpv/*.glsl`: flood-fill (Ultra tier).

From **Spectrum** (MIT, `work/packs/spectrum/shaders/`, attribution "(c) Jacob Eriksson (Zombye), MIT"):
- `include/color/display_transform/*`: an alternative perceptual tonemap/display transform with absolute-luminance
  colourfulness; a good candidate for the XDR mode.
- `program/histogram.glsl` + `autoexposure.glsl`: histogram exposure.
- `include/fragment/waterCaustics.fsh`.
- `program/rsmCompute.glsl` / `rsmFilterIteration.glsl`: RSM GI, if we ever want sun-bounce GI beyond Photon's fake.

**Avoid:**
- the Dave Hoskins hash (CC-BY-SA) in `include/utility/noise.glsl:74`;
- anything else marked with another author's license.
