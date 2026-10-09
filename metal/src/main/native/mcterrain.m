// Portions of this file are copied or adapted from Sodium (https://github.com/CaffeineMC/sodium), Copyright
// JellySquid (jellysquid3) and contributors. Those portions are licensed under the PolyForm Shield License 1.0.0
// (LICENSES/PolyForm-Shield-1.0.0.md, https://polyformproject.org/licenses/shield/1.0.0); the rest of the
// file is Apache-2.0 like the rest of mcopt. See NOTICE.
//
// Split-pass terrain occlusion for MetalTerrain.java:
//
//  1. In the pre command buffer, so it runs ahead of the frame: list the quads that were visible last frame (per-quad
//     visibility bits). The frame draws those first.
//  2. In the frame (mc_occ_suspend): split the render pass and turn its depth into hierarchical Z (farthest depth per 4x4
//     pixels and coarser).
//  3. Cull every quad against that hi-Z (plus frustum and pixel-coverage tests), mark what passes as visible this frame, and
//     compact the survivors not drawn yet, in order, into the list the reopened pass draws (sodium_terrain.vs.metal, PULLED).
//
// The occluders are this frame's own geometry at this frame's camera, so nothing that ends up visible can be culled; how
// well last frame's visible set predicts this one only decides how much gets culled. Order matters for the draw because
// coplanar quads resolve depth ties by draw order.
#import "mcmetal.h"
#include <string.h>

