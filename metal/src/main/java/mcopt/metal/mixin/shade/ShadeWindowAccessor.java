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
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** (2026-10-09) The window's own framebuffer refresh, for the output resolution's runtime changes (ShadeOutput.apply). */
@Mixin(Window.class)
public interface ShadeWindowAccessor {
	@Invoker("refreshFramebufferSize")
	void mcopt$refreshFramebufferSize();

	@Invoker("onFramebufferResize")
	void mcopt$onFramebufferResize(int newWidth, int newHeight);
}
