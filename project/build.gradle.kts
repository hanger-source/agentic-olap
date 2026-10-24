import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import java.security.MessageDigest
import java.util.zip.ZipFile

plugins {
    base
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.protobuf) apply false
    alias(libs.plugins.shadow) apply false
}

val productVersion = (JsonSlurper().parse(file("cli/package.json")) as Map<*, *>)["version"].toString()
val sdkReleaseTag = "v$productVersion"
if (System.getenv("JITPACK") == "true") {
    require(System.getenv("VERSION") == sdkReleaseTag) { "JitPack must build the exact product tag $sdkReleaseTag" }
}
allprojects {
    group = "com.github.hanger-source.agentic-olap"
    version = sdkReleaseTag
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "maven-publish")

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(21))
        withSourcesJar()
    }
    extensions.configure<KotlinJvmProjectExtension> { jvmToolchain(21) }
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions.freeCompilerArgs.addAll("-Xjvm-default=all", "-Xjsr305=strict")
    }
    tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }
    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
    tasks.withType<Jar>().configureEach {
        from(rootProject.file("../LICENSE")) { into("META-INF") }
    }
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging { events("failed", "skipped"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
    }
    extensions.configure<PublishingExtension> {
        publications.create<MavenPublication>("library") {
            from(components["java"])
            pom {
                name.set(project.name)
                description.set("Agentic OLAP ${project.name}")
                url.set("https://github.com/hanger-source/agentic-olap")
                licenses { license { name.set("Apache License, Version 2.0"); url.set("https://www.apache.org/licenses/LICENSE-2.0.txt") } }
                scm { url.set("https://github.com/hanger-source/agentic-olap"); connection.set("scm:git:https://github.com/hanger-source/agentic-olap.git") }
                developers { developer { id.set("hanger-source"); name.set("hanger"); url.set("https://github.com/hanger-source") } }
            }
        }
    }
}

val sdkProjects = subprojects.filter { it.projectDir.parentFile.name == "kernel" || it.name == "connector-loader" }
configure(sdkProjects) {
    extensions.configure<JavaPluginExtension> { withJavadocJar() }
}
tasks.register("publishProductToMavenLocal") {
    description = "为 JitPack 发布 JVM 服务、连接器与 SDK。"
    dependsOn(subprojects.filter { it.name !in setOf("integration-tests", "examples", "development", "bootstrap") }
        .map { "${it.path}:publishLibraryPublicationToMavenLocal" })
}
val sdkDependencyProjects = providers.provider {
    (sdkProjects + sdkProjects.flatMap { module ->
        module.configurations.getByName("runtimeClasspath").incoming.resolutionResult.allComponents.mapNotNull {
            val identity = it.id as? org.gradle.api.artifacts.component.ProjectComponentIdentifier
            identity?.let { project(it.projectPath) }
        }
    }).distinctBy { it.path }.sortedBy { it.path }
}
tasks.register("stageSdk") {
    description = "按正式 Maven 坐标装配 SDK 仓库，供独立插件构建验收。"
    dependsOn(sdkDependencyProjects.map { modules -> modules.flatMap { module ->
        listOf("${module.path}:jar", "${module.path}:sourcesJar", "${module.path}:generatePomFileForLibraryPublication")
    } })
    doLast {
        sync {
            into(layout.buildDirectory.dir("sdk/maven"))
            sdkDependencyProjects.get().forEach { module ->
                val directory = "${module.group.toString().replace('.', '/')}/${module.name}/${module.version}"
                for (name in listOf("jar", "sourcesJar")) {
                    from(module.tasks.named<Jar>(name).flatMap { it.archiveFile }) { into(directory) }
                }
                from(module.layout.buildDirectory.file("publications/library/pom-default.xml")) {
                    into(directory)
                    rename { "${module.name}-${module.version}.pom" }
                }
            }
        }
    }
}

// 参考插件只私有携带 JDBC 实现；共同合同、Kotlin 与 Calcite 由宿主提供。
configure(subprojects.filter { it.projectDir.parentFile.name == "connectors" }) {
    apply(plugin = "com.gradleup.shadow")
    tasks.named<ShadowJar>("shadowJar") {
        archiveClassifier.set("plugin")
        dependencies { include(project(":warehouse-jdbc")) }
        relocate("com.olap.prototype.warehouse.jdbc", "com.olap.connectors.jdbc")
        for (name in listOf("JdbcProvider", "inspection.JdbcWarehouseInspector", "inspection.CalciteRelationshipProfiler", "inspection.JdbcInspectionValues", "inspection.JdbcMetadataReader")) {
            relocate("com.olap.prototype.warehouse.$name", "com.olap.connectors.jdbc.${name.substringAfterLast('.')}")
        }
    }
    tasks.named("assemble") { dependsOn("shadowJar") }
}

