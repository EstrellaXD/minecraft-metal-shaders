/*
 * Copyright 2026 mcbench contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package mcbench;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import mcbench.Spot.Pose;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * Opt-in entity screenshots ({@code -Dmcbench.entities=true} for all of them, or a comma list of
 * {@code noon,evening,night,hand,motion}; off by default, so the standard plan is unchanged), owned by the mcopt-shade M3
 * base/entities work (branch m3). A small "zoo" is summoned for the shots on the flat torch-grid site (the night_torches
 * scene's patch, found from the real terrain), then removed again: every entity carries the tag {@code mcbench_ent}
 * (killed before and after, with any item they drop), and the two blocks it sets (a chest and a sign) are set back to
 * air, so the saved world the other runs use is left as it was. Mobs have NoAI and face the camera.
 * <ul>
 * <li>{@code ent_noon} (6000): villager, iron golem, cow, sheep, pig, horse, chicken, an armour stand in iron armour, a
 * chest, a sign, an item frame with a compass and floating dropped items, from 9 blocks: lighting and shadows on mobs.</li>
 * <li>{@code ent_evening} (11500): the same with a low sun from the west: long entity shadows across the ground.</li>
 * <li>{@code ent_night} (18000): the same at night among the torch grid: mobs lit by torches.</li>
 * <li>{@code ent_hand} (18000): creative mode, a torch in the main hand and a lantern in the off hand, HUD shown so the
 * hand renders (the screenshot is read before the GUI draws), looking at the zoo from 5 blocks: hand lighting and the
 * held-light (dynamic lights) case. Spectator mode and the hidden HUD come back afterwards.</li>
 * <li>{@code ent_motion} (6000): a villager, a horse and a pig teleported along x at 4-6 blocks/s every server tick while
 * the camera holds still, shot mid-motion: TAAU ghosting behind moving entities (per-object motion vectors).</li>
 * </ul>
 * Poses and the site go to results.json "entityShots".
 */
final class EntityShots {
	static final String SPEC = System.getProperty("mcbench.entities", "").trim();
	static final String TAG = "mcbench_ent";

	private EntityShots() {
	}

	static boolean on(String scene) {
		if (SPEC.isEmpty() || SPEC.equals("false")) return false;
		if (SPEC.equals("true") || SPEC.equals("all")) return true;
		for (String s : SPEC.split(",")) if (s.trim().equals(scene)) return true;
		return false;
	}

	/** The site: ground y at (x, z), the mob line along x, camera south of it looking north (+z). */
	record Site(int x, int y, int z) {
		Pose camera(double back, double up, float pitch) {
			return new Pose(x + 0.5, y + up, z + 0.5 - back, 0f, pitch);
		}
	}

	static Site site;
	static final Map<String, Object> JSON = new LinkedHashMap<>();

	/** Called from BenchDriver.buildPlan after the other shots. */
	static void plan(java.util.Deque<BenchDriver.Step> steps) {
		if (!(on("noon") || on("evening") || on("night") || on("hand") || on("motion"))) return;
		BenchDriver.results.put("entityShots", JSON);
		// load the site (the torch grid) first: its chunks must exist on the server to read the ground and summon
		steps.add(new BenchDriver.Scene("ent-site", 6000, false, () -> BenchDriver.spot.night));
		steps.add(new BenchDriver.Settle("ent-site", false, 3, 120));
		steps.add(new BenchDriver.Step("ent-setup", 60) {
			CompletableFuture<Site> f;

			@Override
			void start(Minecraft mc, long now) {
				MinecraftServer s = BenchDriver.server(mc);
				f = CompletableFuture.supplyAsync(() -> setup(s, s.overworld()), s);
			}

			@Override
			boolean poll(Minecraft mc, long now) throws Exception {
				if (!f.isDone()) return false;
				site = f.get();
				JSON.put("site", new int[] {site.x, site.y, site.z});
				BenchDriver.log("entity site " + site);
				return true;
			}
		});
		if (on("noon")) shot(steps, "ent_noon", 6000, () -> site.camera(9.5, 2.4, 9f));
		if (on("evening")) shot(steps, "ent_evening", 11500, () -> site.camera(9.5, 2.4, 9f));
		if (on("night")) shot(steps, "ent_night", 18000, () -> site.camera(9.5, 2.4, 9f));
		if (on("hand")) hand(steps);
		if (on("motion")) motion(steps);
		steps.add(new BenchDriver.Step("ent-cleanup", 0) {
			@Override
			void start(Minecraft mc, long now) {
				cleanup(BenchDriver.server(mc));
			}

			@Override
			boolean poll(Minecraft mc, long now) {
				return BenchDriver.secs(now - BenchDriver.curStart) >= 1.0;
			}
		});
	}

