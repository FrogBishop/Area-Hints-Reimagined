plugins {
    id("net.fabricmc.fabric-loom") version "1.18.4"
}

val gameVersion = stonecutter.current.version
group = "io.github.imsng1019"
version = "${property("modVersion")}+${gameVersion}"
base.archivesName = "area-hints-reimagined"

repositories { mavenCentral() }

dependencies {
    minecraft("com.mojang:minecraft:$gameVersion")
    implementation("net.fabricmc:fabric-loader:${property("fabricLoader")}")
    implementation("net.fabricmc.fabric-api:fabric-api:${property("fabricApi")}")
    testImplementation("junit:junit:4.13.2")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}
val testDedicatedServer = providers.gradleProperty("acceptMinecraftEula").map(String::toBooleanStrict).orElse(false)
fabricApi.configureTests {
    createSourceSet = true
    modId = "ahr_gametest"
    enableGameTests = false
    enableClientGameTests = true
    eula = testDedicatedServer
}
loom.runs.named("clientGameTest") {
    property("ahr.testDedicatedServer", testDedicatedServer.get().toString())
}
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 25
}
tasks.processResources {
    val metadata = mapOf("version" to project.version, "minecraft" to gameVersion,
        "loader" to project.property("fabricLoader"), "api" to project.property("fabricApi"))
    inputs.properties(metadata)
    filesMatching("fabric.mod.json") { expand(metadata) }
}
tasks.jar { from(rootProject.file("LICENSE")) }
