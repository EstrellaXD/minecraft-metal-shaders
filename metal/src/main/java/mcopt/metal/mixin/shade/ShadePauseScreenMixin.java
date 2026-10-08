/*
 * Copyright 2026 mcopt-shade contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0. Unless required by applicable
 * law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
 * OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package mcopt.metal.mixin.shade;

import mcopt.metal.shade.ShadeFrameGenOption;
import mcopt.metal.shade.ShadeResolution;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * M3.1: a "Resolución del mundo" button at the bottom of the pause menu (with a world loaded): each click cycles the native
 * shading's render resolution (ShadeResolution: auto, native, 1800p ... 1080p), applied on the next frame and kept in
 * config/mcopt.properties. Only with the native shading's TAAU (it owns the render scale). M3.3: beside it "Gen. de cuadros"
 * (ShadeFrameGenOption: frame generation on/off from the next start).
 */
@Mixin(PauseScreen.class)
abstract class ShadePauseScreenMixin extends Screen {
	protected ShadePauseScreenMixin(Component title) {
		super(title);
	}

	@Inject(method = "init", at = @At("TAIL"))
	private void mcopt$resolutionButton(CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return;
		// M3.3: frame generation's button next to it (mcopt.shade.framegenButton, default true; false = M3.2's single button)
		boolean frameGen = !"false".equals(System.getProperty("mcopt.shade.framegenButton"));
		boolean resolution = mcopt.metal.shade.taa.ShadeTaa.ON;
		if (resolution) {
			int w = mc.getWindow().getWidth(), h = mc.getWindow().getHeight();
			this.addRenderableWidget(Button.builder(Component.literal(ShadeResolution.label(w, h)), button -> {
				ShadeResolution.cycle(mc.gameDirectory.toPath());
				button.setMessage(Component.literal(ShadeResolution.label(mc.getWindow().getWidth(), mc.getWindow().getHeight())));
			}).bounds(frameGen ? this.width / 2 - 206 : this.width / 2 - 102, this.height - 28, 204, 20).build());
		}
		if (frameGen) {
			this.addRenderableWidget(Button.builder(Component.literal(ShadeFrameGenOption.label()), button -> {
				ShadeFrameGenOption.toggle(mc.gameDirectory.toPath());
				button.setMessage(Component.literal(ShadeFrameGenOption.label()));
			}).tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(ShadeFrameGenOption.tooltip())))
				.bounds(resolution ? this.width / 2 + 2 : this.width / 2 - 102, this.height - 28, 204, 20).build());
		}
	}
}
