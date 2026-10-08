/*
 * Copyright 2026 mcopt-shade contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0. Unless required by applicable
 * law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
 * OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package mcopt.metal.shade;

/**
 * M3.1: which module code this frame's terrain can do without. The M3 modules put their terrain code behind runtime
 * branches (the Nether / End lighting, the wet surfaces, the dynamic lights), and that code weighs on every terrain pixel
 * even where it never runs (registers, occupancy: ~1 ms at balanced, measured 2026-10-08). The world pass therefore draws
 * with a tagged HDR format (ShadeMsl.LEAN_*), and the Sodium terrain pipelines get one variant per tag with that code
 * compiled out (sodium_terrain.fs.metal SHADE_LEAN_OW / _DRY / _NODL); every other pipeline ignores the tag
 * (ShadeMsl canonical). The eight variants are built on the compile worker as the terrain pipelines compile (prewarm),
 * so switching (rain starts, a torch comes into the hand) does not stall a frame.
 */
public final class ShadeLean {
	/** mcopt.shade.lean (true): false draws every frame with the full terrain (M3.0). */
	public static final boolean ON = ShadeConfig.flag("mcopt.shade.lean", true);
	/** mcopt.shade.leanSky (true): the sky's variant without the aurora code while no aurora shows (LEAN_NOAURORA). */
	public static final boolean SKY = ShadeConfig.flag("mcopt.shade.leanSky", true);
	private static volatile int tag;

	private ShadeLean() {
	}

	/** This frame's lean tag (ShadeMsl.LEAN_OW | LEAN_DRY | LEAN_NODL), 0 when off. */
	public static int tag() {
		return (ON ? tag & ShadeMsl.LEAN_TERRAIN : 0) | (SKY ? tag & ShadeMsl.LEAN_NOAURORA : 0);
	}

	/** Once per frame, after the weather and the dynamic lights updated, before the world pass (ShadeFrameUpdater). */
	static void update(int dimension) {
		int t = 0;
		if (dimension == ShadeFrame.DIM_OVERWORLD) t |= ShadeMsl.LEAN_OW;
		if (!mcopt.metal.shade.weather.ShadeWeather.ON || mcopt.metal.shade.weather.ShadeWeather.dry()) t |= ShadeMsl.LEAN_DRY;
		if (!mcopt.metal.shade.light.DynLights.ON || mcopt.metal.shade.light.DynLights.activeCount() == 0) t |= ShadeMsl.LEAN_NODL;
		if (mcopt.metal.shade.dims.DimsConfig.AURORA == 0 || mcopt.metal.shade.dims.ShadeDims.aurora() <= 0.002f) t |= ShadeMsl.LEAN_NOAURORA;
		tag = t;
	}
}
