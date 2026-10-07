package mcopt.metal;

import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import org.jspecify.annotations.Nullable;

/**
 * The backend's seams for redirecting and labelling render passes: inert unless something installs a redirector or a
 * labeler, which nothing in this build does.
 */
public final class MetalHooks {
	/**
	 * Redirected attachments for a pass, and the delegate that draws into them. clears: per color attachment, a clear
	 * color for its load action or null to load it. depth 0 keeps the pass's own depth attachment; otherwise that texture
	 * replaces it (cleared to depthClear unless NaN, else loaded) and the pass covers the whole width x height.
	 *
	 * <p>delegate null (a format redirect, see {@link #formats}): the pass draws the game's own pipelines into the
	 * redirected colors, through each pipeline's variant for colorFormats (MetalPipeline.variant; native shading's HDR
	 * world target). colorFormats: MTLPixelFormat per redirected color, or null when they are the pipelines' own.
	 */
	public record Redirect(long @Nullable [] colors, float @Nullable [][] clears, int width, int height, long depth, float depthClear,
		@Nullable PassDelegate delegate, int @Nullable [] colorFormats) {
		/** No depth clear (depthClear is only read when depth replaces the pass's own). */
		public static final float NO_CLEAR = Float.NaN;

		public Redirect(long @Nullable [] colors, float @Nullable [][] clears, int width, int height, long depth, float depthClear, PassDelegate delegate) {
			this(colors, clears, width, height, depth, depthClear, delegate, null);
		}

		/** The pass keeps its own attachments (colors, depth, clears); only its pipelines go through the delegate. */
		public static Redirect keep(PassDelegate delegate) {
			return new Redirect(null, null, 0, 0, 0, NO_CLEAR, delegate, null);
		}

		/**
		 * The pass draws into colors (formats: their MTLPixelFormats) instead of its own color attachments, keeping its depth
		 * attachment, with the game's pipelines rebuilt for those formats; no delegate.
		 */
		public static Redirect formats(long[] colors, float @Nullable [][] clears, int width, int height, int[] formats) {
			return new Redirect(colors, clears, width, height, 0, NO_CLEAR, null, formats);
		}

		public boolean keepsAttachments() {
			return this.colors == null;
		}
	}

	/** Decides for each render pass whether it is taken over. */
	public interface PassRedirector {
		@Nullable Redirect redirect(RenderPassDescriptor descriptor);
	}

	/**
	 * Installed redirectors, asked in order (lowest order first) for every render pass; the first non-null Redirect wins.
	 * Copy-on-write: the backend reads it on the render thread without locking.
	 */
	private static volatile Entry[] chain = new Entry[0];

	private record Entry(int order, PassRedirector redirector) {
	}

	private MetalHooks() {
	}

	/** The first redirect any installed redirector makes for descriptor, or null (no redirector: null at once). */
	static @Nullable Redirect redirect(RenderPassDescriptor descriptor) {
		Entry[] c = chain;
		for (Entry e : c) {
			Redirect r = e.redirector.redirect(descriptor);
			if (r != null) return r;
		}
		return null;
	}

	/** Whether any redirector is installed (the backend's fast path skips the chain otherwise). */
	static boolean hasRedirectors() {
		return chain.length != 0;
	}

	/** Names a native render encoder (for GPU traces). */
	public interface Labeler {
		void label(long enc, String name);
	}

	/** When set, every render pass's encoder gets its descriptor's label. */
	static volatile @Nullable Labeler labeler;

	public static void setLabeler(@Nullable Labeler l) {
		labeler = l;
	}

	/**
	 * Replaces every installed redirector with r (null: none). Kept for the single-redirector users; prefer
	 * {@link #addRedirector}, which chains.
	 */
	public static synchronized void setRedirector(@Nullable PassRedirector r) {
		chain = r == null ? new Entry[0] : new Entry[] {new Entry(0, r)};
	}

	/**
	 * Adds r to the chain. Lower order runs first: e.g. far terrain's redirector (order -100) can take Distant Horizons'
	 * passes before native shading's HDR redirector (order 0) looks at them. A redirector returns null for passes it
	 * leaves alone, so several can coexist.
	 */
	public static synchronized void addRedirector(int order, PassRedirector r) {
		java.util.List<Entry> list = new java.util.ArrayList<>(java.util.List.of(chain));
		list.add(new Entry(order, r));
		list.sort(java.util.Comparator.comparingInt(Entry::order));
		chain = list.toArray(Entry[]::new);
	}

	public static synchronized void removeRedirector(PassRedirector r) {
		chain = java.util.Arrays.stream(chain).filter(e -> e.redirector != r).toArray(Entry[]::new);
	}
}
