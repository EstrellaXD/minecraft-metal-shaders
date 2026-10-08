# Dynamic lights (M3, branch `m3-lights`)

Gael's request: "si voy caminando con una antorcha, que vaya iluminando en tiempo real, aunque la tenga en la mano".
What moves and glows now lights its surroundings in real time, in every shaded path, behind `mcopt.shade.dynLights`
(default on with `mcopt.shade=true`; `false` = M2.1's shaders byte for byte, no bindings, no mixins).

## Status (2026-10-07 19:30)

- **Done.** Module complete and measured: light list (both hands, mobs and players holding lights, dropped items, burning
  entities, blazes, magma cubes, glow squid, projectiles, TNT, minecarts, fireworks, lightning), visibility slabs, the
  irradiance volume, the entity vertex patch, the lit first-person hand. Build + `tools/shade/validate.sh` ALL OK at HEAD.
- This resume (evening of 2026-10-07): the WIP commit only held doc edits (kept). Added selection hysteresis (lights near
  the 64-light / 16-slab cuts no longer flip in and out; a light left out fades in again instead of popping), renamed the
  module's include guard (it collided with the m3 seam file's), filled section 7 (results: **~0.2 ms GPU**, CPU
  0.01-0.06 ms per frame) and wrote section 9 (how to plug into the m3 seam).
- Screenshots: `work/release/M3-lights-screenshots/` (`on_*` this module, `off_*` the same poses without it).
- Next (integration workflow, not this branch): follow section 9 on `m3`; then re-check the hand in game (m3 lights it in
  its own pass) and measure clean whole-frame numbers there.

## 1. What gives light

| Source | Light | Position |
|---|---|---|
| The local player's main and off hand | the item's light level and our colour (table below) | first person: in front of the eye, low, on the arm's side (`0.45` forward, `0.42` sideways, `0.32` down); third person: at the model's hand. Pulled back toward the eye (mobs: toward the body) when that point is inside an opaque block or behind one, so hugging a wall never lights the room behind it |
| Other players and any mob holding a luminous item (both hands) | same | at the model's hands (body yaw, main arm, width and height) |
| Dropped luminous items, items in item frames | same (frames 85 %) | the item |
| Burning entities (`displayFireAnimation`: on fire or visual fire) | fire, level 15 (12 when small), flicker | the middle of its box |
| Blaze | 12, yellow-orange, flicker | body |
| Magma cube | 6 + 2 x size (max 13), orange | body |
| Glow squid (not while dark after a hit) | 7, teal, dim | body |
| Spectral arrow / fire arrow | 8 pale yellow / fire 12-15 | the arrow |
| Small fireball / large fireball / dragon fireball | 11 / 14 fire / 12 violet | the projectile |
| Primed TNT | 11, white, pulsing with vanilla's white flash | the block |
| Furnace minecart with fuel (any minecart whose display block emits) | the block's emission, fire | the cart |
| Firework rocket, lightning bolt, end crystal | 13 warm white / 15 x3 blue-white / 10 pink-violet | |

Item light (`DynLightTable`): a level like vanilla's block emission, so a carried torch reaches as far as a placed one
(torch 14, lantern 15, soul torch/lantern 10, campfire 15, glowstone 15, sea lantern 15, shroomlight 15, jack o'lantern 15,
lava bucket 15, glow berries 12, end rod 14, redstone torch 7, froglights 15, light block 15, copper torch/lantern 14,
blaze rod 10, ...). Any other block item with light emission uses its emission, coloured by its name. Colours are tints of
the art layer's block-light colour (`ShadeFrame.blockLight`, ~2700 K), so a held torch is exactly a placed torch's colour;
soul fire is cyan, redstone red, sea lantern and beacon cool white, lava deep orange, froglights ochre / green / pink.

## 2. Frame flow