static NSString *cullSource = @
	"#include <metal_stdlib>\n"
	"using namespace metal;\n"
	"struct Chunk { uint firstVertex; uint packed; };\n"  // packed: quads - 1 (bits 0-5), arena (6-8), region (9-31)
	"struct Region { packed_float3 origin; int time; uint id; };\n"
	// Per arena: its vertices; its bounds cache, one uint2 per quad slot (first vertex / 4, unique among live quads):
	// x = min corner (3 x 8 bits, 1/8 block steps from -8) | section << 24, y = max corner | (first vertex & 3) << 24 | valid << 26,
	// zeroed by anything Sodium writes into the arena (MetalTerrain.invalidate), zero meaning recompute; one visibility bit
	// per quad slot for last frame (read) and this one (written); and a box cache, one uint2 per chunk at its first slot,
	// holding its quads' bounds merged at 1/4 block: x = min corner (3 x 7 bits) | section << 24, y = max corner |
	// (quads - 1) << 21 | (first vertex & 3) << 27 | valid << 29, zeroed with the bounds of any slot the chunk spans.
	"struct Arenas { device const uint *vertices[8]; device uint2 *bounds[8]; device const uint *visibleLast[8]; device atomic_uint *visibleNext[8];\n"
	"                device uint2 *boxes[8]; };\n"
	"struct Frame { float4x4 viewProj; float2 size; uint chunks; uint pad0; uint hizLevels; uint number; uint pad[2]; };\n"
	"struct DrawArgs { uint indexCount; uint instanceCount; uint indexStart; int baseVertex; uint baseInstance; };\n"
	"constant uint HIZ_PX = 4u;\n"  // a level-0 hi-Z texel is HIZ_PX x HIZ_PX pixels (and HIZ_PX in the host code below)
	// A quad's section-local box, rounded outwards: positions are 20-bit fixed point, (p + 8) * 32768, so 1/8 block is 4096.
	"static uint2 boundsOf(device const uint *v, uint phase) {\n"
	"  uint3 lo = uint3(0xFFFFFFFFu), hi = uint3(0u);\n"
	"  for (uint i = 0; i < 4; i++) {\n"
	"    uint3 h = (uint3(v[i * 5u]) >> uint3(0u, 10u, 20u)) & uint3(1023u);\n"
	"    uint3 l = (uint3(v[i * 5u + 1u]) >> uint3(0u, 10u, 20u)) & uint3(1023u);\n"
	"    uint3 p = (h << uint3(10u)) | l;\n"
	"    lo = min(lo, p); hi = max(hi, p);\n"
	"  }\n"
	"  uint3 qlo = lo >> 12u, qhi = uint3(max(int3((hi + 4095u) >> 12u) - 1, int3(0)));\n"
	"  return uint2(qlo.x | qlo.y << 8 | qlo.z << 16 | (v[4] >> 24) << 24, qhi.x | qhi.y << 8 | qhi.z << 16 | phase << 24 | 1u << 26);\n"
	"}\n"
	// Whether the box [lo, hi] may put a pixel on screen. False when all its corners are past one clip plane, or when it lies
	// wholly in front of the camera and its screen bounds hold no pixel center, or when it's behind the hi-Z depth
	// everywhere it covers: its nearest point (depth is reversed, larger is nearer) is farther than the farthest occluder in
	// the hi-Z texels under its screen rectangle, picked at the level where that rectangle spans at most 4 x 4 texels (a box
	// needing a level past f.hizLevels counts as shown). The margins absorb rounding differences with the draw's own vertex
	// math, and the relative depth margin keeps near-coplanar surfaces (a carpet on a block is 1/16 above it) visible at any
	// distance: culling must never drop a quad the draw would have shown. Texels past the screen (level 0 is padded) can only
	// lower the minimum, so whatever they hold is safe. Screen y is the render target's row (the draw flips clip y).
	// The corners are one matrix product plus the box's edges along the matrix columns, not eight products.
	"static bool mayShow(float3 lo, float3 hi, constant Frame &f, texture2d<float> hiz) {\n"
	"  float4 c0 = f.viewProj * float4(lo, 1.0);\n"
	"  float3 d = hi - lo;\n"
	"  float4 ex = f.viewProj[0] * d.x, ey = f.viewProj[1] * d.y, ez = f.viewProj[2] * d.z;\n"
	"  float4 c[8] = {c0, c0 + ex, c0 + ey, c0 + ex + ey, c0 + ez, c0 + ex + ez, c0 + ey + ez, c0 + ex + ey + ez};\n"
	"  bool o0 = true, o1 = true, o2 = true, o3 = true, o4 = true, o5 = true, front = true;\n"
	"  for (uint i = 0; i < 8; i++) {\n"
	"    float w = c[i].w + 1e-4 * abs(c[i].w);\n"
	"    o0 = o0 && c[i].x < -w; o1 = o1 && c[i].x > w; o2 = o2 && c[i].y < -w; o3 = o3 && c[i].y > w;\n"
	"    o4 = o4 && c[i].z < -1e-4 * abs(c[i].w); o5 = o5 && c[i].z > w; front = front && c[i].w > 0.0;\n"
	"  }\n"
	"  if (o0 || o1 || o2 || o3 || o4 || o5) return false;\n"
	"  if (!front) return true;\n"
	"  float2 slo = float2(INFINITY), shi = float2(-INFINITY);\n"
	"  float nearest = 0.0;\n"
	"  for (uint i = 0; i < 8; i++) {\n"
	"    float r = 1.0 / c[i].w;\n"
	"    float2 s = (c[i].xy * r * 0.5 + 0.5) * f.size;\n"
	"    slo = min(slo, s); shi = max(shi, s);\n"
	"    nearest = max(nearest, c[i].z * r);\n"
	"  }\n"
	"  if (!all(ceil(slo - 0.51) <= floor(shi - 0.49))) return false;\n"
	"  float2 tlo = clamp(slo, 0.0, f.size - 1.0) / float(HIZ_PX), thi = clamp(shi, 0.0, f.size - 1.0) / float(HIZ_PX);\n"
	"  float extent = max(thi.x - tlo.x, thi.y - tlo.y);\n"
	"  uint level = extent <= 3.0 ? 0u : uint(ceil(log2(extent / 3.0)));\n"
	"  if (level >= f.hizLevels) return true;\n"
	"  uint2 last = uint2(hiz.get_width(level), hiz.get_height(level)) - 1u;\n"
	"  uint2 a = min(uint2(tlo) >> level, last), b = min(uint2(thi) >> level, last);\n"
	"  float farthest = 1.0;\n"
	"  for (uint y = a.y; y <= b.y; y++) for (uint x = a.x; x <= b.x; x++) farthest = min(farthest, hiz.read(uint2(x, y), level).x);\n"
	"  return nearest >= farthest * (1.0 - 1e-4);\n"
	"}\n"
	// A thread per chunk, ahead of terrain_cull: when the chunk's box can't show, none of its quads can (their boxes lie in
	// it, and mayShow is false for a box inside one it's false for), so the chunk gets an empty mask and count 0, which
	// terrain_cull skips; otherwise count 1. A missing box is built from the quads' cached bounds once they're all there and
	// in one section; until then terrain_cull, which fills them, tests every quad.
	"kernel void terrain_chunk(const device Chunk *chunks [[buffer(0)]], const device Region *regions [[buffer(1)]],\n"
	"                          constant Arenas &arenas [[buffer(2)]], constant Frame &f [[buffer(3)]], device uint2 *masks [[buffer(4)]],\n"
	"                          device uint *counts [[buffer(5)]], texture2d<float> hiz [[texture(0)]], uint chunk [[thread_position_in_grid]]) {\n"
	"  if (chunk >= f.chunks) return;\n"
	"  Chunk c = chunks[chunk];\n"
	"  uint arena = (c.packed >> 6) & 7u, n = c.packed & 63u, phase = c.firstVertex & 3u, slot = c.firstVertex >> 2;\n"
	"  uint key = n << 21 | phase << 27 | 1u << 29;\n"
	"  device uint2 *entry = arenas.boxes[arena] + slot;\n"
	"  uint2 box = *entry;\n"
	"  if ((box.y >> 21) << 21 != key) {\n"
	"    device const uint2 *b = arenas.bounds[arena] + slot;\n"
	"    uint section = b[0].x >> 24;\n"
	"    uint3 lo = uint3(255u), hi = uint3(0u);\n"
	"    for (uint i = 0; i <= n; i++) {\n"
	"      uint2 e = b[i];\n"
	"      if ((e.y >> 24 & 7u) != (phase | 4u) || e.x >> 24 != section) { counts[chunk] = 1u; return; }\n"
	"      lo = min(lo, (uint3(e.x) >> uint3(0u, 8u, 16u)) & 255u);\n"
	"      hi = max(hi, (uint3(e.y) >> uint3(0u, 8u, 16u)) & 255u);\n"
	"    }\n"
	"    lo >>= 1u; hi >>= 1u;\n"
	"    box = uint2(lo.x | lo.y << 7 | lo.z << 14 | section << 24, hi.x | hi.y << 7 | hi.z << 14 | key);\n"
	"    *entry = box;\n"
	"  }\n"
	"  Region r = regions[c.packed >> 9];\n"
	"  float3 base = float3(r.origin) + float3((uint3(box.x >> 24) >> uint3(5u, 0u, 2u)) & uint3(7u, 3u, 7u)) * 16.0 - 8.0;\n"
	"  float3 lo = base + float3((uint3(box.x) >> uint3(0u, 7u, 14u)) & 127u) * 0.25;\n"
	"  float3 hi = base + float3(((uint3(box.y) >> uint3(0u, 7u, 14u)) & 127u) + 1u) * 0.25;\n"
	"  bool show = mayShow(lo, hi, f, hiz);\n"
	"  if (!show) masks[chunk] = uint2(0u);\n"
	"  counts[chunk] = uint(show);\n"
	"}\n"
	// One 64-thread threadgroup per chunk, a thread per quad, after the quads visible last frame were drawn: every quad that
	// may show is visible this frame, and of those, the ones not drawn already go in the chunk's 64-bit mask for the draw.
	"kernel void terrain_cull(const device Chunk *chunks [[buffer(0)]], const device Region *regions [[buffer(1)]],\n"
	"                         constant Arenas &arenas [[buffer(2)]], constant Frame &f [[buffer(3)]], device uint2 *masks [[buffer(4)]],\n"
	"                         device uint *counts [[buffer(5)]], texture2d<float> hiz [[texture(0)]],\n"
	"                         uint chunk [[threadgroup_position_in_grid]], uint q [[thread_index_in_threadgroup]],\n"
	"                         uint sg [[simdgroup_index_in_threadgroup]], uint lane [[thread_index_in_simdgroup]]) {\n"
	"  threadgroup uint votes[2];\n"
	"  if (counts[chunk] == 0u) return;\n"  // terrain_chunk culled the whole chunk
	"  Chunk c = chunks[chunk];\n"
	"  bool keep = false;\n"
	"  if (q <= (c.packed & 63u)) {\n"
	"    uint arena = (c.packed >> 6) & 7u, first = c.firstVertex + q * 4u, slot = first >> 2;\n"
	"    device uint2 *entry = arenas.bounds[arena] + slot;\n"
	"    uint2 b = *entry;\n"
	"    if ((b.y >> 26 & 1u) == 0u || (b.y >> 24 & 3u) != (first & 3u)) {\n"
	"      b = boundsOf(arenas.vertices[arena] + first * 5u, first & 3u);\n"
	"      *entry = b;\n"
	"    }\n"
	"    Region r = regions[c.packed >> 9];\n"
	"    float3 base = float3(r.origin) + float3((uint3(b.x >> 24) >> uint3(5u, 0u, 2u)) & uint3(7u, 3u, 7u)) * 16.0 - 8.0;\n"
	"    float3 lo = base + float3((uint3(b.x) >> uint3(0u, 8u, 16u)) & 255u) * 0.125;\n"
	"    float3 hi = base + float3(((uint3(b.y) >> uint3(0u, 8u, 16u)) & 255u) + 1u) * 0.125;\n"
	"    keep = mayShow(lo, hi, f, hiz);\n"
	"    bool showed = (arenas.visibleLast[arena][slot >> 5] >> (slot & 31u) & 1u) != 0u;\n"
	"    if (keep) atomic_fetch_or_explicit(arenas.visibleNext[arena] + (slot >> 5), 1u << (slot & 31u), memory_order_relaxed);\n"
	"    keep = keep && !showed;\n"
	"  }\n"
	"  uint vote = uint(static_cast<simd_vote::vote_t>(simd_ballot(keep)));\n"
	"  if (lane == 0) votes[sg] = vote;\n"
	"  threadgroup_barrier(mem_flags::mem_threadgroup);\n"
	"  if (q == 0) {\n"
	"    masks[chunk] = uint2(votes[0], votes[1]);\n"
	"    counts[chunk] = popcount(votes[0]) + popcount(votes[1]);\n"
	"  }\n"
	"}\n"
	// The first list, a thread per chunk: the chunk's quads visible last frame, whose bits are its consecutive slots. No
	// other test: the rasterizer clips whatever went off screen since.
	"kernel void terrain_last(const device Chunk *chunks [[buffer(0)]], constant Arenas &arenas [[buffer(2)]], constant Frame &f [[buffer(3)]],\n"
	"                         device uint2 *masks [[buffer(4)]], device uint *counts [[buffer(5)]], uint chunk [[thread_position_in_grid]]) {\n"
	"  if (chunk >= f.chunks) return;\n"
	"  Chunk c = chunks[chunk];\n"
	"  uint slot = c.firstVertex >> 2, n = (c.packed & 63u) + 1u, s = slot & 31u;\n"
	"  device const uint *w = arenas.visibleLast[(c.packed >> 6) & 7u] + (slot >> 5);\n"
	// Only the words holding the chunk's bits: the last may be the arena's last.
	"  uint w0 = w[0], w1 = s + n > 32u ? w[1] : 0u, w2 = s + n > 64u ? w[2] : 0u;\n"
	"  ulong bits = ulong(uint((ulong(w1) << 32 | w0) >> s)) | ulong(uint((ulong(w2) << 32 | w1) >> s)) << 32;\n"
	"  if (n < 64u) bits &= (1ul << n) - 1ul;\n"
	"  masks[chunk] = uint2(uint(bits), uint(bits >> 32));\n"
	"  counts[chunk] = uint(popcount(bits));\n"
	"}\n"
	// One 1024-thread threadgroup: exclusive prefix sum of the chunk counts, the draw's arguments (64 quads per instance),
	// and padding after the last survivor so the last instance draws nothing past it.
	"kernel void terrain_scan(constant Frame &f [[buffer(3)]], device const uint *counts [[buffer(5)]], device uint *offsets [[buffer(6)]],\n"
	"                         device DrawArgs &args [[buffer(7)]], device uint2 *survivors [[buffer(8)]], uint t [[thread_index_in_threadgroup]],\n"
	"                         uint sg [[simdgroup_index_in_threadgroup]], uint lane [[thread_index_in_simdgroup]]) {\n"
	"  threadgroup uint sums[32];\n"
	"  threadgroup uint total;\n"
	"  uint n = f.chunks, per = (n + 1023u) / 1024u, begin = min(t * per, n), end = min(begin + per, n);\n"
	"  uint local = 0;\n"
	"  for (uint i = begin; i < end; i++) local += counts[i];\n"
	"  uint prefix = simd_prefix_exclusive_sum(local);\n"
	"  if (lane == 31) sums[sg] = prefix + local;\n"
	"  threadgroup_barrier(mem_flags::mem_threadgroup);\n"
	"  if (sg == 0) {\n"
	"    uint s = sums[lane], e = simd_prefix_exclusive_sum(s);\n"
	"    sums[lane] = e;\n"
	"    if (lane == 31) total = e + s;\n"
	"  }\n"
	"  threadgroup_barrier(mem_flags::mem_threadgroup);\n"
	"  uint running = sums[sg] + prefix;\n"
	"  for (uint i = begin; i < end; i++) { offsets[i] = running; running += counts[i]; }\n"
	"  uint kept = total;\n"
	"  if (t == 0) { args.indexCount = 384; args.instanceCount = (kept + 63u) / 64u; args.indexStart = 0; args.baseVertex = 0; args.baseInstance = 0; }\n"
	"  if (kept + t < ((kept + 63u) & ~63u)) survivors[kept + t] = uint2(0xFFFFFFFFu, 0u);\n"
	"}\n"
	// Same grid as terrain_cull: each surviving quad writes {first vertex, arena | region} at its chunk's offset plus its rank.
	"kernel void terrain_compact(const device Chunk *chunks [[buffer(0)]], device const uint2 *masks [[buffer(4)]],\n"
	"                            device const uint *offsets [[buffer(6)]], device uint2 *survivors [[buffer(8)]],\n"
	"                            uint chunk [[threadgroup_position_in_grid]], uint q [[thread_index_in_threadgroup]]) {\n"
	"  uint2 m = masks[chunk];\n"
	"  uint word = q < 32u ? m.x : m.y, bit = q & 31u;\n"
	"  if (((word >> bit) & 1u) == 0u) return;\n"
	"  uint rank = popcount(word & ((1u << bit) - 1u)) + (q < 32u ? 0u : popcount(m.x));\n"
	"  Chunk c = chunks[chunk];\n"
	"  survivors[offsets[chunk] + rank] = uint2(c.firstVertex + q * 4u, c.packed & ~63u);\n"
	"}\n"
	// Hi-Z levels 0 to HIZ_TOP from the pass's own depth in one dispatch: each texel the farthest (smallest, reversed Z) of
	// the pixels under it. A 32 x 32 threadgroup covers 128 x 128 pixels: a thread per level-0 texel (4 gathers of its 4 x 4
	// pixels), then the coarser levels in threadgroup memory. Level 0 is padded to a multiple of 32 texels each way, so every
	// level halves exactly; texels past the screen get 1.0.
	"constant uint HIZ_TOP = 5u;\n"
	"kernel void hiz_build(depth2d<float, access::sample> depth [[texture(0)]], array<texture2d<float, access::write>, HIZ_TOP + 1> levels [[texture(1)]],\n"
	"                      uint2 t [[thread_position_in_grid]], uint2 lid [[thread_position_in_threadgroup]], uint2 tg [[threadgroup_position_in_grid]]) {\n"
	"  constexpr sampler nearest(coord::pixel, address::clamp_to_edge, filter::nearest);\n"
	"  threadgroup float s[32 * 32];\n"
	"  uint2 size = uint2(depth.get_width(), depth.get_height()), p = t * HIZ_PX;\n"
	"  float m = 1.0;\n"
	"  if (all(p + HIZ_PX <= size)) {\n"
	// A gather at a pixel corner returns the 2 x 2 pixels around it.
	"    float2 c = float2(p);\n"
	"    float4 q = min(min(depth.gather(nearest, c + float2(1.0, 1.0)), depth.gather(nearest, c + float2(3.0, 1.0))),\n"
	"                   min(depth.gather(nearest, c + float2(1.0, 3.0)), depth.gather(nearest, c + float2(3.0, 3.0))));\n"
	"    m = min(min(q.x, q.y), min(q.z, q.w));\n"
	"  } else {\n"
	"    for (uint j = 0; j < HIZ_PX; j++) for (uint i = 0; i < HIZ_PX; i++)\n"
	"      if (p.x + i < size.x && p.y + j < size.y) m = min(m, depth.sample(nearest, float2(p + uint2(i, j)) + 0.5));\n"
	"  }\n"
	"  levels[0].write(float4(m), t);\n"
	"  s[lid.y * 32 + lid.x] = m;\n"
	"  for (uint l = 1; l <= HIZ_TOP; l++) {\n"
	"    uint n = 32u >> l;\n"
	"    bool active = lid.x < n && lid.y < n;\n"
	"    threadgroup_barrier(mem_flags::mem_threadgroup);\n"
	"    if (active) {\n"
	"      uint2 a = lid * 2u;\n"
	"      m = min(min(s[a.y * 32 + a.x], s[a.y * 32 + a.x + 1]), min(s[(a.y + 1) * 32 + a.x], s[(a.y + 1) * 32 + a.x + 1]));\n"
	"      levels[l].write(float4(m), tg * n + lid);\n"
	"    }\n"
	"    threadgroup_barrier(mem_flags::mem_threadgroup);\n"
	"    if (active) s[lid.y * 32 + lid.x] = m;\n"
	"  }\n"
	"}\n"
	// ---- Vanilla's clouds (MetalTerrain.cullClouds) ----
	// CloudRenderer's faces, 3 signed bytes each as clouds.vsh reads them: {cell x >> 1, cell z >> 1, direction | flags}, the
	// flags holding each cell coordinate's low bit. Lists, in order, the faces whose cell box isn't wholly past one side of
	// the view (a face lies in its cell's box, so the rest can't put a pixel on screen), and the draw's arguments. One
	// 1024-thread threadgroup walks the faces 1024 at a time: nothing waits for it but the clouds draw at the frame's end.
	"struct CloudInfo { float4 color; float3 offset; float3 cellSize; };\n"
	"kernel void clouds_cull(const device char *faces [[buffer(0)]], constant uint &count [[buffer(1)]], constant float4x4 &modelView [[buffer(2)]],\n"
	"                        constant float4x4 &proj [[buffer(3)]], constant CloudInfo &info [[buffer(4)]], device char *kept [[buffer(5)]],\n"
	"                        device DrawArgs &args [[buffer(6)]], uint t [[thread_index_in_threadgroup]],\n"
	"                        uint sg [[simdgroup_index_in_threadgroup]], uint lane [[thread_index_in_simdgroup]]) {\n"
	"  threadgroup uint sums[32];\n"
	"  threadgroup uint total;\n"
	"  float4x4 m = proj * modelView;\n"
	"  float4 ex = m[0] * info.cellSize.x, ey = m[1] * info.cellSize.y, ez = m[2] * info.cellSize.z;\n"
	"  uint written = 0;\n"
	"  for (uint base = 0; base < count; base += 1024u) {\n"
	"    uint i = base + t;\n"
	"    bool keep = false;\n"
	"    char3 face = char3(0);\n"
	"    if (i < count) {\n"
	"      face = char3(faces[i * 3u], faces[i * 3u + 1u], faces[i * 3u + 2u]);\n"
	"      int flags = face.z;\n"
	"      float3 cell = float3(int(face.x) * 2 + ((flags & 128) >> 7), 0.0, int(face.y) * 2 + ((flags & 64) >> 6));\n"
	"      float4 c0 = m * float4(cell * info.cellSize + info.offset, 1.0);\n"
	"      float4 c[8] = {c0, c0 + ex, c0 + ey, c0 + ex + ey, c0 + ez, c0 + ex + ez, c0 + ey + ez, c0 + ex + ey + ez};\n"
	"      bool o0 = true, o1 = true, o2 = true, o3 = true;\n"
	"      for (uint k = 0; k < 8; k++) {\n"
	"        float w = c[k].w + 1e-4 * abs(c[k].w);\n"
	"        o0 = o0 && c[k].x < -w; o1 = o1 && c[k].x > w; o2 = o2 && c[k].y < -w; o3 = o3 && c[k].y > w;\n"
	"      }\n"
	"      keep = !(o0 || o1 || o2 || o3);\n"
	"    }\n"
	"    uint rank = simd_prefix_exclusive_sum(uint(keep));\n"
	"    if (lane == 31u) sums[sg] = rank + uint(keep);\n"
	"    threadgroup_barrier(mem_flags::mem_threadgroup);\n"
	"    if (sg == 0u) {\n"
	"      uint s = sums[lane], e = simd_prefix_exclusive_sum(s);\n"
	"      sums[lane] = e;\n"
	"      if (lane == 31u) total = e + s;\n"
	"    }\n"
	"    threadgroup_barrier(mem_flags::mem_threadgroup);\n"
	"    if (keep) {\n"
	"      uint at = (written + sums[sg] + rank) * 3u;\n"
	"      kept[at] = face.x; kept[at + 1u] = face.y; kept[at + 2u] = face.z;\n"
	"    }\n"
	"    written += total;\n"
	"    threadgroup_barrier(mem_flags::mem_threadgroup);\n"
	"  }\n"
	"  if (t == 0u) { args.indexCount = written * 6u; args.instanceCount = 1u; args.indexStart = 0u; args.baseVertex = 0; args.baseInstance = 0u; }\n"
	"}\n";

