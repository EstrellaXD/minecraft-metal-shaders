// Copyright 2026 mcopt-shade contributors. Licensed under the Apache License, Version 2.0
// (http://www.apache.org/licenses/LICENSE-2.0). Dev tool for mcopt native shading's clouds, not part of the mod.
import java.nio.file.*;

/** Writes the cloud passes' MSL (+ the preview composite) and the sky LUT pass' MSL, as the mod assembles them. */
public class CloudPreviewSource {
	public static void main(String[] a) throws Exception {
		Files.writeString(Path.of(a[0]), mcopt.metal.shade.clouds.CloudConfig.source() + "\n" + Files.readString(Path.of(a[2])));
		Files.writeString(Path.of(a[1]), mcopt.metal.shade.ShadeMsl.lut());
	}
}
