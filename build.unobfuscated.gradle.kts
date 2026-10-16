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
