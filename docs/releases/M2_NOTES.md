# M2 "Shaders on Metal v0.2": mcopt-shade-M2.jar

Branch `m2` of `work/mcopt-shade` (worktree `work/wt/m2`), on top of `shade` (= upstream mcopt 0.2.0-alpha.3) with M1 and
FT0 (Distant Horizons on Metal) merged. Jar SHA-256 `b5ce0f3b941f864f86d7973609cb960cc47ba05b017edea518f091d0b951674a`
(byte-identical to the jar every final bench below ran). It replaces mcopt's `metal-0.2.0-alpha.3.jar` in a Prism instance
(Minecraft 26.3, Fabric 0.19.5, Sodium 0.9.3-alpha.1). **Nothing has been installed into any of your instances.**

## Resumen (ES)

**Qué hay de nuevo (lo que pediste: rayos de luz y reflejos):**

- **Rayos de luz (god rays / luz volumétrica):** haces de sol entre las hojas de los árboles por la mañana y al
  atardecer, rayos en la entrada de cuevas y aleros, cortinas de luz bajo el agua que se mueven con las olas. En el cielo
  del atardecer las nubes ahora proyectan **rayos oscuros** sobre el halo del sol (como en Photon) en vez de un velo gris.
- **Reflejos:** el agua refleja la orilla, los árboles, las nubes y el cielo real del juego (reflejos en espacio de
  pantalla, SSR), con el camino dorado del sol al atardecer, refracción del fondo, cáusticas, color de agua por bioma y la
  "ventana de Snell" mirando hacia arriba bajo el agua. Con Distant Horizons las montañas lejanas también se reflejan.
- **Imagen HDR:** tonemap filmico propio, bloom natural, exposición automática (como el ojo), sol redondo con halo, luna
  con fases, estrellas y Vía Láctea, neblina por altura.
- **Atardeceres más bonitos (nuevo en esta versión):** el horizonte ya no es amarillo limón: dorado-naranja, cielo alto
  rosado, luz cálida desde antes (no solo los últimos minutos), nubes iluminadas por debajo.
- **TAAU + resolución dinámica:** el mundo se dibuja a una escala menor y se reconstruye a Retina completa (3024×1964)
  con antialiasing temporal; la escala se ajusta sola para mantener el objetivo de FPS. La mano y la interfaz siempre van
  a resolución nativa.
- **Arreglos de estabilidad:** se corrigió un error que con la validación de Metal podía colgar la GPU al cambiar de
  pantalla completa a ventana; ahora pasa la prueba de estrés (recarga de recursos, cambio de tamaño, ir al Nether) sin
  ningún error.

**Cómo cambiar el preset** — en la instancia, archivo `config/mcopt.properties`:

```
mcopt.shade=true
mcopt.shade.preset=balanced
```

Valores: `performance`, `balanced` (por defecto), `quality`, `ultra`. Para apagar los shaders: `mcopt.shade=false` (o
quitar la línea); apagado el mod se comporta como mcopt normal.

**FPS por preset** (3024×1964 pantalla completa, la vista más pesada del benchmark: media pantalla de agua con reflejos y
rayos de luz; girando / volando):

| Preset | FPS | Escala de render (dinámica) | Para qué |
|---|---|---|---|
| performance | **139 / 142** | 0.60–0.75 | Máximo margen sobre 120 fps |
| **balanced** (por defecto) | **134–136 / 137–139** | 0.60–0.90 | 120 fps con la mejor nitidez posible |
| quality | **92 / 95** | 0.85–1.0 (nativa) | Nítido, ~90 fps |
| ultra | **66 / 72** | 0.90–1.0 | Todo al máximo, >60 fps |
| Distant Horizons + balanced | **61 / 60** | fija 0.80 | Con DH (antes ~51) |

Comparación: M1 iba a **238 / 212 fps** (sin reflejos, sin rayos, sin HDR); Iris + Complementary a la misma resolución
va a **22 / 21 fps**. M2 balanced es **~6 veces más rápido que Complementary** con agua con reflejos y rayos de luz.

**Problemas conocidos:**

- Con Distant Horizons: el océano lejano de DH es una franja azul oscuro sin sombrear en el horizonte (corte visible con el
  agua cercana), las nubes al atardecer salen naranja plano, y la escala de render es fija (sin resolución dinámica).
  Se arregla en FT2.
- En vistas con mucha agua, balanced baja hasta su mínimo (0.60) y queda en ~125–135 fps; si prefieres margen, usa
  performance.
