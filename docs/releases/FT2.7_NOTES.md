# FT2.7 — Revisión de código: godrays de vuelta en el bosque, reflejos más limpios, relámpagos y más FPS

Jar: `mcopt-shade-FT2.7.jar` (rama `ft1` ba7432e; SHA-256 en `mcopt-shade-FT2.7.jar.sha256`). Base: FT2.6.1. Hoja comparativa: `FT2.7-comparacion.jpg` (FT2.6.1 a la izquierda, FT2.7 a la derecha).
Instancias nuevas: **"mcopt Shaders Metal 26.3 (FT2.7)"** y **"(FT2.7 + DH)"** (con tu configuración de FT2.6.1, sin mundos).

## Lo que vas a notar

- **Godrays en bosques de robles y abedules (otoño):** en FT2.6 la prueba que separa "bosque" de "campo abierto" miraba una caja
  de 4 a 16 bloques sobre cada punto. Bajo árboles bajos (copas a 3–7 bloques) casi toda la caja quedaba arriba de las hojas, al sol,
  y el bosque de otoño perdía sus haces. Ahora mira **justo encima** de cada punto y solo cuenta las hojas que están a menos de
  12 bloques en dirección al sol (no la sombra de un árbol lejano al atardecer). Se probaron 13 variantes en 4 rondas, en tu taiga
  (6 lugares de tus capturas) y en tu bosque de otoño. Resultado:
  - bosque de otoño: el haz entre el árbol amarillo y el rojo regresa (estructura de rayos 1.83 → 1.97; FT2.5 tenía 1.94);
  - tu taiga: **menos velo que FT2.6** (p3: 0.6 → 0.05, la bocanada blanca sobre la copa del pino en la cresta desaparece;
    p4: 0.7 → 0.2; p1: 0.85 → 0.15). En p7 casi desaparecen las columnas blancas onduladas ("humo") de la cresta.
- **Reflejos del agua menos lechosos:** a lo que se refleja (orillas, islas, árboles lejanos) se le ponía la neblina dos veces:
  una desde tu cámara hasta el objeto y otra encima del agua. Ahora solo del agua al objeto: los reflejos lejanos se ven tan
  nítidos como los objetos mismos.
- **Charcos y piso mojado:** el mismo arreglo de manchas blancas que se le hizo al lago en FT2.6 (rayos que pasaban detrás de
  troncos caían al cielo brillante), y la misma neblina sin contar doble. (No lo pude ver en tu taiga nevada: ahí no se forman
  charcos; si ves algo raro en charcos después de llover, mándame captura.)
- **Relámpagos:** antes el destello se dibujaba detrás de las nubes de tormenta (el suelo se iluminaba, el cielo se quedaba oscuro) y
  los mobs, cofres, jugadores y los LODs de DH no se iluminaban. Ahora el techo de nubes brilla con cada rayo y todo lo de afuera
  se ilumina (`FT2.7-relampago.jpg`: la misma toma 0.12 s después del rayo, FT2.6.1 a la izquierda; brillo del cielo 128 → 170).
- **Sombras de nubes al atardecer:** se calculaban con 6 rebanadas a lo largo de hasta 800 bloques → sombras (y rayos entre nubes)
  escalonadas, como copias desplazadas. Ahora ~1 rebanada cada 36 bloques (máximo 24); al mediodía igual que antes.
- **Neblina con lluvia:** al pasar la lluvia por la mitad, la neblina se oscurecía de golpe. Ahora cambia suave.
- **Bloom a 1080** (tu monitor, o "Salida" 1080): el resplandor salía 1.8× más ancho que en la pantalla Retina, porque se ajustó ahí.
  Ahora ocupa la misma proporción de la pantalla en cualquier resolución (cambio sutil, un poco menos de velo).
- **Al girar la cámara:** el TAA perdía el historial del cielo, el sol y los LODs de DH en los costados de la pantalla (se veían
  suaves y "temblorosos" mientras girabas). Arreglado. Los rayos oscuros del cielo ya no se quedan un cuadro atrás al girar.
- **Con DH, en una montaña alta:** una banda de nubes lejanas se pintaba encima de las montañas LOD justo bajo el horizonte y
  aparecía de golpe al subir. Arreglado.
- **XDR** con la salida en modo "taau" a menos de nativa: se perdían los brillos. Arreglado (el modo normal no tenía el problema).

## Rendimiento

- En el cielo, los godrays hacían 20 pasos cuyo resultado se multiplicaba por 0: ahora se cortan donde ya no aportan (misma imagen).
- El TAAU revisaba en cada píxel todos los animales en movimiento a 64 bloques, aunque estuvieran detrás de ti: ahora solo los que
  están en pantalla (misma imagen).
- Otros dos ahorros pequeños exactos en los godrays.
- Medido (Retina 3024×1964, balanced, giro sobre el lago, A/B ×3 en la misma sesión): **−0.22 ms por cuadro en los 3 pares**
  (≈ 82.6 → 83.8 fps). Eso cubre el corte del cielo, la prueba de copas nueva y las sombras de nubes; lo de los animales en
  pantalla ahorra aparte donde hay mobs moviéndose (el recorrido de prueba no tiene).

## No incluido (para después)

- Auto-exposición al salir de cuevas (se adapta lento a lo brillante): es cuestión de gusto (más rápido = el brillo "bombea" al
  mirar el cielo). Te lo enseño con video para que elijas.
- Un borde brillante en hojas cercanas mirando al sol muy bajo (necesita pruebas al atardecer).
- Sombras de nubes más lejos para DH (hoy terminan en un cuadrado de ~470 bloques).
