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
package mcopt.metal.shade.light;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * What a luminous item gives off when held or dropped: the light level (vanilla's block emission, so a carried torch reaches
 * as far as a placed one) and our colour per kind of light. Colours are tints relative to the art layer's block-light
 * colour (ShadeFrame.blockLight, ~2700 K: 2.4, 1.2, 0.48), so a torch is (1, 1, 1) and matches placed torches exactly;
 * they come from target colours (Kelvin-ish for flames and lamps, saturated for soul fire, redstone, froglights) divided by
 * that colour, luminance near 1. Items not named here: a block item with light emission uses it, coloured by its name
 * (soul / redstone / copper / amethyst...), else warm.
 */
public final class DynLightTable {
	/** level 0..15, tint rgb, fire: flickers. */
	public record Def(float level, float r, float g, float b, boolean fire) {
	}

	static final Def NONE = new Def(0f, 0f, 0f, 0f, false);

	// tints (target colour / blockLight), see the class comment
	static final float[] WARM = {1f, 1f, 1f};
	static final float[] FIRE = {1.12f, 0.96f, 0.73f};
	static final float[] SOUL = {0.19f, 1.04f, 3.33f};
	static final float[] LAMP = {0.92f, 1.21f, 1.56f};      // glowstone, redstone lamp: 3000 K
	static final float[] COOL = {0.42f, 1.12f, 3.33f};      // sea lantern, beacon, conduit
	static final float[] SHROOM = {1.08f, 0.96f, 0.83f};
	static final float[] PUMPKIN = {1.04f, 1.08f, 0.94f};
	static final float[] LAVA = {1.21f, 0.79f, 0.42f};
	static final float[] REDSTONE = {1.0f, 0.25f, 0.31f};
	static final float[] END_ROD = {0.63f, 1.13f, 3.33f};
	static final float[] OCHRE = {0.96f, 1.33f, 1.25f};
	static final float[] VERDANT = {0.38f, 1.42f, 1.67f};
	static final float[] PEARL = {0.79f, 0.96f, 3.54f};
	static final float[] BERRY = {1.0f, 1.25f, 1.04f};
	static final float[] TEAL = {0.21f, 1.08f, 2.5f};
	static final float[] COPPER = {0.375f, 1.33f, 1.15f};
	static final float[] VIOLET = {0.54f, 0.67f, 3.96f};
	static final float[] WHITE = {0.83f, 1.58f, 3.75f};
	static final float[] BLAZE = {1.12f, 1.33f, 1.04f};
	static final float[] MAGMA = {1.17f, 0.75f, 0.52f};
	static final float[] SQUID = {0.15f, 1.0f, 2.29f};
	static final float[] SPECTRAL = {0.92f, 1.58f, 2.08f};
	static final float[] DRAGON = {0.67f, 0.375f, 3.96f};
	static final float[] FLASH = {0.92f, 1.58f, 3.1f};
	static final float[] LIGHTNING = {0.67f, 1.42f, 5.0f};
	static final float[] ROCKET = {1.0f, 1.5f, 2.3f};
	static final float[] CRYSTAL = {0.75f, 0.92f, 3.96f};

	private static final Map<String, Def> NAMED = new HashMap<>();
	private static final Map<Item, Def> CACHE = new IdentityHashMap<>();

	static {
		named(14, WARM, true, "torch");
		named(10, SOUL, true, "soul_torch");
		named(14, COPPER, true, "copper_torch");
		named(15, WARM, true, "lantern");
		named(10, SOUL, true, "soul_lantern");
		named(14, COPPER, true, "copper_lantern", "exposed_copper_lantern", "weathered_copper_lantern", "oxidized_copper_lantern", "waxed_copper_lantern",
			"waxed_exposed_copper_lantern", "waxed_weathered_copper_lantern", "waxed_oxidized_copper_lantern");
		named(15, FIRE, true, "campfire");
		named(10, SOUL, true, "soul_campfire");
		named(15, LAMP, false, "glowstone", "redstone_lamp");
		named(8, LAMP, false, "glowstone_dust");
		named(15, COOL, false, "sea_lantern", "beacon", "conduit");
		named(15, SHROOM, false, "shroomlight");
		named(15, PUMPKIN, true, "jack_o_lantern");
		named(15, LAVA, true, "lava_bucket");
		named(10, MAGMA, false, "magma_block");
		named(7, REDSTONE, false, "redstone_torch");
		named(6, REDSTONE, false, "redstone_block");
		named(14, END_ROD, false, "end_rod");
		named(15, OCHRE, false, "ochre_froglight");
		named(15, VERDANT, false, "verdant_froglight");
		named(15, PEARL, false, "pearlescent_froglight");
		named(12, BERRY, false, "glow_berries");
		named(7, TEAL, false, "glow_lichen", "glow_ink_sac");
		named(6, TEAL, false, "sculk_catalyst", "sea_pickle");
		named(5, VIOLET, false, "amethyst_cluster", "large_amethyst_bud");
		named(10, VIOLET, false, "crying_obsidian");
		named(15, WHITE, false, "light");
		named(10, BLAZE, true, "blaze_rod");
		named(8, BLAZE, true, "blaze_powder", "fire_charge");
		named(6, MAGMA, false, "magma_cream");
		named(10, WHITE, false, "nether_star");
		named(7, TEAL, false, "heart_of_the_sea");
		named(15, COOL, false, "respawn_anchor");
		named(13, FIRE, true, "furnace_minecart");
	}

	private DynLightTable() {
	}

	private static void named(int level, float[] tint, boolean fire, String... paths) {
		for (String p : paths) NAMED.put(p, new Def(level, tint[0], tint[1], tint[2], fire));
	}

	static Def def(float level, float[] tint, boolean fire) {
		return new Def(level, tint[0], tint[1], tint[2], fire);
	}

	/** The light of a stack (NONE for none). Render thread only. */
	public static Def of(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return NONE;
		return of(stack.getItem());
	}

	static Def of(Item item) {
		Def d = CACHE.get(item);
		if (d == null) {
			d = resolve(item);
			CACHE.put(item, d);
		}
		return d;
	}

	private static Def resolve(Item item) {
		String path;
		try {
			path = BuiltInRegistries.ITEM.getKey(item).getPath();
		} catch (RuntimeException e) {
			path = "";
		}
		Def named = NAMED.get(path);
		if (named != null) return named;
		if (item instanceof BlockItem bi) {
			int level;
			try {
				level = bi.getBlock().defaultBlockState().getLightEmission();
			} catch (RuntimeException e) {
				level = 0;
			}
			if (level > 0) {
				float[] tint = path.contains("soul") ? SOUL : path.contains("redstone") ? REDSTONE : path.contains("copper") ? COPPER
					: path.contains("amethyst") ? VIOLET : path.contains("sculk") || path.contains("glow") ? TEAL : path.contains("froglight") ? OCHRE
					: path.contains("lantern") || path.contains("torch") || path.contains("fire") || path.contains("candle") ? FIRE : WARM;
				boolean fire = path.contains("torch") || path.contains("fire") || path.contains("candle") || path.contains("lantern");
				return new Def(level, tint[0], tint[1], tint[2], fire);
			}
		}
		return NONE;
	}
}
