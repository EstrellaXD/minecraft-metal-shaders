# M3 base + entities (branch `m3`, the M3 integration branch)

Branch `m3` starts from `m2.1` (water M2.1) and is where the M3 modules (dynamic lights `m3-lights`, weather/clouds
`m3-weather`, sky/dimensions `m3-skydims`, ...) merge. Everything here is behind `mcopt.shade=true` and the HDR target; with
`mcopt.shade=false` the backend is upstream's (the two backend seams added, `MetalDrawTap` and redirect-format tags, are inert
without a listener / tagged redirect). Read `M2_INTEGRATION.md` first: this file only adds what M3 base changed.

## 1. What this branch adds

| Piece | Where | Flag (default) |
|---|---|---|
| Entities, block entities, dropped items, armour, moving blocks and particles lit by the shade model (bible E17) | `ShadeEntity.java`, `shade_entity.metal`, `ShadeMsl.FORMAT_ADAPTER` | `mcopt.shade.entities` (true) |
| First-person hand and held items lit the same way (tone-mapped in the fragment, no fog) | `ShadePost.handRedirect`, `ShadeEntity.HAND_FORMAT`, `MCOPT_HAND` in `shade_entity.metal` | `mcopt.shade.handLight` (true) |
| Entity sun shadows in the near cascade (bible E08 / M3c), vanilla blob shadow off | `MetalDrawTap`, `shadow/EntityCasters.java`, `mcshadow.m` `mc_shadow_entity_*`, `shadow.metal` `shadow_entity_vs/fs` | `mcopt.shade.entityShadows` (true), `.log` (false) |
| Per-object motion for TAAU (moving entities don't smear) (bible E18 step 4) | `taa/ShadeMotion.java`, `taa.metal` `TaaMovers`, `mctaa.m` `mc_taa_movers` | `mcopt.shade.taaMotion` (true); `mcopt.shade.taaDebug=5` shows it |
| **Dynamic-lights seam**: `shadeDynamicLights(ShadeDynIn, f, L, R)` called by terrain, water and entities (stub returns 0) | `shade_dynlight.metal` (in `ShadeMsl.prelude()`) | — (the module's) |
| Evening sky: rose / peach hue of the upper sky at golden hour (Complementary-like, our sun, rays and blue top kept) | `ShadeLook.SKY_ROSE` -> `skyGoldZ.w`, `sky_lut.metal` | look-scaled (epic 1, storybook 1.15, natural 0.6) |
| Underwater god rays: caustic curtains on the water's real-time clock (were on the game time, which the server corrects every second: the jumps smeared them through the 0.9 history), less history under water (0.8), stronger (HDR_WATER 0.45 -> 0.6, carve 0.55 -> 0.62) | `vl/ShadeVl.java` | `mcopt.shade.vl.underwater` (true) |
| Bench: entity scenes (`-Dmcbench.entities=true` or `perf,noon,evening,night,hand,motion`), zoo perf phase | `work/mcbench` `EntityShots.java` | see `work/HARNESS.md` 8f |

## 2. Dynamic lights: the seam (for `m3-lights`)

`shade_dynlight.metal` is prepended to every shaded stage after `shade_m2.metal`. **Replace the body of `shadeDynamicLights`**
(keep the signature and `ShadeDynIn`; add fields at the end of the struct if needed and default them in `shadeDynIn()`):

```metal
struct ShadeDynIn { float3 p; float3 n; float3 albedo; float2 pixel; half sky; half block; int kind; };
static inline float3 shadeDynamicLights(ShadeDynIn d, constant ShadeFrame &f, constant ShadeLook &L, constant ShadeResources *R);
```

- Returns **radiance to add** (linear, before exposure; same units as `shadeLit2`: E/pi x albedo). Callers do
  `radiance += shadeDynamicLights(...)` before fog and exposure.
- `p` camera-relative world position, `n` unit world normal (toward the camera for two-sided geometry), `albedo` linear,
  `pixel` fragment position, `sky`/`block` vanilla light levels 0..1, `kind` one of `SHADE_DYN_TERRAIN`, `_WATER` (albedo 1:
  return the light on the surface), `_ENTITY`, `_HAND`, `_PARTICLE`.
- `R`: the `ShadeResources` argument buffer (buffer 19) in HDR stages, `nullptr` in M1/LDR terrain. Free texture slots 4-7
  for a light list texture (`ShadeRender.resource(index, texture)`, ring-safe), or `ShadeLook.heldLight` (offset 608) for
  the held light. Test a light count first and return 0: it runs for every shaded fragment.
- Call sites today: `sodium_terrain.fs.metal` (terrain: after `shadeLit2`; water: added to `wi.lit`), `shade_entity.metal`
  (entities, hand, particles). Terrain now declares `constant ShadeResources& sr [[buffer(19)]]` under `MCOPT_HDR` for all
  passes (was water-only).

## 3. Entity lighting: how it works, how to extend

- The game's entity / item / particle / moving-block pipelines keep their own SPIRV-Cross MSL; only their **HDR variants**
  (`MetalPipeline.variant` through `ShadeMsl.FORMAT_ADAPTER`) are patched textually by `ShadeEntity`:
  vertex + 4 outputs at `locn12..15` (`mcoptPos` camera-relative = the expression the game projects, `mcoptNrm`, `mcoptColor`
  = raw vertex colour without vanilla's cardinal light, `mcoptLight` = UV2 / 240); fragment + shade globals and
  `[[position]]`/`[[front_facing]]`, and before its `return out;` the colour is replaced by `shadeEntityShade(...)`
  (alpha, cutout discard, dissolve stay the game's). The RGBA8 pipelines (GUI items, maps) are untouched.
- Patched set: `ShadeEntity.PIPELINES` (entity_solid/cutout*/translucent*, armor_*, banner_pattern, item_cutout /
  item_translucent, solid/cutout/translucent_block, opaque/translucent_particle). Not patched (M2 path, vanilla light
  converted to HDR): glint pipelines (`item_cutout` shares its name with its glint variants: logged once as "fragment
  shape"), emissive (eyes, energy swirl, entity_translucent_emissive), text, lines, leash, beacon, end portal/gateway.
- A fragment is patched only if its pipeline's vertex stage was (`VERTEX_PATCHED`), so the interfaces always match.
- Shading (`shade_entity.metal`): `shadeLit2` with `shadeShadow` (4-tap, cheaper than PCSS; skipped at sky light <= 0.3),
  + dynamic lights, `shadeFogAir2` + `shadeFinish2` like terrain. Particles at lightmap 15/15 (flames, sparks) glow.
  Outside the overworld the game's colour is kept (as terrain does). Debug views 1 (shadow), 2 (normals), 3 (grey albedo).
- Hand: `ShadePost` redirects the "Item in hand" pass (after the final pass) with format `RGBA8 | 1 << 16`
  (`MetalHooks.TAG_SHIFT`: a variant tag, the pipeline is built for the low bits); `MCOPT_HAND` then writes
  `sRGB(shadeDisplay(radiance))`, no fog.
- Validation: `MCOPT_GEN_DIR=<a -Dmcopt.metal.dumpMsl dir> tools/shade/validate.sh` patches the dumped stages and builds
  every pair as a real pipeline (19 world + 19 hand variants). Re-dump after a Minecraft / mcopt update.

## 4. Entity shadows: how it works

1. `MetalDrawTap.listener` (backend seam): every `drawIndexed` of a QUADS pipeline with a uv at location 2 and a
   `Sampler0`, in a format-redirected pass (the HDR world), reports its vertex MTLBuffer, first-vertex byte offset, quads,
   stride, uv offset and texture.
2. `EntityCasters` keeps the caster pipelines' draws (`CASTERS`, with the alpha cutoff) natively
   (`mc_shadow_entity_record`, buffer + texture retained).
3. `ShadePost.beforeHand` -> `EntityCasters.commit`: one blit copies the recorded vertex ranges into the shadow pass's
   12 MB private buffer (the game's `StagedVertexBuffer` pools are per frame).
4. Next frame, `mc_shadow_render` for cascade 0 draws them after the terrain (`shadow_entity_vs`: vertex pulling, quads
   from `vertex_id`, shifted by camera(then) - camera(now); `shadow_entity_fs`: alpha test). One frame of latency.
   Only the near cascade (±32 blocks, ahead of the view).
- The vanilla blob shadow (`entity_shadow`) is dropped in the HDR variant when entity shadows are on (as Iris does).
- `-Dmcopt.shade.entityShadows.log=true`: a line every 4 s with the casters drawn.

## 5. Per-object motion (TAAU)

`ShadeMotion.update` (from `ShadeTaa.camera`, GameRenderer.renderLevel) puts each entity within 64 blocks at its render
position (`lerp(partial tick, xOld, x)`, exactly what EntityRenderer draws); those that moved since last frame become movers:
bounding box at the render position padded 0.3 (camera-relative) + the change. `taa.metal` reprojects a pixel whose
reconstructed surface is inside a mover from `world - delta`. Max 32 movers, nearest first, 1.5 KB of bytes at buffer(2).
Translation only (limb swing and turning stay with the variance clip). `mctaa.m` keeps `mc_taa_resolve`'s signature
(headless `taatest` unchanged; movers default to none).

## 6. Merge notes for the M3 modules

- Expect conflicts in `sodium_terrain.fs.metal` (dynamic-light call sites, `sr` binding), `ShadeLook` (offsets: M3 base
  uses only `skyGoldZ.w`; `heldLight` 608, `wet` 496, `wind` 512, `clouds0-2` 528-560, `dimTint` 752, `flash` 768 are still
  free for the modules), `ShadeMsl.prelude()` (one more file), `vl/ShadeVl.java` (temporal param line).
- Entities already call `shadeDynamicLights` with `kind`; a lights module needs no change in `shade_entity.metal`.
- Weather/wetness: entities go through `shadeLit2`; a wetness term added there reaches them too.
- Nether/End: entities keep vanilla colours outside the overworld (like terrain); a dimension module that lights terrain
  there should do the same in `shadeEntityShade` (one branch at its top).
- Cloud shadows: entities use `shadeShadow`; multiply the cloud shadow where terrain does.

## 7. Costs and tests

See section 8 (filled from the runs below).
