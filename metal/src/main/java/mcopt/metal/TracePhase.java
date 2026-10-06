/*
 * Copyright 2026 mcopt-shade contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package mcopt.metal;

import java.lang.reflect.Field;

/**
 * -Dmcopt.metal.trace=PHASE[+N] (e.g. spin+300): trace and GPU-profile the submit N frames after the bench mod
 * (mcopt.bench.Bench.phase) enters PHASE, instead of a fixed submit index, so per-pass costs land on the bench route
 * however long loading took. A plain number keeps working as before; unset, nothing here runs.
 */
final class TracePhase {
	private static final String SPEC = System.getProperty("mcopt.metal.trace", "");
	static final String PHASE;
	static final int OFFSET;
	private static Field field;
	private static boolean looked;

	static {
		String phase = null;
		int offset = 0;
		if (!SPEC.isEmpty() && !Character.isDigit(SPEC.charAt(0)) && SPEC.charAt(0) != '-') {
			int plus = SPEC.indexOf('+');
			phase = (plus < 0 ? SPEC : SPEC.substring(0, plus)).toUpperCase(java.util.Locale.ROOT);
			offset = plus < 0 ? 0 : Integer.parseInt(SPEC.substring(plus + 1));
		}
		PHASE = phase;
		OFFSET = offset;
	}

	private TracePhase() {
	}

	/** The fixed submit index of -Dmcopt.metal.trace=N, or -1 (also for the phase form, resolved later by at()). */
	static long fixed() {
		return PHASE == null ? Long.getLong("mcopt.metal.trace", -1) : -1;
	}

	/** Whether the bench is in PHASE now. */
	static boolean in() {
		if (!looked) {
			looked = true;
			try {
				field = Class.forName("mcopt.bench.Bench").getDeclaredField("phase");
				field.setAccessible(true);
			} catch (ReflectiveOperationException | RuntimeException absent) {
				field = null;
			}
		}
		try {
			return field != null && PHASE.equals(String.valueOf(field.get(null)));
		} catch (ReflectiveOperationException e) {
			return false;
		}
	}
}
