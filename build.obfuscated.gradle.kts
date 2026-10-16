// Minecraft up to 1.21.11 ships obfuscated: Loom remaps it, and the mod, to and from Mojang's names.
plugins {
	id("net.fabricmc.fabric-loom-remap")
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
	mappings(loom.officialMojangMappings())
	modImplementation("net.fabricmc:fabric-loader:${property("loader_version")}")
	modImplementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")
}

apply(from = rootProject.file("gradle/common.gradle.kts"))
