// Copyright 2026 mcopt-shade contributors. Licensed under the Apache License, Version 2.0 (LICENSE).
package mcopt.metal.shade.shadow.mixin;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * The sun shadow pass's mixins apply only with -Dmcopt.shade=true (and mcopt.shade.shadows not false) and terrain casters
 * to draw: without them the game is byte-for-byte what it is without them. Reads the properties itself so nothing of
 * mcopt.metal.shade loads before the game does.
 */
public final class ShadowMixinPlugin implements IMixinConfigPlugin {
	static {
		mcopt.metal.Profile.apply(); // before any flag is read
	}

	private static final boolean ENABLED = Boolean.getBoolean("mcopt.shade") // the same tests as Shade.SHADOWS
		&& Boolean.parseBoolean(System.getProperty("mcopt.shade.shadows", "true")) && Boolean.parseBoolean(System.getProperty("mcopt.metal", "true"));

	/**
	 * mcopt 0.3 port (v12): the terrain casters came from Sodium, which this build doesn't have (see ShadowCasters); the
	 * pass stays off until they come from mcopt's own renderer (P4). Vanilla's blob shadows stay meanwhile.
	 */
	private static final boolean TERRAIN_CASTERS = false;

	@Override
	public void onLoad(String mixinPackage) {
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		return ENABLED && TERRAIN_CASTERS;
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
	}

	@Override
	public List<String> getMixins() {
		return null;
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}
}
