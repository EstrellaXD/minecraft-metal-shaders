# M3.3 robustness: never-tested paths and frame generation (branch `m3.3-robust`)

## M3.3 Status (2026-10-08 09:50)

Branch `m3.3-robust` from `m3-int` 397e241 (M3.2 + aurora-lean sky). ROOT = the project folder.

**Done:**
- d41b119 dev probes (inert by default): `ShadeRobust` (`-Dmcopt.shade.dev.failCompile=<list>` injected build failures,
  `deviceAllocatedBytes()` = `MTLDevice.currentAllocatedSize` via new native `mc_device_allocated`).
- mcbench 7ea09ed / 5fa8027 / (fgbutton) `RobustShots.java` (`-Dmcbench.robust=gui,transp,dims,resize,rejoin,fail,framegen,fgbutton`).
- **Run R1** (current code = M3.2 + probes, jar `notes/m33-2026-10-08/jars/m3.3-robust-d41b119.jar`):
  `bench-results/20261008-093244-rb1-robust` (27 shots, no crash, no exception besides the injected ones). Results in
  section 1 below. Only (c) Improved Transparency was broken.
- dab0821 fixes: (c) `ShadeOitMixin` (Improved Transparency kept off while shading, `mcopt.shade.oitOff`); (f) vanilla clouds
  when the shaded sky fails (`ShadeRobust.registryBuilt`, `mcopt.shade.robustFallback`); frame generation under TAAU
  (`ShadeFrameGen` + `mcframegen.m fg_motion_depth_sub`, `mcopt.shade.framegen.taau`); pause-menu button "Gen. de cuadros"
  (`ShadeFrameGenOption`, `mcopt.shade.framegenButton`). Build OK, `validate.sh` ALL OK.

**Running / next:**
- R2 (`notes/m33-2026-10-08/robust/r2.sh`, jar dab0821): frame generation upstream-vs-fix in one session with FrameGen dumps,
  the button, transparency fix check, GUI, "*" failure (clouds fallback).
- R3: startup failure of the post chain (`failCompile=library:ShadePost`) and/or `*` at startup.
- R4: final verification run (all events, final jar, no frame generation).

## 1. Never-tested paths (R1, before fixes)

Run: `bench-results/20261008-093244-rb1-robust`, M3.2 code, balanced, TAAU auto (dynamic resolution), fullscreen 3024x1964.
Contact sheets: `ROOT/work/release/M3.3-screenshots/robust_*.jpg`.

| Path | Result |
|---|---|
| (a) leave + rejoin x3 in one session | **OK.** No crash, no black or garbage frame (shot 0.4 s after the world shows: sky + the chunks loaded so far, nothing else), settled shots match the first one (differences are cloud motion and cloud shadows). **No leak:** Metal device memory in the world 1531 / 1558 / 1533 / 1559 MB (before / after rejoin 1 / 2 / 3), on the title screen 1173 / 1200 / 1175 MB; Java heap after GC 573 / 515 / 518 / 523 MB; RSS 1.95-2.4 GB without a trend. The shading keeps ~1.17 GB of targets alive on the title screen (HDR, TAAU, VL, clouds, shadow maps: reused on rejoin, not a leak). |
| (b) overworld -> Nether -> overworld -> End -> overworld | **OK.** Each dimension shaded as in its own M3 tests, the overworld after both trips identical in look to before (mean abs diff 6-9/255, cloud motion). The Nether settle timed out at 60 s (chunks keep loading there; known, harmless). |
| (c) vanilla Improved Transparency (26.3 OIT) + TAAU | **Broken (fixed in dab0821).** With it on, water lost reflections/refraction (vanilla flat water), rain became opaque dark-blue streaks, cutout leaves greyed out, sand flat: the game's OIT pipelines bypass the shading (`robust_transp_before_off_vs_on.jpg`). M3.2 only logged a warning. |
| (d) GUI over the world + hand | **OK.** HUD + hand (torch, compass) lit, creative inventory, chat with text, F3 overlay, pause menu (blurred world), title screen after leaving: all correct (`robust_gui_hand_inventory_chat_f3_pause_title.jpg`). |
| (e) fullscreen -> windowed 3024x1684 -> window 2000x1280 -> fullscreen | **OK.** TAAU and HDR targets rebuilt at each size (log: `TAA 1814x1010 -> 3024x1684`, `HDR targets 2000x1280`...), shots correct at each size. |
| (f) shader build failure (injected, then a resource reload) | **OK, clean per pipeline.** `failCompile=terrain`: the three Sodium terrain pipelines log `registry MSL for sodium:pipeline/... failed to build, using the game's shaders` and draw with the game's shaders (vanilla-lit terrain, shaded sky and clouds), no crash. `*`: every shaded pipeline falls back (13 log lines): a vanilla look, **but no clouds at all** (ours are composited in the shaded sky, vanilla's stay replaced; fixed in dab0821). Clearing it and reloading brings the full shading back (`robust_fail_terrain_all_restored.jpg`). |

