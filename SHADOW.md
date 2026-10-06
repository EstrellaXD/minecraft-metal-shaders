# Sun shadow pass (M1, branch `m1-shadow`)

The native sun shadow map for the shading pipeline. Everything is gated behind `-Dmcopt.shade=true`; with the flag off,
none of the mixins apply and no shadow code runs.

## 1. What it does

Each frame, right before the level's frame graph runs, the shadow pass replays Sodium's solid and cutout terrain from
the light into a 2-slice depth array texture. The game's world passes ("Sky", "Main") come after it.

- **Cascade 0 (near):** half-width `mcopt.shade.shadowNear` (default `shadowDist / 4` = 32 blocks). Redrawn every frame.
  Its center sits 40% of its half-width ahead of the camera.
- **Cascade 1 (far):** half-width `mcopt.shade.shadowDist` (default 128 blocks), centered on the camera. It is redrawn
  when any of these happens:
  - every `mcopt.shade.shadowFarEvery` frames (default 4);
  - the quantized light direction changes (steps of `mcopt.shade.shadowSunStep`, default 0.1°);
  - the camera moves more than an eighth of the half-width.

  Between redraws, the far map and its world-anchored matrix are kept. The matrix is re-expressed against the moving
  camera every frame, in doubles. It is exact even at 29M blocks; `tools/shade/shadowtest` checks this.
- **Format:** Depth32Float, or Depth16Unorm with `mcopt.shade.shadowDepth=16`. Both have the same speed in the bench.
  - Resolution: `mcopt.shade.shadowRes`, default 2048 per cascade.
  - Memory: 2 × 16 MB at 2048 with Depth32Float.
- **Stable fitting:**
  - The cascade box is a fixed-size square in light space. Turning the camera never changes its size.
  - Its center is snapped to whole texels of a grid fixed in light space, and the depth range snaps the same way.
  - So static geometry keeps its texels while the camera moves. That gives no shimmer when walking or turning.
  - Shimmer only steps when the quantized sun moves: every 0.1°, about every 0.33 s of game time.
- **Light:** the sun, or the moon while the sun is below the horizon.
  - Direction: vanilla's celestial transform `RotY(-90°)·RotX(angle)·(0,1,0) = (-sin a, cos a, 0)`, using
    `SkyRenderState.sunAngle` / `moonAngle`.
  - It is quantized, and its elevation is raised to at least 2°.
  - There is no shadow map in the Nether or the End (`Skybox.NONE` / `END`), where `ShadowPass.enabled()` is false.
- **Bias:**
  - At raster time, casters get a constant bias of 1 texel of world size along the light (`mcopt.shade.shadowBiasTexels`),
    applied in the vertex stage.
  - They also get a slope-scaled `setDepthBias` (`mcopt.shade.shadowBias`, default 1, clamped).
  - Receivers apply `shade_shadow_offset()`: 0.75 texel along the normal, plus (0.5 + 0.75·tanθ) texels toward the light.
  - Depth clip mode is *clamp* ("pancaking"). Casters between the light and the cascade's near plane, up to 192 blocks
    toward the light, are drawn at depth 1, so the depth range stays tight.
- **Faces:** with `mcopt.shade.shadowFaces=sun` (the default), only the face directions that face the light are drawn,
  plus Sodium's UNASSIGNED quads (plants, non-axis-aligned). This uses Sodium's per-facing vertex ranges.
  - Faces turned away from the light are in shadow by N·L ≤ 0 anyway.
  - `all` draws every face.
- **Cutout:** leaves, grass and flowers are drawn with a fragment stage that discards when atlas alpha (level 0, nearest)
  × vertex alpha < 0.5. Sodium's cutout pass uses the same test. Solid terrain has no fragment function at all.
  Translucent terrain (water, glass) does not cast.

## 2. Casters: `mcopt.shade.shadowCull=camera|light`

Both modes read Sodium's section render data directly:

- `SectionRenderDataStorage.getDataPointer(section)`;
- `SectionRenderDataUnsafe` base vertex;
- per-slot vertex count and facing list.

