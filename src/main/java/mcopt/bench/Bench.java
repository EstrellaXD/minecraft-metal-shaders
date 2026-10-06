/*
 * Copyright 2026 mcbench contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package mcopt.bench;

/**
 * Compatibility shim: mcopt reflects on {@code mcopt.bench.Bench.phase} (String) and {@code settledAt} (nanoTime long)
 * when that class exists (mcopt.metal.cpu.Cpu#phase, AbShots, rec.Recorder phase start/stop). mcbench keeps these two
 * fields current so mcopt's own stats/recorder can key off the bench phases. Phase names: BOOT, LOAD, SETUP, SETTLE,
 * WARMUP, SPIN, FLIGHT, SHOT, DONE.
 */
public final class Bench {
	public static volatile String phase = "BOOT";
	/** System.nanoTime() at which the current scene settled (0 while not settled). */
	public static volatile long settledAt;

	private Bench() {
	}
}
