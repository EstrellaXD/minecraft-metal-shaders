# SEUS (Sonic Ether's Unbelievable Shaders): technical and artistic study

Report name: `packs/seus`. Research only: nothing in mcopt, the M1 worktree or the user's instances was touched, no game
was launched, and no GPU work was run.

Author of SEUS: Cody Darr ("Sonic Ether"). Studied: **SEUS Renewed 1.0.x** (the rasterized line), **SEUS PTGI E1-E12
and HRR 1-3** (path-traced GI), plus the two **MIT-licensed** projects in which the same author published code for
techniques SEUS is known for: **SE Natural Bloom & Dirty Lens** and **SEGI** (voxel cone-traced GI).

---

## 0. Summary

1. **I did not read the SEUS Renewed or PTGI shader source, and I recommend not downloading it without the user's
   explicit OK.**
   - Every official download is covered by the "SEUS License". The Renewed page requires ticking "I have read and
     accepted the EULA" before the POST to `agree.php`. The license text also says that *downloading or storing a copy*
     counts as agreement.
   - Accepting a license on the user's behalf is something only the user can authorize. So I studied the official
     public technical material instead (§1).
   - The license allows personal use and modification. It **forbids redistribution and forbids using "parts of SEUS -
     including snippets from its source code" in other work**. So even after a code read, nothing from SEUS
     Renewed/PTGI may be copied into our shaders. A code read would only sharpen the technique notes. The art direction
     and the main techniques are already well documented by the author's own changelogs and dev logs.
2. **The author's own MIT code covers two of SEUS's signature techniques, and we may adapt it with attribution:**
   - **Natural bloom.** Threshold-free, energy-conserving bloom made of 8 octaves blended in with `lerp`, plus a
     lens-dirt layer and a depth/fog-aware glow (§13).
   - **SEGI.** Voxelization with atomic averaging, mip-chained 3D radiance volumes, cone tracing, half-res tracing with
     blue-noise rotation, temporal reprojection with neighbourhood clamp, a bilateral blur and upsample, and a per-voxel
     secondary-bounce feedback loop (§14). SEGI is the direct ancestor of the PTGI design.
3. **What makes SEUS look like SEUS:**
   - photographic, physically motivated light: a bright warm sun against blue sky-fill shadows, and consistent sky, fog
     and cloud colours coming from one set of physical functions (E12);
   - **soft, wide, threshold-free bloom** that makes bright air glow;
   - very clean, filmic TAA;
   - clear water with real absorption colour and caustics;
   - in PTGI, true bounce light and colour bleeding with dark, believable interiors.
4. **For our M4 pipeline, this is the SEUS-like recipe that fits 120 fps at 3024x1964** (details and costs in §18):
   - analytic sky plus a 2D "fake-3D" cloud layer cheap enough to reuse in reflections and ambient light (PTGI E11/E12);
   - cascaded, sun-facing shadow maps with disc-shaped PCSS, later inline RT;
   - natural bloom, exposure, a SEUS-style hue-preserving tonemap and a Purkinje night filter, all merged into the final
     pass;
   - water with physical absorption, a pre-baked 4-depth caustics texture and underwater VL.

   PTGI-class GI belongs in the 60 fps Ultra tier (M4: hardware RT plus a voxel radiance cache plus an SVGF-lite
   filter, about 1-2 ms).

---

## 1. Sources, access status and what each one establishes

### 1.1 What I could get from official sources

| Source (official) | What it is | Status |
|---|---|---|
| `https://www.sonicether.com/seus/` | Official SEUS page: product descriptions, download list (PTGI HRR Test 2.1, E12, E11; Renewed 1.0.1; v11; v10.2 Ultra), gallery (file names like `stained-glass-shadows-1.jpg`, `Torchlight_Bump_After-1.jpg`, `ptgilava.jpg`) | Read |
| `https://www.sonicether.com/shaders/download/renewed-v1-0-1/` | Renewed 1.0.1 download: 6.73 MB, 2020-02-10, "OptiFine HD U F5", changelog "Fixed graphical glitches related to TAA occurring on newer NVIDIA graphics hardware/drivers". **EULA checkbox + POST form** | Read the page and EULA. **Not downloaded** (needs EULA acceptance) |
| Patreon campaign 1205892, 52 posts from 2017-10 to 2024-07, of which 45 are public | Release notes and dev logs for Renewed beta 0.9 → 1.0.0, Renewed E1/E2, PTGI E1-E12, LPVGI E1, HRR Test / 2.1 / 3, and the 2022-2024 rewrite notes | **Read all public text.** Saved as `work/packs/seus/official-notes/patreon-public-posts-2017-2024.txt` (boilerplate stripped) for the team's reference only, not for redistribution |
| PTGI zips as Patreon public-post attachments: `SEUS PTGI E12.zip` (4,350,105 B, post 32990443), `SEUS PTGI HRR Test 2.1.zip` (4,402,258 B, post 45141775), `SEUS PTGI E11.zip` (8,764,969 B, post 32144448). HRR 3 is attached to post 60268558 | Free since 2021-07 ("Patreon Page Changes" post) | **Not downloaded.** The same SEUS License applies |
| `github.com/sonicether/SE-Natural-Bloom-Dirty-Lens` | Unity post effect, **MIT, © 2019 sonicether** | Cloned: `work/packs/seus/SE-Natural-Bloom-Dirty-Lens/`. Fully read |
| `github.com/sonicether/SEGI` | Unity voxel GI, **MIT, © 2017 sonicether** | Cloned: `work/packs/seus/SEGI/`. Core shaders read |
| `github.com/sonicether/SEUS-Mod-Compatibility-Helper` | GPL-3.0; only a GitHub Pages template stub, no code | Cloned; nothing usable |

CurseForge returns 403 to scripted requests, and Modrinth has no official SEUS project; the only Modrinth hit is
"UShader" by RRe36, which is unrelated. sonicether.com is the canonical source, and the author links Patreon from it.

### 1.2 What would need the user's approval

To read the actual Renewed/PTGI GLSL, the user must say they accept the SEUS License (personal use only, no
redistribution, no reuse of snippets). Then download:
- `SEUS Renewed v1.0.1`: 6.73 MB, from `sonicether.com/shaders/download/renewed-v1-0-1/` through the EULA form;
- `SEUS PTGI E12.zip`: 4.35 MB, Patreon post 32990443 attachment;
- optionally `SEUS PTGI HRR Test 2.1.zip`: 4.40 MB.

These would be unzipped to `work/packs/seus/{renewed-1.0.1,ptgi-e12}/` for study. My recommendation is that this is
**optional**: the facts that matter for our design are already established below, and the license forbids reusing code.

### 1.3 Confidence tags used below

- **[V-dev]**: verified from the author's own release notes or dev logs (post id given).
- **[V-MIT]**: verified by reading the author's MIT source; file path given.
- **[I]**: my inference or general knowledge of the OptiFine pipeline. Not verified against SEUS code; treat it as a
  design suggestion, not a fact about SEUS.

---

## 2. Lineage and pipeline shape

| Line | Years | Rendering | Notes |
|---|---|---|---|
| Legacy SEUS v10.x / v11 | 2013-2016 | OptiFine, forward + composite | Shipped as **separate packs per tier** (the site still lists "v10.2 Ultra") |
| **SEUS Renewed** beta 0.9 → 1.0.0 (2017-12-18) → 1.0.1 (2020-02) | 2017-2020 | Rasterized. TAA as a core feature, atmospheric scattering, shadow-map lighting, caustics, POM, motion blur | Patreon 14863620 "A New Beginning" introduces the Renewed TAA. In 15949581 the author says the next step is "a proper deferred + forward pipeline for better handling of transparent rendering", which implies **1.0 handles translucents in a shared structure with known issues** [V-dev] |
| Renewed E1 (2018-05) / E2 (2018-08) | experimental | + leaf SSS, land scattering matched to the sky, blue-noise texture, Lottes tonemap, improved TAA (made "Micro Bloom" obsolete), improved GI and caustics, auto normal/specular generation, anaglyph 3D; **E2: volumetric clouds that drive a weather system (sunshowers)** | 18917158, 20912484 [V-dev]. E1 uses geometry shaders that "can't dynamically be disabled" (fixed cost) |
| SEUS LPVGI E1 (2018-07) | experiment | Light propagation volumes: infinite bounces from all light types, but light takes seconds to "fill", leaks at corners, and the LPV size depends on screen resolution | 20122720 [V-dev]. Abandoned in favour of PTGI |
| **SEUS PTGI** E1 (2018-03) → E12 (2020-01) | | Built "on top of an old beta version of SEUS Renewed" (26671952). Software ray tracing through a **voxelized world volume** stored in OptiFine's shadow-pass buffers | Windows-only in practice ("SEUS PTGI only works on Windows anyway", 43444479) |
| PTGI HRR Test / 2.1 / 3 (2020-08 → 2021-12) | | + "Half-Resolution Rendering": half per-axis internal resolution with temporal reconstruction; POM with depth writes; RT contact shadows; 1.18 support | 40196477, 45141775, 60268558 |
| Rewrite (2022-2024, unreleased) | | Sparse per-quad scene structure (all geometry including entities; rectangle quads only), up to 32 chunks and full world height, per-pixel emission, **ReSTIR** | 70106094, 84935136 |

