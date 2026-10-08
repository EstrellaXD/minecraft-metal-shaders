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
package mcopt.metal.shade.dh;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * FT1: Distant Horizons' own screen effects while the shade frame runs. DH draws its LODs into our HDR frame through
 * ShadePost's bridge; on top of that it runs its own SSAO (two full-screen passes), its own TAA (a full-screen pass plus
 * a history copy) and its "vanilla fade" (a full-screen pass that samples main colour): ~10 ms on the M4 at Retina with DH
 * at 40 chunks (run ft1-0-before: ~50 fps against ~97 without DH). Our frame already has its own AA (TAAU) and lighting,
 * so while it runs those are switched off through DH's config API overrides (values DH holds in memory only: its
 * DistantHorizons.toml and its settings screen keep the player's choice), and the overrides are cleared when it stops
 * (fallback, shading off), which gives DH its own settings back.
 * <p>
 * Live flags, read every frame (the bench's in-session A/B sets them): {@code mcopt.shade.dh.ssao}, {@code mcopt.shade.dh.aa}
 * = "off" (default) or "dh" (DH's own setting); {@code mcopt.shade.dh.fade} = "NONE" (default), "SINGLE_PASS",
 * "DOUBLE_PASS" or "dh". Reflection on DH's internal config entries (Config.Client.Advanced.Graphics; DH 3.3.3-3.3.4, the
 * versions the Metal path is gated to): no compile-time dependency on DH (LGPL, shipped unmodified). Any failure turns
 * this off for the session and leaves DH's settings alone.
 */
public final class ShadeDh {
	public static final boolean LOADED = mcopt.metal.DhCompat.loaded();
	private static final String CFG = "com.seibel.distanthorizons.core.config.Config$Client$Advanced$Graphics";
	private static final String USER = "mcopt-shade";

	/** DH's config entries (ConfigEntry), its setApiValue(Object, String), and the fade-mode enum. */
	private static Object ssao, aa, fade;
	private static Method setApi;
	private static Class<?> fadeEnum;
	private static boolean failed, resolved;
	/** What was last applied (null = no override). */
	private static Object appliedSsao, appliedAa, appliedFade;
	private static boolean logged;

	private ShadeDh() {
	}

	/** Once per level frame. shading: the HDR shade frame draws this frame (not fallback). */
	public static void frame(boolean shading) {
		if (!LOADED || failed) return;
		if (!resolved && !resolve()) return;
		Object wantSsao = shading ? off("mcopt.shade.dh.ssao") : null;
		Object wantAa = shading ? off("mcopt.shade.dh.aa") : null;
		Object wantFade = shading ? fadeMode() : null;
		try {
			if (wantSsao != appliedSsao) setApi.invoke(ssao, appliedSsao = wantSsao, USER);
			if (wantAa != appliedAa) setApi.invoke(aa, appliedAa = wantAa, USER);
			if (wantFade != appliedFade) setApi.invoke(fade, appliedFade = wantFade, USER);
		} catch (Throwable t) {
			fail(t);
			return;
		}
		if (!logged && shading) {
			logged = true;
			System.out.println("[mcopt] shade dh: Distant Horizons' SSAO " + state(appliedSsao) + ", anti-aliasing " + state(appliedAa) + ", vanilla fade "
				+ (appliedFade == null ? "DH's setting" : appliedFade) + " while the shade frame runs");
		}
	}

	private static String state(Object v) {
		return v == null ? "DH's setting" : "off";
	}

	/** Boolean.FALSE (override off) unless the flag says "dh". */
	private static Object off(String key) {
		return "dh".equalsIgnoreCase(System.getProperty(key, "off")) ? null : Boolean.FALSE;
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static Object fadeMode() {
		String v = System.getProperty("mcopt.shade.dh.fade", "NONE");
		if ("dh".equalsIgnoreCase(v)) return null;
		try {
			return Enum.valueOf((Class<? extends Enum>) fadeEnum, v.toUpperCase(java.util.Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return Enum.valueOf((Class<? extends Enum>) fadeEnum, "NONE");
		}
	}

	private static boolean resolve() {
		try {
			ClassLoader cl = ShadeDh.class.getClassLoader();
			Class<?> graphics = Class.forName(CFG, true, cl);
			Class<?> quality = Class.forName(CFG + "$Quality", true, cl);
			Field fs = graphics.getField("enableSsao"), fa = graphics.getField("enableAntiAliasing"), ff = quality.getField("vanillaFadeMode");
			ssao = fs.get(null);
			aa = fa.get(null);
			fade = ff.get(null);
			if (ssao == null || aa == null || fade == null) return false; // DH's config not built yet: next frame
			setApi = ssao.getClass().getMethod("setApiValue", Object.class, String.class);
			fadeEnum = Class.forName("com.seibel.distanthorizons.api.enums.config.EDhApiMcRenderingFadeMode", true, cl);
			resolved = true;
			return true;
		} catch (Throwable t) {
			fail(t);
			return false;
		}
	}

	private static void fail(Throwable t) {
		failed = true;
		System.out.println("[mcopt] shade dh: could not switch Distant Horizons' SSAO / anti-aliasing / fade (" + t + "); its own settings stay");
	}
}
