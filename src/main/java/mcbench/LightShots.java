/*
 * Copyright 2026 mcbench contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package mcbench;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import mcbench.Spot.Pose;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Opt-in dynamic-light screenshots and perf ({@code -Dmcbench.dynLights=true} for all, or a comma list of
 * {@code field,mobs,perf,cave,walk,nether}; off by default, so the standard plan is unchanged), owned by the mcopt-shade M3
 * dynamic lights work (branch m3-lights). Everything summoned carries the tag {@code mcbench_dl} and is killed before and
 * after (dropped items near the sites too); nothing is built. The player is in creative mode with a torch in the main hand
 * and a soul lantern in the off hand while these run, the HUD shown so the first-person hand renders (screenshots are read
 * before the GUI draws); spectator mode, empty hands, peaceful and the hidden HUD come back afterwards.
 * <ul>
 * <li>{@code dl_field_hand} (night 18000): standing on a flat dry patch 30-60 blocks from the torch grid (no placed light
 * near), looking 30 degrees down: the held lights on the grass.</li>
 * <li>{@code dl_field_mobs} (18000): the same patch from 9 blocks with a zombie holding a torch, a skeleton holding a soul
 * lantern, a villager holding a lantern, a burning zombie (visual fire), a small magma cube, a blaze, and dropped torch,
 * glowstone and sea lantern items: 9 lights + the hands.</li>
 * <li>{@code perf}: the recorded perf phase "lights" (results.json perf.lights / gpu.lights) on dl_field_mobs, panning
 * +-15 degrees for -Dmcbench.lightsSeconds (10) s: price it against a run with -Dmcopt.shade.dynLights=false.</li>
 * <li>{@code dl_cave_hand}: the biggest dark cave found under the area (no sky light, no block light), looking along its
 * longest free direction.</li>
 * <li>{@code dl_cave_walk}: walking that way at 3.5 blocks/s, shot after 1.5 s (the light moving with the player).</li>
 * <li>{@code dl_nether}: in the Nether (a floor near 0, 0 found after arriving), two blazes and a magma cube 4-7 blocks
 * ahead, the hands lit.</li>
 * </ul>
 * Poses go to results.json "dynLights".
 */
final class LightShots {
	static final String SPEC = System.getProperty("mcbench.dynLights", "").trim();
	static final String TAG = "mcbench_dl";
	static final Map<String, Object> JSON = new LinkedHashMap<>();
	static Pose field, cave;
	static double caveDx, caveDz;
	static int fieldY;

	private LightShots() {
	}

	static boolean on(String scene) {
		if (SPEC.isEmpty() || SPEC.equals("false")) return false;
		if (SPEC.equals("true") || SPEC.equals("all")) return true;
		for (String s : SPEC.split(",")) if (s.trim().equals(scene)) return true;
		return false;
	}

	private static void cmd(MinecraftServer s, String c) {
		BenchDriver.cmd(s, c);
	}

	private static void run(MinecraftServer s, String c) {
		s.getCommands().performPrefixedCommand(s.createCommandSourceStack().withSuppressedOutput(), c);
	}

	/** Called from BenchDriver.buildPlan after the other shots. */
	static void plan(java.util.Deque<BenchDriver.Step> steps) {
		if (!(on("field") || on("mobs") || on("perf") || on("cave") || on("walk") || on("nether"))) return;
		BenchDriver.results.put("dynLights", JSON);
		steps.add(new BenchDriver.Scene("dl-site", 18000, false, () -> BenchDriver.spot.night));
		steps.add(new BenchDriver.Settle("dl-site", false, 3, 120));
		steps.add(new BenchDriver.Step("dl-find", 60) {
			CompletableFuture<Void> f;

			@Override
			void start(Minecraft mc, long now) {
				MinecraftServer s = BenchDriver.server(mc);
				f = CompletableFuture.runAsync(() -> find(s, s.overworld()), s);
			}

			@Override
			boolean poll(Minecraft mc, long now) throws Exception {
				if (!f.isDone()) return false;
				f.get();
				BenchDriver.log("dynamic-light field " + field + ", cave " + cave + " dir " + caveDx + "," + caveDz);
				return true;
			}
		});
		steps.add(handsOn());
		if (on("field")) shot(steps, "dl_field_hand", 18000, () -> new Pose(field.x(), fieldY, field.z(), field.yaw(), 30f));
		if (on("mobs") || on("perf")) {
			steps.add(new BenchDriver.Step("dl-summon", 0) {
				@Override
				void start(Minecraft mc, long now) {
					summonField(BenchDriver.server(mc));
				}

				@Override
				boolean poll(Minecraft mc, long now) {
					return BenchDriver.secs(now - BenchDriver.curStart) >= 1.0;
				}
			});
			Supplier<Pose> mobs = () -> mobsPose(0f);
			if (on("mobs")) shot(steps, "dl_field_mobs", 18000, mobs);
			if (on("perf")) perf(steps, mobs);
		}
		if (on("cave")) {
			shot(steps, "dl_cave_hand", 18000, () -> cave != null ? cave : field);
		}
		if (on("walk")) walk(steps);
		if (on("nether")) nether(steps);
		steps.add(new BenchDriver.Step("dl-cleanup", 0) {
			@Override
			void start(Minecraft mc, long now) {
				MinecraftServer s = BenchDriver.server(mc);
				cleanup(s);
				cmd(s, "item replace entity @a weapon.mainhand with minecraft:air");
				cmd(s, "item replace entity @a weapon.offhand with minecraft:air");
				cmd(s, "difficulty peaceful");
				cmd(s, "gamemode spectator @a");
				BenchDriver.hideHud(mc);
			}

			@Override
			boolean poll(Minecraft mc, long now) {
				return BenchDriver.secs(now - BenchDriver.curStart) >= 1.0;
			}
		});
	}

