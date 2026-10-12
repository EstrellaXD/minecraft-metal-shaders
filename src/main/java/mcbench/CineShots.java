/*
 * Copyright 2026 mcbench contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package mcbench;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongFunction;
import mcbench.Spot.Pose;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * Cinematic camera paths for showcase videos and stills (opt-in, {@code -Dmcbench.cine=<spec.json>}; added 2026-10-11 for
 * the "Metal Shaders" release videos). Pair it with mcopt's recorder, {@code -Dmcopt.rec=OUT.mp4 -Dmcopt.rec.phases=cine}:
 * only the clips are recorded (bench phase {@code CINE_<NAME>}), back to back in one file; OUT.csv has each frame's phase.
 * <p>Spec (JSON): {@code {"anchors": {...}, "items": [...]}}.
 * <ul>
 * <li>anchors: {@code "<name>": {"biome": "minecraft:cherry_grove", "near": [x, z], "radius": 4000, "dim": "minecraft:overworld"}}
 * (the middle of the nearest patch of that biome, sampled at the surface without generating chunks) or {@code {"at": [x, z]}}.
 * Found anchors go to results.json {@code cine.anchors}.</li>
 * <li>{@code {"type": "scout", "anchor": a, "r": 70, "h": 40, "dirs": 4, "time": 6000}}: stills around the anchor, h above
 * its surface, r out, looking at it ({@code scout_<anchor>_<deg>}).</li>
 * <li>{@code {"type": "still", "name": n, "anchor": a, "time": t, "weather": w, "pose": [x, y, z, yaw, pitch], "y": mode}}.</li>
 * <li>{@code {"type": "clip", "name": n, "anchor": a, "dim": d, "time": t, "weather": "clear|rain|thunder", "y": mode,
 * "smooth": 0.5, "keys": [[t, x, y, z, yaw, pitch], ...], "lookAt": [x, y, z] or [[t, x, y, z], ...], "face": "path",
 * "ease": true, "pre": 0.6, "post": 0.4, "wait": 0, "props": {k: v}, "hand": "torch+air", "setup": [cmd], "teardown": [cmd],
 * "events": [[t, cmd]], "stills": [t, ...], "advanceTime": false, "hud": "f3", "gui": 3, "hotbar": [item, ...]}}: settles at the start (and first at the end when the
 * path is long, so the chunks on the way are built), holds {@code wait} s, then records pre + the path + post as phase
 * {@code CINE_<NAME>}; afterwards the stills ({@code <name>_<t>}) at those path times, held still.</li>
 * </ul>
 * x and z are relative to the anchor; y per mode: {@code anchor} (default: eye height above the anchor's surface), {@code ground}
 * (above the terrain under the camera, leaves ignored, smoothed over {@code smooth} s: walks and terrain-following flights)
 * or {@code abs}. lookAt y is relative to the anchor's surface (abs in abs mode). A key's yaw or pitch may be null: with
 * {@code "face": "path"} yaw is the path's heading plus the key's yaw. Commands may use {ax} {ay} {az} (the anchor) and
 * {sx} {sy} {sz} (the clip's start).
 */
final class CineShots {
	static final String SPEC = System.getProperty("mcbench.cine", "").trim();
	static final Map<String, Object> JSON = new LinkedHashMap<>();
	static final Map<String, Object> ANCHOR_JSON = new LinkedHashMap<>();
	/** Resolved anchors: x, surface y, z, and the dimension. */
	static final Map<String, Anchor> ANCHORS = new LinkedHashMap<>();

	record Anchor(int x, int y, int z, String dim, String biome) {
	}

	private CineShots() {
	}

	static void plan(Deque<BenchDriver.Step> steps) {
		if (SPEC.isEmpty()) return;
		JsonObject spec;
		try {
			spec = JsonParser.parseString(Files.readString(Path.of(SPEC), StandardCharsets.UTF_8)).getAsJsonObject();
		} catch (Exception e) {
			throw new IllegalArgumentException("mcbench.cine: can't read " + SPEC + ": " + e, e);
		}
		BenchDriver.results.put("cine", JSON);
		JSON.put("spec", SPEC);
		JSON.put("anchors", ANCHOR_JSON);
		List<Object> clips = new ArrayList<>();
		JSON.put("clips", clips);
		JsonObject anchors = spec.has("anchors") ? spec.getAsJsonObject("anchors") : new JsonObject();
		for (JsonElement el : spec.getAsJsonArray("items")) {
			JsonObject it = el.getAsJsonObject();
			if (it.has("skip") && it.get("skip").getAsBoolean()) continue;
			String type = str(it, "type", "clip");
			String an = str(it, "anchor", null);
			for (String need : new String[] {an, str(it, "lookAnchor", null)}) {
				if (need != null && !ANCHORS.containsKey(need) && !PLANNED.contains(need)) {
					if (!anchors.has(need)) throw new IllegalArgumentException("mcbench.cine: unknown anchor " + need);
					PLANNED.add(need);
					steps.add(anchorStep(need, anchors.getAsJsonObject(need)));
				}
			}
			switch (type) {
				case "scout" -> scout(steps, it, an);
				case "still" -> still(steps, it, an);
				case "clip" -> clip(steps, it, an, clips);
				default -> throw new IllegalArgumentException("mcbench.cine: unknown item type " + type);
			}
		}
	}

	private static final List<String> PLANNED = new ArrayList<>();

	// ---------------------------------------------------------------------------------------------- spec helpers

	static String str(JsonObject o, String k, String d) {
		return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : d;
	}

	static double num(JsonObject o, String k, double d) {
		return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsDouble() : d;
	}

	static Anchor anchor(String name) {
		if (name == null) return new Anchor(0, 64, 0, "minecraft:overworld", "");
		Anchor a = ANCHORS.get(name);
		if (a == null) throw new IllegalStateException("mcbench.cine: anchor " + name + " not resolved");
		return a;
	}

