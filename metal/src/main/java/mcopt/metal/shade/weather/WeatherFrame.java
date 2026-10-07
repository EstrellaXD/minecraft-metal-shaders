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
package mcopt.metal.shade.weather;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.level.biome.Biome;

/**
 * The game-typed half of the weather module's per-frame update (ShadeWeather stays free of game types): what falls at the
 * camera (rain, snow or nothing: deserts, savannas, the Nether), and lightning bolts near the camera (their direction and a
 * flickering flash). Called from ShadeFrameUpdater.update before ShadeLook is packed.
 */
public final class WeatherFrame {
	private static long lastPrecipCheck;
	private static Biome.Precipitation precipitation = Biome.Precipitation.NONE;
	private static float afterglow;
	private static final float[] glowDir = {0f, 1f, 0f};

	private WeatherFrame() {
	}

	/**
	 * level: the client level (null in menus); cam: camera position; rain / thunder: the game's levels this frame; overworld:
	 * the overworld sky (no weather elsewhere); snap: jump to the targets (world load, dimension change, a stall).
	 */
	public static void update(ClientLevel level, double camX, double camY, double camZ, float rainLevel, float thunder, boolean overworld, float dt,
	                          float dayIndex, float seconds, boolean snap) {
		if (!ShadeWeather.ON) return;
		long now = System.nanoTime();
		if (level == null || !overworld) {
			precipitation = Biome.Precipitation.NONE;
		} else if (snap || now - lastPrecipCheck > 500_000_000L) {
			lastPrecipCheck = now;
			try {
				precipitation = level.getPrecipitationAt(BlockPos.containing(camX, camY, camZ));
			} catch (RuntimeException e) {
				precipitation = Biome.Precipitation.NONE;
			}
		}
		float r = overworld ? rainLevel : 0f;
		float rainTarget = precipitation == Biome.Precipitation.RAIN ? Math.min(1f, r * (1f + 0.25f * thunder)) : 0f;
		float snowTarget = precipitation == Biome.Precipitation.SNOW ? r : 0f;
		ShadeWeather.step(dt, rainTarget, snowTarget, overworld ? thunder : 0f, dayIndex, seconds, snap);
		lightning(level, camX, camY, camZ, overworld, dt, seconds);
	}

	/**
	 * The flash: every live bolt within 256 blocks lights the scene from its direction (a point 40 blocks up the bolt, where the
	 * cloud it came from glows), flickering while it lives, then an afterglow fading in ~0.15 s. Strength falls with distance.
	 */
	private static void lightning(ClientLevel level, double camX, double camY, double camZ, boolean overworld, float dt, float seconds) {
		float best = 0f;
		double bx = 0, by = 1, bz = 0;
		Minecraft mc = Minecraft.getInstance();
		boolean hidden = mc.options != null && mc.options.hideLightningFlash().get();
		if (ShadeWeather.LIGHTNING && level != null && overworld && !hidden) {
			for (Entity e : level.entitiesForRendering()) {
				if (!(e instanceof LightningBolt bolt)) continue;
				double dx = bolt.getX() - camX, dy = bolt.getY() + 40.0 - camY, dz = bolt.getZ() - camZ;
				double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
				if (d > 256.0) continue;
				// strokes: bright for the first ticks, then a flicker that follows the bolt's own seed
				float age = bolt.tickCount + mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
				double n = Math.sin((bolt.seed & 1023) * 0.37 + age * 7.3) * Math.sin(age * 3.1 + (bolt.seed >> 10 & 255));
				float stroke = (age < 2.5f ? 1.0f : 0.45f + 0.55f * (float) Math.abs(n));
				float w = stroke / (1f + (float) (d * d) / (110f * 110f));
				if (w > best) {
					best = w;
					bx = dx / d;
					by = dy / d;
					bz = dz / d;
				}
			}
		}
		if (best > 0f) {
			afterglow = Math.max(afterglow * 0.5f, best);
			glowDir[0] = (float) bx;
			glowDir[1] = (float) by;
			glowDir[2] = (float) bz;
		} else {
			afterglow *= (float) Math.exp(-dt / 0.12f);
			if (afterglow < 0.01f) afterglow = 0f;
		}
		ShadeWeather.flashDir[0] = glowDir[0];
		ShadeWeather.flashDir[1] = glowDir[1];
		ShadeWeather.flashDir[2] = glowDir[2];
		ShadeWeather.flash = afterglow * FLASH_LIGHT;
	}

	/** Flash light at full strength: radiance on a white surface facing it, before exposure (a storm's sky light is ~0.1-0.3). */
	private static final float FLASH_LIGHT = Float.parseFloat(System.getProperty("mcopt.shade.flashLight", "2.5"));
}
