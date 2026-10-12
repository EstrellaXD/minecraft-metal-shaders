# FT1.1 — Arreglo: el cielo se movía al caminar

Jar: `mcopt-shade-FT1.1.jar` (rama `ft1` 124176e; SHA-256 en `mcopt-shade-FT1.1.jar.sha256`). Base: FT1.

## Instancias nuevas (2026-10-08)
- **"mcopt Shaders Metal 26.3 (FT1.1)"**: sin Distant Horizons.
- **"mcopt Shaders Metal 26.3 (FT1.1 + DH)"**: con Distant Horizons 3.3.4 (mismos ajustes de DH que "(FT1 + DH)").

Tus mundos no se copian: tu mundo "lero" sigue en "(FT1)". Si lo quieres jugar con el arreglo, cópialo de
`(FT1)/minecraft/saves/lero` a la misma carpeta de la instancia nueva (o pídemelo).

## El bug
Caminando sobre el suelo (o sobre los árboles), el cielo "daba vueltas": el sol cambiaba de lugar, las nubes se movían o
se volvían una mancha de rayas. Volando no pasaba.

**Causa:** al caminar, Minecraft balancea la cámara (opción "Movimiento de la vista" / *View Bobbing*): la mueve hasta
0.1 bloques. Nuestros shaders calculaban hacia dónde mira cada píxel del cielo usando un punto pegado a la cámara (a 0.05–0.1
bloques) y suponiendo que el ojo estaba quieto en el centro. Con el balanceo, esa dirección se torcía **hasta 87°**.
Volando no hay balanceo, por eso ahí se veía bien. Las pruebas automáticas usan una cámara de espectador que nunca se
balancea, así que no lo detectaban.

**Arreglo:** ahora el cielo, las nubes, la aurora, la lluvia y los rayos de luz miden la dirección desde el ojo real. Lo
mismo para los reflejos del agua, el vidrio y los charcos: de cerca se corrían unos grados al caminar. Error con el
balanceo: de hasta 87° a 0°.

## Cómo se comprobó
- El banco de pruebas ahora puede simular el balanceo de caminar (igual que Minecraft). Capturas y video antes/después:
  `notes/bob-2026-10-08/bob1-antes-despues.png` y `bob-caminando-antes-despues.mp4`.
- Sin balanceo (volando, parado) la imagen es igual a FT1: la diferencia entre FT1 y FT1.1 (6–11 de 255 en promedio) es la
  misma que entre dos corridas del mismo jar (6–9), por las nubes y las olas que se mueven solas.
- Rendimiento: igual (el cambio son unas pocas operaciones por píxel).

Alpha: respalda tus mundos.
