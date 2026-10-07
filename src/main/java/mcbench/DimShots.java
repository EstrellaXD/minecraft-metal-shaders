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
import java.util.function.Predicate;
import mcbench.Spot.Pose;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluids;

/**
 * Opt-in sky and dimension screenshots ({@code -Dmcbench.dims=true} or a comma list; off by default, so the standard plan is
 * unchanged), owned by the M3 sky/dimensions/XDR work (mcopt-shade branch m3-skydims). After the standard shots (skip them with
 * {@code -Dmcbench.standardShots=false}) poses are found by the integrated server (read only, except {@code snow}, which puts
 * one powder snow block at the camera high in the air and removes it after the shot), and shot like extraShots:
 * <ul>
 * <li>{@code nether}: open views in the Nether biomes nearest the origin: {@code nether_wastes}, {@code nether_crimson},
 * {@code nether_warped}, {@code nether_soul}, {@code nether_basalt} (each skipped when not found within 2000 blocks), and
 * {@code nether_lava_sea} (low over the lava sea).</li>
 * <li>{@code end}: {@code end_island} (the main island and its pillars from above its edge) and {@code end_void} (from the
 * island's edge out over the void: sky, nebula, beams).</li>
 * <li>{@code aurora}: {@code aurora_snow} (midnight in the nearest snowy biome, looking north and up; the aurora shows with
 * {@code -Dmcopt.shade.aurora=always}, or on its own on a lucky night) and {@code aurora_snow_low} (same, toward the horizon).</li>
 * <li>{@code lava}: {@code media_lava} (the camera one block inside the Nether's lava sea).</li>
 * <li>{@code snow}: {@code media_powder_snow} (the camera inside a powder snow block, overworld noon).</li>
 * </ul>
 * The player ends back at the overworld spot. Chosen poses go to results.json "dimShots". Each Nether/End scene also records
 * a short perf segment when {@code -Dmcbench.dimsPerf=SECONDS} is set (phases "nether"/"end" in perf/gpu: spin 360 degrees).
 */
final class DimShots {
	static final String SPEC = System.getProperty("mcbench.dims", "").trim();
	static final boolean ON = !SPEC.isEmpty() && !SPEC.equals("false");

	private DimShots() {
	}

	static boolean want(String kind) {
		if (SPEC.equals("true") || SPEC.equals("all")) return true;
		for (String s : SPEC.split(",")) if (s.trim().equals(kind)) return true;
		return false;
	}

	record Found(String dim, Pose pose, int time, String setup, String teardown) {
	}

	/** Called from BenchDriver.buildPlan after the standard shots. */
	static void plan(java.util.Deque<BenchDriver.Step> steps) {
		if (!ON) return;
		steps.add(new BenchDriver.Step("dims-find", 240) {
			CompletableFuture<Map<String, Found>> f;

			@Override
			void start(Minecraft mc, long now) {
				MinecraftServer s = BenchDriver.server(mc);
				f = CompletableFuture.supplyAsync(() -> find(s), s);
			}

			@Override
			boolean poll(Minecraft mc, long now) throws Exception {
				if (!f.isDone()) return false;
				Map<String, Found> found = f.get();
				Map<String, Object> json = new LinkedHashMap<>();
				List<BenchDriver.Step> extra = new ArrayList<>();
				for (var e : found.entrySet()) {
					Found x = e.getValue();
					Map<String, Object> m = x.pose.json();
					m.put("dimension", x.dim);
					m.put("time", x.time);
					json.put(e.getKey(), m);
					BenchDriver.log("dim scene " + e.getKey() + " in " + x.dim + " " + x.pose);
					extra.add(new DimScene(e.getKey(), x));
					extra.add(new BenchDriver.Settle(e.getKey(), false, 4, 40)); // Nether chunks keep loading around: 40 s is plenty
					extra.add(new BenchDriver.Hold(e.getKey(), Math.max(BenchDriver.SHOT_WAIT, 3)));
					extra.add(new BenchDriver.Shot(e.getKey()));
					if (!x.teardown.isEmpty()) extra.add(new Cmd("teardown-" + e.getKey(), x.teardown));
					// opt-in perf spin at the first Nether / End scene (-Dmcbench.dimsPerf=SECONDS): phases "nether" / "end"
					String phase = e.getKey().equals("nether_wastes") ? "nether" : e.getKey().equals("end_island") ? "end" : null;
					if (phase != null && PERF > 0) extra.add(new DimSpin(phase, x.pose, PERF));
				}
				// back to the overworld spot (the saved world must not start the next run in another dimension)
				Pose o = BenchDriver.spot.overview;
				extra.add(new DimScene("dims-return", new Found("minecraft:overworld", o, 6000, "", "")));
				extra.add(new BenchDriver.Settle("dims-return", false, 2, 60));
				BenchDriver.results.put("dimShots", json);
				for (int i = extra.size() - 1; i >= 0; i--) steps.addFirst(extra.get(i));
				return true;
			}
		});
	}