## 2. Fixes checked in R2 (`bench-results/20261008-094839-rb2-framegen`, jar dab0821, frame generation on)

- (c) Improved Transparency: with the option set to on, the four shots match the "off" ones (water with reflections, thin
  rain); the log says once `Improved Transparency is on in the video settings: kept off while the shaders run`.
- (f) `*` failure: now with vanilla clouds (`the shaded sky did not build: volumetric clouds off, vanilla clouds instead`), back to
  volumetric clouds after the restoring reload (`the shaded sky built again`).
- Pause menu: "Resolución: Auto (dinámica)" and "Gen. de cuadros: Sí" side by side; pressed: "Gen. de cuadros: No (al
  reiniciar)" and the config line `mcopt.metal.framegen=false`; pressed again: "Sí", `true`; the file was restored afterwards.
- Seen once, not explained yet: `rb_transp_off_water` (the first scene after the pause-menu test, 45 blocks away and the time
  jumping 6000 -> 12400) had smeared clouds with horizontal streaks after 5 s; the same jump in R1 (no frame generation, no pause
  menu before) was clean. R5 (frame generation) and the final run (no frame generation) repeat that sequence to tell which.

## 3. Frame generation (upstream MetalFX interpolation) with our shaders

- **It runs** with the native shading: no crash, XDR turns itself off (`XDR output off (... frame generation: off)`), the GUI and
  the hand are composited onto generated frames correctly (dump `*-3-generated`), screens show real frames only.
- **Motion vectors were wrong under TAAU (fixed, `mcopt.shade.framegen.taau`).** At the level-drawn point the main target is
  TAAU's world target, rendered in a sub-rect (80 % at auto) with a jittered projection; upstream read its depth texel for texel
  at the output size, so most pixels got another pixel's depth. Turning (rotation only) doesn't care about depth, so `turn` dumps
  look alike; moving does: upstream's generated frame tears the sand terraces' edges into dark specks, the fix's is clean
  (`ROOT/work/release/M3.3-screenshots/robust_framegen_move_upstream_vs_fix_zoom.jpg`, dumps in
  `notes/m33-2026-10-08/robust/r2-framegen-dumps/`).
- **The real frame rate halves to 30 fps: not worth it with our shaders (left off, button hidden).** R5
  (`bench-results/20261008-101850-rb5-framegen-measure`, AC power, FrameGen's own once-a-second report, now in latest.log; averages
  of 20 reports per variant, method: in-session variants, `notes/m33-2026-10-08/robust/r5-latest.log`):

  | Balanced, 3024x1964, frame generation on | real fps | shown fps | real frame start -> shown (avg / p90) | frame GPU p90 |
  |---|---|---|---|---|
  | resolution auto (TAAU at its 0.6 floor) | 29.2 | 58.4 | 51.2 / 52.6 ms | 22.9 ms |
  | resolution 1080p (pause-menu button) | 28.9 | 57.8 | 53.6 / 57.0 ms | 22.5 ms |
  | (reference, same pacing on the loading screen: trivial frames) | 119 | 119 | 24.5 ms | 2.0 ms |

  The frame's GPU time stays ~22.7 ms whatever the render resolution: MetalFX's interpolation at the 3024x1964 output (plus the
  copies) costs ~12-15 ms per real frame on the M4's 10-core GPU. FrameGen only paces real frames at half the refresh (60) when a
  frame fits 85 % of 16.7 ms, so it falls to a quarter: 30 real + 30 generated = 60 shown, ~51 ms from a frame's start to the
  screen. Without it the same preset runs ~88-95 real fps (M3.2 notes), shown up to 120 Hz: smoother and more responsive.
  So `mcopt.metal.framegen` stays off and the pause-menu button only appears with `-Dmcopt.shade.framegenButton=true`.
  Untested: the external 1080p 144 Hz monitor (a 1920x1080 output makes the interpolation ~2.9x cheaper; 72 real fps might fit),
  and quality/ultra presets below 60 fps, where 30 real + 30 generated could feel smoother than ~45 real.
