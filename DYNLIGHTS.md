# Dynamic lights (M3, branch `m3-lights`)

Gael's request: "si voy caminando con una antorcha, que vaya iluminando en tiempo real, aunque la tenga en la mano".
What moves and glows now lights its surroundings in real time, in every shaded path, behind `mcopt.shade.dynLights`
(default on with `mcopt.shade=true`; `false` = M2.1's shaders byte for byte, no bindings, no mixins).

## 1. What gives light

| Source | Light | Position |
|---|---|---|
| The local player's main and off hand | the item's light level and our colour (table below) | first person: in front of the eye, low, on the arm's side (`0.45` forward, `0.42` sideways, `0.32` down); third person: at the model's hand |
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
    fade / flicker / pick ... in 0.06 s, out 0.18 s; fire +-7.5 %; the 64 strongest (hands first)
    visibility slabs ........ the 16 strongest within 40 blocks keep a 32^3 slab; recomputed only when the light moved,
                              its reach changed or the occupancy changed (static mobs / items cost nothing after the first frame)
    pack .................... lights + light grid into this frame's ring slot (buffer 24)
    mc_dl_vis ............... GPU compute, before the world passes: one thread per voxel of each slab to recompute
  world passes .............. Sodium terrain (plain and pulled share the fragment), water, glass: shadeDynamicLights
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
- **Cost control**: a light grid of 16 x 12 x 16 cells of 8 blocks around the camera, each cell a 64-bit mask of the
  lights whose sphere can reach it; a pixel loops only over its cell's bits, and a light out of reach exits before any
  texture fetch.
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
| `buffer(24)` (vertex + fragment) | `ShadeDynLights`, 27712 B: header (count, volumes, frame; grid origin and 1/cell; slabs, strength, specular, entity strength; hand light), 64 lights x 48 B (pos + reach, tint x intensity + specular, slab origin + slab index), 3072 cells x `uint2` | CPU, ring of 4 shared buffers (`mcdynlight.m`) |
| `texture(23)` (vertex + fragment) | visibility, `texture3d<half>` R8Unorm 32 x 32 x (32 x volumes) | `shade_dl_vis` compute (`dynlight_vis.metal`) |
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

(see section 8, filled after the in-game runs)

## 8. Known limits

- Entities are lit per vertex (smooth over a mob's few quads; a torch right next to a big flat face shows no hot spot).
- The local player's own hand takes the warm vanilla lightmap at the dynamic level (not the soul lantern's cyan).
- No occlusion for lights past the 16th or beyond 40 blocks; occupancy is per block (slabs and stairs block fully,
  fences and panes not at all).
- Without the HDR target (`mcopt.shade.hdr=false`) entities don't take the lights (no HDR variant to patch).
- Distant Horizons LODs don't take the lights (drawn by DH's own shaders; far away anyway).
