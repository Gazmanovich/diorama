// Shared by every build script: Java level, resource expansion and the jar's contents.
val javaVersion = (project.property("java_version") as String).toInt()
val resourceProps = mapOf(
	"version" to project.version.toString(),
	"minecraft" to project.property("minecraft_dep") as String,
	"loader" to project.property("loader_version") as String,
	"java" to javaVersion.toString(),
) + listOfNotNull(
	project.findProperty("neoforge_dep")?.let { "neoforge" to it as String },
	project.findProperty("forge_dep")?.let { "forge" to it as String },
)

tasks.named<ProcessResources>("processResources") {
	val props = resourceProps
	inputs.properties(props)
	filesMatching(listOf("fabric.mod.json", "META-INF/neoforge.mods.toml", "META-INF/mods.toml", "diorama.mixins.json")) {
		expand(props)
	}
}

tasks.withType<JavaCompile>().configureEach {
	options.release.set(javaVersion)
	options.encoding = "UTF-8"
	options.compilerArgs.addAll(listOf("-Xmaxerrs", "5000"))
}

configure<JavaPluginExtension> {
	withSourcesJar()
	sourceCompatibility = JavaVersion.toVersion(javaVersion)
	targetCompatibility = JavaVersion.toVersion(javaVersion)
}

// Mod Menu's API is compiled against stand-ins in src/main; the real one comes with Mod Menu.
tasks.withType<Jar>().configureEach {
	exclude("com/terraformersmc/**")
}

tasks.named<Jar>("jar") {
	from(rootProject.file("LICENSE")) {
		rename { "${it}_diorama" }
	}
}
