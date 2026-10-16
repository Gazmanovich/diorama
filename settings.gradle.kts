pluginManagement {
	repositories {
		maven("https://maven.fabricmc.net/") { name = "Fabric" }
		mavenCentral()
		gradlePluginPortal()
	}
}

plugins {
	id("dev.kikugie.stonecutter") version "0.9.8"
}

// One source tree, one jar per Minecraft version. Up to 1.21.11 the game ships obfuscated and
// needs remapping to Mojang's names; from 26.1 it ships with those names already.
stonecutter {
	create(rootProject) {
		versions("1.21.11").buildscript("build.obfuscated.gradle.kts")
		versions("26.3").buildscript("build.unobfuscated.gradle.kts")
		vcsVersion = "26.3"
	}
}

rootProject.name = "diorama"