fun sha256(file: File): String = file.inputStream().use { stream ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(65536)
    while (true) {
        val count = stream.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
    }
    digest.digest().joinToString("") { "%02x".format(it) }
}

val writeRuntimeLicenses by tasks.registering {
    val destination = layout.buildDirectory.dir("runtime-licenses")
    outputs.dir(destination)
    outputs.upToDateWhen { false }
    doLast {
        val root = destination.get().asFile.apply { mkdirs() }
        copy {
            from(file("distribution-resources/META-INF/third-party"))
            into(root.resolve("additional-notices"))
        }
        val artifacts = (listOf(":server", ":worker-runtime", ":script-worker", ":bootstrap", ":dsl-api")
            .map { project(it).configurations.getByName("runtimeClasspath") }
            + listOf(":connector-h2", ":connector-duckdb").map { project(it).configurations.getByName("driver") })
            .flatMap { it.resolvedConfiguration.resolvedArtifacts }
            .filter { it.id.componentIdentifier is ModuleComponentIdentifier }.distinctBy { it.file.canonicalPath }
            .sortedBy { it.moduleVersion.id.toString() }
        val attributions = artifacts.map { artifact ->
            val coordinate = artifact.moduleVersion.id.toString()
            val notices = mutableListOf<String>()
            ZipFile(artifact.file).use { zip ->
                for (entry in zip.entries()) {
                    val name = entry.name.substringAfterLast('/').lowercase()
                    if (entry.isDirectory || !(name.startsWith("license") || name.startsWith("notice") || name.startsWith("copying"))) continue
                    val target = root.resolve(coordinate.replace(':', '/') + "/" + entry.name)
                    require(target.canonicalPath.startsWith(root.canonicalPath + File.separator)) { "dependency license path leaves attribution root" }
                    target.parentFile.mkdirs()
                    zip.getInputStream(entry).use { input -> target.outputStream().use { input.copyTo(it) } }
                    notices += target.relativeTo(root).invariantSeparatorsPath
                }
            }
            mapOf("coordinate" to coordinate, "jar" to artifact.file.name,
                "sha256" to sha256(artifact.file), "embeddedNotices" to notices)
        }
        root.resolve("dependencies.json").writeText(JsonOutput.prettyPrint(JsonOutput.toJson(attributions)) + "\n")
    }
}

