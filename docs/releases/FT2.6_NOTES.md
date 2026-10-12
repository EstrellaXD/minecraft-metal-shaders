# FT2.6 — Arreglo de los bugs de tus capturas de FT2.5

Jar: `mcopt-shade-FT2.6.jar` (rama `ft1` 5faedf7; SHA-256 en `mcopt-shade-FT2.6.jar.sha256`). Base: FT2.5.

## Instancias nuevas (2026-10-09)
- **"mcopt Shaders Metal 26.3 (FT2.6)"**: sin Distant Horizons.
- **"mcopt Shaders Metal 26.3 (FT2.6 + DH)"**: con Distant Horizons 3.3.4.

Tienen tu configuración (de "(FT2.5…)"), pero no tus mundos. Si quieres tu mundo "ft 2_5" ahí, dime y lo copio (copia, el
original no se toca).

## Cómo se probó
Tus 9 capturas se reprodujeron en **tu mismo mundo**: leí la semilla de una copia del archivo de tu mundo "ft 2_5" (sin tocarlo) y
generé un mundo igual en la instancia de pruebas, en tus mismas coordenadas, a la misma altura del sol y a 1080p. Todos los bugs
salieron igual que en tus capturas. Comparativa de tus lugares: `FT2.6-comparacion.jpg` (izquierda FT2.5, derecha FT2.6).

## Qué cambió
- **Halo blanco alrededor del sol sobre las hojas (capturas 1 y 2)**: venía del "aire de bosque" de los godrays (FT2.3). Con la
  cámara dentro de un rayo de sol y mirando al sol, ese aire brillaba muchísimo hacia el sol y tapaba las hojas, con puntitos en los
  bordes. Ahora su brillo hacia el sol es ~4 veces menor (de lado igual que antes). Ya no hay velo ni puntitos.
- **"Humo" y bocanadas blancas sobre los árboles de los cerros (capturas 3, 4, 5 y 7)**: el mismo aire de bosque aparecía también
  sobre las crestas, porque la sombra del propio cerro se contaba como "bosque", y brillaba en bloques de 2 bloques. Ahora el
  shader revisa si arriba de cada punto hay copas de árboles o cielo abierto: sobre crestas y claros grandes ya no pone aire de
  bosque. Los rayos que salen del sol entre los árboles (captura 7) se quedan, más suaves.
  - Medido (luz blanca agregada, promedio en la imagen): escena 4 de 7.3 a ~0.7, escena 7 de 9.0 a ~1.5, escena 1 de 2.4 a ~0.6.
- **Reflejos del lago con manchas blancas (captura 6)**: no eran los godrays. El reflejo (SSR) no encontraba los troncos y hojas
  cuando el rayo pasaba "por detrás" de ellos, y ponía el cielo con nubes. Ahora acepta esos cruces (como Photon) y, si aun así no
  encuentra nada, usa lo que tapó al rayo. Píxeles muy blancos en el bosque reflejado: 0.88 % → 0.05 %. El mar abierto no cambia.
- **Estela blanca del sol dentro de una nube (capturas 8 y 9)**: la reconstrucción de las nubes, cuando la cámara está dentro o muy
  cerca de una nube, movía la imagen vieja como si estuviera a ~1 bloque, así que al moverte o con el viento arrastraba el brillo
  del sol y tardaba ~1 segundo en borrarlo. Ahora, donde eso pasa, usa casi solo la imagen nueva. Video de prueba volando dentro de
  una nube: antes con estela, ahora sin estela. El anillo de colores no lo pude reproducir (en el video nuevo no aparece).
- Interno: el interruptor de pruebas del agua (`mcopt.shade.wdev`) nunca había funcionado; arreglado.

## Costo
Misma sesión, alternando FT2.5 y FT2.6 tres veces (giro del lago, pantalla Retina completa, balanced): +0.005, +0.04 y +0.18 ms =
**dentro del ruido (~0 FPS)**.

## Volver a lo de FT2.5 (argumentos de Java de la instancia)
- Godrays: `-Dmcopt.shade.vl.airGridUp=0 -Dmcopt.shade.vl.airG=0.85 -Dmcopt.shade.vl.airForestGain=1 -Dmcopt.shade.vl.airOpen=1 -Dmcopt.shade.vl.airForest=0.014`
- Agua: `-Dmcopt.shade.water.ssrThick=1 -Dmcopt.shade.water.ssrBehind=false`
- Nubes: `-Dmcopt.shade.clouds.driftClamp=false`
- Rayos más fuertes en los bosques (más brillo, puede volver un poco de velo): `-Dmcopt.shade.vl.airForest=0.02`