	private static void shot(java.util.Deque<BenchDriver.Step> steps, String name, int time, java.util.function.Supplier<Pose> p) {
		steps.add(new BenchDriver.Scene(name, time, false, p));
		steps.add(new BenchDriver.Settle(name, false, 3, 120));
		steps.add(new BenchDriver.Hold(name, Math.max(BenchDriver.SHOT_WAIT, 2.5)));
		steps.add(new BenchDriver.Shot(name));
		steps.add(new BenchDriver.Step("ent-record-" + name, 0) {
			@Override
			boolean poll(Minecraft mc, long now) {
				Map<String, Object> m = p.get().json();
				m.put("time", time);
				JSON.put(name, m);
				return true;
			}
		});
	}

	private static void cmd(MinecraftServer s, String c) {
		BenchDriver.cmd(s, c);
	}

	static void cleanup(MinecraftServer s) {
		cmd(s, "kill @e[tag=" + TAG + "]");
		if (site != null) {
			cmd(s, String.format(Locale.ROOT, "execute in minecraft:overworld positioned %d %d %d run kill @e[type=item,distance=..24]", site.x, site.y, site.z));
			cmd(s, String.format(Locale.ROOT, "setblock %d %d %d air", site.x + 3, groundAt(site.x + 3), site.z + 3));
			cmd(s, String.format(Locale.ROOT, "setblock %d %d %d air", site.x + 4, groundAt(site.x + 4), site.z + 3));
		}
	}

	/** Ground y per x: along the back row (chest, sign; z + 3) and along the mob line (z). */
	private static final Map<Integer, Integer> GROUND = new LinkedHashMap<>(), LINE = new LinkedHashMap<>();

	private static int groundAt(int x) {
		return GROUND.getOrDefault(x, site.y);
	}

	private static int lineAt(int x) {
		return LINE.getOrDefault(x, site.y);
	}

