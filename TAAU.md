# TAA / TAAU + dynamic resolution (shader bible E18) — branch `m2-taau`

Native shading (`-Dmcopt.shade=true`) renders the world at a **render scale** (0.60–1.00 of the output) into a fixed-size
**world target**, with a sub-pixel **Halton(2,3) jitter inside the projection matrix**, and resolves it with a single
fragment **TAAU** pass into a native-resolution **history**, which (sharpened) becomes the main target **before the hand
and GUI**: the HUD, hand and screen effects stay native and crisp. A GPU-time-driven controller moves the scale to hold
the preset's frame rate. At scale 1.0 the same pass is plain TAA (fixes M1's leaf-shadow shimmer and glint aliasing).

Everything is gated: `mcopt.shade=false` loads none of it (mixins in the shade config, every backend hook behind the
static finals `ShadeBindings.ON` / `ShadeTaa.ON`); `mcopt.shade.taa=false` gives back M1's native, unjittered frame.

**Status (2026-10-06):** working in game on `mcopt DEV` (Metal + Sodium, 3024x1964 fullscreen) at every preset; headless
correctness/perf test (`tools/shade/taatest`), `tools/shade/validate.sh` green (both `TAA_HDR` variants, debug variant).
Stills are as crisp as native and anti-aliased (leaf edges, glints, thin lines); rotation is clean; strafing softens
distant pixel-art texture somewhat (classic TAA, mitigated, see "Motion"). **2026-10-07: merged into `m2`** (HDR, bloom,
VL, water, Distant Horizons): see "Integration into M2" here and M2_STATUS.md for the numbers.

## Flags (system properties or `config/mcopt.properties`)

