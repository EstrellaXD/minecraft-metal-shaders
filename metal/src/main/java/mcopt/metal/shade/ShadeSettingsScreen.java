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

import com.mojang.serialization.Codec;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * (1.1) The shader settings screen, from the pause menu's "Shaders..." button: the look, light and colour, sky and water
 * settings of ShadeSettings, laid out like the game's own Video Settings (two columns, sliders and cycling buttons). Changes show
 * on the next frame behind the screen (no blur over the world here, so a slider's effect can be judged while dragging it); the
 * quality preset applies at the next start. Closing the screen writes the changed settings to config/mcopt.properties.
 */
public final class ShadeSettingsScreen extends OptionsSubScreen {
	public ShadeSettingsScreen(Screen lastScreen) {
		super(lastScreen, Minecraft.getInstance().options, Component.literal(ShadeLang.t("Ajustes de shaders", "Shader settings")));
	}

	private static String t(String es, String en) {
		return ShadeLang.t(es, en);
	}

	private static Component header(String es, String en) {
		return Component.literal(t(es, en)).withStyle(ChatFormatting.UNDERLINE, ChatFormatting.BOLD);
	}

	/** Captions are translation keys: plain text with no '%' shows as itself. */
	private static String caption(String es, String en) {
		return t(es, en).replace("%", "");
	}

	/** A cycling button over names (shown through label), the index stored by set. */
	private static OptionInstance<Integer> choice(String es, String en, String tipEs, String tipEn, int count, int current, IntFunction<String> label,
		IntConsumer set) {
		List<Integer> values = new java.util.ArrayList<>();
		for (int i = 0; i < count; i++) values.add(i);
		return new OptionInstance<>(caption(es, en), tipEs == null ? OptionInstance.noTooltip() : OptionInstance.cachedConstantTooltip(Component.literal(t(tipEs, tipEn))),
			(cap, v) -> Component.literal(label.apply(v)), new OptionInstance.Enum<>(values, Codec.INT), current, v -> set.accept(v)); // (the button adds "caption: ")
	}

	/** A slider over min..max (applied while dragging), shown through label. */
	private static OptionInstance<Integer> slider(String es, String en, String tipEs, String tipEn, int min, int max, int current, IntFunction<String> label,
		IntConsumer set) {
		return new OptionInstance<>(caption(es, en), tipEs == null ? OptionInstance.noTooltip() : OptionInstance.cachedConstantTooltip(Component.literal(t(tipEs, tipEn))),
			(cap, v) -> Options.genericValueLabel(cap, Component.literal(label.apply(v))), new OptionInstance.IntRange(min, max), Math.max(min, Math.min(max, current)),
			v -> set.accept(v));
	}

	private static String pct(int v) {
		return v + "%";
	}

	@Override
	protected void addOptions() {
		if (this.list == null) return;
		String[] presetNames = {t("Rendimiento", "Performance"), t("Balanceado", "Balanced"), t("Calidad", "Quality"), "Ultra"};
		String[] lookNames = {t("Épico", "Epic"), t("Cuento", "Storybook"), "Natural"};
		this.list.addHeader(header("Calidad", "Quality"));
		this.list.addSmall(
			choice("Preset", "Preset", "Cuánto trabaja la GPU: sombras, nubes, reflejos y resolución interna. Se aplica al reiniciar el juego.",
				"How hard the GPU works: shadows, clouds, reflections and internal resolution. Applies after restarting the game.",
				4, ShadeSettings.presetChoice(), v -> presetNames[v] + (v != Shade.TIER ? t(" (al reiniciar)", " (on restart)") : ""), ShadeSettings::setPreset),
			choice("Estilo", "Style", "Épico: colores intensos. Cuento: más suave y cálido. Natural: más realista.",
				"Epic: rich colours. Storybook: softer and warmer. Natural: more realistic.", 3, ShadeSettings.lookIndex(), v -> lookNames[v], ShadeSettings::setLook));

		this.list.addHeader(header("Luz y color", "Light and colour"));
		this.list.addSmall(
			slider("Brillo", "Brightness", "Exposición de la imagen (0 = como viene).", "Exposure of the image (0 = default).", -20, 20, ShadeSettings.evTenths(),
				v -> (v > 0 ? "+" : "") + String.format(java.util.Locale.ROOT, "%.1f", v / 10f), ShadeSettings::setEvTenths),
			slider("Saturación", "Saturation", "Intensidad de los colores.", "How colourful the image is.", 0, 150, ShadeSettings.saturationPercent(),
				ShadeSettingsScreen::pct, ShadeSettings::setSaturation),
			slider("Resplandor", "Bloom", "El brillo que se esparce alrededor del sol, las antorchas y la lava.", "The glow around the sun, torches and lava.", 0, 200,
				ShadeSettings.bloomPercent(), ShadeSettingsScreen::pct, ShadeSettings::setBloom),
			slider("Rayos de sol", "God rays", "Los rayos de luz entre los árboles y las nubes.", "The shafts of light through trees and clouds.", 0, 200,
				ShadeSettings.godraysPercent(), ShadeSettingsScreen::pct, ShadeSettings::setGodrays),
			slider("Neblina", "Haze", "La neblina del aire y del suelo (no la niebla del límite de chunks).",
				"The haze in the air and the mist near the ground (not the fog at the render distance).", 0, 200, ShadeSettings.hazePercent(),
				ShadeSettingsScreen::pct, ShadeSettings::setHaze));

		String[] cloudNames = {t("Volumétricas", "Volumetric"), t("Cuadradas", "Blocky"), "Minecraft", t("No", "Off")};
		String[] auroraNames = {t("Nunca", "Never"), t("Pocas", "Rare"), "Normal", t("Siempre", "Always")};
		this.list.addHeader(header("Cielo", "Sky"));
		this.list.addSmall(
			choice("Nubes", "Clouds",
				"Volumétricas: nubes realistas. Cuadradas: los bloques de las nubes de Minecraft, con volumen y luz. Minecraft: las nubes normales del juego.",
				"Volumetric: realistic clouds. Blocky: Minecraft's cloud blocks, with volume and lighting. Minecraft: the game's normal clouds.",
				4, ShadeSettings.cloudsIndex(), v -> cloudNames[v], ShadeSettings::setClouds),
			choice("Auroras", "Auroras", "Cada cuánto salen auroras en las noches despejadas (más en biomas fríos).",
				"How often auroras show on clear nights (more in cold biomes).", 4, ShadeSettings.auroraIndex(), v -> auroraNames[v], ShadeSettings::setAurora));

		String[] waterNames = {t("Natural", "Natural"), t("Azul", "Blue"), t("Turquesa", "Turquoise"), t("Verde", "Green"), t("Cristalina", "Clear")};
		this.list.addHeader(header("Agua", "Water"));
		this.list.addSmall(
			choice("Color del agua", "Water colour", "Natural: el color de cada bioma, como en Minecraft. Los demás: el mismo color en todo el mundo.",
				"Natural: each biome's colour, as in Minecraft. The others: one colour everywhere.", 5, ShadeSettings.waterColorIndex(), v -> waterNames[v],
				ShadeSettings::setWaterColor),
			slider("Transparencia", "Clarity", "Qué tan lejos se ve dentro del agua.", "How far you can see into the water.", 25, 300, ShadeSettings.clarityPercent(),
				ShadeSettingsScreen::pct, ShadeSettings::setClarity),
			slider("Olas", "Waves", "La altura de las olas.", "How high the waves are.", 0, 200, ShadeSettings.wavesPercent(), ShadeSettingsScreen::pct,
				ShadeSettings::setWaves),
			slider("Reflejos", "Reflections", "Qué tanto refleja el agua.", "How strongly the water reflects.", 0, 200, ShadeSettings.reflectPercent(),
				ShadeSettingsScreen::pct, ShadeSettings::setReflect),
			choice("Reflejo del paisaje", "Scene reflections",
				"Sí: el agua refleja montañas, árboles y nubes que están en pantalla. Solo cielo: más FPS frente a lagos y mares.",
				"On: the water mirrors hills, trees and clouds on screen. Sky only: more FPS around lakes and seas.", 2, ShadeSettings.ssr() ? 0 : 1,
				v -> v == 0 ? t("Sí", "On") : t("Solo cielo", "Sky only"), v -> ShadeSettings.setSsr(v == 0)));
	}

	@Override
	protected void addFooter() {
		LinearLayout footer = this.layout.addToFooter(LinearLayout.horizontal().spacing(8));
		footer.addChild(Button.builder(Component.literal(t("Restaurar", "Reset")), button -> {
			ShadeSettings.reset();
			this.minecraft.gui.setScreen(new ShadeSettingsScreen(this.lastScreen));
		}).tooltip(Tooltip.create(Component.literal(t("Todo como viene (el preset no cambia).", "Everything back to default (the preset stays).")))).build());
		footer.addChild(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose()).build());
	}

	/** No blur: the world behind shows each change as it is made. */
	@Override
	protected void extractBlurredBackground(GuiGraphicsExtractor graphics) {
	}

	@Override
	public void removed() {
		super.removed();
		ShadeSettings.save(this.minecraft.gameDirectory.toPath());
	}
}
