# FT2.1 — Las nubes y los godrays ya no tiemblan al caminar

Jar: `mcopt-shade-FT2.1.jar` (rama `ft1` 5609e24; SHA-256 en `mcopt-shade-FT2.1.jar.sha256`). Base: FT2.

## Instancias nuevas (2026-10-09)
- **"mcopt Shaders Metal 26.3 (FT2.1)"**: sin Distant Horizons.
- **"mcopt Shaders Metal 26.3 (FT2.1 + DH)"**: con Distant Horizons 3.3.4.

## Qué pasaba
Al caminar, Minecraft balancea la cámara: el ojo sube y baja hasta 0.1 bloque.
- Las nubes se calculan por partes: cada cuadro solo calcula de nuevo 1 de cada 9 píxeles y reacomoda los demás desde el
  cuadro anterior.
- Ese reacomodo usaba el centro de la cámara y no la posición real del ojo. Con el balanceo, cada cuadro las nubes quedaban
  1–1.5 píxeles fuera de lugar y se iban corrigiendo con atraso: "vibraban, como si se movieran con la cámara".
- Se nota más de noche, porque las estrellas (fijas en el cielo) sirven de referencia.

Medido caminando con el balanceo, en el movimiento vertical:
- **antes**, las nubes se movían 81 % de lo que se movía el paisaje (correlación 0.914);
- **ahora**, 98 % (correlación 0.968).

Los godrays tenían el mismo error, y más grande a corta distancia (hasta ~0.5° a 10 bloques): también quedó corregido.

Video: `notes/clouds-walk-2026-10-09/nubes-caminando-zoom.mp4` (de noche en la nieve, antes | después).

Sin costo de rendimiento: es una suma por píxel.

Alpha: respalda tus mundos.