	/** Server thread: find the ground along the line, clear leftovers of an earlier run, summon the zoo. */
	static Site setup(MinecraftServer s, ServerLevel l) {
		Spot spot = BenchDriver.spot;
		int tx = spot.torchX, tz = spot.torchZ + 2; // between two torch rows (torches are 5 apart on the grid)
		int y = BenchDriver.landY(l, tx, tz);
		if (y == Integer.MIN_VALUE) y = l.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, tx, tz);
		Site st = new Site(tx, y, tz);
		site = st;
		GROUND.clear();
		LINE.clear();
		for (int dx = -9; dx <= 9; dx++) {
			int g = BenchDriver.landY(l, tx + dx, tz + 3);
			int g0 = BenchDriver.landY(l, tx + dx, tz);
			GROUND.put(dx + tx, g != Integer.MIN_VALUE ? g : y);
			LINE.put(dx + tx, g0 != Integer.MIN_VALUE ? g0 : y);
		}
		// leftovers of an interrupted run (tagged entities; the chest and the sign are placed at fixed spots)
		s.getCommands().performPrefixedCommand(s.createCommandSourceStack().withSuppressedOutput(), "kill @e[tag=" + TAG + "]");
		String[] mobs = {"villager", "iron_golem", "cow", "sheep", "pig", "horse", "chicken"};
		int[] xs = {-6, -4, -2, 0, 2, 4, 6};
		for (int i = 0; i < mobs.length; i++) {
			int x = tx + xs[i];
			int g = lineAt(x);
			run(s, String.format(Locale.ROOT, "summon minecraft:%s %.2f %d %.2f {NoAI:1b,Silent:1b,PersistenceRequired:1b,Invulnerable:1b,Tags:[\"%s\"],Rotation:[180f,0f]}",
				mobs[i], x + 0.5, g, tz + 0.5, TAG));
		}
		// an armour stand in iron armour with arms, between the golem and the cow
		int ax = tx - 3, ag = lineAt(ax);
		run(s, String.format(Locale.ROOT, "summon minecraft:armor_stand %.2f %d %.2f {ShowArms:1b,Invulnerable:1b,Tags:[\"%s\"],Rotation:[180f,0f],"
			+ "equipment:{head:{id:\"minecraft:iron_helmet\",count:1},chest:{id:\"minecraft:iron_chestplate\",count:1},legs:{id:\"minecraft:iron_leggings\",count:1},"
			+ "feet:{id:\"minecraft:iron_boots\",count:1},mainhand:{id:\"minecraft:iron_sword\",count:1}}}", ax + 0.5, ag, tz + 1.5, TAG));
		// a chest and a sign behind the line, an item frame (fixed: needs no wall) with a compass, dropped items in front
		int cx = tx + 3, cg = groundAt(cx);
		run(s, String.format(Locale.ROOT, "setblock %d %d %d minecraft:chest[facing=north]", cx, cg, tz + 3));
		int sx = tx + 4, sg = groundAt(sx);
		run(s, String.format(Locale.ROOT, "setblock %d %d %d minecraft:oak_sign[rotation=8]", sx, sg, tz + 3));
		run(s, String.format(Locale.ROOT, "summon minecraft:item_frame %.2f %.2f %.2f {Facing:2b,Fixed:1b,Invulnerable:1b,Tags:[\"%s\"],Item:{id:\"minecraft:compass\",count:1}}",
			tx + 1.5, cg + 1.5, tz + 3.03, TAG));
		String[] items = {"diamond_sword", "golden_apple", "glowstone", "torch", "emerald"};
		for (int i = 0; i < items.length; i++) {
			int x = tx - 4 + i * 2;
			int g = lineAt(x);
			run(s, String.format(Locale.ROOT, "summon minecraft:item %.2f %.2f %.2f {NoGravity:1b,Age:-32768,PickupDelay:32767,Invulnerable:1b,Tags:[\"%s\"],Item:{id:\"minecraft:%s\",count:1}}",
				x + 1.5, g + 0.25, tz - 1.5, TAG, items[i]));
		}
		return st;
	}

	private static void run(MinecraftServer s, String c) {
		s.getCommands().performPrefixedCommand(s.createCommandSourceStack().withSuppressedOutput(), c);
	}

	/** Creative mode with a torch and a lantern in the hands, HUD shown (the hand renders only then), at night. */
	private static void hand(java.util.Deque<BenchDriver.Step> steps) {
		String name = "ent_hand";
		java.util.function.Supplier<Pose> p = () -> site.camera(5.0, 1.6, 14f);
		steps.add(new BenchDriver.Scene(name, 18000, false, p));
		steps.add(new BenchDriver.Step("ent-hand-on", 0) {
			@Override
			void start(Minecraft mc, long now) {
				MinecraftServer s = BenchDriver.server(mc);
				cmd(s, "gamemode creative @a");
				cmd(s, "item replace entity @a weapon.mainhand with minecraft:torch");
				cmd(s, "item replace entity @a weapon.offhand with minecraft:lantern");
			}

			@Override
			boolean poll(Minecraft mc, long now) {
				if (mc.gui.hud.isHidden()) mc.gui.hud.toggle();
				return BenchDriver.secs(now - BenchDriver.curStart) >= 1.0;
			}
		});
		steps.add(new BenchDriver.Settle(name, false, 3, 120));
		steps.add(new BenchDriver.Step("hold-" + name, 0) {
			@Override
			boolean poll(Minecraft mc, long now) {
				if (mc.gui.screen() != null) mc.gui.setScreen(null);
				if (mc.gui.hud.isHidden()) mc.gui.hud.toggle();
				return BenchDriver.secs(now - BenchDriver.curStart) >= Math.max(BenchDriver.SHOT_WAIT, 2.5);
			}
		});
		steps.add(new BenchDriver.Shot(name));
		steps.add(new BenchDriver.Step("ent-hand-off", 0) {
			@Override
			void start(Minecraft mc, long now) {
				MinecraftServer s = BenchDriver.server(mc);
				cmd(s, "item replace entity @a weapon.mainhand with minecraft:air");
				cmd(s, "item replace entity @a weapon.offhand with minecraft:air");
				cmd(s, "gamemode spectator @a");
				BenchDriver.hideHud(mc);
				Map<String, Object> m = p.get().json();
				m.put("time", 18000);
				m.put("hands", "torch, lantern");
				JSON.put(name, m);
			}

			@Override
			boolean poll(Minecraft mc, long now) {
				return BenchDriver.secs(now - BenchDriver.curStart) >= 0.5;
			}
		});
	}

	/** Three mobs teleported along x every server tick (4..6 blocks/s) while the camera holds still; shot mid-motion. */
	private static void motion(java.util.Deque<BenchDriver.Step> steps) {
		String name = "ent_motion";
		java.util.function.Supplier<Pose> p = () -> site.camera(9.5, 2.4, 9f);
		steps.add(new BenchDriver.Scene(name, 6000, false, p));
		steps.add(new BenchDriver.Settle(name, false, 3, 120));
		steps.add(new BenchDriver.Step("ent-motion-spawn", 0) {
			@Override
			void start(Minecraft mc, long now) {
				MinecraftServer s = BenchDriver.server(mc);
				String[] mobs = {"villager", "horse", "pig"};
				for (int i = 0; i < mobs.length; i++) {
					cmd(s, String.format(Locale.ROOT, "summon minecraft:%s %.2f %d %.2f {NoAI:1b,Silent:1b,Invulnerable:1b,Tags:[\"%s\",\"mcbench_mover%d\"],Rotation:[270f,0f]}",
						mobs[i], site.x - 7.5, site.y, site.z - 2.5 - i * 1.6, TAG, i));
				}
			}

			@Override
			boolean poll(Minecraft mc, long now) {
				return BenchDriver.secs(now - BenchDriver.curStart) >= 1.0;
			}
		});
		steps.add(new BenchDriver.Step("move-" + name, 0) {
			long lastCmd;

			@Override
			boolean poll(Minecraft mc, long now) {
				double t = BenchDriver.secs(now - BenchDriver.curStart);
				if (now - lastCmd > 40_000_000L) {
					lastCmd = now;
					MinecraftServer s = BenchDriver.server(mc);
					double[] speed = {4.0, 6.0, 5.0};
					for (int i = 0; i < 3; i++) {
						double x = site.x - 7.5 + speed[i] * t;
						cmd(s, String.format(Locale.ROOT, "execute as @e[tag=mcbench_mover%d] run tp @s %.3f %d %.3f 270 0", i, x, lineAt(site.x), site.z - 2.5 - i * 1.6));
					}
				}
				return t >= 1.6;
			}
		});
		steps.add(new BenchDriver.Shot(name));
		steps.add(new BenchDriver.Step("ent-motion-off", 0) {
			@Override
			void start(Minecraft mc, long now) {
				cmd(BenchDriver.server(mc), "kill @e[tag=mcbench_mover0]");
				cmd(BenchDriver.server(mc), "kill @e[tag=mcbench_mover1]");
				cmd(BenchDriver.server(mc), "kill @e[tag=mcbench_mover2]");
				Map<String, Object> m = p.get().json();
				m.put("time", 6000);
				m.put("movers", "villager 4, horse 6, pig 5 blocks/s along +x");
				JSON.put(name, m);
			}

			@Override
			boolean poll(Minecraft mc, long now) {
				return BenchDriver.secs(now - BenchDriver.curStart) >= 0.3;
			}
		});
	}
}
