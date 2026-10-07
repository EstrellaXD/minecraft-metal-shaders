// Copyright 2026 mcopt-shade contributors. Licensed under the Apache License, Version 2.0
// (http://www.apache.org/licenses/LICENSE-2.0). Dev tool for mcopt native shading's clouds, not part of the mod.
import java.nio.*;
import java.nio.file.*;
import mcopt.metal.shade.*;
import mcopt.metal.shade.clouds.*;

/**
 * Writes, per scenario, the bytes the cloud passes and the preview composite read: <name>.frame (ShadeFrame, 1008 B),
 * <name>.look (ShadeLook, 1024 B, clouds fields on) and <name>.params (FRAMES x CloudParams, one per frame: checkerboard
 * slot, frame counter, wind). Args: out dir, output width, height, cloud scale, frames.
 * Scenario columns: name, sun angle (deg, 0 noon), rain, thunder, yaw (deg), pitch (deg), day (weather), coverage (-1 daily).
 */
public class CloudPreviewFrames {
	public static void main(String[] a) throws Exception {
		Path out = Path.of(a[0]);
		int W = Integer.parseInt(a[1]), H = Integer.parseInt(a[2]);
		float scale = Float.parseFloat(a[3]);
		int frames = Integer.parseInt(a[4]);
		String only = a.length > 5 ? a[5] : "";
		Files.createDirectories(out);
		Object[][] sc = {
			{"noon_toward", 20f, 0f, 0f, 90f, 18f, 0.0, -1f},
			{"noon_away", 20f, 0f, 0f, -90f, 18f, 0.0, -1f},
			{"noon_up", 20f, 0f, 0f, 30f, 55f, 0.0, -1f},
			{"afternoon_back", 62f, 0f, 0f, 90f, 10f, 0.0, -1f},
			{"sunset_toward", 85f, 0f, 0f, 90f, 6f, 0.0, -1f},
			{"sunset_away", 85f, 0f, 0f, -90f, 8f, 0.0, -1f},
			{"dusk", 94f, 0f, 0f, 90f, 8f, 0.0, -1f},
			{"night", 160f, 0f, 0f, 30f, 20f, 0.0, -1f},
			{"rain", 25f, 1f, 0f, 60f, 12f, 0.0, -1f},
			{"storm", 25f, 1f, 1f, 60f, 12f, 0.0, -1f},
			{"day1", 30f, 0f, 0f, 120f, 15f, 1.3, -1f},
			{"day2", 30f, 0f, 0f, 120f, 15f, 2.3, -1f},
			{"day3", 30f, 0f, 0f, 120f, 15f, 3.3, -1f},
			{"sparse", 30f, 0f, 0f, 60f, 12f, 0.0, 0.25f},
			{"dense", 30f, 0f, 0f, 60f, 12f, 0.0, 0.75f},
		};
		for (Object[] s : sc) {
			String name = (String) s[0];
			if (!only.isEmpty() && !name.matches(only)) continue;
			ShadeFrame f = new ShadeFrame();
			double[] d = new double[3];
			ShadeSky.celestial(Math.toRadians((Float) s[1]), d);
			f.sunDir.set((float) d[0], (float) d[1], (float) d[2]);
			f.moonDir.set(f.sunDir).negate();
			f.moonPhase = 1f;
			f.rain = (Float) s[2];
			f.thunder = (Float) s[3];
			f.eyeSkyLight = 15;
			f.dayTime = ((Float) s[1] / 360f + 1f) % 1f;
			f.width = W; f.height = H;
			f.cameraX = 120.5; f.cameraY = 80.0; f.cameraZ = -340.25;
			float yaw = (float) Math.toRadians((Float) s[4]), pitch = (float) Math.toRadians((Float) s[5]);
			f.view.identity().rotateX(-pitch).rotateY(yaw);
			f.proj.setPerspective((float) Math.toRadians(70), (float) W / H, 768f, 0.05f, true);
			f.proj.mul(f.view, f.viewProj);
			f.viewProj.invert(f.invViewProj);
			f.prevViewProj.set(f.viewProj);
			f.fogRenderStart = 230f; f.fogRenderEnd = 256f; f.fogSkyEnd = 256f; f.fogCloudEnd = 256f;
			f.fogEnvStart = 1e4f; f.fogEnvEnd = 1e4f + 1f;
			f.updateAtmosphere(0f, true);
			ShadeLook look = new ShadeLook();
			look.update(f, 0, 0.016f);
			ByteBuffer fb = ByteBuffer.allocateDirect(ShadeFrame.SIZE).order(ByteOrder.LITTLE_ENDIAN);
			f.pack(fb);
			CloudWeather w = new CloudWeather();
			double day = (Double) s[6];
			if ((Float) s[7] >= 0f) System.setProperty("mcopt.shade.clouds.coverage", String.valueOf(s[7]));
			w.update(day + 0.25, f.rain, f.thunder, 0f, true, 0L);
			if ((Float) s[7] >= 0f) w.coverage = (Float) s[7];
			CloudParams p = new CloudParams();
			int hw = Math.round(W * scale), hh = Math.round(H * scale);
			int nc = CloudConfig.CHECKER;
			hw = (hw + nc - 1) / nc * nc; hh = (hh + nc - 1) / nc * nc;
			p.histW = hw; p.histH = hh; p.marchW = hw / nc; p.marchH = hh / nc; p.checker = nc;
			p.base = 200f; p.top = 350f;
			ByteBuffer pb = ByteBuffer.allocateDirect(CloudParams.SIZE * frames).order(ByteOrder.LITTLE_ENDIAN);
			for (int i = 0; i < frames; i++) {
				p.frame = i;
				p.reset = i == 0;
				p.domeValid = i > 0;
				float dt = 1f / 60f;
				p.windStepX = w.windX * w.windSpeed * dt; p.windStepZ = w.windZ * w.windSpeed * dt;
				p.windX += p.windStepX; p.windZ += p.windStepZ;
				p.wind3X += p.windStepX * 0.82; p.wind3Z += p.windStepZ * 0.82;
				p.cirrusX += p.windStepX * 1.7; p.cirrusZ += p.windStepZ * 1.7;
				p.pack(pb.slice(CloudParams.SIZE * i, CloudParams.SIZE), f, w);
			}
			ByteBuffer lb = ByteBuffer.allocateDirect(ShadeLook.SIZE).order(ByteOrder.LITTLE_ENDIAN);
			look.pack(lb, f);
			p.packLook(lb, true);
			write(out.resolve(name + ".frame"), fb);
			write(out.resolve(name + ".look"), lb);
			write(out.resolve(name + ".params"), pb);
			System.out.printf("%-16s %dx%d clouds %dx%d  %s  light %.2f %.2f %.2f amb %.2f %.2f %.2f zenith %.2f %.2f %.2f hor %.2f %.2f %.2f exposure %.3f%n", name, W, H, hw, hh, w.describe(),
				p.light[0], p.light[1], p.light[2], f.sky.ambientSky[0], f.sky.ambientSky[1], f.sky.ambientSky[2], f.sky.zenith[0], f.sky.zenith[1], f.sky.zenith[2],
				f.sky.horizon[0][0], f.sky.horizon[0][1], f.sky.horizon[0][2], f.exposure);
		}
	}

	static void write(Path p, ByteBuffer b) throws Exception {
		byte[] bytes = new byte[b.capacity()];
		b.get(0, bytes);
		Files.write(p, bytes);
	}
}
