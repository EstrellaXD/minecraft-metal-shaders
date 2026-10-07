/*
 * Copyright 2026 mcopt-shade contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package mcopt.metal;

import org.jspecify.annotations.Nullable;

/**
 * A seam for watching the quad draws of format-redirected passes (native shading's HDR world target): inert while no
 * listener is set, which nothing in upstream's path does. Native shading (M3) records entity draws here to cast their
 * shadows (mcopt.metal.shade.shadow.EntityCasters).
 */
public final class MetalDrawTap {
	/**
	 * One drawIndexed of a QUADS pipeline whose vertex format has a uv at location 2 and which samples "Sampler0", in a
	 * redirected pass. vertexBuffer: the MTLBuffer (valid for this frame), firstVertex: byte offset of the draw's first
	 * vertex, quads x 4 vertices of stride bytes (position float3 at 0, uv float2 at uvOffset), texture: Sampler0's MTLTexture.
	 */
	public interface Listener {
		void draw(String pipeline, long vertexBuffer, long firstVertex, int quads, int stride, int uvOffset, long texture);
	}

	public static volatile @Nullable Listener listener;

	private MetalDrawTap() {
	}
}
