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
package mcopt.metal.shade.terrain;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.invoke.MethodHandle;
import mcopt.metal.MetalBridge;
import mcopt.metal.shade.Shade;
import mcopt.metal.shade.ShadeMsl;
import mcopt.metal.shade.taa.ShadeTaa;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * mcopt 0.3 port (v12): native shading on mcopt's own near-terrain renderer (mcopt.metal.own), which draws the world's
 * terrain itself since mcopt 0.3 (no Sodium). The hooks it calls, each one line in upstream's code behind {@link #ON}:
 * <ul>
 * <li>{@link #prefix} (OwnTerrain's constructor): what goes in front of own/terrain.metal: ShadeMsl.ownPrefix. With
 * MCOPT_SHADE, terrain.metal's fragment stages bind the game's uniforms above the shade globals' slots and turn their colour
 * into the HDR world target's value (SHADE_OWN_HDR).</li>
 * <li>{@link #created} (right after the library is built): tells mcown.m so (mco_shade): it then binds those slots and
 * specializes the pipelines that draw into the HDR target.</li>
 * <li>{@link #beforeTranslucent} (OwnTerrain.drawTranslucent): the water module's scene copy, which Sodium's terrain used to
 * trigger by switching to its translucent pipeline (MetalRenderPass.captureWater).</li>
 * <li>{@link #noUocc} (OwnFrag's per-frame configuration): its two-phase occlusion off while TAAU draws the world into a
 * smaller live sub-rect of its targets, whose depth the occlusion test would read at the wrong texels (port plan P5).</li>
 * </ul>
 * P1 (this file's first version): vanilla's look in the HDR frame. The lighting moves in with P2 (port-mcopt-0.3.md).
 */
public final class ShadeTerrain {
	/** The gate for every hook: a static final, so with shading off the hooks are dead code. */
	public static final boolean ON = Shade.ENABLED;
	/**
	 * The terrain fragment stage lights faces itself (port step P2), so meshing leaves vanilla's directional face shade out
	 * (ShadeFaceShadeMixin, applied only then). False in P1: vanilla's look, baked shade included.
	 */
	public static final boolean LIT = false;
	private static boolean said;

	private ShadeTerrain() {
	}

	/** What goes in front of own/terrain.metal (and of OwnTerrain's own defines) when shading is on. */
	public static String prefix() {
		return ShadeMsl.ownPrefix();
	}

	/** OwnTerrain's constructor, right after OwnNative.create built the library with {@link #prefix} (own: the native Own*). */
	public static void created(long own) {
		try {
			MethodHandle shade = Linker.nativeLinker().downcallHandle(
				MetalBridge.library().find("mco_shade").orElseThrow(() -> new IllegalStateException("missing native symbol mco_shade")),
				FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_INT));
			shade.invokeExact(own, 1);
		} catch (Throwable t) {
			throw new IllegalStateException("mcopt-shade: own terrain's shade slots", t);
		}
		if (!said) {
			said = true;
			System.out.println("[mcopt] shade: mcopt's own terrain renderer draws into the shaded frame (vanilla look until its lighting is ported)");
		}
	}

	/** OwnTerrain.drawTranslucent, before its first draw: the water module's scene copy (once per pass). */
	public static void beforeTranslucent(Object encoder) {
		MetalBridge.captureWater(encoder);
	}

	/**
	 * OwnFrag's configuration this frame: true to draw it without the two-phase unit occlusion (uocc), while TAAU's world target
	 * stands in for the main target with a live sub-rect smaller than its textures. The test maps units to the pass's full
	 * depth size and its pyramid is built from all of it, while this frame's depth is only in the sub-rect (P5 makes the test
	 * follow the sub-rect).
	 */
	public static boolean noUocc() {
		if (!ShadeTaa.ON) return false;
		ShadeTaa t = ShadeTaa.current();
		return t != null && t.swapped() && (t.width(Integer.MAX_VALUE) < t.allocWidth() || t.height(Integer.MAX_VALUE) < t.allocHeight());
	}
}