// 构件身份来自真实 Gradle project 和解析后的依赖；发行脚本不维护第二份模块表。
val writeJvmManifest by tasks.registering {
    val manifest = layout.buildDirectory.file("jvm-artifacts.json")
    outputs.file(manifest)
    outputs.upToDateWhen { false }
    dependsOn(subprojects.map { "${it.path}:jar" })
    dependsOn(subprojects.map { "${it.path}:generatePomFileForLibraryPublication" })
    dependsOn(":connector-h2:copyDriver", ":connector-duckdb:copyDriver")
    dependsOn(":connector-h2:shadowJar", ":connector-duckdb:shadowJar")
    dependsOn(writeRuntimeLicenses)
    doLast {
        fun identity(file: File) = mapOf("path" to file.canonicalPath, "sha256" to sha256(file))
        val modules = subprojects.filter { it.name !in setOf("integration-tests", "examples", "development", "bootstrap") }.sortedBy { it.name }.map { module ->
            val jarTask = if (module.projectDir.parentFile.name == "connectors") "shadowJar" else "jar"
            val jar = module.tasks.named<Jar>(jarTask).get().archiveFile.get().asFile
            val pom = module.layout.buildDirectory.file("publications/library/pom-default.xml").get().asFile
            val category = when (module.projectDir.parentFile.name) {
                "kernel" -> "kernel"
                "connectors" -> "connector"
                else -> "internal"
            }
            mapOf("groupId" to module.group.toString(), "artifactId" to module.name,
                "version" to module.version.toString(), "directory" to module.projectDir.relativeTo(rootDir).invariantSeparatorsPath,
                "category" to category, "jar" to identity(jar), "pom" to identity(pom))
        }
        val mainClasses = mapOf("server" to (":server" to "com.olap.prototype.server.ServerMainKt"),
            "worker" to (":worker-runtime" to "com.olap.prototype.runtime.ApplicationMainKt"),
            "script" to (":script-worker" to "com.olap.prototype.runtime.ScriptMainKt"))
        val applications = mainClasses.mapValues { (_, entry) ->
            val module = project(entry.first)
            val jar = module.tasks.named<Jar>("jar").get().archiveFile.get().asFile
            mapOf("mainClass" to entry.second, "classpath" to
                (module.configurations.getByName("runtimeClasspath").files + jar).sortedBy { it.name }.map(::identity))
        }
        val dsl = project(":dsl-api")
        val dslJar = dsl.tasks.named<Jar>("jar").get().archiveFile.get().asFile
        val drivers = listOf(":connector-h2", ":connector-duckdb").associate { path ->
            val module = project(path)
            module.name to module.configurations.getByName("driver").files.map(::identity)
        }
        val external = (mainClasses.values.flatMap { project(it.first).configurations.getByName("runtimeClasspath").resolvedConfiguration.resolvedArtifacts } +
            dsl.configurations.getByName("runtimeClasspath").resolvedConfiguration.resolvedArtifacts +
            listOf(":connector-h2", ":connector-duckdb").flatMap { project(it).configurations.getByName("driver").resolvedConfiguration.resolvedArtifacts })
            .filter { it.id.componentIdentifier is ModuleComponentIdentifier }.distinctBy { it.file.canonicalPath }
            .sortedBy { it.file.name }.map { artifact ->
                require(artifact.extension == "jar") { "runtime dependency must be a Maven JAR: ${artifact.id}" }
                val coordinate = artifact.moduleVersion.id
                val classifier = artifact.classifier?.let { "-$it" } ?: ""
                val path = "${coordinate.group.replace('.', '/')}/${coordinate.name}/${coordinate.version}/${artifact.name}-${coordinate.version}$classifier.jar"
                mapOf("file" to identity(artifact.file), "coordinate" to "${coordinate.group}:${coordinate.name}:${coordinate.version}${artifact.classifier?.let { ":$it" } ?: ""}",
                    "url" to "https://repo.maven.apache.org/maven2/$path", "size" to artifact.file.length())
            }
        val connectorApi = file("modules/kernel/connector-api/src/main/kotlin/com/olap/prototype/connector/OlapConnector.kt").readText()
            .let { Regex("const val API_VERSION\\s*=\\s*\"([^\"]+)\"").find(it)?.groupValues?.get(1) ?: error("连接器缺少明确 API_VERSION") }
        val document = mapOf("formatVersion" to "agentic-olap-jvm-build/v5", "version" to productVersion,
            "modules" to modules, "applications" to applications, "connectorApiVersion" to connectorApi,
            "dsl" to (dsl.configurations.getByName("runtimeClasspath").files + dslJar).sortedBy { it.name }.map(::identity),
            "drivers" to drivers, "externalArtifacts" to external,
            "licenses" to layout.buildDirectory.dir("runtime-licenses").get().asFile.walkTopDown()
                .filter { it.isFile }.sortedBy { it.path }.map(::identity).toList())
        manifest.get().asFile.apply { parentFile.mkdirs(); writeText(JsonOutput.prettyPrint(JsonOutput.toJson(document)) + "\n") }
    }
}

tasks.register("assembleJvm") {
    group = "build"
    description = "编译完整 JVM 产品并记录本轮实际构件，不运行测试。"
    dependsOn(subprojects.map { "${it.path}:assemble" })
    dependsOn(writeJvmManifest)
}

tasks.register<ProductDistribution>("serverDistribution") {
    group = "distribution"
    description = "装配可本地启动的完整 JVM 产品，不运行测试。"
    dependsOn(writeJvmManifest, ":bootstrap:shadowJar")
    manifest.set(layout.buildDirectory.file("jvm-artifacts.json"))
    bootstrapJar.set(project(":bootstrap").tasks.named<Jar>("shadowJar").flatMap { it.archiveFile })
    published.set(false)
}

tasks.register<ProductDistribution>("npmPackage") {
    group = "distribution"
    description = "从同标签 JitPack 实际构件、Maven Central 与包内 JRE 装配 macOS arm64 npm 产品。"
    dependsOn(writeJvmManifest, ":bootstrap:shadowJar")
    manifest.set(layout.buildDirectory.file("jvm-artifacts.json"))
    bootstrapJar.set(project(":bootstrap").tasks.named<Jar>("shadowJar").flatMap { it.archiveFile })
    published.set(true)
}
