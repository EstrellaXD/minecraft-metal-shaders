# FT2.2 — Godrays que se ven entre los árboles, sin velo

Jar: `mcopt-shade-FT2.2.jar` (rama `ft1` 77a0e06; SHA-256 en `mcopt-shade-FT2.2.jar.sha256`). Base: FT2.1.

## Instancias nuevas (2026-10-09)
- **"mcopt Shaders Metal 26.3 (FT2.2)"**: sin Distant Horizons.
- **"mcopt Shaders Metal 26.3 (FT2.2 + DH)"**: con Distant Horizons 3.3.4.

## Qué pedías
1. (00:55) Los godrays "se ven poquito" y hacían "puntitos" en las hojas.
2. (después de ver la primera versión) Con rayos más fuertes la imagen se veía "opaca, llena de luz, como un lente empañado".
   Querías ver los rayos "pero que la pantalla no se vuelva blanca".

## Qué pasaba
- La primera versión (la que viste) subía ~2.25 veces la luz de la neblina bajo los árboles. Pero esa luz no es solo rayos:
  cuando el sol pasa por debajo de las copas (mañana, tarde) TODO el aire cerca de ti queda iluminado y brilla parejo. Más
  luz = más velo, no más rayos. Medido: el "índice de neblina" de la imagen subió de 55 a 64 (bosque de lado) y de 84 a 93
  (hacia el sol); con el sol bajo de lado, 35 % de la imagen quedaba lavada.
- Ningún ajuste de un píxel solo distingue un rayo de un brillo parejo (los dos son aire iluminado).

## Qué cambió
- **Contraste local:** cada punto de la luz se compara con el promedio de su alrededor (un mapa borroso muy chico, a 1/4 de
  la resolución de los rayos). Lo parejo (el velo) se queda en 20 %; lo que brilla más que su alrededor (un rayo) se refuerza
  hasta 1.8×; el aire más oscuro que su alrededor se apaga. Resultado: franjas de luz con huecos oscuros entre ellas.
- Solo bajo árboles (en campo abierto, el lago y el atardecer quedan igual que antes).
- Un "limitador" suave para el brillo más fuerte hacia el sol (hojas a contraluz que se volvían beige).
- Se queda el arreglo de los **puntitos** en las hojas.

## Números (capturas fijas, misma pose y hora; "velo" = luz agregada promedio, "rayos" = cuánto varía esa luz)
| Vista | Velo: FT2.1 → la que viste → **FT2.2** | Rayos: FT2.1 → la que viste → **FT2.2** | Índice de neblina de la imagen |
|---|---|---|---|
| Bosque hacia el sol (mañana) | 10.9 → 20.8 → **7.2** | 2.78 → 4.00 → **3.27** | 83.5 → 93.2 → **78.9** |
| Bosque de lado | 4.3 → 12.6 → **3.5** | 1.57 → 3.03 → **1.64** | 55.1 → 64.2 → **53.6** |
| Sol bajo, de lado | 15.3 → 29.7 → **10.5** | 3.02 → 4.89 → **3.24** | 65.3 → 79.1 → **60.3** |
| Sol a la espalda | 6.4 → 11.6 → **6.0** | 1.49 → 2.29 → **1.56** | 48.5 → 52.5 → **48.0** |

FT2.2 tiene MENOS velo que FT2.1 en todas las vistas del bosque (la imagen queda más limpia que antes) y los rayos se
distinguen mejor (rayos ÷ velo: hacia el sol 0.26 → 0.46, de lado 0.36 → 0.47, sol bajo 0.20 → 0.31). El atardecer, el lago y
la saliente de roca quedan igual. Con la cámara quieta parpadea igual que FT2.1 (un poquito más justo en el borde de los rayos).

Hoja: `notes/godrays-veil-2026-10-09/godrays-sin-velo.jpg`. Video caminando en el bosque con el sol bajo:
`notes/godrays-veil-2026-10-09/godrays-bosque-caminando-sin-velo.mp4` (izquierda FT2.1, derecha FT2.2).

## Costo
En campo abierto, 0 (medido: +0.04 / −0.04 / −0.03 ms = ruido). Bajo árboles, ~0.1 ms (tres pasadas chiquitas a 1/4 de la
resolución de los rayos): ~1 FPS a 85 FPS.

## Para comparar
`mcopt.shade.vl.look=ft21` (en `config/mcopt.properties` o como argumento de Java `-D...`) vuelve al look de FT2.1.

Alpha: respalda tus mundos.