#define MAX_LEVELS 16
#define HIZ_PX 4  // as in the MSL
#define HIZ_TOP 5  // as in the MSL

typedef struct {
	id<MTLComputePipelineState> chunk, cull, last, scan, compact, hizBuild, clouds;
	// Sized to the frame: the hi-Z pyramid (1 / HIZ_PX resolution) with a view per level.
	int width, height, levels;
	id<MTLTexture> hiz, hizLevel[MAX_LEVELS];
} Terrain;

static id<MTLComputePipelineState> kernel(id<MTLDevice> device, id<MTLLibrary> lib, NSString *name, NSError **e) {
	return [device newComputePipelineStateWithFunction:[[lib newFunctionWithName:name] autorelease] error:e];
}

Terrain *mc_terrain_new(Ctx *ctx, char *err, int errCap) {
	@autoreleasepool {
		NSError *e = nil;
		id<MTLDevice> device = ctx->device;
		MTLCompileOptions *o = [[MTLCompileOptions new] autorelease];
		o.languageVersion = MTLLanguageVersion3_0;
		id<MTLLibrary> lib = [[device newLibraryWithSource:cullSource options:o error:&e] autorelease];
		Terrain *t = calloc(1, sizeof(Terrain));
		if (lib) t->chunk = kernel(device, lib, @"terrain_chunk", &e);
		if (t->chunk) t->cull = kernel(device, lib, @"terrain_cull", &e);
		if (t->cull) t->last = kernel(device, lib, @"terrain_last", &e);
		if (t->last) t->scan = kernel(device, lib, @"terrain_scan", &e);
		if (t->scan) t->compact = kernel(device, lib, @"terrain_compact", &e);
		if (t->compact) t->hizBuild = kernel(device, lib, @"hiz_build", &e);
		if (t->hizBuild) t->clouds = kernel(device, lib, @"clouds_cull", &e);
		if (!t->clouds || t->scan.maxTotalThreadsPerThreadgroup < 1024 || t->hizBuild.maxTotalThreadsPerThreadgroup < 1024
			|| t->clouds.maxTotalThreadsPerThreadgroup < 1024) {
			strlcpy(err, e ? e.description.UTF8String : "terrain_scan, hiz_build or clouds_cull can't run 1024 threads per threadgroup", errCap);
			return NULL;
		}
		return t;
	}
}

