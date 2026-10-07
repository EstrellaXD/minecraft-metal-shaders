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
import java.nio.ByteOrder;
import org.joml.Matrix4f;

/**
 * The art layer (bible E01, §3.2): computed on the CPU once per frame from the physical state in ShadeFrame and packed at
 * buffer(17) in the exact layout of struct ShadeLook (shade_look.metal; change both together). It
 * <ul>
 * <li>interpolates the time-of-day keyframes by sun elevation (Catmull-Rom, colours in sqrt space), with the morning /
 * evening asymmetry, rain and the look preset on top;</li>
 * <li>grades the physical sun colour (chroma power, light multiplier) before ShadeFrame is packed, so every consumer (terrain,
 * clouds, water, the volumetric light module) sees the same art-directed light;</li>
 * <li>solves the tone curve (Lottes 2016 through mid-grey and white) and builds the grade matrices and their inverses;</li>
 * <li>sets the exposure reference and the auto-exposure bounds, the haze and border fog, bloom, night sky parameters.</li>
 * </ul>
 * Pure maths, no game types: tools can pack it too.
 */
public final class ShadeLook {
	public static final int SIZE = 1024;
	// Byte offsets of struct ShadeLook (shade_look.metal).
	public static final int VIEW_PROJ = 0, PREV_VIEW_PROJ = 64, CELESTIAL = 128, CLOUD_SHADOW_VP = 192, SUN_GEOM = 256, SUN_ART = 272, AMBIENT_ART = 288,
		BOUNCE = 304, GRADE0 = 320, GRADE1 = 336, GRADE2 = 352, GRADE3 = 368, TONEMAP = 384, OUTPUT = 400, BLOOM = 416, FOG_R = 432, FOG_M = 448,
		FOG_MISC = 464, VL = 480, WET = 496, WIND = 512, CLOUDS0 = 528, CLOUDS1 = 544, CLOUDS2 = 560, WATER = 576, WATER_ABS = 592, HELD_LIGHT = 608,
		EYE = 624, BIOME_A = 640, BIOME_B = 656, NIGHT = 672, SSR = 688, TAA = 704, RENDER_SCALE = 720, EXPOSURE_CFG = 736, DIM_TINT = 752, FLASH = 768,
		DT = 784, TIER = 800, GRADE_IN = 816, GRADE_OUT = 864, GRADE_IN_INV = 912, GRADE_OUT_INV = 960, EXPOSURE_REF = 1008;

	/** ShadeLook.tier.y bits (SHADE_FEATURE_* in shade_look.metal). */
	public static final int FEATURE_HDR = 1, FEATURE_BLOOM = 2, FEATURE_AUTO_EXPOSURE = 4, FEATURE_PCSS = 8, FEATURE_STARS = 16, FEATURE_HAZE = 32,
		FEATURE_LIGHT_V2 = 64;

	// ---- keyframes (bible §3.2), by sun elevation in degrees --------------------------------------------------------------
	// columns: elevation, chroma gamma, light multiplier, ambient tint r g b, saturation, contrast a, bloom mix, VL k, mist sigma
	// (x 1e-3 / block), white balance (mired; positive = warmer image)
	private static final float[][] KEYS = {
		{-15, 1.00f, 1.00f, 0.80f, 0.88f, 1.16f, 0.74f, 1.30f, 0.130f, 0.30f, 1.5f, -20},
		{-9, 1.00f, 1.00f, 0.84f, 0.86f, 1.20f, 0.82f, 1.32f, 0.120f, 0.15f, 2.0f, -25},
		{-4, 1.00f, 1.00f, 0.86f, 0.84f, 1.22f, 1.10f, 1.34f, 0.110f, 0.05f, 2.5f, -30},
		{0, 1.60f, 0.85f, 1.04f, 0.93f, 1.05f, 1.22f, 1.36f, 0.100f, 1.00f, 5.0f, 40},
		{5, 1.40f, 1.00f, 1.00f, 0.95f, 1.05f, 1.18f, 1.38f, 0.090f, 0.85f, 4.0f, 30},
		{12, 1.18f, 1.00f, 0.98f, 0.98f, 1.04f, 1.12f, 1.40f, 0.085f, 0.55f, 2.5f, 18},
		{25, 1.05f, 1.00f, 0.96f, 1.00f, 1.06f, 1.04f, 1.40f, 0.080f, 0.32f, 1.2f, 5},
		{45, 1.00f, 1.00f, 0.95f, 1.00f, 1.08f, 1.02f, 1.40f, 0.075f, 0.22f, 0.6f, 0},
		{65, 1.00f, 1.00f, 0.95f, 1.00f, 1.10f, 1.00f, 1.42f, 0.075f, 0.18f, 0.5f, -5},
	};
	private static final int K_GAMMA = 1, K_LIGHT = 2, K_AMB = 3, K_SAT = 6, K_CONTRAST = 7, K_BLOOM = 8, K_VL = 9, K_MIST = 10, K_WB = 11;