	private static Pose mobsPose(float yawOffset) {
		return new Pose(field.x(), fieldY + 1.0, field.z() - 9.0, yawOffset, 14f);
	}

	/** Creative, torch + soul lantern, HUD shown (kept shown by every hold of ours). */
	private static BenchDriver.Step handsOn() {
		return new BenchDriver.Step("dl-hands-on", 0) {
			@Override
			void start(Minecraft mc, long now) {
				MinecraftServer s = BenchDriver.server(mc);
				cleanup(s);
				cmd(s, "difficulty easy"); // hostile mobs (NoAI) survive the shots
				cmd(s, "gamemode creative @a");
				cmd(s, "item replace entity @a weapon.mainhand with minecraft:torch");
				cmd(s, "item replace entity @a weapon.offhand with minecraft:soul_lantern");
			}

			@Override
			boolean poll(Minecraft mc, long now) {
				showHud(mc);
				return BenchDriver.secs(now - BenchDriver.curStart) >= 1.0;
			}
		};
	}

	static void showHud(Minecraft mc) {
		if (mc.gui.screen() != null) mc.gui.setScreen(null);
		if (mc.gui.hud.isHidden()) mc.gui.hud.toggle();
	}

	private static void shot(java.util.Deque<BenchDriver.Step> steps, String name, int time, Supplier<Pose> p) {
		steps.add(new BenchDriver.Scene(name, time, false, p));
		steps.add(new BenchDriver.Settle(name, false, 3, 120));
		steps.add(hold(name, Math.max(BenchDriver.SHOT_WAIT, 2.5)));
		steps.add(new BenchDriver.Shot(name));
		steps.add(record(name, time, p));
	}

	private static BenchDriver.Step hold(String name, double sec) {
		return new BenchDriver.Step("hold-" + name, 0) {
			@Override
			boolean poll(Minecraft mc, long now) {
				showHud(mc);
				return BenchDriver.secs(now - BenchDriver.curStart) >= sec;
			}
		};
	}

	private static BenchDriver.Step record(String name, int time, Supplier<Pose> p) {
		return new BenchDriver.Step("dl-record-" + name, 0) {
			@Override
			boolean poll(Minecraft mc, long now) {
				Map<String, Object> m = p.get().json();
				m.put("time", time);
				JSON.put(name, m);
				return true;
			}
		};
	}

	static void cleanup(MinecraftServer s) {
		run(s, "kill @e[tag=" + TAG + "]");
		if (field != null) {
			run(s, String.format(Locale.ROOT, "execute in minecraft:overworld positioned %.1f %d %.1f run kill @e[type=item,distance=..24]", field.x(), fieldY, field.z()));
		}
	}