- Al moverte de lado, las texturas lejanas se suavizan un poco (normal en TAA).
- "Improved Transparency" (transparencia mejorada de vanilla) debe estar **apagada**: con ella el agua y el vidrio no se
  sombrean.
- Las nubes aún no proyectan sombra en el suelo (M3). Mobs y partículas todavía no tienen la iluminación nueva.

**Pruébalo y dime:** ¿balanced o performance en tus mundos? ¿Te gustan los colores del atardecer y la fuerza de los rayos?

## What changed in this release (M2 review round)

- **Stability**: ShadeResources (the argument buffer at slot 19) is a ring of retained buffers like ShadeFrame, so frames
  in flight keep the textures they were encoded with. This removed the GPU address faults that Metal API validation showed
  on every resize (and the macOS "ignored command buffer" storm after them) and a use-after-free of the TAA history.
  `prevScene` has a validity flag (no undefined memory after reallocations); the exposure kernel runs after the final pass.
- **Performance**: VL marches only the live part of its targets (cheaper with the dynamic resolution); waves baked at
  512² on performance/balanced and skipped without translucent terrain; one HDR world target under TAA; balanced's DRS
  floor 0.60. Balanced in the bay view: 120–122 → 134–136 fps (8.2–8.3 → 7.3–7.5 ms GPU).
- **Look**: golden hour (chroma gamma, inset, warm keyframes from 25°, golden sky gain in the sky LUT); real shafts (canopy
  mist near march, carve 0.6, dark crepuscular rays on the sky instead of a veil); haze-matched reflections (no dark cloud
  slabs on sunset water); no milky aureole veil on evening ripples; sparse foam (no white shore outlines); tight sun glare
  in Snell's window; brighter cloud undersides at sunset; rain streaks no longer darker than the sky.

## Flags (system properties, also read from `config/mcopt.properties`)

| Flag | Default | Meaning |
|---|---|---|
| `mcopt.shade` | `false` | The switch |
| `mcopt.shade.preset` | `balanced` | `performance` / `balanced` / `quality` / `ultra` (or p/b/q/u): sets every default below |
| `mcopt.shade.hdr` | `true` | HDR world target + post chain (false: M1's tone curve) |
| `mcopt.shade.hdrFormat` | auto | `rg11b10f` (performance/balanced) or `rgba16f` (quality/ultra) |
| `mcopt.shade.bloom` / `.exposure` / `.ev` | true / auto / 0 | bloom; `fixed` exposure; EV compensation |
| `mcopt.shade.look` | `epic` | `epic` / `storybook` / `natural` grade |
| `mcopt.shade.pcss` / `.stars` / `.haze` / `.light2` | true | PCSS-lite shadows, sky objects, height haze, lighting v2 |
| `mcopt.shade.taa` | `true` | TAA/TAAU + dynamic resolution |
| `mcopt.shade.scale` / `.scaleMin` / `.scaleMax` | preset | render scale start / DRS range (never below 0.60) |
| `mcopt.shade.drs` / `.drsFps` / `.drsLog` | true / preset / false | dynamic resolution, its target fps, a log line per change (and when pinned at the floor) |
| `mcopt.shade.sharpen` / `.taaMode` / `.mipBias` | by scale / preset / true | final-pass sharpen, history mode, texture mip bias |
| `mcopt.shade.water` | `true` | water module (reflections, refraction, caustics, foam) |
| `mcopt.shade.ssr` / `.ssrQuad` / `.ssrSteps` / `.ssrDist` | true / preset / 16-32 / 512 | screen-space reflections |
| `mcopt.shade.ssrPrev` | `true` | reflections read last frame's image (clouds, sky, LODs) |
| `mcopt.shade.waterBake` / `.waterBakeSize` | true / 512 (P,B) 1024 (Q,U) | baked wave texture |
| `mcopt.shade.waterWaves` / `.caustics` / `.waterFoam` / `.waterReflect` / `.glossy` | 1 / true / true / 1 / false | water look |
| `mcopt.shade.vl` | `true` | volumetric light (god rays) |
| `mcopt.shade.vl.scale` / `.steps` / `.nearSteps` / `.ssTaps` / `.dist` | preset | P 0.25/10/6/6/160, B 0.33/12/8/8/160, Q 0.5/16/10/10/224, U 0.5/24/12/12/224 |
| `mcopt.shade.vl.strength` / `.mist` / `.moon` / `.underwater` | 1 / 1 / 1 / true | shaft brightness, canopy mist density, moon shafts, underwater shafts |
| `mcopt.shade.shadowRes` | 2048 (3072 ultra) | shadow map size; M1's shadow flags still apply |
| `mcopt.shade.dhBridge` | `true` | Distant Horizons through the HDR frame |
| `mcopt.shade.debug` / `.vl.debug` / `.taaDebug` | 0 | debug views (see M2_INTEGRATION.md §8) |

## Numbers (3024x1964 exclusive fullscreen, RD 16, M4 14" 10-core GPU, AC power; bench route = 15 s spin over the bay + 15 s flight)

All runs in one session (2026-10-07 01:56–02:22), interleaved: each preset twice (with shots, then perf only in reverse
order). GPU ms/frame = union of command-buffer intervals / frames. The Claude app's renderer shared the GPU (shade-off runs
swing ±0.5 ms).

