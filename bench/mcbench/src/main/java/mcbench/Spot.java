/*
 * Copyright 2026 mcbench contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package mcbench;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * The bench location: found once per world by sampling the overworld's biome noise (no chunk generation) for a 192x192
 * area that has water (ocean/river), forest and plains plus some relief, then fixed viewpoints derived from it. The
 * search is a pure function of seed + worldgen, and its result is stored in saves/<world>/mcbench-spot.json, so every
 * run of the same world uses the identical camera route. Runs on the server thread.
 */
public final class Spot {
	public static final double EYE = 1.62; // spectator eye height: poses are feet positions

	/** A camera pose: feet position + yaw/pitch in Minecraft degrees (yaw 0 = +Z/south, 90 = -X/west; pitch + = down). */
	public record Pose(double x, double y, double z, float yaw, float pitch) {
		Map<String, Object> json() {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("x", x);
			m.put("y", y);
			m.put("z", z);
			m.put("yaw", yaw);
			m.put("pitch", pitch);
			return m;
		}

		static Pose of(Map<?, ?> m) {
			return new Pose(num(m, "x"), num(m, "y"), num(m, "z"), (float) num(m, "yaw"), (float) num(m, "pitch"));
		}

		private static double num(Map<?, ?> m, String k) {
			return ((Number) m.get(k)).doubleValue();
		}

		/** Pose at eye position (ex,ey,ez) looking at (tx,ty,tz). */
		static Pose lookAt(double ex, double ey, double ez, double tx, double ty, double tz) {
			double dx = tx - ex, dy = ty - ey, dz = tz - ez;
			double h = Math.sqrt(dx * dx + dz * dz);
			float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
			float pitch = (float) Math.toDegrees(Math.atan2(-dy, h));
			return new Pose(ex, ey - EYE, ez, yaw, pitch);
		}
	}

	// result
	public int cx, cz;
	public double waterFrac, forestFrac, plainsFrac;
	public int minH, maxH;
	public boolean ideal;
	public Pose overview, forest, sunset, night;
	public int torchX, torchZ;
	public double flightYaw;
	public int candidatesTried;
	/** Set once the night scene's torch grid has been placed in this world (persisted). */
	public boolean torchesPlaced;
	public int torchVersion;

	static final int STEP = 24, HALF = 4; // 9x9 samples, 24 blocks apart: a 192x192 area

	private static final int OTHER = 0, WATER = 1, FOREST = 2, PLAINS = 3;

	private static int classify(Holder<Biome> b) {
		if (b.is(BiomeTags.IS_OCEAN) || b.is(BiomeTags.IS_RIVER)) return WATER;
		if (b.is(BiomeTags.IS_FOREST)) return FOREST;
		if (b.is(Biomes.PLAINS) || b.is(Biomes.SUNFLOWER_PLAINS) || b.is(Biomes.MEADOW)) return PLAINS;
		return OTHER;
	}

	/** Search outward (square spiral, 96-block cells) from (sx,sz). */
	public static Spot find(ServerLevel level, int sx, int sz) {
		ChunkGenerator gen = level.getChunkSource().getGenerator();
		RandomState rs = level.getChunkSource().randomState();
		BiomeResolver biomes = gen.getBiomeSource().createUncachedResolver(rs);
		Spot best = null;
		double bestScore = -1;
		int tried = 0;
		int cell = 96, maxRing = 40; // up to ~3.8k blocks out
		outer:
		for (int ring = 0; ring <= maxRing; ring++) {
			for (int i = -ring; i <= ring; i++) {
				for (int j = -ring; j <= ring; j++) {
					if (Math.max(Math.abs(i), Math.abs(j)) != ring) continue;
					int cx = sx + i * cell, cz = sz + j * cell;
					tried++;
					// coarse 5x5 prefilter (48 apart)
					int[] coarse = new int[4];
					for (int a = -2; a <= 2; a++)
						for (int b = -2; b <= 2; b++) coarse[classify(biome(biomes, cx + a * 48, cz + b * 48))]++;
					if (coarse[WATER] == 0 || coarse[FOREST] == 0 || coarse[PLAINS] == 0) continue;
					int[][] cat = new int[9][9];
					int[] n = new int[4];
					for (int a = 0; a < 9; a++)
						for (int b = 0; b < 9; b++) {
							cat[a][b] = classify(biome(biomes, cx + (a - HALF) * STEP, cz + (b - HALF) * STEP));
							n[cat[a][b]]++;
						}
					double w = n[WATER] / 81.0, f = n[FOREST] / 81.0, p = n[PLAINS] / 81.0;
					if (w < 0.06 || f < 0.12 || p < 0.12) continue;
					double score = Math.min(w, Math.min(f, p));
					boolean idealBiomes = w >= 0.08 && f >= 0.15 && p >= 0.15 && w <= 0.5;
					if (!idealBiomes && score <= bestScore) continue;
					int[][] h = heights(gen, level, rs, cx, cz);
					int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
					for (int[] row : h)
						for (int v : row) {
							lo = Math.min(lo, v);
							hi = Math.max(hi, v);
						}
					boolean ideal = idealBiomes && hi - lo >= 12;
					if (ideal) score += 1;
					if (score > bestScore) {
						bestScore = score;
						best = build(cx, cz, cat, h, w, f, p, lo, hi, ideal);
					}
					if (ideal) break outer;
				}
			}
		}
		if (best == null) throw new IllegalStateException("no spot with water+forest+plains within " + (maxRing * cell) + " blocks of " + sx + "," + sz);
		best.candidatesTried = tried;
		return best;
	}

