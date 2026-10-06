package mcopt.metal.mixin.compat;

import java.util.List;
import java.util.Set;
import mcopt.metal.DhCompat;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Mixins into other mods. Each applies only when its mod is loaded (and, for Distant Horizons, only for the DH versions
 * the Metal path was tested with, see {@link DhCompat}) and only while mcopt's Metal backend is on: without them the game
 * is unchanged. Idea and first version of the DH one: mcopt PR #4 by CazzaCoder.
 */
public final class CompatMixinPlugin implements IMixinConfigPlugin {
	static {
		mcopt.metal.Profile.apply(); // before any flag is read
	}

	@Override public void onLoad(String mixinPackage) { }
	@Override public String getRefMapperConfig() { return null; }

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		if (mixinClassName.endsWith(".DhRenderApiMixin")) {
			boolean apply = DhCompat.loaded() && DhCompat.versionSupported() && Boolean.parseBoolean(System.getProperty("mcopt.metal", "true"));
			if (DhCompat.loaded()) {
				System.out.println("[mcopt] compat: Distant Horizons " + DhCompat.version() + " render-API mixin " + (apply ? "applied" : "not applied")
					+ (DhCompat.versionSupported() ? "" : " (tested: " + DhCompat.range() + ")"));
			}
			return apply;
		}
		return false;
	}

	@Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) { }
	@Override public List<String> getMixins() { return null; }
	@Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
	@Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
}