They build the same ranges Sodium's `DefaultChunkRenderer` would build, filtered by facing, merged, and cut into
64-quad chunks. Nothing in Sodium is written, so the result does not depend on mcopt's `cpu.lists`, `cullRecover` and
`cullReuse` mixins (critique C9).

- **`camera` (default, "step 1")** uses the sections in Sodium's render lists for *this* frame
  (`RenderSectionManager.getRenderLists()`). By the time the frame graph runs, `setupTerrain` and `prepareChunkRenders`
  are done, so the lists and uploads are current. Sections are then culled against each cascade box.
  - Limitation: casters the camera doesn't see are missing, for example a mountain behind you or a roof above the view.
    Their shadows can pop when turning.
  - Deviation from the plan's wording: the plan said "record the camera's multi-draws". This mode reads the same lists
    instead, at the start of the frame. Recording would deliver last frame's draws, after arenas may have moved. It would
    also lose the facings, because Sodium culls back-facing *camera* facings, which are often the sun-facing ones.
- **`light` ("step 2")** scans every loaded region (`rsm.regions.getLoadedRegions()`). Every section with geometry whose
  16³ box can cast into a cascade is drawn, whether or not the camera sees it. Region boxes are rejected first.
  - It is not a swap of Sodium's render tree (Iris's approach). It is a read-only light-frustum scan, so it cannot disturb
    the camera's culling or mcopt's list mixins.
  - It is **not verified in game yet**, which is why `camera` stays the default. If the debug view looks right, it
    should become the default: it is the correct one.

Ordering safety:

- MetalTerrain may hold Sodium arena copies back for its split. The pass calls `MetalBridge.flushHeldTerrainCopies`
  before encoding, so it reads arenas as Sodium's tables describe them.
- Every buffer it reads goes through `MetalBridge.useBuffer`, so the backend never writes an arena from the CPU while
  the GPU may still read it.

## 3. Conventions (the contract with receivers)

The full text is in `metal/src/main/resources/mcopt/shade/shade_common.metal` (SHADOW CONTRACT block) and
`shade_shadow.metal`.

| What | Value |
|---|---|
| Binding | `texture(16)` = map (`depth2d_array<float>`, slice 0 near, 1 far); `sampler(12)` = compare sampler. Vertex and fragment stages |
| Matrix input | **Camera-relative** world position: world minus the camera position, which is the origin of Sodium's terrain vertices this frame |
| Matrix output | Orthographic light clip: x, y in [-1, 1], z in [0, 1], w = 1. Column-major `float4x4[2]` |
| Z | **Reversed**: 1 = nearest the light, 0 = farthest; cleared to 0 (no caster) |
| UV | `(0.5 + 0.5·x, 0.5 − 0.5·y)`: no Y flip when rendering, so clip y = +1 is texel row 0 |
| Compare | `GreaterEqual`. Metal evaluates `ref OP texel` (verified on this M4), so the result is lit when the receiver's z ≥ the stored z. Linear filter = 2×2 PCF per tap; border depth 0 |
| Disabled | `ShadowPass.enabled() == false`: treat everything as lit |

Receiver code (in `shade_shadow.metal`; prepend it to the shader):

```metal
float vis = shade_shadow(map, cmp, posCam, normalWorld, lightDir,
                         frame.shadowViewProj[0], frame.shadowViewProj[1],
                         float2(splits.z, splits.w),   // ShadowPass.texelSize(0), (1)
                         frame.shadowTexelSize);       // 1 / resolution
```

- Near cascade inside 85% of its square, then a blend band into the far cascade.
- The far cascade fades to lit at its edge.
- It returns 0 for N·L ≤ 0. For two-sided foliage, pass a light-facing normal.
- `shade_shadow_cascade(...)` gives one cascade alone.

## 4. Java API (`mcopt.metal.shade.shadow.ShadowPass`, render thread)

