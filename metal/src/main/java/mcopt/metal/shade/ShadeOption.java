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

import java.util.Locale;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;
import java.util.function.ToIntFunction;

/**
 * (1.1) One setting of the shader settings screen: a system property (the module reads it live through Live, or at the next
 * start for a restart option), shown as a slider over integer positions, a cycling choice or an on/off toggle, with its texts
 * in Spanish and English (ShadeLang picks by the game's language). A position maps to the property's text and back; the
 * default position may depend on the running preset (Shade.TIER). Setting the default writes the default's text to the
 * property (so a module that read a different value at startup follows) and removes the key from config/mcopt.properties.
 */
public final class ShadeOption {
	public enum Kind {
		SLIDER, CHOICE, TOGGLE
	}

	/** A unit shown in the game's language: " bloques" / " blocks". */
	static final String BLOCKS = "\u0000blocks";

	public final String key;
	public final Kind kind;
	final String es, en, tipEs, tipEn;
	final int min, max;
	private final IntSupplier def;
	private final IntFunction<String> toProp, display;
	private final ToIntFunction<String> fromProp;
	/** CHOICE: the names shown, by position. */
	final String[] namesEs, namesEn;
	boolean restart;

	private ShadeOption(String key, Kind kind, String es, String en, String tipEs, String tipEn, int min, int max, IntSupplier def,
		IntFunction<String> toProp, ToIntFunction<String> fromProp, IntFunction<String> display, String[] namesEs, String[] namesEn) {
		this.key = key;
		this.kind = kind;
		this.es = es;
		this.en = en;
		this.tipEs = tipEs;
		this.tipEn = tipEn;
		this.min = min;
		this.max = max;
		this.def = def;
		this.toProp = toProp;
		this.fromProp = fromProp;
		this.display = display;
		this.namesEs = namesEs;
		this.namesEn = namesEn;
	}

	// ---- factories -----------------------------------------------------------------------------------------------------
	/** A multiplier stored as a float (1 = as released), shown in percent over minPct..maxPct. */
	static ShadeOption pct(String key, String es, String en, String tipEs, String tipEn, int minPct, int maxPct, int defPct) {
		return pctOf(key, es, en, tipEs, tipEn, minPct, maxPct, () -> defPct, 1f);
	}

	/** A float stored as base x percent / 100 (e.g. vl.airForest 0.017 at 100 %), shown in percent. */
	static ShadeOption pctOf(String key, String es, String en, String tipEs, String tipEn, int minPct, int maxPct, IntSupplier defPct, float base) {
		return new ShadeOption(key, Kind.SLIDER, es, en, tipEs, tipEn, minPct, maxPct, defPct, p -> fmt(p * base / 100f),
			v -> roundOr(parse(v, Float.NaN) * 100f / base), p -> p + "%", null, null);
	}

	/** A value stored as percent itself (mcopt.shade.user.saturation=120), shown in percent. */
	static ShadeOption pctInt(String key, String es, String en, String tipEs, String tipEn, int minPct, int maxPct, int defPct) {
		return new ShadeOption(key, Kind.SLIDER, es, en, tipEs, tipEn, minPct, maxPct, () -> defPct, Integer::toString,
			v -> roundOr(parse(v, Float.NaN)), p -> p + "%", null, null);
	}

	/** A number stored as position / div, shown with unit (decimals from div). */
	static ShadeOption num(String key, String es, String en, String tipEs, String tipEn, int min, int max, IntSupplier def, int div, String unit) {
		return new ShadeOption(key, Kind.SLIDER, es, en, tipEs, tipEn, min, max, def, p -> fmt((float) p / div),
			v -> roundOr(parse(v, Float.NaN) * div),
			p -> (div == 1 ? Integer.toString(p) : String.format(Locale.ROOT, div == 10 ? "%.1f" : "%.2f", (float) p / div))
				+ (BLOCKS.equals(unit) ? ShadeLang.t(" bloques", " blocks") : unit),
			null, null);
	}

	/** A slider with its own mapping (position 0 often meaning "automatic"). */
	static ShadeOption custom(String key, String es, String en, String tipEs, String tipEn, int min, int max, IntSupplier def, IntFunction<String> toProp,
		ToIntFunction<String> fromProp, IntFunction<String> display) {
		return new ShadeOption(key, Kind.SLIDER, es, en, tipEs, tipEn, min, max, def, toProp, fromProp, display, null, null);
	}