static void resize(Terrain *t, id<MTLDevice> device, int width, int height) {
	if (t->width == width && t->height == height) return;
	@autoreleasepool {
		[t->hiz release];
		for (int i = 0; i < t->levels; i++) [t->hizLevel[i] release];
		// Level 0 padded to a multiple of 32 texels each way: hiz_build's threadgroups, and exact halving up to HIZ_TOP.
		int hw = ((width + HIZ_PX - 1) / HIZ_PX + 31) & ~31, hh = ((height + HIZ_PX - 1) / HIZ_PX + 31) & ~31;
		MTLTextureDescriptor *h = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatR32Float width:hw height:hh mipmapped:YES];
		h.storageMode = MTLStorageModePrivate;
		h.usage = MTLTextureUsageShaderRead | MTLTextureUsageShaderWrite | MTLTextureUsagePixelFormatView;
		t->hiz = [device newTextureWithDescriptor:h];
		t->levels = (int) MIN(t->hiz.mipmapLevelCount, MAX_LEVELS);
		for (int i = 0; i < t->levels; i++) {
			t->hizLevel[i] = [t->hiz newTextureViewWithPixelFormat:MTLPixelFormatR32Float textureType:MTLTextureType2D levels:NSMakeRange(i, 1) slices:NSMakeRange(0, 1)];
		}
		t->width = width;
		t->height = height;
	}
}

