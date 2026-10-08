// Copyright 2026 mcopt-shade contributors. Licensed under the Apache License, Version 2.0 (LICENSE).
package mcopt.metal.shade.shadow;

/**
 * One orthographic shadow cascade, defined in absolute world coordinates (doubles) so it can be re-expressed against any
 * camera position without drift: Sodium's vertices are camera-relative, so the float matrix handed to the GPU is rebuilt
 * every frame from the current camera, while the cascade itself (and its rendered map) may be kept for several frames.
 * <p>
 * Light space: z points TO the light; x, y span the map. Stable fitting: the box is a fixed half-width square (not fitted
 * to the view frustum, so turning never resizes it), and its center is snapped to whole texels of a grid fixed in light
 * space, so static geometry always lands on the same texels while the camera moves (no shimmer). Depth is reversed:
 * light-space z = zNear (nearest the light) maps to 1, zFar to 0.
 */
final class ShadowCascade {
	/** Light-space basis in world space: x, y across the map, z toward the light. */
	final double[] x = new double[3], y = new double[3], z = new double[3];
	/** Snapped light-space center of the map (x, y), and the light-space depth range. */
	double u0, v0, zNear, zFar;
	/** Light-space z of the (unsnapped) center, for the debug view. */
	double w0;
	double radius;
	int resolution;
	/** World center the cascade was fitted around, to tell when the camera has left it. */
	double cx, cy, cz;
	/** How far past zNear (toward the light) casters are still drawn; they are pancaked onto depth 1. */
	double casterReach;
	boolean valid;

	/**
	 * Fits the cascade around world point (cx, cy, cz) for unit direction to the light l. back / front: light-space depth
	 * kept behind / in front of the center beyond the half-width; casterReach: extra caster range toward the light.
	 */
	void fit(double[] l, double cx, double cy, double cz, double radius, int resolution, double back, double front, double casterReach) {
		basis(l, this.x, this.y, this.z);
		this.radius = radius;
		this.resolution = resolution;
		this.cx = cx;
		this.cy = cy;
		this.cz = cz;
		this.casterReach = casterReach;
		double texel = this.texelWorld();
		double u = dot(this.x, cx, cy, cz), v = dot(this.y, cx, cy, cz);
		this.u0 = Math.floor(u / texel) * texel;
		this.v0 = Math.floor(v / texel) * texel;
		this.w0 = dot(this.z, cx, cy, cz);
		// depth range snapped to whole texels too, so depth values of static geometry stay put as well
		double w = Math.floor(this.w0 / texel) * texel;
		this.zFar = w - radius - back;
		this.zNear = w + radius + front;
		this.valid = true;
	}

	/** The light-space basis for unit direction l: z = l, x perpendicular to world Z where possible (stable over the sun's arc). */
	static void basis(double[] l, double[] x, double[] y, double[] z) {
		z[0] = l[0];
		z[1] = l[1];
		z[2] = l[2];
		// ref x z: with vanilla's sun path in the world XY plane, x stays in that plane and y is world Z
		double rx = 0, ry = 0, rz = 1;
		if (Math.abs(l[2]) > 0.9) {
			rx = 1;
			rz = 0;
		}
		x[0] = ry * l[2] - rz * l[1];
		x[1] = rz * l[0] - rx * l[2];
		x[2] = rx * l[1] - ry * l[0];
		double n = Math.sqrt(x[0] * x[0] + x[1] * x[1] + x[2] * x[2]);
		x[0] /= n;
		x[1] /= n;
		x[2] /= n;
		y[0] = z[1] * x[2] - z[2] * x[1];
		y[1] = z[2] * x[0] - z[0] * x[2];
		y[2] = z[0] * x[1] - z[1] * x[0];
	}

	/** M3.3 far spread: this cascade becomes a copy of other (the back map's fit, once all its parts are drawn). */
	void copyFrom(ShadowCascade o) {
		System.arraycopy(o.x, 0, this.x, 0, 3);
		System.arraycopy(o.y, 0, this.y, 0, 3);
		System.arraycopy(o.z, 0, this.z, 0, 3);
		this.u0 = o.u0;
		this.v0 = o.v0;
		this.zNear = o.zNear;
		this.zFar = o.zFar;
		this.w0 = o.w0;
		this.radius = o.radius;
		this.resolution = o.resolution;
		this.cx = o.cx;
		this.cy = o.cy;
		this.cz = o.cz;
		this.casterReach = o.casterReach;
		this.valid = o.valid;
	}

	double texelWorld() {
		return 2.0 * this.radius / this.resolution;
	}

	double depthRange() {
		return this.zNear - this.zFar;
	}

	/**
	 * Column-major float4x4 (MSL / JOML order) at out[offset..offset + 16): camera-relative world position -> light clip
	 * (x, y in [-1, 1], z in [0, 1] reversed, w = 1), for a camera at world (camX, camY, camZ).
	 */
	void matrix(double camX, double camY, double camZ, float[] out, int offset) {
		double r = this.radius, d = this.depthRange();
		double tx = (dot(this.x, camX, camY, camZ) - this.u0) / r;
		double ty = (dot(this.y, camX, camY, camZ) - this.v0) / r;
		double tz = (dot(this.z, camX, camY, camZ) - this.zFar) / d;
		// row i = (axis / scale, translation); column-major: out[col * 4 + row]
		for (int c = 0; c < 3; c++) {
			out[offset + c * 4] = (float) (this.x[c] / r);
			out[offset + c * 4 + 1] = (float) (this.y[c] / r);
			out[offset + c * 4 + 2] = (float) (this.z[c] / d);
			out[offset + c * 4 + 3] = 0f;
		}
		out[offset + 12] = (float) tx;
		out[offset + 13] = (float) ty;
		out[offset + 14] = (float) tz;
		out[offset + 15] = 1f;
	}

	/** Depth (0..1, reversed) of world point p in this cascade. */
	double depthOf(double px, double py, double pz) {
		return (dot(this.z, px, py, pz) - this.zFar) / this.depthRange();
	}

	/**
	 * Whether the world-space box [min, max] can cast into this cascade: it overlaps the cascade's square in x, y, and its
	 * light-space depth range reaches from zFar up to casterReach past zNear.
	 */
	boolean casts(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
		double hx = (maxX - minX) * 0.5, hy = (maxY - minY) * 0.5, hz = (maxZ - minZ) * 0.5;
		double mx = minX + hx, my = minY + hy, mz = minZ + hz;
		double eu = hx * Math.abs(this.x[0]) + hy * Math.abs(this.x[1]) + hz * Math.abs(this.x[2]);
		double cu = dot(this.x, mx, my, mz) - this.u0;
		if (Math.abs(cu) > this.radius + eu) return false;
		double ev = hx * Math.abs(this.y[0]) + hy * Math.abs(this.y[1]) + hz * Math.abs(this.y[2]);
		double cv = dot(this.y, mx, my, mz) - this.v0;
		if (Math.abs(cv) > this.radius + ev) return false;
		double ew = hx * Math.abs(this.z[0]) + hy * Math.abs(this.z[1]) + hz * Math.abs(this.z[2]);
		double cw = dot(this.z, mx, my, mz);
		return cw + ew >= this.zFar && cw - ew <= this.zNear + this.casterReach;
	}

	private static double dot(double[] a, double x, double y, double z) {
		return a[0] * x + a[1] * y + a[2] * z;
	}
}
