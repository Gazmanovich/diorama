// Minecraft 26.1 and later ship with Mojang's names, so nothing is remapped.
plugins {
	id("net.fabricmc.fabric-loom")
}

version = "${property("mod_version")}+${sc.current.version}"
group = property("mod_group") as String
base.archivesName = "diorama"

loom {
	mods {
		register("diorama") {
			sourceSet(sourceSets.main.get())
		}
	}
}

fabricApi {
	configureTests {
		createSourceSet = true
		modId = "diorama-test"
		enableGameTests = false
		enableClientGameTests = true
		eula = true
	}
}

dependencies {
	minecraft("com.mojang:minecraft:${property("minecraft_version")}")
	implementation("net.fabricmc:fabric-loader:${property("loader_version")}")
	implementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")
}

apply(from = rootProject.file("gradle/common.gradle.kts"))

// The NeoForge and Forge entrypoints and metadata go in their own jars (build.neoforge.gradle.kts, build.forge.gradle.kts).
sourceSets.configureEach {
	java.exclude("dev/diorama/**/neoforge/**", "dev/diorama/**/forge/**")
}
tasks.withType<ProcessResources>().configureEach {
	exclude("META-INF/neoforge.mods.toml", "META-INF/mods.toml")
}

// Fog shader program files that only versions before 1.21.5 read.
tasks.named<ProcessResources>("processResources") {
	exclude("assets/minecraft/shaders/core/diorama_fog.json", "assets/diorama/shaders/core/fog.json")
}