	/** Teleports into the scene's dimension at its pose (and runs its setup command). */
	static final class DimScene extends BenchDriver.Step {
		final Found x;

		DimScene(String name, Found x) {
			super(name, 0);
			this.x = x;
		}

		@Override
		void start(Minecraft mc, long now) {
			BenchDriver.setPhase("SETTLE");
			mcopt.bench.Bench.settledAt = 0;
			MinecraftServer s = BenchDriver.server(mc);
			BenchDriver.cmd(s, "time set " + x.time);
			BenchDriver.cmd(s, "weather clear");
			BenchDriver.hideHud(mc);
			Pose p = x.pose;
			BenchDriver.pose = t -> p;
			BenchDriver.cmd(s, String.format(Locale.ROOT, "execute in %s run tp @a %.3f %.3f %.3f %.2f %.2f", x.dim, p.x(), p.y(), p.z(), p.yaw(), p.pitch()));
			if (!x.setup.isEmpty()) BenchDriver.cmd(s, x.setup);
			BenchDriver.log("scene " + name + " in " + x.dim + " pose " + p);
		}

		@Override
		boolean poll(Minecraft mc, long now) {
			// wait until the client is in the target dimension
			return mc.level != null && mc.level.dimension().toString().contains(x.dim) || BenchDriver.secs(now - BenchDriver.curStart) > 20;
		}
	}

	static final double PERF = Double.parseDouble(System.getProperty("mcbench.dimsPerf", "0"));

	/** A recorded perf segment at a dimension pose: 360 degrees of yaw over sec seconds (like the overworld spin). */
	static final class DimSpin extends BenchDriver.Step {
		final Pose o;
		final double sec;

		DimSpin(String phase, Pose o, double sec) {
			super(phase, 0);
			this.o = o;
			this.sec = sec;
		}

		@Override
		void start(Minecraft mc, long now) {
			BenchDriver.setPhase("SPIN");
			long t0 = now;
			BenchDriver.pose = t -> new Pose(o.x(), o.y(), o.z(), (float) (o.yaw() + 360.0 * Math.min(1, BenchDriver.secs(t - t0) / sec)), o.pitch());
			BenchDriver.recPhase = BenchDriver.REC_PHASES.indexOf(name);
			BenchDriver.phaseRanges.put(name, new long[] {now, 0});
			BenchDriver.log("perf " + name + " " + sec + " s");
		}

		@Override
		boolean poll(Minecraft mc, long now) {
			if (BenchDriver.secs(now - BenchDriver.curStart) < sec) return false;
			BenchDriver.recPhase = -1;
			BenchDriver.phaseRanges.get(name)[1] = now;
			Pose last = BenchDriver.pose.apply(now);
			BenchDriver.pose = t -> last;
			return true;
		}
	}

	static final class Cmd extends BenchDriver.Step {
		final String c;

		Cmd(String name, String c) {
			super(name, 0);
			this.c = c;
		}

		@Override
		void start(Minecraft mc, long now) {
			BenchDriver.cmd(BenchDriver.server(mc), c);
		}

