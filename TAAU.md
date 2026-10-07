# TAA / TAAU + dynamic resolution (shader bible E18) — branch `m2-taau`

Native shading (`-Dmcopt.shade=true`) renders the world at a **render scale** (0.60–1.00 of the output) into a fixed-size
**world target**, with a sub-pixel **Halton(2,3) jitter inside the projection matrix**, and resolves it with a single
fragment **TAAU** pass into a native-resolution **history**, which (sharpened) becomes the main target **before the hand
and GUI**: the HUD, hand and screen effects stay native and crisp. A GPU-time-driven controller moves the scale to hold
the preset's frame rate. At scale 1.0 the same pass is plain TAA (fixes M1's leaf-shadow shimmer and glint aliasing).

Everything is gated: `mcopt.shade=false` loads none of it (mixins in the shade config, every backend hook behind the
static finals `ShadeBindings.ON` / `ShadeTaa.ON`); `mcopt.shade.taa=false` gives back M1's native, unjittered frame.

STATUS_PLACEHOLDER

## Flags (system properties or `config/mcopt.properties`)

| Flag | Default | Meaning |
|---|---|---|
| `mcopt.shade.taa` | `true` | TAA/TAAU on (with `mcopt.shade=true`). `false` = M1's native render. |
| `mcopt.shade.preset` | `performance` | `performance` / `balanced` / `quality` / `ultra` (the same flag M2's `ShadeConfig.TIER` reads). |
| `mcopt.shade.scale` | preset's | Start scale. |
| `mcopt.shade.scaleMin`, `scaleMax` | preset's | Dynamic-resolution range. Targets are allocated at `scaleMax`. Floor 0.60. |
| `mcopt.shade.drs` | `true` | Dynamic resolution. `false` = fixed at `scale`. |
| `mcopt.shade.drsFps` | preset's | Frame rate the controller holds. |
| `mcopt.shade.taaMode` | preset's | `responsive` (α_min 0.12, clip γ 1.0) / `stable` (0.08, 1.25); γ +0.5 while the camera is still. |
| `mcopt.shade.sharpen` | by scale | 0..1. Default 0.25 at native rising to 0.40 at 0.67x. |
| `mcopt.shade.mipBias` | `true` | Texture LOD bias log2(scale): textures stay as sharp as at native. |
| `mcopt.shade.taaLossy` | `false` | Lossy framebuffer compression for the colour history (Apple8+). Measured no faster (the pass is ALU/latency bound); kept for M2's heavier frames. |
| `mcopt.shade.taaDebug` | `0` | 1 history age (red→green), 2 rejection (red clip, green disocclusion, blue off-screen/no history), 3 no history (raw jittered input). |
| `mcopt.shade.drsLog` | `false` | Log every scale change with the controller's GPU ms. |

Presets (bible §5; "never default below 0.67x", critique C2):

| Preset | Start | Range | Holds | Mode |
|---|---|---|---|---|
| performance | 0.67 | 0.60–0.75 | 120 fps | responsive |
| balanced | 0.80 | 0.67–0.90 | 120 fps | stable |
| quality | 1.00 | 0.85–1.00 | 60 fps | stable |
| ultra | 1.00 | 0.90–1.00 | 60 fps | stable (MetalFX option: see "Not done") |

`mcopt.fx.scale` (upstream MetalFX) is ignored while `ShadeTaa.ON` (one owner of the render scale; a log line says so).

## Per-frame flow (M1 path, this branch)

| When (hook) | What | Code |
|---|---|---|
| `Camera.extractRenderState` TAIL | DRS picks this frame's scale; the camera projection is pre-multiplied by a translation of (2jx/w, 2jy/h) NDC, Halton(2,3) with N = clamp(round(8/s²), 8, 32) phases. Every world draw copies this projection (vanilla, Sodium, Distant Horizons via `MixinProjectionMatrixBuffer`), so all of them jitter alike. | `ShadeTaaCameraMixin`, `ShadeTaa.jitter` |
| `GameRenderer.render`, first `RenderSystem.getDevice()` | The **world target** (a `MainTarget` allocated once at `scaleMax`) replaces `GameRenderer.mainRenderTarget`; `MetalBridge.setSubRect` makes the backend draw every pass on its colour/depth into (0,0,w,h). `levelRenderer.resize(alloc)` so entity outlines / post chains match. | `ShadeTaaGameRendererMixin.mcopt$taaBeginWorld`, `ShadeTaa.beginWorld`, `MetalEncoder.setSubRect/inSubRect` |
| `GlobalSettingsUniform.update` args | `ScreenSize` = the live sub-rect (line widths, texel maths in world shaders). | `mcopt$taaWidth/Height` |
| `ShadeFrameUpdater` | `ShadeFrame.screen` = sub-rect size, `jitter.xy` = NDC jitter (zw last frame's), `cameraBlock.w` = mip scale. | `ShadeFrameUpdater` |
| Sodium terrain fragment | `dfdx/dfdy(uv) *= cameraBlock.w` → LOD bias log2(s). | `sodium_terrain.fs.metal` (MCOPT_SHADE) |
| `GameRenderer.renderLevel` at `setProjectionMatrix` | Unjittered view-projection (jitter removed from the final projection, view bobbing included) + camera position, for reprojection. | `mcopt$taaCamera`, `ShadeTaa.camera` |
| `GameRenderer.render3dHud` HEAD (before the hand clears depth) | Main target back; **TAAU resolve** world sub-rect + history → new history, then the **sharpen/output** pass into the real main target. Sub-rect off. | `mcopt$taaResolve`, `ShadeTaa.resolve` → `resolveTextures` → `mc_taa_resolve` |
| `FogRenderer.endFrame` | Safety net: a frame that never reached `render3dHud` puts the main target back and resets the history. | `mcopt$taaEndFrame`, `ShadeTaa.endFrame` |
| command buffer retire | GPU start/end → DRS. | `MetalEncoder` retire → `ShadeTaa.gpuFrame` |

History cut (fresh accumulation): first frame, resize, level change, > 0.5 s since the last resolve (menus/loading), camera
moved > 8 blocks (teleport).

## The resolve (`metal/src/main/resources/mcopt/shade/taa.metal`, `metal/src/main/native/mctaa.m`)

Pass 1 `taau_fs`, once per **output** pixel q, MRT into two compressible targets (private, `RenderTarget|ShaderRead`):
`color(0)` RGBA16F history (rgb in the input's encoding, a = age in frames) and `color(1)` R16F linear view depth.

1. Input position: `pin = q·(in/out) + jitter` (pixels of the jittered input sub-rect).
2. 3×3 input neighbourhood: Gaussian (Blackman–Harris fit, r ≈ 1 input px, separable: 6 exps) reconstruction `current`;
   mean/σ for the clip box. HDR input (`TAA_HDR`, function constant 0): every sample range-compressed c/(1+luma) (Karis).
   RGB box, not YCoCg (no better on taatest, costs ALU); a 5-tap cross was 0.06 ms cheaper but flickered 20–25% more.
3. Motion from depth: nearest of the 2×2 depths (one gather, reversed Z max) → camera-relative world → last frame's
   unjittered clip (`prevVP` re-based on this frame's camera, so no large-coordinate precision loss).
4. History: 5-tap Catmull-Rom (MJP, MIT; Jimenez' corner drop) in half; age = the centre tap's alpha.
5. Disocclusion: |stored linear depth − expected| > 3%·d + 0.1 blocks fades the age to 0. Off-screen → no history.
6. Variance clip toward the mean (γσ, AABB-line clip that keeps hue). `outside` = how far out, in box units.
7. α = max(1/(age+1), α_min) · mix(0.35, 1, w_nearest) (confidence: output pixels between input samples trust history);
   α ≥ 0.5·saturate((outside−1)/4) (anti-ghosting: something changed); α ≥ 0.85·reactive (when a mask is bound).
8. NaN/Inf guard → current.

Pass 2 `taa_output_fs`: history → destination (any format; PSO per format), contrast-limited 4-neighbour unsharp mask
clamped to the local min/max (no halos). Skipped when `dst == 0` (M2: the final pass sharpens).

Cost (headless `tools/shade/taatest/run.sh perf`, 2026x1316 → 3024x1964, M4 10-core, GPU lock held, min of 4):

| | resolve | resolve + output |
|---|---|---|
| before (`a33be44`) | 1.26–1.32 ms | 1.63–1.70 ms |
| now (half precision, age from the centre tap, reactive read only with a mask) | 1.09 ms | 1.42 ms |
| floor: a pass that only reads+writes the same bytes | 0.20–0.27 ms | |

The pass is ALU/latency bound (lossy history: no change; removing the clip: −0.12 ms; removing the history fetch:
−0.54 ms). Bible budget 0.72 ms: still ~0.4 ms over (see "Next").

Quality (headless, synthetic checkered ground with 1/16-block texels and thin lines vs a 4×4 supersampled truth; err =
mean abs error /255, flicker = mean frame-to-frame change of a still scene):

PLACEHOLDER_QUALITY

## Dynamic resolution (`DynamicResolution.java`)

Busy GPU time per frame (union of command-buffer intervals) → EMA over 12 frames → target 0.86 × budget (7.2 ms at
120 fps). s' = s·√(T*/T), quantised to 1/64, applied if |Δ| > 0.015 or at a bound. **Asymmetric** so it doesn't hunt:
down by up to 0.04 after 8 frames; up by at most 0.02 after 45 frames and only while T < 0.92·T*. In-flight frames of
the old scale are skipped. Targets never reallocate (viewport sub-rect), so a change costs nothing and Distant Horizons
(which reallocates on every main-target resize) sees a constant size.

## In-game results

PLACEHOLDER_INGAME

## Integration into M2 (branch `m2`: HDR world target, `ShadePost`)

`m2` renders the world into an HDR target (format redirect of main-colour passes, `ShadePost.redirect`) and tone-maps
at `render3dHud` HEAD (`ShadePost.beforeHand`). TAAU slots in like this:

1. **Keep both TAA mixins and `MetalEncoder.setSubRect`.** `ShadeTaaGameRendererMixin` swaps `mainRenderTarget` before
   `LevelRenderer.render`, so `ShadePost.beginWorld(main)` sees the world target and sizes its HDR targets to the
   allocation (`scaleMax` × output) — fixed size, never per frame.
2. **Sub-rect for redirected passes.** `MetalEncoder` applies the sub-rect only when `redirect == null`. In
   `ShadePost.redirect`, return `Redirect.formats(..., w, h, ...)` with `w = taa.width(width)`, `h = taa.height(height)`
   while `ShadeTaa.get().swapped()`: the redirected pass then draws (0,0,w,h) (MetalRenderPass area = redirect size).
   DH bridge encode/decode passes and every `ShadeRender.pass` that reads the HDR scene must use the same sub-rect
   (viewport) or sample it with `uv · (w,h)/alloc`.
3. **Call order at `render3dHud` HEAD:** make `ShadeTaaGameRendererMixin.mcopt$taaResolve` *not* resolve when HDR is
   on (just put the main target back), and in `ShadePost.beforeHand`:
   - `ShadeTaa.setHdrInput(true)` once at init (before the first `ShadeTaa.get()`);
   - hooks before the tone map that work at internal resolution (VL, fog composite) run first, on the sub-rect;
   - bloom L0 from the internal HDR scene (bible E19: pre-TAAU, saves the full-res read);
   - `taa.resolveTextures(scene, depth, reactive, 0, 0f)` (dst 0: history only);
   - the final pass reads `taa.history()` (RGBA16F, output size; rgb = linear pre-exposed HDR, decompressed; a = age)
     instead of `scene`, at output size, and does the RCAS-style sharpen (strength
     `TaaConfig.sharpen(taa.scale())`).
   - SSR (E14) samples `taa.history()` of the previous frame (call `history()` before `resolveTextures`) with the
     unjittered previous view-projection; `taa.historyDepth()` is the bible's `prevDepth` (linear blocks, output size).
4. **Exposure:** `taa.setHistoryRescale(exposure.x / exposure.z)` before the resolve (the CPU copy of `ShadeGpu.exposure`,
   or move `misc.x` to a GPU read of the exposure buffer if exposure is only known on the GPU).
5. **ShadeLook (slot 17):** `viewProjNoJitter`, `prevViewProjNoJitter`, `renderScale` (xy scale, zw sub-rect size) —
   `ShadeTaa.viewProj` (unjittered, camera-relative) and `prevViewProj` re-based are exactly these. `cameraBlock.w` (mip
   scale) moves to `ShadeLook.renderScale` per the shade_common.metal comment.
6. **Reactive mask:** bind an R8 texture at input resolution (`reactive` argument); particles/rain (E09) and water's
   animated normals (E14, 0.25) write it; with none bound the shader skips the read.
7. **Distant Horizons (far-terrain-plan §3.2):** jitter is already in the projection (LODs inherit it; DH's own TAA must be
   off, R6). FT2's merged scene-distance image should replace `curDepth` in the resolve: reconstruct with
   `world = normalize(invCur·(ndc,0,1) − camera) · dist` and keep the disocclusion test on linear depth (it already
   stores linear blocks). DH targets follow the (constant) allocation size, never the sub-rect.
8. **validate.sh** already compiles both `TAA_HDR` variants and builds the resolve + output PSOs.

## Per-object motion (plan, M3)

Today motion is the camera's only (static world). Moving entities, falling blocks, pistons and the held item rely on the
clip (they ghost a little at high speed but never smear for long: α rises with `outside`). Plan (bible E09 M3):
entity pipelines get an MRT variant writing `color4` RG16F = current clip − previous clip (previous pose from a cache
keyed by entity id + part); the resolve reads it where non-zero instead of the depth reprojection, and marks pixels
without a previous pose reactive 0.5. Hand pixels never reach TAAU (resolved before the hand).

## Not done / next

- MetalFX as the Ultra upscaler (bible E18 option: depth at H7, exposure texture, reactive mask, inputContentWidth for
  DRS). `MetalFx` is simply disabled while `ShadeTaa.ON`; Ultra = native + TAA for now.
- Resolve cost: ~0.4 ms over the bible's 0.72 ms. Next candidates: one combined reprojection matrix
  (`prevVP·invCur`, view depth = 1/h.w: −0.04 ms measured by removing both), a cheaper clip, and in M2 dropping the
  output pass (final pass sharpens).
- Reactive mask producers (particles, rain, water) belong to E09/E14.
