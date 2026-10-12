# FT2.4 — Resolución de salida elegible (como la de un monitor)

Jar: `mcopt-shade-FT2.4.jar` (rama `ft1` 8841e11; SHA-256 en `mcopt-shade-FT2.4.jar.sha256`). Base: FT2.3 (godrays nuevos).

## Instancias nuevas (2026-10-09)
- **"mcopt Shaders Metal 26.3 (FT2.4)"**: sin Distant Horizons.
- **"mcopt Shaders Metal 26.3 (FT2.4 + DH)"**: con Distant Horizons 3.3.4.

Tienen tu configuración, pero no tus mundos. Si quieres uno ahí, dime y lo copio (copia, el original no se toca).

## Qué pedías
"La resolución como la del monitor, no la del reescalado". Después: "que todo se renderice a la resolución que se cambia".

En tu monitor de 1080p el juego iba muy rápido. En la pantalla de la Mac, el "1080p" del menú solo bajaba la resolución del
mundo; todo lo demás seguía dibujándose a 3024×1964.

## Qué hay ahora
En el menú de pausa (Esc), abajo, hay dos botones:
- **"Resolución: …"** (ya existía): cuántos píxeles usa el mundo, relativo a la salida. "Auto" = la que elija el preset.
- **"Salida: …"** (nuevo): Nativa, 1440p, 1200p, 1080p, 900p.

Con "Salida", todo el juego se dibuja a esa resolución, como si cambiaras la del monitor: el mundo, el menú, el texto, la mano
y el HDR de la pantalla. macOS estira la imagen a la pantalla sin costo.
- Se aplica al instante, sin reiniciar, y se guarda.
- F3 dice el tamaño real en "Display" (por ejemplo `Display: 1662x1080`).
- Es 1662 y no 1920 porque la pantalla de la Mac no es 16:9.
- Funciona también con Distant Horizons.

## Números (misma vuelta sobre el lago, pantalla de la Mac completa, preset balanced, Mac conectada)
| Salida | FPS |
|---|---|
| Nativa (3024×1964) | ~80 |
| 1440p (2218×1440) | ~125–130 |
| **1080p (1662×1080)** | **~180** |
| 900p (1386×900) | ~210 |
| (referencia: la ventana a 1920×1080 real) | ~150 |

En el monitor de 1080p viste ~300 FPS en una escena más ligera. En esta vuelta (agua, reflejos, nubes) 1080p da ~180.

## Cómo se ve
- **1080p:** se ve un poco más suave que nativa en la pantalla de la Mac, porque son menos píxeles, igual que un monitor 1080p.
- **El menú y el texto:** también salen a esa resolución, como pediste.
- **Modo avanzado** (`mcopt.shade.outputMode=taau` en `config/mcopt.properties`): baja solo la imagen del mundo y deja el menú
  y el texto nítidos. Da menos FPS: ~145 a 1080p.

## Otros cambios
- Las nubes y la aurora se calculan a una fracción de la salida, no de la pantalla. También bajan su costo con la salida.

Alpha: respalda tus mundos.
