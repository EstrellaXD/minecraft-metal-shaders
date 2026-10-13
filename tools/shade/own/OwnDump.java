// Copyright 2026 mcopt-shade contributors. Licensed under the Apache License, Version 2.0
// (http://www.apache.org/licenses/LICENSE-2.0). Dev tool for mcopt native shading, not part of the mod.
// Writes own/terrain.metal as OwnTerrain builds it (indie defines), with and without the shade prefix (ShadeMsl.ownPrefix).
import java.nio.file.*;
import mcopt.metal.shade.ShadeMsl;

public class OwnDump {
	public static void main(String[] a) throws Exception {
		Path out = Path.of(a[0]);
		Files.createDirectories(out);
		String terrain = new String(OwnDump.class.getResourceAsStream("/mcopt/own/terrain.metal").readAllBytes());
		// indie: mesh.exactPos (OWN_EXACT_POS) and mesh.qrec (OWN_QREC) on; vcount, tflat, tprobe off; A1_SH default
		String defines = "#define OWN_EXACT_POS 1\n#define OWN_QREC 1\n";
		Files.writeString(out.resolve("own_plain.metal"), defines + terrain);
		Files.writeString(out.resolve("own_plain_nodef.metal"), terrain);
		String prefix = ShadeMsl.ownPrefix();
		Files.writeString(out.resolve("own_shade.metal"), defines + prefix + terrain);
		Files.writeString(out.resolve("own_shade_nodef.metal"), prefix + terrain);
		// with the weather and water preludes too (the plan's full prefix; P2/P3 decide)
		Files.writeString(out.resolve("own_shade_full.metal"), defines + prefix + mcopt.metal.shade.weather.ShadeWeather.prelude(false)
			+ mcopt.metal.shade.water.ShadeWater.prelude() + terrain);
		System.out.println("prefix " + prefix.length() + " chars, terrain " + terrain.length());
	}
}