	private static Holder<Biome> biome(BiomeResolver r, int x, int z) {
		return r.getNoiseBiome(x >> 2, 64 >> 2, z >> 2);
	}

	private static int[][] heights(ChunkGenerator gen, ServerLevel level, RandomState rs, int cx, int cz) {
		int[][] h = new int[9][9];
		for (int a = 0; a < 9; a++)
			for (int b = 0; b < 9; b++)
				h[a][b] = gen.getBaseHeight(cx + (a - HALF) * STEP, cz + (b - HALF) * STEP, Heightmap.Types.WORLD_SURFACE_WG, level, rs);
		return h;
	}

	private static Spot build(int cx, int cz, int[][] cat, int[][] h, double w, double f, double p, int lo, int hi, boolean ideal) {
		Spot s = new Spot();
		s.cx = cx;
		s.cz = cz;
		s.waterFrac = w;
		s.forestFrac = f;
		s.plainsFrac = p;
		s.minH = lo;
		s.maxH = hi;
		s.ideal = ideal;
		double[] wc = centroid(cat, WATER), fc = centroid(cat, FOREST), pc = centroid(cat, PLAINS);
		// overview: from the plains side, 80 blocks out from the water/forest midpoint, ~30 above the terrain, looking at it
		double tx = (wc[0] + fc[0]) / 2, tz = (wc[1] + fc[1]) / 2;
		double dx = pc[0] - tx, dz = pc[1] - tz, len = Math.hypot(dx, dz);
		if (len < 1) {
			dx = 1;
			dz = 0;
			len = 1;
		}
		dx /= len;
		dz /= len;
		double ox = tx + dx * 80, oz = tz + dz * 80;
		int oy = Math.max(hi + 18, hAt(h, ox - cx, oz - cz) + 30);
		oy = Math.max(oy, 96);
		s.overview = Pose.lookAt(ox + cx, oy, oz + cz, tx + cx, 64, tz + cz);
		s.flightYaw = s.overview.yaw();

		// forest close-up: a forest sample next to a non-forest one, camera 20 blocks outside, ~12 above the ground
		int[] fe = null, out = null;
		int bestD = Integer.MAX_VALUE;
		for (int a = 1; a < 8; a++)
			for (int b = 1; b < 8; b++) {
				if (cat[a][b] != FOREST) continue;
				int[][] nb = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
				for (int[] d : nb) {
					int t = cat[a + d[0]][b + d[1]];
					if (t == FOREST || t == WATER) continue;
					int dist = Math.abs(a - HALF) + Math.abs(b - HALF) + (t == PLAINS ? 0 : 3);
					if (dist < bestD) {
						bestD = dist;
						fe = new int[] {a, b};
						out = d;
					}
				}
			}
		if (fe == null) { // every forest sample borders only forest/water: just look into the forest centroid
			fe = new int[] {(int) Math.round(fc[0] / STEP) + HALF, (int) Math.round(fc[1] / STEP) + HALF};
			out = new int[] {1, 0};
		}
		double fx = (fe[0] - HALF) * STEP, fz = (fe[1] - HALF) * STEP;
		double camX = fx + out[0] * 20, camZ = fz + out[1] * 20;
		int gF = h[fe[0]][fe[1]];
		int gC = hAt(h, camX, camZ);
		s.forest = Pose.lookAt(camX + cx, Math.max(gF, gC) + 9 + EYE, camZ + cz, fx - out[0] * 8 + cx, gF + 5, fz - out[1] * 8 + cz);

		// sunset: a water sample whose samples to the west are water too; camera a bit east of it facing west (sun sets at -X)
		int[] ws = null;
		int wsScore = -1;
		for (int a = 0; a < 9; a++)
			for (int b = 0; b < 9; b++) {
				if (cat[a][b] != WATER) continue;
				int sc = 0;
				for (int k = 1; k <= 3 && a - k >= 0; k++)
					if (cat[a - k][b] == WATER) sc++;
				sc = sc * 10 - Math.abs(b - HALF);
				if (sc > wsScore) {
					wsScore = sc;
					ws = new int[] {a, b};
				}
			}
		double wx = (ws[0] - HALF) * STEP + 12, wz = (ws[1] - HALF) * STEP;
		int sy = Math.max(64, hAt(h, wx, wz)) + 7;
		s.sunset = new Pose(wx + cx, sy, wz + cz, 90f, 6f);

		// night: a plains sample near the centre away from water; torch grid around it, camera 24 out diagonally
		int[] pt = null;
		int ptD = Integer.MAX_VALUE;
		for (int a = 1; a < 8; a++)
			for (int b = 1; b < 8; b++) {
				if (cat[a][b] != PLAINS) continue;
				boolean dry = true;
				for (int i = -1; i <= 1; i++)
					for (int j = -1; j <= 1; j++)
						if (cat[a + i][b + j] == WATER) dry = false;
				int d = Math.abs(a - HALF) + Math.abs(b - HALF) + (dry ? 0 : 6);
				if (d < ptD) {
					ptD = d;
					pt = new int[] {a, b};
				}
			}
		if (pt == null) pt = new int[] {(int) Math.round(pc[0] / STEP) + HALF, (int) Math.round(pc[1] / STEP) + HALF};
		double px = (pt[0] - HALF) * STEP, pz = (pt[1] - HALF) * STEP;
		s.torchX = (int) Math.round(px) + cx;
		s.torchZ = (int) Math.round(pz) + cz;
		int gp = h[Math.max(0, Math.min(8, pt[0]))][Math.max(0, Math.min(8, pt[1]))];
		s.night = Pose.lookAt(px - 20 + cx, gp + 9 + EYE, pz - 20 + cz, px + cx, gp + 1, pz + cz);
		return s;
	}

