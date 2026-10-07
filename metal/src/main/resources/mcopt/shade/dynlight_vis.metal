// Copyright 2026 mcopt-shade contributors
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
// License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0. Unless required by applicable
// law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
// OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and
// limitations under the License.
//
// Dynamic lights: the visibility volumes (compiled by mcdynlight.m; dynlight.metal describes the whole module). One thread per
// voxel of each occluded light's 32^3 block of the world around it: it walks the voxels a ray from the light's centre to the
// voxel's centre crosses (3D DDA, Amanatides & Woo 1987) through the CPU's block occupancy and writes 1 when none of them
// (the light's own voxel and the target excepted) is an opaque block, else 0. The shading samples it trilinearly in front
// of each surface, so light stops at walls, floors and ceilings with block-sized soft edges.
//
// Occupancy (mcopt.metal.shade.light.Occupancy, a shared buffer the CPU fills from the client's chunk sections): a toroidal
// window of 8 x 8 x 8 sections (128^3 blocks) around the camera, one bit per block: section slot (sy & 7) * 64 +
// (sz & 7) * 8 + (sx & 7), 128 words each, bit index (y & 15) << 8 | (z & 15) << 4 | (x & 15) (the order of the game's
// PalettedContainer). Sections not (yet) known read as air.
#include <metal_stdlib>
using namespace metal;

struct DlVolume {
	int4 origin;  // xyz absolute block coordinates of voxel (0, 0, 0), w the slab of the 3D texture it goes to
	float4 light; // xyz light position relative to origin (blocks), w reach (blocks)
};

#define DL_VOLUMES_MAX 64
struct DlVisParams {
	uint4 info;   // x volumes to compute this frame (the others keep last frame's slabs), yzw 0
	DlVolume vols[DL_VOLUMES_MAX];
};

static inline bool dlSolid(device const uint *occ, int3 b) {
	uint slot = ((uint(b.y >> 4) & 7u) << 6) | ((uint(b.z >> 4) & 7u) << 3) | (uint(b.x >> 4) & 7u);
	uint idx = (uint(b.y & 15) << 8) | (uint(b.z & 15) << 4) | uint(b.x & 15);
	return (occ[(slot << 7) | (idx >> 5)] >> (idx & 31u)) & 1u;
}

kernel void shade_dl_vis(uint3 t [[thread_position_in_grid]], constant DlVisParams &P [[buffer(0)]], device const uint *occ [[buffer(1)]],
                         texture3d<half, access::write> out [[texture(0)]]) {
	uint k = t.z >> 5;
	if (k >= P.info.x) return;
	DlVolume V = P.vols[k];
	int3 target = int3(int(t.x), int(t.y), int(t.z & 31u));
	float3 src = V.light.xyz;
	float3 dv = float3(target) + 0.5 - src;
	float dist = length(dv);
	half visible = 0.0h;
	if (dist <= V.light.w - 1.0) { // the shading stops 2 blocks short of the reach (SHADE_DL_CUT); one voxel of margin
		visible = 1.0h;
		int3 cell = int3(floor(src));
		if (any(cell != target)) {
			float3 dir = dv / max(dist, 1e-4);
			int3 stp = int3(sign(dir));
			float3 inv = 1.0 / max(abs(dir), 1e-6);
			float3 next = select(float3(cell) + 1.0 - src, src - float3(cell), dir < 0.0); // distance along each axis to the next boundary
			float3 tMax = select(next * inv, float3(1e30), stp == 0);
			float3 tDelta = select(inv, float3(1e30), stp == 0);
			for (int i = 0; i < 96; i++) {
				if (tMax.x < tMax.y && tMax.x < tMax.z) {
					cell.x += stp.x;
					tMax.x += tDelta.x;
				} else if (tMax.y < tMax.z) {
					cell.y += stp.y;
					tMax.y += tDelta.y;
				} else {
					cell.z += stp.z;
					tMax.z += tDelta.z;
				}
				if (all(cell == target)) break;
				if (dlSolid(occ, V.origin.xyz + cell)) {
					visible = 0.0h;
					break;
				}
			}
		}
	}
	out.write(half4(visible), uint3(t.x, t.y, uint(V.origin.w) * 32u + (t.z & 31u)));
}
