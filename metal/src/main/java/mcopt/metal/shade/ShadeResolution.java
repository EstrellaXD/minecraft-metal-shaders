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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * M3.1: the world's render resolution as the player picks it in the pause menu (ShadePauseScreenMixin), live: the native
 * shading's TAAU (ShadeTaa) renders the world at that height and rebuilds the full-resolution frame; the GUI and the hand stay
 * at the screen's resolution. "Auto" is the preset's own behaviour (dynamic resolution in its range). The choice is kept in
 * config/mcopt.properties as {@code mcopt.shade.resolution} (auto, native, or a height such as 1440), read at startup like
 * every mcopt key.
 */
public final class ShadeResolution {
	/** The choices in menu order: 0 = auto (the preset), -1 = native, else a render height in pixels. */
	private static final int[] CHOICES = {0, -1, 1800, 1600, 1440, 1200, 1080};
	/** The lowest scale a choice may ask for (TAAU still rebuilds a sharp frame from about half the pixels per axis). */
	public static final float MIN_SCALE = 0.5f;
	private static volatile int index = parse(System.getProperty("mcopt.shade.resolution", "auto"));

	private ShadeResolution() {
	}

	private static int parse(String v) {
		String s = v.trim().toLowerCase(Locale.ROOT).replace("p", "");
		if (s.equals("native") || s.equals("nativa")) return 1;
		try {
			int h = Integer.parseInt(s);
			for (int i = 2; i < CHOICES.length; i++) if (CHOICES[i] == h) return i;
		} catch (NumberFormatException e) {
			// auto
		}
		return 0;
	}

	/** The render scale this choice asks for at an output height, or 0 for auto (the preset decides). */
	public static float scale(int outHeight) {
		int c = CHOICES[index];
		if (c == 0 || outHeight <= 0) return 0f;
		if (c < 0) return 1f;
		return Math.clamp((float) c / outHeight, MIN_SCALE, 1f);
	}

	/** Whether the player fixed a resolution (not auto). */
	public static boolean fixed() {
		return CHOICES[index] != 0;
	}

	/** The menu label for the current choice at this output size. */
	public static String label(int outWidth, int outHeight) {
		int c = CHOICES[index];
		if (c == 0) return "Resolución del mundo: Auto";
		float s = scale(outHeight);
		int w = Math.round(outWidth * s), h = Math.round(outHeight * s);
		if (c < 0) return "Resolución del mundo: Nativa (" + w + "×" + h + ")";
		return "Resolución del mundo: " + c + "p (" + w + "×" + h + ")";
	}

	/** Next choice (the pause-menu button), saved to config/mcopt.properties. */
	public static void cycle(Path gameDir) {
		index = (index + 1) % CHOICES.length;
		int c = CHOICES[index];
		String value = c == 0 ? "auto" : c < 0 ? "native" : Integer.toString(c);
		System.setProperty("mcopt.shade.resolution", value);
		save(gameDir.resolve("config").resolve("mcopt.properties"), value);
	}

	private static void save(Path file, String value) {
		try {
			List<String> lines = Files.exists(file) ? new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8)) : new ArrayList<>();
			boolean found = false;
			for (int i = 0; i < lines.size(); i++) {
				if (lines.get(i).trim().startsWith("mcopt.shade.resolution=")) {
					lines.set(i, "mcopt.shade.resolution=" + value);
					found = true;
				}
			}
			if (!found) {
				lines.add("# Resolución del mundo (también desde el menú de pausa): auto, native, 1800, 1600, 1440, 1200, 1080");
				lines.add("mcopt.shade.resolution=" + value);
			}
			Files.createDirectories(file.getParent());
			Files.write(file, lines, StandardCharsets.UTF_8);
		} catch (IOException e) {
			System.out.println("[mcopt] shade: could not save the resolution choice: " + e);
		}
	}
}