	static ResourceKey<Level> dimKey(String dim) {
		return switch (dim) {
			case "minecraft:the_nether", "nether" -> Level.NETHER;
			case "minecraft:the_end", "end" -> Level.END;
			default -> Level.OVERWORLD;
		};
	}

	static String dimId(String dim) {
		return switch (dim) {
			case "minecraft:the_nether", "nether" -> "minecraft:the_nether";
			case "minecraft:the_end", "end" -> "minecraft:the_end";
			default -> "minecraft:overworld";
		};
	}

	static String fill(String cmd, Anchor a, Pose start) {
		String c = cmd.replace("{ax}", Integer.toString(a.x())).replace("{ay}", Integer.toString(a.y())).replace("{az}", Integer.toString(a.z()));
		if (start != null) {
			c = c.replace("{sx}", Long.toString(Math.round(start.x()))).replace("{sy}", Long.toString(Math.round(start.y())))
				.replace("{sz}", Long.toString(Math.round(start.z())));
		}
		return c;
	}

	/** Teleports into the dimension (a plain tp can't change it) and holds the pose there. */
	static void place(MinecraftServer s, String dim, Pose p) {
		BenchDriver.pose = t -> p;
		BenchDriver.cmd(s, String.format(Locale.ROOT, "execute in %s run tp @a %.3f %.3f %.3f %.2f %.2f", dimId(dim), p.x(), p.y(), p.z(), p.yaw(), p.pitch()));
	}

	// ---------------------------------------------------------------------------------------------- anchors

	private static BenchDriver.Step anchorStep(String an, JsonObject a) {
		return new BenchDriver.Step("cine-anchor-" + an, 180) {
			CompletableFuture<Anchor> f;

			@Override
			void start(Minecraft mc, long now) {
				BenchDriver.setPhase("SETUP");
				MinecraftServer s = BenchDriver.server(mc);
				String dim = dimId(str(a, "dim", "minecraft:overworld"));
				ServerLevel l = s.getLevel(dimKey(dim));
				f = CompletableFuture.supplyAsync(() -> resolve(l, dim, a), s);
			}

			@Override
			boolean poll(Minecraft mc, long now) throws Exception {
				if (!f.isDone()) return false;
				Anchor r = f.get();
				ANCHORS.put(an, r);
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("x", r.x());
				m.put("y", r.y());
				m.put("z", r.z());
				m.put("dim", r.dim());
				m.put("biome", r.biome());
				ANCHOR_JSON.put(an, m);
				BenchDriver.log("cine: anchor " + an + " = " + m);
				return true;
			}
		};
	}

	/** Server thread: the terrain height (first free block above it, leaves ignored), generating the chunk if needed. */
	static int height(ServerLevel l, int x, int z) {
		l.getChunk(x >> 4, z >> 4);
		return l.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
	}

	/** Server thread. */
	static Anchor resolve(ServerLevel l, String dim, JsonObject a) {
		if (a.has("at")) {
			JsonArray at = a.getAsJsonArray("at");
			int[] r = refine(l, a, at.get(0).getAsInt(), at.get(1).getAsInt());
			int x = r[0], z = r[1];
			int y = a.has("y") ? a.get("y").getAsInt() : height(l, x, z);
			return new Anchor(x, y, z, dim, l.getBiome(new net.minecraft.core.BlockPos(x, y, z)).getRegisteredName());
		}
		String id = a.get("biome").getAsString();
		int nx = 0, nz = 0;
		if (a.has("near")) {
			nx = a.getAsJsonArray("near").get(0).getAsInt();
			nz = a.getAsJsonArray("near").get(1).getAsInt();
		}
		int radius = (int) num(a, "radius", 4000);
		ChunkGenerator gen = l.getChunkSource().getGenerator();
		RandomState rs = l.getChunkSource().randomState();
		BiomeResolver br = gen.getBiomeSource().createUncachedResolver(rs);
		int cell = 32;
		int hx = Integer.MIN_VALUE, hz = 0;
		search:
		for (int ring = 0; ring * cell <= radius; ring++) {
			for (int i = -ring; i <= ring; i++) {
				for (int j = -ring; j <= ring; j++) {
					if (Math.max(Math.abs(i), Math.abs(j)) != ring) continue;
					int x = nx + i * cell, z = nz + j * cell;
					if (is(br, gen, l, rs, x, z, id)) {
						hx = x;
						hz = z;
						break search;
					}
				}
			}
		}
		if (hx == Integer.MIN_VALUE) throw new IllegalStateException("mcbench.cine: no " + id + " within " + radius + " of " + nx + "," + nz);
		// the middle of the patch: the matching sample nearest to the centroid of the matches around the hit
		List<int[]> hits = new ArrayList<>();
		int step = (int) num(a, "patchStep", 24), half = (int) num(a, "patchHalf", 8);
		double sx = 0, sz = 0;
		for (int i = -half; i <= half; i++) {
			for (int j = -half; j <= half; j++) {
				int x = hx + i * step, z = hz + j * step;
				if (is(br, gen, l, rs, x, z, id)) {
					hits.add(new int[] {x, z});
					sx += x;
					sz += z;
				}
			}
		}
		double cx = sx / hits.size(), cz = sz / hits.size();
		int[] best = hits.getFirst();
		double bd = Double.MAX_VALUE;
		for (int[] h : hits) {
			double d = (h[0] - cx) * (h[0] - cx) + (h[1] - cz) * (h[1] - cz);
			if (d < bd) {
				bd = d;
				best = h;
			}
		}
		int[] r = refine(l, a, best[0], best[1]);
		best = new int[] {r[0], r[1]};
		int y = height(l, best[0], best[1]);
		BenchDriver.log(String.format(Locale.ROOT, "cine: %s first at %d,%d, patch of %d samples (step %d), middle %d,%d y%d", id, hx, hz, hits.size(), step, best[0], best[1], y));
		return new Anchor(best[0], y, best[1], dim, id);
	}