| Config | Spin fps | Spin 1% low | Flight fps | Flight 1% low | GPU ms (spin / flight) | runs |
|---|---|---|---|---|---|---|
| mcopt, shade off (this jar) | 338–457 | 103–106 | 316–324 | 103–105 | 1.88–2.29 / 2.46–2.53 | fin-off, fin-off2 |
| upstream mcopt jar | 194 | 95 | 374 | 107 | 3.45 / 2.33 (noisy) | fin-up |
| **M1** (mcopt-shade-M1.jar) | 238 | 143–150 | 212–213 | 127–129 | 4.20 / 4.70 | fin-m1, fin-m1b |
| **M2 performance** | 138.8 | 95–97 | 141.7–142.9 | 97–100 | 7.20 / 7.00–7.06 | fin-perf, fin-perf2 |
| **M2 balanced** (default) | 134.1–136.1 | 94 | 137.3–139.0 | 95–105 | 7.34–7.45 / 7.20–7.28 | fin-bal, fin-bal2 |
| **M2 quality** | 92.4 | 65–66 | 95.4–95.8 | 75–77 | 10.82 / 10.45–10.49 | fin-qual, fin-qual2 |
| **M2 ultra** | 65.5–65.8 | 46–47 | 71.7–72.3 | 59–61 | 15.20–15.28 / 13.85–13.97 | fin-ultra, fin-ultra2 |
| M2 balanced + Distant Horizons (DEV-DH, fixed 0.80) | 60.9 | 46.7 | 59.7 | 52.0 | 16.43 / 16.76 | fin-dh |
| M2 balanced before this review round | 120–122 | 85–97 | 123–125 | — | 8.18–8.33 / 7.98–8.10 | ti2/ti3-bal (M2_STATUS) |
| Iris 1.11.7 + Complementary r5.9.3 (OpenGL, M0 baseline) | 22.4 | 14.0 | 21.4 | 10.5 | n/a | baseline-iris-gl-retina |

Result dirs: `work/bench-results/20261007-0156*-fin-val` … `20261007-0220*-fin-dh` (labels `fin-*`).

- **Validation**: `fin-val` ran the stress (resource reload, fullscreen → windowed → fullscreen twice, Nether and back)
  with `MTL_DEBUG_LAYER=1` and the game's stderr captured: no Metal error (the same stress faulted 1–4 times before the fix).
- DRS: performance climbs to 0.75 in light views; balanced spends 0.60–0.90 (it reaches the 0.60 floor in the water
  close-ups, still ≥120 fps); quality and ultra stay at native resolution on the route.
- Shade off vs upstream: equal within the session's noise; no shade line in the log with `mcopt.shade=false`.

## Screenshots (`work/release/M2-screenshots/`)

- `M2_vs_M1_vs_Complementary.jpg`: noon, forest, sunset over water, night torches, rain: M1 | M2 | Iris+Complementary.
- `M2_godrays_showcase.jpg`, `M2_godrays_crop.jpg` (100 % crop of the morning beams): god rays.
- `M2_reflections_showcase.jpg`: sunset reflections, shore at noon and evening, sunset with Distant Horizons.
- `M2_water_vs_Complementary.jpg`: the water poses against Iris+Complementary.
- `M2_review_before_after.jpg`: this review round (sunset colour, cloud reflections, shafts, foam, rain).
- `M2_<scene>.jpg`: every balanced shot at full resolution (standard, water, god rays, motion); `M2_DH_*.jpg`: with DH.

## Licensing

Original code (Apache-2.0 headers on new files; Sodium-derived `sodium_terrain.*.metal` keep their PolyForm Shield
headers). The bloom composite follows the idea of SE Natural Bloom (MIT) and the TAA resolve uses MJP's Catmull-Rom (MIT
style), both credited in NOTICE. No Complementary, BSL, SEUS or current Photon code.
