// Minecraft up to 1.21.11 ships obfuscated: Loom remaps it, and the mod, to and from Mojang's names.
plugins {
	id("net.fabricmc.fabric-loom-remap")
}

// A jar that covers several Minecraft versions names them all (minecraft_label), so a player on
// 1.21.8 can tell the 1.21.6 jar is theirs.
version = "${property("mod_version")}+${findProperty("minecraft_label") ?: sc.current.version}"
group = property("mod_group") as String
base.archivesName = "diorama"

loom {
	mods {
		register("diorama") {
			sourceSet(sourceSets.main.get())
		}
	}
}

// Fabric API's client game test API exists from 1.21.4. Older versions run the same tests on a
// test-only stand-in for it (src/gameshim), set up at the end of this file.
val hasGameTestApi = sc.current.parsed >= "1.21.4"

if (hasGameTestApi) {
	fabricApi {
		configureTests {
			createSourceSet = true
			modId = "diorama-test"
			enableGameTests = false
			enableClientGameTests = true
			eula = true
		}
	}
}

dependencies {
	minecraft("com.mojang:minecraft:${property("minecraft_version")}")
	mappings(loom.officialMojangMappings())
	modImplementation("net.fabricmc:fabric-loader:${property("loader_version")}")
	modImplementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")
	// The source marks nullable types with JSpecify's @Nullable, which Minecraft only ships from
	// 1.21.11. Older versions get it at compile time only: the JVM skips annotations whose class is
	// missing, so the jar runs without it.
	if (sc.current.parsed < "1.21.11") {
		compileOnly("org.jspecify:jspecify:1.0.0")
		if (hasGameTestApi) {
			"gametestCompileOnly"("org.jspecify:jspecify:1.0.0")
		}
	}
}

apply(from = rootProject.file("gradle/common.gradle.kts"))

// The NeoForge and Forge entrypoints and metadata go in their own jars (build.neoforge.gradle.kts, build.forge.gradle.kts).
sourceSets.configureEach {
	java.exclude("dev/diorama/**/neoforge/**", "dev/diorama/**/forge/**")
}
tasks.withType<ProcessResources>().configureEach {
	exclude("META-INF/neoforge.mods.toml", "META-INF/mods.toml")
}

// The fog shader's program file, which only the versions that load shaders from json read: from
// the mod's namespace on 1.21.2-1.21.4, from the minecraft namespace (by name) before.
tasks.named<ProcessResources>("processResources") {
	if (sc.current.parsed >= "1.21.2") {
		exclude("assets/minecraft/shaders/core/diorama_fog.json")
	}
	if (sc.current.parsed < "1.21.2" || sc.current.parsed >= "1.21.5") {
		exclude("assets/diorama/shaders/core/fog.json")
	}
}

// The 1.21.11 client game test API's network synchronizer gives up at random points in our runs
// ("Detected interfacing with packets at a lower level"), and its own message says to turn it off.
// The scenarios wait for what they need rather than counting on packets arriving in lockstep.
val disableNetworkSynchronizer = "-Dfabric.client.gametest.disableNetworkSynchronizer=true"
loom.runs.matching { it.name == "clientGameTest" }.configureEach {
	vmArgs(disableNetworkSynchronizer)
}

if (hasGameTestApi) {
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
}

if (!hasGameTestApi) {
	// Below 1.21.4 the tests run on src/gameshim: a test-only stand-in for the part of Fabric's
	// client game test API they use. Neither the shim nor the tests go in the release jar.
	val main = sourceSets.main.get()
	val gameshim = sourceSets.create("gameshim") {
		compileClasspath += main.compileClasspath
		runtimeClasspath += main.runtimeClasspath
	}
	val gametest = sourceSets.create("gametest") {
		compileClasspath += main.compileClasspath + main.output + gameshim.output
		runtimeClasspath += main.runtimeClasspath + main.output + gameshim.output
	}
	dependencies {
		"gametestCompileOnly"("org.jspecify:jspecify:1.0.0")
	}
	loom {
		mods {
			register("diorama-gameshim") { sourceSet(gameshim) }
			register("diorama-test") { sourceSet(gametest) }
		}
		runs {
			register("clientGameTest") {
				client()
				source(gametest)
				property("diorama.gameshim", "true")
				runDir("build/run/clientGameTest")
				ideConfigGenerated(false)
			}
		}
	}

	// The same, with the remapped release jar in a production environment (intermediary names).
	fun remapped(name: String, from: SourceSet): TaskProvider<net.fabricmc.loom.task.RemapJarTask> {
		val dev = tasks.register<Jar>("${name}Jar") {
			from(from.output)
			archiveClassifier = "$name-dev"
			destinationDirectory = layout.buildDirectory.dir("devlibs")
		}
		return tasks.register<net.fabricmc.loom.task.RemapJarTask>("remap${name.replaceFirstChar { it.uppercase() }}Jar") {
			inputFile = dev.flatMap { it.archiveFile }
			archiveClassifier = name
		}
	}
	val remapGameshimJar = remapped("gameshim", gameshim)
	val remapGametestJar = remapped("gametest", gametest)
	val productionMods = configurations.create("productionMods") { isTransitive = false }
	dependencies {
		productionMods("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")
	}
	tasks.register<net.fabricmc.loom.task.prod.ClientProductionRunTask>("runProductionClientGameTest") {
		mods.from(tasks.named("remapJar"), remapGameshimJar, remapGametestJar, productionMods)
		jvmArgs.add("-Ddiorama.gameshim=true")
		runDir = layout.buildDirectory.dir("run-production")
	}
}
