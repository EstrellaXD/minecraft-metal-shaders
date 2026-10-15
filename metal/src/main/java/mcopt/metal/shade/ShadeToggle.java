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

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * (1.2.1) Two small pieces of the settings screens. The shaders' on/off switch: it writes mcopt.shade to config/mcopt.properties
 * and applies at the next start (off = mcopt's own Metal renderer, as upstream; the pause menu keeps its "Shaders..." button so
 * they can be turned back on, ShadeOffScreen). And the support button: a small "☕" button in the top-right corner of the main
 * settings page (as Sodium's), which asks before opening the Ko-fi page in the browser. Reads only Shade.ENABLED, so both work
 * with the shaders off.
 */
public final class ShadeToggle {
	public static final String KOFI = "https://ko-fi.com/gaelquintanilla";
	/** What the config file holds after a click this session; null: untouched (the running state). */
	private static Boolean pending;

	private ShadeToggle() {
	}

	/** On at the next start. */
	public static boolean target() {
		return pending != null ? pending : Shade.ENABLED;
	}

	public static Component label() {
		String state = target() ? ShadeLang.t("Sí", "On") : ShadeLang.t("No", "Off");
		String later = target() != Shade.ENABLED ? ShadeLang.t(" (al reiniciar)", " (after restart)") : "";
		return Component.literal("Shaders: " + state + later);
	}

	public static Tooltip tooltip() {
		return Tooltip.create(Component.literal(ShadeLang.t(
			"No: el juego usa mcopt 0.3 tal cual (Metal sin efectos, más FPS). Sí: los shaders. Se aplica al reiniciar el juego; tus ajustes se conservan.",
			"Off: the game runs plain mcopt 0.3 (Metal without effects, more FPS). On: the shaders. Applies after restarting the game; your settings are kept.")));
	}

	/** The switch's button (full width of a list row is the caller's choice). */
	public static Button button(Path gameDir) {
		return Button.builder(label(), b -> {
			set(gameDir, !target());
			b.setMessage(label());
		}).tooltip(tooltip()).build();
	}

	/** Writes mcopt.shade=on to config/mcopt.properties (its line replaced, or added at the end). */
	static synchronized void set(Path gameDir, boolean on) {
		Path file = gameDir.resolve("config").resolve("mcopt.properties");
		try {
			List<String> lines = Files.exists(file) ? new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8)) : new ArrayList<>();
			boolean found = false;
			for (int i = 0; i < lines.size(); i++) {
				String t = lines.get(i).trim();
				if (t.matches("mcopt\\.shade\\s*[=:].*")) {
					lines.set(i, "mcopt.shade=" + on);
					found = true;
				}
			}
			if (!found) lines.add("mcopt.shade=" + on);
			Files.createDirectories(file.getParent());
			Files.write(file, lines, StandardCharsets.UTF_8);
			pending = on;
			System.out.println("[mcopt] shade: mcopt.shade=" + on + " written to " + file + " (applies at the next start)");
		} catch (IOException e) {
			System.out.println("[mcopt] shade: could not write mcopt.shade: " + e);
		}
	}

	/** The small support button for a screen's top-right corner. */
	public static Button supportButton(Screen screen) {
		String text = ShadeLang.t("☕ Apoyar", "☕ Support");
		int w = screen.getFont().width(text) + 12;
		return Button.builder(Component.literal(text), ConfirmLinkScreen.confirmLink(screen, URI.create(KOFI)))
			.tooltip(Tooltip.create(Component.literal(ShadeLang.t(
				"Metal Shaders es gratis. Si te gusta, puedes invitarme un café en Ko-fi.",
				"Metal Shaders is free. If you enjoy it, you can buy me a coffee on Ko-fi."))))
			.bounds(screen.width - w - 6, 6, w, 20).build();
	}
}
