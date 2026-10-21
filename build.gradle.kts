plugins {
    id("dev.architectury.loom") version "1.9-SNAPSHOT"
    kotlin("jvm") version "2.4.0"
    kotlin("plugin.serialization") version "2.4.0"
}

base {
    val minecraftVersion: String = project.property("minecraft_version") as String
    val archivesBaseName: String = project.property("archives_base_name") as String
    archivesName.set("$archivesBaseName-mc$minecraftVersion")
}

version = project.property("mod_version") as String
group = project.property("maven_group") as String

repositories {
    // maven.neoforged.net is DNS-polluted in this environment (TCP connect hangs for minutes);
    // forgecdn mirrors the same artifacts, so the official repo is intentionally NOT listed.
    // Any Gradle dependency resolution would otherwise try it in order and stall the build.
    maven("https://neoforged.forgecdn.net/releases")
    mavenCentral()
    maven("https://maven.fabricmc.net/")
    maven("https://maven.architectury.dev/")
    maven("https://maven.shedaniel.me/")
    maven("https://thedarkcolour.github.io/KotlinForForge/")
    maven("https://jitpack.io/")
    maven("https://maven.su5ed.dev/releases")
}

// mahjong4j 0.3.2 的 POM 误依赖 org.apache.maven.plugins:maven-gpg-plugin:1.6 (Maven 构建插件),
// 会拖入整条 Maven 2.2.1 工具链 (maven-project/maven-artifact-manager/...)。
// 其中 maven-artifact-manager-2.2.1 与 neoforge 依赖的 maven-artifact:3.8.5 在
// org.apache.maven.artifact.resolver 包上冲突: securejarhandler 的 union 文件系统中旧 jar 的
// 目录视图会遮蔽新 jar 的类文件, 导致 dev 启动时 FML 的 MavenVersionAdapter
// NoClassDefFoundError: ArtifactResolutionException。此处整条切断。
configurations.all {
    exclude(mapOf("group" to "org.apache.maven.plugins", "module" to "maven-gpg-plugin"))
}

dependencies {
    val minecraftVersion: String = project.property("minecraft_version") as String
    minecraft("com.mojang:minecraft:$minecraftVersion")
    val yarnMappings: String = project.property("yarn_mappings") as String
    val yarnPatch: String = project.property("yarn_patch") as String
    mappings(loom.layered {
        mappings("net.fabricmc:yarn:$yarnMappings:v2")
        mappings("dev.architectury:yarn-mappings-patch-neoforge:$yarnPatch")
    })
    val neoforgeVersion: String = project.property("neoforge_version") as String
    neoForge("net.neoforged:neoforge:$neoforgeVersion")

    // Loom 的 NeoForge dev-launch 类路径会漏掉 net.neoforged:neoforge POM 中声明的 maven-artifact,
    // 导致 FML 的 MavenVersionAdapter 在解析 mods.toml versionRange 时 NoClassDefFoundError
    // (org.apache.maven.artifact.resolver.ArtifactResolutionException)。生产环境不受影响, 仅 dev 运行需要。
    runtimeOnly("org.apache.maven:maven-artifact:3.8.5")

    // Kotlin for NeoForge (provides Kotlin stdlib/coroutines/serialization + object entrypoints)
    val kotlinforforgeVersion: String = project.property("kotlinforforge_version") as String
    implementation("thedarkcolour:kotlinforforge-neoforge:$kotlinforforgeVersion")

    // Cloth Config (NeoForge edition, external dependency - not bundled)
    val clothConfigVersion: String = project.property("cloth_config_version") as String
    modApi("me.shedaniel.cloth:cloth-config-neoforge:$clothConfigVersion")

    // CialloLib: 從原本內嵌的 LibGui 移植版抽出的獨立 GUI 函式庫模組 (../CialloLib)。
    // 編譯用 dev jar (yarn 命名); 執行期由使用者把 build/libs 的 ciallolib jar 一起放進 mods/。
    compileOnly(files("libs/ciallolib-mc1.21.1-1.0.0+1.21.1-dev.jar"))

    // Bundled (JiJ) pure-Java libraries
    // compileOnly 让它们在编译期可见; forgeRuntimeLibrary + include 负责 JiJ 打包与开发运行时可见
    // mahjong4j 的排除必须写在依赖声明本身: Loom 的 minecraftClasspath 解析走
    // detachedConfiguration(不继承配置级 exclude), 但 DependencyEntry$Direct 会原样保留
    // Dependency 对象自带的 exclude 规则, 因此依赖级排除可以穿透。
    compileOnly("com.github.mahjong4j:mahjong4j:0.3.2") {
        exclude(group = "org.apache.maven.plugins", module = "maven-gpg-plugin")
    }
    forgeRuntimeLibrary(
        include(
            create("com.github.mahjong4j:mahjong4j:0.3.2") {
                exclude(group = "org.apache.maven.plugins", module = "maven-gpg-plugin")
            }
        )!!
    )
}

tasks {
    val javaVersion = JavaVersion.VERSION_21
    compileJava {
        options.encoding = "UTF-8"
        options.release.set(javaVersion.toString().toInt())
        sourceCompatibility = "$javaVersion"
        targetCompatibility = "$javaVersion"
    }
    compileKotlin {
        compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
    processResources {
        inputs.property("version", project.version)
        filesMatching("META-INF/neoforge.mods.toml") {
            expand(mutableMapOf("version" to project.version))
        }
    }
    jar {
        // 注意: base.archivesName 是 Property<String>, 字符串模板会插值出 Property 对象本身, 必须 .get()
        from("LICENSE") { rename { "${it}_${base.archivesName.get()}" } }
    }
    java {
        sourceCompatibility = javaVersion
        targetCompatibility = javaVersion
        withSourcesJar()
    }
}