	/**
	 * Server thread. "refine": "forest" moves the anchor to the densest dry-land canopy within refineRadius (default 96),
	 * "high" to the highest ground there; else it stays.
	 */
	static int[] refine(ServerLevel l, JsonObject a, int x, int z) {
		String how = str(a, "refine", "");
		if (how.isEmpty()) return new int[] {x, z};
		if (how.equals("water")) return water(l, a, x, z);
		int rr = (int) num(a, "refineRadius", 96);
		int bx = x, bz = z;
		double bs = -1e18;
		for (int dx = -rr; dx <= rr; dx += 8) {
			for (int dz = -rr; dz <= rr; dz += 8) {
				int cx = x + dx, cz = z + dz;
				double sc;
				if (how.equals("high")) sc = height(l, cx, cz);
				else {
					int n = 0;
					for (int i = -12; i <= 12; i += 4) {
						for (int j = -12; j <= 12; j += 4) {
							int g = height(l, cx + i, cz + j), top = l.getHeight(Heightmap.Types.MOTION_BLOCKING, cx + i, cz + j);
							boolean dry = l.getFluidState(new net.minecraft.core.BlockPos(cx + i, g - 1, cz + j)).isEmpty();
							if (dry && top - g >= 3) n++;
						}
					}
					sc = n - Math.hypot(dx, dz) / 48.0;
				}
				if (sc > bs) {
					bs = sc;
					bx = cx;
					bz = cz;
				}
			}
		}
		BenchDriver.log(String.format(Locale.ROOT, "cine: refine %s %d,%d -> %d,%d (score %.1f)", how, x, z, bx, bz, bs));
		return new int[] {bx, bz};
	}

	/**
	 * Server thread, "refine": "water": the nearest open water (a 9x9 patch all water at the surface) going out from the point
	 * along "toward": [dx, dz] between "min" and "max" blocks (default 40..260), up to "side" blocks (64) to either side.
	 */
	static int[] water(ServerLevel l, JsonObject a, int x, int z) {
		double dx = 1, dz = 0;
		if (a.has("toward")) {
			dx = a.getAsJsonArray("toward").get(0).getAsDouble();
			dz = a.getAsJsonArray("toward").get(1).getAsDouble();
		}
		double len = Math.hypot(dx, dz);
		dx /= len;
		dz /= len;
		int min = (int) num(a, "min", 40), max = (int) num(a, "max", 260), side = (int) num(a, "side", 64);
		for (int t = min; t <= max; t += 4) {
			for (int k = 0; k <= side; k += 8) {
				for (int sgn = -1; sgn <= 1; sgn += 2) {
					if (k == 0 && sgn > 0) continue;
					int cx = (int) Math.round(x + dx * t - dz * k * sgn), cz = (int) Math.round(z + dz * t + dx * k * sgn);
					boolean open = true;
					for (int i = -4; i <= 4 && open; i += 2) {
						for (int j = -4; j <= 4 && open; j += 2) {
							int h = height(l, cx + i, cz + j);
							open = !l.getFluidState(new net.minecraft.core.BlockPos(cx + i, h - 1, cz + j)).isEmpty();
						}
					}
					if (open) {
						BenchDriver.log(String.format(Locale.ROOT, "cine: refine water %d,%d -> %d,%d (%d out)", x, z, cx, cz, t));
						return new int[] {cx, cz};
					}
				}
			}
		}
		BenchDriver.log("cine: refine water: none found, kept " + x + "," + z);
		return new int[] {x, z};
	}

