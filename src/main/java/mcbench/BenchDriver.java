/*
 * Copyright 2026 mcbench contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package mcbench;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.LongFunction;
import mcbench.Spot.Pose;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;

/**
 * The bench: a list of steps polled once per frame on the client thread (Minecraft.runTick HEAD). Everything is driven
 * by JVM properties:
 * <ul>
 * <li>{@code -Dmcbench.out=<dir>} (required; without it the mod's mixins don't even apply)</li>
 * <li>{@code -Dmcbench.scenes=all|perf|shots} (default all: perf first, then shots)</li>
 * <li>{@code -Dmcbench.seconds=N} seconds for each perf segment: 360 deg spin, then straight flight (default 20)</li>
 * <li>{@code -Dmcbench.world=NAME} (default mcbench-world), {@code -Dmcbench.seed=LONG} (default 20261006, only used when
 * the world is created)</li>
 * <li>{@code -Dmcbench.shotWait=S} hold before each screenshot (default 2), {@code -Dmcbench.flightSpeed=B} blocks/s (10),
 * {@code -Dmcbench.timeoutSec=S} in-game watchdog (default 900), {@code -Dmcbench.label=TEXT}</li>
 * <li>{@code -Dmcbench.stress=reload,resize,nether} (opt-in, default none): robustness events after perf, before shots</li>
 * <li>{@code -Dmcbench.godRays=true} (opt-in): god-ray screenshots found in the world after the standard shots (GodRayShots)</li>
 * <li>{@code -Dmcbench.water=true} (opt-in): water screenshots found in the world after the standard shots (WaterShots)</li>
 * <li>{@code -Dmcbench.standardShots=false} (opt-in): skip the five standard screenshots</li>
 * <li>{@code -Dmcbench.motionShots=forest;overview;sunset} (opt-in): screenshots taken while the camera moves (MotionShots)</li>
 * <li>{@code -Dmcbench.dims=true|nether,end,aurora,lava,snow} (opt-in): Nether, End, aurora and camera-media screenshots (DimShots)</li>
 * <li>{@code -Dmcbench.entities=true} or {@code noon,evening,night,hand,motion} (opt-in): mobs, block entities, items, the hand (EntityShots)</li>
 * <li>{@code -Dmcbench.dynLights=true} or {@code field,mobs,perf,cave,walk,nether} (opt-in): held and entity dynamic lights, perf phase "lights" (LightShots)</li>
 * <li>{@code -Dmcbench.robust=gui,transp,dims,resize,rejoin,fail} (opt-in): GUI over the world, improved transparency, dimension changes, window resize, leave and rejoin, injected shader failures (RobustShots)</li>
 * </ul>
 * Output: results.json, frametimes.csv, screenshots/*.png, gputimes.csv (with mcopt -Dmcopt.metal.gpuTimes=true),
 * error.txt on failure. The game then quits (Minecraft.stop).
 */
