# FT2.8.1 — La aurora ya no salta

Jar: `mcopt-shade-FT2.8.1.jar` (rama `ft1` 41b316d; SHA-256 7c6c28d1de09… en `mcopt-shade-FT2.8.1.jar.sha256`). Base: FT2.8 (ver `FT2.8_NOTES.md`).
Instancias nuevas: **"mcopt Shaders Metal 26.3 (FT2.8.1)"** y **"(FT2.8.1 + DH)"** (con tu configuración de FT2.8, sin mundos).

- **Aurora, cielo del End y niebla del Nether:** usaban el reloj del agua, que vuelve a cero cada 4096 s (68 min). Al agua no le
  pasa nada (sus olas están ajustadas a ese ciclo), pero la aurora y el cielo del End se mueven a otras velocidades, así que una vez
  cada 68 minutos de juego las cortinas de la aurora y los rayos del End cambiaban de forma de golpe. Ahora tienen su propio reloj,
  que vuelve a cero cada 65536 s (~18 horas de juego seguido). Se ven igual que en FT2.8; solo desaparece el salto.
- Rendimiento: igual que FT2.8.