		@Override
		boolean poll(Minecraft mc, long now) {
			return true;
		}
	}

	// ---- finding poses (server thread) ------------------------------------------------------------------------------------
	static Map<String, Found> find(MinecraftServer s) {
		Map<String, Found> out = new LinkedHashMap<>();
		if (want("nether") || want("lava")) {
			ServerLevel n = s.getLevel(Level.NETHER);
			if (n != null) nether(n, out);
		}
		if (want("end")) {
			ServerLevel e = s.getLevel(Level.END);
			if (e != null) end(e, out);
		}
		if (want("aurora")) aurora(s.overworld(), out);
		if (want("snow")) {
			Pose o = BenchDriver.spot.overview;
			int x = (int) Math.floor(o.x()), z = (int) Math.floor(o.z()), y = 230;
			Pose p = new Pose(x + 0.5, y + 0.1 - Spot.EYE + 0.5, z + 0.5, 30f, 5f);
			out.put("media_powder_snow", new Found("minecraft:overworld", p, 6000,
				String.format(Locale.ROOT, "setblock %d %d %d minecraft:powder_snow", x, y, z), String.format(Locale.ROOT, "setblock %d %d %d minecraft:air", x, y, z)));
		}
		return out;
	}

	private static boolean air(ServerLevel l, int x, int y, int z) {
		return l.getBlockState(new BlockPos(x, y, z)).isAir();
	}

	/** Free blocks along a horizontal ray from (x, y, z) (up to max). */
	private static int reach(ServerLevel l, int x, int y, int z, double yaw, int max) {
		double dx = -Math.sin(Math.toRadians(yaw)), dz = Math.cos(Math.toRadians(yaw));
		for (int i = 1; i <= max; i++) {
			int bx = (int) Math.floor(x + 0.5 + dx * i), bz = (int) Math.floor(z + 0.5 + dz * i);
			if (!air(l, bx, y, bz)) return i;
		}
		return max;
	}

	/** An open pose near (cx, cz) in the Nether: eye in air, looking along its most open direction, 10 degrees down; null if none. */
	private static Pose openNether(ServerLevel l, int cx, int cz) {
		double best = 0;
		Pose pose = null;
		for (int dx = -24; dx <= 24; dx += 8) {
			for (int dz = -24; dz <= 24; dz += 8) {
				int x = cx + dx, z = cz + dz;
				l.getChunk(x >> 4, z >> 4); // generate
				for (int y = 40; y <= 100; y += 3) {
					if (!air(l, x, y, z) || !air(l, x, y + 1, z) || !air(l, x, y - 1, z)) continue;
					for (int a = 0; a < 360; a += 45) {
						int r = reach(l, x, y, z, a, 48);
						int below = 0; // something to look at below the view (not a bottomless void)
						for (int k = 2; k < 30 && below == 0; k++) if (!air(l, x, y - k, z)) below = k;
						double score = r + (below > 0 && below < 25 ? 8 : 0) - Math.abs(y - 60) * 0.1;
						if (score > best) {
							best = score;
							pose = new Pose(x + 0.5, y + 0.2 - Spot.EYE + 1.6, z + 0.5, a, 12f);
						}
					}
				}
			}
		}
		return best >= 24 ? pose : null;
	}

