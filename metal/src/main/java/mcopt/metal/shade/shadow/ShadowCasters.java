// Copyright 2026 mcopt-shade contributors. Licensed under the Apache License, Version 2.0 (LICENSE).
package mcopt.metal.shade.shadow;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataStorage;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataUnsafe;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/**
 * Which Sodium geometry casts into each cascade this frame, as lists of 64-quad chunks per (cascade, pass kind, arena),
 * read straight from Sodium's section render data (SectionRenderDataStorage / SectionRenderDataUnsafe): the same base
 * vertex and per-facing vertex counts its own renderer turns into draws. Read-only: nothing in Sodium is changed, so this
 * is indifferent to mcopt's list/culling mixins (mcopt.cpu.lists, cullRecover, cullReuse).
 * <ul>
 * <li>{@code camera} mode: the sections in Sodium's render lists for this frame (frustum + occlusion culled for the camera).</li>
 * <li>{@code light} mode: every section of every loaded region whose box can cast into the cascade (the light frustum),
 * whether or not the camera sees it: a mountain behind the player, a roof overhead.</li>
 * </ul>
 * Facing: only the face directions that face the light (Sodium's per-facing ranges), plus UNASSIGNED quads (non-axis
 * aligned: plants), unless mcopt.shade.shadowFaces=all.
 */
final class ShadowCasters {
	static final int KIND_SOLID = 0, KIND_CUTOUT = 1;
	private static final int CHUNK_QUADS = 64;
	private static final int REGION_BYTES = 16, CHUNK_BYTES = 8;
	private static final int UNASSIGNED = 6;
	/** Sodium's RenderSectionManager, private in SodiumWorldRenderer (mcopt's Lod reads it the same way). */
	private static final @Nullable Field RSM = rsmField();

	/** A list of chunks drawn with one instanced draw: one arena, one kind, one cascade. */
	static final class DrawList {
		final GpuBuffer arena;
		final int kind;
		/** firstVertex, packed pairs. */
		int[] chunks = new int[256];
		int count;

		DrawList(GpuBuffer arena, int kind) {
			this.arena = arena;
			this.kind = kind;
		}

		void add(int firstVertex, int packed) {
			if (this.count * 2 == this.chunks.length) this.chunks = java.util.Arrays.copyOf(this.chunks, this.chunks.length * 2);
			this.chunks[this.count * 2] = firstVertex;
			this.chunks[this.count * 2 + 1] = packed;
			this.count++;
		}
	}

	/** Per cascade: its draw lists this frame (empty when the cascade isn't redrawn). */
	@SuppressWarnings("unchecked")
	final List<DrawList>[] lists = new List[] {new ArrayList<DrawList>(), new ArrayList<DrawList>()};
	/** ShadowRegion records (camera-relative origin + pad), regionCount of them. */
	long regions = MemoryUtil.nmemAlloc((long) REGION_BYTES * 256);
	private int regionCapacity = 256;
	int regionCount;
	/** Sections and quads listed, per cascade, for stats. */
	final int[] sections = new int[2];
	final long[] quads = new long[2];
	/** Why the last build found nothing to do, or null. */
	@Nullable String problem;

	private static @Nullable Field rsmField() {
		try {
			Field f = SodiumWorldRenderer.class.getDeclaredField("renderSectionManager");
			f.setAccessible(true);
			return f;
		} catch (ReflectiveOperationException | RuntimeException e) {
			System.out.println("[mcopt] shade: no Sodium render section manager, no shadow casters: " + e);
			return null;
		}
	}

