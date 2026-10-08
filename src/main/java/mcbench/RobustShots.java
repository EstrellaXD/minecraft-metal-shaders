/*
 * Copyright 2026 mcopt-shade contributors. Licensed under the Apache License, Version 2.0.
 */
package mcbench;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import mcbench.Spot.Pose;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Opt-in {@code -Dmcbench.robust=<list>} (mcopt-shade M3.3 robustness, branch m3.3-robust): the paths a normal session takes
 * that the bench never did, each with screenshots and notes in results.json "robust". Events run after the standard shots,
 * in the order given:
 * <ul>
 * <li>{@code gui}: creative, a torch in the hand, HUD shown: {@code rb_hud_hand}; then over the world the creative inventory
 * ({@code rb_inventory}), chat with text ({@code rb_chat}), the F3 debug overlay ({@code rb_f3}), the pause menu
 * ({@code rb_pause}); back to spectator, HUD hidden.</li>
 * <li>{@code transp}: vanilla "Improved Transparency" (26.3: order-independent transparency) off, then on, at the sunset water
 * pose ({@code rb_transp_off_water}/{@code _on_water}) and the overview in rain ({@code rb_transp_off_rain}/{@code _on_rain});
 * set back off.</li>
 * <li>{@code dims}: overworld -> Nether -> overworld -> End -> overworld, a shot after each change ({@code rb_dim1_nether},
 * {@code rb_dim2_overworld}, {@code rb_dim3_end}, {@code rb_dim4_overworld}).</li>
 * <li>{@code resize}: leave fullscreen ({@code rb_windowed}), resize the window smaller ({@code rb_windowed_small}), back to
 * fullscreen ({@code rb_fullscreen_back}).</li>
 * <li>{@code rejoin}: {@code -Dmcbench.rejoins} (3) times: Save and Quit to the title screen ({@code rb_title_1} on the first),
 * open the world again, a shot as soon as the world shows ({@code rb_rejoin_K_early}) and once settled at the overview
 * ({@code rb_rejoin_K}); memory (Metal device allocated bytes through mcopt-shade's ShadeRobust, Java heap after a GC, the
 * process RSS) before the first leave, on each title screen and after each rejoin: results.json "robust" / "memory".</li>
 * <li>{@code fail}: in-session shader build failures: {@code -Dmcopt.shade.dev.failCompile} set to each entry of
 * {@code -Dmcbench.robustFail} (default {@code terrain;*}, ';'-separated), a resource reload (rebuilds the pipelines), a shot
 * ({@code rb_fail_<n>}); then cleared, reloaded again ({@code rb_fail_restored}). Put it last: an injected "*" also fails any
 * module that sets itself up later in the session.</li>
 * </ul>
 */
final class RobustShots {
	static final List<String> EVENTS = java.util.Arrays.stream(System.getProperty("mcbench.robust", "").split(",")).map(String::trim)
		.filter(x -> !x.isEmpty() && !x.equals("false")).toList();
	static final int REJOINS = Integer.getInteger("mcbench.rejoins", 3);
	static final List<Object> JSON = new ArrayList<>();
	static final List<Object> MEMORY = new ArrayList<>();

	private RobustShots() {
	}

	static void plan(Deque<BenchDriver.Step> steps) {
		if (EVENTS.isEmpty()) return;
		BenchDriver.results.put("robust", JSON); // live lists: a failed run's results-partial.json has them too
		BenchDriver.results.put("robustMemory", MEMORY);
		for (String e : EVENTS) {
			switch (e) {
				case "gui" -> gui(steps);
				case "transp" -> transp(steps);
				case "dims" -> dims(steps);
				case "resize" -> resize(steps);
				case "rejoin" -> rejoin(steps);
				case "fail" -> fail(steps);
				default -> throw new IllegalArgumentException("mcbench.robust: unknown event " + e);
			}
		}
	}

	// ---- helpers ------------------------------------------------------------------------------------------------------

	interface Action {
		void run(Minecraft mc) throws Exception;
	}

	/** A step that runs action at its start and then waits sec seconds (screens stay open). */
	static BenchDriver.Step step(String name, double timeout, Action action, double sec) {
		return new BenchDriver.Step(name, timeout) {
			@Override
			void start(Minecraft mc, long now) throws Exception {
				action.run(mc);
			}

			@Override
			boolean poll(Minecraft mc, long now) {
				return BenchDriver.secs(now - BenchDriver.curStart) >= sec;
			}
		};
	}