### 2.1 Why PTGI has those odd setup requirements [V-dev, explanation I]

Every PTGI release requires:
- **Shadow Quality 1x**. The shadow buffers double as the voxel data store, so their size must match a fixed atlas
  layout.
- **Alternate Blocks OFF** and **Natural Textures OFF**. Random model and texture rotations break the voxelizer's
  assumptions and the normal maps.
- **Trees Fancy/Fast, not Smart**. Smart changes which leaf faces exist.
- **Old Lighting DEFAULT**.
- **No custom block models**, because block shapes are hard-coded per block ID (§9).

Lesson for us: deriving scene data from the shadow pass is fragile. Our Metal backend can build voxel or BVH data
directly from Sodium's section meshes (apple-gpu §4) and avoid all of these constraints.

---

## 3. Sky and atmosphere

**Renewed [V-dev 18917158]:** a physical atmospheric-scattering sky. In E1 "land atmospheric scattering matches sky
model", meaning terrain haze uses the same scattering functions as the sky dome.

**PTGI's sky went through three generations:**
1. **E7 (26671952):** "Since the sky is being sampled by reflections and GI, it's important that the sky model itself is
   lightweight." A new, cheaper model replaced the Renewed-derived one.
2. **E11 (31414296, 32144448):** a new approach that "relies more heavily on the math": a lot of work to derive formulas,
   after which "the shader code is as simple as evaluating the final formula, without any iteration loops". In other
   words, a **closed-form, loop-free atmosphere**: no ray-marched optical depth and no LUT.
3. **E12 (32990443):**
   - "Everything is now using the same physically based functions": sky, lighting and cloud colours share one model;
   - "Added 'horizon' to sky", so below the horizon there is a fake distant ocean/landscape instead of a white void;
   - "Improved land atmospheric scattering";
   - **HRR 3 (60268558):** white balance adjusted "to make sky bluer and sunlight less dingy".

**How to build the equivalent (our formulation, [I]):**
- **Model.** Rayleigh plus Mie single scattering over a planet sphere. The optical depth along a ray from altitude *h* at
  zenith angle *θ* uses an analytic **Chapman-function approximation** for each species (scale heights about 8 km for
  Rayleigh and 1.2 km for Mie). That gives transmittance T(h,θ) = exp(−Σ β_i · H_i · Ch(x_i, θ)) with no loop.
- **In-scattering along the view ray** has two parts:
  - a closed form for the integral of an exponential density times sun transmittance, taken at the "optical midpoint" of
    the ray;
  - an **ambient multiple-scattering term**: a constant or zenith-gradient fraction of the single scatter, coloured
    toward the Rayleigh hue. It stops twilight going grey.
- **Phases:** Rayleigh 3/16π(1+μ²); Cornette-Shanks or Henyey-Greenstein for Mie, with g ≈ 0.76-0.8 for the bright
  aureole around the sun.
- **Using one function everywhere ("same functions everywhere"):**
  - the **sky dome**;
  - a **sky irradiance** for ambient light: the sky integrated over a hemisphere, precomputed once per frame into a tiny
    SH or a 3-colour gradient;
  - **sun colour at the ground**: T toward the sun, which gives golden hour for free;
  - **aerial perspective on terrain**: the same in-scatter evaluated for the view segment of length *d*;
  - **cloud lighting** (§5).
- **Horizon:** below the horizon, blend toward a dark, slightly desaturated "ground albedo × sky irradiance" colour with
  a soft step. This keeps reflections and the bottom of the sky from glowing white.

**Cost driver:** ALU only. Evaluated per pixel only where the sky is visible. For reflections and ambient light, bake it
once per frame into a 64x32 to 128x64 lat-long texture: under 0.05 ms (apple-gpu §5.4 estimates under 0.1 ms for LUT
skies).

---

## 4. Sun, moon, stars, night

**[V-dev]:**
- **E12:**
  - "Reduced the size of the sun as rendered directly in the sky";
  - "Made the sun look less like a lonely, dim, ugly white disc": the visible disc is small but very bright, with limb
    darkening and a strong Mie aureole and bloom carrying the glow.
- **HRR 3:**
  - **Moonlight overhaul**: the moon phase scales night lighting, bright at full moon and very dark at new moon;
  - moon colour tweak;
  - **starlight contributes to GI** at night, so new-moon nights still have a faint, cool ambient;
  - a "physically-inspired low-light vision effect which now applies on top of the entire image" replaces per-light-type
    hacks.
- **E6:** "Night time 'blue filter', simulates how the human eye sees low intensity light". This is a **Purkinje / scotopic
  shift**.
- **E9:** sunlight colours re-tuned "to be more natural".

Aurora: not mentioned anywhere in official material, so SEUS does not appear to have one.

**Implementation notes [I]:**
- **Sun disc:** angular radius about 0.27°, maybe up to 0.5° for a game look. Radiance = E_sun / (π·r²) × limb
  darkening (1 − u(1 − √(1 − (ρ/r)²)), u ≈ 0.6), multiplied by the atmospheric transmittance toward the sun.
