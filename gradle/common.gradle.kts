// Shared by both build scripts: Java level, resource expansion and the jar's contents.
val javaVersion = (project.property("java_version") as String).toInt()
val resourceProps = mapOf(
	"version" to project.version.toString(),
	"minecraft" to project.property("minecraft_dep") as String,
	"java" to javaVersion.toString(),
)

tasks.named<ProcessResources>("processResources") {
	val props = resourceProps
	inputs.properties(props)
	filesMatching(listOf("fabric.mod.json", "diorama.mixins.json")) {
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

tasks.named<Jar>("jar") {
	from(rootProject.file("LICENSE")) {
		rename { "${it}_diorama" }
	}
}
