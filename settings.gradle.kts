pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/")
        maven("https://maven.kikugie.dev/releases")
        mavenCentral()
        gradlePluginPortal()
    }
    resolutionStrategy.eachPlugin {
        when (requested.id.id) {
            "dev.kikugie.stonecutter" -> if (requested.version != null) useModule("dev.kikugie:stonecutter:${requested.version}")
            "net.fabricmc.fabric-loom" -> useModule("net.fabricmc:fabric-loom:${requested.version}")
        }
    }
}

plugins {
    id("dev.kikugie.stonecutter") version "0.9.8"
}

rootProject.name = "area-hints-reimagined"
stonecutter {
    create(rootProject) {
        version("26.3")
        vcsVersion = "26.3"
    }
}