| Flag | Default | Meaning |
|---|---|---|
| `mcopt.shade.taa` | `true` | TAA/TAAU on (with `mcopt.shade=true`). `false` = M1's native render. |
| `mcopt.shade.preset` | `balanced` (m2) | `performance` / `balanced` / `quality` / `ultra` (the same flag M2's `ShadeConfig.TIER` reads). |
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
| quality | 1.00 | 0.85–1.00 | 80 fps | stable |
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

Cost (headless `tools/shade/taatest/run.sh perf`, 2026x1316 → 3024x1964, M4 10-core, timed while holding the bench's GPU
lock so no game runs alongside, min of 3–4 interleaved runs):

| shader | resolve (0.67x) | resolve + output (0.67x) | resolve + output (native TAA) |
|---|---|---|---|
| before this session (`a33be44`) | 1.26–1.32 ms (session A) / 1.62 (session B) | 1.63–1.70 / 2.06 | 2.08 (B) |
| now (half precision, age from the centre tap, offset-sampled 3x3, reactive read only with a mask) | 1.09 (A) / 1.30 (B) | 1.42 (A) / 1.77 (B) | 1.80 (B) |

(Sessions A and B ran ~2 h apart at different GPU clocks; within a session the variants are interleaved, so the ~20%
saving is the reliable number.)

The pass is ALU/latency bound, not bandwidth bound: a pass that only reads and writes the same bytes costs 0.20–0.27 ms;
lossy history compression changed nothing; the 3x3 neighbourhood costs ~0.3 ms, the history fetch chain ~0.5 ms, the clip
~0.1 ms. Bible budget 0.72 ms (0.67x): still ~0.3 ms over (see "Next"). In game, native + TAA costs +1.3–1.5 ms GPU over
M1 native (`taau6-qual` vs `-off`).

Quality (headless, synthetic checkered ground with 1/16-block texels and thin lines vs a 4×4 supersampled truth; err =
mean abs error /255, flicker = mean frame-to-frame change of a still scene):

| case (960x600 output) | plain (bilinear upscale) err | TAAU err | TAAU flicker |
|---|---|---|---|
| still 1.00 / 0.80 / 0.67 | 5.09 / 4.25 / 4.92 | **2.26 / 2.90 / 3.44** | 0.58 / 0.65 / 0.74 |
| turning (~2 px/frame) | 5.07 / 4.23 / 4.92 | 3.01 / 3.45 / 3.85 | 3.65 / 3.50 / 3.39 |
| moving (translation) | 5.14 / 4.30 / 4.99 | 3.22 / 3.67 / 4.11 | 6.46 / 6.05 / 5.75 |
| creep (~0.3 px/frame) | 5.10 / 4.23 / 4.91 | 2.92 / 3.39 / 3.84 | 1.04 / 1.05 / 1.11 |
| still 0.67 + sharpen 0.4 | 4.92 | 3.04 | 0.83 |

(The flicker of a moving camera includes real image change; compare it between variants, not with still.)

## Dynamic resolution (`DynamicResolution.java`)

Busy GPU time per frame (union of command-buffer intervals) → EMA over 12 frames → target 0.86 × budget (7.2 ms at
120 fps). s' = s·√(T*/T), quantised to 1/64, applied if |Δ| > 0.015 or at a bound. **Asymmetric** so it doesn't hunt:
down by up to 0.04 after 8 frames; up by at most 0.02 after 45 frames and only while T < 0.92·T*. In-flight frames of
the old scale are skipped. Targets never reallocate (viewport sub-rect), so a change costs nothing and Distant Horizons
(which reallocates on every main-target resize) sees a constant size.

## In-game results

`mcopt DEV`, Sodium, 3024x1964 fullscreen, RD 16, `--seconds 15`, GPU ms/frame from gputimes, all runs in the same
session interleaved with other agents' runs (the GPU is shared, so ±5%). M1 shading (shadows, sky, clouds, water) on.

| run | spin fps | spin GPU ms | flight fps | flight GPU ms |
|---|---|---|---|---|
| TAA off (M1 native) ×3 | 226 / 230 / 243 | 4.41 / 4.33 / 4.11 | 205 / 204 / 208 | 4.87 / 4.89 / 4.81 |
| **performance** (DRS → settles at 0.75, the preset max) ×4 | 226 / 220 / 220 / 233 | 4.43 / 4.53 / 4.55 / 4.29 | 198 / 193 / 194 / 208 | 5.05 / 5.17 / 5.16 / 4.81 |
| performance fixed 0.67 | 247 | 4.05 | 215 | 4.66 |
| balanced fixed 0.80 | 212 | 4.71 | 188 | 5.32 |
| quality (native + TAA) ×2 | 168 / 176 | 5.95 / 5.67 | 154 / 161 | 6.49 / 6.22 |

Reading: on M1's content the world is partly geometry/shadow bound, so 0.67x saves ~1.8 ms of world work and TAAU spends
~1.4 of it back: the performance preset runs at **M1-native speed (~200–240 fps, far above 120) with anti-aliasing**, and
the controller spends the headroom on resolution (it climbs to the preset's 0.75 cap). The real win comes with M2's
per-pixel effects (VL, SSR, water, fog, bloom at internal res), which scale with s²; the DRS then drops toward 0.67/0.60
to hold 120. Quality (native TAA) costs ~25% fps vs M1 and is the clean-image option at 60+ fps targets.

DRS under pressure (`taau6-drs200b`: performance preset told to hold 200 fps, a 4.3 ms target right at this frame's
cost): it tracked the spin/flight load between 0.60 and 0.75 (73 changes in ~90 s, never two within 8 frames, steps of
1/64, no reallocation), and the run held 245 / 233 fps (GPU 4.08 / 4.28 ms). At the default 120 fps target it never
needed to move off the 0.75 cap on M1 content.

Screenshots (`bench-results/<run>/screenshots`, motion shots via `-Dmcbench.motionShots=forest;overview;sunset`):
`20261006-210412-taau6-perfb` (performance, current code), `20261006-210200-taau6-qual` (quality),
`20261006-190036-taau2-off` (TAA off), debug views `20261006-194834-taau4-dbg2` (rejection) and
`20261006-200320-taau5-dbg4` (reprojection check).
- **Still** (forest close-up, noon overview, sunset water, night torches, rain): at 0.75x TAAU is as sharp as native and
  better anti-aliased (leaf cut-out edges, distant tree silhouettes, water glints); quality (native TAA) cleaner still.
- **Rotation** (forest pan 30°/s): clean, slightly softer than native, no ghosting on leaves against the sky.
- **Translation** (overview strafe 8 blocks/s): reprojection is exact (debug 4: no doubled edges) but distant pixel-art
  texture softens (cobblestone, spruce leaves) — repeated sub-pixel history resampling. The motion weight floor reduced
  it; still the main visible TAA cost. Rising smoke particles leave short trails (no reactive mask yet).

## Integration into M2 (done: branch `m2`, merge `095ce42`)

What the merge did (frame diagram and module rules: M2_INTEGRATION.md section 6):

1. Both TAA mixins and `MetalEncoder.setSubRect` stay. `MetalEncoder` now also clips **redirected** passes (M2's HDR
   format redirect) to the sub-rect when the pass's original attachments are the world target's, so `ShadePost.redirect`
   is unchanged.
2. `ShadeTaa` defaults `hdrInput` to `ShadeConfig.HDR` (the first `get()` happens at the camera jitter, before ShadePost
   exists). New: `resolveHdr(color, depth)` (resolve without an output pass, returns `history()`), `outputTarget()` (the
   real main target), `allocWidth/allocHeight()`.
3. `ShadePost.beforeHand` (runs right before render3dHud, i.e. before the TAA mixin's HEAD inject): hooks (VL) on the
   sub-rect -> `resolveHdr` -> bloom L0 from the **internal** HDR scene (uv scaled to the sub-rect, bible E19) -> exposure
   -> `shade_final` reads the history at output size, **sharpens it** (the module's contrast-limited cross, moved into the
   final pass: one full-resolution pass less) and writes the game's real main target. The mixin at render3dHud HEAD then
   only gives the main target back (`swapped()` is false after `resolveHdr`).
4. Exposure: the resolve binds `ShadeGpu.exposure` (buffer 1, offset 176) and rescales the history by x / z on the GPU
   (`mc_shade_gpu_buffer`); M1 / taatest bind (misc.x, 0, 1, 0) instead, so the CPU rescale still works there.
5. `ShadeLook.viewProjNoJitter/prevViewProjNoJitter` = the jitter translation removed; `renderScale` = (scale, mip bias,
   live w, live h). `cameraBlock.w` keeps the mip scale for Sodium terrain.
6. VL: targets sized from the texture (allocation) size, `VlParams.size.zw` = live size (the VL image always covers the
   live area; the final pass's uv upsample is then exact). The jittered matrices are kept on purpose: positions come from
   this frame's jittered depth.
7. Water: `SHADE_WATER_SUBRECT` always defined, `SHADE_WATER_TAA` makes the SSR step jitter change per frame; SSR's
   colour source (`prevScene`) is the TAA history. The scene/depth copies still copy the whole allocation (simple; the
   region copy of a depth texture is not worth the risk for ~0.1 ms).
8. Presets: one parser (`Shade.TIER`, default **balanced**); quality holds 80 fps (was 60).

Still open: reactive mask producers (particles/rain/water), per-object motion vectors, MetalFX as the ultra upscaler,
FT2's merged scene distance as the resolve's depth for DH pixels.

## Per-object motion (plan, M3)

Today motion is the camera's only (static world). Moving entities, falling blocks, pistons and the held item rely on the
clip (they ghost a little at high speed but never smear for long: α rises with `outside`). Plan (bible E09 M3):
entity pipelines get an MRT variant writing `color4` RG16F = current clip − previous clip (previous pose from a cache
keyed by entity id + part); the resolve reads it where non-zero instead of the depth reprojection, and marks pixels
without a previous pose reactive 0.5. Hand pixels never reach TAAU (resolved before the hand).

## Motion

Every moving frame resamples the history at a new sub-pixel offset; Catmull-Rom at a half-pixel offset passes a 2.5 px
period at 0.45, and fed back with a weight of ~0.07 that leaves ~0.12 of it. So without countermeasures fine texture
melts while strafing even with exact reprojection. Current countermeasures: a floor on the current frame's weight that
is full from 0.5 px/frame of motion (`TaaConfig.motionAlpha`: Responsive 0.25, Stable 0.18), plus the contrast-limited
sharpen. A sharper resampling cubic (Keys a = −0.75) helped motion error a little but flickered more; a 5-tap cross
neighbourhood flickered 20–25% more; both rejected (taatest numbers in the shader comments).

## Not done / next

- MetalFX as the Ultra upscaler (bible E18 option: depth at H7, exposure texture, reactive mask, inputContentWidth for
  DRS). `MetalFx` is simply disabled while `ShadeTaa.ON`; Ultra = native + TAA for now.
- Resolve cost: ~0.3 ms over the bible's 0.72 ms. Next candidates: one combined reprojection matrix
  (`prevVP·invCur`, view depth = 1/h.w: −0.04 ms measured by removing both), a cheaper clip, and in M2 dropping the
  output pass (−0.33 ms; the final pass sharpens).
- Motion softness while strafing (above): candidates are a motion-aware sharpen in the output/final pass and FSR2-style
  "locks" on thin features.
- Distant Horizons under TAAU: only reasoned (jitter in the projection, fixed allocation); test on `mcopt DEV-DH` after
  the merge with `ft0`/`m2`.
- Reactive mask producers (particles, rain, water) belong to E09/E14.
