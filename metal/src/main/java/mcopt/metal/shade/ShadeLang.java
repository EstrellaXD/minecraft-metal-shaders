/*
 * Copyright 2026 mcopt-shade contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0. Unless required by applicable
 * law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
 * OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package mcopt.metal.shade;

import net.minecraft.client.Minecraft;

/**
 * (2026-10-11) The pause menu buttons' language: Spanish when the game's language is any Spanish (es_mx, es_es, ...), else
 * English. Chosen in code from Options.languageCode instead of lang files: a mod's assets need Fabric API's resource loader,
 * and the instance without Distant Horizons has no Fabric API.
 */
public final class ShadeLang {
	private ShadeLang() {
	}

	public static boolean spanish() {
		Minecraft mc = Minecraft.getInstance();
		return mc != null && mc.options != null && mc.options.languageCode != null && mc.options.languageCode.startsWith("es");
	}

	/** The text in the game's language. */
	public static String t(String spanish, String english) {
		return spanish() ? spanish : english;
	}
}
