// Copyright 2026 mcopt-shade contributors. Licensed under the Apache License, Version 2.0 (LICENSE).
package mcopt.metal.shade.shadow;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/**
 * Which terrain casts into each cascade this frame, as lists of 32-quad chunks per (cascade, pass kind, arena).
 * <p>
 * mcopt 0.3 port (v12, P0): up to 1.1 the lists were read from Sodium's section render data (its regions, arenas and
 * per-facing vertex ranges). mcopt 0.3 draws terrain with its own renderer and this build has no Sodium, so for now
 * {@link #build} lists no terrain at all (ShadowMixinPlugin keeps the shadow pass off until then); the casters come back
 * from mcopt's own renderer (OwnTerrain.shadowCull / shadowBuffers) in P4. The Sodium version is v11's
 * metal/src/main/java/mcopt/metal/shade/shadow/ShadowCasters.java. Entity casters (EntityCasters) don't depend on it.
 */
final class ShadowCasters {
	/** KIND_TRANSLUCENT (M3 coloured shadows): water, stained glass, ice..., near cascade only, drawn into the tint map. */
	static final int KIND_SOLID = 0, KIND_CUTOUT = 1, KIND_TRANSLUCENT = 2;
	private static final int REGION_BYTES = 16, CHUNK_BYTES = 8;
	private static final int UNASSIGNED = 6;

	/** A list of chunks drawn with one instanced draw: one arena, one kind, one cascade. */
	static final class DrawList {
		GpuBuffer arena;
		int kind;
		/** firstVertex, packed pairs. */
		int[] chunks = new int[256];
		int count;

		DrawList(GpuBuffer arena, int kind) {
			this.arena = arena;
			this.kind = kind;
		}
	}

	/** Per cascade: its draw lists this frame (empty when the cascade isn't redrawn). */
	@SuppressWarnings("unchecked")
	final List<DrawList>[] lists = new List[] {new ArrayList<DrawList>(), new ArrayList<DrawList>()};
	/** ShadowRegion records (camera-relative origin + pad), regionCount of them. */
	long regions = MemoryUtil.nmemAlloc((long) REGION_BYTES * 16);
	int regionCount;
	/** Sections and quads listed, per cascade, for stats. */
	final int[] sections = new int[2];
	final long[] quads = new long[2];
	/** Why the last build found nothing to do, or null. */
	@Nullable String problem;
	/** M3 coloured shadows: the near cascade's translucent casters' world block boxes (x0 y0 z0 x1 y1 z1, max exclusive). */
	int[] tintBoxes = new int[6 * 64];
	int tintCount;
	/** M3.3 far cascade spread: the part of the far cascade's sections listed this frame (-1: all of them), of farParts. */
	int farPart = -1, farParts = 1;
	/** M3.3 (mcopt.shade.tintTight): the tint rectangle from the translucent blocks themselves. */
	boolean tintTight;
	/** Sections scanned this build (stats). */
	int tightScans;

	/**
	 * Builds this frame's lists for the cascades with draw[c] set: none yet (see the class comment), so every cascade gets
	 * only the entity casters. Returns true (an empty but valid build).
	 */
	boolean build(ShadowCascade[] cascades, boolean[] draw, int faceMask, double camX, double camY, double camZ, boolean lightMode, boolean tint,
		boolean tintNoWater) {
		for (int c = 0; c < 2; c++) {
			this.lists[c].clear();
			this.sections[c] = 0;
			this.quads[c] = 0;
		}
		this.regionCount = 0;
		this.tintCount = 0;
		this.tightScans = 0;
		this.problem = null;
		return true;
	}

	/** M3.3 far spread: the part (0..parts-1) of section column (sx, sz): a lattice, so every 2 x 2 columns hold one of each of 4. */
	static int part(int sx, int sz, int parts) {
		return Math.floorMod(sx + sz * ((parts + 1) / 2), parts);
	}

	/** The faces (bits POS_X, POS_Y, POS_Z, NEG_X, NEG_Y, NEG_Z = 0..5, UNASSIGNED = 6) that face direction l, plus UNASSIGNED; or all of them. */
	static int faceMask(double[] l, boolean all) {
		if (all) return (1 << 7) - 1;
		final double eps = 1e-4;
		int mask = 1 << UNASSIGNED;
		if (l[0] > eps) mask |= 1;
		if (l[1] > eps) mask |= 1 << 1;
		if (l[2] > eps) mask |= 1 << 2;
		if (l[0] < -eps) mask |= 1 << 3;
		if (l[1] < -eps) mask |= 1 << 4;
		if (l[2] < -eps) mask |= 1 << 5;
		return mask;
	}

	static int chunkBytes() {
		return CHUNK_BYTES;
	}

	static int regionBytes() {
		return REGION_BYTES;
	}

	void close() {
		MemoryUtil.nmemFree(this.regions);
		this.regions = 0;
	}
}
