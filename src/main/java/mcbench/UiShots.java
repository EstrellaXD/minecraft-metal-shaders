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
	 * -Dmcbench.uiButton (2026-10-09; default "Resolución:"): the label prefix of the button pressed, e.g. "Salida:" (mcopt-shade's
	 * output resolution: 5 choices, -Dmcbench.uiChoices=5); -Dmcbench.uiChoices: how many choices it cycles through (restore).
	 */
	private static final String BUTTON = System.getProperty("mcbench.uiButton", "Resolución:");
	private static final int PRESSES = 4, CHOICES = Integer.getInteger("mcbench.uiChoices", 7);

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

	/** Presses the resolution button of the open pause screen (found by its label). */
	private static void press(Minecraft mc) {
		if (mc.gui.screen() == null) return;
		for (GuiEventListener l : mc.gui.screen().children()) {
			if (l instanceof Button b && b.getMessage().getString().startsWith(BUTTON)) {
				b.onPress(null);
				return;
			}
		}
		System.out.println("[mcbench] ui: no resolution button in the pause menu");
	}
}
