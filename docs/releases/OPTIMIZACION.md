# Optimización de los shaders (M3.1, 2026-10-08)

Para Gael: qué técnicas existen para que el juego corra más rápido sin verse peor, cuáles ya usamos y cuáles siguen.
Los números son de la Mac M4 a 3024×1964, preset balanced (ms de GPU por cuadro: menos es mejor; 8.3 ms ≈ 120 fps).

## Ya aplicadas

| Técnica | Qué hace | Dónde |
|---|---|---|
| **Culling por vista (frustum)** | No dibuja lo que queda fuera de la cámara | Sodium (terreno), Minecraft (mobs, partículas) |
| **Culling por oclusión** | No dibuja secciones de terreno tapadas por otras (cuevas, detrás de montañas) | Sodium (grafo de visibilidad) + mcopt (oclusión en GPU, "pulled twin") |
| **Culling de sombras** | El mapa de sombras solo dibuja lo que puede proyectar sombra dentro de la vista | `ShadowCasters` (por cascada, por la luz) |
| **Cascadas de sombra escalonadas** | La cascada lejana se actualiza cada 4 cuadros | M1 |
| **Escalado temporal (TAAU)** | El mundo se dibuja a menos resolución y se reconstruye a la completa usando cuadros anteriores | M2 (`ShadeTaa`) |
| **Resolución dinámica** | Baja/sube la resolución del mundo para mantener los FPS | M2 (preset "Auto") |
| **Resolución elegible en pausa** | Tú eliges: Nativa, 1800p, 1600p, 1440p, 1200p, 1080p o Auto | M3.1 (botón en el menú de pausa) |
| **Variantes "lean" del terreno** | El shader del terreno sin el código que no se usa en ese momento (lluvia, Nether, luces) | M3.1 (`ShadeLean`): −2.1 ms girando |
| **Medio-resolución / tablero** | Godrays y nubes se calculan en menos píxeles (las nubes 1 de cada 9 por cuadro) y se reconstruyen | M2, M3 |
| **Saltar trabajo invisible** | Sin búsqueda de sombra donde no llega el sol; lluvia sin brillo del sol en tormenta; charcos sin reflejo a los pies | M1–M3.1 |
| **LUT del cielo** | El cielo se calcula una vez en una tabla chica (10 mil píxeles) en vez de en 6 millones | M1 |
| **Precompilar** | Los shaders se compilan al arrancar en otro hilo (sin tirones al cambiar de variante o al ver la mano) | M3, M3.1 |

## Siguientes (de más a menos ganancia esperada)

1. **Nubes más baratas** (~1.0 ms hoy, meta ≤0.5): un agente está en eso (pases que se pueden juntar, lecturas de sombra
   de nubes más baratas, niebla de nubes a menor resolución).
2. **Salida XDR** (~0.15–0.3 ms): el pase extra de pantalla completa solo cuando hay algo más brillante que el blanco
   (sol, lava, antorchas en pantalla).
3. ~~Aurora como variante "lean" del cielo~~: probado (M3.1, `mcopt.shade.leanSky`): no ahorra nada medible.
4. **Generación de cuadros (MetalFX Frame Interpolation, ya existe en mcopt, apagada)**: inventa un cuadro entre cada
   dos: casi el doble de FPS percibidos, a cambio de un poco de retraso al mover el mouse. Se podría poner como opción
   en el menú de pausa.
5. **Sombreado de tasa variable** (Metal "rasterization rate maps"): menos detalle en las orillas de la pantalla, donde
   casi no se nota. Complejo con el TAAU; para más adelante.
6. **Distant Horizons con nuestra luz (FT1)**: dibuja el terreno lejano mucho más barato que los chunks normales, y
   permitiría bajar la distancia de renderizado de chunks sin perder horizonte.

## En batería

En batería macOS baja la velocidad de la GPU: los FPS bajan aunque nada cambie (medido: hasta ~1 ms por cuadro). En
"Auto" la resolución dinámica lo compensa bajando la resolución del mundo (llega a 0.6 de la pantalla); con una
resolución fija (por ejemplo 1440p) los FPS bajan en vez de la nitidez. Para jugar en batería: "Auto" o 1440p/1200p.
