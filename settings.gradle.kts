rootProject.name = "Iris"

pluginManagement {
    repositories {
        maven { url = uri("https://maven.fabricmc.net/") }
        maven { url = uri("https://maven.neoforged.net/releases/") }
        gradlePluginPortal()
    }
    
    // 强制所有项目使用相同的 Fabric Loom 版本，解决版本冲突
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "net.fabricmc.fabric-loom") {
                useVersion("1.16.2")
            }
        }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    // Neo complains if we don't have 21 for some reason, and I can't add 21 to an Action...
}

include("common", "fabric", "neoforge")
