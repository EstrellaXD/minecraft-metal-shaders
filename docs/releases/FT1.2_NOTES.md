# FT1.2 — Orillas de nubes sin escalones + reflejos de nubes estables en los bordes

Jar: `mcopt-shade-FT1.2.jar` (rama `ft1` e39fd3f; SHA-256 en `mcopt-shade-FT1.2.jar.sha256`). Base: FT1.1 (incluye el arreglo
del cielo al caminar).

## Instancias nuevas (2026-10-08)
- **"mcopt Shaders Metal 26.3 (FT1.2)"**: sin Distant Horizons.
- **"mcopt Shaders Metal 26.3 (FT1.2 + DH)"**: con Distant Horizons 3.3.4.

Tus mundos no se copian (tu mundo "lero" sigue en "(FT1)").

## 1. Nubes: orillas pixeleadas (lo que veías en las capturas del atardecer)
- **Qué pasaba:** las nubes se calculan a baja resolución (1 de cada 3×3 píxeles por cuadro) y se reconstruyen con los cuadros
  anteriores. En cada orilla contra el cielo, un "recorte" de seguridad forzaba los píxeles a la caja de colores de sus vecinos
  de baja resolución, igual para cada bloque de 3×3. Eso dejaba escalones de ~7 píxeles y puntitos claros u oscuros.
- **Arreglo:** ese recorte solo se usa donde hace falta, cuando la cámara se mueve rápido o hay nubes cercanas sobre lejanas.
  Para saberlo, se mide cuánto cambiaría la reproyección con la distancia más cercana y la más lejana.
- **Resultado:** puntitos fuertes en las orillas (vista previa, error > 20/255 por 100 mil píxeles de cielo): atardecer
  63 → 4, horizonte 94 → 12, de espaldas al sol 53 → 19, mediodía 90 → 45. En el juego, con las nubes congeladas: orillas
  suaves (`notes/clouds-edge-2026-10-08/ce2-orillas-antes-despues.png`).
- **Rendimiento:** igual. La pasada que cambió (resolve) mide 0.366 → 0.360 ms en el juego (24 mediciones de cada lado) y
  0.000 ± 0.01 ms fuera del juego.

## 2. Agua: reflejos de nubes que se deformaban en los bordes de la pantalla
- **Qué pasaba (dos cosas):**
  - Al girar aparecía una franja diagonal clara cerca del borde. Los rayos reflejados que salían por el costado "chocaban"
    con el fondo del lago. Como el punto no estaba en el cuadro anterior, se pintaba el fondo sin agua encima.
  - A los lados, el cielo reflejado se tapaba estirando la última columna de la pantalla: nubes deformadas que
    aparecían y desaparecían.
- **Arreglo:**
  - Un rayo que sube desde el agua ya no puede chocar con algo más abajo que su punto de partida.
  - A los lados y arriba, el reflejo del cielo pasa suavemente a la "cúpula" de nubes, que se calcula por dirección y no se
    deforma.
  - Las orillas con árboles (el arreglo de M2.1) se ven igual.
- **Video:** `notes/refl-2026-10-08/reflejos-girando-antes-despues.mp4`.

## Interruptores (en `config/mcopt.properties`, por si algo se ve raro)
- `mcopt.shade.clouds.edgeGate=false` → orillas de nubes como antes.
- `mcopt.shade.water.edgeRefl=false` → reflejos en los bordes como antes.

Alpha: respalda tus mundos.