	private static boolean is(BiomeResolver br, ChunkGenerator gen, ServerLevel l, RandomState rs, int x, int z, String id) {
		int y = gen.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, l, rs);
		Holder<Biome> b = br.getNoiseBiome(x >> 2, (y - 2) >> 2, z >> 2);
		return b.getRegisteredName().equals(id);
	}

	// ---------------------------------------------------------------------------------------------- scout and stills

	private static void scout(Deque<BenchDriver.Step> steps, JsonObject it, String an) {
		double r = num(it, "r", 70), h = num(it, "h", 40);
		int dirs = (int) num(it, "dirs", 4), time = (int) num(it, "time", 6000);
		double start = num(it, "startDeg", 0);
		for (int k = 0; k < dirs; k++) {
			double deg = start + 360.0 * k / dirs;
			String name = "scout_" + an + "_" + (int) deg;
			java.util.function.Supplier<Pose> pose = () -> {
				Anchor a = anchor(an);
				double rad = Math.toRadians(deg);
				// the camera r out toward deg (0 = north of the anchor, 90 = east), looking back at the anchor
				double ex = a.x() + Math.sin(rad) * r, ez = a.z() - Math.cos(rad) * r;
				return Pose.lookAt(ex, a.y() + h, ez, a.x(), a.y() + 4, a.z());
			};
			shot(steps, name, an, time, "clear", pose, 2.5);
		}
	}

	private static void still(Deque<BenchDriver.Step> steps, JsonObject it, String an) {
		JsonArray p = it.getAsJsonArray("pose");
		String mode = str(it, "y", "anchor");
		java.util.function.Supplier<Pose> pose = () -> {
			Anchor a = anchor(an);
			double x = a.x() + p.get(0).getAsDouble(), z = a.z() + p.get(2).getAsDouble();
			double base = switch (mode) {
				case "abs" -> 0;
				case "ground" -> GROUND.getOrDefault(key(x, z), (double) a.y());
				default -> a.y();
			};
			return new Pose(x, base + p.get(1).getAsDouble() - Spot.EYE, z, p.get(3).getAsFloat(), p.get(4).getAsFloat());
		};
		if (mode.equals("ground")) {
			JsonArray pp = p;
			steps.add(groundProbe("cine-ground-" + str(it, "name", "still"), an, () -> {
				Anchor a = anchor(an);
				return new double[] {a.x() + pp.get(0).getAsDouble(), a.z() + pp.get(2).getAsDouble()};
			}));
		}
		shot(steps, str(it, "name", "still"), an, (int) num(it, "time", 6000), str(it, "weather", "clear"), pose, num(it, "hold", 2.5));
	}

	/** Ground heights probed for stills in ground mode, by block column. */
	private static final Map<Long, Double> GROUND = new java.util.concurrent.ConcurrentHashMap<>();

	private static long key(double x, double z) {
		return ((long) Math.floor(x) << 32) ^ (Math.floor(z) < 0 ? (long) Math.floor(z) & 0xffffffffL : (long) Math.floor(z));
	}

	private static BenchDriver.Step groundProbe(String name, String an, java.util.function.Supplier<double[]> xz) {
		return new BenchDriver.Step(name, 120) {
			CompletableFuture<Double> f;
			double[] at;

			@Override
			void start(Minecraft mc, long now) {
				MinecraftServer s = BenchDriver.server(mc);
				at = xz.get();
				ServerLevel l = s.getLevel(dimKey(anchor(an).dim()));
				f = CompletableFuture.supplyAsync(() -> (double) height(l, (int) Math.floor(at[0]), (int) Math.floor(at[1])), s);
			}

			@Override
			boolean poll(Minecraft mc, long now) throws Exception {
				if (!f.isDone()) return false;
				GROUND.put(key(at[0], at[1]), f.get());
				return true;
			}
		};
	}

	private static void shot(Deque<BenchDriver.Step> steps, String name, String an, int time, String weather, java.util.function.Supplier<Pose> pose, double hold) {
		boolean rain = !weather.equals("clear");
		steps.add(new BenchDriver.Step("cine-scene-" + name, 0) {
			@Override
			void start(Minecraft mc, long now) {
				BenchDriver.setPhase("SETTLE");
				mcopt.bench.Bench.settledAt = 0;
				MinecraftServer s = BenchDriver.server(mc);
				BenchDriver.cmd(s, "time set " + time);
				BenchDriver.cmd(s, "weather " + weather);
				BenchDriver.hideHud(mc);
				place(s, anchor(an).dim(), pose.get());
				BenchDriver.log("cine still " + name + " @" + time + " " + weather + " pose " + pose.get());
			}

			@Override
			boolean poll(Minecraft mc, long now) {
				return true;
			}
		});
		steps.add(new BenchDriver.Settle(name, rain, 3, 150));
		steps.add(new BenchDriver.Hold(name, rain ? Math.max(hold, 5) : hold));
		steps.add(new BenchDriver.Shot(name));
	}

	// ---------------------------------------------------------------------------------------------- clips

	/** A planned camera path: pose(t) for t in [0, length] seconds of motion. */
	static final class CamPath {
		double[] kt;
		double[][] kp; // per key: x, y(offset or abs), z, yaw, pitch (NaN = from lookAt / path)
		double[] lt;
		double[][] lp; // lookAt keys: x, y, z (absolute)
		boolean ease, facePath;
		String mode;
		double eye;
		// ground: heights sampled every 1/20 s along the path, smoothed
		double[] ground;
		Anchor a;

		double length() {
			return kt[kt.length - 1];
		}

		/** Hermite through the keys (Catmull-Rom tangents for uneven times; zero at the ends when easing). */
		static double herm(double[] t, double[] v, double at, boolean ease) {
			int n = t.length;
			if (n == 1 || at <= t[0]) return v[0];
			if (at >= t[n - 1]) return v[n - 1];
			int i = 0;
			while (i < n - 2 && at > t[i + 1]) i++;
			double h = t[i + 1] - t[i], s = (at - t[i]) / h;
			double m0 = tangent(t, v, i, ease), m1 = tangent(t, v, i + 1, ease);
			double s2 = s * s, s3 = s2 * s;
			return (2 * s3 - 3 * s2 + 1) * v[i] + (s3 - 2 * s2 + s) * h * m0 + (-2 * s3 + 3 * s2) * v[i + 1] + (s3 - s2) * h * m1;
		}

		static double tangent(double[] t, double[] v, int i, boolean ease) {
			int n = t.length;
			if (i == 0) return ease ? 0 : (v[1] - v[0]) / (t[1] - t[0]);
			if (i == n - 1) return ease ? 0 : (v[n - 1] - v[n - 2]) / (t[n - 1] - t[n - 2]);
			return (v[i + 1] - v[i - 1]) / (t[i + 1] - t[i - 1]);
		}

		double comp(int c, double at) {
			double[] v = new double[kt.length];
			for (int i = 0; i < v.length; i++) v[i] = kp[i][c];
			return herm(kt, v, at, ease);
		}

		double xz(int c, double at) {
			return (c == 0 ? a.x() : a.z()) + comp(c, at);
		}

		double groundAt(double at) {
			double f = Math.clamp(at * 20, 0, ground.length - 1);
			int i = (int) Math.floor(f);
			int j = Math.min(i + 1, ground.length - 1);
			return ground[i] + (ground[j] - ground[i]) * (f - i);
		}

		Pose pose(double at) {
			double x = xz(0, at), z = xz(2, at);
			double y = switch (mode) {
				case "abs" -> comp(1, at);
				case "ground" -> groundAt(at) + comp(1, at);
				default -> a.y() + comp(1, at);
			};
			float yaw, pitch;
			if (lt != null) {
				double[] tx = new double[lt.length], ty = new double[lt.length], tz = new double[lt.length];
				for (int i = 0; i < lt.length; i++) {
					tx[i] = lp[i][0];
					ty[i] = lp[i][1];
					tz[i] = lp[i][2];
				}
				double lx = herm(lt, tx, at, ease), ly = herm(lt, ty, at, ease), lz = herm(lt, tz, at, ease);
				Pose p = Pose.lookAt(x, y, z, lx, ly, lz);
				yaw = p.yaw();
				pitch = p.pitch();
			} else {
				double ky = comp(3, at), kpitch = comp(4, at);
				if (facePath) {
					double e = 0.3;
					double dx = xz(0, Math.min(at + e, length())) - xz(0, Math.max(at - e, 0)), dz = xz(2, Math.min(at + e, length())) - xz(2, Math.max(at - e, 0));
					double head = Math.hypot(dx, dz) > 1e-3 ? Math.toDegrees(Math.atan2(-dx, dz)) : kp[0][3];
					yaw = (float) (head + (Double.isNaN(ky) ? 0 : ky));
				} else yaw = (float) ky;
				pitch = (float) kpitch;
			}
			return new Pose(x, y - Spot.EYE, z, yaw, pitch);
		}

		double travel() {
			double d = 0;
			double px = xz(0, 0), pz = xz(2, 0);
			for (double t = 0.25; t <= length(); t += 0.25) {
				double x = xz(0, t), z = xz(2, t);
				d += Math.hypot(x - px, z - pz);
				px = x;
				pz = z;
			}
			return d;
		}
	}

	static CamPath parsePath(JsonObject it, Anchor a) {
		CamPath p = new CamPath();
		p.a = a;
		p.mode = str(it, "y", "anchor");
		p.ease = !it.has("ease") || it.get("ease").getAsBoolean();
		p.facePath = "path".equals(str(it, "face", ""));
		JsonArray keys = it.getAsJsonArray("keys");
		int n = keys.size();
		p.kt = new double[n];
		p.kp = new double[n][5];
		for (int i = 0; i < n; i++) {
			JsonArray k = keys.get(i).getAsJsonArray();
			p.kt[i] = k.get(0).getAsDouble();
			for (int c = 0; c < 5; c++) p.kp[i][c] = k.size() > c + 1 && !k.get(c + 1).isJsonNull() ? k.get(c + 1).getAsDouble() : (c >= 3 ? 0 : Double.NaN);
		}
		// yaw unwrapped, so the camera turns the short way between keys
		for (int i = 1; i < n; i++) {
			double d = p.kp[i][3] - p.kp[i - 1][3];
			p.kp[i][3] = p.kp[i - 1][3] + (d - 360 * Math.round(d / 360));
		}
		if (it.has("lookAt")) {
			JsonArray la = it.getAsJsonArray("lookAt");
			// "lookAnchor": the lookAt keys are relative to that anchor instead (e.g. a peak seen from a lake)
			Anchor la0 = it.has("lookAnchor") ? anchor(it.get("lookAnchor").getAsString()) : a;
			double base = p.mode.equals("abs") ? 0 : la0.y();
			if (la.get(0).isJsonArray()) {
				p.lt = new double[la.size()];
				p.lp = new double[la.size()][3];
				for (int i = 0; i < la.size(); i++) {
					JsonArray k = la.get(i).getAsJsonArray();
					p.lt[i] = k.get(0).getAsDouble();
					p.lp[i] = new double[] {la0.x() + k.get(1).getAsDouble(), base + k.get(2).getAsDouble(), la0.z() + k.get(3).getAsDouble()};
				}
			} else {
				p.lt = new double[] {0};
				p.lp = new double[][] {{la0.x() + la.get(0).getAsDouble(), base + la.get(1).getAsDouble(), la0.z() + la.get(2).getAsDouble()}};
			}
		}
		return p;
	}

	/**
	 * Server thread: a clip's keys from {@code "walk": {"yaw": 90, "length": 24, "speed": 2.4, "search": 40, "yawRange": 25,
	 * "canopy": true, "eye": 1.62, "look": [yawOffset, pitch], "sway": 0, "clear": 1.6, "headroom": 3}}: the straight walk of
	 * that length near the anchor, heading about yaw, over dry ground with steps of at most one block, the columns within clear
	 * blocks to each side free of anything solid (trunks, leaves) up to headroom blocks above the ground; the most covered by canopy with "canopy", else the smoothest.
	 */
	static JsonObject walk(ServerLevel l, Anchor a, JsonObject it, Map<String, Object> json) {
		JsonObject w = it.getAsJsonObject("walk");
		double yaw0 = num(w, "yaw", 0), len = num(w, "length", 24), speed = num(w, "speed", 2.4), search = num(w, "search", 40);
		double yawRange = num(w, "yawRange", 25), eye = num(w, "eye", 1.62), sway = num(w, "sway", 0), clear = num(w, "clear", 1.6);
		int headroom = (int) num(w, "headroom", 3);
		boolean canopy = w.has("canopy") && w.get("canopy").getAsBoolean();
		double lookYaw = 0, lookPitch = -3;
		if (w.has("look")) {
			lookYaw = w.getAsJsonArray("look").get(0).getAsDouble();
			lookPitch = w.getAsJsonArray("look").get(1).getAsDouble();
		}
		int half = (int) Math.ceil(search + len / 2 + 2), n = 2 * half + 1;
		int[][] hgt = new int[n][n];
		boolean[][] ok = new boolean[n][n], cover = new boolean[n][n];
		for (int i = 0; i < n; i++) {
			for (int j = 0; j < n; j++) {
				int x = a.x() - half + i, z = a.z() - half + j;
				int h = height(l, x, z);
				hgt[i][j] = h;
				boolean free = l.getFluidState(new net.minecraft.core.BlockPos(x, h - 1, z)).isEmpty();
				for (int dy = 0; dy <= headroom && free; dy++) {
					net.minecraft.core.BlockPos bp = new net.minecraft.core.BlockPos(x, h + dy, z);
					free = l.getBlockState(bp).getCollisionShape(l, bp).isEmpty() && l.getFluidState(bp).isEmpty();
				}
				ok[i][j] = free;
				cover[i][j] = l.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - h >= 3;
			}
		}
		double best = -1e18, bsx = 0, bsz = 0, byaw = yaw0;
		int steps = (int) Math.round(len / 0.5);
		for (double dyaw = -yawRange; dyaw <= yawRange + 1e-6; dyaw += 5) {
			double yr = Math.toRadians(yaw0 + dyaw), dx = -Math.sin(yr), dz = Math.cos(yr), px = -dz, pz = dx;
			for (double ox = -search; ox <= search; ox += 3) {
				for (double oz = -search; oz <= search; oz += 3) {
					double sx = a.x() + 0.5 + ox - dx * len / 2, sz = a.z() + 0.5 + oz - dz * len / 2;
					double rough = 0, cov = 0;
					int prev = Integer.MIN_VALUE;
					boolean good = true;
					for (int k = 0; k <= steps && good; k++) {
						double x = sx + dx * k * 0.5, z = sz + dz * k * 0.5;
						for (int side = -2; side <= 2 && good; side++) {
							double off = clear * side / 2;
							int gi = (int) Math.floor(x + px * off) - (a.x() - half), gj = (int) Math.floor(z + pz * off) - (a.z() - half);
							if (gi < 0 || gj < 0 || gi >= n || gj >= n || !ok[gi][gj]) good = false;
						}
						if (!good) break;
						int gi = (int) Math.floor(x) - (a.x() - half), gj = (int) Math.floor(z) - (a.z() - half);
						int h = hgt[gi][gj];
						if (prev != Integer.MIN_VALUE) {
							if (Math.abs(h - prev) > 1) good = false;
							rough += Math.abs(h - prev);
						}
						prev = h;
						if (cover[gi][gj]) cov++;
					}
					if (!good) continue;
					double score = (canopy ? cov / (steps + 1) * 10 : 0) - rough * 0.3 - Math.hypot(ox, oz) / 32 - Math.abs(dyaw) / 30;
					if (score > best) {
						best = score;
						bsx = sx;
						bsz = sz;
						byaw = yaw0 + dyaw;
					}
				}
			}
		}
		if (best == -1e18) throw new IllegalStateException("mcbench.cine: no free walk of " + len + " blocks near the anchor heading " + yaw0);
		double yr = Math.toRadians(byaw), ex = bsx - Math.sin(yr) * len, ez = bsz + Math.cos(yr) * len, T = len / speed;
		JsonObject spec = it.deepCopy();
		spec.remove("walk");
		spec.addProperty("y", "ground");
		JsonArray keys = new JsonArray();
		double fy = byaw + lookYaw;
		keys.add(key(0, bsx - a.x(), eye, bsz - a.z(), fy, lookPitch));
		if (sway != 0) keys.add(key(T / 2, (bsx + ex) / 2 - a.x(), eye, (bsz + ez) / 2 - a.z(), fy + sway, lookPitch));
		keys.add(key(T, ex - a.x(), eye, ez - a.z(), fy, lookPitch));
		spec.add("keys", keys);
		if (!spec.has("smooth")) spec.addProperty("smooth", 0.35);
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("from", new double[] {bsx, bsz});
		m.put("to", new double[] {ex, ez});
		m.put("yaw", byaw);
		m.put("score", best);
		json.put("walk", m);
		BenchDriver.log(String.format(Locale.ROOT, "cine: walk %.1f,%.1f -> %.1f,%.1f heading %.0f (score %.2f)", bsx, bsz, ex, ez, byaw, best));
		return spec;
	}

	private static JsonArray key(double t, double x, double y, double z, double yaw, double pitch) {
		JsonArray k = new JsonArray();
		for (double v : new double[] {t, x, y, z, yaw, pitch}) k.add(v);
		return k;
	}

	/** Server thread: the terrain under the path (leaves ignored), every 1/20 s, raised to the highest point nearby and smoothed. */
	static void planGround(ServerLevel l, CamPath p, double smooth) {
		int n = (int) Math.ceil(p.length() * 20) + 1;
		double[] raw = new double[n];
		for (int i = 0; i < n; i++) {
			double t = Math.min(i / 20.0, p.length());
			int x = (int) Math.floor(p.xz(0, t)), z = (int) Math.floor(p.xz(2, t));
			int h = Integer.MIN_VALUE;
			for (int dx = -1; dx <= 1; dx++)
				for (int dz = -1; dz <= 1; dz++) h = Math.max(h, height(l, x + dx, z + dz));
			raw[i] = h;
		}
		int w = Math.max(0, (int) Math.round(smooth * 20 / 2));
		double[] mx = new double[n], out = new double[n];
		for (int i = 0; i < n; i++) {
			double m = -1e9;
			for (int j = Math.max(0, i - w); j <= Math.min(n - 1, i + w); j++) m = Math.max(m, raw[j]);
			mx[i] = m;
		}
		for (int i = 0; i < n; i++) {
			double s = 0;
			int c = 0;
			for (int j = Math.max(0, i - w); j <= Math.min(n - 1, i + w); j++) {
				s += mx[j];
				c++;
			}
			out[i] = s / c;
		}
		p.ground = out;
	}

	private static void clip(Deque<BenchDriver.Step> steps, JsonObject it, String an, List<Object> clips) {
		String name = str(it, "name", "clip" + clips.size());
		String phase = "CINE_" + name.toUpperCase(Locale.ROOT);
		int time = (int) num(it, "time", 6000);
		String weather = str(it, "weather", "clear");
		boolean rain = !weather.equals("clear");
		double pre = num(it, "pre", 0.6), post = num(it, "post", 0.4), wait = num(it, "wait", 0), smooth = num(it, "smooth", 0.5);
		String hand = str(it, "hand", null);
		String mode = str(it, "mode", "creative");
		boolean f3 = "f3".equals(str(it, "hud", ""));
		int gui = (int) num(it, "gui", 0);
		List<String> hotbar = list(it, "hotbar");
		int[] prevGui = {-1};
		boolean advance = it.has("advanceTime") && it.get("advanceTime").getAsBoolean();
		Map<String, String> props = new LinkedHashMap<>();
		if (it.has("props")) for (var e : it.getAsJsonObject("props").entrySet()) props.put(e.getKey(), e.getValue().getAsString());
		List<String> setup = list(it, "setup"), teardown = list(it, "teardown");
		List<double[]> evT = new ArrayList<>();
		List<String> evC = new ArrayList<>();
		if (it.has("events")) for (JsonElement e : it.getAsJsonArray("events")) {
			evT.add(new double[] {e.getAsJsonArray().get(0).getAsDouble()});
			evC.add(e.getAsJsonArray().get(1).getAsString());
		}
		List<Double> stills = new ArrayList<>();
		if (it.has("stills")) for (JsonElement e : it.getAsJsonArray("stills")) stills.add(e.getAsDouble());
		Map<String, Object> json = new LinkedHashMap<>();
		json.put("name", name);
		json.put("phase", phase);
		json.put("anchor", an);
		json.put("time", time);
		json.put("weather", weather);
		json.put("props", props);
		clips.add(json);
		CamPath[] path = new CamPath[1];
		Map<String, String> before = new LinkedHashMap<>();

		// plan (server thread: the chunks along a ground path are generated here)
		steps.add(new BenchDriver.Step("cine-plan-" + name, 300) {
			CompletableFuture<CamPath> f;

			@Override
			void start(Minecraft mc, long now) {
				BenchDriver.setPhase("SETUP");
				MinecraftServer s = BenchDriver.server(mc);
				Anchor a = anchor(an);
				ServerLevel l = s.getLevel(dimKey(a.dim()));
				f = CompletableFuture.supplyAsync(() -> {
					JsonObject spec = it.has("walk") ? walk(l, a, it, json) : it;
					CamPath p = parsePath(spec, a);
					if (p.mode.equals("ground")) planGround(l, p, smooth);
					return p;
				}, s);
			}

			@Override
			boolean poll(Minecraft mc, long now) throws Exception {
				if (!f.isDone()) return false;
				path[0] = f.get();
				json.put("seconds", path[0].length());
				json.put("travel", Math.round(path[0].travel()));
				json.put("start", path[0].pose(0).json());
				json.put("end", path[0].pose(path[0].length()).json());
				BenchDriver.log(String.format(Locale.ROOT, "cine: clip %s planned, %.1f s, %.0f blocks, start %s", name, path[0].length(), path[0].travel(), path[0].pose(0)));
				return true;
			}
		});
		// the end first when the path is long (its chunks get built), then the start
		steps.add(new BenchDriver.Step("cine-scene-" + name, 0) {
			@Override
			void start(Minecraft mc, long now) {
				BenchDriver.setPhase("SETTLE");
				mcopt.bench.Bench.settledAt = 0;
				MinecraftServer s = BenchDriver.server(mc);
				Anchor a = anchor(an);
				BenchDriver.cmd(s, "gamerule advance_time false");
				BenchDriver.cmd(s, "time set " + time);
				BenchDriver.cmd(s, "weather " + weather);
				before.clear();
				for (var e : props.entrySet()) {
					before.put(e.getKey(), System.getProperty(e.getKey()));
					set(e.getKey(), e.getValue());
				}
				Pose start = path[0].pose(0);
				for (String c : setup) BenchDriver.cmd(s, fill(c, a, start));
				BenchDriver.hideHud(mc);
				boolean far = path[0].travel() > 48;
				place(s, a.dim(), far ? path[0].pose(path[0].length()) : start);
				hand(mc, hand, mode);
				for (int k = 0; k < hotbar.size(); k++) BenchDriver.cmd(s, "item replace entity @a hotbar." + (k + 1) + " with minecraft:" + hotbar.get(k));
				if (gui > 0) {
					prevGui[0] = mc.options.guiScale().get();
					mc.options.guiScale().set(gui);
					mc.resizeGui();
				}
				BenchDriver.log("cine clip " + name + " @" + time + " " + weather + (props.isEmpty() ? "" : " " + props) + (far ? " (end first)" : ""));
			}

			@Override
			boolean poll(Minecraft mc, long now) {
				return true;
			}
		});
		steps.add(new BenchDriver.Settle(name + "-a", rain, 2, 150) {
			@Override
			boolean poll(Minecraft mc, long now) {
				hud(mc, hand, f3);
				return super.poll(mc, now);
			}
		});
		steps.add(new BenchDriver.Step("cine-start-" + name, 0) {
			@Override
			void start(Minecraft mc, long now) {
				place(BenchDriver.server(mc), anchor(an).dim(), path[0].pose(0));
			}

			@Override
			boolean poll(Minecraft mc, long now) {
				return true;
			}
		});
		steps.add(new BenchDriver.Settle(name, rain, 3, 150) {
			@Override
			boolean poll(Minecraft mc, long now) {
				hud(mc, hand, f3);
				return super.poll(mc, now);
			}
		});
		// a hold (TAA history, wetness, DH LODs), then the recorded phase: pre, the path, post
		double hold = Math.max(BenchDriver.SHOT_WAIT, rain ? 5 : 2) + wait;
		steps.add(new BenchDriver.Step("cine-hold-" + name, 0) {
			@Override
			boolean poll(Minecraft mc, long now) {
				hud(mc, hand, f3);
				return BenchDriver.secs(now - BenchDriver.curStart) >= hold;
			}
		});
		steps.add(new BenchDriver.Step("cine-" + name, 0) {
			long t0, last, worst;
			int ev, frames;

			@Override
			void start(Minecraft mc, long now) {
				BenchDriver.setPhase(phase);
				t0 = last = now;
				ev = frames = 0;
				worst = 0;
				CamPath p = path[0];
				BenchDriver.pose = t -> p.pose(Math.clamp(BenchDriver.secs(t - t0) - pre, 0, p.length()));
				if (advance) BenchDriver.cmd(BenchDriver.server(mc), "gamerule advance_time true");
				json.put("recordedFrom", System.currentTimeMillis());
				BenchDriver.log("cine: recording " + phase + " (" + (pre + path[0].length() + post) + " s)");
			}

			@Override
			boolean poll(Minecraft mc, long now) {
				double s = BenchDriver.secs(now - t0);
				frames++;
				worst = Math.max(worst, now - last);
				last = now;
				hud(mc, hand, f3);
				while (ev < evT.size() && s - pre >= evT.get(ev)[0]) {
					BenchDriver.cmd(BenchDriver.server(mc), fill(evC.get(ev), anchor(an), path[0].pose(0)));
					ev++;
				}
				if (s < pre + path[0].length() + post) return false;
				json.put("recordedTo", System.currentTimeMillis());
				json.put("avgFps", Math.round(frames / s * 10) / 10.0);
				json.put("worstFrameMs", Math.round(worst / 1e5) / 10.0);
				BenchDriver.log(String.format(Locale.ROOT, "cine: %s done, %.1f fps average, worst frame %.1f ms", name, frames / s, worst / 1e6));
				if (advance) BenchDriver.cmd(BenchDriver.server(mc), "gamerule advance_time false");
				BenchDriver.setPhase("SETTLE");
				Pose last = path[0].pose(path[0].length());
				BenchDriver.pose = t -> last;
				return true;
			}
		});
		for (double st : stills) {
			String shot = name + "_" + String.format(Locale.ROOT, "%04.1f", st).replace('.', 'p');
			steps.add(new BenchDriver.Step("cine-still-" + shot, 0) {
				@Override
				void start(Minecraft mc, long now) {
					BenchDriver.setPhase("SETTLE");
					Pose p = path[0].pose(st);
					BenchDriver.pose = t -> p;
				}

				@Override
				boolean poll(Minecraft mc, long now) {
					hud(mc, hand, f3);
					return BenchDriver.secs(now - BenchDriver.curStart) >= 2.5;
				}
			});
			steps.add(new BenchDriver.Shot(shot));
		}
		steps.add(new BenchDriver.Step("cine-teardown-" + name, 0) {
			@Override
			void start(Minecraft mc, long now) {
				MinecraftServer s = BenchDriver.server(mc);
				for (var e : before.entrySet()) set(e.getKey(), e.getValue());
				hand(mc, null, mode);
				for (int k = 0; k < hotbar.size(); k++) BenchDriver.cmd(s, "item replace entity @a hotbar." + (k + 1) + " with minecraft:air");
				hud(mc, null, false);
				if (prevGui[0] >= 0) {
					mc.options.guiScale().set(prevGui[0]);
					mc.resizeGui();
					prevGui[0] = -1;
				}
				for (String c : teardown) BenchDriver.cmd(s, fill(c, anchor(an), path[0].pose(0)));
			}

			@Override
			boolean poll(Minecraft mc, long now) {
				return BenchDriver.secs(now - BenchDriver.curStart) >= 0.3;
			}
		});
	}

	/** The HUD for a clip: shown with items in hand or with F3 (the debug overlay, vanilla's default entries), else hidden (F1). */
	static void hud(Minecraft mc, String hand, boolean f3) {
		if (hand != null || f3) {
			if (mc.gui.screen() != null) mc.gui.setScreen(null);
			if (mc.gui.hud.isHidden()) mc.gui.hud.toggle();
			if (hand != null && mc.player != null && mc.player.getInventory().getSelectedSlot() != 0) mc.player.getInventory().setSelectedSlot(0);
		} else {
			if (mc.gui.screen() != null) mc.gui.setScreen(null);
			BenchDriver.hideHud(mc);
		}
		if (mc.debugEntries.isOverlayVisible() != f3) mc.debugEntries.setOverlayVisible(f3);
	}

	static List<String> list(JsonObject o, String k) {
		List<String> l = new ArrayList<>();
		if (o.has(k)) for (JsonElement e : o.getAsJsonArray(k)) l.add(e.getAsString());
		return l;
	}

	static void set(String key, String value) {
		if (value == null || value.equals("-")) System.clearProperty(key);
		else System.setProperty(key, value);
	}

	private static String handOn;

	/**
	 * "main+off" item ids: creative (or mode "survival": hearts, hunger and the XP bar on the HUD; no fall damage, 30 levels,
	 * BenchDriver.applyPose lets the player fly) with those items and the HUD shown (the first-person hand renders); null:
	 * spectator, empty hands, hidden HUD.
	 */
	static void hand(Minecraft mc, String h, String mode) {
		if (java.util.Objects.equals(h, handOn)) return;
		MinecraftServer s = BenchDriver.server(mc);
		if (h != null) {
			String[] it = h.split("\\+", 2);
			if (mode.equals("survival")) {
				BenchDriver.cmd(s, "gamerule fall_damage false");
				BenchDriver.cmd(s, "gamemode survival @a");
				BenchDriver.cmd(s, "xp set @a 30 levels");
				BenchDriver.cmd(s, "xp set @a 60 points");
			} else BenchDriver.cmd(s, "gamemode creative @a");
			BenchDriver.cmd(s, "item replace entity @a weapon.mainhand with minecraft:" + it[0].trim());
			BenchDriver.cmd(s, "item replace entity @a weapon.offhand with minecraft:" + (it.length > 1 ? it[1].trim() : "air"));
			LightShots.showHud(mc);
		} else {
			BenchDriver.cmd(s, "item replace entity @a weapon.mainhand with minecraft:air");
			BenchDriver.cmd(s, "item replace entity @a weapon.offhand with minecraft:air");
			BenchDriver.cmd(s, "gamemode spectator @a");
			BenchDriver.hideHud(mc);
		}
		handOn = h;
	}
}
