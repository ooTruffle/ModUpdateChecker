val modName = project.property("mod_name") as String
val modId = project.property("mod_id") as String
val modVersion = project.property("mod_version") as String
val modDescription = project.property("mod_description") as String
val modArchivesName = project.property("mod_archives_name") as String
val baseGroup = project.property("base_group") as String

val javaVersion = project.property("java_version") as String
val minecraftVersion = project.property("minecraft_version") as String
val fabricLoaderVersion = project.property("fabric_loader_version") as String
val oslEntrypointsVersion = project.property("osl_entrypoints_version") as String
val oslCoreVersion = project.property("osl_core_version") as String
val mcpMappings = project.property("mcp_mappings") as String
val polycomposeVersion = project.property("polycompose_version") as String
val kotlinVersion = project.property("kotlin_version") as String

group = baseGroup
version = "$modVersion+$minecraftVersion"

plugins {
    id("net.fabricmc.fabric-loom-remap") version "1.19-SNAPSHOT"
    id("ploceus") version "1.19-SNAPSHOT"
    // The UI is drawn with PolyCompose; use the Kotlin version PolyCompose is built with.
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.compose") version "2.4.21"
}

repositories {
    mavenCentral()
    google()
    maven("https://repo.polyfrost.org/releases")
    maven("https://repo.polyfrost.org/snapshots")
    maven("https://redirector.kotlinlang.org/maven/compose-dev")
    maven("https://central.sonatype.com/repository/maven-snapshots")
    maven("https://maven.cloverclient.com/releases")
    maven("https://repo.papermc.io/repository/maven-public/")
    exclusiveContent {
        forRepository { maven("https://api.modrinth.com/maven") }
        filter { includeGroup("maven.modrinth") }
    }
    exclusiveContent {
        forRepository { maven("https://maven.deftu.dev/releases") }
        filter { includeGroup("dev.deftu") }
    }
}

ploceus {
    setIntermediaryGeneration(2)
}

base {
    archivesName.set("$modArchivesName-$modVersion-${minecraftVersion}_ornithe")
}

loom {
    runConfigs.remove(runConfigs["server"])
}

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    mappings(ploceus.mcpMappings("stable", minecraftVersion, mcpMappings))
    modImplementation("net.fabricmc:fabric-loader:$fabricLoaderVersion")
    modImplementation("net.ornithemc.osl-gen2:entrypoints:$oslEntrypointsVersion")
    modImplementation("net.ornithemc.osl-gen2:core:$oslCoreVersion")

    // Required at runtime (never bundled): it draws the updates screen.
    modImplementation("org.polyfrost:polycompose-$minecraftVersion-ornithe:$polycomposeVersion")
    compileOnly("org.jetbrains.kotlin:kotlin-stdlib:$kotlinVersion")

    // Optional at runtime and never bundled: with it, the mod adds a page to OneConfig's menu
    // (only loaded when OneConfig is installed) and the screen follows its theme. Dev runs only
    // load it with -Pmodupdatechecker.devOneConfig=true.
    val oneconfigVersion = project.property("oneconfig_version") as String
    val devOneConfig = providers.gradleProperty("modupdatechecker.devOneConfig").orNull == "true"
    if (devOneConfig) {
        modImplementation("org.polyfrost.oneconfig:$minecraftVersion-ornithe:$oneconfigVersion")
    } else {
        modCompileOnly("org.polyfrost.oneconfig:$minecraftVersion-ornithe:$oneconfigVersion") { isTransitive = false }
    }
    for (module in arrayOf("config", "config-impl", "utils", "ui", "notifications")) {
        if (devOneConfig) {
            implementation("org.polyfrost.oneconfig:$module:$oneconfigVersion")
        } else {
            compileOnly("org.polyfrost.oneconfig:$module:$oneconfigVersion") { isTransitive = false }
        }
    }
}

tasks.processResources {
    val props = mapOf(
        "mod_id" to modId,
        "mod_name" to modName,
        "mod_version" to modVersion,
        "mod_description" to modDescription,
        "base_group" to baseGroup,
        "java_version" to javaVersion,
        "minecraft_version" to minecraftVersion,
        "fabric_loader_version" to fabricLoaderVersion,
    )
    inputs.properties(props)
    filesMatching(listOf("fabric.mod.json", "mixins.$modId.json")) {
        expand(props)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = javaVersion.toInt()
    options.encoding = "UTF-8"
}

java {
    withSourcesJar()
    sourceCompatibility = JavaVersion.toVersion(javaVersion)
    targetCompatibility = JavaVersion.toVersion(javaVersion)
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(javaVersion))
    }
}

kotlin {
    jvmToolchain(javaVersion.toInt())
}

tasks.jar {
    inputs.property("archivesName", base.archivesName)
}

// Keep the unexpanded metadata out of the sources jar, so it can't break mod discovery if it ends
// up in a mods folder.
tasks.named<org.gradle.api.tasks.bundling.Jar>("sourcesJar") {
    exclude("fabric.mod.json", "mixins.*.json")
}