// A flush's list is built in steps, each reading what the one before wrote: CHUNK, CULL, SCAN, COMPACT for the quads
// terrain_cull newly lets through, LAST (terrain_last, then SCAN and COMPACT) for the quads visible last frame.
enum { CHUNK, CULL, SCAN, COMPACT, LAST };

// Records `step` of a flush's list of `chunkCount` chunks. offsets: byte offsets in `work` of the chunk masks, chunk counts,
// chunk offsets, survivors and draw arguments (see MetalTerrain.Work). arenaAddresses and arenas: 8 each of vertex buffers,
// bounds caches, last frame's visibility, this frame's, box caches (GPU addresses, objects), the first arenaCount of each
// used. frame: the MSL Frame struct; hizLevels is filled in here.
static void cull(id<MTLComputeCommandEncoder> c, Terrain *t, int step, id<MTLBuffer> chunks, uint64_t chunksOffset, int chunkCount, id<MTLBuffer> regions,
	uint64_t regionsOffset, const uint64_t *arenaAddresses, id<MTLBuffer> const *arenas, int arenaCount, const void *frame, id<MTLBuffer> work,
	const uint64_t *offsets) {
	uint8_t f[96];
	int levels = HIZ_TOP + 1;
	memcpy(f, frame, 96);
	memcpy(f + 80, &levels, 4);
	// terrain_last reads nothing of the tables but last frame's visibility. Declaring the rest would order it after this
	// frame's geometry uploads, which wait for last frame's draws to finish reading the arenas.
	if (step != LAST) {
		[c useResources:arenas count:arenaCount usage:MTLResourceUsageRead];
		[c useResources:arenas + 8 count:arenaCount usage:MTLResourceUsageRead | MTLResourceUsageWrite];
		[c useResources:arenas + 24 count:arenaCount usage:MTLResourceUsageRead | MTLResourceUsageWrite];
		[c useResources:arenas + 32 count:arenaCount usage:MTLResourceUsageRead | MTLResourceUsageWrite];
	}
	[c useResources:arenas + 16 count:arenaCount usage:MTLResourceUsageRead];
	[c setBytes:arenaAddresses length:40 * 8 atIndex:2];
	[c setBuffer:chunks offset:chunksOffset atIndex:0];
	[c setBuffer:regions offset:regionsOffset atIndex:1];
	[c setBytes:f length:96 atIndex:3];
	[c setBuffer:work offset:offsets[0] atIndex:4];
	[c setBuffer:work offset:offsets[1] atIndex:5];
	[c setBuffer:work offset:offsets[2] atIndex:6];
	[c setBuffer:work offset:offsets[4] atIndex:7];
	[c setBuffer:work offset:offsets[3] atIndex:8];
	[c setTexture:t->hiz atIndex:0];
	if (step == LAST || step == CHUNK) {
		[c setComputePipelineState:step == LAST ? t->last : t->chunk];
		[c dispatchThreads:MTLSizeMake(chunkCount, 1, 1) threadsPerThreadgroup:MTLSizeMake(64, 1, 1)];
	}
	if (step == CULL) {
		[c setComputePipelineState:t->cull];
		[c dispatchThreadgroups:MTLSizeMake(chunkCount, 1, 1) threadsPerThreadgroup:MTLSizeMake(64, 1, 1)];
	}
	if (step == LAST || step == SCAN) {
		[c setComputePipelineState:t->scan];
		[c dispatchThreadgroups:MTLSizeMake(1, 1, 1) threadsPerThreadgroup:MTLSizeMake(1024, 1, 1)];
	}
	if (step == LAST || step == COMPACT) {
		[c setComputePipelineState:t->compact];
		[c dispatchThreadgroups:MTLSizeMake(chunkCount, 1, 1) threadsPerThreadgroup:MTLSizeMake(64, 1, 1)];
	}
}

