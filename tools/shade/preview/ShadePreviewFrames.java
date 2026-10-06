// Copyright 2026 mcopt-shade contributors. Licensed under the Apache License, Version 2.0
// (http://www.apache.org/licenses/LICENSE-2.0). Art-preview tool for mcopt native shading, not part of the mod.
import mcopt.metal.shade.*;
import java.nio.*;
import java.nio.file.*;
import org.joml.Matrix4f;

/** Writes ShadeFrame bytes for preview scenarios: name, sunAngle (deg, 0 noon), rain, thunder, yaw (deg), pitch (deg), eyeSky. */
public class ShadePreviewFrames {
	public static void main(String[] a) throws Exception {
		Path out = Path.of(a[0]);
		Files.createDirectories(out);
		Object[][] sc = {
			{"noon", 10f, 0f, 0f, -60f, 4f, 15},
			{"morning", -55f, 0f, 0f, 70f, 4f, 15},
			{"afternoon", 55f, 0f, 0f, -80f, 6f, 15},
			{"afternoon_away", 55f, 0f, 0f, 100f, 6f, 15},
			{"sunset", 86f, 0f, 0f, -90f, 4f, 15},
			{"sunset_away", 86f, 0f, 0f, 90f, 4f, 15},
			{"dusk", 95f, 0f, 0f, -90f, 8f, 15},
			{"night", 180f, 0f, 0f, 30f, 6f, 15},
			{"night_up", 180f, 0f, 0f, 30f, 50f, 15},
			{"rain", 30f, 1f, 0f, -60f, 4f, 15},
			{"cave", 20f, 0f, 0f, -60f, 4f, 2},
			{"noon_up", 10f, 0f, 0f, -60f, 45f, 15},
		};
		for (Object[] s : sc) {
			ShadeFrame f = new ShadeFrame();
			float sunAngle = (float) Math.toRadians((Float) s[1]);
			f.sunDir.set(-(float) Math.sin(sunAngle), (float) Math.cos(sunAngle), 0f);
			// Minecraft's sun path is tilted only by the RotY(-90); add a slight tilt so shadows aren't axis-aligned in the preview
			f.sunDir.add(0, 0, 0.25f).normalize();
			f.moonDir.set(f.sunDir).negate();
			f.moonPhase = 1f;
			f.rain = (Float) s[2];
			f.thunder = (Float) s[3];
			f.eyeSkyLight = (Integer) s[6];
			f.dayTime = ((Float) s[1] / 360f + 1f) % 1f;
			f.width = 1280; f.height = 720;
			float yaw = (float) Math.toRadians((Float) s[4]), pitch = (float) Math.toRadians((Float) s[5]);
			// Minecraft-like view: rotation only; yaw around Y, pitch around X. Forward = -z in view.
			f.view.identity().rotateX(-pitch).rotateY(yaw);
			f.proj.setPerspective((float) Math.toRadians(70), 1280f / 720f, 0.05f, 768f, true); // zZeroToOne
			// reversed Z like renderpearl: swap near/far
			f.proj.setPerspective((float) Math.toRadians(70), 1280f / 720f, 768f, 0.05f, true);
			f.proj.mul(f.view, f.viewProj);
			f.viewProj.invert(f.invViewProj);
			f.prevViewProj.set(f.viewProj);
			f.fogRenderStart = 172.8f; f.fogRenderEnd = 192f; f.fogSkyEnd = 192f; f.fogCloudEnd = 192f;
			f.fogEnvStart = f.rain > 0 ? 0f : 1e4f; f.fogEnvEnd = f.rain > 0 ? 160f : 1e4f + 1f;
			f.fogColor[0] = 0.75f; f.fogColor[1] = 0.85f; f.fogColor[2] = 1f; f.fogColor[3] = 0f;
			f.updateAtmosphere(0f, true);
			ByteBuffer b = ByteBuffer.allocateDirect(ShadeFrame.SIZE).order(ByteOrder.LITTLE_ENDIAN);
			f.pack(b);
			byte[] bytes = new byte[ShadeFrame.SIZE]; b.get(0, bytes);
			Files.write(out.resolve(s[0] + ".bin"), bytes);
			float[] l = f.sky.light;
			System.out.printf("%-15s exposure %.3f light(%.3f %.3f %.3f) sky(%.3f %.3f %.3f) ground(%.3f %.3f %.3f) zenith(%.3f %.3f %.3f) hor0(%.3f %.3f %.3f) hor7(%.3f %.3f %.3f) luma %.3f%n",
				s[0], f.exposure, l[0], l[1], l[2], f.sky.ambientSky[0], f.sky.ambientSky[1], f.sky.ambientSky[2], f.sky.ambientGround[0], f.sky.ambientGround[1], f.sky.ambientGround[2],
				f.sky.zenith[0], f.sky.zenith[1], f.sky.zenith[2], f.sky.horizon[0][0], f.sky.horizon[0][1], f.sky.horizon[0][2], f.sky.horizon[7][0], f.sky.horizon[7][1], f.sky.horizon[7][2], f.sky.sceneLuma);
		}
	}
}
