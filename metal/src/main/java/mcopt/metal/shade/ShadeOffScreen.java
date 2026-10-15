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

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

/**
 * (1.2.1) The pause menu's "Shaders..." with the shaders off (mcopt.shade=false): only the switch to turn them back on (at the
 * next start) and the support button. Loads none of the shading's classes, so mcopt runs exactly as upstream otherwise.
 */
public final class ShadeOffScreen extends OptionsSubScreen {
	public ShadeOffScreen(Screen lastScreen) {
		super(lastScreen, Minecraft.getInstance().options, Component.literal("Metal Shaders"));
	}

	@Override
	protected void init() {
		super.init();
		this.addRenderableWidget(ShadeToggle.supportButton(this));
	}

	@Override
	protected void addOptions() {
		if (this.list == null) return;
		this.list.addHeader(Component.literal(ShadeLang.t("Los shaders están apagados: el juego usa mcopt 0.3 tal cual.",
			"The shaders are off: the game runs plain mcopt 0.3.")));
		this.list.addBig(ShadeToggle.button(this.minecraft.gameDirectory.toPath()));
	}
}