- With frame generation the clouds smear: R2 and R5 `rb_transp_off_water` both show smeared clouds with horizontal streaks
  5 s after the jump (45 blocks, time 6000 -> 12400) that follows the fast camera moves and the pause menu; R1 (no frame
  generation, no fast moves) was clean after the same kind of jump. R6 repeats the sequence without frame generation.

## 3b. Startup shader failures (R3, R4)

- R3 `bench-results/20261008-101627-rb3-failpost`, `-Dmcopt.shade.dev.failCompile=library:ShadePost` (the HDR post chain fails at
  startup): log `HDR post chain failed, M1's tone curve instead`; the game runs M1's LDR way with everything else on (shadows,
  water, VL "LDR", TAAU, GUI, hand): coherent, no crash, no garbage (standard shots + GUI). **But no clouds at all**: M1's sky has
  no volumetric-cloud composite (`sky.fs.metal`, MCOPT_HDR only) and vanilla's stayed replaced. Fixed in 056669f
  (`ShadeRobust.postFailed`: our clouds step back, vanilla's return; one line in ShadePost.init's catch).
- R4 (queued): `taa,vl,shadow,library:` at startup + in-session `*`.

## 4. Flags added (all default = the fixed behaviour; the value in brackets = M3.2)

| Flag | Default | Meaning |
|---|---|---|
| `mcopt.shade.oitOff` | true (false) | Improved Transparency (OIT) kept off while shading; read every call. |
| `mcopt.shade.robustFallback` | true (false) | Shaded sky failed to build, or the HDR post chain failed -> volumetric clouds step back, vanilla clouds return. |
| `mcopt.shade.framegen.taau` | true (false) | Frame generation reads TAAU's live depth sub-rect with unjittered matrices; read every frame. Only matters with `mcopt.metal.framegen=true`. |
| `mcopt.shade.framegenButton` | false (= M3.2) | true: "Gen. de cuadros" button next to "Resolución" in the pause menu (saves `mcopt.metal.framegen` in config/mcopt.properties, applies at the next start). Hidden by default: frame generation doesn't pay with our shaders (section 3). |
| `mcopt.shade.dev.failCompile` | empty | Dev only: inject shader build failures (see `ShadeRobust`). |
| `mcopt.metal.framegen.dumpTag` | empty | Dev only: re-arms FrameGen's dumps (files prefixed with the tag). |

## 5. Integration notes (shared or upstream files touched)

- `metal/src/main/native/mcmetal.m`: + `mc_device_allocated` (one function). `Native.java`, `MetalBridge.java`: its binding.
- `MetalPipeline.java` (upstream): registry path: injected failure check (dev) and `ShadeRobust.registryBuilt` calls (only
  reached for registry pipelines, i.e. mcopt.shade=true).
- `ShadeRender.java`, `ShadeBindings.java`, `taa/ShadeTaa.java`, `vl/ShadeVl.java`, `clouds/ShadeClouds.java`,
  `shadow/ShadowPass.java`: one `ShadeRobust.maybeFail/failCompile` line each before their native setup (dev, inert unset).
- `FrameGen.java` (upstream) + `mcframegen.m` (upstream): TAAU branch in `recordLevel` (null = upstream path), new
  `motionSub`/`mcf_motion_sub`/`fg_motion_depth_sub`, dev `dumpTag`.
- `mixin/shade/ShadePauseScreenMixin.java`: with `mcopt.shade.framegenButton=true` two buttons side by side (resolution left,
  frame generation right); by default unchanged (one centred resolution button).
- `ShadePost.java` (shared): one line in init's catch, `ShadeRobust.postFailed()`.
- `FrameGen.java`: its three printf log lines now println(String.format(...)) (printf bypassed latest.log).
- `resources/mcopt-metal-shade.mixins.json`: + `ShadeOitMixin`.
- New files: `shade/ShadeRobust.java`, `shade/ShadeFrameGenOption.java`, `shade/taa/ShadeFrameGen.java`,
  `mixin/shade/ShadeOitMixin.java`.
- mcbench (shared checkout): new `RobustShots.java`; `BenchDriver.java` one plan line + one doc line.
