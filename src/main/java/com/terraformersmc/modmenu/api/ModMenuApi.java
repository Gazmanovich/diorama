package com.terraformersmc.modmenu.api;

/**
 * Compile-time stand-in for Mod Menu's API, so the build needs no Mod Menu jar for each Minecraft
 * version. It matches Mod Menu's own interface and is left out of the release jar (see
 * gradle/common.gradle.kts); with Mod Menu installed, its real class is the one loaded.
 */
public interface ModMenuApi {
	default ConfigScreenFactory<?> getModConfigScreenFactory() {
		return parent -> null;
	}
}