	/**
	 * Builds this frame's lists for the cascades with draw[c] set. Camera at world (camX, camY, camZ), as this frame's
	 * terrain uses it. faceMask[c]: Sodium ModelQuadFacing bits to draw. Returns false when Sodium has nothing to give.
	 */
	boolean build(ShadowCascade[] cascades, boolean[] draw, int faceMask, double camX, double camY, double camZ, boolean lightMode) {
		for (int c = 0; c < 2; c++) {
			this.lists[c].clear();
			this.sections[c] = 0;
			this.quads[c] = 0;
		}
		this.regionCount = 0;
		this.problem = null;
		SodiumWorldRenderer sodium = SodiumWorldRenderer.instanceNullable();
		if (sodium == null || RSM == null) {
			this.problem = "no Sodium world renderer";
			return false;
		}
		RenderSectionManager rsm;
		try {
			rsm = (RenderSectionManager) RSM.get(sodium);
		} catch (ReflectiveOperationException | RuntimeException e) {
			this.problem = "Sodium render section manager: " + e;
			return false;
		}
		if (rsm == null) {
			this.problem = "no Sodium render section manager";
			return false;
		}
		CameraTransform camera = new CameraTransform(camX, camY, camZ);
		if (lightMode) {
			for (RenderRegion region : rsm.regions.getLoadedRegions()) this.region(region, null, cascades, draw, faceMask, camera);
		} else {
			Iterator<ChunkRenderList> it = rsm.getRenderLists().iterator(false);
			while (it.hasNext()) {
				ChunkRenderList list = it.next();
				this.region(list.getRegion(), list, cascades, draw, faceMask, camera);
			}
		}
		return true;
	}

	private final ArrayList<DrawList> regionLists = new ArrayList<>(4);
	private final boolean[] sectionIn = new boolean[2];

	/** One region: its sections that cast into a cascade being drawn, from list (camera mode) or all of them (light mode). */
	private void region(RenderRegion region, @Nullable ChunkRenderList list, ShadowCascade[] cascades, boolean[] draw, int faceMask, CameraTransform camera) {
		RenderRegion.DeviceResources resources = region.getResources();
		if (resources == null) return;
		GpuBuffer arena = resources.getGeometryBuffer();
		if (arena == null || arena.isClosed()) return;
		double ox = region.getOriginX(), oy = region.getOriginY(), oz = region.getOriginZ();
		boolean any = false;
		for (int c = 0; c < 2; c++) {
			if (draw[c] && cascades[c].casts(ox, oy, oz, ox + RenderRegion.REGION_WIDTH * 16, oy + RenderRegion.REGION_HEIGHT * 16, oz + RenderRegion.REGION_LENGTH * 16)) any = true;
		}
		if (!any) return;
		int regionIndex = -1;
		for (int kind = 0; kind < 2; kind++) {
			TerrainRenderPass pass = kind == KIND_SOLID ? DefaultTerrainRenderPasses.SOLID : DefaultTerrainRenderPasses.CUTOUT;
			SectionRenderDataStorage storage = region.getStorage(pass);
			if (storage == null) continue;
			DrawList near = null, far = null;
			if (list != null) {
				var it = list.sectionsWithGeometryIterator(false);
				if (it == null) return;
				while (it.hasNext()) {
					int section = it.nextByteAsInt();
					if (!this.sectionCasts(region, section, cascades, draw)) continue;
					if (regionIndex < 0) regionIndex = this.addRegion(region, camera);
					if (draw[0] && this.sectionIn[0] && near == null) near = this.listFor(0, arena, kind);
					if (draw[1] && this.sectionIn[1] && far == null) far = this.listFor(1, arena, kind);
					this.emit(storage.getDataPointer(section), faceMask, regionIndex, this.sectionIn[0] ? near : null, this.sectionIn[1] ? far : null);
				}
			} else {
				for (int section = 0; section < RenderRegion.REGION_SIZE; section++) {
					long ptr = storage.getDataPointer(section);
					if ((SectionRenderDataUnsafe.getSliceMask(ptr) & faceMask) == 0) continue;
					if (!this.sectionCasts(region, section, cascades, draw)) continue;
					if (regionIndex < 0) regionIndex = this.addRegion(region, camera);
					if (draw[0] && this.sectionIn[0] && near == null) near = this.listFor(0, arena, kind);
					if (draw[1] && this.sectionIn[1] && far == null) far = this.listFor(1, arena, kind);
					this.emit(ptr, faceMask, regionIndex, this.sectionIn[0] ? near : null, this.sectionIn[1] ? far : null);
				}
			}
		}
	}