	// ---- look presets (bible §3.6): epic, storybook, natural -------------------------------------------------------------
	private static final float[] PRESET_GAMMA = {1.0f, 1.15f, 0.9f}, PRESET_SAT = {0.08f, 0.14f, 0.02f}, PRESET_CONTRAST = {1.05f, 0.97f, 0.95f},
		PRESET_BLOOM = {1.0f, 1.25f, 0.8f}, PRESET_LAMBDA = {0.55f, 0.40f, 0.70f}, PRESET_INSET = {0.14f, 0.10f, 0.20f}, PRESET_VL = {1.0f, 1.25f, 0.8f},
		PRESET_SPECTRUM = {0.6f, 0.9f, 0.3f};

	// ---- tuning knobs ----------------------------------------------------------------------------------------------------
	/** Tone curve anchors: mid-grey in -> out, white point (scene) -> peak (display). */
	static final float MID_IN = 0.18f, MID_OUT = 0.20f, WHITE = 12f, SHOULDER = 0.92f;
	/** Auto-exposure metering key (the metered mean luminance maps to this, pre-exposed) and bounds around the reference. */
	static final float METER_KEY = 0.30f, EV_DOWN = 1.75f, EV_UP = 1.75f, EV_UP_NIGHT = 0.6f, TAU_UP = 0.6f, TAU_DOWN = 2.2f;
	/** Haze (Rayleigh-like) extinction at its base, per block, and scale height; base height above sea level. */
	static final float[] HAZE_SIGMA = {0.22e-3f * 0.40f, 0.48e-3f * 0.40f, 1.0e-3f * 0.40f};
	/** The keyframes' mist sigma column (x 1e-3 / block) is scaled by this: at full strength a low sun drowns the near field. */
	static final float MIST_SCALE = 0.35f;
	public static final float HAZE_H = 48f, HAZE_BASE = 16f, MIST_H = 10f, MIST_BASE = 4f, MIST_ALBEDO = 0.9f, SEA = 63f;
	/** Sun and moon disc angular radius (radians). */
	static final float SUN_RADIUS = (float) Math.toRadians(1.0), MOON_RADIUS = (float) Math.toRadians(0.65);

	// ---- per-frame values (read by tools and the debug log) --------------------------------------------------------------
	public float elevation, rising, chromaGamma, lightMul, saturation, contrast, bloomMix, vlStrength, mistSigma, whiteBalance;
	public final float[] ambientTint = new float[3], wbGain = new float[3];
	public float toneB, toneBeta, inset, exposureRef;
	public float starBrightness, moonPhaseAngle, borderRadius;
	private float eyeSky = 1f;
	private final float[][] gradeIn = new float[3][3], gradeOut = new float[3][3], gradeInInv = new float[3][3], gradeOutInv = new float[3][3];
	private final Matrix4f celestial = new Matrix4f();
	private boolean snapExposure = true;
	private float dt;
	private int features;
	private int frames;

	/** Exposure jumps to its target next frame (teleport, dimension change, resize, setup). */
	public void snapExposure() {
		this.snapExposure = true;
	}