	/** A choice between property values (lower-case), shown by name. */
	static ShadeOption choice(String key, String es, String en, String tipEs, String tipEn, String[] values, String[] namesEs, String[] namesEn, IntSupplier def) {
		return new ShadeOption(key, Kind.CHOICE, es, en, tipEs, tipEn, 0, values.length - 1, def, p -> values[p], v -> {
			String s = v.trim().toLowerCase(Locale.ROOT);
			for (int i = 0; i < values.length; i++) if (values[i].equals(s)) return i;
			return Integer.MIN_VALUE;
		}, p -> null, namesEs, namesEn);
	}

	/** On / off (true / false); inverted: the property is the opposite of what is shown (e.g. a "pause" key). */
	static ShadeOption toggle(String key, String es, String en, String tipEs, String tipEn, boolean def, boolean inverted) {
		return new ShadeOption(key, Kind.TOGGLE, es, en, tipEs, tipEn, 0, 1, () -> def ? 1 : 0, p -> Boolean.toString((p == 1) != inverted), v -> {
			String s = v.trim();
			if (!s.equalsIgnoreCase("true") && !s.equalsIgnoreCase("false")) return Integer.MIN_VALUE;
			return s.equalsIgnoreCase("true") != inverted ? 1 : 0;
		}, p -> null, null, null);
	}

	static ShadeOption toggle(String key, String es, String en, String tipEs, String tipEn, boolean def) {
		return toggle(key, es, en, tipEs, tipEn, def, false);
	}

	/** Applies at the next start (the label says so while the choice differs from what runs). */
	ShadeOption restart() {
		this.restart = true;
		return this;
	}

	/** A preset-dependent default: values by tier (performance, balanced, quality, ultra). */
	static IntSupplier tier(int p, int b, int q, int u) {
		int[] v = {p, b, q, u};
		return () -> v[Shade.TIER];
	}

	// ---- values --------------------------------------------------------------------------------------------------------
	public int defaultPos() {
		return clamp(this.def.getAsInt());
	}

	/** The position the property holds now (its default when unset or unreadable). */
	public int pos() {
		String v = System.getProperty(this.key);
		if (v == null) return defaultPos();
		int p;
		try {
			p = this.fromProp.applyAsInt(v);
		} catch (RuntimeException e) {
			p = Integer.MIN_VALUE;
		}
		return p == Integer.MIN_VALUE ? defaultPos() : clamp(p);
	}

	/** Sets the property (now; restart options take effect at the next start) and records it for config/mcopt.properties. */
	public void set(int pos) {
		int p = clamp(pos);
		String text = this.toProp.apply(p);
		System.setProperty(this.key, text);
		ShadeSettings.changed(this.key, p == defaultPos() ? null : text);
	}

	public void reset() {
		set(defaultPos());
	}

	/** The value's text as shown (slider: number and unit; choice: its name; toggle: on / off). */
	public String valueText(int p) {
		return switch (this.kind) {
			case SLIDER -> this.display.apply(p);
			case CHOICE -> ShadeLang.t(this.namesEs[p], this.namesEn[p]);
			case TOGGLE -> p == 1 ? ShadeLang.t("Sí", "On") : ShadeLang.t("No", "Off");
		};
	}

	/** The caption: a restart option whose choice differs from the default it started with gets a mark. */
	public String caption() {
		return ShadeLang.t(this.es, this.en) + (this.restart ? " *" : "");
	}

	public String tooltip() {
		String t = this.tipEs == null ? null : ShadeLang.t(this.tipEs, this.tipEn);
		if (!this.restart) return t;
		String r = ShadeLang.t("* Se aplica al reiniciar el juego.", "* Applies after restarting the game.");
		return t == null ? r : t + "\n" + r;
	}

	private int clamp(int p) {
		return Math.max(this.min, Math.min(this.max, p));
	}

	/** A parsed value's position, MIN_VALUE (unreadable: the default) for NaN. */
	static int roundOr(float v) {
		return Float.isNaN(v) ? Integer.MIN_VALUE : Math.round(v);
	}

	static float parse(String v, float fallback) {
		try {
			return Float.parseFloat(v.trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	/** Shortest text for a float (1 -> "1", 0.25 -> "0.25"). */
	static String fmt(float v) {
		if (v == Math.rint(v) && Math.abs(v) < 1e7f) return Integer.toString((int) v);
		String s = String.format(Locale.ROOT, "%.4f", v);
		s = s.replaceAll("0+$", "");
		return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
	}
}
