/*
 * Copyright 2026 mcopt-shade contributors. Licensed under the Apache License, Version 2.0.
 */
package mcbench;

import java.util.Deque;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.PauseScreen;

/**
 * Opt-in {@code -Dmcbench.ui=true} (M3.1): the pause menu with mcopt-shade's "Resolución del mundo" button, shot as it is
 * (ui_pause_0), then pressed through its next choices (ui_pause_1..4: native, 1800p, 1600p, 1440p from auto) with a shot
 * each; then the menu is closed and the world shot at that last choice (ui_world_after); the choice is pressed back to auto
 * at the end (one full cycle of the seven choices), so the run leaves config/mcopt.properties as it found it.
 */
final class UiShots {
	/**
	 * -Dmcbench.uiButton (2026-10-09; default "Resolución:|Resolution:"): the label prefix of the button pressed, e.g. "Salida:" (mcopt-shade's
	 * output resolution: 5 choices, -Dmcbench.uiChoices=5); -Dmcbench.uiChoices: how many choices it cycles through (restore).
	 */
	private static final String BUTTON = System.getProperty("mcbench.uiButton", "Resolución:|Resolution:");
	private static final int PRESSES = 4, CHOICES = Integer.getInteger("mcbench.uiChoices", 7);

	/** (2026-10-11) mcopt-shade's buttons follow the game's language: BUTTON holds '|'-separated prefixes ("Salida:|Output:"). */
	private static boolean labelMatches(String label) {
		for (String prefix : BUTTON.split("\\|")) if (label.startsWith(prefix)) return true;
		return false;
	}

	private UiShots() {
	}

	static void plan(Deque<BenchDriver.Step> steps) {
		if (!Boolean.getBoolean("mcbench.ui")) return;
		steps.add(new BenchDriver.Scene("ui", 6000, false, () -> BenchDriver.spot.overview));
		steps.add(new BenchDriver.Settle("ui", false, 3, 120));
		for (int i = 0; i <= PRESSES; i++) {
			final int k = i;
			steps.add(new BenchDriver.Step("ui-pause-" + k, 0) {
				@Override
				void start(Minecraft mc, long now) {
					if (!(mc.gui.screen() instanceof PauseScreen)) mc.gui.setScreen(new PauseScreen(true));
					if (k > 0) press(mc);
				}

				@Override
				boolean poll(Minecraft mc, long now) {
					return BenchDriver.secs(now - BenchDriver.curStart) >= 1.5;
				}
			});
			steps.add(new BenchDriver.Shot("ui_pause_" + k));
		}
		steps.add(new BenchDriver.Step("ui-close", 0) {
			@Override
			void start(Minecraft mc, long now) {
				mc.gui.setScreen(null);
			}

			@Override
			boolean poll(Minecraft mc, long now) {
				return BenchDriver.secs(now - BenchDriver.curStart) >= 3.0;
			}
		});
		steps.add(new BenchDriver.Shot("ui_world_after"));
		steps.add(new BenchDriver.Step("ui-restore", 0) {
			@Override
			void start(Minecraft mc, long now) {
				mc.gui.setScreen(new PauseScreen(true));
			}

			@Override
			boolean poll(Minecraft mc, long now) {
				if (BenchDriver.secs(now - BenchDriver.curStart) < 0.5) return false;
				for (int i = PRESSES; i < CHOICES; i++) press(mc);
				mc.gui.setScreen(null);
				return true;
			}
		});
	}

