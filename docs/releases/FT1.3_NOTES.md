# FT1.3 — Agua nueva (estilo Photon) + terreno lejano del atardecer sin puntos ni blanco crema

Jar: `mcopt-shade-FT1.3.jar` (rama `ft1` c4a0a07; SHA-256 en `mcopt-shade-FT1.3.jar.sha256`). Base: FT1.2.

## Instancias nuevas (2026-10-08)
- **"mcopt Shaders Metal 26.3 (FT1.3)"**: sin Distant Horizons.
- **"mcopt Shaders Metal 26.3 (FT1.3 + DH)"**: con Distant Horizons 3.3.4.

Tus mundos no se copian (tu mundo "lero" sigue en "(FT1)").

## Qué cambió en el agua
Pediste un mar bonito y realista, con buena textura y buenos reflejos, como Photon y como tus fotos de referencia.

- **Sin cuadritos.** El agua de M2.1 dividía las ondas cercanas en celdas de 1/16 de bloque, para imitar los píxeles de la
  textura de Minecraft. Cada celda inclinaba el reflejo de golpe, y las nubes y orillas reflejadas se rompían en
  rectangulitos: ese era el "pixelado". Ahora las ondas son continuas.
- **Textura de ondas finas.** El oleaje largo está más calmado y las ondas finas son más fuertes y llegan más lejos (112
  bloques). Rompen el reflejo en vetas y el sol en destellos, como en tus referencias. Hay zonas de agua más calma y más
  movida, y las crestas son curvas para que no se forme una rejilla repetida. Con lluvia, el agua se pica más.
- **Reflejos más nítidos.** Los reflejos de árboles, orillas y nubes ya no salen en bloques de 2×2 píxeles: cada píxel
  calcula su propio punto reflejado.
- **De lejos**, el agua se aplana un poco y refleja como un lago tranquilo.

## Terreno lejano del atardecer: "punteado y blanco raro"
- **Puntos:**
  - La neblina de la distancia se aplica al final, sobre la imagen ya reconstruida a resolución completa. Pero para saber qué
    tan lejos está cada píxel, leía la profundidad a resolución interna (más baja y con un pequeño temblor de antialiasing).
  - En los bordes del terreno contra el cielo, unos píxeles se quedaban sin neblina (puntos oscuros) y otros la recibían de
    más (puntos claros).
  - Ahora, en los bordes, la neblina se mezcla entre los 4 puntos vecinos y el borde sale suave. Los rayos de luz
    (godrays) no tenían que ver: probado apagándolos.
- **Blanco crema:**
  - La neblina mandaba demasiada luz en dirección al sol (dispersión muy dirigida, g = 0.76). Al ras del agua, mirando al
    atardecer, el terreno lejano quedaba casi blanco.
  - Ahora g = 0.5: las islas y montañas conservan forma y color, con un brillo dorado todavía.
- Interruptores: `mcopt.shade.hazeEdge=false` y `mcopt.shade.hazeMieG=0.76` → como antes.

## Rendimiento
Medido con la vista sobre el lago, mucha agua en pantalla y la resolución dinámica apagada, en 3 sesiones con ruido de otras
apps abiertas:
- Entre **+0.2 y +1.0 ms** por cuadro en esa vista. Lo más probable es unos +0.4 ms, de ~85 a ~82 fps.
- Con poca agua en pantalla: casi nada.
- El arreglo de la neblina no lo medí por separado. Debería costar muy poco: lee 3 profundidades más por píxel y solo
  repite el cálculo en los bordes. Se medirá en la fase de optimización.

Lo más caro era llevar las ondas finas a 160 bloques (+0.3 ms). Se dejaron en 112: lejos se ve igual. Al final del
proyecto toca la optimización general.

## Si quieres ajustarlo (`config/mcopt.properties`, quita el `#`)
- `mcopt.shade.water.style=m21` → el agua de antes.
- `mcopt.shade.water.ripple=2.0` → fuerza de las ondas finas (más alto = más picado, más textura).
- `mcopt.shade.water.calm=0.55` → fuerza del oleaje largo (más bajo = más espejo).

Alpha: respalda tus mundos.