	private static void nether(ServerLevel l, Map<String, Found> out) {
		BlockPos origin = new BlockPos(0, 64, 0);
		String[] names = {"nether_wastes", "nether_crimson", "nether_warped", "nether_soul", "nether_basalt"};
		List<net.minecraft.resources.ResourceKey<Biome>> keys = List.of(Biomes.NETHER_WASTES, Biomes.CRIMSON_FOREST, Biomes.WARPED_FOREST,
			Biomes.SOUL_SAND_VALLEY, Biomes.BASALT_DELTAS);
		for (int i = 0; i < names.length && want("nether"); i++) {
			var key = keys.get(i);
			Predicate<Holder<Biome>> test = h -> h.is(key);
			var hit = l.findClosestBiome3d(test, origin, 2000, 32, 24);
			if (hit == null) continue;
			BlockPos b = hit.getFirst();
			Pose p = openNether(l, b.getX(), b.getZ());
			if (p != null) out.put(names[i], new Found("minecraft:the_nether", p, 6000, "", ""));
		}
		// the lava sea: a lava column at y 31 near the origin with air above
		BlockPos lava = null;
		search:
		for (int r = 0; r <= 160; r += 8) {
			for (int dx = -r; dx <= r; dx += 8) {
				for (int dz = -r; dz <= r; dz += 8) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
					l.getChunk(dx >> 4, dz >> 4);
					if (l.getFluidState(new BlockPos(dx, 30, dz)).getType() == Fluids.LAVA && air(l, dx, 33, dz) && air(l, dx, 36, dz)) {
						lava = new BlockPos(dx, 31, dz);
						break search;
					}
				}
			}
		}
		if (lava != null) {
			int best = 0;
			float yaw = 0;
			for (int a = 0; a < 360; a += 45) {
				int r = reach(l, lava.getX(), 34, lava.getZ(), a, 64);
				if (r > best) {
					best = r;
					yaw = a;
				}
			}
			if (want("nether")) out.put("nether_lava_sea", new Found("minecraft:the_nether", new Pose(lava.getX() + 0.5, 34.5 - Spot.EYE + 1.6, lava.getZ() + 0.5, yaw, 6f), 6000, "", ""));
			if (want("lava")) out.put("media_lava", new Found("minecraft:the_nether", new Pose(lava.getX() + 0.5, 29.6 - Spot.EYE + 1.6, lava.getZ() + 0.5, yaw, 0f), 6000, "", ""));
		}
	}

	private static int surface(ServerLevel l, int x, int z) {
		l.getChunk(x >> 4, z >> 4);
		return l.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
	}

	private static void end(ServerLevel l, Map<String, Found> out) {
		// the main island: from above its north-east edge looking at the centre (pillars), and from its south edge out over the void
		int h0 = Math.max(surface(l, 0, 0), 60);
		int e1 = surface(l, 70, 70);
		double y1 = Math.max(e1, h0) + 28;
		float yaw1 = (float) Math.toDegrees(Math.atan2(70, -70)); // toward (0, 0): yaw = atan2(-dx, dz) with dx = -70, dz = -70
		out.put("end_island", new Found("minecraft:the_end", new Pose(70.5, y1, 70.5, yaw1, 18f), 6000, "", ""));
		int z2 = 0;
		for (int z = 40; z <= 140; z += 4) {
			if (surface(l, 0, z) > l.getMinY() + 1) z2 = z;
		}
		int h2 = Math.max(surface(l, 0, Math.max(z2 - 4, 0)), 50);
		out.put("end_void", new Found("minecraft:the_end", new Pose(0.5, h2 + 3.0, z2 - 3.5, 0f, -6f), 6000, "", ""));
	}

	private static void aurora(ServerLevel l, Map<String, Found> out) {
		Pose o = BenchDriver.spot.overview;
		BlockPos origin = BlockPos.containing(o.x(), 80, o.z());
		var hit = l.findClosestBiome3d(h -> h.value().getBaseTemperature() < 0.05f && !h.is(Biomes.FROZEN_OCEAN) && !h.is(Biomes.DEEP_FROZEN_OCEAN),
			origin, 3000, 32, 64);
		int x = origin.getX(), z = origin.getZ();
		if (hit != null) {
			x = hit.getFirst().getX();
			z = hit.getFirst().getZ();
		}
		int y = surface(l, x, z);
		out.put("aurora_snow", new Found("minecraft:overworld", new Pose(x + 0.5, y + 3.0, z + 0.5, 180f, -32f), 18000, "", ""));
		out.put("aurora_snow_low", new Found("minecraft:overworld", new Pose(x + 0.5, y + 6.0, z + 0.5, 160f, -8f), 18000, "", ""));
	}
}