	/**
	 * Opt-in {@code -Dmcbench.uiSettings=true} (2026-10-13, mcopt-shade 1.1): the pause menu with the "Shaders..." button
	 * (ui_settings_pause), that button pressed (ui_settings: the shader settings screen over the world), the screen's list
	 * scrolled to its end (ui_settings_end); then closed. Changes nothing (no setting is pressed).
	 */
	static void planSettings(Deque<BenchDriver.Step> steps) {
		if (!Boolean.getBoolean("mcbench.uiSettings")) return;
		steps.add(new BenchDriver.Scene("uiset", 6000, false, () -> BenchDriver.spot.overview));
		steps.add(new BenchDriver.Settle("uiset", false, 3, 120));
		steps.add(timed("uiset-pause", 1.5, mc -> mc.gui.setScreen(new PauseScreen(true))));
		steps.add(new BenchDriver.Shot("ui_settings_pause"));
		steps.add(timed("uiset-open", 2.0, mc -> pressLabel(mc, "Shaders")));
		steps.add(new BenchDriver.Shot("ui_settings"));
		steps.add(timed("uiset-scroll", 1.0, mc -> {
			if (mc.gui.screen() != null) mc.gui.screen().mouseScrolled(mc.gui.screen().width / 2.0, mc.gui.screen().height / 2.0, 0.0, -50.0);
		}));
		steps.add(new BenchDriver.Shot("ui_settings_end"));
		// (2026-10-13) each page: the k-th button whose label ends with "..." (the pages), shot at its top and scrolled to its end,
		// then Done back to the main page. -Dmcbench.uiPages (12): how many to try (missing ones are skipped).
		int pages = Integer.getInteger("mcbench.uiPages", 12);
		for (int i = 0; i < pages; i++) {
			final int k = i;
			steps.add(timed("uiset-page-" + k, 1.5, mc -> pressNth(mc, k)));
			steps.add(new BenchDriver.Shot("ui_page_" + k));
			steps.add(timed("uiset-page-scroll-" + k, 1.0, mc -> {
				if (mc.gui.screen() != null) mc.gui.screen().mouseScrolled(mc.gui.screen().width / 2.0, mc.gui.screen().height / 2.0, 0.0, -80.0);
			}));
			steps.add(new BenchDriver.Shot("ui_page_" + k + "_end"));
			steps.add(timed("uiset-page-back-" + k, 1.0, mc -> pressLabel(mc, net.minecraft.network.chat.CommonComponents.GUI_DONE.getString())));
		}
		steps.add(timed("uiset-close", 1.0, mc -> mc.gui.setScreen(null)));
	}

	/** On the settings' main page: presses the k-th button whose label ends with "..." (a page); nothing when there is none. */
	private static void pressNth(Minecraft mc, int k) {
		if (mc.gui.screen() == null) return;
		java.util.List<Button> found = new java.util.ArrayList<>();
		collect(mc.gui.screen().children(), found);
		int n = 0;
		for (Button b : found) {
			if (b.getMessage().getString().endsWith("...") && n++ == k) {
				b.onPress(null);
				return;
			}
		}
	}

	/** The buttons among the listeners, also inside lists (an options list's rows hold them). */
	private static void collect(java.util.List<? extends GuiEventListener> ls, java.util.List<Button> out) {
		for (GuiEventListener l : ls) {
			if (l instanceof Button b) out.add(b);
			if (l instanceof net.minecraft.client.gui.components.events.ContainerEventHandler c) collect(c.children(), out);
		}
	}

	private static BenchDriver.Step timed(String name, double seconds, java.util.function.Consumer<Minecraft> action) {
		return new BenchDriver.Step(name, 0) {
			@Override
			void start(Minecraft mc, long now) {
				action.accept(mc);
			}

			@Override
			boolean poll(Minecraft mc, long now) {
				return BenchDriver.secs(now - BenchDriver.curStart) >= seconds;
			}
		};
	}

	/** Presses the open screen's first button whose label starts with prefix. */
	private static void pressLabel(Minecraft mc, String prefix) {
		if (mc.gui.screen() == null) return;
		for (GuiEventListener l : mc.gui.screen().children()) {
			if (l instanceof Button b && b.getMessage().getString().startsWith(prefix)) {
				b.onPress(null);
				return;
			}
		}
		System.out.println("[mcbench] ui: no button '" + prefix + "' on " + mc.gui.screen().getClass().getSimpleName());
	}

	/** Presses the resolution button of the open pause screen (found by its label). */
	private static void press(Minecraft mc) {
		if (mc.gui.screen() == null) return;
		for (GuiEventListener l : mc.gui.screen().children()) {
			if (l instanceof Button b && labelMatches(b.getMessage().getString())) {
				b.onPress(null);
				return;
			}
		}
		System.out.println("[mcbench] ui: no resolution button in the pause menu");
	}
}
