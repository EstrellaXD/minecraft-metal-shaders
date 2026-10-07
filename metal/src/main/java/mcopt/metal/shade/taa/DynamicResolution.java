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
package mcopt.metal.shade.taa;

/**
 * Dynamic resolution controller (shader bible E18): picks the render scale from the GPU time of finished frames, so the
 * frame fits the budget of the target frame rate. Pure logic (no game or Metal types), render thread only.
 *
 * <p>Input: each retired frame's command buffer GPU start/end (seconds, host clock). The busy time of a frame is the part
 * of its [start, end] not already covered by the previous frame's (two frames are in flight and may overlap), so it is the
 * GPU time the frame really added. An exponential average over ~12 frames is compared with the target, 86% of the frame
 * budget (headroom for the CPU side and other apps); the new scale follows s' = s * sqrt(target / time) (cost ~ pixels ~
 * s^2), quantised to 1/64, applied only when it moves more than 0.015 (or reaches a bound). Asymmetric, so it doesn't
 * hunt: it drops fast (up to -0.04 per step, 8 frames after the last change) and climbs slowly (up to +0.02 per step, 45
 * frames after the last change, and only while the frame is under 92% of the target, so a climb never lands right on the
 * edge). After a change the frames still in flight are skipped and the average starts afresh (12 frames), so it never mixes
 * two scales. Changes cost nothing: the world renders into a viewport sub-rect of targets allocated at the maximum scale.
 */
public final class DynamicResolution {
	private static final float STEP_DOWN = 0.04f, STEP_UP = 0.02f, QUANTUM = 1f / 64f, DEADBAND = 0.015f, UP_MARGIN = 0.92f;
	private static final int SETTLE_FRAMES = 8, SETTLE_UP_FRAMES = 45, AVERAGE_FRAMES = 12, IN_FLIGHT = Math.max(1, Integer.getInteger("mcopt.metal.inFlight", 2));

	private final float min, max;
	private final double targetSeconds;
	private float scale;
	private double average, lastEnd;
	private int samples, sinceChange, skip, pinned;

	public DynamicResolution(float start, float min, float max, int fps) {
		this.min = min;
		this.max = max;
		this.scale = TaaConfig.clamp(start, min, max);
		this.targetSeconds = 0.86 / fps;
	}

	public float scale() {
		return this.scale;
	}

	/** Average GPU time per frame (ms) as the controller sees it, 0 before any frame. */
	public double averageMs() {
		return this.average * 1e3;
	}

	/** A retired frame's command buffer GPU times (seconds; 0 when unknown). */
	public void frame(double gpuStart, double gpuEnd) {
		if (!(gpuEnd > 0) || !(gpuStart > 0) || gpuEnd < gpuStart) return;
		double busy = gpuEnd - Math.max(gpuStart, this.lastEnd);
		this.lastEnd = Math.max(this.lastEnd, gpuEnd);
		if (busy <= 0 || busy > 0.5) return; // overlapped entirely, or a stall (loading, a hitch): not the steady cost
		if (this.skip > 0) {
			this.skip--;
			return;
		}
		this.average = this.samples == 0 ? busy : this.average + (busy - this.average) / AVERAGE_FRAMES;
		this.samples++;
	}

	/** Called once per frame before it renders: the scale for this frame. */
	public float update() {
		this.sinceChange++;
		if (this.samples < AVERAGE_FRAMES || this.sinceChange < SETTLE_FRAMES) return this.scale;
		// at the floor and still over budget: the preset can't hold its target here (mcopt.shade.drsLog says so every ~5 s)
		if (this.scale <= this.min && this.average > this.targetSeconds) {
			if (++this.pinned % 600 == 0 && TaaConfig.DRS_LOG) {
				System.out.println(String.format(java.util.Locale.ROOT, "[mcopt] shade: render scale pinned at its floor %.3f, GPU %.2f ms/frame over the %.2f ms target",
					this.scale, this.average * 1e3, this.targetSeconds * 1e3));
			}
		} else {
			this.pinned = 0;
		}
		float next = (float) (this.scale * Math.sqrt(this.targetSeconds / this.average));
		if (next > this.scale && (this.sinceChange < SETTLE_UP_FRAMES || this.average > UP_MARGIN * this.targetSeconds)) return this.scale;
		next = TaaConfig.clamp(next, this.scale - STEP_DOWN, this.scale + STEP_UP);
		next = TaaConfig.clamp(Math.round(next / QUANTUM) * QUANTUM, this.min, this.max);
		boolean toBound = (next == this.min || next == this.max) && next != this.scale;
		if (Math.abs(next - this.scale) <= DEADBAND && !toBound) return this.scale;
		this.scale = next;
		// frames of the old scale are still in flight: skip them, then average afresh
		this.sinceChange = 0;
		this.samples = 0;
		this.skip = IN_FLIGHT + 1;
		return this.scale;
	}

	/** Forgets the timing history (e.g. after a pause or a world change). */
	public void reset() {
		this.samples = 0;
		this.average = 0;
		this.sinceChange = 0;
	}
}