	/**
	 * The art layer for this frame, from f (atmosphere already updated: f.sky, f.exposure). Grades f.sky.light in place
	 * (call before f.pack). moonPhaseIndex: vanilla's (0 full .. 4 new .. 7). dtSeconds: since the last frame.
	 */
	public void update(ShadeFrame f, int moonPhaseIndex, float dtSeconds) {
		this.frames++;
		this.dt = Math.max(0f, Math.min(dtSeconds, 0.25f));
		int look = ShadeConfig.LOOK;
		float sunY = Math.max(-1f, Math.min(1f, f.sunDir.y));
		this.elevation = (float) Math.toDegrees(Math.asin(sunY));
		this.rising = f.dayTime > 0.5f ? 1f : -1f; // dayTime: 0 noon, 0.25 sunset, 0.5 midnight, 0.75 sunrise
		float[] k = keyframe(this.elevation);
		float rain = f.rain, thunder = f.thunder;
		this.chromaGamma = k[K_GAMMA] * PRESET_GAMMA[look];
		this.lightMul = k[K_LIGHT];
		for (int i = 0; i < 3; i++) this.ambientTint[i] = k[K_AMB + i];
		this.saturation = k[K_SAT] + PRESET_SAT[look];
		this.contrast = k[K_CONTRAST] * PRESET_CONTRAST[look];
		this.bloomMix = k[K_BLOOM] * PRESET_BLOOM[look];
		this.vlStrength = k[K_VL] * PRESET_VL[look];
		this.mistSigma = k[K_MIST];
		this.whiteBalance = k[K_WB];
		// morning / evening asymmetry
		float lowSun = smooth(-12f, -2f, this.elevation) * (1f - smooth(15f, 35f, this.elevation));
		if (this.rising > 0f) {
			this.mistSigma *= 1f + 0.6f * lowSun;
			this.whiteBalance -= 10f * lowSun;
			this.saturation -= 0.04f * lowSun;
		} else {
			this.mistSigma *= 1f - 0.1f * lowSun;
			this.whiteBalance += 12f * lowSun;
			this.saturation += 0.05f * lowSun;
		}
		// night: the keyframes' desaturation would grey the torch-lit pools too; the dim parts get the Purkinje shift (final
		// pass, luminance-gated) instead, so the grade keeps colour where there is light
		float night = 1f - smooth(-10f, -2f, this.elevation);
		this.saturation += (Math.max(this.saturation, 1f) - this.saturation) * night;
		// weather
		this.saturation *= 1f - 0.25f * rain;
		this.contrast -= 0.08f * rain;
		this.bloomMix += 0.03f * rain;
		this.mistSigma *= 1f + 4f * rain;
		this.whiteBalance -= 10f * rain;
		this.lightMul *= 1f - 0.1f * thunder;
		// grade the light (sun or moon) in place: chroma power keeps luminance, deepens the colour
		gradeLight(f.sky.light, f.sky.sunLight ? this.chromaGamma : 1f, this.lightMul);
		// white balance (mired shift from D65: positive = the image warmer), partial strength
		float mired = 1e6f / 6504f + this.whiteBalance;
		float[] target = blackbody(1e6f / Math.max(mired, 20f)), d65 = blackbody(6504f);
		for (int i = 0; i < 3; i++) this.wbGain[i] = 1f + (target[i] / d65[i] - 1f) * 0.5f;
		float l = luma(this.wbGain[0], this.wbGain[1], this.wbGain[2]);
		for (int i = 0; i < 3; i++) this.wbGain[i] /= l;
		// tone curve through (MID_IN, MID_OUT) and (WHITE, 1)
		float a = this.contrast, d = SHOULDER;
		double wa = Math.pow(WHITE, a), ma = Math.pow(MID_IN, a), wad = Math.pow(WHITE, a * d), mad = Math.pow(MID_IN, a * d);
		this.toneB = (float) ((wa / 1.0 - ma / MID_OUT) / (wad - mad));
		this.toneBeta = (float) (ma / MID_OUT - mad * this.toneB);
		// grade matrices: in = saturation(sat * (1 - inset)) * diag(wb); out = saturation(1 / (1 - 0.6 inset))
		this.inset = PRESET_INSET[look];
		float sIn = Math.max(0.05f, this.saturation * (1f - this.inset));
		float sOut = 1f / (1f - 0.6f * this.inset);
		satMatrix(sIn, this.gradeIn);
		for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++) this.gradeIn[r][c] *= this.wbGain[c];
		satMatrix(sOut, this.gradeOut);
		invert(this.gradeIn, this.gradeInInv);
		invert(this.gradeOut, this.gradeOutInv);
		// exposure reference: M1's eye-adapted exposure (sky model + eye sky light), with the user's compensation
		// overcast is darker than a clear day (rain palette, bible §3.3): -0.7 EV at full rain
		this.exposureRef = f.exposure * (float) Math.pow(2, ShadeConfig.EV - 0.7f * rain);
		this.eyeSky += (f.eyeSkyLight / 15f - this.eyeSky) * (1f - (float) Math.exp(-this.dt / 1.5f));
		// night sky
		this.starBrightness = f.starBrightness * (1f - rain);
		this.moonPhaseAngle = (float) Math.toRadians(45.0 * Math.floorMod(moonPhaseIndex, 8));
		// celestial frame: e1 = the sun direction, e3 = the (tilted) pole of its path, e2 = e3 x e1
		float t = (float) Math.toRadians(ShadeSky.SUN_TILT);
		float px = 0f, py = (float) -Math.sin(t), pz = (float) Math.cos(t);
		float sx = f.sunDir.x, sy = f.sunDir.y, sz = f.sunDir.z;
		float ex = py * sz - pz * sy, ey = pz * sx - px * sz, ez = px * sy - py * sx;
		// stored so that the shader's celestial[i] (a column in MSL) is e(i+1): it reads them as rows of the world -> star space map
		this.celestial.set(sx, sy, sz, 0, ex, ey, ez, 0, px, py, pz, 0, 0, 0, 0, 1);
		// border fog radius: vanilla's render distance fog end, unless something moved it away (Distant Horizons: start >= end)
		this.borderRadius = f.fogRenderStart < f.fogRenderEnd && f.fogRenderEnd < 1e5f ? Math.max(f.fogRenderEnd - 8f, 16f) : 0f;
		int bits = 0;
		if (ShadeConfig.HDR) bits |= FEATURE_HDR;
		if (ShadeConfig.BLOOM) bits |= FEATURE_BLOOM;
		if (ShadeConfig.AUTO_EXPOSURE) bits |= FEATURE_AUTO_EXPOSURE;
		if (ShadeConfig.PCSS) bits |= FEATURE_PCSS;
		if (ShadeConfig.STARS) bits |= FEATURE_STARS;
		if (ShadeConfig.HAZE) bits |= FEATURE_HAZE;
		if (ShadeConfig.LIGHT2) bits |= FEATURE_LIGHT_V2;
		this.features = bits;
	}

	/** Writes the struct (SIZE bytes) at out's position 0. */
	public void pack(ByteBuffer out, ShadeFrame f) {
		ByteBuffer b = out.duplicate().order(ByteOrder.LITTLE_ENDIAN);
		// without the TAA jitter (a translation in clip space: ShadeTaa.jitter), for passes that want a stable raster
		new Matrix4f().translation(-f.jitterX, -f.jitterY, 0f).mul(f.viewProj).get(VIEW_PROJ, b);
		new Matrix4f().translation(-f.prevJitterX, -f.prevJitterY, 0f).mul(f.prevViewProj).get(PREV_VIEW_PROJ, b);
		this.celestial.get(CELESTIAL, b);
		new Matrix4f().get(CLOUD_SHADOW_VP, b);
		put4(b, SUN_GEOM, (float) Math.toRadians(ShadeSky.SUN_TILT), SUN_RADIUS, MOON_RADIUS, (float) Math.toRadians(this.elevation));
		float[] l = f.sky.light;
		put4(b, SUN_ART, l[0], l[1], l[2], 1f);
		put4(b, AMBIENT_ART, this.ambientTint[0], this.ambientTint[1], this.ambientTint[2], 0.06f * (1f - 0.7f * f.rain));
		put4(b, BOUNCE, 0.62f, 0.56f, 0.44f, 0f);
		put4(b, GRADE0, 0f, this.saturation, ShadeConfig.LOOK == 1 ? 0.18f : 0.10f, 0f);
		put4(b, GRADE1, this.wbGain[0], this.wbGain[1], this.wbGain[2], 0.55f);
		put4(b, GRADE2, 0f, 0f, 0f, 0.0025f);
		put4(b, GRADE3, 1f, 1f, 1f, this.inset);
		put4(b, TONEMAP, this.contrast, SHOULDER, this.toneB, this.toneBeta);
		float inverseMax = Math.min(0.80f, 0.95f / (this.toneB * SHOULDER));
		put4(b, OUTPUT, 1f, 1f, 1f, inverseMax);
		put4(b, BLOOM, this.bloomMix, PRESET_SPECTRUM[ShadeConfig.LOOK], 0.06f * (1f + f.rain), 0f);
		float haze = 1f + 1.0f * f.rain;
		put4(b, FOG_R, HAZE_SIGMA[0] * haze, HAZE_SIGMA[1] * haze, HAZE_SIGMA[2] * haze, HAZE_H);
		put4(b, FOG_M, this.mistSigma * 1e-3f * MIST_SCALE, MIST_H * (1f + 2f * f.rain), SEA + MIST_BASE, f.rain > 0.5f ? 0.6f : MIST_ALBEDO);
		put4(b, FOG_MISC, SEA + HAZE_BASE, f.rain > 0f ? 0.78f - 0.18f * f.rain : 0.78f, this.borderRadius, 0.6f * (1f - f.rain));
		put4(b, VL, this.vlStrength, 160f, 0.7f, 12f);
		put4(b, EYE, 0f, -1.62f, 0f, this.eyeSky);
		put4(b, NIGHT, this.starBrightness, 1f, 0f, this.moonPhaseAngle);
		put4(b, RENDER_SCALE, f.renderScale, (float) (Math.log(f.mipScale) / Math.log(2)), f.width, f.height);
		// at night the meter may brighten less (night stays night: the reference is already lifted for it)
		float nightUp = EV_UP - (EV_UP - EV_UP_NIGHT) * (1f - smooth(-10f, -2f, this.elevation));
		put4(b, EXPOSURE_CFG, EV_DOWN, nightUp, TAU_UP, TAU_DOWN);
		put4(b, DT, this.dt, f.gameTimeSeconds, 0f, this.rising);
		b.putInt(TIER, ShadeConfig.TIER).putInt(TIER + 4, this.features).putInt(TIER + 8, ShadeConfig.LOOK).putInt(TIER + 12, f.debug);
		putRows(b, GRADE_IN, this.gradeIn);
		putRows(b, GRADE_OUT, this.gradeOut);
		putRows(b, GRADE_IN_INV, this.gradeInInv);
		putRows(b, GRADE_OUT_INV, this.gradeOutInv);
		float lambda = ShadeConfig.AUTO_EXPOSURE ? PRESET_LAMBDA[ShadeConfig.LOOK] : 0f;
		put4(b, EXPOSURE_REF, this.exposureRef, lambda, METER_KEY, this.snapExposure ? 1f : 0f);
		this.snapExposure = false;
	}

	// ---- values for modules (volumetric light: its open-air medium follows E06's haze and mist) ---------------------------
	/** Luminance of the haze extinction at its base (1/block), as packed in fogR this frame. */
	public float hazeSigmaLuma(ShadeFrame f) {
		return luma(HAZE_SIGMA[0], HAZE_SIGMA[1], HAZE_SIGMA[2]) * (1f + 1.0f * f.rain);
	}

	/** Mist extinction at its base (1/block), as packed in fogM.x this frame. */
	public float mistSigmaBlock() {
		return this.mistSigma * 1e-3f * MIST_SCALE;
	}

	/** Mist scale height (blocks), as packed in fogM.y. */
	public float mistHeight(ShadeFrame f) {
		return MIST_H * (1f + 2f * f.rain);
	}

	/**
	 * Volumetric light strength factor from the VL keyframe column x look preset (vlStrength: ~0.2 at noon, 1 at golden hour),
	 * softened to 0.66 .. 1.2 (shafts under a canopy stay visible at noon); 1 at night (the module grades moon shafts itself).
	 */
	public float vlFactor() {
		if (this.elevation < -4f) return PRESET_VL[ShadeConfig.LOOK];
		return 0.55f + 0.53f * Math.min(this.vlStrength, 1.25f);
	}

	// ---- maths -------------------------------------------------------------------------------------------------------------
	/** Keyframe values at elevation e: Catmull-Rom between rows (clamped ends), colours in sqrt space. */
	static float[] keyframe(float e) {
		int n = KEYS.length;
		float[] out = new float[KEYS[0].length];
		if (e <= KEYS[0][0]) return KEYS[0].clone();
		if (e >= KEYS[n - 1][0]) return KEYS[n - 1].clone();
		int i = 0;
		while (KEYS[i + 1][0] < e) i++;
		float t = (e - KEYS[i][0]) / (KEYS[i + 1][0] - KEYS[i][0]);
		float[] p0 = KEYS[Math.max(i - 1, 0)], p1 = KEYS[i], p2 = KEYS[i + 1], p3 = KEYS[Math.min(i + 2, n - 1)];
		for (int c = 1; c < out.length; c++) {
			boolean colour = c >= K_AMB && c < K_AMB + 3;
			float a0 = colour ? (float) Math.sqrt(p0[c]) : p0[c], a1 = colour ? (float) Math.sqrt(p1[c]) : p1[c];
			float a2 = colour ? (float) Math.sqrt(p2[c]) : p2[c], a3 = colour ? (float) Math.sqrt(p3[c]) : p3[c];
			float v = catmullRom(a0, a1, a2, a3, t);
			// no overshoot past the neighbouring keys
			v = Math.max(Math.min(a1, a2), Math.min(Math.max(a1, a2), v));
			out[c] = colour ? v * v : v;
		}
		out[0] = e;
		return out;
	}

	private static float catmullRom(float p0, float p1, float p2, float p3, float t) {
		float t2 = t * t, t3 = t2 * t;
		return 0.5f * (2f * p1 + (p2 - p0) * t + (2f * p0 - 5f * p1 + 4f * p2 - p3) * t2 + (3f * p1 - p0 - 3f * p2 + p3) * t3);
	}

	/** Chroma power: c -> Y * (q^gamma / luma(q^gamma)) * mul, q = c / Y. */
	static void gradeLight(float[] c, float gamma, float mul) {
		float y = luma(c[0], c[1], c[2]);
		if (y <= 0f) return;
		float q0 = (float) Math.pow(Math.max(c[0] / y, 0f), gamma), q1 = (float) Math.pow(Math.max(c[1] / y, 0f), gamma), q2 = (float) Math.pow(Math.max(c[2] / y, 0f), gamma);
		float ql = luma(q0, q1, q2);
		if (ql <= 0f) return;
		float s = y * mul / ql;
		c[0] = q0 * s;
		c[1] = q1 * s;
		c[2] = q2 * s;
	}

	/** Saturation matrix around Rec.709 luma: c' = Y + s (c - Y). */
	static void satMatrix(float s, float[][] m) {
		float[] w = {0.2126f, 0.7152f, 0.0722f};
		for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++) m[r][c] = (1f - s) * w[c] + (r == c ? s : 0f);
	}

	static void invert(float[][] m, float[][] out) {
		float a = m[0][0], b = m[0][1], c = m[0][2], d = m[1][0], e = m[1][1], f = m[1][2], g = m[2][0], h = m[2][1], i = m[2][2];
		float A = e * i - f * h, B = -(d * i - f * g), C = d * h - e * g;
		float det = a * A + b * B + c * C;
		float inv = Math.abs(det) > 1e-12f ? 1f / det : 0f;
		out[0][0] = A * inv; out[0][1] = -(b * i - c * h) * inv; out[0][2] = (b * f - c * e) * inv;
		out[1][0] = B * inv; out[1][1] = (a * i - c * g) * inv; out[1][2] = -(a * f - c * d) * inv;
		out[2][0] = C * inv; out[2][1] = -(a * h - b * g) * inv; out[2][2] = (a * e - b * d) * inv;
	}

	/** Planck's law at 610, 550 and 465 nm, normalized to luma 1 (shadeBlackbody without the pull to white). */
	static float[] blackbody(float kelvin) {
		double[] lam = {610e-9, 550e-9, 465e-9};
		float[] out = new float[3];
		for (int i = 0; i < 3; i++) out[i] = (float) (1.0 / (Math.pow(lam[i], 5) * (Math.exp(1.4388e-2 / (lam[i] * kelvin)) - 1.0)));
		float l = luma(out[0], out[1], out[2]);
		for (int i = 0; i < 3; i++) out[i] /= l;
		return out;
	}

	/** The display transform on the CPU (shade_look.metal shadeDisplay), for colours that must match the GPU's (tools, tests). */
	public float[] display(float[] x) {
		float[] c = new float[3];
		for (int r = 0; r < 3; r++) c[r] = Math.max(0f, this.gradeIn[r][0] * x[0] + this.gradeIn[r][1] * x[1] + this.gradeIn[r][2] * x[2]);
		for (int r = 0; r < 3; r++) {
			double xa = Math.pow(Math.max(c[r], 1e-7), this.contrast), xad = Math.pow(Math.max(c[r], 1e-7), this.contrast * SHOULDER);
			c[r] = (float) Math.min(1.0, xa / (xad * this.toneB + this.toneBeta));
		}
		float[] y = new float[3];
		for (int r = 0; r < 3; r++) y[r] = Math.max(0f, this.gradeOut[r][0] * c[0] + this.gradeOut[r][1] * c[1] + this.gradeOut[r][2] * c[2]);
		return y;
	}

	/**
	 * shadeLdrToHdr on the CPU: an sRGB colour drawn by an unshaded pipeline -> the pre-exposed scene value the display
	 * transform maps back to it (e.g. the HDR target's clear colour from the game's fog colour).
	 */
	public float[] ldrToHdr(float r, float g, float b) {
		float[] s = {r, g, b}, y = new float[3], c = new float[3], x = new float[3];
		for (int i = 0; i < 3; i++) {
			float v = Math.min(Math.max(s[i], 0f), 1f);
			s[i] = v <= 0.04045f ? v / 12.92f : (float) Math.pow((v + 0.055f) / 1.055f, 2.4f);
		}
		float max = Math.min(0.80f, 0.95f / (this.toneB * SHOULDER));
		for (int i = 0; i < 3; i++) y[i] = Math.min(max, Math.max(0f, this.gradeOutInv[i][0] * s[0] + this.gradeOutInv[i][1] * s[1] + this.gradeOutInv[i][2] * s[2]));
		for (int i = 0; i < 3; i++) {
			// Newton on u = x^a: u = y (b u^d + beta), from an upper bound (see shadeLottesInverse)
			double yy = y[i], bb = this.toneB, d = SHOULDER, beta = this.toneBeta;
			double u = (yy * bb * (1 - d) + yy * beta) / (1 - yy * bb * d);
			for (int k = 0; k < 6; k++) {
				double ud = Math.pow(Math.max(u, 1e-12), d);
				double gv = u - yy * (bb * ud + beta), dg = 1 - yy * bb * d * ud / Math.max(u, 1e-12);
				u = Math.max(u - gv / Math.max(dg, 0.05), 0);
			}
			c[i] = (float) Math.pow(u, 1.0 / this.contrast);
		}
		for (int i = 0; i < 3; i++) x[i] = Math.max(0f, this.gradeInInv[i][0] * c[0] + this.gradeInInv[i][1] * c[1] + this.gradeInInv[i][2] * c[2]);
		return x;
	}

	private static float luma(float r, float g, float b) {
		return 0.2126f * r + 0.7152f * g + 0.0722f * b;
	}

	private static float smooth(float e0, float e1, float x) {
		float t = Math.min(Math.max((x - e0) / (e1 - e0), 0f), 1f);
		return t * t * (3f - 2f * t);
	}

	private static void put4(ByteBuffer b, int at, float x, float y, float z, float w) {
		b.putFloat(at, x).putFloat(at + 4, y).putFloat(at + 8, z).putFloat(at + 12, w);
	}

	private static void putRows(ByteBuffer b, int at, float[][] m) {
		for (int r = 0; r < 3; r++) put4(b, at + 16 * r, m[r][0], m[r][1], m[r][2], 0f);
	}
}
