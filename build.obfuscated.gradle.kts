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

// The 1.21.11 client game test API's network synchronizer gives up at random points in our runs
// ("Detected interfacing with packets at a lower level"), and its own message says to turn it off.
// The scenarios wait for what they need rather than counting on packets arriving in lockstep.
val disableNetworkSynchronizer = "-Dfabric.client.gametest.disableNetworkSynchronizer=true"
loom.runs.matching { it.name == "clientGameTest" }.configureEach {
	vmArgs(disableNetworkSynchronizer)
}

// The tests, run against the remapped release jar in a production environment. In development the
// game uses Mojang's names; a real install uses intermediary ones, so anything that reflects on the
// game by name only fails there.
val gametest = sourceSets.named("gametest")
val gametestJar = tasks.register<Jar>("gametestJar") {
	from(gametest.map { it.output })
	archiveClassifier = "gametest-dev"
	destinationDirectory = layout.buildDirectory.dir("devlibs")
}
val remapGametestJar = tasks.register<net.fabricmc.loom.task.RemapJarTask>("remapGametestJar") {
	inputFile = gametestJar.flatMap { it.archiveFile }
	archiveClassifier = "gametest"
}
// Fabric API as published, in intermediary names, plus the client game test module it doesn't bundle.
val productionMods = configurations.create("productionMods") { isTransitive = false }
dependencies {
	productionMods("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")
	productionMods("net.fabricmc.fabric-api:fabric-client-gametest-api-v1:${property("client_gametest_api_version")}")
}
tasks.register<net.fabricmc.loom.task.prod.ClientProductionRunTask>("runProductionClientGameTest") {
	mods.from(tasks.named("remapJar"), remapGametestJar, productionMods)
	jvmArgs.add("-Dfabric.client.gametest")
	jvmArgs.add(disableNetworkSynchronizer)
	runDir = layout.buildDirectory.dir("run-production")
	// The multiplayer scenario starts a dedicated server, which won't run without the EULA accepted;
	// the development test run gets this from configureTests' eula = true.
	val eula = runDir.file("eula.txt")
	doFirst {
		eula.get().asFile.apply {
			parentFile.mkdirs()
			writeText("eula=true\n")
		}
	}
}
