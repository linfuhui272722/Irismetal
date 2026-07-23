plugins {
    id("java")
    id("idea")
    id("net.fabricmc.fabric-loom") version("1.16.2")
    id("com.github.gmazzo.buildconfig") version "5.3.5"
}

repositories {
    mavenLocal()
    maven("https://maven.parchmentmc.org/")
    maven("https://maven.caffeinemc.net/releases")
    exclusiveContent {
        forRepository {
            maven {
                name = "Modrinth"
                url = uri("https://api.modrinth.com/maven")
            }
        }
        filter {
            includeGroup("maven.modrinth")
        }
    }
}

val MINECRAFT_VERSION: String by rootProject.extra
val PARCHMENT_VERSION: String? by rootProject.extra
val FABRIC_LOADER_VERSION: String by rootProject.extra
val SODIUM_DEPENDENCY_NEO: Any by rootProject.extra
val FABRIC_API_VERSION: String by rootProject.extra

sourceSets.create("desktop")

buildConfig {
    className("BuildConfig") // forces the class name. Defaults to 'BuildConfig'
    packageName("net.irisshaders.iris") // forces the package. Defaults to '${project.group}'
    useJavaOutput()
    // TODO hook this up
    buildConfigField("IS_SHARED_BETA", false)
    buildConfigField("ACTIVATE_RENDERDOC", false)
    buildConfigField("BETA_TAG", "")
    buildConfigField("BETA_VERSION", 0)
    sourceSets.getByName("desktop") {
        buildConfigField("IS_SHARED_BETA", false)
    }
}

// === 下载 metallum 主 jar 文件 ===
val downloadMetallum by tasks.registering(Exec::class) {
    onlyIf { !file("libs/metallum-0.0.21.jar").exists() }
    commandLine(
        "curl", "-L", "-o", "libs/metallum-0.0.21.jar",
        "https://github.com/kokodio/metallum/releases/download/v0.0.21/metallum-0.0.21.jar"
    )
    doFirst {
        mkdir("libs")
    }
}

// === 下载 metallum sources jar 文件 ===
val downloadMetallumSources by tasks.registering(Exec::class) {
    onlyIf { !file("libs/metallum-0.0.21-sources.jar").exists() }
    commandLine(
        "curl", "-L", "-o", "libs/metallum-0.0.21-sources.jar",
        "https://github.com/kokodio/metallum/releases/download/v0.0.21/metallum-0.0.21-sources.jar"
    )
    doFirst {
        mkdir("libs")
    }
}

dependencies {
    minecraft(group = "com.mojang", name = "minecraft", version = MINECRAFT_VERSION)
    implementation("net.fabricmc:fabric-loader:$FABRIC_LOADER_VERSION")
    compileOnly("net.fabricmc.fabric-api:fabric-renderer-api-v1:3.2.9+1172e897d7")
    implementation(SODIUM_DEPENDENCY_NEO)
    compileOnly("org.antlr:antlr4-runtime:4.13.1")
    compileOnly("io.github.douira:glsl-transformer:3.0.0-pre3")
    compileOnly("org.anarres:jcpp:1.4.14")
    compileOnly(files(rootDir.resolve("DHApi.jar")))
    
    // Metallum - 同时添加主 jar 和 sources jar
    compileOnly(files("libs/metallum-0.0.21.jar"))
    compileOnly(files("libs/metallum-0.0.21-sources.jar"))
}

afterEvaluate {
    tasks.withType<JavaCompile> {
        options.compilerArgs.add("-Xmaxerrs")
        options.compilerArgs.add("2000")
    }
}

val vendoredJar by tasks.registering(Jar::class) {
    from(sourceSets.getByName("vendored").output)
    archiveClassifier.set("vendored")
}

val apiJar by tasks.registering(Jar::class) {
    from(sourceSets.getByName("api").output)
    archiveClassifier.set("api")
}

val headersJar by tasks.registering(Jar::class) {
    from(sourceSets.getByName("headers").output)
    archiveClassifier.set("headers")
}

configurations {
    register("vendoredJar") {
        isCanBeResolved = false
        isCanBeConsumed = true
    }
    register("apiJar") {
        isCanBeResolved = false
        isCanBeConsumed = true
    }
    register("headersJar") {
        isCanBeResolved = false
        isCanBeConsumed = true
    }
}

sourceSets {
    val main = getByName("main")
    val headers = create("headers")
    val api = create("api")
    val vendored = create("vendored")
    val desktop = getByName("desktop")

    headers.apply {
        java {
            compileClasspath += main.compileClasspath
        }
    }
    vendored.apply {
        java {
            compileClasspath += main.compileClasspath
        }
    }
    api.apply {
        java {
            compileClasspath += main.compileClasspath
        }
    }
    desktop.apply {
        java {
            srcDir("src/desktop/java")
        }
    }
    main.apply {
        java {
            compileClasspath += headers.output
            compileClasspath += api.output
            compileClasspath += vendored.output
            runtimeClasspath += api.output
            runtimeClasspath += vendored.output
        }
    }
}

artifacts {
    add("vendoredJar", vendoredJar)
    add("apiJar", apiJar)
    add("headersJar", headersJar)
}

loom {
    mixin {
        defaultRefmapName = "iris.refmap.json"
        useLegacyMixinAp = false
    }
    accessWidenerPath = file("src/main/resources/iris.accesswidener")
    mods {
        val main by creating {
            // to match the default mod generated for Forge
            sourceSet("vendored")
            sourceSet("main")
        }
    }
}

// === Metal native library build task ===
// 在 macOS 上编译 IrisMetalNative.swift 为 libiris_metal.dylib
// 非 macOS 环境下跳过（Iris 会回退到 OpenGL 路径）
val buildMetalNative by tasks.registering(Exec::class) {
    onlyIf { org.gradle.internal.os.OperatingSystem.current().isMacOsX }
    workingDir = projectDir
    val nativeSource = file("src/main/native/IrisMetalNative.swift")
    val outputDir = file("src/main/resources/natives/macos")
    outputs.dir(outputDir)
    doFirst {
        outputDir.mkdirs()
        commandLine(
            "swiftc", "-O", "-target", "arm64-apple-macos14.0",
            "-framework", "Foundation",
            "-framework", "Metal",
            "-framework", "MetalKit",
            "-framework", "QuartzCore",
            "-emit-library", nativeSource.absolutePath,
            "-o", File(outputDir, "libiris_metal.dylib").absolutePath
        )
    }
}

// 让 processResources 依赖下载和 native 构建
tasks.named("processResources") {
    dependsOn(buildMetalNative)
    dependsOn(downloadMetallum)
    dependsOn(downloadMetallumSources)
}

tasks {
    processResources {
        filesMatching("fabric.mod.json") {
            expand(mapOf("version" to project.version))
        }
    }
    getByName<JavaCompile>("compileDesktopJava") {
        sourceCompatibility = JavaVersion.VERSION_1_8.toString()
        targetCompatibility = JavaVersion.VERSION_1_8.toString()
    }
    jar {
        from(rootDir.resolve("LICENSE.md"))
        val vendored = sourceSets.getByName("vendored")
        from(vendored.output.classesDirs)
        from(vendored.output.resourcesDir)
        val api = sourceSets.getByName("api")
        from(api.output.classesDirs)
        from(api.output.resourcesDir)
        val desktop = sourceSets.getByName("desktop")
        from(desktop.output.classesDirs)
        from(desktop.output.resourcesDir)
        manifest.attributes["Main-Class"] = "net.irisshaders.iris.LaunchWarn"
    }
}

// This trick hides common tasks in the IDEA list.
tasks.configureEach {
    group = null
}
