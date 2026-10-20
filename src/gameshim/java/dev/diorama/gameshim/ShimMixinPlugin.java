package dev.diorama.gameshim;

import java.util.List;
import java.util.Set;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Applies the shim's mixins only with {@code -Ddiorama.gameshim=true}: they cut the game off from
 * the real keyboard and mouse, which nobody wants outside a test run.
 */
public final class ShimMixinPlugin implements IMixinConfigPlugin {
	@Override
	public void onLoad(String mixinPackage) {
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		return GameShim.enabled();
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
	}

	/** Mixins for classes only some versions have (diorama-gameshim.mixins.json lists the rest). */
	@Override
	public List<String> getMixins() {
		//? if >=26.3 {
		return List.of("GlPresentMixin", "VulkanPresentMixin", "SDLEventHandlerMixin");
		//?} else if >=26.2 {
		/*return List.of("GlPresentMixin", "VulkanPresentMixin");
		*///?} else if >=1.21.5 {
		/*return List.of("GlPresentMixin");
		*///?} else {
		/*return null;
		*///?}
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}
}