	/** Server thread: the field patch and the cave. */
	static void find(MinecraftServer s, ServerLevel l) {
		Spot spot = BenchDriver.spot;
		int tx = spot.torchX, tz = spot.torchZ;
		// field: a flat dry 9x13 patch (mobs line 4..8 blocks north of the camera) 30..60 blocks from the torch grid centre
		double best = -1;
		for (int r = 30; r <= 60 && best < 0; r += 6) {
			for (int a = 0; a < 24; a++) {
				double ang = a * Math.PI / 12;
				int x = tx + (int) Math.round(Math.cos(ang) * r), z = tz + (int) Math.round(Math.sin(ang) * r);
				int y = BenchDriver.landY(l, x, z);
				if (y == Integer.MIN_VALUE) continue;
				boolean flat = true;
				for (int dx = -4; dx <= 4 && flat; dx += 2) {
					for (int dz = -10; dz <= 8 && flat; dz += 2) {
						int g = BenchDriver.landY(l, x + dx, z + dz);
						if (g == Integer.MIN_VALUE || Math.abs(g - y) > 1) flat = false;
					}
				}
				if (!flat) continue;
				best = r;
				fieldY = y;
				field = new Pose(x + 0.5, y, z + 0.5, 0f, 30f);
				break;
			}
		}
		if (field == null) { // fall back to the torch grid itself
			fieldY = BenchDriver.landY(l, tx, tz);
			if (fieldY == Integer.MIN_VALUE) fieldY = l.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, tx, tz);
			field = new Pose(tx + 0.5, fieldY, tz + 0.5, 0f, 30f);
		}
		JSON.put("field", field.json());
		// cave: the darkest roomiest underground air pocket within 96 blocks (feet + 2 more air, floor, no sky / block light)
		int cx = spot.cx, cz = spot.cz;
		int bestAir = 0;
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (int x = cx - 96; x <= cx + 96; x += 4) {
			for (int z = cz - 96; z <= cz + 96; z += 4) {
				if (!l.hasChunk(x >> 4, z >> 4)) continue;
				int top = l.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
				for (int y = Math.max(l.getMinY() + 8, -50); y < Math.min(top - 10, 50); y++) {
					m.set(x, y, z);
					if (!l.getBlockState(m).isAir()) continue;
					if (!l.getBlockState(m.set(x, y - 1, z)).isSolidRender()) continue;
					if (!l.getBlockState(m.set(x, y + 1, z)).isAir() || !l.getBlockState(m.set(x, y + 2, z)).isAir()) continue;
					m.set(x, y + 1, z);
					if (l.getBrightness(LightLayer.SKY, m) > 0 || l.getBrightness(LightLayer.BLOCK, m) > 0) continue;
					int air = 0;
					for (int dx = -6; dx <= 6; dx += 2) {
						for (int dy = 0; dy <= 4; dy += 2) {
							for (int dz = -6; dz <= 6; dz += 2) if (l.getBlockState(m.set(x + dx, y + dy, z + dz)).isAir()) air++;
						}
					}
					if (air > bestAir) {
						bestAir = air;
						// the longest free horizontal direction at eye level (8 directions, up to 20 blocks)
						int bestLen = -1;
						double bdx = 0, bdz = 1;
						for (int d = 0; d < 8; d++) {
							double ang = d * Math.PI / 4, ux = -Math.sin(ang), uz = Math.cos(ang);
							int len = 0;
							while (len < 20 && l.getBlockState(m.set((int) Math.floor(x + 0.5 + ux * (len + 1)), y + 1, (int) Math.floor(z + 0.5 + uz * (len + 1)))).isAir()
								&& l.getBlockState(m.set((int) Math.floor(x + 0.5 + ux * (len + 1)), y, (int) Math.floor(z + 0.5 + uz * (len + 1)))).isAir()) len++;
							if (len > bestLen) {
								bestLen = len;
								bdx = ux;
								bdz = uz;
							}
						}
						caveDx = bdx;
						caveDz = bdz;
						float yaw = (float) Math.toDegrees(Math.atan2(-bdx, bdz));
						cave = new Pose(x + 0.5, y, z + 0.5, yaw, 12f);
					}
				}
			}
		}
		if (cave != null) {
			Map<String, Object> cj = cave.json();
			cj.put("air", bestAir);
			JSON.put("cave", cj);
		}
	}

	/** Server thread (a command step): the field's lights, a line 4..8 blocks north of the mobs camera. */
	static void summonField(MinecraftServer s) {
		double x = field.x(), z = field.z();
		int y = fieldY;
		String base = "NoAI:1b,Silent:1b,PersistenceRequired:1b,Invulnerable:1b,Tags:[\"" + TAG + "\"],Rotation:[180f,0f]";
		run(s, String.format(Locale.ROOT, "summon minecraft:zombie %.2f %d %.2f {%s,equipment:{mainhand:{id:\"minecraft:torch\",count:1}}}", x - 4, y, z - 3, base));
		run(s, String.format(Locale.ROOT, "summon minecraft:skeleton %.2f %d %.2f {%s,equipment:{mainhand:{id:\"minecraft:soul_lantern\",count:1}}}", x - 1.5, y, z - 1, base));
		run(s, String.format(Locale.ROOT, "summon minecraft:villager %.2f %d %.2f {%s,equipment:{mainhand:{id:\"minecraft:lantern\",count:1}}}", x + 1.5, y, z - 1, base));
		run(s, String.format(Locale.ROOT, "summon minecraft:zombie %.2f %d %.2f {%s,HasVisualFire:1b}", x + 4, y, z - 3, base));
		run(s, String.format(Locale.ROOT, "summon minecraft:magma_cube %.2f %d %.2f {%s,Size:0}", x - 3, y, z + 1.5, base));
		run(s, String.format(Locale.ROOT, "summon minecraft:blaze %.2f %d %.2f {%s,NoGravity:1b}", x + 3, y + 2.5, z + 1.5, base));
		String[] items = {"torch", "glowstone", "sea_lantern"};
		for (int i = 0; i < items.length; i++) {
			run(s, String.format(Locale.ROOT, "summon minecraft:item %.2f %.2f %.2f {NoGravity:1b,Age:-32768,PickupDelay:32767,Invulnerable:1b,Tags:[\"%s\"],Item:{id:\"minecraft:%s\",count:1}}",
				x - 2.5 + i * 2.5, y + 0.1, z - 4.5, TAG, items[i]));
		}
	}

	/** Perf phase "lights": the mobs view panning +-15 degrees (hands + 9 lights in view). */
	private static void perf(java.util.Deque<BenchDriver.Step> steps, Supplier<Pose> start) {
		double sec = Double.parseDouble(System.getProperty("mcbench.lightsSeconds", "10"));
		steps.add(new BenchDriver.Scene("lights", 18000, false, start));
		steps.add(new BenchDriver.Settle("lights", false, 3, 120));
		steps.add(hold("lights-warm", 2));
		steps.add(new BenchDriver.Step("lights", 0) {
			@Override
			void start(Minecraft mc, long now) {
				BenchDriver.setPhase("LIGHTS");
				Pose o = start.get();
				long t0 = now;
				BenchDriver.pose = t -> new Pose(o.x(), o.y(), o.z(), (float) (15.0 * Math.sin(BenchDriver.secs(t - t0) * 2 * Math.PI / sec)), o.pitch());
				BenchDriver.recPhase = BenchDriver.REC_PHASES.indexOf("lights");
				BenchDriver.phaseRanges.put("lights", new long[] {now, 0});
				BenchDriver.log("perf lights " + sec + " s");
			}

			@Override
			boolean poll(Minecraft mc, long now) {
				showHud(mc);
				if (BenchDriver.secs(now - BenchDriver.curStart) < sec) return false;
				BenchDriver.recPhase = -1;
				BenchDriver.phaseRanges.get("lights")[1] = now;
				return true;
			}
		});
	}

	/** Walking along the cave at 3.5 blocks/s for 1.5 s, shot while moving. */
	private static void walk(java.util.Deque<BenchDriver.Step> steps) {
		String name = "dl_cave_walk";
		Supplier<Pose> p = () -> cave != null ? cave : field;
		steps.add(new BenchDriver.Scene(name, 18000, false, p));
		steps.add(new BenchDriver.Settle(name, false, 3, 120));
		steps.add(hold(name, 2));
		steps.add(new BenchDriver.Step("move-" + name, 0) {
			@Override
			void start(Minecraft mc, long now) {
				Pose o = p.get();
				double vx = cave != null ? caveDx * 3.5 : 0, vz = cave != null ? caveDz * 3.5 : 3.5;
				long t0 = now;
				BenchDriver.pose = t -> {
					double s = BenchDriver.secs(t - t0);
					return new Pose(o.x() + vx * s, o.y(), o.z() + vz * s, o.yaw(), o.pitch());
				};
				Map<String, Object> m = o.json();
				m.put("velocity", new double[] {vx, 0, vz});
				JSON.put(name, m);
			}

			@Override
			boolean poll(Minecraft mc, long now) {
				showHud(mc);
				return BenchDriver.secs(now - BenchDriver.curStart) >= 1.5;
			}
		});
		steps.add(new BenchDriver.Shot(name));
	}

	/** The Nether: arrive, find a floor near 0,0, summon two blazes and a magma cube ahead, shoot, come back. */
	private static void nether(java.util.Deque<BenchDriver.Step> steps) {
		String name = "dl_nether";
		Pose[] at = new Pose[1];
		steps.add(new BenchDriver.Step("dl-nether-go", 180) {
			int state;
			long since;
			CompletableFuture<Pose> f;

			@Override
			void start(Minecraft mc, long now) {
				BenchDriver.setPhase("SETTLE");
				BenchDriver.pose = null;
				cmd(BenchDriver.server(mc), "execute in minecraft:the_nether run tp @a 0.5 70 0.5 0 0");
			}

			@Override
			boolean poll(Minecraft mc, long now) throws Exception {
				showHud(mc);
				String dim = mc.level == null ? "" : mc.level.dimension().toString();
				if (!dim.contains("the_nether")) return false;
				if (state == 0) {
					state = 1;
					since = now;
					return false;
				}
				if (state == 1) {
					if (BenchDriver.secs(now - since) < 6) return false;
					MinecraftServer s = BenchDriver.server(mc);
					ServerLevel nl = s.getLevel(net.minecraft.world.level.Level.NETHER);
					f = CompletableFuture.supplyAsync(() -> netherFloor(s, nl), s);
					state = 2;
					return false;
				}
				if (!f.isDone()) return false;
				at[0] = f.get();
				BenchDriver.pose = t -> at[0];
				BenchDriver.log("nether pose " + at[0]);
				return true;
			}
		});
		steps.add(new BenchDriver.Settle(name, false, 4, 120));
		steps.add(hold(name, 3));
		steps.add(new BenchDriver.Shot(name));
		steps.add(new BenchDriver.Step("dl-nether-back", 120) {
			@Override
			void start(Minecraft mc, long now) {
				MinecraftServer s = BenchDriver.server(mc);
				Map<String, Object> m = at[0] != null ? at[0].json() : new LinkedHashMap<>();
				JSON.put(name, m);
				run(s, "execute in minecraft:the_nether run kill @e[tag=" + TAG + "]");
				Pose o = BenchDriver.spot.overview;
				BenchDriver.pose = null;
				cmd(s, String.format(Locale.ROOT, "execute in minecraft:overworld run tp @a %.3f %.3f %.3f %.2f %.2f", o.x(), o.y(), o.z(), o.yaw(), o.pitch()));
			}

			@Override
			boolean poll(Minecraft mc, long now) {
				String dim = mc.level == null ? "" : mc.level.dimension().toString();
				return dim.contains("overworld") && BenchDriver.secs(now - BenchDriver.curStart) >= 2;
			}
		});
	}

	/** Server thread: a standing spot (solid floor, 3 air) near 0,0 in the Nether with room ahead; summons there. */
	static Pose netherFloor(MinecraftServer s, ServerLevel l) {
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		Pose best = null;
		int bestRoom = -1;
		for (int x = -24; x <= 24; x += 3) {
			for (int z = -24; z <= 24; z += 3) {
				if (!l.hasChunk(x >> 4, z >> 4)) continue;
				for (int y = 32; y < 110; y++) {
					if (!l.getBlockState(m.set(x, y - 1, z)).isSolidRender() || !l.getBlockState(m.set(x, y, z)).isAir()
						|| !l.getBlockState(m.set(x, y + 1, z)).isAir() || !l.getBlockState(m.set(x, y + 2, z)).isAir()) continue;
					int room = 0; // free ground ahead (+z) for the mobs
					for (int dz = 2; dz <= 8; dz++) {
						for (int dx = -3; dx <= 3; dx += 3) {
							if (l.getBlockState(m.set(x + dx, y, z + dz)).isAir() && l.getBlockState(m.set(x + dx, y + 1, z + dz)).isAir()) room++;
						}
					}
					if (room > bestRoom) {
						bestRoom = room;
						best = new Pose(x + 0.5, y, z + 0.5, 0f, 10f);
					}
				}
			}
		}
		if (best == null) best = new Pose(0.5, 70, 0.5, 0f, 10f);
		String base = "NoAI:1b,Silent:1b,PersistenceRequired:1b,Invulnerable:1b,Tags:[\"" + TAG + "\"],Rotation:[180f,0f]";
		String in = "execute in minecraft:the_nether run ";
		run(s, in + String.format(Locale.ROOT, "summon minecraft:blaze %.2f %.2f %.2f {%s,NoGravity:1b}", best.x() - 2, best.y() + 1.5, best.z() + 6, base));
		run(s, in + String.format(Locale.ROOT, "summon minecraft:blaze %.2f %.2f %.2f {%s,NoGravity:1b}", best.x() + 2.5, best.y() + 2.0, best.z() + 8, base));
		run(s, in + String.format(Locale.ROOT, "summon minecraft:magma_cube %.2f %.2f %.2f {%s,Size:1}", best.x() + 0.5, best.y(), best.z() + 4, base));
		run(s, in + String.format(Locale.ROOT, "tp @a %.3f %.3f %.3f 0 10", best.x(), best.y(), best.z()));
		return best;
	}
}
