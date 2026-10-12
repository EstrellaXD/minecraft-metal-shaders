# FT2.3 — Godrays nuevos: la neblina respeta las sombras (idea de Photon) y aire húmedo solo bajo los árboles

Jar: `mcopt-shade-FT2.3.jar` (rama `ft1` a4cc9ad; SHA-256 en `mcopt-shade-FT2.3.jar.sha256`). Base: FT2.2.

## Instancias nuevas (2026-10-09)
- **"mcopt Shaders Metal 26.3 (FT2.3)"**: sin Distant Horizons.
- **"mcopt Shaders Metal 26.3 (FT2.3 + DH)"**: con Distant Horizons 3.3.4.

Tienen tu configuración de "(FT2.2)" (opciones, controles), pero **no tu mundo**: los mundos no se copian solos. Si quieres
"New Worlddasd" ahí, dime y lo copio (copia, el original no se toca).

## Qué pedías
Jugaste FT2.2 en tu bosque de otoño a 1080p:
- "tienen como artefactos, se ven raros";
- "pixeleados, bugueados, parecen manchas; deberían ser líneas de luz continuas como en la vida real";
- "atraviesan el árbol";
- "cópiale el sistema de godrays a otros shaderpacks / o toma sus ideas".

Hoy, viendo las pruebas:
- "no lo optimices tanto";
- "hay un artefacto de luz sobre la parte de arriba del tronco".

## Qué pasaba en FT2.2
- **Columnas de humo:** la neblina de los rayos vivía solo alrededor de la cámara y se cortaba 4 bloques arriba de tus ojos.
  Un haz de luz se veía como humo que sale del suelo.
- **Láminas con borde duro:** un truco de contraste local reforzaba lo que brillaba más que su alrededor. Bajo un hueco
  grande, eso dibujaba "vidrios empañados" con bordes rectos.
- **Velo bajo los árboles:** la neblina normal del juego brillaba al sol aunque estuviera a la sombra de las copas.
- **El "rayo" que cruzaba el tronco:** era una raya dibujada en la pantalla desde la posición del sol. Por eso pasaba por
  encima de los árboles.

## Qué cambió
1. **La neblina respeta las sombras** (la idea de Photon, con su licencia MIT y atribución):
   - la misma neblina que ya tenía la imagen ahora se recorre paso a paso desde tu ojo hasta lo que ves;
   - en cada paso se revisa si ahí da el sol;
   - bajo los árboles el aire a la sombra ya no brilla (más contraste, adiós velo);
   - en campo abierto al sol la imagen queda igual que antes.
2. **Aire húmedo solo bajo las copas** (idea propia, inspirada en el "scene-aware" de Complementary):
   - un mapa 3D chico alrededor de ti (bloques de 2×2×2) mide cuánta sombra hay cerca de cada punto;
   - donde casi todo es sombra (bajo los árboles), el aire es más denso y los huecos de luz se ven como haces;
   - donde hay sol abierto, el aire queda limpio. Eso evita que todo se llene de luz blanca, que fue el primer intento.
3. **La luz se concentra hacia el sol:** de lado casi no hay brillo, así que no hay velo.
4. **Nítido cerca de ti:**
   - el aire húmedo empieza a los 24 bloques;
   - así el haz ya no pasa "por delante" de un tronco cercano (el artefacto que viste en la parte de arriba del tronco).
5. **Sin trucos de pantalla sobre los árboles:** no hay rayas dibujadas, ni blur radial, ni contraste local.
6. **Al atardecer sobre el agua** aparecen rayos de verdad: las sombras de las nubes recortan la bruma.

## Números (tu bosque, mundo aparte con tu semilla, mismas poses que tus capturas)
"Velo" = luz agregada promedio (menos es mejor); "neblina" = índice de neblina de la imagen (menos = imagen más limpia).

| Vista | Velo FT2.2 → **FT2.3** | Neblina FT2.2 → **FT2.3** |
|---|---|---|
| Tronco, 7 am | 3.2 → **0.2** | 64 → **43** |
| Claro, 7 am | 2.0 → **1.0** | 81 → **67** |
| Borde del bosque, 7 am | 4.6 → **3.8** | 81 → **75** |
| Tronco, 9 am | 2.6 → **0.2** | 46 → **34** |
| Claro, 9 am | 5.6 → **0.4** | 73 → **62** |
| Borde del bosque, 9 am | 8.8 → **3.7** | 72 → **67** |
| Tronco, 10:40 am | 2.5 → **0.1** | 42 → **31** |
| Claro, 10:40 am | 7.7 → **0.2** | 70 → **55** |
| Borde del bosque, 10:40 am | 4.6 → **0.1** | 67 → **57** |

**Menos velo que FT2.2 en las 9 vistas**, y la imagen más limpia en todas. Los haces se ven sobre todo:
- en la mañana y la tarde;
- mirando hacia el sol;
- donde las copas son densas.

A media mañana, en tu bosque con árboles separados, son suaves, porque el sol inunda el espacio entre los árboles (en la
vida real pasa igual).

En movimiento no agrega ruido. El parpadeo con la cámara quieta es el mismo que en FT2.2: lo causan las hojas que caen y el
antialiasing.

## Costo
Medido con la Mac conectada, girando sobre el lago a resolución completa:
- **Modo nuevo:** unos +0.55 ms por cuadro, ~4 FPS menos (82–84 contra 87–88 FPS).
- **Dentro del bosque:** la diferencia es menor (el modo anterior ahí hacía pasos extra).
- **Optimización:** no se recortó nada que se viera. Probé menos pasos y me pediste no optimizar tanto, así que quedaron como
  antes. Solo se quedaron dos optimizaciones invisibles.

## Archivos
- Hoja comparativa: `FT2.3-comparacion.jpg` (sin godrays / FT2.2 / FT2.3 en tu bosque).
- Video: `FT2.3-video-paneo.mp4` (izquierda FT2.2, derecha FT2.3; paneo lento en tu bosque a las 7 am).

## Para comparar
Tu instancia "(FT2.2)" sigue igual. Ajustes avanzados en `config/mcopt.properties` o como argumento de Java `-D...`:
- `mcopt.shade.vl.airForest` (0.014): densidad del aire bajo los árboles. Más = haces más fuertes, pero más brillo hacia el sol.
- `mcopt.shade.vl.airNear` (24): a cuántos bloques de ti empieza ese aire.
- `mcopt.shade.vl.air=0`: apaga el modo nuevo.

Alpha: respalda tus mundos.