| Call | Returns |
|---|---|
| `beginFrame(CameraRenderState)` | Fits this frame's cascades and fixes the matrices. Idempotent within a frame; the shadow mixin calls it at `LevelRenderer.render` HEAD. **The ShadeFrame packer should call it before reading the values below**, so mixin order doesn't matter |
| `enabled()` | `ShadeFrame.shadowsEnabled` |
| `matrices(float[] out[, offset])`, `matrices(Matrix4f c0, Matrix4f c1)` | 32 floats: cascade 0, then cascade 1, column-major, camera-relative |
| `splits(float[4])` | {d0, d1, texel0, texel1}. d0 and d1 are the distances (blocks) within which cascade 0 / 1 covers every direction inside its blend margin: 14.4 and 92.8 by default. This is the `cascadeSplits.xy` layout, for receivers that pick a cascade by distance. texel0 / texel1 are blocks per texel |
| `receiverDepthBias()` | 0: casters are already biased at raster time |
| `texelSize(i)`, `texelUv()`, `resolution()` | blocks per texel of cascade i, 1 / res, res |
| `lightDir(float[3])`, `lightIsMoon()` | direction to the casting light (use it for N·L) |
| `texture()`, `sampler()` | `id<MTLTexture>` / `id<MTLSamplerState>` as long (0 before the first frame) |
| `bind(long enc)` | binds the map at texture(16) and the sampler at sampler(12) on the open native render encoder (`MetalBridge.enc(encoder)`), vertex and fragment. Call it at the infrastructure's rebind points |
| `TEXTURE_SLOT`, `SAMPLER_SLOT`, `CASCADES` | 16, 12, 2 |

Mapping onto the infrastructure branch's `ShadeFrame.setShadow(...)` (as of `m1-shade`):

```java
ShadowPass.beginFrame(cameraState);
float[] sp = new float[4];
ShadowPass.splits(sp);
ShadowPass.matrices(c0, c1);
frame.setShadow(ShadowPass.enabled(), ShadowPass.CASCADES, c0, c1, sp[0], sp[1], ShadowPass.resolution(),
	ShadowPass.receiverDepthBias(), 1.0f);
```

- `lightDirWorld` should be `ShadowPass.lightDir()`, with w = `lightIsMoon() ? 0 : 1`.
- The infrastructure's `shadeShadow()` offsets along the normal only. The headless tests (§7) show that grazing light
  also needs the toward-light term of `shade_shadow_offset()`.
- Selecting the cascade with the box test (`shade_shadow`) instead of distance uses the forward-pushed near cascade fully.

Native side (`metal/src/main/native/mcshadow.m`, in libmcmetal):

- `mc_shadow_new / free / texture / compare_sampler / render / bind / debug`.
- `mc_shadow_bind(Enc*, Shadow*, textureSlot, samplerSlot)` can also be called from C.

## 5. Debug view: `-Dmcopt.shade.debug=1`

Two tiles in the top-right corner of the screen: near cascade on the left, far on the right. Each is a quarter of the
screen height, with a yellow border.

- Dark blue: cleared (nothing casts there).
- Gray: depth. ±32 blocks around the camera's light depth map to black..white.
- A faint contour band every 4 blocks.
- Orientation: light-space +x is to the right and +y is up. With vanilla's sun path, light-space y is world +Z.

In-game check list:

1. Terrain should appear in both tiles.
2. The near tile should be a zoomed-in part of the far one, ahead of where you look.
3. Walking should slide the content without shimmering.
4. Turning should not change the far tile, except for its 4-frame refresh.
5. With `shadowCull=camera`, looking at the sky shrinks the content to the visible sections. With `light` it stays full.

## 6. Cost

**Headless bench** (`tools/shade/shadowtest/run.sh perf`, M4 10-core, steady state, 200 passes per command buffer):

| Workload | GPU time |
|---|---|
| Empty pass (clear + store, 2048²) | ≈ 0.05 ms |
| 83k sun-facing quads (256×256 heightmap) into 2048² | ≈ 0.21–0.23 ms per cascade |
| Same quads, Depth16Unorm | same |

