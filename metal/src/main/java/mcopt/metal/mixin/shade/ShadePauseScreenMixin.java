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
import mcopt.metal.shade.ShadeOutput;
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
 * (ShadeFrameGenOption: frame generation on/off from the next start), hidden unless -Dmcopt.shade.framegenButton=true.
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
		// M3.3: frame generation's button next to it, only with mcopt.shade.framegenButton=true (default false = M3.2's single
		// button): with the native shading, MetalFX frame interpolation costs ~12 ms of GPU per real frame at 3024x1964, so the
		// game drops to 30 real + 30 generated fps with ~53 ms of latency instead of ~90 real fps (M33_ROBUST.md section 3)
		boolean frameGen = Boolean.getBoolean("mcopt.shade.framegenButton");
		// M3.3 robustness: not when TAAU failed to set up (no render scale to choose; the button did nothing, run rb4);
		// mcopt.shade.robustFallback=false: M3.2 (shown anyway)
		boolean resolution = mcopt.metal.shade.taa.ShadeTaa.ON
			&& !(mcopt.metal.shade.taa.ShadeTaa.failed() && !"false".equals(System.getProperty("mcopt.shade.robustFallback")));
		// (2026-10-09) the output resolution's button to its right (ShadeOutput: the whole window at that resolution; with
		// mcopt.shade.outputMode=taau only the shaded frame, then not with Distant Horizons: "Nativa (DH)", does nothing)
		boolean output = resolution;
		if (resolution) {
			int w = mc.getWindow().getWidth(), h = mc.getWindow().getHeight();
			this.addRenderableWidget(Button.builder(Component.literal(ShadeResolution.label(w, h)), button -> {
				ShadeResolution.cycle(mc.gameDirectory.toPath());
				button.setMessage(Component.literal(ShadeResolution.label(mc.getWindow().getWidth(), mc.getWindow().getHeight())));
			}).bounds(output ? this.width / 2 - 206 : this.width / 2 - 102, this.height - 28, 204, 20).build());
		}
		if (output) {
			this.addRenderableWidget(Button.builder(Component.literal(ShadeOutput.label()), button -> {
				if (ShadeOutput.available()) ShadeOutput.cycle(mc.gameDirectory.toPath());
				button.setMessage(Component.literal(ShadeOutput.label()));
			}).tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(ShadeOutput.tooltip())))
				.bounds(this.width / 2 + 2, this.height - 28, 204, 20).build());
		}
		if (frameGen) {
			this.addRenderableWidget(Button.builder(Component.literal(ShadeFrameGenOption.label()), button -> {
				ShadeFrameGenOption.toggle(mc.gameDirectory.toPath());
				button.setMessage(Component.literal(ShadeFrameGenOption.label()));
			}).tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(ShadeFrameGenOption.tooltip())))
				.bounds(this.width / 2 - 102, resolution ? this.height - 52 : this.height - 28, 204, 20).build());
		}
	}
}
