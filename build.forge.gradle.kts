// Forge, through ModDevGradle's plugin for Forge, which works like build.neoforge.gradle.kts's.
// Before 1.20.6 Forge runs a real install on its own (SRG) names: development runs on Mojang's, and
// the release jar and the mixins' reference map are remapped to SRG (reobfJar).
plugins {
	id("net.neoforged.moddev.legacyforge")
}

// The loader goes in the version, as on NeoForge: diorama-0.5.0+forge-1.20.1.jar.
version = "${property("mod_version")}+forge-${findProperty("minecraft_label") ?: sc.current.version}"
group = property("mod_group") as String
base.archivesName = "diorama"

// The client game tests run on src/gameshim, the test-only stand-in for Fabric's client game test
// API, as on NeoForge. Neither goes in the release jar.
val main = sourceSets.main.get()
val gameshim = sourceSets.create("gameshim")
val gametest = sourceSets.create("gametest") {
	compileClasspath += main.output + gameshim.output
	runtimeClasspath += main.output + gameshim.output
}

legacyForge {
	version = "${property("minecraft_version")}-${property("forge_version")}"
	addModdingDependenciesTo(gameshim)
	addModdingDependenciesTo(gametest)

	mods {
		register("diorama") { sourceSet(main) }
		register("diorama_gameshim") { sourceSet(gameshim) }
		register("diorama_test") { sourceSet(gametest) }
	}

	runs {
		register("client") {
			client()
			loadedMods = listOf(mods["diorama"])
		}
		// ./gradlew :1.20.1-forge:runClientGameTest, like the other versions' task.
		register("clientGameTest") {
			client()
			sourceSet = gametest
			loadedMods = listOf(mods["diorama"], mods["diorama_gameshim"], mods["diorama_test"])
			gameDirectory = project.file("build/run/clientGameTest")
			systemProperty("diorama.gameshim", "true")
			// The shim's mixins, which only this run loads (the mixin block below lists the rest).
			programArguments.addAll("--mixin.config", "diorama-gameshim.mixins.json")
		}
	}
}

// The mixins' reference maps tell them the SRG names a real install runs on. The configs listed
// here are for every development run; a real install finds a jar's in its manifest.
mixin {
	add(main, "diorama.refmap.json")
	add(gameshim, "diorama-gameshim.refmap.json")
	config("diorama.mixins.json")
	config("mixinextras.init.mixins.json")
}
tasks.named<Jar>("jar") {
	manifest.attributes("MixinConfigs" to "diorama.mixins.json")
}
// ModDevGradle remaps every jar with the mappings of every source set's mixins, the shim's too.
tasks.named("reobfJar") {
	dependsOn(tasks.named("compileGameshimJava"))
}

// The shim and the tests as jars for a real install, in SRG names like the release jar
// (./gradlew :1.20.1-forge:reobfGameshimJar :1.20.1-forge:reobfGametestJar). Not released.
for ((name, sourceSet) in listOf("gameshim" to gameshim, "gametest" to gametest)) {
	val devJar = tasks.register<Jar>("${name}Jar") {
		from(sourceSet.output)
		archiveClassifier = "$name-dev"
		destinationDirectory = layout.buildDirectory.dir("devlibs")
		if (sourceSet == gameshim) {
			manifest.attributes("MixinConfigs" to "diorama-gameshim.mixins.json")
		}
	}
	// The task obfuscation.reobfuscate makes, without publishing the jar as the source set's.
	tasks.register<net.neoforged.moddevgradle.legacyforge.tasks.RemapJar>("reobf${name.replaceFirstChar { it.uppercase() }}Jar") {
		input = devJar.flatMap { it.archiveFile }
		libraries.from(sourceSet.compileClasspath)
		obfuscation.configureNamedToSrgOperation(remapOperation)
		// That replaces the mappings ModDevGradle gives every remap: the mixins' own, which the
		// Mixin annotation processor writes as it compiles.
		dependsOn(tasks.named("compileJava"), tasks.named("compileGameshimJava"))
		remapOperation.mappings.from(fileTree(layout.buildDirectory.dir("mixin")) { include("*.mappings.tsrg") })
		archiveClassifier = name
	}
}