- The cost is vertex-bound: 4096² costs about the same.
- Expected in game: near cascade (≈20–40k quads) every frame plus far cascade (≈100–200k quads) every 4 frames, which is
  about **0.2–0.3 ms per frame**, well inside the plan's ≤ 1 ms.
- CPU: one walk over the listed sections per cascade drawn. That is a few thousand pointer reads; the far walk runs every
  4 frames.

**In game:**

- `-Dmcopt.metal.trace=N` prints one line per cascade with sections, quads, chunks and draws.
- With profiling it names the GPU encoder number the cascade took. Encoder numbers after it shift by one relative to the
  game's trace numbering.
- Encoders are labelled `mcopt shadow cascade 0/1` for Xcode GPU captures.

## 7. Verification done (headless)

`tools/shade/shadowtest/run.sh` builds against the jar's `libmcmetal.dylib` and the real `shadow.metal`,
`shade_shadow.metal` and `ShadowCascade.java`. It does the following:

1. Builds a synthetic Sodium arena: a floor, a floating occluder, a +X wall, and a half-transparent cutout quad.
   It uses the real 20-byte compact format (with Sodium's UV shrink), a non-zero first vertex, and a camera-relative
   region offset computed like Sodium's `CameraTransform` at x≈1000, z≈−2000.
2. Renders both cascades through `mc_shadow_render`.
3. Evaluates `shade_shadow` at about 3,600 receiver points against ray-cast ground truth. The checks cover zenith angles
   20–70° (sun toward +X and −X), Depth32 and Depth16, and each cascade alone.
   - Result: **0 acne and 0 light leaks** outside a 0.35-block band around shadow edges (PCF penumbra).
   - The cutout's transparent half casts nothing; its opaque half casts.
   - At 85° (5° elevation) a few grazing-angle errors appear near geometry edges, where N·L ≈ 0.09 anyway.
4. Checks that a cached far matrix re-expressed against a moved camera maps world points to the same clip position, at
   0, 3·10⁴ and 2.9·10⁷ blocks.
5. Checks that cascade snapping keeps static points on the same texel phase while the camera moves.

**Not verified:** anything in game (mixin injection, Sodium state at the hook, the overlay). `mcbench-run` was not
available while this was built.

## 8. Files

- New:
  - `metal/src/main/native/mcshadow.m`
  - `metal/src/main/resources/mcopt/shade/{shadow,shade_shadow,shade_common}.metal`
  - `metal/src/main/java/mcopt/metal/shade/shadow/{ShadowPass,ShadowCascade,ShadowCasters,ShadowConfig,ShadowNative}.java`
  - `.../shadow/mixin/{ShadowLevelRendererMixin,ShadowMixinPlugin}.java`
  - `metal/src/main/resources/mcopt-metal-shade-shadow.mixins.json`
  - `tools/shade/shadowtest/*`
- Patched (minimal):
  - `metal/build.gradle`: `mcshadow.m` added to `sources`.
  - `fabric.mod.json`: the mixin config.
  - `MetalBridge.java`: `useBuffer`, `flushHeldTerrainCopies`.
- Merge note: `shade_common.metal` here holds only the SHADOW CONTRACT comment block. Keep the infrastructure's
  `ShadeFrame` file and paste the block in.

## 9. Known limits and next steps

- `camera` casters miss off-screen casters. Verify `light` in game and make it the default.
- Entities, block entities and particles don't cast (M3). Translucent terrain doesn't cast; coloured glass shadows are M3.
- Far terrain (`mcopt.lod`) doesn't cast. Its columns are outside 128 blocks anyway.
- No contact-hardening or PCSS yet: a fixed 3×3 tent PCF.
- The vanilla sun path has no tilt, so at noon shadows fall straight down. A tilted sun path, as many packs use, would
  have to change both `ShadowPass.chooseLight` and the infrastructure's `sunDirWorld`. Use `ShadowPass.lightDir()` as
  the single source.