```
LevelRenderer.render HEAD (ShadeFrameUpdater, after the globals and the sky LUT)
  DynLights.frame
    Occupancy.update ........ CPU: block occupancy window (8x8x8 sections around the camera) within 300 us
    gather .................. CPU: hands + entities within 48 blocks (entitiesForRendering), keyed by entity id + kind
    fade / flicker / pick ... in 0.06 s, out 0.18 s; fire +-7.5 %; the 64 strongest (hands first), with hysteresis: a
                              light already in the list, or holding a slab, wins a near tie (x1.15 each), and one left
                              out past the cap fades in again when it comes back (no pop, no flip-flop at the cuts)
    visibility slabs ........ the 16 strongest within 40 blocks keep a 32^3 slab; recomputed only when the light moved,
                              its reach changed or the occupancy changed (static mobs / items cost nothing after the first frame)
    pack .................... lights + light grid into this frame's ring slot (buffer 24)
    mc_dl_vis ............... GPU compute, before the world passes: one thread per voxel of each slab to recompute
    mc_dl_irr ............... GPU compute: the irradiance volume, 80 x 48 x 80 blocks around the camera, every light of
                              the voxel's grid cell (visibility read from its slab) -> rgb light + mean light direction;
                              dispatched only over the box the lights reach, united with last frame's box (which it clears)
  world passes .............. Sodium terrain (plain and pulled share the fragment), glass, the water body:
                              shadeDynamicLightsVol (2 trilinear fetches, any number of lights)
                              water surface seen from above: shadeDynamicLightsSpec (per light: diffuse + glint)
                              game entity / item / block entity / particle vertex stages (HDR variants): shadeDynLightmap
render3dHud ................. the first-person hand: its packed light raised by DynLights.levelAt (CPU)
```

## 3. Shading model (`resources/mcopt/shade/dynlight.metal`)

