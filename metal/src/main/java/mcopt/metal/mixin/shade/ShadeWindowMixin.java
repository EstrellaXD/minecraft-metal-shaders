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
package mcopt.metal.mixin.shade;

import com.mojang.blaze3d.platform.Window;
import mcopt.metal.shade.ShadeOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The output resolution (2026-10-09, Gael: "que todo se renderice a la resolución que se cambia", as on his 1080p monitor): the
 * window reports the chosen resolution as its framebuffer size (ShadeOutput.framebuffer), so the game's main target, the GUI
 * and the Metal surface's drawable all have that size, and the compositor stretches the layer over the window. Both ways the
 * size reaches the game: the query (startup, fullscreen changes, the surface's configuration) and the resize event.
 */
@Mixin(Window.class)
abstract class ShadeWindowMixin {
	@Inject(method = "queryFramebufferSize", at = @At("RETURN"), cancellable = true)
	private void mcopt$outputSize(CallbackInfoReturnable<Window.FramebufferSize> cir) {
		Window.FramebufferSize s = cir.getReturnValue();
		int[] o = ShadeOutput.framebuffer(s.width(), s.height());
		if (o[0] != s.width() || o[1] != s.height()) cir.setReturnValue(new Window.FramebufferSize(o[0], o[1]));
	}

	@Inject(method = "onFramebufferResize", at = @At("HEAD"), cancellable = true)
	private void mcopt$outputResize(int newWidth, int newHeight, CallbackInfo ci) {
		int[] o = ShadeOutput.framebuffer(newWidth, newHeight);
		if (o[0] == newWidth && o[1] == newHeight) return;
		// (the scaled size scales to itself, so this call runs the original once with it)
		ci.cancel();
		((ShadeWindowAccessor) (Object) this).mcopt$onFramebufferResize(o[0], o[1]);
	}
}
