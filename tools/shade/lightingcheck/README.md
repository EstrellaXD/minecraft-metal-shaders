# Natural lighting comparison

Build with `./gradlew :metal:build`, then run on a Mac with Metal access and JDK 25:

```sh
tools/shade/lightingcheck/run.sh /tmp/lighting-check
tools/shade/lightingcheck/run.sh /tmp/lighting-perf perf
```

Set `BASELINE_JAR` to the previous full-scene HDR jar to compare Current with its actual Java classes and shader code. Set `MTL_DEBUG_LAYER=1 MTL_SHADER_VALIDATION=1` for correctness checks, but leave both unset for timing. `JAVA_HOME` may select the JDK. Like the existing cloud checks, the fixture runner uses cached JOML and Prism Launcher's LWJGL 3.4.3 libraries.

The runtime choice is **Shaders → Lighting → Lighting mode: Current / Natural**, saved as `mcopt.shade.lighting=current|natural`. Current is the default. Switching is live; it is independent of the existing Style choice. Natural requires Improved lighting (on by default). For the HDR comparison, use **Colour and camera → HDR display → HDR mode: Full scene**, then return to gameplay: pause/inventory screens intentionally use SDR.

Natural changes the light balance and exposure without changing the approved HDR display curve. Clear daytime sunlight gains 35%; sky fill and approximate bounce decrease more on surfaces with low skylight. A smaller minimum fill retains some dark detail, and torch radiance is unchanged. The CPU exposure reference is limited to a 1.5 EV interior lift relative to outdoors at full strength; the histogram can add at most another 0.75 EV above that reference. Metering includes more of a bright window while ignoring the brightest 2% of weighted samples. Adaptation to darkness is slower than adaptation to daylight. The effect fades through dawn/dusk, rain, storms and night vision; underwater, non-air fog, other dimensions and the early lighting model preserve Current behavior.

## Checks and outputs

- Real `ShadeFrame` / `ShadeLook` packing, option persistence/reset, live return to Current, exposure bounds and transitions.
- 24 scene/mode fixtures × 384 surface samples through production `shadeLit2`, `shade_exposure`, and `shadeFullHdr` on the GPU. Torch contribution, finite output, peak bounds, daylight/interior contrast, small bright-source rejection and broad-window metering are checked.
- Optional previous-jar comparison: Current's uniforms and exposure match exactly; lighting permits half-precision compiler rounding (0.3% relative tolerance, with measured errors printed).
- `contrast.json`: an equal-albedo surface in shade and an upward sunlit surface, with an 80% interior / 20% outdoor luminance histogram and 4× HDR headroom. These are synthetic measurements, not captures of a Minecraft house.
- `adaptation.csv`: CPU exposure entering and leaving a dark space at 60 Hz.
- `performance.json` / `performance.csv`: paired alternating-order GPU samples for 3840×2160 invocations of production diffuse lighting plus a float4 output store. Six warmup rounds, 30 measured rounds per mode, and an optional previous-jar control. This isolates the changed surface-lighting calculation; it is not a full-game FPS benchmark and excludes texture/shadow reads, other render passes and CPU work.

For an in-game comparison, stand inside a house facing a sunny doorway, keep the camera and time/weather fixed, and switch modes. Wait roughly 10–15 seconds for settled exposure after entering darkness; also test the transition by walking outside. Compare a torch-lit room and night/rain separately. Keep resolution and other settings identical when comparing frame times; a frame-rate cap may hide available performance headroom.
