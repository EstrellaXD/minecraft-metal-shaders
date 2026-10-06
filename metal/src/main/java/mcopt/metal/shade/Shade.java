/*
 * Copyright 2026 mcopt-shade contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package mcopt.metal.shade;

/**
 * mcopt native shading ("Shaders on Metal"): original MSL lighting, shadows and sky inside the Metal backend. Everything
 * is off unless -Dmcopt.shade=true (or mcopt.shade=true in config/mcopt.properties, which Profile turns into the same
 * system property before any of this loads); off, the backend is byte-for-byte upstream: no mixin of ours applies, the
 * registry stays empty, the bind hooks are dead code behind a static final false.
 *
 * <p>Flags (read once at startup):
 * <ul>
 * <li>{@code mcopt.shade} (false): the switch. Needs the Metal backend (not with -Dmcopt.metal=false).</li>
 * <li>{@code mcopt.shade.shadows} (true): sun/moon shadow maps (the shadow pass, mcopt.metal.shade.shadow).</li>
 * <li>{@code mcopt.shade.shadowRes} (2048): shadow map size per cascade.</li>
 * <li>{@code mcopt.shade.shadowDist} (128): shadow distance in blocks.</li>
 * <li>{@code mcopt.shade.debug} (0): 1 shadow visibility and cascades, 2 normals, 3 lighting only (grey albedo).</li>
 * <li>{@code mcopt.shade.sunTilt} (30): sun path tilt in degrees (ShadeSky.SUN_TILT); 0 = vanilla's overhead path.</li>
 * <li>{@code mcopt.shade.dev} (0): feature-off bits for pricing in game (SHADE_DEV_* in shade_common.metal).</li>
 * <li>Shadow pass details (cascades, casters, bias): mcopt.metal.shade.shadow.ShadowConfig.</li>
 * </ul>
 *
 * <p>This class stays free of game types: the mixin plugin loads it while mixins bootstrap.
 *
 * <p>Pieces: ShadeFrame (per-frame uniforms, buffer 16), ShadeFrameUpdater (fills it at LevelRenderer.render HEAD),
 * ShadeBindings (binds buffer 16, texture 16/17, samplers 12/13 on every render encoder; the sky LUT pass), ShadeMsl (the
 * in-jar MSL registered for Sodium terrain and the sky pipelines), the face-shade-off mixin, and the shadow pass.
 */
public final class Shade {
	/** Native shading on: mcopt.shade=true and the Metal backend not turned off. */
	public static final boolean ENABLED = Boolean.getBoolean("mcopt.shade") && Boolean.parseBoolean(System.getProperty("mcopt.metal", "true"));
	/** Sun shadows (mcopt.shade.shadows, default true); the shadow pass's own settings are in shadow.ShadowConfig. */
	public static final boolean SHADOWS = ENABLED && Boolean.parseBoolean(System.getProperty("mcopt.shade.shadows", "true"));
	public static final int DEBUG = Integer.getInteger("mcopt.shade.debug", 0);
	/** Sun path tilt in degrees (mcopt.shade.sunTilt); see ShadeSky.SUN_TILT. */
	public static final float SUN_TILT = ShadeSky.SUN_TILT;

	/** Celestial direction for SkyRenderState angle a, tilted by SUN_TILT; see ShadeSky.celestial. */
	public static void celestial(double angle, double[] out) {
		ShadeSky.celestial(angle, out);
	}

	/** Vertex colours without vanilla's directional face shade (the shaders light faces themselves); see mixin.shade.Sodium*FaceShadeMixin and ShadeFrameUpdater.faceShadeOff. */
	private static volatile boolean faceShadeOff = ENABLED;
	private static boolean initialized;

	private Shade() {
	}

	/** Early setup, from the shade mixin plugin (before the game's classes load): registers the shaded MSL. */
	public static synchronized void init() {
		if (initialized || !ENABLED) return;
		initialized = true;
		ShadeMsl.register();
		System.out.println("[mcopt] shade: native shading on (shadows " + SHADOWS + ", " + mcopt.metal.shade.shadow.ShadowConfig.RES + " px, "
			+ mcopt.metal.shade.shadow.ShadowConfig.DIST + " blocks"
			+ (DEBUG != 0 ? ", debug view " + DEBUG : "") + ")");
	}

	public static boolean faceShadeOff() {
		return faceShadeOff;
	}

	/**
	 * Turns vanilla's baked face shade off (true) or back on, rebuilding every chunk when it changes (the shade is baked
	 * into the vertex colours at meshing time). The terrain shaders assume it off in the overworld.
	 */
	public static void setFaceShadeOff(boolean off) {
		if (faceShadeOff == off) return;
		faceShadeOff = off;
		ShadeFrameUpdater.rebuildChunks();
	}

	/** This frame's uniform values (the shadow pass reads the light direction and writes its matrices with setShadow). */
	public static ShadeFrame frame() {
		return ShadeFrameUpdater.FRAME;
	}
}
