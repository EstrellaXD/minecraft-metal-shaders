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

import java.nio.ByteBuffer;
import mcopt.metal.MetalBridge;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * The global resources every render encoder gets (shade_common.metal's header lists the slots): the ShadeFrame ring,
 * the shadow map (a 1x1x2 dummy until the shadow pass sets one) with its comparison sampler, and the sky-view LUT, which
 * is rendered here once per frame. bind() is called by the backend wherever a new encoder starts: after
 * Native.renderBegin opened one (MetalEncoder.createRenderPass), after MetalTerrain's split reopened it
 * (MetalRenderPass.rebind) and after far terrain drew (MetalRenderPass.reapplyPipeline). Render thread only.
 */
public final class ShadeBindings {
	/** The backend's gate for the bind hooks: a static final, so with shading off the hooks are dead code. */
	public static final boolean ON = Shade.ENABLED;
	/** Ring slots: more than the frames that can be in flight (-Dmcopt.metal.inFlight, 2 by default) plus the one being written. */
	private static final int RING = Math.max(3, Integer.getInteger("mcopt.metal.inFlight", 2) + 1);
	private static final int SLOT_SIZE = 1024;

	private static long ctx, state, compareSampler, dummyShadow, shadowMap;
	private static final long[] buffers = new long[RING];
	private static final ByteBuffer[] views = new ByteBuffer[RING];
	private static int slot;
	private static boolean failed;

	private ShadeBindings() {
	}

	/** Binds the globals into the encoder's open render encoder (enc: the native Enc*); sets everything up on first use. */
	public static void bind(long ctx, long enc) {
		if (state == 0 && !init(ctx)) return;
		ShadeNative.bind(enc);
	}

	static boolean ready() {
		return state != 0;
	}

	static synchronized boolean init(long context) {
		if (state != 0) return true;
		if (failed) return false;
		try {
			ctx = context;
			state = ShadeNative.create(context);
			compareSampler = ShadeNative.compareSampler(state);
			dummyShadow = ShadeNative.dummyShadow(state);
			for (int i = 0; i < RING; i++) {
				buffers[i] = MetalBridge.newBuffer(context, SLOT_SIZE);
				views[i] = MemoryUtil.memByteBuffer(MetalBridge.bufferContents(buffers[i]), SLOT_SIZE);
				ShadeFrameUpdater.FRAME.pack(views[i]); // defaults until the first level frame: never an unwritten buffer
			}
			try (MemoryStack stack = MemoryStack.stackPush()) {
				long err = stack.nmalloc(1, 4096);
				if (ShadeNative.lutInit(state, context, MemoryUtil.memAddress(stack.UTF8(ShadeMsl.lut())), ShadeMsl.LUT_WIDTH, ShadeMsl.LUT_HEIGHT, err, 4096) != 0) {
					throw new IllegalStateException("sky LUT: " + MemoryUtil.memUTF8(err));
				}
			}
			publish();
			return true;
		} catch (RuntimeException e) {
			failed = true;
			state = 0;
			System.out.println("[mcopt] shade: setup failed, shading stays off: " + e);
			e.printStackTrace();
			return false;
		}
	}

	/** The next ring slot, for this frame's ShadeFrame (bound by publish()). */
	static ByteBuffer nextSlot() {
		slot = (slot + 1) % RING;
		return views[slot];
	}

	static ByteBuffer currentSlot() {
		return views[slot];
	}

	/** Points the native bind state at the current slot, shadow map and sampler. */
	static void publish() {
		ShadeNative.set(buffers[slot], 0, shadowMap != 0 ? shadowMap : dummyShadow, compareSampler);
	}

	/** Renders the sky-view LUT from the current slot into the frame's command buffer (ends any open render encoder). */
	static void renderSkyLut(long enc) {
		ShadeNative.lutRender(enc, state);
	}

	/** The rebinding entry for code holding only a native Enc* (the shadow pass after its own encoders). */
	public static void rebind(long enc) {
		if (state != 0) ShadeNative.bind(enc);
	}

	/**
	 * The shadow pass's map: an MTLTexture of type 2DArray, Depth32Float, one slice per cascade (see shade_common.metal's
	 * convention); 0 goes back to the dummy. The caller keeps it alive while it's set. Takes effect for encoders bound from
	 * now on (call rebind for an open one).
	 */
	public static void setShadowMap(long texture) {
		shadowMap = texture;
		if (state != 0) publish();
	}

	/** The comparison sampler bound at sampler(12) (linear, clamp, GreaterEqual), for the shadow pass's own use. */
	public static long compareSampler() {
		return compareSampler;
	}

	/** Rewrites the shadow fields of this frame's slot after Shade.frame().setShadow(...) (the GPU reads it when the frame runs). */
	public static void updateShadow() {
		if (state != 0) ShadeFrameUpdater.FRAME.packShadow(views[slot]);
	}

	static long ctx() {
		return ctx;
	}
}