- A light of level l acts at distance d like block light level `l - d` (euclidean; vanilla floods taxicab), through the
  terrain's own block-light curve `F(b) = (2.2 b^7 + 0.55 b^2.4) / 2.75` (shadeLit2), times `ShadeFrame.ambientBlock`
  (the art layer's torch colour) and the light's tint: a carried torch lights exactly like a placed one at the same
  distance. Within the first 0.75 block the level is capped (no singular spot under the hand).
- Wrapped N.L: `saturate((N.L + 0.3) / 1.3)` (two-sided plants: `0.55 + 0.45 |N.L|`).
- Where vanilla block light already lights a surface brightly, a carried light adds less: `x (1 - 0.5 F(block))` (bible
  E09 step 6).
- **Occlusion**: the visibility slab, sampled trilinearly half a block in front of the surface (the air the face looks
  into): 1 where the light's centre sees that voxel's centre through air. Light stops at walls, floors and ceilings with
  block-sized soft edges; it goes through glass, leaves and water (only `BlockState.canOcclude` blocks stop it).
  Lights without a slab (17th and later, beyond 40 blocks, lightning) are unoccluded.
- **Cost control: the irradiance volume.** Per-pixel loops over the lights (first version) cost ~1 ms at balanced with the
  hands + 9 lights, because grass and leaves overdraw and every fragment looped over every nearby light. Now a compute
  pass evaluates every light once per block voxel (80 x 48 x 80 around the camera, RGBA16F rgb light + RGBA16F mean
  direction weighted by each light's luminance) and the terrain fragment does two trilinear fetches half a block in front of
  its face, with the wrapped N.L taken against the mean direction (its length < 1 where lights come from several sides:
  the term blends toward 0.6). Same model as the per-light loop for one light; cost independent of the light count. The
  light grid (16 x 12 x 16 cells of 8 blocks, a 64-bit mask per cell, sphere-tested) keeps the compute, the water glint and
  the entity vertices to the lights that can reach them. Lights stop 2 blocks short of their reach (level < 2, < 0.2 % of
  the core), faded over the last block.
- **Water**: the water body takes the diffuse term (into `ShadeWaterIn.lit`), and the surface seen from above gets a
  Blinn-Phong glint per light (exponent 160, Fresnel-weighted, normal tilted by the fine ripples): torchlight sparkles on
  the water while walking along a shore. Glass and ice take the diffuse term like any texel.
- **Outside the overworld** (vanilla-lit colours in the terrain shader): the colour is raised toward the dynamic level
  (`sqrt` of the response as a display value, warm like the lightmap), never darkened.
- **Entities, items, block entities, particles, text** (`DynLightMsl.patch`, the HDR world variants only): every
  `sample_lightmap(Sampler2, Sampler2Smplr, uv)` in the game's vertex stage becomes
  `shadeDynLightmap(lm, uv, in.Position, in.Normal or 0, ...)`: per vertex, per light, the lightmap sampled at the
  dynamic level, the excess over the vertex's own lightmap colour added, tinted, with a soft N.L and the same visibility.
  It is the vanilla look of a mob standing in torchlight, coloured and directional, never doubled where placed torches
  already light it. Skipped: terrain, OIT, weather, wireframe, GUI and lightning pipelines; the hand and the GUI keep the
  game's stages (they are not drawn into the HDR world target).
- **The first-person hand** (and the local player's body): `DynLightEntityRendererMixin` raises the packed light that
  `EntityRenderer.getPackedLightCoords` returns for the local player to `DynLights.levelAt` at its light probe (the
  strongest unoccluded light, CPU walk through the occupancy), so the hand holding a torch in a dark cave is lit.

## 4. Bindings and data (document of record: `dynlight.metal` header)

| Slot | Content | Writer |
|---|---|---|
| `buffer(24)` (vertex + fragment) | `ShadeDynLights`, 27712 B: header (count, volumes, frame; grid origin and 1/cell; slabs, strength, specular, entity strength; irradiance volume origin), 64 lights x 48 B (pos + reach, tint x intensity + specular, slab origin + slab index), 3072 cells x `uint2` | CPU, ring of 4 shared buffers (`mcdynlight.m`) |
| `texture(23)` (vertex + fragment) | visibility, `texture3d<half>` R8Unorm 32 x 32 x (32 x volumes) | `shade_dl_vis` compute (`dynlight_vis.metal`) |
| `texture(24)`, `texture(25)` (fragment) | irradiance volume: rgb light, mean light direction (`texture3d<half>` RGBA16F, 80 x 48 x 80, one voxel per block; origin in `ShadeDynLights.irr`) | `shade_dl_irr` compute (`dynlight.metal`, `SHADE_DL_KERNELS`) |
| compute only | block occupancy, 256 KB shared buffer, 1 bit per block, toroidal 8^3 sections | CPU (`Occupancy`) |

Both slots are free in every pipeline that binds the shade globals: vanilla's uniforms and samplers count from 0, Sodium
uses 20-22, 26 and vertex buffers 27-30, water 18-23; mcopt's own far terrain (mcopt.lod) uses 22-30 but rebinds shade
after drawing. `mc_dl_bind` runs inside `ShadeBindings.bind/rebind` (every encoder the backend opens). The buffers exist from
`ShadeBindings.init` (before any shaded draw), zeroed (no lights).

Occupancy updates: `ClientLevel.sendBlockUpdated` sets one bit at once (dig a wall: light passes through the next
frame), `setSectionDirtyWithNeighbors` / `setSectionRangeDirty` queue a refill (`DynLightClientLevelMixin`), the window
follows the camera section by section, the nearest dirty sections fill first within the CPU budget, and one slot per
frame is re-read anyway (self-healing).

## 5. Flags (`DynLightConfig`)

| Flag | Default | |
|---|---|---|
| `mcopt.shade.dynLights` | true | the module (false: no define, no bindings, no mixins) |
| `.max` | 64 | lights per frame |
| `.range` | 48 | gather radius (blocks, horizontal; 40 vertical) |
| `.volumes` | preset 8 / **16** / 24 / 32 | lights with a visibility slab |
| `.occlusion` | true | slabs + occupancy (false: no occlusion at all) |
| `.occBudgetUs` | 300 | CPU per frame for the occupancy |
| `.strength` / `.spec` / `.entity` | 1 / 1 / 1 | overall, water glint, entity strength |
| `.entities` | true | the game's vertex stages get the lights |
| `.hand` | true | local player's hands as lights + its packed light |
| `.others` | true | every other source |
| `.flicker` | true | fire flicker |
| `.log` | false | a line every 2 s: lights, slabs, computed slabs, CPU ms, occupancy |

## 6. Tests

`mcbench` (`work/mcbench/LightShots.java`): `-Dmcbench.dynLights=true` or a list of `field,mobs,perf,cave,walk,water,nether`
(see HARNESS.md). Perf phase `lights` = the mobs scene (hands + 9 lights) panning: compare `perf.lights / gpu.lights`
against `-Dmcopt.shade.dynLights=false`. Validation: `tools/shade/validate.sh` (terrain variants with the module,
`dynlight_vis.metal`, and with `MCOPT_GEN_DIR` every game vertex stage patched by `DynLightMsl.patch`).

## 7. Results

All at full Retina (3024 x 1964), preset balanced, DRS off (`-Dmcopt.shade.drs=false`), night, a torch in the main hand
and a soul lantern in the off hand. Screenshots: `work/release/M3-lights-screenshots/` (`on_*` = this module, `off_*` = the
same poses with `-Dmcopt.shade.dynLights=false`, i.e. M2.1's look).

### 7.1 Cost (in-session A/B: the `lights` / `lights_off` / `lights2` phases)

The mobs scene (hands + 9 lights: 3 mobs holding lights, a burning zombie, a magma cube, a blaze, 3 dropped luminous
items) panning +-15 degrees, 10 s per phase. In `lights_off` the module is paused at run time (no lights: every shader
leaves at its first test), so the difference is the module's whole GPU cost in one session, immune to launch-to-launch
drift. GPU ms per frame:

| Run (build) | Power | CPU during the phases | lights | lights_off | lights2 | Cost (mean of lights, lights2 - off) |
|---|---|---|---|---|---|---|
| `20261007-143027-dl-t6-on` (`1e7f6c2`) | battery | clean (java 72 %, total < 350 %) | 8.142 | 8.047 | 8.188 | **+0.12 ms** |
| `20261007-145128-dl-t6-on2` (`1e7f6c2`) | AC | **contaminated** (Python 87 %, Safari) | 8.455 | 8.301 | 8.510 | +0.18 ms |
| `20261007-181411-dl-r1-on` (`1e7f6c2`) | AC | clean (total 105-244 %) | 8.131 | 7.979 | 8.144 | **+0.16 ms** |
| `20261007-182737-dl-r2-on` (`731a503`, + hysteresis) | AC | **contaminated in `lights_off`** (Chrome 114 % + Python 95 % at its end, total 384 %) | 8.261 | 8.321 | 8.291 | (-0.05: the slowed `off` phase hides the cost; not used) |
| `20261007-185356-dl-r3-on` (`8aa4a96`, 12 s phases) | AC | clean in `lights` / `lights_off` (98-160 %); `lights2` ends at 376 % and drifts up | 8.385 | 8.071 | 8.787 | +0.31 (lights vs off; lights2 not trusted) |
| `20261007-192454-dl-r4-on` (`8aa4a96`, 12 s phases, perf only) | AC | clean (112-151 %) | 8.166 | 7.906 | 8.069 | **+0.21 ms** |

Control: with the module off (`20261007-144310-dl-t6-off`) the same three phases read 7.722 / 7.767 / 7.772 ms: the
phase-to-phase noise is ~0.05 ms. **Price: ~0.2 ms GPU for the hands + 9 lights (budget 0.4 ms)**: +0.12, +0.16 and +0.21 ms in the three clean runs, +0.31
in r3, whose phases drifted (its `lights2` alone is 0.4 ms above `lights`). History of the
same measurement: the first version (per-pixel loop over the lights) cost **+1.06 ms** (`20261007-123517-dl-t3` and
`-123726-dl-t4`, AC: 8.84 / 7.81 / 8.91 and 8.84 / 7.85 / 8.94); the irradiance volume computed over the whole 80 x 48 x 80
blocks every frame **+0.27 ms** (`20261007-132040-dl-t5`, battery: 8.94 / 8.69 / 8.97); computed only over the box the
lights reach (current) **~0.15 ms**.

CPU (the render thread, `-Dmcopt.shade.dynLights.log=true`, one line every 2 s, r1): occupancy + gather + packing
**0.01-0.06 ms per frame** with 2-11 lights (median ~0.03 ms); 0.3 ms (the occupancy budget, 300 us) in the frames where
the block occupancy fills after a teleport or a dimension change (205 sections in one frame in the Nether), ~0.9 ms once
at world load. The irradiance compute covers 22 000 voxels (hands only) to 40 000 (mobs scene) instead of the full 307 200.
Visibility slabs are recomputed only when a light moves more than 0.04 block (walking: every second or third frame
for the hand lights), so static mobs and items cost nothing after their first frame.

Whole-frame numbers across launches (spin / flight of the same runs) are not comparable today: r1 (AC, clean) read
11.8 / 11.2 ms GPU against 9.7 / 9.1 in t6-on (battery) for the same build, a launch-to-launch swing far larger than the
module; clean whole-frame numbers come after integration.

### 7.2 What it looks like (pairs in `M3-lights-screenshots/`)

- `dl_field_hand` (open field at night): with the module the ground lights up around the player, warm, fading smoothly
  to dark ~10 blocks away like a placed torch; grass blades facing the hand catch more. No block-grid steps on the
  ground (the volume is interpolated between block centres and sampled half a block in front of each face). Without
  it the field is moonlit only.
- `dl_field_mobs`: the villager (lantern), the zombie (torch), the skeleton (soul lantern: whiter-cyan) and the burning
  zombie light themselves and the grass at their feet; the dropped torch, glowstone and sea lantern too. Without the module
  the same mobs are dark silhouettes. The skeleton is very bright (white texture at full light level 10 from a lantern in
  its own hand): it is how vanilla lights a skeleton next to a lantern.
- `dl_cave_hand` / `dl_cave_walk` (a dark cave): the floor and the walls within ~8 blocks are lit in front of the player
  with soft falloff; walls facing away stay dark; nothing appears behind the walls. Without the module the cave is black and the
  held items are black too; with it the held torch and soul lantern are lit.
- `dl_water` (shore): a bright glint of the torch on the ripples next to the shore and the sand lit. 
- `dl_nether`: blazes lit (they glow and light themselves), the basalt around the player raised toward the torch light.
- Soul light: cyan where it is the only source (the skeleton); next to a torch it is mostly hidden, because a level-14
  torch is ~20x brighter than a level-10 soul lantern at the same distance (vanilla's light curve).

## 8. Known limits

- Entities are lit per vertex (smooth over a mob's few quads; a torch right next to a big flat face shows no hot spot).
- The local player's own hand takes the warm vanilla lightmap at the dynamic level (not the soul lantern's cyan). On m3
  this goes away: m3 shades the hand in the fragment through the seam with the light's own colour (section 9.1 item 6).
- Next to a torch a soul lantern hardly shows (a level-14 torch is ~20x brighter at the same distance on vanilla's
  curve); on its own it is clearly cyan.
- The irradiance volume is one value per block, linearly interpolated: smooth (no steps), but a light within ~1 block
  of a face lights it a little flatter than the exact per-pixel model would (no hot spot right under a torch held
  against a wall).
- No occlusion for lights past the 16th or beyond 40 blocks; occupancy is per block (slabs and stairs block fully,
  fences and panes not at all).
- Without the HDR target (`mcopt.shade.hdr=false`) entities don't take the lights (no HDR variant to patch).
- Distant Horizons LODs don't take the lights (drawn by DH's own shaders; far away anyway).

## 9. Integration with the m3 base

What m3 has (`wt/m3/BASE.md` sections 2-3): `shade_dynlight.metal` (the seam, last file of `ShadeMsl.prelude()`) with
`shadeDynamicLights(ShadeDynIn d, constant ShadeFrame &f, constant ShadeLook &L, constant ShadeResources *R)` returning
radiance to add; called by the Sodium terrain (`SHADE_DYN_TERRAIN`, with `R = &sr` under `MCOPT_HDR`, `nullptr` otherwise),
the water surface (`SHADE_DYN_WATER`, n = up, albedo 1, added to `wi.lit`) and `shade_entity.metal`
(`SHADE_DYN_ENTITY` / `_HAND` / `_PARTICLE`) for every pipeline of `ShadeEntity.PIPELINES`, in the fragment.

### 9.1 How this module plugs into the seam

1. **Prelude order.** `ShadeMsl.prelude()` = shade_look, shade_common, shade_m2, then `DynLightMsl.prelude()`
   (`#define SHADE_DYNLIGHT` + `dynlight.metal`, empty when off), then m3's `shade_dynlight.metal`. The two files have
   different include guards (`MCOPT_SHADE_DYNLIGHT_MODULE` here, renamed in `8aa4a96`, vs the seam's `MCOPT_SHADE_DYNLIGHT`);
   with the old shared name one of them would have been silently dropped.
2. **Reaching the module's data from the seam (recommended: through `ShadeResources`, keeps the seam's signature).**
   The seam only gets `f`, `L` and `R`, so the light list and the volumes must be reachable through `R` (buffer 19, a
   Tier-2 argument buffer of 8-byte resource ids written by `mc_shade_resource`, made resident by `mc_shade_bind` with
   `useResource`):
   - `shade_look.metal` `ShadeResources`: `reserved4` -> `texture3d<half> dlVis` (the R8 visibility slabs, today
     texture 23), `reserved5` -> `texture3d<half> dlIrr`, `reserved6` -> `texture3d<half> dlDir` (the irradiance volume,
     today textures 24 / 25), `reserved7` -> `constant ShadeDynLights *dl` (this frame's ring slot of the light buffer).
   - Java/native: after `DynLights.init`, `ShadeRender.resource(4..6, texture)` once (the textures are not ringed: the
     compute rewrites them at the start of each frame, before the world passes). The light buffer is a ring of 4 shared
     buffers: each frame, after `mc_shade_resources_advance`, write its GPU address
     (`buffer.gpuAddress + offset`) into slot 7 with a new 3-line `mc_shade_resource_address(int index, id<MTLBuffer>, uint64_t
     offset)` that also keeps the buffer for `useResource` (or keep `mc_dl_bind` binding it at buffer 24, which already
     makes it resident in every encoder). The dummy texture is 2D: with the module off the seam is the stub and never
     reads these slots; with it on, all four are set before the first shaded draw (`ShadeBindings.init`).
   - Seam body:
     ```metal
     static inline float3 shadeDynamicLights(ShadeDynIn d, constant ShadeFrame &f, constant ShadeLook &L, constant ShadeResources *R) {
     #ifdef SHADE_DYNLIGHT
         if (R == nullptr) return float3(0.0);           // M1 / LDR terrain: no lights (as today without HDR)
         constant ShadeDynLights &D = *R->dl;
         if (D.info.x == 0u) return float3(0.0);          // no lights (or paused): one uniform branch
         float3 k = d.albedo * f.ambientBlock.xyz;        // the units of shadeLit2's torch term
         bool thin = d.kind == SHADE_DYN_PARTICLE;        // (terrain: pass `thin` through a ShadeDynIn field added at the end)
         if (d.kind == SHADE_DYN_HAND)                    // near field: the exact per-light loop with visibility (2-5 lights)
             return k * shadeDynamicLights(D, R->dlVis, d.p, d.n, false, d.block);
         return k * shadeDynamicLightsVol(D, R->dlIrr, R->dlDir, d.p, d.n, thin, d.block); // terrain, entities, particles, water body
     #else
         return float3(0.0);
     #endif
     }
     ```
     Terrain needs one more input: two-sided plants use a different N.L (`thin`); add `bool thin` at the end of
     `ShadeDynIn` (default false in `shadeDynIn()`), set it at the terrain call site.
   - Alternative without touching `ShadeResources`: keep the direct bindings (below) and give `shadeEntityShade` four
     more parameters that `ShadeEntity`'s fragment patch appends (`constant ShadeDynLights& mcoptDL [[buffer(24)]],
     texture3d<half> mcoptDLVis [[texture(23)]], mcoptDLIrr [[texture(24)]], mcoptDLDir [[texture(25)]]`; free in those
     stages: they use buffers 16-19 and textures 16-17); terrain keeps this branch's own call sites. This changes the seam's
     signature, so it is the second choice.
3. **Terrain.** Replace m3's terrain seam call with the seam above (or keep this branch's
   `radiance += albedo * ambientBlock * shadeDynamicLightsVol(...)`: identical result). Keep this branch's two other
   terrain additions, which m3 has no seam for: the **cutout-water branch** (`ALPHA_CUTOUT` `water`: `lit +=` the volume)
   and the **Nether / End branch** (vanilla-lit colour raised toward the dynamic level before the early return).
4. **Water and the per-light glint.** The seam returns one colour, but the water needs two: diffuse light on the body
   (into `wi.lit`) and the glint (added to `radiance` after `shadeWaterSurface`, Fresnel-weighted, normal tilted by the fine
   ripples `wi.detail`). So at the water call site keep this branch's block instead of the seam call: for the surface seen
   from above `wi.lit += ambientBlock * shadeDynamicLightsSpec(D, vis, p, dlN, block, V, 160, dlSpec)` and
   `radiance += dlSpec * ambientBlock * (0.25 + 3 fresnel)` after `shadeWaterSurface`; other water faces take the volume.
   With the `R` route, `dl` / `dlVis` / `dlIrr` / `dlDir` there are `*sr.dl`, `sr.dlVis`, ... (the water pass has `sr`).
5. **Entities, items, block entities, particles (m3's ShadeEntity) and the vertex patches.**
   m3 replaces those fragments' colour with `shadeEntityShade` (our lighting from `UV2 / 240`, not the vanilla lightmap
   colour), so in the **overworld** this branch's `DynLightMsl` vertex patch is **redundant** on every pipeline of
   `ShadeEntity.PIPELINES` (entity_solid / _offset_forward, entity_cutout / _cull / _z_offset / _dissolve,
   entity_translucent / _cull, armor_cutout_no_cull, armor_decal_cutout_no_cull, armor_translucent, banner_pattern,
   item_cutout, item_translucent, solid_block, cutout_block, translucent_block, opaque_particle, translucent_particle): the
   raised lightmap colour it computes is overwritten. Two choices:
   - simplest (recommended for the merge): keep applying both patches (they are textually independent: `DynLightMsl`
     rewrites `sample_lightmap(...)` calls and appends two entry parameters; `ShadeEntity` adds outputs at the projection
     line). The cost in the overworld is a per-vertex loop over the few lights near the entity (negligible next to the
     fragment), and **outside the overworld**, where `shadeEntityShade` keeps the game's colour (as terrain does), that
     colour is exactly the raised lightmap: mobs in the Nether and the End are still lit by blazes and held torches;
   - leaner: in `ShadeMsl.FORMAT_ADAPTER.vertex` run `DynLightMsl.patchVertex` only when `ShadeEntity` did not patch the
     stage, and add a dynamic raise in `shadeEntityShade`'s non-overworld branch (as the terrain's Nether branch does).
   Still needed in both cases, for the pipelines m3 leaves unpatched that sample the lightmap (from the 26.3 MSL dump):
   `text`, `text_grayscale`, `text_polygon_offset`, `text_grayscale_polygon_offset` (signs, name tags), `leash`,
   `breeze_wind`, `end_crystal_beam`, and the glint pipelines `entity_solid_glint`, `armor_cutout_no_cull_glint`,
   `item_translucent_glint`. Emissive pipelines (eyes, energy swirl), lines, beacon and end portal don't sample the
   lightmap and are skipped automatically (the patch only touches stages with `sample_lightmap`). Terrain, OIT, weather,
   wireframe, GUI and lightning stay excluded by name (`DynLightMsl.wants`).
6. **The hand.** m3 lights the first-person hand in its own tagged pass (`ShadeEntity.HAND_FORMAT`, `MCOPT_HAND`,
   `SHADE_DYN_HAND`): the seam lights it in the fragment (per-light loop above: the hand's own torch at the 0.75-block cap,
   so it is lit with the light's colour, soul lantern cyan included, which this branch could not do). This branch's
   `DynLightEntityRendererMixin` (raises the local player's packed light to `DynLights.levelAt`) must then be **off in
   the overworld when `ShadeEntity.HAND` / `ShadeEntity.ON`**: m3 reads that packed light as block light (`UV2 / 240`) and
   `shadeLit2` would light the hand and the third-person body a second time (warm, uncoloured). Keep it outside the
   overworld (m3 keeps the game's colour there, so the raised packed light is what lights the hand) and with
   `mcopt.shade.handLight=false`. Check in game that the hand's `p` in `MCOPT_HAND` is camera-relative and world-oriented
   (the hand's pose stack has no view rotation); if it is view-space, rotate it (and `n`) by the inverse view rotation
   before the lookup, or the hand would sample the light list in the wrong place.
7. **Bindings side by side.**

   | Slot | m3 base | this module |
   |---|---|---|
   | buffer 16-19 | ShadeFrame, ShadeLook, ShadeGpu, ShadeResources (19, now bound in all terrain passes) | — |
   | buffer 23 | water params (`ShadeWaterParams`) | — |
   | buffer 24 | free | `ShadeDynLights` (vertex + fragment) |
   | texture 16 / 17 | shadow map / sky LUT | — |
   | texture 18-22 | water (scene copy, depth copy, waves, coarse, detail) | — |
   | texture 23 | free | visibility slabs (vertex + fragment) |
   | texture 24 / 25 | free | irradiance rgb / mean direction (fragment) |
   | ShadeResources 4-7 | reserved (free) | with the `R` route: dlVis, dlIrr, dlDir, dl |
   | ShadeLook 608 (`heldLight`) | reserved for the held light | not used (the hand is a light in the list) |

   No overlap: entity fragments patched by m3 use buffers 16-19, textures 16-17 and samplers 12-13; the vertex patch here
   uses buffer 24 and texture 23 in the vertex stage. `mc_dl_bind` already runs from `ShadeBindings.bind` / `rebind`
   (every encoder the backend opens, the hand's redirected pass included) and the compute (`mc_dl_vis`, `mc_dl_irr`) from
   `ShadeFrameUpdater` before the world passes, so nothing new is needed for the bindings on m3. Merge conflicts to expect:
   `sodium_terrain.fs.metal` (both add code after `shadeLit2` and in the water block), `ShadeMsl.prelude()` (one more
   file each), `ShadeMsl.FORMAT_ADAPTER.vertex` (both patch the HDR variants), `ShadeBindings` (two one-line hooks).