// Lists a flush's quads visible last frame, in the pre command buffer, for the frame to draw first.
void mc_occ_last(Enc *enc, Terrain *t, id<MTLBuffer> chunks, uint64_t chunksOffset, int chunkCount, id<MTLBuffer> regions, uint64_t regionsOffset,
	const uint64_t *arenaAddresses, id<MTLBuffer> const *arenas, int arenaCount, const void *frame, id<MTLBuffer> work, const uint64_t *offsets) {
	cull(mc_pre_compute(enc), t, LAST, chunks, chunksOffset, chunkCount, regions, regionsOffset, arenaAddresses, arenas, arenaCount, frame, work, offsets);
}

// In the frame, after the quads visible last frame were drawn: splits the open render pass and turns its depth into hi-Z.
// Then mc_occ_cull, step by step across the flushes, on the returned (concurrent) encoder; mc_render_resume reopens the pass.
id<MTLComputeCommandEncoder> mc_occ_suspend(Enc *enc, Terrain *t) {
	resize(t, enc->ctx->device, enc->width, enc->height);
	id<MTLComputeCommandEncoder> c = mc_render_suspend(enc);
	[c setComputePipelineState:t->hizBuild];
	[c setTexture:enc->depth atIndex:0];
	[c setTextures:t->hizLevel withRange:NSMakeRange(1, HIZ_TOP + 1)];
	[c dispatchThreads:MTLSizeMake(t->hizLevel[0].width, t->hizLevel[0].height, 1) threadsPerThreadgroup:MTLSizeMake(32, 32, 1)];
	return c;
}

