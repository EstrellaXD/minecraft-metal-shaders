# FT2.6.1 — Dos arreglos de la revisión de código sobre FT2.6

Jar: `mcopt-shade-FT2.6.1.jar` (rama `ft1` a11f02a; SHA-256 en `mcopt-shade-FT2.6.1.jar.sha256`). Base: FT2.6.
Instancias nuevas: **"mcopt Shaders Metal 26.3 (FT2.6.1)"** y **"(FT2.6.1 + DH)"** (con tu configuración de FT2.6, sin mundos).

## Qué cambió
- **Nubes con viento fuerte**: el arreglo de la estela del sol de FT2.6 también se activaba con el viento normal y al moverte, y
  podía volver granulados (en bloques) los bordes de las nubes en días de viento o tormenta. Ahora solo actúa cerca de la cámara
  (dentro o al lado de una nube) o junto al sol. Medido con viento ×3: el ruido de los bordes es igual que sin el arreglo; dentro de la
  nube sigue sin estela.
- **Reflejos del agua al borde de la pantalla**: el respaldo nuevo del reflejo ("lo que tapó al rayo") no tenía los desvanecidos que
  evitan que un tronco u orilla pegados al borde de la pantalla se embarren de lado sobre el agua al girar. Ahora sí. El lago de tu
  captura sigue sin manchas blancas (0.85 % → 0.05 %) y el mar abierto no cambia.

Los dos los encontró la revisión de código con varios agentes (detalle en `work/notes/audit-2026-10-09/`). Costo: ninguno medible.