	/** Sets sectionIn[c] for the section's 16^3 box; true if it casts into any cascade being drawn. */
	private boolean sectionCasts(RenderRegion region, int section, ShadowCascade[] cascades, boolean[] draw) {
		// Sodium's LocalSectionIndex: x bits 5-7, y bits 0-1, z bits 2-4
		double x = (region.getChunkX() + (section >> 5 & 7)) * 16.0, y = (region.getChunkY() + (section & 3)) * 16.0, z = (region.getChunkZ() + (section >> 2 & 7)) * 16.0;
		boolean any = false;
		for (int c = 0; c < 2; c++) {
			this.sectionIn[c] = draw[c] && cascades[c].casts(x, y, z, x + 16, y + 16, z + 16);
			any |= this.sectionIn[c];
		}
		return any;
	}

	private DrawList listFor(int cascade, GpuBuffer arena, int kind) {
		for (DrawList l : this.lists[cascade]) {
			if (l.arena == arena && l.kind == kind) return l;
		}
		DrawList l = new DrawList(arena, kind);
		this.lists[cascade].add(l);
		return l;
	}

	/** The region's ShadowRegion record: its origin relative to the camera, computed exactly as Sodium's renderer does. */
	private int addRegion(RenderRegion region, CameraTransform camera) {
		if (this.regionCount == this.regionCapacity) this.regions = MemoryUtil.nmemRealloc(this.regions, (long) (this.regionCapacity *= 2) * REGION_BYTES);
		long at = this.regions + (long) this.regionCount * REGION_BYTES;
		MemoryUtil.memPutFloat(at, (region.getOriginX() - camera.intX) - camera.fracX);
		MemoryUtil.memPutFloat(at + 4, (region.getOriginY() - camera.intY) - camera.fracY);
		MemoryUtil.memPutFloat(at + 8, (region.getOriginZ() - camera.intZ) - camera.fracZ);
		MemoryUtil.memPutFloat(at + 12, 0f);
		return this.regionCount++;
	}

	/**
	 * A section's ranges whose facing is in faceMask, merged where consecutive, cut into 64-quad chunks, into near and/or far.
	 * Layout as Sodium's renderer reads it: the section's vertices start at its base vertex, one range per storage slot i
	 * (vertex count i), slot i holding facing (facingList >> 8 i) & 0xFF.
	 */
	private void emit(long ptr, int faceMask, int regionIndex, @Nullable DrawList near, @Nullable DrawList far) {
		if (near == null && far == null) return;
		if ((SectionRenderDataUnsafe.getSliceMask(ptr) & faceMask) == 0) return;
		long facingList = SectionRenderDataUnsafe.getFacingList(ptr);
		long vertex = SectionRenderDataUnsafe.getBaseVertex(ptr);
		long start = -1, end = -1;
		for (int i = 0; i < 7; i++) {
			long count = SectionRenderDataUnsafe.getVertexCount(ptr, i);
			if (count == 0) continue;
			int facing = (int) (facingList >>> (i * 8) & 0xFF);
			if ((faceMask >> facing & 1) != 0) {
				if (start >= 0 && end == vertex) {
					end = vertex + count;
				} else {
					if (start >= 0) this.range(start, end, regionIndex, near, far);
					start = vertex;
					end = vertex + count;
				}
			}
			vertex += count;
		}
		if (start >= 0) this.range(start, end, regionIndex, near, far);
		if (near != null) this.sections[0]++;
		if (far != null) this.sections[1]++;
	}

	private void range(long start, long end, int regionIndex, @Nullable DrawList near, @Nullable DrawList far) {
		long quads = (end - start) >> 2;
		if (near != null) this.quads[0] += quads;
		if (far != null) this.quads[1] += quads;
		for (long q = 0; q < quads; q += CHUNK_QUADS) {
			int first = (int) (start + q * 4);
			int packed = (int) (Math.min(CHUNK_QUADS, quads - q) - 1) | regionIndex << 6;
			if (near != null) near.add(first, packed);
			if (far != null) far.add(first, packed);
		}
	}

	/** The faces (ModelQuadFacing bits) that face direction l, plus UNASSIGNED; or all of them. */
	static int faceMask(double[] l, boolean all) {
		if (all) return (1 << 7) - 1;
		final double eps = 1e-4;
		int mask = 1 << UNASSIGNED;
		// POS_X, POS_Y, POS_Z, NEG_X, NEG_Y, NEG_Z = 0..5
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