dependencies {
	annotationProcessor("org.spongepowered:mixin:0.8.5:processor")
	"gameshimAnnotationProcessor"("org.spongepowered:mixin:0.8.5:processor")
	// Forge 1.20.1 doesn't ship MixinExtras, which some mixins use (@Local): the jar carries it.
	compileOnly(annotationProcessor("io.github.llamalad7:mixinextras-common:${property("mixinextras_version")}")!!)
	"gameshimCompileOnly"("io.github.llamalad7:mixinextras-common:${property("mixinextras_version")}")
	"gameshimAnnotationProcessor"("io.github.llamalad7:mixinextras-common:${property("mixinextras_version")}")
	jarJar("io.github.llamalad7:mixinextras-forge:${property("mixinextras_version")}")
	// Development runs don't open jars in jars, so they get MixinExtras as two plain libraries: its
	// Forge jar's start-up mixin config (mixin block above) and the MixinExtras jar inside it.
	"additionalRuntimeClasspath"("io.github.llamalad7:mixinextras-forge:${property("mixinextras_version")}")
	"additionalRuntimeClasspath"("io.github.llamalad7:mixinextras-common:${property("mixinextras_version")}")
	compileOnly("org.jspecify:jspecify:1.0.0")
	"gameshimCompileOnly"("org.jspecify:jspecify:1.0.0")
	"gametestCompileOnly"("org.jspecify:jspecify:1.0.0")
}

apply(from = rootProject.file("gradle/common.gradle.kts"))

// The other loaders' entrypoints and metadata go in their own jars.
sourceSets.configureEach {
	java.exclude("dev/diorama/**/fabric/**", "dev/diorama/**/neoforge/**")
}
tasks.withType<ProcessResources>().configureEach {
	exclude("fabric.mod.json", "META-INF/neoforge.mods.toml")
}

// The mixin configs name their reference map only here: Loom adds it to the Fabric jars'. Forge
// also logs an error for a config without the Mixin version it needs.
tasks.withType<ProcessResources>().configureEach {
	val refmaps = mapOf("diorama.mixins.json" to "diorama.refmap.json", "diorama-gameshim.mixins.json" to "diorama-gameshim.refmap.json")
	inputs.property("mixinConfigAdditions", refmaps.toString() + " minVersion 0.8")
	filesMatching(refmaps.keys) {
		val added = "\n\t\"refmap\": \"${refmaps.getValue(name)}\"," + if (name == "diorama.mixins.json") "\n\t\"minVersion\": \"0.8\"," else ""
		filter { line -> if (line.trim() == "\"required\": true,") line + added else line }
	}
}

// Forge 1.20.1 leaves out a mod's assets (translations, textures, shaders) unless the jar has a
// pack.mcmeta. Fabric and NeoForge don't need one, so it's written for the Forge jars only.
val packMetadataDir = layout.buildDirectory.dir("generated/packmetadata")
val generatePackMetadata = tasks.register("generatePackMetadata") {
	val output = packMetadataDir
	outputs.dir(output)
	doLast {
		// 15 is 1.20.1's resource and data pack format.
		output.get().file("pack.mcmeta").asFile.apply {
			parentFile.mkdirs()
			writeText("{\"pack\": {\"description\": \"Diorama\", \"pack_format\": 15}}\n")
		}
	}
}
for (sourceSet in listOf(main, gameshim, gametest)) {
	sourceSet.resources.srcDir(generatePackMetadata)
}

// The fog shader's program file: 1.20.1 loads it from the minecraft namespace, by name.
tasks.named<ProcessResources>("processResources") {
	exclude("assets/diorama/shaders/core/fog.json")
}