	private static double[] centroid(int[][] cat, int c) {
		double x = 0, z = 0;
		int n = 0;
		for (int a = 0; a < 9; a++)
			for (int b = 0; b < 9; b++)
				if (cat[a][b] == c) {
					x += (a - HALF) * STEP;
					z += (b - HALF) * STEP;
					n++;
				}
		return n == 0 ? new double[] {0, 0} : new double[] {x / n, z / n};
	}

	/** Nearest-sample height at an offset from the centre (clamped to the grid). */
	private static int hAt(int[][] h, double ox, double oz) {
		int a = (int) Math.round(ox / STEP) + HALF, b = (int) Math.round(oz / STEP) + HALF;
		return h[Math.max(0, Math.min(8, a))][Math.max(0, Math.min(8, b))];
	}

	/** Torch positions (x,z) on a 7x7 grid, 6 apart, around the night point. */
	public List<int[]> torchColumns() {
		List<int[]> l = new ArrayList<>();
		for (int i = -3; i <= 3; i++)
			for (int j = -3; j <= 3; j++) l.add(new int[] {torchX + i * 6, torchZ + j * 6});
		return l;
	}

	public static BlockPos blockPos(Pose p) {
		return BlockPos.containing(p.x(), p.y(), p.z());
	}

	public Map<String, Object> json() {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("centerX", cx);
		m.put("centerZ", cz);
		m.put("waterFrac", waterFrac);
		m.put("forestFrac", forestFrac);
		m.put("plainsFrac", plainsFrac);
		m.put("minHeight", minH);
		m.put("maxHeight", maxH);
		m.put("ideal", ideal);
		m.put("candidatesTried", candidatesTried);
		m.put("torchesPlaced", torchesPlaced);
		m.put("torchVersion", torchVersion);
		m.put("torchX", torchX);
		m.put("torchZ", torchZ);
		m.put("flightYaw", flightYaw);
		m.put("overview", overview.json());
		m.put("forest", forest.json());
		m.put("sunset", sunset.json());
		m.put("night", night.json());
		return m;
	}

	public static Spot of(Map<?, ?> m) {
		Spot s = new Spot();
		s.cx = ((Number) m.get("centerX")).intValue();
		s.cz = ((Number) m.get("centerZ")).intValue();
		s.waterFrac = ((Number) m.get("waterFrac")).doubleValue();
		s.forestFrac = ((Number) m.get("forestFrac")).doubleValue();
		s.plainsFrac = ((Number) m.get("plainsFrac")).doubleValue();
		s.minH = ((Number) m.get("minHeight")).intValue();
		s.maxH = ((Number) m.get("maxHeight")).intValue();
		s.ideal = Boolean.TRUE.equals(m.get("ideal"));
		s.candidatesTried = ((Number) m.get("candidatesTried")).intValue();
		s.torchesPlaced = Boolean.TRUE.equals(m.get("torchesPlaced"));
		s.torchVersion = m.get("torchVersion") instanceof Number v ? v.intValue() : 0;
		s.torchX = ((Number) m.get("torchX")).intValue();
		s.torchZ = ((Number) m.get("torchZ")).intValue();
		s.flightYaw = ((Number) m.get("flightYaw")).doubleValue();
		s.overview = Pose.of((Map<?, ?>) m.get("overview"));
		s.forest = Pose.of((Map<?, ?>) m.get("forest"));
		s.sunset = Pose.of((Map<?, ?>) m.get("sunset"));
		s.night = Pose.of((Map<?, ?>) m.get("night"));
		return s;
	}
}