- **Moon:** sample the vanilla moon-phase texture as the disc (the phase index is already in vanilla's state). Scale
  moonlight by the illuminated fraction, (1 − cos φ)/2, with a small floor.
- **Stars:** keep vanilla's star geometry, or hash a direction grid. Twinkle with time, extinguish with T near the
  horizon, and add a faint additive starlight term to the ambient light.
- **Purkinje / low-light vision**, in the final pass, before tonemap:
  - compute a scotopic luminance (about 0.35R + 0.65G + 0.0B in linear sRGB-ish weights; rod sensitivity peaks near
    507 nm);
  - estimate how "dark-adapted" the scene is from the exposure value;
  - blend chroma toward a desaturated blue-grey (about 0.6, 0.75, 1.0) in proportion to that adaptation;
  - optionally add a little luminance noise for the "low-light grain" feel.

  It costs a few ALU instructions in the final pass.

---

## 5. Clouds

Two distinct designs, both useful to us.

### 5.1 Renewed E2: volumetric clouds and weather [V-dev 20912484]

"All-new volumetric clouds powerful enough to drive a new weather system". Cloud coverage drives rain, giving emergent
"sunshowers" when rain coincides with a gap in the cover. Biome-specific weather was planned.

Mechanism [I]: the usual ray-marched density field (FBM/Worley over a coverage map), a sun light march, Beer-Powder
lighting and an HG phase. The weather state is a function of the coverage near the player, and rain intensity is tied
to the local density. We cannot drive Minecraft's real weather, but we can **tie visual cloud coverage to vanilla's
rain/thunder level** so that clouds thicken before rain.

### 5.2 PTGI E11/E12/HRR 3: 2D clouds that look 3D [V-dev 31414296, 32990443, 60268558]

- **Design goal:** clouds "cheap enough to have them render in raytraced reflections and GI. So, volumetric clouds were
  out of the question". The author pushed "a 2D clouds system to look 3D", with no ray marching.
- **Two layers:** cirrus and cumulus (E12).
- **Coverage:** varies "from almost no clouds to somewhat cloudy over long periods of time". The clouds "extend to the
  horizon".
- **Look options:** a blocky option "to bring some Minecraft essence to them" (an option for both looks).
- **Lighting:** clouds contribute to GI and reflections; "warmly lit clouds" at sunset create drama; rain lighting is
  reworked.
- **HRR 3:** less coverage directly overhead and more near the horizon. Cloud and sky colours are balanced so that
  ambient shadows are not washed out when "Clouds in GI" is on.

How to make a 2D layer read as 3D (our method, [I]):
1. **Intersection.** Intersect the view ray with a plane, or better a thin spherical shell, at altitude H_c (cumulus
   about 1.5-2 km scaled to taste, cirrus about 8 km).
2. **Density.** Sample density D(x,z) = remap(FBM(x,z), 1 − coverage, 1) × shape at the hit point. For the blocky
   option, quantize (x,z) to a cell grid (for example 12-blocks wide) before the noise, and soften the cell edges by
   about 1 cell/16.
3. **Fake thickness.** Treat D as optical thickness τ = D·k, so transmittance is exp(−τ).
4. **Fake self-shadowing.** Take 2-4 extra density taps offset from the hit point toward the sun's projection on the
   plane (in the direction of L_xz, with growing step). Their sum τ_s gives lit "tops" that face the sun and dark
   bellies: light = sun × exp(−τ_s·k_s) × "powder" (1 − exp(−2τ)) × HG(μ, g₁ ≈ 0.6) mixed with HG(μ, g₂ ≈ −0.3).
   Multiple scattering is approximated by adding 2 octaves with attenuated τ and g.
5. **Ambient.** Add sky irradiance from §3 × (1 − 0.5·D), for blue-grey bellies.
6. **Silver lining.** Forward scattering at low τ gives bright edges when the sun is behind the clouds.
7. **Horizon.** Fade toward the sky's aerial perspective with distance along the plane. Both layers reach the horizon
   because the plane extends to infinity; use the shell to avoid an infinitely thin line.
8. **Coverage over time.** Coverage = smooth noise(dayTime / several days) remapped, plus a bias from the rain level.
   An "overhead less, horizon more" bias can be done by adding a function of the ray's elevation to the coverage
   (HRR 3's choice).
9. **Reuse.** The same function, evaluated at the sun direction, gives **cloud shadows** on the ground: one density
   lookup at the projection of each pixel's world position along L. It is also evaluated in the reflection sky lookup
   and in the per-frame sky-irradiance bake.

**Cost driver:** about 6-10 noise lookups per sky pixel (2 layers × (1 + 2-4 sun taps)). That is negligible at internal
resolution compared with volumetrics (§18).

---

## 6. Fog and atmospheric scattering on terrain

**[V-dev]:**
- Renewed E1 "land atmospheric scattering matches sky model".
- PTGI E4 "atmospheric scattering fog for distant terrain"; E8 restored it ("for nice distance fog"); E12 improved it.
- E5 added **rain fog**; E12 adjusted it.
- **Water fog**: E8 physically-based absorption; E12 "New physically-based water fog" with a "Water Fog Density" option;
  HRR 3 set the default density to 0 because of "glowing water" artifacts in caves.
- E9 fixed "atmospheric scattering causing lighting bugs during rain" and "ray traced reflections not considering
  atmospheric scattering". So fog is applied to reflections too.

**Method [I].** Aerial perspective: color = surface·T(d) + inscatter(d), with T and the in-scatter taken from the same
sky functions over the view segment (§3).
- The density scale is a free art parameter. SEUS-like images use visible blue haze from about 100-200 blocks out, much
  stronger than real air at Minecraft scale.
- **Rain:** raise the Mie density (grey-white), desaturate the sun and lower the sun intensity. Clouds already darken the
  sky irradiance.
- **Caves:** fade the sky in-scatter by the skylight level (lightmap .y), so caves do not fill with blue haze. This is the
  same skylight-based gating SEUS uses for its leak fixes (§9).

---

## 7. Volumetric light ("Atmospheric Godrays") and underwater shafts

**[V-dev]:**
- E11 moved the "Atmospheric Godrays" option into the new "Atmospherics Options" screen.
- HRR 3 "Increased max ray distance for Atmospheric Godrays".
- E8 added **underwater godrays/volumetric lighting with caustics influence**: the shafts are modulated by the caustics
  texture, so underwater rays shimmer.
- Renewed 1.0.0's roadmap mentions redesigning "volumetric clouds and crepuscular rays".

**Method [I].**
- March from the camera to the depth (capped at a maximum distance), 12-16 dithered steps at half resolution.
- At each step, sample a shadow visibility and multiply by the phase function HG(μ, 0.6-0.8) × medium density × the
  transmittance accumulated so far. Accumulate temporally.
- **Underwater:** multiply each step by the caustics pattern at that point's (x,z) and depth below the surface, and use
  water's absorption coefficient as extinction. The result is the strong, slowly moving shafts of the SEUS look.
- Cost: see §18. apple-gpu §5.3 measures 0.32/0.47/0.80 ms for 16 taps at half resolution (0.5x / 0.67x / native).

---

## 8. Shadows

**Verified facts [V-dev]:**
- **Coloured shadows from stained glass** (PTGI E8, and the sonicether.com gallery image `stained-glass-shadows-1.jpg`
  for SEUS in general).
- **Water casts caustics shadows** (E8): the caustic pattern is projected through the water surface, not applied to
  "any surface behind water".
- E9 "Improved shadow appearance, spots of sunlight no longer look like little targets, but like full-shaded discs."
  This describes the **filter kernel**: the earlier kernel had visible rings (concentric sample rings). The fix gives a
  uniformly filled disc, the look of a correct PCSS or penumbra kernel with well-distributed samples (Vogel/Poisson disc
  with per-pixel rotation, resolved by TAA).
- E12 "Reduced sunlight shadow artifacts on surfaces where the sunlight is at a grazing angle": a slope- and NdotL-scaled
  bias and normal offset.
- **Screen-space shadows:**
  - E5 "screen-space GI tracing" lets entities cast indirect shadows;
  - HRR 2.1 "Reworked screen-space shadows: much more accurate and tighter shadows", with "nice contact shadows with
    POM";
  - POM with fragment depth gives "free" screen-space shadows;
  - HRR 3 "Added short raytraced contact shadows" (a few voxels against the voxel volume). This option is cheap.
- **Leaf sub-surface scattering** (Renewed E1); PTGI E4 "translucent GI on leaves".
- "Sunlight Leak Fix" / "Full Cave GI Leak Fix" / "Light Leak Fix" (E5, E8, HRR 3):
  - the HRR 3 version is "not based on where the player is", so it is per-surface;
  - it presumably gates sunlight and sky GI by the surface's skylight level, so sunlight never reaches surfaces whose
    vanilla skylight is 0 (inference).
- PTGI requires shadow quality 1x (§2.1), so shadow resolution is not user-tunable there.

**Unknown without a code read:** SEUS's exact shadow distortion function and its shadow distance/resolution defaults. Iris
and OptiFine packs typically use a radial distortion like Complementary's (`apple-gpu` §5.1).

**Techniques to adopt (our formulation):**

1. **PCSS with a "full disc" look.**
   - Blocker search: 8-12 taps in a fixed radius, average blocker depth z_b.
   - Penumbra width w = (z_r − z_b) · tan(θ_sun) · k. Use the sun's angular radius about 0.27°, raised to about 0.6-1°
     for a softer, more "photographic" contact-hardening look.
   - Filter: 12-16 Vogel-disc taps of width w, rotated per pixel by interleaved-gradient noise plus a frame index, then
     resolved by TAA.
   - The rotating Vogel spiral is exactly what removes the "little targets" rings.
2. **Coloured translucent shadows.** In the shadow pass, add an RGBA8 "shadowcolor" target:
   - translucent casters (stained glass, water, ice) write their tint multiplied by (1 − alpha) into it;
   - a separate opaque-only depth decides full occlusion;
   - in lighting: visibility_rgb = opaque_vis × (translucent depth < receiver depth ? tint : 1). Water writes a caustics
     factor instead of a tint.
3. **Leaf SSS.**
   - For leaf materials, add a transmission lobe: sun × visibility_from_shadow_map(using a *thicker* bias) ×
     albedo² × HG(−V·L, g ≈ 0.4-0.6) × k.
   - Use the distance through the leaf block, from the shadow-map depth difference, to attenuate:
     exp(−Δz·σ_leaf). Back-lit canopies then glow yellow-green.
4. **Contact shadows.** A short screen-space march (8-12 steps, 0.5-1 block) toward the sun from each pixel,
   thickness-tested against linear depth. With hardware RT (M4) this becomes the inline sun ray anyway.
5. **Grazing-angle bias.** bias = base + slope × tan(acos(NdotL)), clamped; also offset the receiver position along the
   geometric normal by about 1-2 shadow texels before projecting.

---

## 9. Lighting model, GI and block light

### 9.1 What SEUS does

- **Renewed: sun + sky + block light with a GI option [V-dev 18917158 "Improved GI"].** The GI technique in Renewed is not
  documented publicly. Legacy SEUS and Renewed are commonly described as using a shadow-map-based single bounce (a
  reflective-shadow-map style gather) [I, unverified].
- **PTGI: path-traced diffuse GI through a voxel volume [V-dev]:**
  - **E1:** 1 sample per pixel at half resolution, so effectively 1/4 spp; sky light only; only glowstone, lapis and
    redstone emit.
  - **E2 (20991507):**
    - moved to **1 spp at full resolution**;
    - "made the volume data more spatially coherent and cache-friendly" (a voxel layout optimization);
    - doubled the trace distance;
    - a denoiser that "preserves harsh lighting transitions for better contact shadows", with a user trade-off between
      detail and noise. About 50 fps at 1792x1008 on a GTX 1080.
  - **E3 (22356734): sunlight GI, and infinite bounces through a per-block feedback cache.**
    - Per-pixel bounces were cut from 2 to 1.
    - Separately, "per-block, light rays are randomly traced and accumulated in a feedback loop so that, with each new
      frame, an additional bounce is calculated."
    - Drawbacks: "light breathing" (low-frequency flicker) in dim areas, and slow adaptation.
    - This is a **voxel radiance cache with temporal accumulation**, the same idea as SEGI's secondary-bounce pass
      (§14) and modern DDGI-style caches.
  - **E4:**
    - torches and lava emit GI (later fire, jack o'lanterns, sea lanterns, redstone torches, magma, campfires, lanterns,
      candles, sea pickles, end rods);
    - GI "blends into Minecraft's lighting when out of range" (the vanilla lightmap is the fallback outside the voxel
      volume).
  - **E5 (24120613):**
    - **GI Responsiveness**: 8x more responsive by default, with less temporal reuse;
    - **screen-space GI tracing**, so entities, which are not voxelized, cast indirect light and shadow;
    - the sunlight leak fix.
  - **E6 (25496168, 25937144):**
    - the GI filter was redesigned so that it "can handle a wide range of light source intensity". The old filter
      over-blurred dim light and under-filtered bright light. This is the classic reason to use **luminance-variance-
      normalized edge-stopping weights (SVGF)** instead of fixed colour weights;
    - torches no longer need to be as bright as the sun.
  - **E7:** GI colour precision fixed in low light; secondary bounces brought to a more correct intensity.
  - **E9:**
    - **hard-coded box geometry per block ID** for stairs, slabs, fences, doors, trapdoors, pressure plates, snow layers
      and carpets (28423723). The cost was about 12% with only slabs, stairs and fences;
    - "Better depth-based rejection for GI filter";
    - a **custom Kelvin→RGB** for the "Torchlight Color Temp" option.
  - **E10 (30234504): adaptive temporal filter.** It detects where lighting changed on screen and forces the temporal
    filter to discard history there. The same mechanism lets the filter be *more* aggressive in stable areas. This is
    the A-SVGF "temporal gradient" idea. One frame of latency remained because of OptiFine's ordering.
  - **E11/E12:**
    - "much faster filter";
    - fixed over-darkening with normal maps and custom leaves;
    - "multi-bounce GI is no longer allowed to let indoor areas made up of brightly colored blocks look super
      neon/glowy", which means an albedo or energy clamp on secondary bounces;
    - clouds contribute to GI;
    - unlit campfires no longer emit.
  - **HRR 2.1:** "Fixed incorrect loss of energy in secondary light bounces", which brightened interiors realistically.
  - **HRR 3:**
    - the GI voxel area grew (the 2023 post says **335x335x335 centred on the player**, "about 10 chunks");
    - longer default rays;
    - **candle light colour from the candle texture and the candle count**;
    - "Candlelight Color Temp";
    - "Wet Caves Effect";
    - starlight in GI.
  - **Dynamic held light** (E8): held items emit a torch-coloured light, but only for the local player.
  - E7: entities, items and hand get "full specular + normal map support and render the same way as blocks".

### 9.2 Kelvin colour temperature for block light [I]

SEUS's own Kelvin conversion is "custom and not physically based at all" (E9), so we choose our own:
- Planckian locus → CIE XYZ → linear sRGB, normalized to luminance 1, precomputed into a 1D table of 1000-10000 K.
- Art defaults: torch about 2300-2700 K, lantern about 2600 K, candle about 1900-2100 K, soul fire implemented as a
  separately tinted cyan, glowstone about 3000 K, sea lantern about 6500 K with a cyan tint.
- Since Sodium's vertex gives only the block-light level, not the source, a single "torch" temperature drives all block
  light in M1/M2. Per-source colour needs the voxel or flood-fill tier (§18).

### 9.3 Block-light falloff [I]

The vanilla level L in 0..15 maps to illuminance with a steep curve, for example E = (L/15)^(3..5) × E_max. That gives a
bright core and quick decay, which reads like an inverse-square falloff. Use a small flicker, 1 ± 0.04·noise(t·8 + hash(
block)), only if a per-source position is available. Otherwise skip it, because flickering all block light uniformly
looks wrong.

---

## 10. Materials, reflections, weather wetness

**[V-dev]:**
- **Specular and normal maps** in SEUS's "old PBR" layout. The HRR 2.1 showcase uses "HardTop Vanillaccurate (OldPBR
  variant)".
- E8 options: which channel holds **smoothness** and which holds **metalness**, and "interpret smoothness as roughness".
- E6: support for 1k/2k packs, and a "Texture Resolution" option that must be set; normal-map strength depends on it
  (E9 fixed the resolution dependence).
- E6: "Surfaces with normal maps can no longer have impossible angles", i.e. the normal is clamped so that N·V > 0 for
  reflections.
- Renewed E1: "auto normal specular data generation" (derived from albedo when the pack has none). "Fixed POM and
  normal mapping on rotated textures/blocks".
- **POM, HRR 2.1:**
  - "a totally new approach to POM";
  - **fragment depth modification** gives screen-space shadows, correct reprojection and "displaced intersections", for
    example redstone dust embedded in the block;
  - "Smooth Parallax Surface" interpolates the heightmap for smooth slopes;
  - HRR 3 adds "Texture LOD Bias" and "Anisotropic LOD Bias".
- **Reflections:**
  - E4 world-space ray-traced reflections on water, glass, ice and any surface with a specular map;
  - E5 screen-space reflection tracing (rough);
  - E6 redesigned reflection noise filter; ray-traced reflections made "consistent and similar to sunlight specular
    highlights" (so RT reflections and the analytic sun specular share one BRDF);
  - E11 sampling switched to a **"clamped GGX distribution instead of phong"**, and rough reflections look up a
    **lower-resolution texture LOD** (prefiltering by roughness);
  - E11 the reflection filter **skips denoising where reflections are barely visible** (an optional optimization);
  - HRR 3 "Improved energy conservation in reflection filter".
- **Weather wetness:**
  - E4 rain ripples and a wetness effect;
  - E5 "material darkening when wet", rain splash on water, rain fog;
  - E8 the bilateral rain-splash option was made always-on "achieved in a cheaper way";
  - HRR 3 "Wet Caves Effect" (damp surfaces in caves).

**Method [I].** Wetness w = rain level × skylight exposure (lightmap .y > about 0.9, upward-facing normals more).
- **Darkening:** albedo ← albedo^(1 + w·0.6) (porous darkening) and roughness ← lerp(rough, 0.05, w·puddle).
- **Puddles:** a low-frequency world-space noise thresholded on up-facing surfaces, with the height from POM or noise
  deciding where water pools.
- **Ripples:** an animated ring-normal function from a hashed grid of drop events (2-3 overlapping cells, each with an
  expanding ring of radius r = (t − t₀)·v, a sin(k·(d − r)) profile and an exp fade). Perturb the normal of puddles and
  water.
- **Reflection on wet surfaces:** use the sky probe plus SSR or RT when available.

---

## 11. Water

**[V-dev]:**
- E4: water, stained glass and ice rendering; world-space reflections.
- **E8 water redesign (27746705, 28168773):**
  - **physically accurate absorption values** for water. The author was "amazed at just how much water absorbs longer
    wavelengths", which gives the blue/cyan tint on anything but shallow, close surfaces;
  - **caustics from a pre-rendered animated texture**, made by a custom tool:
    - tiles and loops seamlessly, **1-second loop at 60 fps, tiling across 2 blocks**;
    - generated with **the same water height function** as the surface, so the caustics match the waves;
    - **4 depth layers packed in RGBA** (shallow → deep), crossfaded by water depth. The author's comparison shows one
      layer versus four;
  - caustics are visible directly and in reflections;
  - underwater god rays use the caustics;
  - water casts caustic shadows.
- E12:
  - "New physically-based water fog" plus a density option;
  - "Reflections on water now better approximate apparent reflectivity based on the presence of waves", i.e. the
    effective Fresnel is evaluated over the wave normal distribution, not the flat plane;
  - fixed GI from clouds being too bright underwater.
- E5: rain splash rings on water.

**Our formulation [I]:**

- **Waves.** A height function h(x,z,t): sum of 4-6 directional Gerstner or sine octaves plus a small noise octave,
  wavelengths about 0.5-6 blocks, amplitude about 0.02-0.08 block for still water.
  - Normals come from analytic derivatives, or from a 64² tiled normal map baked per frame from h (compute, about
    0.01 ms).
  - Flowing water: advect along the vanilla flow direction. This needs a material mask plus a flow direction; the flow
    can come from the UV rotation of the flowing-water sprite.
- **Fresnel.** Schlick with F₀ = 0.02. "Apparent reflectivity with waves": average the Fresnel over the normal
  distribution by evaluating F at the perturbed normal **and** blending toward the flat-surface F with distance, because
  far waves are sub-pixel. A cheap "Fresnel roughness" term avoids the plastic over-bright horizon.
- **Reflection.** Sky probe (§3, including clouds and sun specular via GGX at roughness about 0.02-0.05) → SSR on water
  pixels only → RT on M4.
- **Refraction.** Offset the scene-copy UV by the normal's xz × k × min(depth_difference, 1). Reject the offset if the
  sample is in front of the water (depth test).
- **Absorption.** Use per-metre coefficients close to pure water: σ ≈ (0.45, 0.065, 0.017) m⁻¹ for R, G, B, plus a
  small art scattering colour.
  - Transmittance exp(−σ·d), where *d* is the underwater path length (view-ray distance from the surface to the floor).
  - In-scatter: (1 − T) × water scatter colour × (sky + sun irradiance below the surface).
  - The 1 m = 1 block scale gives the strong cyan at 5-10 blocks that SEUS is known for.
- **Caustics, our version of the E8 idea.** Generate the texture **at load time in a compute kernel**:
  - for 64 time steps, refract a dense grid of sun rays through h(x,z,t) and splat them onto receiver planes at 4 depths
    (for example 0.5, 1.5, 3 and 6 blocks);
  - the intensity is the photon density, or analytically the inverse Jacobian determinant of the refracted mapping;
  - store the result in a 3D RGBA8 texture of 128²×64, 4 MB, tiling 2 blocks;
  - shading: sample at the receiver's (x,z) + refraction offset, crossfade the 4 channels by depth, and multiply the sun
    term of underwater surfaces and of the VL march;
  - writing this ourselves keeps it original and matched to our wave function.
- **Underwater view.** Apply the same absorption from the camera, tint the ambient light, and add VL shafts with
  caustics. Snell's window (total internal reflection above the critical angle about 48.6°) when looking up is optional
  and cheap.

---

## 12. Translucents, glass, waving foliage

- **Translucents [V-dev]:** "Issues with the rendering of semi-transparent/translucent materials" is a standing known
  issue in every PTGI release. Renewed 1.0's roadmap is to move "to a proper deferred + forward pipeline for better
  handling of transparent rendering". Nether portals render "like stained glass" (E7). Stained glass appears in hand
  (E6) and tints shadows (E8).
- **Lesson:** do translucents as a **forward pass after deferred lighting**, which is exactly our plan (apple-gpu §2.1,
  §8 step 3).
  - Glass: tinted transmission of the scene copy + Fresnel reflection of the sky probe + a thin-surface absorption
    tint.
  - Stained glass at grazing angles: increase the tint via 1/|N·V| path length.
- **Waving foliage:** not mentioned in the public notes I read. SEUS packs historically had waving grass and leaves
  options, but this is unverified.
  - With Sodium's 20-byte vertex there is no block ID, so it needs the **atlas material mask** (critique §5 M2.2) read
    in the vertex shader by UV.
  - Displacement: wind(world_pos, t) × (top vertex ? 1 : 0) for plants, and full-block jiggle for leaves.
  - Motion vectors must include the displacement, or TAA smears.

---

## 13. Bloom and lens: SE Natural Bloom (MIT, code read) [V-MIT]

Files:
- `work/packs/seus/SE-Natural-Bloom-Dirty-Lens/Assets/SE Natural Bloom/Shaders/SENaturalBloomAndDirtyLens.shader`
- `.../Scripts/SENaturalBloomAndDirtyLens.cs`
- 16 lens-dirt textures in `.../Lens Textures/`

**Algorithm, in my own words:**
1. **Prepass ("clamp"):** clamp the HDR colour to non-negative and finite. Also compute a per-pixel fog factor
   1 − exp(−d·k), or its square, and store it in alpha multiplied by the pixel's luminance. Encoding it this way lets the
   factor survive the blur as a luminance-weighted average.
2. **Pyramid:** **8 octaves** (4 in low quality). Octave 0 is at 1/2 resolution (1/4 in LQ), and each next octave is
   /2 (/3 in LQ).
   - Each octave = a bilinear downsample of the previous one, followed by a separable Gaussian blur, applied **once for
     octave 0 and twice for later octaves**.
   - The blur is the 9-tap Gaussian done with 5 bilinear taps: weights about 0.227 (centre), 0.316 and 0.071 at offsets
     0, ±1.385 and ±3.231 texels.
3. **Composite.** Three weighted sums of the octaves:
   - **bloom**: weights i^s for i = 1..8, normalized to sum to 1. The "scatter" slider s maps to −2.5..+2.5, so the
     spectrum ranges from tight to very wide;
   - **lens-dirt bloom**: same form with its own scatter;
   - **fog bloom**: a fixed bell over the octaves (peaking at octave 4), multiplied by a per-octave "fog fix" that drops
     an octave when its blurred fog factor is lower than the pixel's own. This stops bright foreground objects bleeding
     haze into the distance.
4. **Blend: no threshold, no additive spill.**
   - color = lerp(color, bloom, I);
   - then lerp(color, dirtBloom, saturate(dirt_rgb·J));
   - then lerp(color, fogBloom, fogFactor).
   - The intensity sliders are passed as exp(x) − 1, which gives a perceptually linear slider. Defaults: bloom 0.05,
     dirt 0.05.

**Why it looks "natural":**
- Every pixel contributes, so there is no threshold edge.
- The lerp **conserves energy**: it redistributes light instead of adding it.
- A wide-scatter tail behaves like a lens's veiling glare.
- The depth-weighted "fog bloom" makes distant air glow around bright skies, which is a big part of the hazy,
  sun-drenched SEUS feel.

**Adapting to Metal (allowed, MIT):**
- Keep the math. Restructure the passes for TBDR: compressible RGBA16F or lossy targets, fragment passes, and a
  downsample→upsample chain (apple-gpu §5.3, 0.74 ms).
- The 8 octave weights can be folded into an upsample chain where each level adds `w_i × level_i` with a
  pre-normalized weight. That avoids sampling 8 textures in the final pass. Either structure costs about the same here,
  because the small levels are tiny.
- Take octave 0 from the **internal-resolution** HDR image, before TAAU (apple-gpu §5.3 saves about 0.2-0.3 ms).
- Lens dirt: one RGBA8 texture read in the final pass. Generate our own dirt texture, or ship the MIT ones with the
  notice.

**Other lens effects [V-dev]:**
- Motion blur: Renewed E1 improved it; E8 "Motion Blur HQ" and an intensity option; HRR 2.1 a foreground-aware technique
  that avoids revealing low-res edges.
- "Post Sharpening" (E8).
- The vanilla vignette overlay is force-disabled (E8).
- Anaglyph stereo (Renewed E1).
- Status effects: blindness, night vision, red flash when an entity is hurt, creeper flashing (Renewed 1.0.0, PTGI E8).
- "Visualize Dangerous Light Level" utility (HRR 3).

The bloom shader's own commented-out ghost lens flare is labelled by the author as "not very good"; skip it.

---

## 14. SEGI (MIT, code read): the ancestor of PTGI's GI [V-MIT]

Directory: `work/packs/seus/SEGI/`.

| Stage | File | What it does (own words) |
|---|---|---|
| Sun depth | `Resources/SEGIRenderSunDepth.shader` | Orthographic sun depth, used to decide which voxels are sunlit |
| Voxelize | `Resources/SEGIVoxelizeScene.shader` | A geometry shader picks each triangle's dominant axis and projects it onto that plane. Each fragment computes direct sun × albedo + emission and **atomically averages** it into a `RWTexture3D<uint>`. The colour is packed as HSV with a cube-root value (7-bit H, 7-bit S, 11-bit V, 7-bit count/alpha), and a compare-exchange loop adds to the running value (`EncodeRGBAuint`, `interlockedAddFloat4`) |
| Transfer + mips | `SEGITransferInts.compute`, `SEGIMipFilter.compute` | Decode the uint volume to RGBA16F (with origin shift for a volume that follows the camera), then build 6 mip levels (separate 3D textures) |
| Infinite bounce | `SEGITraceScene.shader` | For every voxel, trace a few cones from the voxel into the *previous* radiance volume, add sky × visibility, and accumulate into a secondary-irradiance volume, so each frame adds one bounce. This is the same feedback concept as PTGI E3 |
| Diffuse trace | `SEGI.shader` pass 0 + `SEGI.cginc ConeTrace` | Per pixel (half res by default): N cone directions on a golden-angle (Fibonacci) sphere, rotated by blue noise per frame, then bent toward the normal. Along each cone, sample the mip level ≈ cone width (step distance grows exponentially). Accumulate radiance × current visibility and update visibility with the sample's alpha (front-to-back). Leftover visibility × a sky gradient (plus a soft sun lobe) gives sky light. Outside the volume, fade to a "fake GI" = sky × hemisphere term |
| Temporal | pass 3 | Reproject with motion vectors and reject by world-position distance. Soft-clamp history toward the 2x2 neighbourhood min/max (25% blend) and blend with weight about 0.1 |
| Filter | pass 1 (bilateral blur) and pass 11 (bilateral upsample) | 9-tap separable blur with weights = depth similarity (threshold scaled by 1/N·V) × (N·N′)^24. The upsample is a 2x2 bilinear weighted by depth and normal, with a fallback when all weights fail |
| Reflections | `SpecularConeTrace` | One cone along R with width from smoothness, plus leftover visibility for the sky reflection |
| Settings | `SEGI.cs`, `SEGIPreset.cs` | Voxel resolution 128/256, 6 cones, 14 steps, half-res on, stochastic on, temporal weight 0.1, optional infinite bounces; a cascaded variant (`SEGICascaded.cs`, `*_C.*`) |

**What transfers to us:**
- **Directly adaptable (MIT):**
  - the cone-trace loop structure and its visibility accumulation;
  - the Fibonacci cone set with blue-noise rotation;
  - the temporal blend with world-position rejection and a soft neighbourhood clamp;
  - the depth+normal bilateral blur and upsample;
  - the voxel feedback bounce.
- **Better replaced on Metal:**
  - Geometry-shader voxelization becomes a **compute voxelizer fed from Sodium section meshes**, or simply from
    block-state data. Minecraft is already a voxel grid, so "voxelize" really means "write block albedo/emission per
    cell", which is cheap in compute.
  - Atomic HSV packing is unnecessary when each block maps to one cell.
  - On M4, hardware RT replaces cone tracing for sharp occlusion. Cone tracing remains a good **non-RT fallback** for
    M1/M2 Macs.

---

## 15. Temporal AA and upscaling (TAA, HRR), exposure, tonemapping

### 15.1 TAA and HRR [V-dev]

- TAA is a pillar of Renewed: "A New Beginning" shows 2x-zoomed TAA comparisons. E1 improved TAA and made "Micro Bloom"
  (a sub-pixel glow to hide aliasing) obsolete. 1.0.1 fixed TAA glitches on newer NVIDIA hardware.
- **HRR, half resolution per axis with temporal reconstruction (37885188, 40196477, 45141775, 60268558):**
  - Ideal: 3x fps; achieved 1.3-2x, **because OptiFine forced overhead passes that do not contribute**.
  - The author's key insight: **rays per output pixel drop 4x**, so GI and reflections get noisier, diffuse lighting
    "takes longer to fade", and disocclusions look worse.
  - Fix strategy: "new ways of rejecting information" (better history rejection).
  - HRR 2.1 added "AA Style":
    - **Responsive** (default): refresh quickly on change;
    - **Stable**: keep history longer.
  - HRR 2.1 added "Pixel Look":
    - **Sharp**: no change;
    - **Filmic**: "softens the rendered image before it's tonemapped" for a less video-gamey look.
  - "Skip All Anti-Aliasing" shows the raw internal image.
  - HRR 3 fixed blur when turning quickly, improved stability, and gave **entities proper motion vectors**.
- E8: "Post Sharpening" for render scale under 1.0.
- HRR 3 "Texture LOD Bias": higher values give more detail but more instability; "Anisotropic LOD Bias" keeps detail at
  grazing angles.

**Takeaways for our TAAU:**
- Our pipeline does not have OptiFine's overhead: TAAU is 0.62-0.72 ms (apple-gpu §3.2).
- Expose Responsive and Stable as a preset knob. In practice it is the history weight and clamp-box width:
  - Responsive: α about 0.1-0.15 and a 1.0σ variance box;
  - Stable: α about 0.05 and 1.5σ.
- Decouple noisy-effect resolution from the TAAU input:
  - run GI and reflections with their **own** temporal accumulation and history;
  - feed TAAU the denoised result;
  - give these effects their own responsiveness setting ("GI Responsiveness").
- The "Filmic pixel look" can be implemented as a pre-tonemap 3x3 soft kernel, or a lower TAAU sharpening. On a 254 ppi
  Retina screen, a slightly soft, filmic image is very pleasing.
- Mip bias: follow apple-gpu §3.1, starting at −1 at 0.5x for pixel art.

### 15.2 Exposure [V-dev]

- E4 added auto-exposure. E6 "Smooth auto-exposure (finally)! Fixes flickering when swinging tool/weapon". E9 fixed
  "auto-exposure adjustment reading from the wrong screen position", which suggests it samples a **centre-weighted
  luminance**. E11: "Exposure can now adjust to brighter scenes".
- Method [I]:
  - take the log-average luminance from a small mip of the internal HDR image, centre-weighted and excluding the hand;
  - EV target = log2(L_avg) − compensation;
  - adapt asymmetrically: faster toward bright, slower toward dark, τ ≈ 0.5 s / 2 s;
  - clamp the EV range so night stays dark (SEUS nights are dark, with the Purkinje shift).

### 15.3 Tonemapping families [V-dev names; curve math is public knowledge]

SEUS (PTGI) offers several operators:

| Operator | Where | Curve family (public math, in own words) |
|---|---|---|
| **SEUSTonemap** | Default since E7 | The author's own curve. E12 improved "handling of purely-colored bright highlights" and removed code "that caused unnatural colors"; HRR 3 "reworked default tonemapping for an emphasis on a bright, vibrant look". The exact formula is unknown without a code read. The changelog shows it is a **hue-aware** curve that desaturates over-bright saturated colours toward white instead of clipping per channel |
| **ACES** | E9 "proper ACES tonemap color transformations" | RRT+ODT with the AP1 input matrix and output matrix, not just the scalar Narkowicz fit. The usual fitted form is x(ax+b)/(x(cx+d)+e) in AP1 with the sRGB matrices around it |
| **ReinhardJodie** | E9 | A blend between luminance-Reinhard c/(1+L) and per-channel Reinhard c/(1+c), weighted by the per-channel result. Saturated highlights keep some hue while still shouldering |
| **Lottes** | Renewed E1 | Timothy Lottes' 2016 "Advanced Techniques and Optimization of HDR Color Pipelines" curve: y = x^a / ((x^a)^d · b + c), with *a* = contrast, *d* = shoulder, and *b*, *c* solved so that mid-grey in→out and hdrMax→1 are hit |
| "Black Depth" option | E6 | A black-level/toe control |

Our choice remains AgX (apple-gpu §5.3), with an optional "SEUS-like vibrant" look:
- AgX with the "punchy" look (saturation about 1.2-1.35, slight contrast);
- a **highlight hue-preservation** step: compute the curve on max(r,g,b) or a norm, apply the ratio, then desaturate
  toward white as the norm passes about 0.8. This gives exactly the "purely-colored bright highlights" behaviour.

Merge all of this into the 0.35 ms final pass.

---

## 16. Settings, profiles and platform behaviour

- **Legacy SEUS** shipped tiers as separate packs (Lite, Standard, Ultra; the site still offers "v10.2 Ultra").
- **Renewed 1.0.x:** one pack with option screens. Profile presets are not documented publicly, so the code read would
  confirm whether `shaders.properties` defines `profile.*`.
- **PTGI option screens [V-dev]:**
  - Raytracing Options: Full Raytrace Reflections, Geometry Trace Quality 0/1/2, GI Filter Quality, GI Responsiveness,
    Secondary GI Samples, Simpler GI Sky Model, Diffuse GI / Reflection Trace Length, the leak fixes;
  - Lighting & Shadows: Sunlight Intensity, Block/Torch Light Intensity, Dynamic Held Light, Torchlight and Candlelight
    Color Temp, Light Leak Fix;
  - Surface: Texture Resolution, smoothness/metalness channel, roughness interpretation, Smooth Parallax Surface,
    Texture LOD Bias, Anisotropic LOD Bias, Wet Caves Effect;
  - Atmospherics: clouds, Atmospheric Godrays;
  - Water: Water Fog Density;
  - Post-Processing: tonemap operator, Black Depth, AA (AA Style, Pixel Look, Skip All AA), Motion Blur HQ/Intensity,
    Post Sharpening;
  - Utility: Visualize Dangerous Light Level.
- **Defaults:** E6 turned screen-space reflection tracing, screen-space GI tracing and Full Raytrace Reflections OFF by
  default for performance.
- **macOS:** PTGI is officially Windows-only. Intel GPUs were "not guaranteed or planned at all", and early AMD support
  was broken. PTGI's reliance on geometry shaders or OptiFine instancing and big render-target budgets makes it a
  non-starter on macOS OpenGL 4.1.
  - For Renewed, I found no official macOS statement. A code read would show whether it uses `MC_OS_MAC` guards, as
    Complementary does at `lib/common.glsl:33` (apple-gpu §9).
  - In any case, our native Metal path is unaffected: compute, 3D textures and read-write textures are all available.

---

## 17. What we should emulate

These are the visual qualities that make SEUS great, ranked by how much of "the SEUS look" each one carries.

1. **One physical light model everywhere.**
   - Sun colour and intensity, sky dome, sky ambient light, distance haze, cloud lighting and reflection sky all come
     from the same atmosphere functions (E12).
   - That consistency is why SEUS scenes look photographed rather than graded.
   - Golden hour and twilight fall out of the transmittance for free.
2. **High dynamic range with honest exposure.**
   - The sun is far brighter than the sky, and the sky far brighter than block light.
   - Smooth auto-exposure, plus a hue-preserving, vibrant tonemap.
   - Interiors are genuinely dark, with light coming from openings.
3. **Threshold-free "natural" bloom with a wide tail and depth-aware haze glow.** Bright skies and the sun veil the image
   softly. There are never threshold blobs.
4. **Image stability.** Filmic, clean TAA, with responsive/stable trade-offs and a soft "filmic pixel" option.
5. **Water that reads as real water.**
   - Physical cyan-blue absorption with depth.
   - Wave-aware Fresnel.
   - Animated, depth-aware caustics on the floor, in shadows and in light shafts.
   - Clear shallows and deep blue depths.
6. **Shadows with character.**
   - Crisp contact, disc-shaped soft penumbrae, no acne at grazing angles.
   - Coloured stained-glass shadows.
   - Contact shadows on small details.
   - Glowing back-lit leaves (SSS).
7. **Clouds that reach the horizon and change over days.** Lit warmly at sunset, cheap enough to show in reflections and
   to cast shadows. A blocky option is a nice Minecraft nod.
8. **Bounce light (PTGI tier).** Colour bleeding, believable interiors, torch-lit caves with warm Kelvin light and soft
   indirect shadows, and no neon over-saturation from multi-bounce (energy-clamped secondary bounces).
9. **Night that feels like night.** Moon-phase-dependent brightness, starlight ambient and a Purkinje blue shift instead
   of a flat dark-blue tint.
10. **Weather mood.** Wet darkened materials, ripples, rain fog, a desaturated sun. Clouds thicken before rain.

---

## 18. Port notes for our Metal/TBDR pipeline

Assumptions:
- Output 3024x1964.
- Internal resolution 0.5-0.67x (1512x982 = 1.48 MP to 2016x1309 = 2.64 MP).
- TBDR single-pass deferred with a memoryless G-buffer.
- Costs from `apple-gpu.md` (measured) or marked (est.).

Constraint reminders:
- Sodium's 20-byte vertex has no normal and no block ID. Normals come from quad corners (M1), and materials from an
  **atlas material mask** (M2).
- Reversed-Z.
- The main target is LDR until M2's HDR redirect.

| SEUS effect | Our implementation | Milestone | Pass and resolution | Est. cost at 3024x1964 output |
|---|---|---|---|---|
| Closed-form atmosphere (one function set) | §3. Analytic Chapman single-scatter + ambient multi-scatter. A per-frame 128x64 sky-view probe and a 3-colour/SH irradiance, written by one tiny compute or fragment pass | **M1** (sky), reused by everything later | Sky pixels at internal res; probe 128x64 | < 0.05-0.1 ms (apple-gpu §5.4) |
| Sun disc, limb, aureole; moon phase; stars | In the sky shader; moon phase scales the moonlight UBO value | M1 | Sky pixels | ~0 |
| Purkinje / low-light vision; starlight ambient | Final-pass ALU, driven by the exposure value | M2 | Merged into final | ~0 (inside 0.35 ms final) |
| Aerial perspective (land scattering) | Lighting pass: surface·T + inscatter from the sky functions; gated by the skylight level in caves | M1 | Inside the TBDR lighting draw | ~0 (the pass is geometry-bound, apple-gpu §2.1) |
| 2D cumulus + cirrus "fake-3D" clouds, coverage over days, blocky option, cloud shadows | §5.2. Evaluated in the sky pass, the probe bake, water reflections, and as a 1-tap cloud-shadow term in lighting | **M2** (recommended default) | Sky pixels at internal res | 0.1-0.3 ms (est.: about 8 noise taps on sky pixels) |
| Volumetric clouds + weather mood (Renewed E2) | Quarter-to-half-internal ray march, 8-16 steps, reprojection, bilateral upsample; coverage biased by the rain level | M3, Ultra tier | ¼-½ internal | 0.4-1.2 ms (apple-gpu §5.4 est.) |
| Atmospheric godrays | 12-16 dithered taps against the far cascade (or a cached 1024² VL map), HG phase, temporal | M2 | ½ internal | 0.32 (0.5x) / 0.47 (0.67x) ms; map amortized about 0.2 |
| Underwater shafts with caustics | Same march, with water extinction and a caustics multiplier | M2 | ½ internal, underwater only | +0.05 over the VL above (est.) |
| Sun shadows, disc-PCSS look, grazing bias | 2 cascades, sun-facing faces only, Depth16; 8-12 blocker taps + 12-16 Vogel taps rotated by IGN, resolved by TAA | **M1** | Shadow pass + inside lighting | 0.7-1.0 ms average (cached cascades); filter cost hidden (apple-gpu §5.1) |
| → RT sun shadows | Inline any-hit ray in the lighting draw, cone-jittered for penumbra, temporal | M4 | Inline | +0.05-0.3 ms (apple-gpu §4.1) |
| Contact shadows (SS) | 8-12-step march toward the sun in linear depth, 0.5-1 block | M2 | Internal | 0.1-0.2 ms (est., SSAO-class: 0.08-0.16) |
| Coloured stained-glass + water caustic shadows | RGBA8 shadow-colour target written by translucent casters in the cascade pass; lighting multiplies by tint or caustics | M3 | Shadow pass + lighting | +0.1-0.2 ms (est.) |
| Leaf SSS / translucent foliage | Transmission lobe with a thickness term from the shadow-map depth difference; needs the material mask | M2 | Inside lighting | ~0 |
| Kelvin block light, held light | 1D Kelvin table; held light = point light at the camera, using `heldItem` light level from Java state | M1 (colour), M2 (held) | Inside lighting | ~0 |
| Ambient / sky-occlusion base | Sky irradiance × lightmap.y^k × (vertex AO × SSAO) | M1 + M2 (SSAO) | Half internal | SSAO 0.08-0.16 ms |
| Single-bounce sun GI (RSM-style, Renewed-class) | Quarter-internal gather of 8-16 RSM samples from the near cascade (needs an RGBA8 flux target in the shadow pass), temporal + bilateral upsample (SEGI passes 1/3/11, MIT) | M3 option | ½ internal | 0.3-0.6 ms (est.) |
| **PTGI-class diffuse GI** | (a) A **voxel radiance cache**: 128³-256³ cells around the camera (RG11B10, 8-64 MB) with albedo/emission written from block data in compute. Each frame, 16-64k cells trace 1 HW-RT ray and EMA-blend the result, giving infinite bounces (E3 / SEGI feedback) with an energy clamp (E12). (b) Per pixel: 1 HW-RT ray at ½ internal, cosine-weighted with blue noise; on a hit, look up the cache + sun visibility + emission; on a miss, the sky probe. (c) **SVGF-lite**: temporal with variance and an adaptive α from a luminance-change estimate (E10), then 3 à-trous iterations with luminance-normalized weights (E6), then a bilateral upsample. Fallbacks: no HW RT → SEGI-style cone tracing through mipped 3D textures (MIT); far away → lightmap-based ambient (E4 "blends into Minecraft's lighting") | **M4**, 60-90 fps Ultra | ½ internal | Rays 0.25-0.45 ms (from 0.79 ms mirror rays on every 0.5x pixel, ÷4, plus hit shading); cache update 0.1-0.2; denoise 0.4-0.6 (4-5 compressible RGBA16F passes at ¼ of 0.5x); **≈ 1.0-1.8 ms total (est.)** |
| Torch/emissive GI, per-source colour | Emission from the material mask written into the voxel cache, so block light becomes real GI with colour | M4 (with GI); a flood-fill coloured-light volume is a cheaper M3 alternative (compute, apple-gpu §9) | Compute volume | 0.1-0.3 ms (est.) |
| labPBR / old-PBR normal + specular, auto-generated fallback | Load `_n`/`_s` atlases on stitch; channel-swizzle options as SEUS has; clamp N·V > 0 | M3 | G-buffer | +0.1-0.3 ms (extra atlas samples in a geometry-bound pass; apple-gpu §2.1 "heavier material" 2.81 vs 2.09 at native) |
| POM with depth write | Only within about 16 blocks and only for materials with height. **Depth writes defeat HSR**: use a separate pipeline drawn after normal opaque terrain | Ultra | G-buffer | Pipeline-dependent; measure first |
| Rain wetness, puddles, ripples, rain fog | §10. Material ALU and a ripple normal function; rain fog in aerial perspective | M3 | Lighting + water | ~0-0.1 ms |
| Water: waves, wave-aware Fresnel, sky probe, refraction, absorption | Forward translucent pass reading the HDR scene copy; §11 | **M2** | Internal, water pixels | 0.3-0.6 ms (apple-gpu §5.4 est.) |
| Water reflections | SSR on water pixels only, half-res + refinement → sky-probe fallback; M4: 1 RT ray per water pixel with clamped-GGX jitter and a cheap filter | M2 / M4 | Internal, water pixels | 0.3-0.5 ms |
| Caustics texture | Load-time compute bake: 128²×64 frames × 4 depth layers, RGBA8, 4 MB, 2-block tile; sampled in lighting, VL and shadows | M2 | One-time bake about 5-20 ms at load (est.); per-frame ~0 | ~0 per frame |
| Translucents and glass | Forward after deferred, tinted transmission + Fresnel (the "proper deferred + forward" that Renewed never got) | M2 | Internal | Included in the water pass |
| Waving foliage | Vertex displacement by the material mask + correct motion vectors | M2 | Vertex | ~0 (vertex-bound pass, keep ALU small) |
| TAA / HRR-style upscale with Responsive/Stable | Custom TAAU, variance clamp, history weight per preset; Filmic pixel option; per-effect histories for GI/VL/clouds | **M2** | Output | 0.62 (0.5x) / 0.72 (0.67x) ms; native TAA 1.26 |
| Natural bloom + lens dirt + fog glow | §13 (MIT). Fragment pyramid from the internal-res HDR; lerp composite in the final pass | **M2** | Pyramid | 0.5-0.74 ms |
| Auto-exposure, smooth and centre-weighted | Log-luminance 64x64 reduction (one fragment pass + a 1x1 compute) | M2 | Tiny | ~0.02-0.05 ms |
| SEUS-like vibrant tonemap | AgX "punchy" + highlight hue preservation + black-depth toe; Lottes/ACES as alternates | M2 | Final | Inside 0.35 ms |
| Motion blur (foreground-aware) | Tile max-velocity + reconstruction filter (McGuire-style), half-res tiles | M3 option | Output | 0.3-0.5 ms (est.) |
| Post sharpening | CAS-style 3x3 in the final pass | M2 | Final | +0.05 ms |
| Nether / End | Not documented in the SEUS public notes. Our plan: no sun; dimension-specific fog colour and density from vanilla fog state; strong emissive bloom (lava, portals); End: dark-violet sky gradient, star field, no clouds | M2 | – | ~0 |

**Budget check at the 120 fps tier (0.5-0.67x internal), SEUS-like default:**

| Part | ms |
|---|---|
| Terrain TBDR + lighting + inline shadows or cached CSM | 1.4-1.9 |
| Entities | 0.3-0.8 |
| Sky + 2D clouds | 0.1-0.3 |
| VL | 0.3-0.5 |
| Water + reflections | 0.6-1.1 |
| SSAO + contact shadows | 0.2-0.35 |
| TAAU | 0.62-0.72 |
| Bloom | 0.5-0.74 |
| Final | 0.35 |
| GUI | 0.2-0.4 |
| **Total** | **≈ 4.6-7.2 ms** |

That fits 8.33 ms with margin for dynamic resolution, which is consistent with apple-gpu §7.1.

**60 fps Ultra:** add PTGI-class GI (1.0-1.8 ms), volumetric clouds (0.4-1.2), RT water reflections and a native or
0.75x internal resolution. Then the frame is about 9-13 ms, which leaves room for MetalFX quality mode (4.5-6 ms) if
preferred.

**Ordering advice drawn from SEUS's history:**
- Build the shared atmosphere first and reuse it everywhere (E12's lesson).
- Make every expensive effect have its own temporal history and responsiveness knob (E5/E10).
- Keep the sky and clouds cheap enough to evaluate inside reflections and GI rays (E7/E11).
- Put translucents in a forward pass after deferred (Renewed roadmap).
- Never let half-resolution upscaling also starve the ray budget of GI (the HRR post-mortem).

---

## 19. Licensing: what can be adapted

| Material | License | Use |
|---|---|---|
| SEUS Renewed, SEUS PTGI (all versions), legacy SEUS | **SEUS License** (proprietary EULA): personal use and modification only. "It is, however, interdicted to (re-)distribute SEUS … You may ipso facto not … use parts of SEUS - including snippets from its source code - in your own work". Downloading or storing it implies acceptance | **Study only, and only after the user accepts the EULA.** No code, constants tables or shader structure copied. Techniques in this report are described from public dev logs and general graphics knowledge |
| `SE-Natural-Bloom-Dirty-Lens` (`Assets/SE Natural Bloom/Shaders/SENaturalBloomAndDirtyLens.shader`, `Scripts/SENaturalBloomAndDirtyLens.cs`, `Lens Textures/*.png`) | **MIT**, © 2019 sonicether | **May be ADAPTED** (ported to MSL) with attribution: keep the copyright line and the MIT text in `THIRD_PARTY_NOTICES`, and add a header comment "Adapted from SE Natural Bloom & Dirty Lens (c) 2019 sonicether, MIT" on the ported `.metal` file. The lens-dirt PNGs fall under the repo's MIT license, but prefer generating our own to avoid shipping images |
| `SEGI` (`Resources/SEGI.cginc`, `SEGI.shader`, `SEGIVoxelizeScene.shader`, `SEGITraceScene.shader`, `SEGIMipFilter.compute`, `SEGITransferInts.compute`, `SEGIRenderSunDepth.shader`, `SEGI.cs`, cascaded `*_C.*` variants) | **MIT**, © 2017 sonicether | **May be ADAPTED** with the same attribution pattern. Most valuable: the cone-trace loop and visibility accumulation, the temporal blend with world-position rejection and neighbourhood clamp, the bilateral blur and upsample, and the secondary-bounce feedback |
| `SEUS-Mod-Compatibility-Helper` | GPL-3.0 | No code of value (GitHub Pages template). Do not use |
| Patreon post text and sonicether.com text | Author's copyright | Summarized in my own words here; the local copy in `official-notes/` is for team reference only |
| Public tonemap formulas (Lottes 2016, ACES RRT/ODT and fits, Reinhard variants) | Published math | Free to implement from the papers or specs |

---

## 20. Open questions a code read would settle (only with the EULA accepted)

1. Renewed's exact GI method (RSM-style or otherwise), and its shadow distortion and filter constants.
2. Renewed's sky implementation (ray-marched or analytic) and the cloud and VL step counts.
3. Whether Renewed has waving foliage, rain puddles and Nether/End-specific looks, and how they are keyed (block IDs in
   `block.properties`).
4. The exact SEUSTonemap formula and its default exposure and saturation constants. Our tonemap does not need them.
5. Whether Renewed's `shaders.properties` ships profiles, and any macOS (`MC_OS_MAC`) guards.

None of these block M2-M4. Our design is fully specified without them.
