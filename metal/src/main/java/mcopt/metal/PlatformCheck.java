package mcopt.metal;

import net.fabricmc.loader.impl.gui.FabricGuiEntry;
import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;

/**
 * Stops the launch, before any Metal class loads, on a machine the native library can't run on: it is arm64-only and
 * the mod supports macOS 15 and later (mcopt upstream asks for 26; the native library is built for 15.0, the shaders use
 * Metal Shading Language 3.1, which macOS 14 already has, and the one macOS 26 API, MetalFX frame interpolation, is checked
 * with @available and stays off before 26: a player ran it on Sequoia with -Dmcopt.platformCheck.fake=16.0, 2026-10-13). The message goes to the log and to Fabric's own error dialog (the one it shows
 * for a missing dependency), then the game exits. A plain exception from preLaunch would only show up as "A mod crashed
 * on startup!" with the reason buried in a stack trace. -Dmcopt.platformCheck.fake=linux (or x86_64, or a macOS
 * version like 15.5) pretends to be that platform, to test the message.
 */
final class PlatformCheck {
	private PlatformCheck() {
	}

	static void run() {
		String os = System.getProperty("os.name", ""), arch = System.getProperty("os.arch", ""), version = System.getProperty("os.version", "");
		String fake = System.getProperty("mcopt.platformCheck.fake");
		if (fake != null) {
			if (fake.matches("[0-9.]+")) version = fake;
			else if (fake.equals("x86_64") || fake.equals("amd64")) arch = fake;
			else os = fake;
		}
		String problem = problem(os, arch, version);
		if (problem == null) return;
		String message = "mcopt Metal can't run here. " + problem
			+ " To play on this computer, remove the mcopt-metal jar from the mods folder.";
		Log.error(LogCategory.GENERAL, message);
		try {
			FabricGuiEntry.displayError(message, null, true); // exits the game
		} catch (Throwable t) {
			throw new IllegalStateException(message, t);
		}
		throw new IllegalStateException(message);
	}

	private static String problem(String os, String arch, String version) {
		if (!os.startsWith("Mac")) return "It needs an Apple Silicon Mac (M1 or newer) with macOS 15 or later, and this computer runs " + os + ".";
		if (!arch.equals("aarch64") && !arch.equals("arm64")) {
			return "It needs an Apple Silicon Mac (M1 or newer) and an Apple Silicon (arm64) Java, and this Java is " + arch
				+ " (an Intel Mac, or an Intel Java running through Rosetta).";
		}
		int major;
		try {
			major = Integer.parseInt(version.split("\\.")[0]);
		} catch (NumberFormatException e) {
			return null; // unknown version string: let it try rather than block a working Mac
		}
		// A JDK built against an older SDK may see macOS 26 as "16.x" (Apple's compatibility numbering); there was no macOS 16.
		// (2026-10-13) 15 (Sequoia) and later; mcopt.platformCheck.minMacos (15) moves the floor for testing
		int min = Integer.getInteger("mcopt.platformCheck.minMacos", 15);
		if (major < min) return "It needs macOS " + min + " or later, and this Mac runs macOS " + version + ". Update macOS in System Settings > General > Software Update.";
		return null;
	}
}
