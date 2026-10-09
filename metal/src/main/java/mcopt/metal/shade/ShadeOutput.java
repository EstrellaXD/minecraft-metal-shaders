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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The OUTPUT resolution of the native shading (2026-10-09, Gael: "la resolución como la del monitor, no la del reescalado"): the
 * size TAAU resolves the world into and the final pass tone-maps at, before one upscale pass to the window (ShadePost
 * shade_upscale). The render resolution (ShadeResolution: the world's own pixels, a share of this output) and the GUI, hand and
 * text (drawn after, on the window's target) are not affected. On a Retina screen "1080p" here costs the TAAU resolve and the
 * final pass about a quarter of their pixels; "Nativa" is the window's size (everything as before).
 *
 * <p>mcopt.shade.output = native | 1440 | 1200 | 1080 | 900 (an output height; the width follows the window's aspect), read
 * every frame, also cycled from the pause menu and saved to config/mcopt.properties. v1: native while Distant Horizons is
 * loaded (it draws its LODs at the window's size).
 */
public final class ShadeOutput {
	/** The choices in menu order: -1 = native (the window), else an output height in pixels. */
	private static final int[] CHOICES = {-1, 1440, 1200, 1080, 900};

	private static volatile int index = parse(System.getProperty("mcopt.shade.output", "native"));

	private ShadeOutput() {
	}

	private static int parse(String v) {
		String s = v.trim().toLowerCase(Locale.ROOT).replace("p", "");
		try {
			int h = Integer.parseInt(s);
			for (int i = 1; i < CHOICES.length; i++) if (CHOICES[i] == h) return i;
		} catch (NumberFormatException e) {
			// native
		}
		return 0;
	}

	/**
	 * Whether the choice can apply in this session: the HDR frame (M2: its final pass writes the output-size image the upscale
	 * reads; M1's TAAU writes the window's target directly) and no Distant Horizons (it draws its LODs at the window's size, v1).
	 */
	public static boolean available() {
		return ShadeConfig.HDR && !mcopt.metal.DhCompat.loaded();
	}

	/**
	 * The output size for a window of mainWidth x mainHeight: {width, height}, the window's own size for native, a choice at or
	 * above the window's height, or while unavailable. The width keeps the window's aspect (even, so the 2x2 quads of the
	 * upscale line up).
	 */
	public static int[] size(int mainWidth, int mainHeight) {
		// (read every frame: a bench phase may set the property directly)
		String live = System.getProperty("mcopt.shade.output");
		int c = CHOICES[live != null ? parse(live) : index];
		if (c < 0 || !available() || mainHeight <= 0 || c >= mainHeight) return new int[] {mainWidth, mainHeight};
		int w = Math.max(2, (int) (Math.round((double) mainWidth * c / mainHeight / 2.0) * 2));
		return new int[] {Math.min(w, mainWidth), c};
	}

	/** The pause menu's label (short: the button is 204 pixels wide). */
	public static String label() {
		int c = CHOICES[index];
		if (!available()) return "Salida: Nativa (DH)";
		return c < 0 ? "Salida: Nativa" : "Salida: " + c + "p";
	}

	/** The F3 line's part: "salida 1920x1080" while scaled, else "". */
	public static String debugPart(int outWidth, int outHeight, int mainWidth, int mainHeight) {
		if (outWidth <= 0 || (outWidth == mainWidth && outHeight == mainHeight)) return "";
		return String.format(Locale.ROOT, " → salida %dx%d", outWidth, outHeight);
	}

	/** Next choice (the pause-menu button), saved to config/mcopt.properties. */
	public static void cycle(Path gameDir) {
		index = (index + 1) % CHOICES.length;
		int c = CHOICES[index];
		String value = c < 0 ? "native" : Integer.toString(c);
		System.setProperty("mcopt.shade.output", value);
		save(gameDir.resolve("config").resolve("mcopt.properties"), value);
	}

	private static void save(Path file, String value) {
		try {
			List<String> lines = Files.exists(file) ? new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8)) : new ArrayList<>();
			boolean found = false;
			for (int i = 0; i < lines.size(); i++) {
				if (lines.get(i).trim().startsWith("mcopt.shade.output=")) {
					lines.set(i, "mcopt.shade.output=" + value);
					found = true;
				}
			}
			if (!found) {
				lines.add("# Resolución de salida (también desde el menú de pausa): native, 1440, 1200, 1080, 900");
				lines.add("mcopt.shade.output=" + value);
			}
			Files.createDirectories(file.getParent());
			Files.write(file, lines, StandardCharsets.UTF_8);
		} catch (IOException e) {
			System.out.println("[mcopt] shade: could not save the output resolution choice: " + e);
		}
	}
}
