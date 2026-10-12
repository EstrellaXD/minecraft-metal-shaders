# Complementary Shaders (Reimagined + Unbound) r5.9.3: technical and artistic study (report `packs/complementary`)

Research only. This report does not change mcopt, the M1 worktree or the user's instances, and it ran no GPU work.

All paths in `pack:` are relative to the pack's `shaders/` folder. Both packs live under `WORK/packs/`:
- Reimagined: `WORK/packs/ComplementaryReimagined_r5.9.3/` (already present).
- Unbound: `WORK/packs/ComplementaryUnbound_r5.9.3/`, downloaded from Modrinth project `complementary-unbound`, version r5.9.3 (published 2026-09-15, game versions 26.1.2 / 26.2 / 26.3). The file is `ComplementaryUnbound_r5.9.3.zip` (553,400 B) from `cdn.modrinth.com/data/R6NEzAwj/versions/B1kyfoUZ/`.

`WORK` = `.../scratch-2026-10-06-836a24/work`.

The effects are described in my own words, with math where it helps. I did not transcribe code, because the license does not allow reuse (see §1).

---

## 0. Key takeaways

1. **Reimagined and Unbound are the same code.** `diff -r` of the two `shaders/` trees finds exactly one differing line: `lib/common.glsl:14`, which sets `SHADER_STYLE 1` (Reimagined) or `4` (Unbound). That define changes six defaults:
   - water style
   - cloud style
   - sun/moon style
   - sun path angle (0° vs −40°) and the "perpendicular tweaks"
   - aurora style
   - rainbow style, plus glowing ores
   
   Everything else is shared: lighting, shadows, fog, VL, TAA, bloom and tonemap (§2).
2. **Complementary is a forward renderer with a deferred post chain, not a G-buffer deferred renderer.** All lighting, shadows and specular are computed in the `gbuffers_*` fragment shaders, into one HDR-ish color target (`colortex0`, R11G11B10F). `deferred1` only adds SSAO, sky effects, clouds and fog to the opaque image (`pack:program/deferred1.glsl`). That matches our M1 design, where lighting runs in the Sodium terrain fragment shader. On TBDR, forward shading also measured as the cheapest variant (apple-gpu §2.1).
3. **The look is art-directed, not physically based.**
   - The sky is a hand-tuned three-band gradient driven by vanilla's `skyColor` uniform. There is no atmosphere model.
   - Lights are combined "in quadrature" (sqrt of the sum of squares) in a perceptual, gamma-like space. The scene is linearised with `pow(2.2)` only in `composite1`.
   - There is **no auto-exposure**. Brightness is held stable by sky-light-dependent ambient, cave minimum light and per-time-of-day light colors.
   - The final curve is a modified **Lottes 2016** tonemap, followed by "path to white", dark desaturation and a BSL-style vibrance/saturation step.
4. **Why it is slow on the M4 at Retina.** At native resolution the chain is:
   - a 2048² distorted shadow map of the full geometry;
   - about 8 full-resolution full-screen passes: deferred1, composite1, composite3 (a copy when DOF is off), composite4/5, the TAA pass, FXAA and final;
   - a mip chain of `colortex0` for bloom;
   - a half-resolution SSR pass.
   
   With apple-gpu's numbers (about 1 ms per uncompressed RGBA16F-class full-screen pass at native, 2.8–5.2 ms for a full shadow map) this comes to an estimated 13–22 ms at 3024x1964 (§8). That is why the user drops to 1080p.
5. **What we can reuse legally.**
   - No code from the Complementary-licensed files.
   - One file is MPL-2.0 (`lib/materials/materialMethods/anisotropicFiltering.glsl`). It could be adapted under MPL file-level terms, but we don't need it.
   - Several *techniques* inside the pack come from public sources we can implement from the original papers: Lottes 2016 tonemap, Horizon Zero Dawn GGX sphere-light approximation, FXAA 3.11, Catmull-Rom history sampling (Filmic SMAA), and Jimenez-style reprojection.
   
   Everything else should be re-derived from the descriptions below.

---

## 1. Sources and license verdict

- License file: `License.txt`, "Complementary License Agreement 1.7", identical in both zips.
  - §0.2 defines "Normal Usage" to *exclude* redistributing parts of the pack and modifying its code.
  - §1.3 allows a "Modified Pack" only under conditions incompatible with our project: the same license must ship unchanged, no other license terms may apply, and the name must differ, among others.
  - §2.1 makes everything else "All Rights Reserved", except files that carry their own license notice.
- **Verdict: `can_adapt_code = false`** for Complementary-authored code. We may study it and re-implement techniques in our own words and code, which is what this report does.
- Files with a different license or a third-party origin, as noted in the pack itself:

| File | Origin noted in the pack | Use for us |
|---|---|---|
| `lib/materials/materialMethods/anisotropicFiltering.glsl` | header: Mozilla Public License 2.0 | Adaptable under MPL-2.0 (file-level copyleft: our modified file stays MPL). Not needed: Metal has hardware anisotropy, and our atlas sampling is separate. |
| `lib/antialiasing/fxaa.glsl` | FXAA 3.11 via Simon Rodriguez's blog | Re-implement FXAA from NVIDIA's public description if ever needed. We plan TAA instead. |
| `lib/lighting/ggx.glsl` | "GGX area light approximation from Horizon Zero Dawn" (Guerrilla, GDC 2017) | Re-implement from the HZD paper. |
| `program/composite5.glsl` tonemap | "Lottes 2016, Advanced Techniques and Optimization of HDR Color Pipelines" | The curve family is public. Choose our own parameters. |
| `lib/antialiasing/taa.glsl` | Catmull-Rom from the Filmic SMAA talk, reprojection and jitter from Chocapic13 | Standard techniques. Write our own. |
| `lib/atmospherics/nightNebula.glsl` | "Nebula implementation by flytrap" (godotshaders.com) | The license of the original snippet was not verified. Write our own fBm nebula. |
| `lib/atmospherics/clouds/cloudCoord.glsl` | rounded-texel trick "Thanks to SixthSurge" | A generic idea (smoothstep inside each texel). Our own implementation is fine. |

---

## 2. Reimagined vs Unbound (`SHADER_STYLE` 1 vs 4)

Source: `pack:lib/common.glsl:405-490`, `pack:lang/en_US.lang:63-66`. The pack describes Reimagined as "complementing Minecraft while preserving its unique visual elements" and Unbound as "reworking the visuals with a semi-realistic fantasy style".

| Aspect | Reimagined (1) | Unbound (4) | Where |
|---|---|---|---|
| Water style | **1**: vanilla water texture drives color and alpha. Wave positions are quantised to the block texture's pixel grid, so ripples look pixelated. Normals stay mostly geometric. | **3**: procedural water. Flat tinted color (about 0.375 × biome water tint). A 4-octave normal map from `cloud-water.png` RG, with a 4-iteration parallax offset from the A (height) channel. Smoothness 1 and a sharp sun glint computed from the wave normal. | `lib/materials/specificMaterials/translucents/water.glsl` |
| Caustics (shadow pass) | from the water texture's luminance squared (texture-driven caustics) | procedural: the finite-difference gradient of the RG water map at two scrolling scales, mapped to a 0.35..1.0 brightness | `program/shadow.glsl:114-152` |
| Clouds | **Reimagined clouds**: vanilla-like *blocky* cloud map (B channel of `cloud-water.png`, binary 0/255), extruded about ±4.2 blocks, corners rounded, first-hit ray march | **Unbound clouds**: volumetric noise clouds (4-octave 3D value-noise fBm), layer about ±16 blocks thick, front-to-back accumulation, forward-scatter "silver lining" | `lib/atmospherics/clouds/{reimaginedClouds,unboundClouds}.glsl` |
| Cloud shadows (off by default) | sampled from the same blocky map, projected along the light | 8-tap blurred noise texture | `lib/lighting/cloudShadows.glsl` |
| Sun/moon | **1**: vanilla sun/moon *textures*, re-graded: sun brightness from its texture luminance raised to a high power, tinted orange; moon slightly boosted | **2**: procedural round sun disc (very bright orange-white) and procedural moon with crater noise and phase shading, using an offset "virtual sun" vector | `program/gbuffers_skytextured.glsl`, `program/gbuffers_skybasic.glsl:94-150` |
| Sun path | `sunPathRotation = 0` and `PERPENDICULAR_TWEAKS` on: foliage shadow offset, fake penumbra from the AO gradient, N/S face light boost, fake bounce light | `sunPathRotation = −40°`: tilted sun, so every face gets varied light and long diagonal shadows. Tweaks off. | `lib/common.glsl:455-490`, `lib/lighting/mainLighting.glsl:183-200,237,527-533`, `program/shadow.glsl:315` |
| Aurora | style 1: blocky, with plane coordinates floored to a grid | style 2: smooth, 3 noise layers | `lib/atmospherics/auroraBorealis.glsl` |
| Rainbow | style 1: pixelated, quantised in shadow-space direction | style 4: smooth | `lib/atmospherics/rainbow.glsl` |
| Glowing ores | off unless enabled | all ore types glow (`GLOWING_ORE_MASTER==1 && SHADER_STYLE==4`) | `lib/common.glsl:184` |
| Cloud light color | – | slightly cooler at noon (blue shifted, red and green reduced) | `lib/colors/cloudColors.glsl:8` |
| Moon phase light | smooth interpolation between dark/partial/full | stepped | `lib/colors/moonPhaseInfluence.glsl` |

Practical meaning for us: one engine, two "art presets". We can offer the same split: "Classic/Reimagined" (blocky clouds, pixel-respecting water, flat sun path) and "Fantasy/Unbound" (soft clouds, glossy procedural water, tilted sun, round sun disc).

---

## 3. Pipeline overview (Iris program graph)

### 3.1 Render targets (`pack:lib/pipelineSettings.glsl`)