public final class BenchDriver {
	static final String VERSION = "0.1.0";
	static final String OUT_PROP = System.getProperty("mcbench.out");
	static final boolean ON = OUT_PROP != null && !OUT_PROP.isBlank();
	static final String SCENES = System.getProperty("mcbench.scenes", "all").trim();
	static final double SECONDS = dbl("mcbench.seconds", 20);
	static final String WORLD = System.getProperty("mcbench.world", "mcbench-world");
	static final long SEED = Long.getLong("mcbench.seed", 20261006L);
	static final double SHOT_WAIT = dbl("mcbench.shotWait", 2);
	/** -Dmcbench.standardShots=false skips the five standard screenshots (the opt-in ones still run). Default true. */
	static final boolean STANDARD_SHOTS = Boolean.parseBoolean(System.getProperty("mcbench.standardShots", "true"));
	static final double TIMEOUT = dbl("mcbench.timeoutSec", 900);
	static final double SPEED = dbl("mcbench.flightSpeed", 10);
	static final String LABEL = System.getProperty("mcbench.label", "");
	/** Target display: builtin (default; the Retina panel), main (SDL primary display), keep, or a name substring. */
	static final String DISPLAY = System.getProperty("mcbench.display", "builtin").trim();
	/** Go fullscreen on the target display after moving there (default true). Start the game windowed. */
	static final boolean FULLSCREEN = !"false".equals(System.getProperty("mcbench.fullscreen", "true"));
	/**
	 * Opt-in robustness events between perf and shots, comma-separated, in the order given: {@code reload} (F3+T: a full
	 * resource reload), {@code resize} (leave fullscreen, then go back: two framebuffer resizes), {@code nether} (go to
	 * the Nether for a few seconds and come back: two dimension changes). Empty (default): no extra steps.
	 */
	static final List<String> STRESS = java.util.Arrays.stream(System.getProperty("mcbench.stress", "").split(","))
		.map(String::trim).filter(x -> !x.isEmpty()).toList();

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().serializeSpecialFloatingPointValues().create();
	private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "mcbench-io");
		t.setDaemon(true);
		return t;
	});

	static Path out;
	static final long T0 = System.nanoTime();
	static boolean initialized;
	static volatile boolean finished; // results.json or error.txt written
	static volatile String phase = "BOOT";
	static final Deque<Step> steps = new ArrayDeque<>();
	static Step cur;
	static long curStart;
	/** The camera pose as a function of System.nanoTime(), or null: the player isn't touched. */
	static LongFunction<Pose> pose;
	static Spot spot;
	static boolean worldCreated;
	static final Map<String, Object> results = new LinkedHashMap<>();
	static final List<Object> settles = new ArrayList<>();
	static final List<Object> shots = new ArrayList<>();
	static final List<Object> stress = new ArrayList<>();
	static final List<String> warnings = new ArrayList<>();
	static final Map<String, long[]> phaseRanges = new LinkedHashMap<>();
	// frame log: one entry per frame interval recorded in a perf phase
	static final int CAP = 1 << 20;
	static final long[] frameNs = new long[CAP], frameAt = new long[CAP];
	static final byte[] framePhase = new byte[CAP];
	static int frames;
	static final List<String> REC_PHASES = List.of("spin", "flight", "zoo", "zoo_noshadow", "zoo2", "lights", "lights_off", "lights2", "nether", "end", "nether_off", "nether2", "end_off", "end2", "xdr", "xdr_off", "xdr2", "wxdry", "wxrain", "wxdry_notint", "wxdry2", "wxrain_nossr", "wxrain_nogate", "wxrain_analytic", "wxrain_noripple", "wxrain_plainrain", "wxrain_dry", "wxrain2", "wxrain_c32", "wxrain_c64", "wxrain_c128", "wxrain_c224", "wxrain_notint", "clouds", "clouds_off", "clouds2", "clouds_d1", "clouds_d2", "clouds_d3", "clouds_d4", "clouds_d5", "clouds_d6", "clouds_d7", "clouds_d8", "ab1", "ab2", "ab3", "ab4", "ab5", "ab6", "ab7", "ab8", "ab9", "ab10", "ab11", "ab12", "ab13", "ab14", "ab15", "ab16", "ab17", "ab18", "ab19", "ab20", "ab21", "ab22", "ab23", "ab24"); // ab*: MiscShots' generic A/B phases (opt-in) // clouds*: CloudShots' perf A/B (opt-in) // wxdry, wxrain: WeatherShots' perf pans (opt-in) // nether, end: DimShots' perf spins (opt-in) // zoo: EntityShots' perf segment at the entity site; lights: LightShots' (opt-in)
	static int recPhase = -1, prevRecPhase = -1;
	static long lastFrame;

	private BenchDriver() {
	}

	static double dbl(String k, double d) {
		try {
			return Double.parseDouble(System.getProperty(k, Double.toString(d)));
		} catch (NumberFormatException e) {
			return d;
		}
	}

	static double secs(long ns) {
		return ns / 1e9;
	}

	static void setPhase(String p) {
		phase = p;
		mcopt.bench.Bench.phase = p;
	}

	// ------------------------------------------------------------------------------------------------ entry points

	public static void frame(Minecraft mc) {
		if (!ON || finished) return;
		long now = System.nanoTime();
		if (lastFrame != 0 && prevRecPhase >= 0 && frames < CAP) {
			frameNs[frames] = now - lastFrame;
			frameAt[frames] = lastFrame;
			framePhase[frames] = (byte) prevRecPhase;
			frames++;
		}
		lastFrame = now;
		try {
			if (!initialized) init(mc);
			step(mc, now);
			applyPose(mc, now);
		} catch (Throwable t) {
			fail(mc, "exception in step " + (cur == null ? "-" : cur.name), t);
		}
		prevRecPhase = recPhase;
	}

	public static void beforeRender(Minecraft mc) {
		if (!ON || finished || pose == null) return;
		try {
			applyPose(mc, System.nanoTime());
		} catch (Throwable t) {
			fail(mc, "exception applying pose", t);
		}
	}

	// ------------------------------------------------------------------------------------------------ lifecycle

	static void init(Minecraft mc) throws IOException {
		initialized = true;
		out = Paths.get(OUT_PROP).toAbsolutePath();
		Files.createDirectories(out.resolve("screenshots"));
		Files.deleteIfExists(out.resolve("results.json"));
		Files.deleteIfExists(out.resolve("error.txt"));
		log("start: out=" + out + " scenes=" + SCENES + " seconds=" + SECONDS + " world=" + WORLD);
		results.put("mcbenchVersion", VERSION);
		results.put("label", LABEL);
		results.put("startedAt", OffsetDateTime.now().toString());
		results.put("scenes", SCENES);
		results.put("seconds", SECONDS);
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			if (!finished) writeError("game exited before the bench finished (phase " + phase + ", step " + (cur == null ? "-" : cur.name) + ")", null);
		}, "mcbench-exit"));
		Thread watchdog = new Thread(() -> {
			long deadline = T0 + (long) (TIMEOUT * 1e9);
			while (!finished) {
				if (System.nanoTime() > deadline) {
					writeError("mcbench watchdog: no finish after " + TIMEOUT + " s (phase " + phase + ", step " + (cur == null ? "-" : cur.name) + ")", null);
					mc.execute(mc::stop);
					sleep(60_000);
					Runtime.getRuntime().halt(3);
				}
				sleep(1000);
			}
		}, "mcbench-watchdog");
		watchdog.setDaemon(true);
		watchdog.start();
		buildPlan();
	}

	static void buildPlan() {
		boolean perf = SCENES.equals("all") || SCENES.equals("perf");
		boolean shotsOn = SCENES.equals("all") || SCENES.equals("shots");
		if (!perf && !shotsOn) throw new IllegalArgumentException("mcbench.scenes must be all|perf|shots, got " + SCENES);
		steps.add(new WaitBoot());
		steps.add(new PlaceWindow());
		steps.add(new OpenWorld());
		steps.add(new WaitInWorld());
		steps.add(new Setup());
		steps.add(new Torches()); // expands itself only the first time in a world
		if (perf) {
			steps.add(new Scene("perf", 6000, false, () -> spot.overview));
			steps.add(new Settle("perf", false, 3, 150));
			steps.add(new Warmup(2));
			steps.add(new Route("spin", SECONDS));
			steps.add(new Route("flight", SECONDS));
		}
		for (String kind : STRESS) steps.add(new Stress(kind));
		if (shotsOn) {
			if (STANDARD_SHOTS) { // opt-out -Dmcbench.standardShots=false: only the opt-in shots below (quicker look-dev runs)
				shotScene("noon_overview", 6000, false, () -> spot.overview, SHOT_WAIT);
				shotScene("forest_closeup", 6000, false, () -> spot.forest, SHOT_WAIT);
				shotScene("sunset_water", 12400, false, () -> spot.sunset, SHOT_WAIT);
				shotScene("night_torches", 18000, false, () -> spot.night, SHOT_WAIT);
				shotScene("rain", 6000, true, () -> spot.overview, Math.max(SHOT_WAIT, 4));
			}
			extraShots();
			GodRayShots.plan(steps); // opt-in -Dmcbench.godRays=true
			WaterShots.plan(steps); // opt-in -Dmcbench.water=true
			MotionShots.plan(steps); // opt-in -Dmcbench.motionShots=forest;overview;...
			CloudShots.plan(steps); // opt-in -Dmcbench.clouds=all|noon_south,sunset_west,... (sky and cloud scenes)
			EntityShots.plan(steps); // opt-in -Dmcbench.entities=true|noon,evening,night,hand,motion (M3 entities)
			WeatherShots.plan(steps); // opt-in -Dmcbench.weather=true|rain,sunset,night,water,storm,glass
			LightShots.plan(steps); // opt-in -Dmcbench.dynLights=true|field,mobs,perf,cave,walk,nether (M3 dynamic lights)
			DimShots.plan(steps); // opt-in -Dmcbench.dims=true|nether,end,aurora,lava,snow (M3 sky/dimensions)
			UiShots.plan(steps); // opt-in -Dmcbench.ui=true (M3.1: the pause menu's resolution button)
			MiscShots.plan(steps); // opt-in -Dmcbench.ab=... / -Dmcbench.variantShots=... (M3.3 misc: generic A/B phases and variant shots)
			RobustShots.plan(steps); // opt-in -Dmcbench.robust=gui,transp,dims,resize,rejoin,fail (M3.3 robustness)
		}
		// leave the saved world clear at noon: the next run then starts without rain (shader packs smooth wetness/fog
		// over tens of seconds, so a world saved in the rain would haze the next run's first scenes)
		steps.add(new Step("cleanup", 0) {
			@Override
			void start(Minecraft mc, long now) {
				MinecraftServer s = server(mc);
				cmd(s, "weather clear");
				cmd(s, "time set 6000");
			}

			@Override
			boolean poll(Minecraft mc, long now) {
				return secs(now - curStart) >= 1.5;
			}
		});
		steps.add(new Finish());
	}

	/**
	 * Opt-in extra screenshots: -Dmcbench.extraShots="name@time@x,y,z,yaw,pitch[@rain];..." (absolute world pose, game time
	 * of day). Empty by default, so the standard plan is unchanged.
	 */
	static void extraShots() {
		String spec = System.getProperty("mcbench.extraShots", "").trim();
		if (spec.isEmpty()) return;
		for (String one : spec.split(";")) {
			String[] f = one.trim().split("@");
			if (f.length < 3) throw new IllegalArgumentException("mcbench.extraShots: bad entry " + one);
			String[] c = f[2].split(",");
			Pose pose = new Pose(Double.parseDouble(c[0]), Double.parseDouble(c[1]), Double.parseDouble(c[2]), Float.parseFloat(c[3]), Float.parseFloat(c[4]));
			boolean rain = f.length > 3 && f[3].equals("rain");
			shotScene(f[0], Integer.parseInt(f[1]), rain, () -> pose, rain ? Math.max(SHOT_WAIT, 4) : SHOT_WAIT);
		}
	}

	static void shotScene(String name, int time, boolean rain, java.util.function.Supplier<Pose> p, double hold) {
		steps.add(new Scene(name, time, rain, p));
		steps.add(new Settle(name, rain, 3, 120));
		steps.add(new Hold(name, hold));
		steps.add(new Shot(name));
	}

	static void step(Minecraft mc, long now) throws Exception {
		while (true) {
			if (cur == null) {
				cur = steps.poll();
				if (cur == null) return;
				curStart = now;
				cur.start(mc, now);
			}
			if (!cur.poll(mc, now)) {
				if (cur.timeoutSec > 0 && secs(now - curStart) > cur.timeoutSec) cur.onTimeout(mc, now);
				return;
			}
			cur = null; // next step starts in the same frame
		}
	}

	static void fail(Minecraft mc, String what, Throwable t) {
		if (finished) return;
		log("FAILED: " + what + ": " + t);
		writeError(what, t);
		try {
			results.put("error", what + ": " + t);
			writeJson(out.resolve("results-partial.json"), results);
		} catch (Throwable ignored) {
		}
		pose = null;
		mc.stop();
	}

	static synchronized void writeError(String what, Throwable t) {
		if (finished) return;
		finished = true;
		try {
			StringWriter sw = new StringWriter();
			PrintWriter pw = new PrintWriter(sw);
			pw.println("mcbench error: " + what);
			pw.println("phase: " + phase);
			pw.println("elapsed: " + String.format("%.1f s", secs(System.nanoTime() - T0)));
			if (t != null) t.printStackTrace(pw);
			pw.flush();
			Path dir = out != null ? out : Paths.get(OUT_PROP);
			Files.createDirectories(dir);
			Files.writeString(dir.resolve("error.txt"), sw.toString(), StandardCharsets.UTF_8);
		} catch (Throwable e) {
			e.printStackTrace();
		}
	}

	static void log(String s) {
		System.out.println("[mcbench] " + String.format("%7.1fs ", secs(System.nanoTime() - T0)) + s);
	}

	static void sleep(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException ignored) {
		}
	}

	// ------------------------------------------------------------------------------------------------ helpers

	static MinecraftServer server(Minecraft mc) {
		MinecraftServer s = mc.getSingleplayerServer();
		if (s == null) throw new IllegalStateException("no integrated server");
		return s;
	}

	static void cmd(MinecraftServer s, String c) {
		s.execute(() -> s.getCommands().performPrefixedCommand(s.createCommandSourceStack().withSuppressedOutput(), c));
	}

	static void applyPose(Minecraft mc, long now) {
		LongFunction<Pose> f = pose;
		LocalPlayer pl = mc.player;
		if (f == null || pl == null) return;
		Pose p = f.apply(now);
		pl.getAbilities().flying = true;
		pl.setDeltaMovement(Vec3.ZERO);
		pl.snapTo(p.x(), p.y(), p.z(), p.yaw(), p.pitch());
		pl.setYHeadRot(p.yaw());
		pl.setYBodyRot(p.yaw());
	}

	static void setStaticPose(MinecraftServer s, Pose p) {
		pose = t -> p;
		cmd(s, String.format(java.util.Locale.ROOT, "tp @a %.3f %.3f %.3f %.2f %.2f", p.x(), p.y(), p.z(), p.yaw(), p.pitch()));
	}

	static void hideHud(Minecraft mc) {
		if (!mc.gui.hud.isHidden()) mc.gui.hud.toggle();
	}

	static Path spotFile(Minecraft mc) {
		return mc.gameDirectory.toPath().resolve("saves").resolve(WORLD).resolve("mcbench-spot.json");
	}

	static void writeJson(Path p, Object o) throws IOException {
		Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
		try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
			GSON.toJson(o, w);
		}
		Files.move(tmp, p, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
	}

	// ------------------------------------------------------------------------------------------------ steps

	abstract static class Step {
		final String name;
		double timeoutSec;

		Step(String name, double timeoutSec) {
			this.name = name;
			this.timeoutSec = timeoutSec;
		}

		void start(Minecraft mc, long now) throws Exception {
		}

		abstract boolean poll(Minecraft mc, long now) throws Exception;

		void onTimeout(Minecraft mc, long now) throws Exception {
			throw new IllegalStateException("step '" + name + "' timed out after " + timeoutSec + " s");
		}
	}

	/** Title screen (or any first screen) with resources loaded and no overlay for 2 s. */
	static final class WaitBoot extends Step {
		long readySince;

		WaitBoot() {
			super("boot", 300);
		}

		@Override
		boolean poll(Minecraft mc, long now) {
			setPhase("BOOT");
			boolean ready = mc.isGameLoadFinished() && mc.gui.overlay() == null && mc.level == null;
			if (!ready) {
				readySince = 0;
				return false;
			}
			if (readySince == 0) readySince = now;
			return secs(now - readySince) >= 2;
		}
	}

	/**
	 * Put the window on the target display (-Dmcbench.display) and go fullscreen there (-Dmcbench.fullscreen). With an
	 * external monitor as the macOS main display the game would otherwise open (and go fullscreen) on that monitor.
	 * Exclusive fullscreen on the built-in 14" panel uses its 1512x982@120 mode = a 3024x1964 framebuffer.
	 */
	static final class PlaceWindow extends Step {
		int state;
		long t;
		int lastW, lastH;
		long sizeSince;
		int target;

		PlaceWindow() {
			super("place-window", 30);
		}

		@Override
		void start(Minecraft mc, long now) {
			target = findDisplay(mc);
			t = now;
		}

		@Override
		boolean poll(Minecraft mc, long now) {
			Window w = mc.getWindow();
			long h = w.handle();
			switch (state) {
				case 0 -> { // leave fullscreen if the options started us there
					if (target != 0 && org.lwjgl.sdl.SDLVideo.SDL_GetDisplayForWindow(h) != target) {
						if (mc.options.fullscreen().get()) {
							w.setFullscreen(false);
							mc.options.fullscreen().set(false);
						}
						state = 1;
					} else state = 2;
					t = now;
				}
				case 1 -> { // move (windowed) once out of fullscreen
					if (secs(now - t) < 1) return false;
					org.lwjgl.sdl.SDLVideo.SDL_SetWindowPosition(h, org.lwjgl.sdl.SDLVideo.SDL_WINDOWPOS_CENTERED_DISPLAY(target),
						org.lwjgl.sdl.SDLVideo.SDL_WINDOWPOS_CENTERED_DISPLAY(target));
					state = 2;
					t = now;
				}
				case 2 -> {
					if (secs(now - t) < 1) return false;
					if (target != 0 && org.lwjgl.sdl.SDLVideo.SDL_GetDisplayForWindow(h) != target)
						warnings.add("window did not move to display " + org.lwjgl.sdl.SDLVideo.SDL_GetDisplayName(target));
					if (FULLSCREEN) w.setFullscreen(true);
					else if (isFullscreen(w)) w.setFullscreen(false);
					state = 3;
					t = now;
					sizeSince = now;
				}
				case 3 -> { // wait for the framebuffer size to settle
					if (w.getWidth() != lastW || w.getHeight() != lastH) {
						lastW = w.getWidth();
						lastH = w.getHeight();
						sizeSince = now;
					}
					if (secs(now - t) >= 2 && secs(now - sizeSince) >= 1.5) {
						log("window on " + org.lwjgl.sdl.SDLVideo.SDL_GetDisplayName(org.lwjgl.sdl.SDLVideo.SDL_GetDisplayForWindow(h)) + ", framebuffer " + lastW + "x" + lastH
							+ (FULLSCREEN ? " fullscreen" : " windowed"));
						return true;
					}
				}
				default -> {
				}
			}
			return false;
		}

		@Override
		void onTimeout(Minecraft mc, long now) {
			warnings.add("place-window timed out in state " + state);
			cur = null;
		}
	}

	static boolean isFullscreen(Window w) {
		return (org.lwjgl.sdl.SDLVideo.SDL_GetWindowFlags(w.handle()) & 1L) != 0; // SDL_WINDOW_FULLSCREEN
	}

	/** 0 = keep the current display. */
	static int findDisplay(Minecraft mc) {
		if (DISPLAY.equals("keep")) return 0;
		if (DISPLAY.equals("main")) return org.lwjgl.sdl.SDLVideo.SDL_GetPrimaryDisplay();
		java.nio.IntBuffer ids = org.lwjgl.sdl.SDLVideo.SDL_GetDisplays();
		if (ids == null) return 0;
		int best = 0;
		float bestScale = 0;
		List<String> names = new ArrayList<>();
		for (int i = 0; i < ids.remaining(); i++) {
			int id = ids.get(ids.position() + i);
			String name = String.valueOf(org.lwjgl.sdl.SDLVideo.SDL_GetDisplayName(id));
			float scale = org.lwjgl.sdl.SDLVideo.SDL_GetDisplayContentScale(id);
			names.add(name + " (scale " + scale + ")");
			if (DISPLAY.equals("builtin")) {
				float score = scale + (name.toLowerCase(java.util.Locale.ROOT).contains("built-in") ? 10 : 0);
				if (score > bestScale) {
					bestScale = score;
					best = id;
				}
			} else if (name.toLowerCase(java.util.Locale.ROOT).contains(DISPLAY.toLowerCase(java.util.Locale.ROOT))) best = id;
		}
		results.put("displaysSeen", names);
		if (best == 0) warnings.add("no display matching '" + DISPLAY + "' among " + names);
		return best;
	}

	static final class OpenWorld extends Step {
		OpenWorld() {
			super("open-world", 0);
		}

		@Override
		void start(Minecraft mc, long now) throws Exception {
			setPhase("LOAD");
			mc.options.pauseOnLostFocus = false;
			hideHud(mc);
			if (mc.getLevelSource().levelExists(WORLD)) {
				log("loading world " + WORLD);
				mc.createWorldOpenFlows().openWorld(WORLD, () -> fail(mc, "openWorld cancelled for " + WORLD, new IllegalStateException("cancelled")));
			} else {
				log("creating world " + WORLD + " seed " + SEED);
				worldCreated = true;
				LevelSettings settings = new LevelSettings(WORLD, GameType.CREATIVE, new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true,
					WorldDataConfiguration.DEFAULT);
				mc.createWorldOpenFlows().createFreshLevel(WORLD, settings, new WorldOptions(SEED, true, false), WorldPresets::createNormalWorldDimensions,
					mc.gui.screen());
			}
		}

		@Override
		boolean poll(Minecraft mc, long now) {
			return true;
		}
	}

	static final class WaitInWorld extends Step {
		long inSince;

		WaitInWorld() {
			super("wait-in-world", 300);
		}

		@Override
		boolean poll(Minecraft mc, long now) {
			if (mc.level == null || mc.player == null || mc.getSingleplayerServer() == null) {
				inSince = 0;
				return false;
			}
			if (inSince == 0) inSince = now;
			if (mc.gui.screen() != null) {
				// the level-loading screen closes itself; anything still up 20 s after joining (pause menu etc.) is closed
				if (secs(now - inSince) > 20) {
					warnings.add("closed unexpected screen " + mc.gui.screen().getClass().getName());
					mc.gui.setScreen(null);
				}
				return false;
			}
			return secs(now - inSince) >= 1;
		}
	}

	/** Gamerules, spectator camera, peaceful; then the bench spot (loaded from the world, or searched once). */
	static final class Setup extends Step {
		CompletableFuture<Spot> future;

		Setup() {
			super("setup", 240);
		}

		@Override
		void start(Minecraft mc, long now) {
			setPhase("SETUP");
			mc.options.pauseOnLostFocus = false;
			hideHud(mc);
			raiseWindow(mc);
			MinecraftServer s = server(mc);
			for (String c : new String[] {"gamerule send_command_feedback false", "gamerule log_admin_commands false", "gamerule advance_time false",
				"gamerule advance_weather false", "gamerule random_tick_speed 0", "gamerule spawn_mobs false", "gamerule spawn_monsters false",
				"gamerule spawn_patrols false", "gamerule spawn_phantoms false", "gamerule spawn_wandering_traders false", "gamerule fire_spread_radius_around_player 0",
				"gamerule player_movement_check false", "gamerule show_advancement_messages false", "difficulty peaceful", "gamemode spectator @a",
				"time set 6000", "weather clear"}) {
				cmd(s, c);
			}
			Path file = spotFile(mc);
			future = CompletableFuture.supplyAsync(() -> {
				try {
					if (Files.isRegularFile(file)) {
						Spot sp = Spot.of(GSON.fromJson(Files.readString(file), Map.class));
						log("spot loaded from " + file);
						return sp;
					}
				} catch (Exception e) {
					log("spot file unreadable, searching again: " + e);
				}
				ServerLevel lvl = s.overworld();
				BlockPos spawn = s.getRespawnData().pos();
				long t = System.nanoTime();
				Spot sp = Spot.find(lvl, spawn.getX(), spawn.getZ());
				log(String.format("spot found at %d,%d (ideal=%s, %d candidates, %.1f s) water %.2f forest %.2f plains %.2f heights %d..%d", sp.cx, sp.cz, sp.ideal,
					sp.candidatesTried, secs(System.nanoTime() - t), sp.waterFrac, sp.forestFrac, sp.plainsFrac, sp.minH, sp.maxH));
				try {
					writeJson(file, sp.json());
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
				return sp;
			}, s);
		}

		@Override
		boolean poll(Minecraft mc, long now) throws Exception {
			if (!future.isDone()) return false;
			spot = future.get();
			if (!spot.ideal) warnings.add("spot is not ideal (biome mix or relief below target)");
			results.put("spot", spot.json());
			return true;
		}
	}

	static void raiseWindow(Minecraft mc) {
		try {
			org.lwjgl.sdl.SDLVideo.SDL_RaiseWindow(mc.getWindow().handle());
		} catch (Throwable t) {
			warnings.add("SDL_RaiseWindow failed: " + t);
		}
	}

	/**
	 * First run in a world only (spot.torchVersion < TORCH_VERSION): load the area around the overview, pick the flattest
	 * dry patch (from the real, generated terrain) near the spot centre, put a 7x7 torch grid (5 apart) on it, aim the
	 * night camera at it, persist. Placing torches before the first perf run keeps every perf run on the same world.
	 */
	static final int TORCH_VERSION = 2;

	static final class Torches extends Step {
		Torches() {
			super("torches", 0);
		}

		@Override
		boolean poll(Minecraft mc, long now) {
			if (spot.torchesPlaced && spot.torchVersion >= TORCH_VERSION) return true;
			MinecraftServer s = server(mc);
			List<Step> extra = new ArrayList<>();
			extra.add(new Scene("torch-setup", 6000, false, () -> spot.overview));
			extra.add(new Settle("torch-setup", false, 3, 180));
			extra.add(new Step("place-torches", 60) {
				CompletableFuture<int[]> f;

				@Override
				void start(Minecraft mc2, long now2) {
					f = CompletableFuture.supplyAsync(() -> placeTorches(s.overworld()), s);
				}

				@Override
				boolean poll(Minecraft mc2, long now2) throws Exception {
					if (!f.isDone()) return false;
					int[] r = f.get();
					log("torch site " + r[1] + "," + r[2] + " y" + r[3] + ": placed " + r[0] + " torches");
					results.put("torchesPlacedThisRun", r[0]);
					spot.torchesPlaced = true;
					spot.torchVersion = TORCH_VERSION;
					writeJson(spotFile(mc2), spot.json());
					results.put("spot", spot.json());
					return true;
				}
			});
			for (int i = extra.size() - 1; i >= 0; i--) steps.addFirst(extra.get(i));
			return true;
		}
	}

	/** Surface y (first non-solid above ground) if the column is loaded dry land, else MIN_VALUE. */
	static int landY(ServerLevel lvl, int x, int z) {
		if (!lvl.hasChunk(x >> 4, z >> 4)) return Integer.MIN_VALUE;
		int y = lvl.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
		BlockPos pos = new BlockPos(x, y, z), below = pos.below();
		if (!lvl.getFluidState(below).isEmpty() || !lvl.getFluidState(pos).isEmpty()) return Integer.MIN_VALUE;
		if (!lvl.getBlockState(pos).canBeReplaced()) return Integer.MIN_VALUE;
		if (!lvl.getBlockState(below).isFaceSturdy(lvl, below, Direction.UP)) return Integer.MIN_VALUE;
		return y;
	}

	/** Server thread. Returns {placed, x, z, y}. Also sets spot.torchX/Z and spot.night. */
	static int[] placeTorches(ServerLevel lvl) {
		int bestScore = -1, bx = spot.cx, bz = spot.cz, by = 64;
		for (int x = spot.cx - 112; x <= spot.cx + 112; x += 4)
			for (int z = spot.cz - 112; z <= spot.cz + 112; z += 4) {
				int yc = landY(lvl, x, z);
				if (yc == Integer.MIN_VALUE) continue;
				int score = 0;
				for (int i = -3; i <= 3; i++)
					for (int j = -3; j <= 3; j++) {
						int y = landY(lvl, x + i * 5, z + j * 5);
						if (y != Integer.MIN_VALUE && Math.abs(y - yc) <= 2) score += 2;
					}
				score -= (Math.abs(x - spot.cx) + Math.abs(z - spot.cz)) / 32; // prefer near the centre
				if (score > bestScore) {
					bestScore = score;
					bx = x;
					bz = z;
					by = yc;
				}
			}
		int n = 0;
		for (int i = -3; i <= 3; i++)
			for (int j = -3; j <= 3; j++) {
				int x = bx + i * 5, z = bz + j * 5, y = landY(lvl, x, z);
				if (y == Integer.MIN_VALUE || Math.abs(y - by) > 3) continue;
				lvl.setBlock(new BlockPos(x, y, z), Blocks.TORCH.defaultBlockState(), 3);
				n++;
			}
		spot.torchX = bx;
		spot.torchZ = bz;
		int camGround = Math.max(by, lvl.getHeight(Heightmap.Types.MOTION_BLOCKING, bx - 22, bz - 22));
		spot.night = Pose.lookAt(bx - 22 + 0.5, camGround + 8 + Spot.EYE, bz - 22 + 0.5, bx + 0.5, by, bz + 0.5);
		return new int[] {n, bx, bz, by};
	}

	/** Set time + weather and the camera for a scene. */
	/** -Dmcbench.stress: one robustness event (reload, resize or nether), recorded in results.json "stress". */
	static final class Stress extends Step {
		final String kind;
		int state;
		long t, sizeSince;
		int lastW, lastH;
		CompletableFuture<Void> reload;
		final List<String> notes = new ArrayList<>();

		Stress(String kind) {
			super("stress-" + kind, 180);
			this.kind = kind;
			if (!List.of("reload", "resize", "nether").contains(kind)) throw new IllegalArgumentException("mcbench.stress: unknown event " + kind);
		}

		@Override
		void start(Minecraft mc, long now) {
			setPhase("SETTLE");
			t = now;
			log("stress " + kind + ": start");
			switch (kind) {
				case "reload" -> reload = mc.reloadResourcePacks();
				case "resize" -> {
					Window w = mc.getWindow();
					lastW = w.getWidth();
					lastH = w.getHeight();
					notes.add("from " + lastW + "x" + lastH + (isFullscreen(w) ? " fullscreen" : " windowed"));
					w.setFullscreen(!isFullscreen(w));
					sizeSince = now;
				}
				case "nether" -> {
					pose = null;
					cmd(server(mc), "execute in minecraft:the_nether run tp @a 0.5 100 0.5 0 0");
				}
				default -> {
				}
			}
		}

		boolean sizeStable(Window w, long now) {
			if (w.getWidth() != lastW || w.getHeight() != lastH) {
				lastW = w.getWidth();
				lastH = w.getHeight();
				sizeSince = now;
			}
			return secs(now - t) >= 2 && secs(now - sizeSince) >= 1.5;
		}

		@Override
		boolean poll(Minecraft mc, long now) {
			switch (kind) {
				case "reload" -> {
					if (!reload.isDone()) return false;
					if (reload.isCompletedExceptionally()) throw new IllegalStateException("resource reload failed: " + reload.exceptionNow());
					if (mc.gui.overlay() != null) {
						t = now;
						return false;
					}
					if (secs(now - t) < 2) return false;
				}
				case "resize" -> {
					Window w = mc.getWindow();
					if (!sizeStable(w, now)) return false;
					if (state == 0) {
						notes.add("to " + lastW + "x" + lastH + (isFullscreen(w) ? " fullscreen" : " windowed"));
						w.setFullscreen(!isFullscreen(w));
						state = 1;
						t = sizeSince = now;
						return false;
					}
					notes.add("back to " + lastW + "x" + lastH + (isFullscreen(w) ? " fullscreen" : " windowed"));
				}
				case "nether" -> {
					String dim = mc.level == null ? "" : mc.level.dimension().toString();
					if (state == 0) {
						if (!dim.contains("the_nether")) return false;
						if (t != -1) {
							notes.add("in the Nether after " + Math.round(secs(now - curStart) * 10) / 10.0 + " s");
							t = -1;
							sizeSince = now;
						}
						if (secs(now - sizeSince) < 8) return false;
						Pose p = spot.overview;
						cmd(server(mc), String.format(java.util.Locale.ROOT, "execute in minecraft:overworld run tp @a %.3f %.3f %.3f %.2f %.2f", p.x(), p.y(), p.z(), p.yaw(), p.pitch()));
						state = 1;
						sizeSince = now;
						return false;
					}
					if (!dim.contains("overworld")) return false;
					if (state == 1) {
						notes.add("back in the overworld after " + Math.round(secs(now - curStart) * 10) / 10.0 + " s");
						state = 2;
						sizeSince = now;
					}
					if (secs(now - sizeSince) < 3) return false;
				}
				default -> {
				}
			}
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("event", kind);
			m.put("seconds", Math.round(secs(now - curStart) * 10) / 10.0);
			m.put("notes", notes);
			stress.add(m);
			log("stress " + kind + ": done in " + m.get("seconds") + " s " + notes);
			return true;
		}
	}

	static final class Scene extends Step {
		final int time;
		final boolean rain;
		final java.util.function.Supplier<Pose> p;

		Scene(String name, int time, boolean rain, java.util.function.Supplier<Pose> p) {
			super(name, 0);
			this.time = time;
			this.rain = rain;
			this.p = p;
		}

		@Override
		void start(Minecraft mc, long now) {
			setPhase("SETTLE");
			mcopt.bench.Bench.settledAt = 0;
			MinecraftServer s = server(mc);
			cmd(s, "time set " + time);
			cmd(s, rain ? "weather rain" : "weather clear");
			hideHud(mc);
			setStaticPose(s, p.get());
			log("scene " + name + " pose " + p.get());
		}

		@Override
		boolean poll(Minecraft mc, long now) {
			return true;
		}
	}

	/**
	 * Wait until the world around the camera is loaded and built: client chunk count unchanged for 2 s, the renderer
	 * reports every visible section built (Sodium: isTerrainRenderComplete) for 1.5 s, rain level at its target, and at
	 * least minSec. Times out (with a warning, not a failure) after maxSec.
	 */
	static final class Settle extends Step {
		final String scene;
		final boolean rain;
		final double minSec;
		int lastChunks = -1;
		long chunkChange, notBuilt, rainWrong;

		Settle(String scene, boolean rain, double minSec, double maxSec) {
			super("settle-" + scene, maxSec);
			this.scene = scene;
			this.rain = rain;
			this.minSec = minSec;
		}

		@Override
		void start(Minecraft mc, long now) {
			setPhase("SETTLE");
			chunkChange = notBuilt = rainWrong = now;
		}

		@Override
		boolean poll(Minecraft mc, long now) {
			if (mc.level == null) return false;
			int chunks = mc.level.getChunkSource().getLoadedChunksCount();
			if (chunks != lastChunks) {
				lastChunks = chunks;
				chunkChange = now;
			}
			if (!mc.levelRenderer.hasRenderedAllSections()) notBuilt = now;
			float r = mc.level.getRainLevel(1f);
			if (rain ? r < 0.999f : r > 0.001f) rainWrong = now;
			boolean done = secs(now - curStart) >= minSec && secs(now - chunkChange) >= 2 && secs(now - notBuilt) >= 1.5 && secs(now - rainWrong) >= 0.5;
			if (done) record(now, false);
			return done;
		}

		void record(long now, boolean timedOut) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("scene", scene);
			m.put("seconds", Math.round(secs(now - curStart) * 10) / 10.0);
			m.put("timedOut", timedOut);
			m.put("clientChunks", lastChunks);
			settles.add(m);
			mcopt.bench.Bench.settledAt = now;
			log("settled " + scene + " in " + m.get("seconds") + " s" + (timedOut ? " (TIMED OUT)" : "") + ", " + lastChunks + " chunks");
		}

		@Override
		void onTimeout(Minecraft mc, long now) {
			record(now, true);
			warnings.add("settle timed out for " + scene + " after " + timeoutSec + " s");
			cur = null; // move on
		}
	}

	static final class Hold extends Step {
		final double sec;

		Hold(String scene, double sec) {
			super("hold-" + scene, 0);
			this.sec = sec;
		}

		@Override
		boolean poll(Minecraft mc, long now) {
			// a screen opened by a stray key press (the game has the keyboard while it is fullscreen on the built-in panel,
			// e.g. E = inventory) would end up in the shot: close it and hold again
			if (mc.gui.screen() != null) {
				warnings.add("closed screen " + mc.gui.screen().getClass().getSimpleName() + " before shot " + name.substring(5));
				mc.gui.setScreen(null);
				hideHud(mc);
				curStart = now;
				return false;
			}
			return secs(now - curStart) >= sec;
		}
	}

	static final class Warmup extends Step {
		final double sec;

		Warmup(double sec) {
			super("warmup", 0);
			this.sec = sec;
		}

		@Override
		void start(Minecraft mc, long now) {
			setPhase("WARMUP");
			results.put("display", display(mc));
			results.put("options", options(mc));
		}

		@Override
		boolean poll(Minecraft mc, long now) {
			return secs(now - curStart) >= sec;
		}
	}

	/** A recorded perf segment: spin (360 deg yaw at the overview spot) or flight (straight, constant altitude). */
	static final class Route extends Step {
		final double sec;
		long end;

		Route(String name, double sec) {
			super(name, 0);
			this.sec = sec;
		}

		@Override
		void start(Minecraft mc, long now) {
			setPhase(name.toUpperCase(java.util.Locale.ROOT));
			Pose o = spot.overview;
			long t0 = now;
			if (name.equals("spin")) {
				pose = t -> new Pose(o.x(), o.y(), o.z(), (float) (o.yaw() + 360.0 * Math.min(1, secs(t - t0) / sec)), 10f);
			} else {
				double yaw = Math.toRadians(spot.flightYaw);
				double dx = -Math.sin(yaw), dz = Math.cos(yaw);
				pose = t -> {
					double d = SPEED * Math.min(sec, secs(t - t0));
					return new Pose(o.x() + dx * d, o.y(), o.z() + dz * d, (float) spot.flightYaw, 12f);
				};
			}
			recPhase = REC_PHASES.indexOf(name);
			phaseRanges.put(name, new long[] {now, 0});
			log("perf " + name + " " + sec + " s");
		}

		@Override
		boolean poll(Minecraft mc, long now) {
			if (secs(now - curStart) < sec) return false;
			recPhase = -1;
			phaseRanges.get(name)[1] = now;
			Pose last = pose.apply(now);
			pose = t -> last;
			return true;
		}
	}

	static final class Shot extends Step {
		volatile boolean done;
		volatile Throwable error;
		int w, h;

		Shot(String scene) {
			super("shot-" + scene, 60);
		}

		@Override
		void start(Minecraft mc, long now) {
			setPhase("SHOT");
			String scene = name.substring(5);
			Path file = out.resolve("screenshots").resolve(scene + ".png");
			RenderTarget target = mc.gameRenderer.mainRenderTarget();
			w = target.width;
			h = target.height;
			Screenshot.takeScreenshot(target, image -> IO.execute(() -> {
				try (image) {
					image.writeToFile(file);
				} catch (Throwable t) {
					error = t;
				}
				done = true;
			}));
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("name", scene);
			m.put("file", "screenshots/" + scene + ".png");
			m.put("width", w);
			m.put("height", h);
			m.put("rainLevel", mc.level.getRainLevel(1f));
			Pose p = pose == null ? null : pose.apply(now);
			if (p != null) m.put("pose", p.json());
			shots.add(m);
		}

		@Override
		boolean poll(Minecraft mc, long now) throws Exception {
			if (!done) return false;
			if (error != null) throw new IOException("screenshot write failed", error);
			log("screenshot " + name + " " + w + "x" + h);
			return true;
		}
	}

	static final class Finish extends Step {
		Finish() {
			super("finish", 0);
		}

		@Override
		boolean poll(Minecraft mc, long now) throws Exception {
			setPhase("DONE");
			pose = null;
			results.putIfAbsent("display", display(mc));
			results.putIfAbsent("options", options(mc));
			results.put("environment", environment());
			results.put("world", Map.of("name", WORLD, "seed", SEED, "createdThisRun", worldCreated));
			results.put("settle", settles);
			if (!phaseRanges.isEmpty()) {
				results.put("perf", perfStats());
				writeFrameCsv();
				Object gpu = mcoptGpu();
				if (gpu != null) results.put("gpu", gpu);
			}
			results.put("screenshots", shots);
			if (!STRESS.isEmpty()) results.put("stress", stress);
			results.put("warnings", warnings);
			results.put("totalSeconds", Math.round(secs(now - T0) * 10) / 10.0);
			writeJson(out.resolve("results.json"), results);
			finished = true;
			log("done: " + out.resolve("results.json"));
			mc.stop();
			return true;
		}
	}

	// ------------------------------------------------------------------------------------------------ reporting

	static Map<String, Object> display(Minecraft mc) {
		Window w = mc.getWindow();
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("framebufferWidth", w.getWidth());
		m.put("framebufferHeight", w.getHeight());
		m.put("windowWidthPoints", w.getScreenWidth());
		m.put("windowHeightPoints", w.getScreenHeight());
		m.put("pixelDensity", w.getPixelDensity());
		RenderTarget t = mc.gameRenderer.mainRenderTarget();
		m.put("mainTargetWidth", t.width);
		m.put("mainTargetHeight", t.height);
		m.put("fullscreenOption", mc.options.fullscreen().get());
		m.put("exclusiveFullscreen", mc.options.exclusiveFullscreen().get());
		m.put("windowFocused", w.isFocused());
		try {
			int d = org.lwjgl.sdl.SDLVideo.SDL_GetDisplayForWindow(w.handle());
			m.put("displayName", org.lwjgl.sdl.SDLVideo.SDL_GetDisplayName(d));
			m.put("displayContentScale", org.lwjgl.sdl.SDLVideo.SDL_GetDisplayContentScale(d));
			var mode = w.getActiveVideoMode();
			if (mode != null) m.put("displayMode", mode.toString());
		} catch (Throwable e) {
			m.put("displayName", "unknown: " + e);
		}
		m.put("windowFullscreen", isFullscreen(w));
		try {
			var info = RenderSystem.getDevice().getDeviceInfo();
			m.put("gpuBackend", info.backendName());
			m.put("gpuName", info.name());
			m.put("gpuDriver", info.driverInfo());
		} catch (Throwable e) {
			m.put("gpuBackend", "unknown: " + e);
		}
		return m;
	}

	static Map<String, Object> options(Minecraft mc) {
		Map<String, Object> m = new LinkedHashMap<>();
		var o = mc.options;
		m.put("renderDistance", o.renderDistance().get());
		m.put("simulationDistance", o.simulationDistance().get());
		m.put("maxFps", o.framerateLimit().get());
		m.put("vsync", o.enableVsync().get());
		m.put("graphicsPreset", String.valueOf(o.graphicsPreset().get()));
		m.put("ambientOcclusion", o.ambientOcclusion().get());
		m.put("clouds", String.valueOf(o.cloudStatus().get()));
		m.put("guiScale", o.guiScale().get());
		return m;
	}

	static Map<String, Object> environment() {
		Map<String, Object> m = new LinkedHashMap<>();
		Map<String, String> mods = new LinkedHashMap<>();
		for (ModContainer c : FabricLoader.getInstance().getAllMods()) {
			String id = c.getMetadata().getId();
			if (id.equals("java") || id.startsWith("fabric-") && !id.equals("fabric-api") || id.equals("mixinextras")) continue;
			mods.put(id, c.getMetadata().getVersion().getFriendlyString());
		}
		m.put("mods", mods);
		m.put("java", System.getProperty("java.version") + " " + System.getProperty("java.vendor"));
		List<String> jvm = new ArrayList<>();
		for (String a : ManagementFactory.getRuntimeMXBean().getInputArguments())
			if (a.startsWith("-Dmcopt") || a.startsWith("-Dmcbench") || a.startsWith("-Diris") || a.startsWith("-Dsodium") || a.startsWith("-Xm") || a.startsWith("-XX"))
				jvm.add(a);
		m.put("jvmArgs", jvm);
		m.put("mcoptMetal", System.getProperty("mcopt.metal", "(default)"));
		Path iris = FabricLoader.getInstance().getConfigDir().resolve("iris.properties");
		if (FabricLoader.getInstance().isModLoaded("iris") && Files.isRegularFile(iris)) {
			try {
				java.util.Properties p = new java.util.Properties();
				try (var r = Files.newBufferedReader(iris)) {
					p.load(r);
				}
				m.put("irisShaderPack", p.getProperty("shaderPack", ""));
				m.put("irisEnableShaders", p.getProperty("enableShaders", ""));
			} catch (IOException ignored) {
			}
		}
		m.put("cpuCores", Runtime.getRuntime().availableProcessors());
		return m;
	}

	static Map<String, Object> perfStats() {
		Map<String, Object> m = new LinkedHashMap<>();
		long[] all = new long[frames];
		for (int p = 0; p < REC_PHASES.size(); p++) {
			int n = 0;
			for (int i = 0; i < frames; i++)
				if (framePhase[i] == p) all[n++] = frameNs[i];
			if (n > 0) m.put(REC_PHASES.get(p), stats(Arrays.copyOf(all, n)));
		}
		m.put("all", stats(Arrays.copyOf(frameNs, frames)));
		return m;
	}

	static Map<String, Object> stats(long[] ns) {
		Map<String, Object> m = new LinkedHashMap<>();
		int n = ns.length;
		m.put("frames", n);
		if (n == 0) return m;
		long sum = 0;
		for (long v : ns) sum += v;
		long[] s = ns.clone();
		Arrays.sort(s);
		m.put("seconds", r3(sum / 1e9));
		m.put("avgFps", r1(n * 1e9 / sum));
		m.put("low1Fps", r1(low(s, 0.01)));
		m.put("low01Fps", r1(low(s, 0.001)));
		m.put("p50Ms", r3(pct(s, 0.50) / 1e6));
		m.put("p95Ms", r3(pct(s, 0.95) / 1e6));
		m.put("p99Ms", r3(pct(s, 0.99) / 1e6));
		m.put("maxMs", r3(s[n - 1] / 1e6));
		m.put("meanMs", r3(sum / 1e6 / n));
		return m;
	}

	static double pct(long[] sorted, double q) {
		int i = (int) Math.ceil(q * sorted.length) - 1;
		return sorted[Math.max(0, Math.min(sorted.length - 1, i))];
	}

	/** "1% low" fps: frames per second over the slowest fraction of frames (mean of the worst 1% frame times). */
	static double low(long[] sorted, double fraction) {
		int k = Math.max(1, (int) (sorted.length * fraction));
		long sum = 0;
		for (int i = sorted.length - k; i < sorted.length; i++) sum += sorted[i];
		return k * 1e9 / sum;
	}

	static double r1(double v) {
		return Math.round(v * 10) / 10.0;
	}

	static double r3(double v) {
		return Math.round(v * 1000) / 1000.0;
	}

	static void writeFrameCsv() throws IOException {
		StringBuilder sb = new StringBuilder(frames * 32 + 64);
		sb.append("phase,frame,t_ms,frame_ms\n");
		int[] idx = new int[REC_PHASES.size()];
		long base = frames > 0 ? frameAt[0] : 0;
		for (int i = 0; i < frames; i++) {
			int p = framePhase[i];
			sb.append(REC_PHASES.get(p)).append(',').append(idx[p]++).append(',');
			sb.append(String.format(java.util.Locale.ROOT, "%.3f,%.4f", (frameAt[i] - base) / 1e6, frameNs[i] / 1e6)).append('\n');
		}
		Files.writeString(out.resolve("frametimes.csv"), sb.toString(), StandardCharsets.UTF_8);
	}

	/**
	 * mcopt's opt-in GPU cadence recorder (-Dmcopt.metal.gpuTimes=true on the Metal backend): per command buffer GPU
	 * start/end. Reported per perf phase: GPU busy ms per CPU frame (sum of command-buffer GPU time in the phase / frames),
	 * busy fraction of wall time, per-submit p50/p95, and mcopt's own presented-frame fps.
	 */
	@SuppressWarnings("unchecked")
	static Object mcoptGpu() {
		if (!Boolean.getBoolean("mcopt.metal.gpuTimes") || "false".equals(System.getProperty("mcopt.metal"))) return null;
		try {
			Class<?> c = Class.forName("mcopt.metal.GpuTimes");
			Map<String, Map<String, Object>> phases = new LinkedHashMap<>();
			for (var e : phaseRanges.entrySet()) {
				Map<String, Object> p = new LinkedHashMap<>();
				p.put("nanoRange", e.getValue().clone());
				phases.put(e.getKey(), p);
			}
			c.getMethod("snapshot", Map.class).invoke(null, phases);
			Map<String, Object> outMap = new LinkedHashMap<>();
			StringBuilder csv = new StringBuilder("phase,submitIndex,gpuStartNs,gpuEndNs,busyUs,presenting\n");
			for (var e : phases.entrySet()) {
				Map<String, Object> p = e.getValue();
				Map<String, Object> gf = (Map<String, Object>) p.get("gpuFrames");
				if (gf == null) continue;
				long[] idx = (long[]) gf.get("submitIndex"), st = (long[]) gf.get("gpuStartNs"), en = (long[]) gf.get("gpuEndNs");
				boolean[] pres = (boolean[]) gf.get("presenting");
				long busy = 0;
				long[] each = new long[st.length];
				int n = 0;
				for (int i = 0; i < st.length; i++) {
					if (st[i] == 0 || en[i] == 0) continue;
					long b = en[i] - st[i];
					busy += b;
					each[n++] = b;
					csv.append(e.getKey()).append(',').append(idx[i]).append(',').append(st[i]).append(',').append(en[i]).append(',')
						.append(b / 1000).append(',').append(pres[i] ? 1 : 0).append('\n');
				}
				long[] range = phaseRanges.get(e.getKey());
				int cpuFrames = 0;
				int pi = REC_PHASES.indexOf(e.getKey());
				for (int i = 0; i < frames; i++)
					if (framePhase[i] == pi) cpuFrames++;
				// command buffers overlap on the GPU timeline (frames in flight): busy time = union of [start,end]
				long[][] iv = new long[n][];
				int k = 0;
				for (int i = 0; i < st.length; i++)
					if (st[i] != 0 && en[i] != 0) iv[k++] = new long[] {st[i], en[i]};
				Arrays.sort(iv, (a, b) -> Long.compare(a[0], b[0]));
				long union = 0, cs = Long.MIN_VALUE, ce = Long.MIN_VALUE;
				for (long[] v : iv) {
					if (v[0] > ce) {
						if (ce > cs) union += ce - cs;
						cs = v[0];
						ce = v[1];
					} else ce = Math.max(ce, v[1]);
				}
				if (ce > cs) union += ce - cs;
				Map<String, Object> s = new LinkedHashMap<>();
				s.put("submits", n);
				s.put("cpuFrames", cpuFrames);
				s.put("gpuMsPerFrame", cpuFrames == 0 ? null : r3(union / 1e6 / cpuFrames));
				s.put("gpuBusyFraction", r3((double) union / (range[1] - range[0])));
				s.put("submitSpanSumMsPerFrame", cpuFrames == 0 ? null : r3(busy / 1e6 / cpuFrames));
				long[] sorted = Arrays.copyOf(each, n);
				Arrays.sort(sorted);
				if (n > 0) {
					s.put("submitP50Ms", r3(pct(sorted, 0.5) / 1e6));
					s.put("submitP95Ms", r3(pct(sorted, 0.95) / 1e6));
					s.put("submitP99Ms", r3(pct(sorted, 0.99) / 1e6));
				}
				for (String key : new String[] {"gpuAvgFps", "gpuLow1Fps", "gpuPresentingFrames"})
					if (p.containsKey(key)) s.put(key, p.get(key) instanceof Double d ? r1(d) : p.get(key));
				outMap.put(e.getKey(), s);
			}
			Files.writeString(out.resolve("gputimes.csv"), csv.toString(), StandardCharsets.UTF_8);
			return outMap;
		} catch (ClassNotFoundException e) {
			return null;
		} catch (Throwable t) {
			warnings.add("mcopt GPU times unavailable: " + t);
			return null;
		}
	}
}
