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
package mcopt.metal.shade.shadow;

import java.util.Map;
import mcopt.metal.MetalDrawTap;

/**
 * M3 (bible E08 "entity shadows", M3c): entities, block entities, dropped items, armour and moving blocks cast sun shadows.
 * <ol>
 * <li>Record: during the HDR world passes, MetalDrawTap reports every quad draw; the caster pipelines' draws (their vertex
 * buffer range, quad count, stride, uv offset and texture) are kept natively (mc_shadow_entity_record).</li>
 * <li>Commit (ShadePost.beforeHand, the end of the world): the recorded vertices are copied by one blit into the shadow
 * pass's own buffer (the game's vertex buffers are pooled per frame).</li>
 * <li>Cast: the next frame's near cascade draws them after the terrain (shadow.metal shadow_entity_vs/fs: depth only, the
 * texture's alpha tested, shifted from the camera they were recorded with onto this frame's).</li>
 * </ol>
 * One frame of latency (invisible at entity speeds); only the near cascade (receivers further away keep the terrain's
 * shadows). mcopt.shade.entityShadows=false turns it off.
 */
public final class EntityCasters {
	public static final boolean ON = mcopt.metal.shade.Shade.SHADOWS && mcopt.metal.shade.ShadeConfig.HDR
		&& Boolean.parseBoolean(System.getProperty("mcopt.shade.entityShadows", "true"));

	/** Pipelines that cast, with their alpha cutoff (the game's own: 0.1 for cutouts; 0.5 for translucent ones). */
	static final Map<String, Float> CASTERS = Map.ofEntries(
		Map.entry("minecraft:pipeline/entity_solid", 0.1f), Map.entry("minecraft:pipeline/entity_solid_offset_forward", 0.1f),
		Map.entry("minecraft:pipeline/entity_solid_glint", 0.1f),
		Map.entry("minecraft:pipeline/entity_cutout", 0.1f), Map.entry("minecraft:pipeline/entity_cutout_cull", 0.1f),
		Map.entry("minecraft:pipeline/entity_cutout_z_offset", 0.1f), Map.entry("minecraft:pipeline/entity_cutout_dissolve", 0.1f),
		Map.entry("minecraft:pipeline/entity_translucent", 0.5f), Map.entry("minecraft:pipeline/entity_translucent_cull", 0.5f),
		Map.entry("minecraft:pipeline/armor_cutout_no_cull", 0.1f), Map.entry("minecraft:pipeline/armor_cutout_no_cull_glint", 0.1f),
		Map.entry("minecraft:pipeline/item_cutout", 0.1f), Map.entry("minecraft:pipeline/item_translucent", 0.5f),
		Map.entry("minecraft:pipeline/item_translucent_glint", 0.5f),
		Map.entry("minecraft:pipeline/solid_block", 0.1f), Map.entry("minecraft:pipeline/cutout_block", 0.5f));

	private static long shadow;
	private static int recorded, dropped;
	private static boolean installed;

	private EntityCasters() {
	}

	private static final MetalDrawTap.Listener TAP = (pipeline, vertexBuffer, firstVertex, quads, stride, uvOffset, texture) -> {
		Float cutoff = CASTERS.get(pipeline);
		long s = shadow;
		if (cutoff == null || s == 0 || quads <= 0 || texture == 0) return;
		if (ShadowNative.entityRecord(s, vertexBuffer, firstVertex, quads, stride, uvOffset, texture, cutoff) == 0) recorded++;
		else dropped++;
	};

	/** The shadow pass made its native state (ShadowPass.create): start listening. */
	static void attach(long nativeShadow) {
		if (!ON) return;
		shadow = nativeShadow;
		if (!installed) {
			installed = true;
			MetalDrawTap.listener = TAP;
		}
	}

	static void detach() {
		shadow = 0;
		if (installed) {
			installed = false;
			MetalDrawTap.listener = null;
		}
	}

	/** Before this frame's world passes: drop whatever a frame that never reached its commit recorded. */
	static void beginFrame() {
		if (shadow != 0 && recorded + dropped > 0) ShadowNative.entityReset(shadow);
		recorded = dropped = 0;
	}

	// the camera the committed casters were recorded with, and their frame
	private static double camX, camY, camZ;
	private static long committedFrame = -10;

	/** The near cascade is about to draw (frame `frame`, camera x, y, z): shift last frame's casters onto it. */
	static void prepareCast(long frame, double x, double y, double z) {
		if (shadow == 0) return;
		boolean stale = committedFrame != frame - 1;
		double dx = camX - x, dy = camY - y, dz = camZ - z;
		if (dx * dx + dy * dy + dz * dz > 64 * 64) stale = true; // a teleport: they'd land in the wrong place
		ShadowNative.entityShift(shadow, (float) dx, (float) dy, (float) dz, stale ? 1 : 0);
	}

	/** End of the world (ShadePost.beforeHand): copy this frame's recorded casters for the next frame's near cascade. */
	public static void commit(long enc) {
		ShadowPass p = ShadowPass.instance();
		if (shadow == 0 || p == null) return;
		ShadowNative.entityCommit(enc, shadow);
		camX = p.camX();
		camY = p.camY();
		camZ = p.camZ();
		committedFrame = p.frame();
		recorded = dropped = 0;
	}

	/** Casters drawn into this frame's near cascade (for logs and tests). */
	public static int count() {
		return shadow == 0 ? 0 : ShadowNative.entityCount(shadow);
	}
}
