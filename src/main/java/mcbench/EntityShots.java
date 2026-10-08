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
 * {@code perf,noon,evening,night,hand,motion}; off by default, so the standard plan is unchanged), owned by the mcopt-shade M3
 * base/entities work (branch m3). A small "zoo" is summoned for the shots on the flat torch-grid site (the night_torches
 * scene's patch, found from the real terrain), then removed again: every entity carries the tag {@code mcbench_ent}
 * (killed before and after, with any item they drop), and the two blocks it sets (a chest and a sign) are set back to
 * air, so the saved world the other runs use is left as it was. Mobs have NoAI and face the camera.
 * <ul>
 * <li>{@code perf}: a recorded perf phase "zoo" (results.json perf.zoo / gpu.zoo): noon, panning +-20 degrees over the zoo
 * for -Dmcbench.zooSeconds (10) s, to price entity lighting and shadows; then "zoo_noshadow" (system property
 * mcopt.shade.entityShadows.pause, read by mcopt-shade every frame: no entity shadows) and "zoo2" (as zoo): the entity
 * shadows' cost A/B in one session, bracketed.</li>
 * <li>{@code ent_noon} (6000): villager, iron golem, cow, sheep, pig, horse, chicken, an armour stand in iron armour, a
 * chest, a sign, an item frame with a compass and floating dropped items, from 9 blocks: lighting and shadows on mobs.</li>
 * <li>{@code ent_evening} (11500): the same with a low sun from the west: long entity shadows across the ground.</li>
 * <li>{@code ent_night} (18000): the same at night among the torch grid: mobs lit by torches.</li>
 * <li>{@code ent_hand} (18000): creative mode, a torch in the main hand and a lantern in the off hand, HUD shown so the
 * hand renders (the screenshot is read before the GUI draws), looking at the zoo from 5 blocks: hand lighting and the
 * held-light (dynamic lights) case. Spectator mode and the hidden HUD come back afterwards.</li>
 * <li>{@code ent_motion} (6000): a villager, a horse and a pig teleported along x at 4-6 blocks/s every server tick while
 * the camera holds still, shot mid-motion: TAAU ghosting behind moving entities (per-object motion vectors).</li>
 * <li>{@code cave} -> {@code ent_cave}: a pig and a villager with a torch beside them in LightShots' dark cave (sky light 0):
 * vanilla's blob shadow must stay there (no real shadow can replace it). The torch is set back to air afterwards.</li>
 * <li>{@code rose} -> {@code rose_off_water}, {@code rose_on_water}, {@code rose_off_sky}, {@code rose_on_sky}: the
 * sunset_water pose (12400) and the same 25 degrees up, each shot with mcopt.shade.skyRose=false then true (read every
 * frame by mcopt-shade) in one session, the ticks frozen around each pair (/tick freeze: the vanilla clouds, which follow
 * the game time, keep their layout): a matched before/after of the evening rose.</li>
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
		if (!(on("noon") || on("evening") || on("night") || on("hand") || on("motion") || on("perf") || on("cave") || on("rose"))) return;
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
		if (on("perf")) zoo(steps);
		if (on("noon")) shot(steps, "ent_noon", 6000, () -> site.camera(9.5, 2.4, 9f));
		if (on("evening")) shot(steps, "ent_evening", 11500, () -> site.camera(9.5, 2.4, 9f));
		if (on("night")) shot(steps, "ent_night", 18000, () -> site.camera(9.5, 2.4, 9f));
		if (on("hand")) hand(steps);
		if (on("motion")) motion(steps);
		if (on("cave")) cave(steps);
		if (on("rose")) {
			rose(steps, "water", () -> BenchDriver.spot.sunset);
			rose(steps, "sky", () -> {
				Pose o = BenchDriver.spot.sunset;
				return new Pose(o.x(), o.y(), o.z(), o.yaw(), -25f);
			});
		}
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
		String key = name;
		steps.add(new BenchDriver.Step("ent-record-" + name, 0) {
			@Override
			boolean poll(Minecraft mc, long now) {
				Map<String, Object> m = p.get().json();
				m.put("time", time);
				JSON.put(key, m);
				return true;
			}
		});
	}

	private static void cmd(MinecraftServer s, String c) {
		BenchDriver.cmd(s, c);
	}

	static void cleanup(MinecraftServer s) {
		cmd(s, "kill @e[tag=" + TAG + "]");
		cmd(s, "tick unfreeze");
		System.clearProperty("mcopt.shade.skyRose");
		if (caveTorch != null) {
			cmd(s, String.format(Locale.ROOT, "setblock %d %d %d air replace", caveTorch.getX(), caveTorch.getY(), caveTorch.getZ()));
			caveTorch = null;
		}
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

	/**
	 * The zoo perf segment (recorded as perf/gpu phase "zoo", like spin and flight): noon, the camera 9.5 blocks from the
	 * line panning +-20 degrees for -Dmcbench.zooSeconds (10) s, entity-heavy frames for pricing entity shading and shadows.
	 */
	private static void zoo(java.util.Deque<BenchDriver.Step> steps) {
		double sec = Double.parseDouble(System.getProperty("mcbench.zooSeconds", "10"));
		steps.add(new BenchDriver.Scene("zoo", 6000, false, () -> site.camera(9.5, 2.4, 9f)));
		steps.add(new BenchDriver.Settle("zoo", false, 3, 120));
		steps.add(new BenchDriver.Warmup(2));
		for (String phase : new String[] {"zoo", "zoo_noshadow", "zoo2"}) {
			steps.add(new BenchDriver.Step(phase, 0) {
				@Override
				void start(Minecraft mc, long now) {
					BenchDriver.setPhase(phase.toUpperCase(Locale.ROOT));
					System.setProperty("mcopt.shade.entityShadows.pause", Boolean.toString(phase.equals("zoo_noshadow")));
					Pose o = site.camera(9.5, 2.4, 9f);
					long t0 = now;
					BenchDriver.pose = t -> new Pose(o.x(), o.y(), o.z(), (float) (20.0 * Math.sin(BenchDriver.secs(t - t0) * 2 * Math.PI / sec)), o.pitch());
					BenchDriver.recPhase = BenchDriver.REC_PHASES.indexOf(phase);
					BenchDriver.phaseRanges.put(phase, new long[] {now, 0});
					BenchDriver.log("perf " + phase + " " + sec + " s");
				}

				@Override
				boolean poll(Minecraft mc, long now) {
					if (BenchDriver.secs(now - BenchDriver.curStart) < sec) return false;
					BenchDriver.recPhase = -1;
					BenchDriver.phaseRanges.get(phase)[1] = now;
					System.setProperty("mcopt.shade.entityShadows.pause", "false");
					return true;
				}
			});
		}
	}

	/** Creative mode with a torch and a lantern in the hands, HUD shown (the hand renders only then), at night. */
	private static void hand(java.util.Deque<BenchDriver.Step> steps) {
		final String name = "ent_hand";
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
				JSON.put("ent_hand", m);
			}

			@Override
			boolean poll(Minecraft mc, long now) {
				return BenchDriver.secs(now - BenchDriver.curStart) >= 0.5;
			}
		});
	}

	/** A step that sets (value) or clears (null) a system property mcopt-shade reads every frame, and runs a command. */
	private static BenchDriver.Step prop(String name, String key, String value, String command) {
		return new BenchDriver.Step(name, 0) {
			@Override
			void start(Minecraft mc, long now) {
				if (value == null) System.clearProperty(key);
				else System.setProperty(key, value);
				if (command != null) cmd(BenchDriver.server(mc), command);
			}

			@Override
			boolean poll(Minecraft mc, long now) {
				return true;
			}
		};
	}

	/** The evening rose off then on at one pose, in one session, the ticks frozen around the pair. */
	private static void rose(java.util.Deque<BenchDriver.Step> steps, String tag, java.util.function.Supplier<Pose> p) {
		String off = "rose_off_" + tag, onName = "rose_on_" + tag;
		steps.add(new BenchDriver.Scene(off, 12400, false, p));
		steps.add(new BenchDriver.Settle(off, false, 3, 120));
		steps.add(prop("rose-freeze-" + tag, "mcopt.shade.skyRose", "false", "tick freeze"));
		steps.add(new BenchDriver.Hold(off, Math.max(BenchDriver.SHOT_WAIT, 2.5)));
		steps.add(new BenchDriver.Shot(off));
		steps.add(prop("rose-on-" + tag, "mcopt.shade.skyRose", "true", null));
		steps.add(new BenchDriver.Hold(onName, Math.max(BenchDriver.SHOT_WAIT, 2.5)));
		steps.add(new BenchDriver.Shot(onName));
		steps.add(prop("rose-unfreeze-" + tag, "mcopt.shade.skyRose", null, "tick unfreeze"));
		steps.add(new BenchDriver.Step("ent-record-rose-" + tag, 0) {
			@Override
			boolean poll(Minecraft mc, long now) {
				Map<String, Object> m = p.get().json();
				m.put("time", 12400);
				m.put("shots", off + " (mcopt.shade.skyRose=false), " + onName + " (true), ticks frozen");
				JSON.put("rose_" + tag, m);
				return true;
			}
		});
	}

	static BlockPos caveTorch;

	/** A pig and a villager with a torch beside them in LightShots' dark cave: the blob shadow must stay there. */
	private static void cave(java.util.Deque<BenchDriver.Step> steps) {
		String name = "ent_cave";
		Pose[] pose = new Pose[1];
		steps.add(new BenchDriver.Step("ent-cave-setup", 120) {
			CompletableFuture<Pose> f;

			@Override
			void start(Minecraft mc, long now) {
				MinecraftServer s = BenchDriver.server(mc);
				f = CompletableFuture.supplyAsync(() -> {
					ServerLevel l = s.overworld();
					if (LightShots.cave == null) LightShots.find(s, l);
					Pose c = LightShots.cave;
					if (c == null) return null;
					double ux = LightShots.caveDx, uz = LightShots.caveDz;
					BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
					String[] mobs = {"pig", "villager"};
					double[] dist = {3.5, 5.5};
					for (int i = 0; i < 2; i++) {
						double x = c.x() + ux * dist[i] + uz * (i == 0 ? 0.6 : -0.6), z = c.z() + uz * dist[i] - ux * (i == 0 ? 0.6 : -0.6);
						int y = floor(l, m, x, (int) c.y(), z);
						run(s, String.format(Locale.ROOT, "summon minecraft:%s %.2f %d %.2f {NoAI:1b,Silent:1b,PersistenceRequired:1b,Invulnerable:1b,Tags:[\"%s\"],Rotation:[%.0ff,0f]}",
							mobs[i], x, y, z, TAG, c.yaw() + 180f));
					}
					// the torch: beside the line, 4.5 blocks ahead (on the line if the side is not free)
					for (double side : new double[] {1.6, -1.6, 0.0}) {
						double x = c.x() + ux * 4.5 + uz * side, z = c.z() + uz * 4.5 - ux * side;
						int y = floor(l, m, x, (int) c.y(), z);
						BlockPos t = BlockPos.containing(x, y, z);
						if (l.getBlockState(t).isAir() && l.getBlockState(t.below()).isSolidRender()) {
							run(s, String.format(Locale.ROOT, "setblock %d %d %d minecraft:torch", t.getX(), t.getY(), t.getZ()));
							caveTorch = t;
							break;
						}
					}
					return new Pose(c.x(), c.y() + 1.0, c.z(), c.yaw(), 22f);
				}, s);
			}

			@Override
			boolean poll(Minecraft mc, long now) throws Exception {
				if (!f.isDone()) return false;
				pose[0] = f.get();
				BenchDriver.log("entity cave " + pose[0] + ", torch " + caveTorch);
				return true;
			}
		});
		java.util.function.Supplier<Pose> p = () -> pose[0] != null ? pose[0] : site.camera(9.5, 2.4, 9f);
		shot(steps, name, 6000, p);
	}

	/** The first y at or below y0 (down to y0 - 4) standing on a solid block. */
	private static int floor(ServerLevel l, BlockPos.MutableBlockPos m, double x, int y0, double z) {
		int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
		for (int y = y0 + 1; y >= y0 - 4; y--) {
			if (l.getBlockState(m.set(bx, y, bz)).isAir() && l.getBlockState(m.set(bx, y - 1, bz)).isSolidRender()) return y;
		}
		return y0;
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
						mobs[i], site.x - 7.5, lineAt(site.x), site.z - 1.0 - i * 1.4, TAG, i));
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
						cmd(s, String.format(Locale.ROOT, "execute as @e[tag=mcbench_mover%d] run tp @s %.3f %d %.3f 270 0", i, x, lineAt(site.x), site.z - 1.0 - i * 1.4));
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
				JSON.put("ent_motion", m);
			}

			@Override
			boolean poll(Minecraft mc, long now) {
				return BenchDriver.secs(now - BenchDriver.curStart) >= 0.3;
			}
		});
	}
}
