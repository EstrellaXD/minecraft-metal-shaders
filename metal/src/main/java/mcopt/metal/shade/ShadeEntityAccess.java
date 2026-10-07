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
package mcopt.metal.shade;

import java.util.Set;
import org.jspecify.annotations.Nullable;

/** Validation tools' access to ShadeEntity's patcher (tools/shade/ShadeMslDump), independent of the runtime flags. */
public final class ShadeEntityAccess {
	private ShadeEntityAccess() {
	}

	public static Set<String> pipelines() {
		return ShadeEntity.PIPELINES.keySet();
	}

	public static @Nullable String vertex(String name, String msl) {
		return ShadeEntity.vertexPatch(name, msl);
	}

	public static @Nullable String fragment(String name, String msl) {
		return ShadeEntity.fragmentPatch(name, msl);
	}
}