	static void note(String event, String text) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("event", event);
		m.put("t", Math.round(BenchDriver.secs(System.nanoTime() - BenchDriver.T0) * 10) / 10.0);
		m.put("note", text);
		JSON.add(m);
		BenchDriver.log("robust " + event + ": " + text);
	}

	/** A screenshot of the main target as it is (GUI and screens included; no level needed). */
	static final class Snap extends BenchDriver.Step {
		volatile boolean done;
		volatile Throwable error;

		Snap(String scene) {
			super("snap-" + scene, 60);
		}

		@Override
		void start(Minecraft mc, long now) {
			BenchDriver.setPhase("SHOT");
			String scene = name.substring(5);
			Path file = BenchDriver.out.resolve("screenshots").resolve(scene + ".png");
			var target = mc.gameRenderer.mainRenderTarget();
			Screenshot.takeScreenshot(target, image -> new Thread(() -> {
				try (image) {
					image.writeToFile(file);
				} catch (Throwable t) {
					error = t;
				}
				done = true;
			}, "mcbench-robust-shot").start());
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("name", scene);
			m.put("file", "screenshots/" + scene + ".png");
			m.put("width", target.width);
			m.put("height", target.height);
			m.put("screen", mc.gui.screen() == null ? null : mc.gui.screen().getClass().getSimpleName());
			m.put("level", mc.level == null ? null : mc.level.dimension().toString());
			BenchDriver.shots.add(m);
		}

		@Override
		boolean poll(Minecraft mc, long now) throws Exception {
			if (!done) return false;
			if (error != null) throw new IOException("screenshot write failed", error);
			BenchDriver.log("screenshot " + name + " (robust)");
			return true;
		}
	}

	/** Scene + settle + a plain hold (closes stray screens) + shot. */
	static void shot(Deque<BenchDriver.Step> steps, String name, int time, boolean rain, java.util.function.Supplier<Pose> p) {
		steps.add(new BenchDriver.Scene(name, time, rain, p));
		steps.add(new BenchDriver.Settle(name, rain, 3, 120));
		steps.add(new BenchDriver.Hold(name, rain ? Math.max(BenchDriver.SHOT_WAIT, 4) : BenchDriver.SHOT_WAIT));
		steps.add(new BenchDriver.Shot(name));
	}

	/** Memory snapshot (render thread): Metal device bytes (mcopt-shade ShadeRobust, -1 if absent), Java heap after a GC, RSS. */
	static void memory(String tag) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("tag", tag);
		m.put("t", Math.round(BenchDriver.secs(System.nanoTime() - BenchDriver.T0) * 10) / 10.0);
		long dev = -1;
		try {
			dev = (long) Class.forName("mcopt.metal.shade.ShadeRobust").getMethod("deviceAllocatedBytes").invoke(null);
		} catch (Throwable t) {
			m.put("deviceError", String.valueOf(t));
		}
		System.gc();
		Runtime r = Runtime.getRuntime();
		long heap = r.totalMemory() - r.freeMemory();
		long rss = -1;
		try {
			Process p = new ProcessBuilder("ps", "-o", "rss=", "-p", Long.toString(ProcessHandle.current().pid())).start();
			rss = Long.parseLong(new String(p.getInputStream().readAllBytes()).trim()) * 1024;
		} catch (Throwable ignored) {
		}
		m.put("deviceMB", dev < 0 ? -1 : Math.round(dev / 1048576.0 * 10) / 10.0);
		m.put("heapMB", Math.round(heap / 1048576.0 * 10) / 10.0);
		m.put("rssMB", rss < 0 ? -1 : Math.round(rss / 1048576.0 * 10) / 10.0);
		MEMORY.add(m);
		BenchDriver.log("robust memory " + m);
	}

	// ---- gui ----------------------------------------------------------------------------------------------------------

	static void gui(Deque<BenchDriver.Step> steps) {
		steps.add(new BenchDriver.Scene("rb_gui", 6000, false, () -> BenchDriver.spot.forest));
		steps.add(new BenchDriver.Settle("rb_gui", false, 3, 120));
		steps.add(step("rb-gui-on", 0, mc -> {
			MinecraftServer s = BenchDriver.server(mc);
			BenchDriver.cmd(s, "gamemode creative @a");
			BenchDriver.cmd(s, "item replace entity @a weapon.mainhand with minecraft:torch");
			BenchDriver.cmd(s, "item replace entity @a weapon.offhand with minecraft:compass");
			if (mc.gui.hud.isHidden()) mc.gui.hud.toggle();
		}, 2.5));
		steps.add(new Snap("rb_hud_hand"));
		steps.add(step("rb-gui-inventory", 0, mc -> mc.gui.setScreen(new InventoryScreen(mc.player)), 1.5));
		steps.add(new Snap("rb_inventory"));
		steps.add(step("rb-gui-chat", 0, mc -> mc.gui.setScreen(new ChatScreen("hola mundo: probando el chat sobre los shaders", false)), 1.5));
		steps.add(new Snap("rb_chat"));
		steps.add(step("rb-gui-f3", 0, mc -> {
			mc.gui.setScreen(null);
			mc.debugEntries.toggleDebugOverlay();
		}, 2.0));
		steps.add(new Snap("rb_f3"));
		steps.add(step("rb-gui-pause", 0, mc -> {
			mc.debugEntries.toggleDebugOverlay();
			mc.gui.setScreen(new PauseScreen(true));
		}, 1.5));
		steps.add(new Snap("rb_pause"));
		steps.add(step("rb-gui-off", 0, mc -> {
			mc.gui.setScreen(null);
			MinecraftServer s = BenchDriver.server(mc);
			BenchDriver.cmd(s, "item replace entity @a weapon.mainhand with minecraft:air");
			BenchDriver.cmd(s, "item replace entity @a weapon.offhand with minecraft:air");
			BenchDriver.cmd(s, "gamemode spectator @a");
			BenchDriver.hideHud(mc);
			note("gui", "hud+hand, creative inventory, chat, F3, pause shot");
		}, 1.0));
	}

	// ---- transp -------------------------------------------------------------------------------------------------------

	static void transp(Deque<BenchDriver.Step> steps) {
		steps.add(step("rb-transp-off", 0, mc -> {
			mc.options.improvedTransparency().set(false);
			note("transp", "improved transparency off (was " + mc.options.improvedTransparency().get() + ")");
		}, 0.5));
		shot(steps, "rb_transp_off_water", 12400, false, () -> BenchDriver.spot.sunset);
		shot(steps, "rb_transp_off_rain", 6000, true, () -> BenchDriver.spot.overview);
		steps.add(step("rb-transp-on", 0, mc -> {
			mc.options.improvedTransparency().set(true);
			note("transp", "improved transparency on: useImprovedTransparency=" + mc.gameRenderer.useImprovedTransparency());
		}, 1.0));
		shot(steps, "rb_transp_on_water", 12400, false, () -> BenchDriver.spot.sunset);
		shot(steps, "rb_transp_on_rain", 6000, true, () -> BenchDriver.spot.overview);
		steps.add(step("rb-transp-restore", 0, mc -> {
			note("transp", "on, after the shots: useImprovedTransparency=" + mc.gameRenderer.useImprovedTransparency());
			mc.options.improvedTransparency().set(false);
		}, 1.0));
	}

	// ---- dims ---------------------------------------------------------------------------------------------------------

	record Where(String dim, Pose pose) {
	}

	static void dims(Deque<BenchDriver.Step> steps) {
		steps.add(new BenchDriver.Step("rb-dims-find", 240) {
			CompletableFuture<Where[]> f;

			@Override
			void start(Minecraft mc, long now) {
				MinecraftServer s = BenchDriver.server(mc);
				f = CompletableFuture.supplyAsync(() -> new Where[] {nether(s.getLevel(Level.NETHER)), end(s.getLevel(Level.END))}, s);
			}

			@Override
			boolean poll(Minecraft mc, long now) throws Exception {
				if (!f.isDone()) return false;
				Where[] w = f.get();
				Pose o = BenchDriver.spot.overview;
				Where over = new Where("minecraft:overworld", o);
				List<BenchDriver.Step> extra = new ArrayList<>();
				String[] names = {"rb_dim1_nether", "rb_dim2_overworld", "rb_dim3_end", "rb_dim4_overworld"};
				Where[] seq = {w[0], over, w[1], over};
				for (int i = 0; i < seq.length; i++) {
					Where x = seq[i];
					String n = names[i];
					extra.add(new DimShots.DimScene(n, new DimShots.Found(x.dim, x.pose, 6000, "", "")));
					extra.add(new BenchDriver.Settle(n, false, 4, 60));
					extra.add(new BenchDriver.Hold(n, Math.max(BenchDriver.SHOT_WAIT, 3)));
					extra.add(new BenchDriver.Shot(n));
					extra.add(step("rb-dim-note-" + i, 0, mc2 -> note("dims", n + ": in " + (mc2.level == null ? "-" : mc2.level.dimension().toString()) + " at " + x.pose), 0));
				}
				for (int i = extra.size() - 1; i >= 0; i--) steps.addFirst(extra.get(i));
				return true;
			}
		});
	}

	private static boolean air(ServerLevel l, int x, int y, int z) {
		return l.getBlockState(new BlockPos(x, y, z)).isAir();
	}

	/** An open view near the Nether origin (eye in air, the most open of 8 directions), or a fixed pose if none. */
	static Where nether(ServerLevel l) {
		Pose best = null;
		int bestReach = 0;
		for (int dx = -32; dx <= 32; dx += 8) {
			for (int dz = -32; dz <= 32; dz += 8) {
				l.getChunk(dx >> 4, dz >> 4);
				for (int y = 40; y <= 100; y += 3) {
					if (!air(l, dx, y, dz) || !air(l, dx, y + 1, dz) || !air(l, dx, y - 1, dz)) continue;
					for (int a = 0; a < 360; a += 45) {
						double ux = -Math.sin(Math.toRadians(a)), uz = Math.cos(Math.toRadians(a));
						int r = 1;
						while (r < 48 && air(l, (int) Math.floor(dx + 0.5 + ux * r), y, (int) Math.floor(dz + 0.5 + uz * r))) r++;
						if (r > bestReach) {
							bestReach = r;
							best = new Pose(dx + 0.5, y + 0.2, dz + 0.5, a, 12f);
						}
					}
				}
			}
		}
		return new Where("minecraft:the_nether", best != null ? best : new Pose(0.5, 70, 0.5, 0f, 10f));
	}

	/** Above the main End island's edge looking at its centre (like DimShots end_island). */
	static Where end(ServerLevel l) {
		l.getChunk(0, 0);
		l.getChunk(4, 4);
		int h0 = Math.max(l.getHeight(Heightmap.Types.MOTION_BLOCKING, 0, 0), 60);
		int e1 = l.getHeight(Heightmap.Types.MOTION_BLOCKING, 70, 70);
		float yaw = (float) Math.toDegrees(Math.atan2(70, -70));
		return new Where("minecraft:the_end", new Pose(70.5, Math.max(e1, h0) + 28, 70.5, yaw, 18f));
	}

	// ---- resize -------------------------------------------------------------------------------------------------------

	/** Waits until the framebuffer size is unchanged for 1.5 s (at least 2 s). */
	static BenchDriver.Step sizeStep(String name, Action action) {
		return new BenchDriver.Step(name, 60) {
			int w, h;
			long since;

			@Override
			void start(Minecraft mc, long now) throws Exception {
				action.run(mc);
				since = now;
			}

			@Override
			boolean poll(Minecraft mc, long now) {
				var win = mc.getWindow();
				if (win.getWidth() != w || win.getHeight() != h) {
					w = win.getWidth();
					h = win.getHeight();
					since = now;
				}
				if (BenchDriver.secs(now - BenchDriver.curStart) < 2 || BenchDriver.secs(now - since) < 1.5) return false;
				note("resize", name + ": framebuffer " + w + "x" + h + (BenchDriver.isFullscreen(win) ? " fullscreen" : " windowed") + ", main target "
					+ mc.gameRenderer.mainRenderTarget().width + "x" + mc.gameRenderer.mainRenderTarget().height);
				return true;
			}
		};
	}

	static void resize(Deque<BenchDriver.Step> steps) {
		steps.add(new BenchDriver.Scene("rb_resize", 6000, false, () -> BenchDriver.spot.overview));
		steps.add(new BenchDriver.Settle("rb_resize", false, 3, 120));
		steps.add(sizeStep("rb-windowed", mc -> mc.getWindow().setFullscreen(false)));
		steps.add(new BenchDriver.Hold("rb_windowed", 1.5));
		steps.add(new BenchDriver.Shot("rb_windowed"));
		steps.add(sizeStep("rb-windowed-small", mc -> org.lwjgl.sdl.SDLVideo.SDL_SetWindowSize(mc.getWindow().handle(), 1000, 640)));
		steps.add(new BenchDriver.Hold("rb_windowed_small", 1.5));
		steps.add(new BenchDriver.Shot("rb_windowed_small"));
		steps.add(sizeStep("rb-fullscreen-back", mc -> mc.getWindow().setFullscreen(true)));
		steps.add(new BenchDriver.Settle("rb_fullscreen_back", false, 2, 60));
		steps.add(new BenchDriver.Hold("rb_fullscreen_back", 2.0));
		steps.add(new BenchDriver.Shot("rb_fullscreen_back"));
	}

	// ---- rejoin -------------------------------------------------------------------------------------------------------

	static void rejoin(Deque<BenchDriver.Step> steps) {
		shot(steps, "rb_rejoin_0", 6000, false, () -> BenchDriver.spot.overview);
		steps.add(step("rb-mem-0", 0, mc -> memory("in world, before the first leave"), 0.5));
		for (int k = 1; k <= REJOINS; k++) {
			final int K = k;
			steps.add(step("rb-leave-" + k, 0, mc -> {
				BenchDriver.pose = null;
				note("rejoin", "leave " + K + ": Save and Quit to Title");
				mc.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE);
			}, 0));
			steps.add(new BenchDriver.Step("rb-title-" + k, 120) {
				long since;

				@Override
				boolean poll(Minecraft mc, long now) {
					if (mc.level != null || !(mc.gui.screen() instanceof TitleScreen) || mc.gui.overlay() != null) {
						since = 0;
						return false;
					}
					if (since == 0) since = now;
					return BenchDriver.secs(now - since) >= 3;
				}
			});
			steps.add(step("rb-mem-title-" + k, 0, mc -> memory("title screen after leave " + K), 0.5));
			if (k == 1) steps.add(new Snap("rb_title_1"));
			steps.add(step("rb-open-" + k, 0, mc -> {
				note("rejoin", "open the world again (" + K + ")");
				mc.createWorldOpenFlows().openWorld(BenchDriver.WORLD, () -> BenchDriver.fail(mc, "openWorld cancelled", new IllegalStateException("cancelled")));
			}, 0));
			steps.add(new BenchDriver.Step("rb-in-world-" + k, 300) {
				long since;

				@Override
				boolean poll(Minecraft mc, long now) {
					if (mc.level == null || mc.player == null || mc.gui.screen() != null || mc.gui.overlay() != null) {
						since = 0;
						return false;
					}
					if (since == 0) {
						since = now;
						mc.options.pauseOnLostFocus = false;
						BenchDriver.hideHud(mc);
						BenchDriver.raiseWindow(mc);
					}
					return BenchDriver.secs(now - since) >= 0.4;
				}
			});
			steps.add(new Snap("rb_rejoin_" + k + "_early"));
			shot(steps, "rb_rejoin_" + k, 6000, false, () -> BenchDriver.spot.overview);
			steps.add(step("rb-mem-" + k, 0, mc -> memory("in world after rejoin " + K), 0.5));
		}
	}

	// ---- fail ---------------------------------------------------------------------------------------------------------

	static BenchDriver.Step reload(String name, String failValue) {
		return new BenchDriver.Step(name, 180) {
			CompletableFuture<Void> f;
			long since;

			@Override
			void start(Minecraft mc, long now) {
				if (failValue == null) System.clearProperty("mcopt.shade.dev.failCompile");
				else System.setProperty("mcopt.shade.dev.failCompile", failValue);
				note("fail", "mcopt.shade.dev.failCompile=" + failValue + ", resource reload");
				f = mc.reloadResourcePacks();
			}

			@Override
			boolean poll(Minecraft mc, long now) {
				if (!f.isDone() || mc.gui.overlay() != null) {
					since = 0;
					return false;
				}
				if (f.isCompletedExceptionally()) throw new IllegalStateException("resource reload failed: " + f.exceptionNow());
				if (since == 0) since = now;
				return BenchDriver.secs(now - since) >= 2;
			}
		};
	}

	static void fail(Deque<BenchDriver.Step> steps) {
		String[] cases = System.getProperty("mcbench.robustFail", "terrain;*").split(";");
		for (int i = 0; i < cases.length; i++) {
			String c = cases[i].trim();
			if (c.isEmpty()) continue;
			String n = "rb_fail_" + (c.equals("*") ? "all" : c.replaceAll("[^A-Za-z0-9]+", "_"));
			steps.add(reload("rb-fail-reload-" + i, c));
			shot(steps, n, 6000, false, () -> BenchDriver.spot.forest);
		}
		steps.add(reload("rb-fail-restore", null));
		shot(steps, "rb_fail_restored", 6000, false, () -> BenchDriver.spot.forest);
	}
}