// One step (CHUNK to COMPACT) of culling a flush's quads against mc_occ_suspend's hi-Z (marking what shows for next frame)
// and listing the ones still to draw in work (offsets as for mc_occ_last). Called for every flush at step 0, then every
// flush at step 1, and so on, with barrier set on each step's first call: its dispatches wait for everything before, and
// the flushes' dispatches within a step overlap (1080p 133 -> 121 us, 3456x2234 198 -> 186 us in an offscreen rig, same output).
void mc_occ_cull(id<MTLComputeCommandEncoder> c, Terrain *t, int step, int barrier, id<MTLBuffer> chunks, uint64_t chunksOffset, int chunkCount,
	id<MTLBuffer> regions, uint64_t regionsOffset, const uint64_t *arenaAddresses, id<MTLBuffer> const *arenas, int arenaCount, const void *frame,
	id<MTLBuffer> work, const uint64_t *offsets) {
	if (barrier) [c memoryBarrierWithScope:MTLBarrierScopeBuffers | MTLBarrierScopeTextures];
	cull(c, t, step, chunks, chunksOffset, chunkCount, regions, regionsOffset, arenaAddresses, arenas, arenaCount, frame, work, offsets);
}

// Lists the `count` clouds faces at faces that may be on screen into out (faces from keptOffset, the draw's arguments at 0),
// in an encoder of its own in the pre command buffer, so the frame's terrain doesn't wait for it. The matrices and CloudInfo
// are the uniform buffers of the clouds draw.
void mc_clouds_cull(Enc *enc, Terrain *t, id<MTLBuffer> faces, uint64_t facesOffset, int count, id<MTLBuffer> modelView, uint64_t modelViewOffset,
	id<MTLBuffer> proj, uint64_t projOffset, id<MTLBuffer> info, uint64_t infoOffset, id<MTLBuffer> out, uint64_t keptOffset) {
	mc_pre_end_encoders(enc);
	@autoreleasepool {
		id<MTLComputeCommandEncoder> c = [mc_pre(enc) computeCommandEncoder];
		c.label = @"mcopt clouds cull";
		[c setComputePipelineState:t->clouds];
		[c setBuffer:faces offset:facesOffset atIndex:0];
		[c setBytes:&count length:4 atIndex:1];
		[c setBuffer:modelView offset:modelViewOffset atIndex:2];
		[c setBuffer:proj offset:projOffset atIndex:3];
		[c setBuffer:info offset:infoOffset atIndex:4];
		[c setBuffer:out offset:keptOffset atIndex:5];
		[c setBuffer:out offset:0 atIndex:6];
		[c dispatchThreadgroups:MTLSizeMake(1, 1, 1) threadsPerThreadgroup:MTLSizeMake(1024, 1, 1)];
		[c endEncoding];
	}
}