| Target | Format | Content |
|---|---|---|
| colortex0 | R11G11B10F | main scene color (perceptual/"gamma-ish" until composite1, linear afterwards) |
| colortex1 | RGB8_SNORM, **half res** (`REFLECTION_RES 0.5`) | previous-frame normals for reflection reprojection |
| colortex2 | RGB16F | TAA history (in **display/LDR** space) |
| colortex3 | RGBA8 | cloud/water texture in gbuffers. Later: translucentMult (1−tint), then bloom tiles, then the final LDR color |
| colortex4 | RGBA8_SNORM | world normal + reflection strength (sqrt(fresnel)) |
| colortex5 | RGBA8 | scene copy for water SSR (encoded as sqrt(c)·0.5). A = cloud linear depth. Bottom-right texel = "scene-aware VL factor" |
| colortex6 | RGB8 | smoothness, material mask (8-bit enum), sky-light factor |
| colortex7 | RGBA16F, half res | temporal PBR reflection history (rgb) + linear depth (a) |
| colortex8 | RGBA16F | SSR results for world-space reflections, topmost translucent opacity |
| colortex12 | RGBA8 | "improved rain" data (distance, rain/snow, block light, opacity) |
| colortex13 | R8 | entity linear depth (so VL doesn't make entities behind glass look transparent) |
| shadowtex0/1, shadowcolor0/1 | 2048² (default) | depth with all casters / opaque-only depth / translucent tint ×0.1 / light-shaft color + caster height |

### 3.2 Pass order (overworld, defaults ≈ HIGH profile)

| # | Program | Resolution | What it does |
|---|---|---|---|
| 1 | `shadow` | 2048² (1024² low, 4096² ultra) | Distorted orthographic sun map. Writes tint for translucent casters, procedural water caustics, a light-shaft tint and caster height (for "scene-aware light shafts"). Applies waving vertex animation. With colored lighting on (not on Mac), it also voxelises the world into a 3D image. |
| 1b | `shadowcomp` (compute) | 3D 128³..1024×256×1024 | Flood-fill colored light propagation. **Disabled on macOS.** |
| 2 | `gbuffers_skybasic` / `skytextured` | full | Sky gradient, stars, sun/moon |
| 3 | `gbuffers_terrain`, `_entities`, `_block`, `_hand`, `_textured`… | full | **Forward lighting**: material heuristics (IPBR), shadows, sun/sky/block/held light, SSS, AO, GGX highlight. Writes color, material data and normals. |
| 4 | `deferred1` | full | SSAO, aurora/nebula/End sky, **volumetric clouds**, fog on opaques. Writes the scene copy for water reflections, cloud depth and reflection fresnel. |
| 5 | `gbuffers_water` (and other translucents) | full | Forward-lit translucents. Water normals, foam, alpha from depth difference, **SSR inside the fragment shader**, sky reflection, fog. Writes the translucent tint. |
| 6 | `composite` | **half** | Stochastic glossy SSR for opaque blocks, temporally accumulated |
| 7 | `composite1` | full | Refraction, reflection composite (5×5 bilateral), rainbow, **volumetric light**, Nether storm, colored light fog, underwater tint, improved rain, **linearise (pow 2.2)**, add VL, "bloom fog" multiplier |
| 8 | `composite3` | full | DOF / distance blur (off by default; then it is a plain copy) |
| – | mip generation of colortex0 | full→1×1 | done by Iris because `colortex0MipmapEnabled` is set in composite4 |
| 9 | `composite4` | bloom atlas within a 1920×1080-equivalent region | 7 bloom tiles (LOD 2..8), each a 7×7 binomial filter over the mipmapped scene. Optional motion blur. |
| 10 | `composite5` | full | Bloom composite, optional channel-mixer grading, **Lottes-family tonemap**, lens flare, saturation/vibrance → LDR in colortex3 |
| 11 | `composite6` | full | **TAA** on the tonemapped image |
| 12 | `composite7` | full | FXAA 3.11 (on from MEDIUM up) |
| 13 | `final` | full | Underwater distortion, chromatic aberration, 4-tap sharpening, vignette, dither |

`composite2` exists in `program/` but has no `world*/composite2.*` entry point, so it never runs.

### 3.3 Global time-of-day scalars (used everywhere)

From `lib/common.glsl:718-750` and the per-program headers:
- `timeAngle` remaps Iris' `sunAngle` piecewise: the daylight part of the cycle is stretched to the first half and the transitions are eased. Then:
  - `noonFactor = sqrt(max(sin 2πt, 0))`
  - `nightFactor = max(sin(−2πt), 0)`
- `SdotU` = sun direction · up.
- `sunVisibility = clamp((SdotU + 0.0625)/0.125)`: a crossfade of about ±7° around the horizon.
- `sunFactor`: a slower curve that reaches 0 well below the horizon, used to blend sky colors.
- `shadowTime = ((|sunVisibility − 0.5|·2)²)²`: drops to 0 exactly when the light source switches from sun to moon. Direct light and shadows fade out and back in, which hides the switch.
- `lightVec` = sunVec in daytime, −sunVec (the moon) at night.
- Biome scalars from smoothed custom uniforms: `inDry`, `inRainy`, `inSnowy`, `inPaleGarden`, `inSulfurCaves`, Nether biomes. Others: `rainFactor` (smoothed `rainStrength`), `wetness`, `eyeBrightnessM` (smoothed sky light at the eye), `isEyeInCave`.

---

## 4. Effects: how each one works

### 4.1 Sky and atmosphere (`lib/atmospherics/sky.glsl`, `lib/colors/skyColors.glsl`)

- **There is no physical atmosphere.** Three color "bands" are derived from vanilla's `skyColor` uniform (the biome sky color, already dimmed by weather and time):
  - **up** (zenith): the sky color strongly darkened and saturated, raised to about the 2.9th power and tinted slightly green-neutral at noon. At sunset it becomes muted mauve.
  - **middle** (horizon): the sky color boosted about 1.3–1.8×. At sunset it is pushed warm (more red).
  - **down** (below horizon): a fixed warm sunset orange mixed with the middle color at sunset; the middle color at noon.
  - **night**: a fixed navy blue added to `skyColor`, slightly brighter toward the horizon. The navy is lifted at the opposite side from the moon (anti-solar brightening).
  - Weather variants shift these colors. Rain desaturates and lifts toward grey. Snowy biomes get bluer. Dry biomes go dusty and less blue. These are additive per-channel offsets scaled by `rainFactor·inSnowy` and so on.
- **Gradient math.** With `u = max(V·U, 0)`:
  - mix zenith → horizon by `(1−u)^2`, raised to an exponent that *shrinks toward the sun* (`1 − 0.4·(V·S)^4`), so the bright horizon band widens around the sun;
  - a **sunset band** is mixed in near the horizon with weight `smoothstep((1−|V·U|)^2)·(0.7 + 0.3·(V·S)^2)·(1−noon)·sunFactor`;
  - below the horizon the ground color fades in with *per-channel different curves*: red on a squared curve, green on sqrt, blue on cube root. This acts as a cheap "Rayleigh-like" color shift, where blue persists longer toward the ground;
  - an optional ground darkening.
- **Sun/moon glare.** A hyperbolic halo `g = k/(1 − (1−k)·(V·S)^p) − k`, with k ≈ 0.075 and a sharpness p that jumps near the disc. Its color goes from orange at low sun to near-white at noon, and grey-blue for the moon. Rain reduces it by 60–80% and desaturates it. It is multiplied by `shadowTime`.
- **Cave fog override.** The sky turns into a dark grey cave color as `caveFactor` rises. `caveFactor` = below sea level and low eye sky light.
- **Banding.** The result gets ±1/256 of Bayer or IGN dither.
- **Cost driver.** Pure ALU, about 60 instructions. It is evaluated *many* times per pixel: sky pixels, border fog, cloud fog color, water sky reflection, block-reflection fallback. A low-quality variant (`GetLowQualitySky`) is used in cheap paths.

### 4.2 Sun and moon (§2 table)

- Reimagined re-grades the vanilla textures (square sun) and fades them with a horizon factor, a smoothstep of (V·U+0.1)·10.
- Unbound draws:
  - the sun as `mix(sky, orange·25, sqrt(k·(|V·S|−cos r))^2)`, with a disc radius of about 4°;
  - the moon as a noise-cratered grey disc. Its phase is made by a second "virtual sun" vector offset along the orbit by a crescent offset, used to cut the lit part.
  - Both go through the horizon factor, rain fade and cave fade.

### 4.3 Stars, nebula, aurora, rainbow, End stars

- **Stars** (`lib/atmospherics/stars.glsl`):
  - project the world direction onto a pseudo-plane, `d.xz / (d.y + 0.5·|d.xz|)`, drifting slowly with time;
  - quantise to a 1/1024 grid;
  - star value = product of 3 hash values minus a threshold (amount setting), squared;
  - intensity ×40 in a pale blue-white;
  - faded by altitude, near the sun, by rain, and by daytime.
- **Night nebula** (off by default, `nightNebula.glsl`): two-color fBm clouds (blue, orange), 5 or 8 octaves of value noise, on the same star plane, animated by slow tidal offsets. Star brightening inside the nebula. Night only.
- **Aurora** (`auroraBorealis.glsl`):
  - 25 samples in deferred1 (10 in reflections) through a stack of horizontal "planes" at increasing virtual heights: plane coordinate = d.xz/d.y·(0.8+t)·11 plus a camera-position drift;
  - per plane: a ridged noise curtain `(1−2|n−0.5|)^16` × a second fast noise;
  - color from green at the bottom (lower planes) to purple at the top;
  - visibility (default condition 3) needs night, no rain, **full moon** and a **snowy biome**.
- **Rainbow** (`rainbow.glsl`):
  - after rain, in rainy biomes, morning or afternoon only, when `wetness > 0.33`;
  - a ring at about 41° from the anti-solar point (`V·L ≈ −0.75`), width scaled by a diameter setting;
  - hue from a hue-wheel function of the ring coordinate, with 3 offset copies averaged to soften it and an extra red outer fringe;
  - faded by distance to geometry and by clouds (via the cloud depth in colortex5).
- **End stars** (`enderStars.glsl`): same hash stars on a both-hemisphere plane, purple, very bright (×3000·endSkyColor) but squeezed toward the horizon. Masked where the End beams are.

### 4.4 Clouds (`lib/atmospherics/clouds/*`, rendered per pixel in `deferred1`)

Shared shell (`mainClouds.glsl`):
- The camera position is wrapped modulo the cloud texture period, which avoids float precision loss far from the origin.
- Max distance is 4000 blocks.
- The first pass runs only on pixels whose depth is > 0.56 (not hand/entities). Geometry closer than the cloud blocks it; clouds behind distant fogged terrain still draw where fog is ≥ 0.7.
- Output: rgb + alpha and a *cloud linear depth* in colortex5.a. Later passes read it to:
  - discard water fragments behind clouds;
  - occlude the lens flare;
  - fade the rainbow;
  - fix TAA on entities seen through clouds.
- Aurora and nebula are added on top of the clouds at 10–20%.

**Reimagined clouds (blocky):**
- Layer: altitude 192 (setting) ± 4.2 blocks. A second layer at 288 is optional.
- Density lookup: a *binary* 2D map (B channel of `cloud-water.png`, 256², only 0 or 255). It is sampled with a "rounded texel" coordinate: inside each texel the fractional part goes through `smoothstep(0.5−r, 0.5+r)` with r = 0.125. With bilinear filtering this rounds the corners of each cloud block.
- Inside test: `map > 0.25 + 0.5·(|y − alt|/4.2)^8`. This gives slightly bulged, rounded vertical edges.
- Ray march between the two planes:
  - samples = max(distance/8, 12) at quality 2, or **one per block** at quality 3;
  - dithered start;
  - **stops at the first hit**, so it's cheap.
- Shading at the hit:
  - vertical gradient (0 at the bottom, 1 at the top, ^0.8);
  - 2 extra 2D samples offset toward the light, a cheap self-shadow;
  - V·S forward-scatter term;
  - color = ambient·(1−0.35s) + light·(0.1+s).
- If the hit point is in the terrain shadow map it is darkened or skipped. This is a "closed area check" that avoids clouds showing inside caves or under roofs.
- Distance fog: blend to `GetSky()` with `(1−d/4000)^2`. Opacity rises with distance (sqrt), so far clouds look solid.

**Unbound clouds (volumetric):**
- Layer thickness ±11/16/18 blocks (quality 1/2/3). Horizontal frequency 0.00012/block. Wind 0.0006·time.
- Density:
  - `Noise3D` makes 3D value noise from one 128² 2D noise texture: 128 z-slices with a per-slice (23,29) offset, linear interpolation between 2 slices, so 2 fetches;
  - 4 octaves at ×3 frequency with persistence 0.5, normalised and squared;
  - × (0.8 base + 0.1 when the camera is above + 0.4·rain) × amount;
  - minus a vertical profile `0.25 + 0.2·(|y−alt|/h)^8`.
- March: fixed step 32 blocks (quality 2) or 16 (quality 3), count = thickness/step+1. Plane-clipped, so usually only 2–6 steps.
- Accumulation:
  - front-to-back: color = mix(previous, sample, 1 − alpha);
  - alpha += min(8·density, 1) · distanceFactor^(0.5 + 10·|V·S|^90). The exponent makes clouds *very thin right around the sun*, which reads as a glowing rim;
  - early out at alpha 0.9.
- Shading: height gradient × (1 + forward scatter near the sun, stronger when the cloud is thin); color = ambient·(0.4+0.6s) + light·s.
- Distance fog to sky with a slightly steeper curve. Opacity rises with distance.

**Cost driver.** Full resolution, per sky or distant pixel. Reimagined: one 8-bit fetch per step, with up to 12–30+ steps when grazing. Unbound: 4×2 fetches per step × 2–6 steps. Each pixel also calls `GetSky` once (ALU). No temporal reuse beyond TAA smoothing the dither.

### 4.5 Fog and atmospheric scattering (`lib/atmospherics/fog/*`)

`DoFog()` applies, in order:
1. **Cave fog**: `caveFactor·0.9·(1 − e^{−0.015·dist})` toward a dark grey-blue (0.13,0.13,0.15), scaled by screen brightness. The sulfur caves biome tints it yellow-green.
2. **Atmospheric fog** (overworld) has three parts:
   - density: `1 − exp(−(dist·(0.001−0.0007·rain))^(2−rain²)·dist·192/renderDistance)`. A super-linear curve keeps the near field crisp and builds haze quickly in the distance.
   - **altitude falloff**: full below altitude 63 and fading to 0 over the next 60 blocks, so valleys look hazy and mountain tops look clear. The camera altitude also reduces it.
   - color: `GetAtmFogColor`, which blends the night zenith color and the day "down" sky color by a day/night factor that depends on V·S (fog is brighter toward the sun at dawn).
   
   In rain in snowy or dry biomes, an extra dense near fog (`1−exp(−d·k/ATM_FOG_DISTANCE)`) is mixed in. Fog is suppressed with low eye sky light (indoors).
3. **Border fog**: `fog = 1 − exp(−3·(d/renderDistance)^16)` (the 16th power keeps it at 0 until near the edge) toward **exactly the sky color for that view direction**. This is what makes chunk borders vanish into the sky. Its value is also exported as `skyFade` and later tells clouds and reflections "this is sky".
4. Water (`1 − e^{−(d/48)^2}` with VL, else `1 − e^{−d/32}`), lava, powder snow, blindness and darkness fogs.

**"Bloom fog"** (`bloomFog.glsl`):
- In composite1 the linear color is *multiplied* by `1 + f·k`, with f = (1 − e^{−0.02d})^4 and k from rain (8), night (3), cave (14), underwater (14), Nether (14), × bloom strength.
- composite5 divides it back out *after* the bloom pyramid has been built from the boosted image.
- Net effect: distant and foggy regions bleed more glow into their surroundings without becoming brighter themselves. That is an "aerial glow / scattering" look that costs nothing.

### 4.6 Volumetric light / god rays (`lib/atmospherics/volumetricLight/volumetricLight.glsl`, run in `composite1` at full resolution)

- Ray from the camera to the opaque depth (z1), or to `0.98·far` for sky pixels.
- **Sample placement**: `nearSamples` = 6 / 10 / 15 / 30 (low / medium / high / very high), distributed as `t^2` (denser near the camera) up to min(far, shadowDistance), plus 1 far sample to cover the rest. All offset by blue-noise dither that changes per frame with the golden ratio. The jitter is integrated by TAA.
- **Per sample**:
  - one point fetch of shadowtex0 (texelFetch, hard compare scaled ×65536 into a soft 0..1);
  - if blocked, a check of shadowtex1: if only a translucent caster blocks, the light is tinted by shadowcolor1. That gives colored god rays through stained glass, and water-colored shafts underwater;
  - beyond the translucent surface, samples are multiplied by `translucentMult` (the tint of glass or water in front).
- **Integration**:
  - each slice weight = `w(d_next) − w(d_prev)` with `w(d) = smoothstep((d/maxDist)^0.9)`, so total fog density doesn't change with the sample distribution;
  - result = Σ(visibility·weight);
  - in "intense" situations (low `vlFactor` or rain) the fog curve goes to 0.5, which makes it thicker near the camera.
- **Angular and time shaping**:
  - × `(V·L+1)/2` (forward bias);
  - × a function of V·U (less looking up, depending on distance);
  - × time factor (fades at the sun↔moon switch);
  - × `(1−noon)^2·0.875+0.125` (weak at noon, strong at sunrise and sunset);
  - × 1.4−0.4·rain.
- **Color**: `lightColor` raised to a time-dependent power, more saturated at sunset (setting `LIGHTSHAFT_SUNSET_SATURATION`). At night: normalized, dim blue-ish moonlight.
- **"Scene-aware light shafts"** (`LIGHTSHAFT_BEHAVIOUR 1`, default):
  - about 15 times per second, the bottom-right pixel reads a 5×5 grid in the middle of the *shadow map*;
  - the shadow pass wrote each caster's world height into shadowcolor1.a;
  - if the casters above the player are on average more than 6 blocks high, the player is under a canopy, overhang or cave mouth, so the scalar `vlFactor` creeps up 1/255 per update; otherwise it creeps down;
  - a sky-visibility check on the screen's bottom row forces it down in open areas;
  - `vlFactor` is stored in the corner texel of colortex5 and read back next frame by the vertex shaders.
  
  Result: strong shafts in forests and caves, subtle ones in open plains.
- **Cost driver**: full-resolution, 10–15 random shadow-map fetches per pixel, every frame. It was the single biggest composite cost in Iris profiles at Retina (est.).

### 4.7 Shadows (`program/shadow.glsl`, `lib/lighting/shadowSampling.glsl`, `lib/lighting/mainLighting.glsl:94-346`)

- **One distorted orthographic map.**
  - Distortion in clip space: `xy /= |xy|·b + (1−b)` with `b = 1 − 25.6/shadowDistance`, and `z ×0.2` (depth range compressed, which reduces acne). This is a radial "fisheye" that gives about 4–8× density near the player.
  - shadowDistance 64–256 by profile (192 default).
  - Resolution 1024 (low), 2048 (medium/high), 4096 (ultra, or when smoothing < 3).
  - Hardware 2×2 compare filtering.
  - Entities cast shadows within `shadowDistance·0.125` blocks (Iris feature), with a dithered fade.
- **Two depth maps + color**:
  - shadowtex0 = all casters, shadowtex1 = opaque only, shadowcolor0 = translucent caster tint stored at 1/10 (so water tints can exceed 1 after scaling).
  - Per sample: if fully lit → 1; if shadowtex0 blocks but shadowtex1 doesn't → a tint `(shadowcolor0·shadow1·colorMult)^colorPow`, where colorMult and colorPow depend on sky light (strong saturated colors outdoors, softer in caves).
  - Glass casts a faint grey shadow (0.1·(1−GLASS_OPACITY)). Ice casts a squared tint.
  - Water casts **caustics** (§2 table) and a noise-modulated underwater light-shaft color.
- **Filtering** (`SampleTAAFilteredShadow`, medium and up, with TAA):
  - 2·N taps, N = 2 / 4 / 8 / 16 for medium / high / very high / ultra; doubled for foliage closer than 10 blocks;
  - the taps lie on a golden-angle spiral (angle = frac((noise+i)·2.427)·π, radius ∝ (noise+i)/N), each mirrored (±offset);
  - interleaved gradient noise, advanced per frame by the golden ratio, so TAA averages the rotations;
  - base radius about 0.00098 UV × 1.39 ≈ 2.8 texels at 2048. Rain multiplies the radius by up to 3× (soft overcast shadows);
  - medium is only 4 taps, but it looks smooth thanks to TAA. Low uses 5 fixed taps, very low 4 basic taps.
- **Bias without peter-panning**:
  - world-space normal offset `(0.12 + 0.0008·dist^1.5)·(2 − 0.95·N·L)` along the geometric normal;
  - plus a small upward bias that grows with distance on vertical faces near water level (fixes "water shadow outside water").
- **Light-leak fix (caves)**: the shadow lookup position is pulled 20% toward the *center of the block* the fragment belongs to, by `(1 − max(AO, skylight)^4)`. Dark, enclosed fragments sample deeper inside geometry, where the map reliably says "shadow". Foliage and centered-bias blocks are pulled by `0.5 − 0.5·skylight`.
- **Fake variable penumbra** (Reimagined only, `PERPENDICULAR_TWEAKS`): on up-facing surfaces, `dFdx`/`dFdy` of the vertex AO give an "AO gradient direction". If it points away from the light, the AO value drives the block-center pull, so contact areas next to walls get softer and darker shadow transitions.
- **Shadow distance fade**: in the last 16 blocks before `0.917·shadowDistance`, the result fades to a fake shadow = skylight^8. Foliage gets special values.
- **Sub-surface for leaves and plants**:
  - `subsurfaceMode 1` (grass, flowers): N·L forced to 1 (lit from any side), a tiny depth offset, highlight `0.8·(V·L)^10` (light shining through when looking toward the sun);
  - `subsurfaceMode 2` (leaves): each shadow tap gets a different depth offset (`z − 0.12·offset·(noise+i)/N`), a cheap "thickness" or transmission test, so light leaks a few texels into canopies; highlight 0.6·(V·L)^10;
  - mode 3 (vines): center bias.
  - The Reimagined shadow pass also moves foliage geometry along its normal by `0.2·sign(texel_y)·noon` so the shadow of a cross-plant doesn't self-shadow at noon.
- **Leaf shadow optimisation** (potato only): skip the shadow lookup on leaves and derive light from the lightmap.

### 4.8 Lighting model (`lib/lighting/mainLighting.glsl`, `lib/colors/lightAndAmbientColors.glsl`)

Inputs per fragment: albedo × vertex color, lightmap (block x, sky y), vertex AO (`glColor.a`; Iris `separateAo=true` keeps AO out of rgb), geometric normal, material normal, material flags.

1. **Light and ambient colors** (overworld):
   - noon light: warm white (0.65,0.55,0.375)·2.05;
   - sunset light: (0.64,0.45,0.30)^(1.5+(1−noon))·5, a strongly saturated orange that gets redder as the sun drops;
   - night light: a dim blue-violet (0.15,0.14,0.20) × brightness;
   - ambient = `skyColor^0.75·0.85` (biome sky drives the shadow color: blue shadows). At sunset ×(1.21,0.92,0.76). Night ambient is cool blue and about 4× the night light;
   - rain: light becomes a dim grey-brown, ambient a flat grey-blue. Biome offsets for dry and snowy.
   - VL and clouds use separate, more saturated variants (`COMPOSITE1`/`DEFERRED1` branches): VL at noon is light blue (0.4,0.75,1.3), so midday god rays read as blue "sky light" shafts and sunset shafts as orange.
2. **N·L shaping**:
   - "side shadowing" wrap: `NdotLM = max(N·L + 0.4, 0)·0.714`, so light wraps 0.4 past the terminator. This softens the dark side of blocks;
   - foliage: N·L = 1;
   - End: sqrt³.
3. **Direct** = lightColor × shadow (colored) × NdotLM × `shadowTime`. In the overworld it is also × cloud shadow (optional).
4. **Ambient multiplier** = smoothstep(skylight), or skylight³ in rain. Indoors this falls to 0 automatically, which is how caves get dark without exposure control.
5. **Directional shading** (replaces vanilla's baked face shade; Iris `oldLighting=false`):
   - up 1.0, down 0.5 (×(0.75+0.25·N·U)²);
   - north/south ×1.075, east/west ×0.9 (then squared in the final mix);
   - in the overworld the *light* is boosted 1+0.75·|N·E|² on east/west faces, because the sun travels east–west at 0° rotation;
   - Reimagined only: **fake bounce light** (ambient pulled toward light color by 5–8%·|N·N|·skylight²) and a noon boost on N/S faces.
6. **Block light**:
   - lightmap x → `(a·x^8 + b·x)^2.25` with a+b ≈ 5.6 (the `XLIGHT_CURVE` setting trades the steep and calm terms): very bright right next to the source, dropping fast;
   - × warm torch orange (0.1775, 0.104, 0.077);
   - in daylight, block light is *reduced* by raising it to a small power that grows with sky light and sun (torches don't glow at noon);
   - optional flicker (noise texture sampled by time);
   - "brighter lit particles" in the textured program.
7. **Held light** (`heldLighting.glsl`): `((level·0.47)/(dist+6))^4`, squared again, with a lava-bucket special case. It is combined with block light as `sqrt(block² + held)`, i.e. in quadrature.
8. **Minimum light** in caves: about (0.0056 + 0.043·brightness)·(0.45,0.475,0.6) where skylight is 0, so dark caves stay faintly blue-readable. Night vision adds a large blue-white term.
9. **Vanilla AO**: `pow(AO^1.5, 1 + 0.02·|scene|² + up-facing·(0.15+…))`, then 0.9·AO+0.1. AO is *deeper in bright light* and on top faces at noon. Foliage AO is weakened in light.
10. **Final mix** (the signature): `diffuse = sqrt( (dirShade·AO)² · (blockLight + sceneLight² + minLight) + emission² )`, with `sceneLight = lightColor·shadow + ambient·ambientMult`.
    - Sun and sky light are combined linearly, then *squared*; block light enters the sum as an already-squared energy; then everything is square-rooted.
    - This is light addition in quadrature. The result is soft and never clips: a torch in sunlight barely changes the image, and a torch in a dark cave is fully visible.
    - The albedo is multiplied by this, and a GGX highlight is added.
11. **Specular highlight**:
    - GGX D with the HZD sphere-light NoH approximation (sun radius tan 0.01), Schlick F with f0 = 0.05, roughness `(1.35 − sqrt(0.9s+0.1))^4`, soft-clamped `s/(0.125s+1)`;
    - color `normalize(lightColor^0.37)·(0.3+1.5·sunVis²)·(1−0.85·rain)`;
    - × shadow. Underwater: a weaker pow(shadow, 0.25).
12. **Rain and night tweaks**: within 96 blocks of the camera, ambient and light get +0.2–1% per block, and the distance gets darker. This fakes thicker air at night and in rain.

### 4.9 Emissives, colored block light ("ACT"), distant light bokeh

- **Emission** comes from the material heuristics (§4.11): torch flames above a red threshold, lava = 7.5·luminance+0.5, glowstone from green minus 0.3, and so on. It is added as `emission²` in the quadrature mix, so a lit texel stays its albedo color, brightened. It is reduced by 25% in direct sunlight.
- **Glowing ores**: average the 8 border texels of the sprite (the stone around the ore) and use `max|texel − border|` as emission. Only ore pixels glow. Unbound turns this on for all ores.
- **Colored lighting (Iris custom images; disabled on Mac)**:
  - the shadow vertex shader writes block ids into a camera-centred 3D `r16ui` volume (128³ … 1024×256×1024);
  - `shadowcomp` runs a ping-pong **flood fill**: each air voxel = (sum of 6 neighbours)/6.42 (the slight loss limits range); light-source voxels emit a per-block color (a table of about 60 colors: torches orange, soul fire cyan, beacons, froglights, amethyst…); translucent blocks multiply by a tint (stained-glass colored light);
  - optimisations: half the volume updates per frame, and voxels behind the camera only copy;
  - the volume scrolls with the camera;
  - shading samples the volume trilinearly (with a manual leak-proof filter in ultra), takes sqrt, rebalances it against the vanilla lightmap and uses it as the block-light color. It fades back to vanilla light at the volume edge;
  - **colored light fog**: an 8-block-step march through the volume in composite1, which adds glowing colored haze around light sources.
- **Distant light bokeh**: beyond about 60 blocks, emissive blocks are rendered with their mip-averaged color/emission and deferred1 does a 5-tap max-blur, so far torches become soft glowing dots instead of flickering sub-pixels.

### 4.10 Ambient occlusion

- **Vanilla per-vertex AO**, re-curved (above).
- **SSAO** (`deferred1.glsl:58-107`): full resolution. 4 pairs (quality 2) or 12 pairs (quality 3) of symmetric depth taps along a golden-angle spiral, scaled by FOV/distance; a horizon-like "angle + distance" heuristic per pair; result^(SSAO_I·0.004). It multiplies the already-lit color, so ambient and direct are both darkened. Skipped on hand and entities.
- No GI. The fake bounce is in §4.8.5, and the "AO stronger in brighter light" rule gives contact contrast at noon.

### 4.11 Materials

- **IPBR ("integrated PBR", default `RP_MODE 1`)**: about 2,850 lines (`lib/materials/materialHandling/terrainIPBR.glsl`) keyed by `mc_Entity` block id (from `block.properties`).
  - Each block class derives `smoothnessG` (highlight), `smoothnessD` (reflection), `highlightMult`, `emission`, `materialMask` (Fresnel class: "intense", copper, gold, redstone) and flags (subsurface, no directional shading, no smooth lighting) **from the albedo color with hand-tuned curves**. Examples:
    - stone: smoothness = 2·g⁴;
    - iron: r⁴ with an intense Fresnel, and the albedo darkened toward mid-grey;
    - leaves: from greenness, highlight reduced at grazing angles;
    - rails: split into redstone, iron, gold and wood parts by color tests.
  - Everything is procedural from vanilla textures. This is a large part of the "looks expensive" feel.
- **Generated normals** (off by default in IPBR): a bump map from 4 neighbour luminance differences inside the sprite (clamped to sprite bounds), with a dead zone of 0.05 and a clamp of 0.2.
- **Coated textures** (off by default): a per-texel multiplicative noise on a 64-px grid per sprite, randomised per block position. It breaks up tiling.
- **Custom PBR** (`RP_MODE 2/3`: SEUS-PBR or labPBR resource packs): normal map + specular map decoding (labPBR f0 / metal ranges, smoothness, emission in specular alpha), POM up to 128 samples within 32 blocks with slope normals and self-shadowing (`pomEffects.glsl`), directional block light from normal maps.
- **Rain wetness and puddles** (`gbuffers_terrain.glsl:256-311`, off by default; on at VERYHIGH and up):
  - mask = (sky light ≈ 1) × (no block light) × (up-facing²) × inRainy × wetness⁴;
  - puddle shape from 3 octaves of a large-scale noise thresholded at 7;
  - two scrolling noise normal maps (raindrop ripple wobble, pixel-quantised with water style 1);
  - smoothness toward 1 and a strong highlight;
  - reflections come from the PBR reflection pass (or a cheap sky-only Fresnel with low block reflection quality);
  - with colored lighting on, a 2D "covered" map voxelised from the shadow pass blocks puddles under roofs.
- **Snowy world** (option): turns up-facing foliage and grass white.

### 4.12 Reflections

- **Water and translucents** (inside `gbuffers_water`, `lib/materials/materialMethods/reflections.glsl`):
  - **SSR**: a view-space march starting slightly above the surface. Initial step 0.5 along R, **doubling every step** (exponential), up to 30 steps. When the ray passes behind the depth buffer by less than 3× the step, it refines (back off, step ×0.1), up to 6 times.
  - Accepted if the hit is not sky, faces the right way, and the error is small for the distance. The edge of the screen fades with an 8th-power falloff, plus dither jitter near the edges.
  - The color comes from the **scene copy written by deferred1** (colortex5, stored as sqrt(c)·0.5 in RGBA8 and decoded by squaring ×2). Fog is applied to the reflected point.
  - Low quality: "mirrored image" (project the reflected direction at a fixed offset and sample once).
  - **Fallback**: `GetSky()` in the reflected direction + a GGX sun glint + optional clouds/aurora/stars in the reflection (`SKY_EFFECT_REFLECTION`, HIGH and up) × sky-light factor `((lm.y−0.7)·3.33)²`. Under cover, the reflection becomes a darkened version of the surface instead of bright sky.
  - Water blend: `mix(color, reflection, (fresnel³·0.85+0.15)·reflectMult)`.
- **Opaque PBR reflections** (`program/composite.glsl`, **half resolution**):
  - same SSR, but the normal gets a **3-channel blue-noise jitter × (1−smoothness)²·0.3** that rotates per frame (stochastic glossy reflection);
  - mip-level selection from roughness and hit distance (`texture2DLod(colortex5, …, lod)`);
  - **temporal accumulation**: reproject the *virtual reflected point* (surface position + hit distance along the view ray) into the previous frame, which is parallax-correct for reflections. Validity = exp(−penalties for screen distance, camera speed, pixel motion, normal change vs the previous normal buffer, depth mismatch, off-screen). Blend with min factor 0.035–0.125;
  - in composite1: a 5×5 Gaussian-weighted **bilateral blur** (rejects normal difference > 0.1, and depth at ultra) at a 3–9 px dithered radius;
  - "texture preservation": the reflection is pulled 70% toward max(surface, reflection), so reflections never make a block darker than its texture. This is an artistic choice;
  - × Fresnel: Schlick-like with a sharpened curve in SSR-only mode to hide screen-space failures.
- **World-space reflections** (WSR, ultra and up, needs colored lighting, so not on Mac):
  - the shadow pass voxelises the scene into a 3D `r16ui` (block id / texture slot, per-block atlas data in an SSBO) plus a 4×-coarser occupancy volume;
  - composite traces reflections with a **two-level DDA** (coarse 4³ cells, then voxels);
  - hits are shaded with the shadow map, the flood-fill light and the block atlas, and the player model is ray-traced as boxes;
  - SSR is used first and WSR fills in what SSR misses.

  It is effectively a software voxel ray tracer.

### 4.13 Water (`lib/materials/specificMaterials/translucents/water.glsl`, `program/gbuffers_water.glsl`)

- **Color**: biome water tint, sqrt-softened and shifted slightly toward cyan. Style 1 uses the squared vanilla texture; style 3 a flat 0.375 tint. A slow noise modulates saturation.
- **Waves**:
  - vertex: two sine waves of ≈ 0.1 block amplitude, only on surfaces under open sky;
  - fragment normals (style 2/3): 4 octaves from `cloud-water.png` RG (scales ×1, ×4, ×0.25, ×0.05 with different wind speeds), weighted small 0.75, medium 1.7, big 2.0 (settings), scaled down at grazing angles and by sky light (calmer indoors);
  - 4-step **parallax** using the A channel as height;
  - normals that would point into the water after reflection are blended back toward the geometric normal.
- **Opacity from depth**: `waterFog = 1 − exp(0.075·(d_surface − d_floor))`; alpha = 0.25 + 0.75·waterFog. Shallow water is clear, deep water opaque. "Bright cave water" boosts color in shallow or dark water so it stays visible.
- **Foam**: on top faces of real water, where the floor is within about 1.6 blocks of the surface (shore), foam = ((threshold + Δy)/threshold)², with the threshold from texture brightness (style 1) or noise (style 3). White-blue foam reduces reflectivity.
- **Fresnel**: `fresnel = 1 + N·V`; alpha → 1 at grazing; reflection weight as above.
- **Highlight**: style 3 uses a smoothness-1 GGX plus a bump-normal glint term, `((N_bump·L / N·L)·1.1)^4·0.24`, faded at distance by mip level. That gives sparkly sun glitter.
- **Refraction** (`refraction.glsl`, composite1, DETAIL ≥ HIGH):
  - a screen-space offset from world-space noise, ∝ intensity·FOV/(3+dist), × the depth gap to the floor, and only if the offset pixel is also water;
  - the already-composited color is re-sampled at the offset. That is a cheap warp, not a true refraction of the opaque layer.
- **Caustics**: in the shadow map color (§2), so they show only on sunlit underwater surfaces.
- **Underwater**:
  - exponential-squared water fog toward `waterFogColor` (default (0.46,0.62,1.0) × 0.2–0.3);
  - composite1 multiplies the scene by (0.80,0.87,0.97)·0.85 and the VL by the same squared ×0.55;
  - the surface from below uses a total-internal-reflection-like Fresnel (`translucentMult *= 1 − fresnel^4`);
  - water-tinted god rays with procedural shaft noise (the shadow pass writes a noise-modulated color into shadowcolor1);
  - final-pass sine screen wobble;
  - bloom fog ×14 underwater.

### 4.14 Translucents and glass

- **Translucent tint buffer**: every translucent writes `translucentMult`, the tint light gets when it passes through, into colortex3, stored as 1−tint. composite1 uses it to tint VL, colored light fog, rainbow and rain *behind* glass or water.
- **Glass**:
  - "fancy glass": the clear part of vanilla glass becomes a light grey-blue (0.75,0.8,0.85) at alpha ≥ 0.25, with a strong highlight and reflection;
  - distant glass becomes more opaque and reflective (192-block ramp), which hides sorting artifacts and looks like real windows;
  - glass close to the camera loses reflectivity and opacity;
  - stained glass: smoothness 0.5, reflective;
  - connected-glass edges are faked by sampling the sprite border (needs colored lighting, so not on Mac).
- **Nether portal** (`netherPortal.glsl`): an 8-sample *parallax* through the portal texture along the view vector (inner depth), emission from red^8, a soft close-up fade.
- **End portal**: a modified multi-layer parallax star effect.

### 4.15 Waving foliage (`lib/materials/materialMethods/wavingBlocks.glsl`, vertex stage, also in the shadow pass)

- Base wave = 3 sines at incommensurate time frequencies, phase-shifted by world x, y, z, with an amplitude that also oscillates. It is evaluated at `time·170·WAVING_SPEED`.
- Grounded foliage: only the *top* vertices move. The test is "texcoord above the sprite middle", or a y-fraction test. x ×3, z ×8, y 0.
- Leaves: whole block, ×(4,3,8). Disabled in snowy biomes.
- Vines ×0.75. Nether vines (with voxel checks so attached vines don't sway). Lava ±0.0125, lily pads.
- All scaled by `clamp(skylight − 0.87, 0, 0.1)`, so nothing waves indoors, and by rain intensity.
- Rain particles also lean with wind (`WAVING_RAIN`).

### 4.16 TAA and anti-aliasing

- **Jitter**: an 8-point pattern (Chocapic13 set), scaled ×0.125 by default (`TAA_JITTER 1`). The jitter is *tiny*. The pack relies on TAA mainly to integrate per-frame noise (shadows, VL, SSR, clouds, dither), not for geometric supersampling. That keeps the image sharp.
- **History in display space**: TAA runs **after the tonemap** on the 8-bit image (colortex3) with RGB16F history. This avoids HDR fireflies and the need for tonemap-aware blending.
- **Reprojection**: camera-only (depth → previous view-projection). The hand uses no reprojection. Entities and "no TAA" materials skip TAA entirely.
- **History sampling**: 5-tap Catmull-Rom (sharpness c = 0.7).
- **Clamping**: AABB *clip* against the 3×3 min/max.
- **Blend**:
  - `max(exp(−10·|v_px|²)·0.2 + 0.7 − min(camera_move, 0.05)·edge, 0.35)` (smoothing 3);
  - `edge` (6, or ×3 on leaves) is set where neighbour linear depth differs by > 0.09, which reduces history at depth edges while the camera moves;
  - off-screen: no history.
- **FXAA 3.11** after TAA (on by default from MEDIUM), with a TAA-interaction setting.
- **Sharpening** in final: a 4-neighbour unsharp mask (×1.25 at strength 5), effectively a light CAS.
- No upscaling: Iris renders at native resolution.

### 4.17 Bloom (`program/composite4.glsl`, `program/composite5.glsl`)

- colortex0 gets a full mip chain.
- composite4 writes an **atlas of 7 tiles** (LOD 2 to 8, scales 1/4 … 1/256), each computed with a **7×7 binomial (1,6,15,20,15,6,1)² filter** of the mip-sampled scene. Each tile covers only its region, and the atlas is laid out in 1920×1080-equivalent coordinates.
- Storage: RGBA8 with `(x/128)^0.25`, a fourth-root encoding that gives HDR range in 8 bits.
- **Composite**: decode, average the 7 tiles (each ×0.14), then `color = mix(color, bloom, 0.12)`.
  - A *mix*, not an add: bloom slightly reduces local contrast and creates a dreamy glow everywhere, not only on highlights. There is **no threshold**. Strength increases with darkness or the Nether.
- Combined with "bloom fog" (§4.5), this gives the hazy, glowing distance.

### 4.18 Exposure, tonemapping and color grading (`program/composite5.glsl`)

- **No auto-exposure** (no histogram, no average luminance). `TM_EXPOSURE` is fixed at 1.0. Scene brightness is kept stable by construction:
  - ambient follows sky light;
  - block light is tuned against daylight;
  - caves have a minimum light;
  - night colors are pre-brightened.
- **Order**: optional 3×3 channel mixer with per-channel power (`COLORGRADING`, off by default) → tonemap → lens flare → saturation.
- **Tonemap = Lottes 2016 generalised curve**, per channel:
  - `y = x^a / (x^(a·d)·b + c)`, with b and c solved so the curve maps mid-grey `midIn → midOut` and `hdrMax → 1`;
  - the pack's parameters: contrast a = 1.05 (setting), shoulder d = 1, hdrMax = 8, mid-grey in = out = 0.25;
  - then the sRGB OETF;
  - **dark lift**: below luminance 0.1, blend up to 75% toward plain `x^(1/2.2)` (un-tonemapped) for readable darks. The amount shrinks as contrast moves away from 1.05;
  - **path to white**: from luminance 0 to 16, blend toward white along a smoothstep of `(L/16)^(2−whitePath)·16` (very bright highlights bleach out like film);
  - **dark desaturation**: below luminance 0.1, desaturate 25% toward grey (dark areas go neutral, like scotopic vision).
- **Saturation and vibrance** ("BSL color saturation"): vibrance boosts low-saturation, mid-luminance colors; saturation +0.07 above the user setting (so the default is ×1.07).
- **Result**: slightly punchy, warm-saturated midtones, soft rolled highlights, neutral readable shadows.

### 4.19 Lens and camera effects

- **Lens flare** (off by default, `lensFlare.glsl`): about 18 analytic ghosts (discs, overlapping-disc crescents, point dots, 2 rings), placed along the sun–screen-center axis with warm-to-blue color ramps. The shape is round or squarish (rounded square by L4 norm). It is occluded by 8 depth and cloud-depth probes around the sun, faded near the screen center and in rain, and blended as `mix(color, white, flare)`. In a mode it can also run for the moon in blue-grey.
- **World blur**: distance blur or DOF (`composite3`). 18 taps on two hexagonal rings, with mip-LOD sampling proportional to the CoC, optional chromatic split and anamorphic stretch. Focus is the auto center depth or a fixed distance.
- **Motion blur** (off): camera-only velocity, 9 taps, with bloom fog removed and re-applied per sample.
- **Chromatic aberration** (off): a radial R/B split in final.
- **Vignette** (on): `1 − |uv−0.5|²·(1−L)`. Bright areas are not darkened.
- **Underwater distortion**: a sine wobble of UV in final.
- **Final dither**: noise ±1/256.

### 4.20 Nether and End looks

- **Nether**:
  - no sun; light color 0;
  - ambient = biome-tinted fog color (custom palette per biome: wastes orange-red, crimson red, warped teal-violet, basalt grey, soul cyan) + lava glow;
  - directional shade: lava glow from below and the sides (`(|N·N|·0.5 + max(−N·U,0))²` × lava color);
  - border fog toward the Nether color with a hard `NETHER_VIEW_LIMIT` (256);
  - bloom fog ×14 (glowing haze);
  - **Nether storm** (`netherStorm.glsl`): a 3D noise ray march (1 step per 8 blocks up to 512 blocks, 4 octaves of Noise3D per step), a height band around altitude 28 ± 200, colored by the Nether color, alpha-blended. This gives drifting ash/heat clouds at the cost of up to 64 steps × 8 fetches per pixel;
  - lava gets a slight noise darkening.
- **End**:
  - sky = flat purple (0.095,0.07,0.15)·1.65 + End stars;
  - light = lavender (0.68,0.51,1.07) from a fixed direction (sunPathRotation 0);
  - shadows on; VL always active in the End: 3× near samples, 1536-block range, multiplied by **"End beams"**. These are noise-driven vertical curtains around a center altitude (default 68): purple with orange "fire" streaks, brighter far from the island;
  - the `vlFactor` scalar animates the beams to calm down once the dragon is dead (fog check);
  - "End flashes" (26.x feature): a directional orange/purple flash lighting surfaces that face the flash and brightening the beams in its direction.

### 4.21 Weather

- Rain and snow particles are lit with the scene light: block light + (ambient + 0.2·light).
- Opacity: rain 0.25, snow 0.5.
- Rain streaks lean with wind.
- "Improved rain" (not on Mac): rain writes distance, type and light into colortex12 instead of blending. composite1 composites it after VL and fog, tinted by translucents and colored light fog, so it doesn't break TAA or fog.
- Rain also:
  - desaturates and greys the sky;
  - softens shadows (radius ×3);
  - dims sun glare;
  - adds near fog and bloom fog ×8;
  - lowers VL brightness;
  - raises ambient in the near field;
  - adds wet puddles (option);
  - leaves a rainbow afterwards.
- Biome weather: dry biomes get dusty orange fog in "rain" (sandstorm look). Snowy biomes get bluish fog.

### 4.22 Other notable details

- **Material mask enum** (8 bits in colortex6.g). It carries Fresnel class, water, entities, "no SSAO", "reduced edge TAA" (leaves), selection outline and green screen through the post chain.
- **Selection outline**: a colored outline (pink-purple default) and a "versatile" mode that inverts luminance.
- **Optional dark or world outline**, pixelated shadows/AO/blocklight (texel-snapped lighting), hand sway, light-level display.
- **Moon phase influence** on light, atmosphere and reflections.
- **Pale Garden**: special stronger night shafts.
- **Sulfur caves** (26.2+): yellow fog.

---

## 5. Settings profiles (`pack:shaders.properties:1-9`)

| Profile | Shadows (quality / dist) | SSAO | AA | Detail | Clouds | VL | Water refl | Block refl | AF | Colored light | WSR | Entity shadow | Puddles |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| POTATO | off / 64 | 2 | none | 0 | 1 | off | 1 | 0 | 0 | 0 | off | off | 0 |
| VERYLOW | 0 (very low) / 64 | 2 | none | 0 | 1 | off | 1 | 0 | 0 | 0 | off | off | 0 |
| LOW | 0 / 96 | 2 | none | 2 | 2 | off | 2 | 1 | 0 | 0 | off | off | 0 |
| MEDIUM | 2 / 128 | 2 | FXAA+TAA | 2 | 2 | low | 2 | 1 | 0 | 0 | off | off | 0 |
| HIGH (= built-in default) | 2 / 192 | 2 | FXAA+TAA | 2 | 2 | medium | 2 | 3 | 0 | 0 | off | on | 0 |
| VERYHIGH | 2 / 192 | 2 | FXAA+TAA | 3 | 2 | medium | 2 | 3 | 0 | **192** | off | on | 2 |
| ULTRA | 2 / 224 | 2 | FXAA+TAA | 3 | 3 | medium | 2 | 3 | 8× | **256** | **on** | on | 2 |
| COMPLEMENTARY | **3** / 256 | 2 | FXAA+TAA | 3 | 3 | **high** | 2 | 3 | 8× | **512** | **on** | on | 2 |

Notes:
- `DETAIL_QUALITY`:
  - 0 = potato shortcuts (leaf shadow optimisation, low-quality Nether storm and water material, no TAA);
  - ≥ 2 enables TAA;
  - ≥ 3 adds water refraction, sky effects (clouds/aurora) in water reflections, HQ nebula, more foliage waving and an entity/cloud TAA fix;
  - 4 adds sky effects in block reflections, a depth-checked reflection blur, and AF on translucents.
- `CLOUD_QUALITY` 3 = 1-block steps (Reimagined) or 16-block steps (Unbound) and a thicker Unbound layer.
- `SHADOW_QUALITY` 2/3/4/5 = 4/8/16/32 filter taps (doubled on close foliage); 5 also uses 4096².
- `BLOCK_REFLECT_QUALITY` 3 = PBR SSR on blocks (half res, temporal). 1 = specular highlights only.

---

## 6. What it disables on macOS (`MC_OS_MAC`)

- **Colored lighting forced to 0** (`shaders.properties:72-74`; `lib/common.glsl:33`). macOS OpenGL 4.1 has no image load/store. This removes, all at once:
  - the flood-fill colored block light and its colored light fog;
  - world-space reflections;
  - puddle voxelisation (no roof check);
  - the connected-glass effect;
  - the portal edge effect;
  - the colored candle light option.
  
  `final.glsl:23` shows an "Apple ACT" error text if the user turns it on.
- **Improved rain off** (`lib/common.glsl:693`, `shaders.properties:149`), so the plain blended weather is used.
- **composite1 sampler budget** (`program/composite1.glsl:23-28`): with Distant Horizons or Voxy, it drops refraction, smoothness-aware reflection blur and the chunk-fade variable, to stay under the 8-sampler limit noted in that file's comment.
- Everything else (shadows, VL, clouds, SSR, TAA, bloom) runs on Mac, but on the OpenGL 4.1 → Metal translation layer at full Retina resolution.

A native Metal pipeline can do all of the disabled features. Compute and read-write textures (tier 2) support the flood fill and voxel structures, and on the M4 hardware RT replaces WSR.

---

## 7. Data Complementary depends on vs what we have

| Input it uses | Purpose | What we have in mcopt 26.3 / Sodium (mcopt-architecture §4) | Plan |
|---|---|---|---|
| `mc_Entity` block id (`block.properties`) | waving, IPBR heuristics, emission, water/glass/leaf classes, SSS | none (Sodium vertex has no block id) | **Atlas material mask**: R8/R16Uint texture over the block atlas, built on stitch from sprite names (critique M2.2). Good enough for about 40 classes. |
| `mc_midTexCoord` / sprite bounds | generated normals, bottom-anchored foliage waving, ore border averaging, foliage tweaks | atlas UV only | Sprite-rect lookup: a second atlas-sized texture storing sprite min/size (RGBA16), or a sprite table indexed by the material mask. Pull it from the quad's 4 corner UVs in the pulled vertex path. |
| `gl_Normal`, `at_tangent` | lighting, TBN for water/puddles/normal maps | none | Face normal from quad corners (M1 does this). Tangent is trivially derived for axis-aligned quads (U/V along the two edges). |
| `glColor.a` = vanilla AO (Iris `separateAo`) | AO curve, fake penumbra, leak fix | Sodium bakes AO × face shade × biome tint into rgb | Mixin Sodium's color packing to move AO into alpha (alpha is free on opaque terrain; cutout uses the texture alpha), or a second-pass division. **Needed** for the look: AO is re-curved by light. |
| lightmap (x,y) | block/sky light | yes (`a_LightAndData.xy`) | same |
| `skyColor`, `fogColor`, sun angle, rain, wetness, biome precipitation, eye brightness, moon phase, `isEyeInWater`, blindness/darkness | sky, fog, all colors | partly in `SkyRenderState`/`levelRenderState` | `ShadeFrame` UBO (critique M1.1). Add **smoothed** biome scalars (dry/rainy/snowy), wetness, eyeBrightness smoothing and cave factor on the CPU. |
| shadow tex0/tex1/color | colored shadows, caustics | M1: depth-only cascades | Add a translucent caster pass (depth + RGBA8 tint) in M3. |
| previous matrices / camera | TAA, reflection reprojection | M1 adds previous-frame matrices | same |

---

## 8. Why it costs so much on this M4 at 3024x1964 (estimate)

Each line uses apple-gpu numbers: about 1.0–1.1 ms per uncompressed RGBA16F-class read+write pass at native, about 0.3–0.5 ms when compressible, and 2.8–5.2 ms for the shadow pass. Iris on macOS GL goes through Apple's GL-on-Metal layer, so assume the uncompressed rate.

| Iris pass | Est. ms at native Retina |
|---|---|
| shadow 2048², 192 blocks, all faces + entities | 3–5 |
| gbuffers terrain/entities (forward-lit, 4-tap TAA PCF, IPBR) | 2–3 |
| deferred1 (SSAO 8 taps, fog with GetSky, cloud march on sky pixels) | 2–3 |
| gbuffers_water (SSR in-fragment on water pixels) | 0.5–2 |
| composite (half-res PBR SSR + temporal) | 0.5–1.5 |
| composite1 (VL 10–15 shadow taps, 25-tap reflection blur, refraction) | 2–3 |
| composite3 copy + colortex0 mip chain | 1–1.5 |
| composite4 bloom tiles (49 taps in a 1080p-sized region) | 0.7–1.2 |
| composite5 / 6 (TAA) / 7 (FXAA) / final | 3–4 |
| **Total** | **≈ 15–24 ms → 40–65 fps** at native, before CPU and driver overhead. Consistent with the user dropping to 1080p. |

The lessons for us:
- most of the cost is fixed full-resolution passes and the full shadow map, not the shading math;
- render the world at 0.5–0.67× with our own TAAU;
- merge passes;
- keep targets compressible;
- use cascades or RT for shadows.

---

## 9. What we should emulate (the qualities that make it look great)

1. **Warm light, cool shadows, with strong time-of-day storytelling.**
   - Noon: neutral-warm sun, blue sky ambient.
   - Golden hour: deep saturated orange light with long VL shafts and an orange horizon band widening around the sun.
   - Night: blue-violet, readable moonlight with stars.
   
   Every color is a function of a few scalars (sun height, rain, biome). Copy the *idea* of one central color-state block that every effect reads, not the numbers.
2. **Lighting that never clips and never looks flat.**
   - Combine sun+sky light and block light in quadrature (sqrt of the sum of squares) in a perceptual domain.
   - Wrap lighting past the terminator.
   - Make AO stronger in strong light.
   - Faint fake bounce on side faces.
   - Torches that fade in daylight and dominate in caves.
   
   This is the core of the "cozy but rich" Complementary feel.
3. **Readable darkness without auto-exposure.** Sky-light-driven ambient, a bluish minimum cave light, tonemap dark lift and dark desaturation. Night and caves feel dark but are always playable.
4. **Sky ⇄ fog ⇄ reflection consistency.** One sky function feeds the sky, the border fog, cloud fog, water reflection and the reflection fallback, so distant terrain dissolves seamlessly into the exact sky color behind it. Add altitude-dependent haze (valleys hazy, peaks clear).
5. **Atmosphere that glows.** Bloom without a threshold, mixed at about 12%, plus "bloom fog" that boosts glow contribution with distance, rain, night, caves and water. Distant scenery gets a soft luminous haze. Distant light bokeh turns far torches into glowing dots.
6. **Living foliage.** Gentle multi-sine waving (top vertices only, none indoors), light-through-leaves SSS highlights toward the sun, and per-tap depth jitter that lets light leak into canopies.
7. **Water that sells.** Deep tinted color with depth-based clarity, shoreline foam, crisp SSR and sky/cloud reflections, a sharp sun glitter (Unbound), caustics on the floor, blue god rays underwater.
8. **Scene-aware god rays.** Stronger under canopies and in caves, subtle in open fields, colored through glass and water, saturated at sunset.
9. **Material richness from vanilla textures.** Per-block specular and reflectivity heuristics from albedo (metals shine, stone has a faint sheen, ores glow), with no resource pack needed.
10. **Signature skies.** Blocky-but-soft Reimagined clouds or fluffy Unbound clouds with silver linings; full-moon auroras in snowy biomes; rainbows after rain; End beams and Nether ash storms.
11. **Film-like tone.** A Lottes-style contrast curve, highlights bleaching to white, gentle vibrance, light vignette, a hint of sharpening.

---

## 10. Port notes for our Metal/TBDR pipeline

Assumptions from apple-gpu.md and critique.md:
- output 3024x1964;
- internal 0.5x (1512x982, 1.48 MP) to 0.67x (2016x1309, 2.64 MP) with our TAAU (0.62–0.72 ms);
- fragment-shader post into compressible targets;
- TBDR single-pass with a memoryless G-buffer in M3;
- RT shadows in M4.

"(est.)" marks estimates. Costs are GPU ms at 0.5x / 0.67x internal unless noted.

### 10.1 Architecture mapping

| Complementary | Ours |
|---|---|
| Forward lighting in `gbuffers_*` | M1/M2: forward lighting in `sodium_terrain.fs.metal` (and entity overrides). M3: TBDR single-pass deferred (memoryless albedo/normal/material + full-screen lighting draw reading `[[color(n)]]`). Same math in both. |
| colortex0 perceptual color, linearised in composite1 | Store **linear HDR** (RG11B10F or RGBA16F) from the start. Reproduce the quadrature mix by evaluating it in a perceptual domain inside the lighting function and converting the result to linear once (`pow 2.2` → a couple of ALU ops). Same look, one consistent space for VL and bloom. |
| deferred1 + composite + composite1 + composite3 (4 full-res passes) | **One "atmosphere composite" pass at internal resolution** after translucents: fog for opaques can move into the lighting pass; then clouds (upsampled), VL (upsampled), reflections composite, underwater tint, rain. SSAO and SSR as small half-res passes before it. |
| composite4/5/6/7/final (5 passes + mips) | **TAAU (internal→output)** → **bloom pyramid** (Jimenez 13-tap down / tent up, first level from the internal image) → **one final pass**: bloom mix, grading, tonemap, lens flare, vignette, sharpening, dither. 3 output-resolution passes instead of 5. |
| TAA on the LDR tonemapped image | Our TAAU must run before the tonemap at upscale, so it has to handle HDR. Use a reversible luma-weighted tonemap inside TAAU (Karis trick) to get the same firefly robustness. Keep the history compressible. |

### 10.2 Per-effect port notes

**Global color state (M1, 0 ms GPU).** Compute all the per-frame colors on the CPU once per frame and put them in `ShadeFrame`: sun/moon light color, ambient, sky up/middle/down (day/sunset/night/rain blends), cloud colors, VL color, fog color, highlight color, plus smoothed biome scalars. Complementary recomputes them per pixel. We save ALU and get one source of truth. Author our own palette, tuned against screenshots, but keep the *structure*: separate palettes for ground, cloud and shaft light; sunset light raised to a sun-height-dependent power; ambient from the sky color.

**Sky (M1).**
- Our M1 plans an analytic single-scattering atmosphere. To hit the Complementary *art* look:
  - render the physical sky into a small LUT (for example 128×64 per frame, < 0.05 ms);
  - then apply an **art grade**: horizon band broadening toward the sun, per-channel ground falloff, a sunset band, cool night with anti-solar lift, and a hyperbolic glare halo.
- Alternatively, implement a pure artistic gradient like theirs (pure ALU, < 0.05 ms at internal res).
- In both cases expose one `skyColor(dir)` function for fog, reflections and cloud fog.
- Stars and the moon: hash stars on a sky plane (ALU) and a procedural moon with a phase terminator (Unbound style).
- Cost: under 0.1 ms.

**Sun/moon disc (M1).** Draw a procedural disc in the sky pass: sun radius about 0.5–1° with limb darkening, a bright HDR value so bloom makes the glow. Moon from a crater noise texture with a phase terminator from a second vector. Both cost about 0.

**Shadows (M1 → M4).**
- M1's 2-cascade, sun-facing-faces-only map with staggered far cascades (≤ 1 ms) is already better than Complementary's single distorted map: no distortion warping, and stable texel snapping.
- Port these Complementary details because they are cheap and visible:
  1. **rotated golden-spiral taps with per-frame IGN rotation + TAA**: 4–8 taps is enough. Hardware 2×2 compare per tap via `sample_compare`;
  2. **radius ×(1+2·rain²)**: soft overcast shadows;
  3. **normal-offset bias that grows with dist^1.5**;
  4. **block-center pull by (1 − max(AO, skylight)^4)**: cave light-leak fix, pure ALU;
  5. **leaf per-tap depth jitter**: cheap SSS through canopies;
  6. **shadow fade to skylight^8** at the far edge;
  7. **shadowTime fade** at the sun/moon switch.
- **Colored translucent shadows + caustics (M3)**: a second small pass draws translucent casters (water, stained glass) into a separate depth + RGBA8 tint target within the near cascade (est. 0.1–0.2 ms). Procedural caustics in the tint for water (2 texture fetches).
- **M4 RT**: one any-hit ray per pixel (0.42 / 0.67 ms at 0.5x / 0.67x), with soft shadows by cone-jittered rays + TAA. Keep a cheap 1024² map for VL.
- Per apple-gpu, in the TBDR lighting pass 4 vs 16 taps vs PCSS costs nothing extra (geometry-bound), so we can afford the "high" tap count.

**Lighting model (M1/M2, essentially free inside the geometry-bound pass).**
- Implement our own version of:
  - wrap N·L (about 0.4 wrap);
  - foliage N·L = 1 plus a (V·L)^10 transmission highlight;
  - E/W light boost if we keep a 0° sun path, or a tilted sun path (Unbound's −40° is more dramatic; consider −25° to −40° as our default);
  - ambient = sky color, scaled by a curve of sky light;
  - block light curve (steep plus calm term), warm color, faded in daylight;
  - held light in quadrature;
  - minimum cave light;
  - the quadrature combine;
  - GGX sphere-light highlight (HZD paper);
  - AO re-curved by light (needs separated AO, §7).
- The material class comes from the atlas mask. Start with about 15 classes:
  - foliage (ground/upper), leaves, vines;
  - water, lava, glass, ice;
  - torch/fire, glowstone/froglight/sea lantern/redstone emissives;
  - ores;
  - metals (iron, gold, copper);
  - stone-like;
  - wood.
- Per-class smoothness/emission rules can mimic IPBR's "derive from albedo" approach in our own formulas.

**SSAO (M2 option).** Complementary uses full-res 4 pairs. Ours: half internal res, 8 samples (apple-gpu: 0.08 / 0.16 ms), bilateral upsample in the lighting pass. With M3 TBDR, apply it to the ambient term only, which is more correct than multiplying the lit color. M4 option: RTAO (1 ray, 4 blocks: 0.60 / 1.13 ms).

**Atmospheric, border, cave, water fog (M1).**
- Do fog analytically in the lighting pass for opaques, and in the translucent shader for water and glass.
- Port:
  - the super-linear distance curve;
  - the **altitude falloff band** (strong below about 63, gone about 60 blocks higher);
  - fog color from the sky function (biased toward the sun);
  - border fog `1−exp(−3·(d/R)^16)` to the exact sky color, which also drives `skyFade`;
  - cave fog from (below sea level × low eye sky light);
  - squared-exponential water fog.
- Cost: ALU only, about 0 extra (inside existing passes).

**Bloom fog (M2, free).** Multiply the linear scene by `1 + f(dist, rain, night, cave, water)` before building the bloom pyramid, and divide after. Because our bloom source is the internal-resolution image, apply it in the atmosphere composite and undo it in the final pass using a stored distance (half-float depth, or recompute from depth) or a 1-channel "bloom fog factor" written by the atmosphere pass (R8, internal res, about 0.02 ms).

**Volumetric light (M2).**
- Complementary: full-res, 10–15 point taps.
- Ours:
  - **half internal res** (0.74 MP at 0.5x);
  - 12–16 taps against the far cascade or a dedicated 1024² VL map;
  - the same `t^2` distribution with a slice-weight integral so density doesn't depend on step count;
  - IGN + golden-ratio dither, temporal accumulation (reuse the TAAU reprojection) and a depth-aware upsample;
  - apple-gpu: 16 taps half-res 0.32 ms at 0.5x, 0.47 ms at 0.67x.
- Port the **scene-aware factor**: read a 5×5 grid of the near-cascade depth above the player (one tiny compute or fragment of 1 thread, or do it on the CPU from the shadow camera), compare caster height above the eye, and slowly integrate a 0..1 "under cover" scalar. It drives shaft strength and fog curve. Cost 0.
- Colored VL through translucent casters needs the M3 tint map.
- Keep the sunset saturation, the noon reduction and the light-blue noon shaft color as art choices (our own values).

**Clouds (M3; a simple 2D version can come in M2).**
- **Reimagined-style**: a binary cloud map (our own procedurally generated 256² tile, or vanilla's `clouds.png` from the resource manager, which keeps vanilla's cloud shapes), rounded-texel sampling, first-hit march through a ±4-block slab. At quarter internal res (0.37 MP at 0.5x), ≤ 16 steps with early exit, est. 0.1–0.2 ms. Lighting: 2 light-direction samples plus a height gradient.
- **Unbound-style**: 3D fBm. Use a **real 3D noise texture** (64³ or 128³ RG8 tiling Worley+value, built once) instead of their 2-fetch slice trick: 1 fetch per octave with hardware trilinear filtering. 3–4 octaves, 4–8 steps through the slab, front-to-back with early-out, a forward-scatter rim term, distance fog to the sky color. Quarter res + temporal reprojection (moving per-frame jitter) + bilateral upsample: est. 0.3–0.6 ms at 0.5x.
- Both write a cloud depth (R16F) used by: water discard (we can instead blend clouds over water in the composite), lens flare occlusion, rainbow, TAA.
- Clouds in water reflections: evaluate a cheaper 2D cloud lookup for reflected rays, or sample the cloud buffer at the reflected screen position.
- Cloud shadows (option): project the 2D map along the light in the lighting pass, 1 fetch.

**Stars / aurora / nebula / rainbow (M3, cheap).**
- All ALU or a few fetches on sky pixels only. Aurora: 10–16 plane samples at half res (est. < 0.1 ms, night only). Rainbow: ALU in the composite.
- Put them in the sky pass, so they are naturally occluded and fogged.

**Water (M2).**
- Complementary does water shading and SSR in the translucent fragment shader. On our pipeline:
  1. suspend the pass and copy the opaque HDR color and depth (critique M2.3);
  2. water fragment shader: normals from a 4-octave scrolling normal texture (our own texture: generate a tileable normal and height map offline from FFT or Gerstner sums) with a 2–4 step parallax; Fresnel; depth-based clarity (`1−exp(−k·Δd)`) using the copied depth; shoreline foam where Δd is small; sun glitter from the bump normal; sky reflection from `skyColor(R)` × sky-light factor;
  3. reflections: **SSR on water pixels only** at half res with Hi-Z + 4-step binary refinement and a sky fallback (apple-gpu: 0.3–0.5 ms), or **RT reflections on water pixels** in M4 (measured cheaper than 24-step SSR, and they reflect off-screen geometry);
  4. refraction: true refraction by sampling the *copied opaque* color with a normal-derived offset, depth-validated. Better than Complementary's post-warp and the same cost.
- Underwater: squared-exponential fog, color multiply, water-tinted VL, screen wobble in final.
- Cost: water forward pass 0.3–0.6 ms (est.) + SSR 0.3–0.5 ms at 0.5–0.67x.

**Block PBR reflections and puddles (M3/M4, optional "Quality" tier).**
- Complementary: half-res stochastic SSR + temporal + 5×5 bilateral.
- Ours: limit it to pixels with smoothness > threshold (material mask), half res, reuse TAAU motion and history. Or with RT on M4: 1 glossy ray on reflective pixels only (est. 0.2–0.5 ms at 0.5x, depending on coverage).
- Rain puddles: noise-thresholded up-facing wet mask × wetness × sky light ≈ 1. Roof check via the sky lightmap (already 0 under roofs), or a 2D heightmap from the shadow cascade, which needs no voxelisation.

**Colored block light (Complementary's ACT, M3+, a real upgrade over Mac Complementary).**
- A camera-centred 3D texture (for example 128×64×128 RGBA16F = 8 MB; or 256×128×256 = 64 MB, too much on 16 GB with everything else, so prefer 128–192).
- Voxel occupancy and emitters come from Sodium section data on the CPU (block states are known per section), or from a GPU kernel that scans section geometry plus the atlas material mask.
- A compute flood fill (6-neighbour average, slight loss), half the volume per frame, scrolling with the camera. The ShaderWrite penalty doesn't matter for a small volume: 128×64×128×8 B = 8 MB per iteration, about 0.1 ms (est.).
- Shading: one trilinear fetch per pixel. Colored light fog: an 8–16 step march at half res (est. 0.1–0.2 ms).
- This gives the colored torch/soul-fire/amethyst lighting that the Mac build of Complementary cannot do.

**Waving foliage (M2, vertex stage).**
- Needs the material class (atlas mask) and a "top vertex" test. Our vertex shader can compare the vertex's atlas v with the sprite's center, or use the quad corner index plus face orientation.
- Use the same multi-sine idea with our own frequencies, the sky-light gate (no waving indoors) and rain amplification. Apply the same displacement in the shadow and VL map pass.
- Cost: about 0.05 ms of extra vertex ALU. The terrain pass is geometry-bound on Apple (vertex stage), so keep it to a few sines and no texture fetches. The material mask fetch in the vertex shader adds 1 texture read per vertex: acceptable.

**Emissives and glowing ores (M2).**
- Emission per class from albedo rules. Ore border-average needs sprite bounds, so precompute an "ore emission mask" atlas offline at atlas stitch time on the CPU (compare each texel to the sprite's border average). It becomes a free lookup at runtime.
- Emission adds HDR radiance, so our bloom does the glow.
- Distant light bokeh: replace with mip-biased emissive sampling beyond about 60 blocks. TAAU plus bloom handles the rest.

**Translucent tint buffer (M2).** Write the transmittance tint of the nearest translucent (RGBA8, internal res) during the water/glass pass, and use it in the atmosphere composite to tint VL, rain and fog behind glass. Cheap and important for correctness.

**TAA/TAAU (M2).**
- Our TAAU replaces Complementary's tiny-jitter TAA + FXAA + sharpen.
- Lessons to keep:
  - Catmull-Rom history;
  - AABB clip (variance clip is better);
  - depth-edge history reduction while moving;
  - a per-material "reduce TAA" flag for leaves (alpha-tested edges);
  - entities with per-object motion (Complementary skips TAA for them; we should write real motion vectors in M3);
  - a final light sharpening (RCAS-like) in the final pass.
- Use a full Halton(2,3) jitter (needed for upscaling), unlike their ×0.125 jitter.

**Bloom (M2).**
- Jimenez pyramid, 6 levels, fragment, compressible or lossy RGBA16F/RG11B10F: 0.5–0.74 ms at output, or less starting from internal res.
- Port the *look*: **no threshold, a low mix (≈10–15%) instead of an additive sum**, weights roughly flat across 7 octaves (wide, soft halo), bloom fog boost.

**Tonemap and grade (M2, final pass 0.35 ms).**
- Two candidate curves:
  - (a) our own **Lottes-family** generalised curve (public paper). Our parameters: contrast about 1.05, shoulder 1, hdrMax 8–16, mid-grey anchors;
  - (b) AgX, as in the apple-gpu plan.
- Complementary's specific character comes from the *extras*, which we should implement in our own way:
  - a dark lift to the un-tonemapped gamma curve below L ≈ 0.1;
  - path-to-white for very bright highlights;
  - 25% dark desaturation;
  - vibrance plus about +7% saturation.
- Offer both curves as a "Film (AgX)" vs "Vivid (Lottes)" setting.
- In XDR mode (M3), map the shoulder to EDR headroom so the sun and emissives really glow.

**Lens effects (M3, final pass, about 0).** Analytic ghost flare (ALU + 8 depth probes), vignette that spares bright pixels, CA, underwater wobble, final dither. Optional DOF (half-res gather, 0.2–0.4 ms est.).

**Nether/End (M3).**
- Nether: biome palette ambient, lava under-glow, view limit fog, bloom fog ×high. The Nether storm (64 steps × 8 fetches full res in Complementary) becomes ours at quarter res with a 3D noise texture, ≤ 16 steps + temporal (est. 0.2–0.4 ms).
- End: VL always on with procedural beam curtains. Reuse the VL pass with a beam density function (half res, est. 0.3–0.5 ms). End flashes from the 26.x uniform.

**Weather (M2/M3).** Rain particles lit by the scene light (vanilla `weather` pipeline override). Rain is "reactive" for TAAU (reactive mask) or composited after TAAU at output resolution. Wetness and puddles: above. Biome weather fog colors from the color-state block.

### 10.3 Frame budget with the Complementary feature set (120 Hz target = 8.33 ms)

Output 3024x1964. Measured items come from apple-gpu §7; the rest are estimates.

| Pass | Res | 0.5x | 0.67x |
|---|---|---|---|
| Shadow cascades (M1, sun-facing, cached far) | 2048² atlas | 0.7–1.0 | 0.7–1.0 |
| Translucent caster tint map (M3) | near cascade | 0.1–0.2 | 0.1–0.2 |
| Terrain + entities forward/TBDR lighting (shadow taps, IPBR-like materials, fog, waving) | internal | 1.6–2.4 | 1.8–2.6 |
| Sky + stars + aurora (sky pixels) | internal | 0.05–0.1 | 0.05–0.1 |
| SSAO 8 samples | ½ internal | 0.08 | 0.16 |
| Water/glass forward + refraction + tint buffer | internal | 0.3–0.5 | 0.4–0.6 |
| Water SSR (or RT in M4) | ½ internal on water | 0.2–0.4 | 0.3–0.5 |
| Clouds (Unbound-style) + reprojection | ¼–½ internal | 0.3–0.5 | 0.4–0.7 |
| VL 12–16 taps + temporal | ½ internal | 0.3 | 0.47 |
| Atmosphere composite (fog for translucents, VL/cloud upsample, bloom-fog, underwater) | internal | 0.15–0.25 | 0.25–0.4 |
| Colored light flood fill (option) | 3D | 0.1–0.2 | 0.1–0.2 |
| TAAU → output | output | 0.62 | 0.72 |
| Bloom pyramid | output pyramid | 0.5–0.74 | 0.5–0.74 |
| Final (bloom mix, tonemap, grade, flare, vignette, sharpen, dither) | output | 0.35 | 0.35 |
| GUI + present | output | 0.2–0.4 | 0.2–0.4 |
| **Total** | | **≈ 5.6–8.0 ms** | **≈ 6.5–9.4 ms** |

So a "Complementary-class" feature set fits 120 Hz at 0.5–0.6x internal with dynamic resolution, and about 90–120 Hz at 0.67x. Per apple-gpu, MetalFX instead of TAAU adds about 4.5–6 ms, so keep it for a 60 fps "Ultra" tier. That tier would also add PBR block reflections, RTAO and soft RT shadows.

### 10.4 Suggested mapping onto milestones

- **M1 (in progress, don't touch)**: the color-state block, sky gradient/grade, quadrature lighting, wrap/SSS foliage terms, shadow details 1–7 from §10.2, analytic fog with altitude band and border fog. All are ALU-only additions to what M1 already builds.
- **M2**: atlas material mask + sprite rect, waving, emissives, water (normals, foam, depth clarity, SSR, refraction, glitter), translucent tint, VL with scene-aware factor, bloom + bloom fog, Lottes/AgX final with dark lift / path-to-white / dark desaturation / vibrance, TAAU, SSAO.
- **M3**: TBDR deferred, clouds (both styles as an art preset), colored translucent shadows + caustics, aurora/rainbow/nebula, Nether storm/End beams, puddles, colored flood-fill light, XDR, lens effects.
- **M4**: RT shadows, RT water and glossy block reflections (replaces Complementary's SSR + WSR), RTAO.

---

## 11. Open questions / risks

1. **AO separation from Sodium colors.** Complementary's lighting relies on raw vertex AO in its own channel. We need a Sodium mixin, or a reliable decode, to get AO back out of the baked color (critique M1.3 already turns face shade off).
2. **Biome scalars.** `inDry`/`inRainy`/`inSnowy` come from the biome precipitation at the camera, smoothed over about 10–20 s. Make sure the 26.3 client exposes the biome at the player (it does through `ClientLevel`) and smooth it on the CPU.
3. **Vanilla `skyColor` dependency.** Complementary's palette is a function of the vanilla sky color, which is how biome skies, weather and custom dimensions propagate. If our M1 sky is physical, decide whether to keep the vanilla sky color as a tint input. That keeps biome or modded sky colors working.
4. **Art tuning time.** Most of Complementary's quality is many small hand-tuned constants. Our versions need a tuning harness: the fixed bench route with screenshots at day, sunset, night, rain, cave and underwater (critique M0), and side-by-side captures with the user's Complementary install for reference (reference screenshots only, no code).
5. **TAA on HDR vs LDR.** Complementary does TAA after tonemapping. Our TAAU runs pre-tonemap for upscaling. Verify firefly behaviour on water glitter and emissives, and use a luma-weighted (Karis) resolve.
