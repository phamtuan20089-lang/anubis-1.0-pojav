plugins {
    id("fabric-loom") version "1.16.3"
}

version = project.property("mod_version")!!
group = project.property("maven_group")!!

base {
    archivesName.set(project.property("archives_base_name") as String)
}

repositories {
    mavenCentral()
    exclusiveContent {
        forRepository { maven("https://api.modrinth.com/maven") }
        filter { includeGroup("maven.modrinth") }
    }
}

loom {
    splitEnvironmentSourceSets()

    mods {
        register("anubisplus") {
            sourceSet(sourceSets["main"])
            sourceSet(sourceSets["client"])
        }
    }

    mixin {
        useLegacyMixinAp.set(false)
    }
}

val fabricVersion = project.property("fabric_version") as String
val imguiVersion = project.property("imgui_version") as String

dependencies {
    minecraft("com.mojang:minecraft:${project.property("minecraft_version")}")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:${project.property("loader_version")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:$fabricVersion")
    modCompileOnly("maven.modrinth:litematica:${project.property("litematica_version")}")
    modCompileOnly("maven.modrinth:malilib:${project.property("malilib_version")}")

    listOf(
        "fabric-api-base",
        "fabric-key-binding-api-v1",
        "fabric-lifecycle-events-v1",
        "fabric-rendering-fluids-v1",
        "fabric-resource-loader-v0",
        "fabric-resource-loader-v1",
        "fabric-screen-api-v1",
    ).forEach { include(fabricApi.module(it, fabricVersion)) }

    listOf(
        "imgui-java-binding",
        "imgui-java-lwjgl3",
        "imgui-java-natives-linux",
        "imgui-java-natives-macos",
        "imgui-java-natives-windows",
    ).forEach {
        implementation("io.github.spair:$it:$imguiVersion")
        include("io.github.spair:$it:$imguiVersion")
    }
}

tasks {
    processResources {
        val props = mapOf("version" to project.version.toString())
        inputs.properties(props)
        filesMatching("fabric.mod.json") { expand(props) }
    }

    withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(21)
    }

    named<JavaCompile>("compileJava") {
        options.release.set(8)
        options.compilerArgs.add("-Xlint:-options")
    }

    jar {
        from("LICENSE") {
            rename { "${it}_${project.base.archivesName.get()}" }
        }
        manifest {
            attributes(
                "Implementation-Title" to "Anubis Client",
                "Implementation-Version" to project.version,
                "Implementation-Vendor" to "Anubis",
                "Specification-Title" to "Anubis Client",
                "Specification-Version" to project.version,
                "Specification-Vendor" to "Anubis",
                "Main-Class" to "net.anubis.launcher.InstallNotice",
            )
        }
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

val generateBuildConfig by tasks.registering {
    val debug = providers.gradleProperty("debug_build").map(String::toBoolean).orElse(false)
    val signCert = providers.gradleProperty("sign_cert").orElse("")
    val outputDir = layout.buildDirectory.dir("generated/sources/buildConfig/client")
    inputs.property("debug", debug)
    inputs.property("signCert", signCert)
    outputs.dir(outputDir)
    doLast {
        val parts = signCert.get().lowercase().filter { it.isLetterOrDigit() }.chunked(13).joinToString(", ") { "\"$it\"" }
        val file = outputDir.get().file("net/anubis/client/BuildConfig.java").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            |package net.anubis.client;
            |
            |import net.fabricmc.api.EnvType;
            |import net.fabricmc.api.Environment;
            |
            |@Environment(EnvType.CLIENT)
            |public final class BuildConfig {
            |    public static final boolean DEBUG = ${debug.get()};
            |    public static final String[] SIGN_CERT_PARTS = new String[]{$parts};
            |
            |    private BuildConfig() {
            |    }
            |}
            |""".trimMargin()
        )
    }
}

sourceSets["client"].java.srcDir(generateBuildConfig)
