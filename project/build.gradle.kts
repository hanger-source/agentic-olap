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
    description = "为 JitPack 发布 JVM 服务、连接器与 SDK；不构建 Go 或 npm 包。"
    dependsOn(subprojects.filter { it.name != "integration-tests" && it.name != "examples" }
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

val buildProtocGenGo by tasks.registering(Exec::class) {
    workingDir("service")
    val executable = layout.buildDirectory.file("tools/protoc-gen-go")
    inputs.files(file("service/go.mod"), file("service/go.sum"))
    outputs.file(executable)
    doFirst { executable.get().asFile.parentFile.mkdirs() }
    commandLine("go", "build", "-mod=readonly", "-trimpath", "-o", executable.get().asFile.absolutePath, "google.golang.org/protobuf/cmd/protoc-gen-go")
}

tasks.register<Sync>("generateGoProtocol") {
    dependsOn(":olap-protocol:generateProto")
    from(project(":olap-protocol").layout.buildDirectory.dir("generated/sources/proto/main/go"))
    into("service/internal/protocol")
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
        val artifacts = listOf(
            project(":runtime").configurations.getByName("runtimeClasspath"),
            project(":runtime").configurations.getByName("scriptBoundaryRuntime"),
            project(":script-runtime").configurations.getByName("runtimeClasspath"),
            project(":dsl-api").configurations.getByName("runtimeClasspath"),
        ).flatMap { it.resolvedConfiguration.resolvedArtifacts }
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
        val modules = subprojects.sortedBy { it.name }.map { module ->
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
        val runtime = project(":runtime")
        val runtimeJar = runtime.tasks.named<Jar>("jar").get().archiveFile.get().asFile
        val classpath = (runtime.configurations.getByName("runtimeClasspath").files + runtimeJar)
            .sortedBy { it.name }.map(::identity)
        require(classpath.isNotEmpty()) { "runtime classpath is empty" }
        val script = project(":script-runtime")
        val scriptJar = script.tasks.named<Jar>("jar").get().archiveFile.get().asFile
        val dsl = project(":dsl-api")
        val dslJar = dsl.tasks.named<Jar>("jar").get().archiveFile.get().asFile
        val boundary = runtime.configurations.getByName("scriptBoundaryRuntime")
        fun scriptFiles(configuration: String) = (script.configurations.getByName(configuration).files +
            boundary.files + scriptJar + runtimeJar).distinctBy { it.canonicalPath }.sortedBy { it.name }.map(::identity)
        val scripting = mapOf("dslApiVersion" to "agentic-olap-kotlin-dsl/v1",
            "runtime" to scriptFiles("runtimeClasspath"),
            "dsl" to (dsl.configurations.getByName("runtimeClasspath").files + dslJar).sortedBy { it.name }.map(::identity))
        val drivers = listOf(":connector-h2", ":connector-duckdb").associate { path ->
            val module = project(path)
            module.name to module.configurations.getByName("driver").files.map(::identity)
        }
        val external = (runtime.configurations.getByName("runtimeClasspath").resolvedConfiguration.resolvedArtifacts +
            boundary.resolvedConfiguration.resolvedArtifacts +
            script.configurations.getByName("runtimeClasspath").resolvedConfiguration.resolvedArtifacts +
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
        val document = mapOf("formatVersion" to "agentic-olap-jvm-build/v4", "version" to productVersion,
            "mainClass" to "com.olap.prototype.runtime.ApplicationMainKt", "modules" to modules,
            "runtimeClasspath" to classpath, "scripting" to scripting, "drivers" to drivers, "externalArtifacts" to external,
            "licenses" to layout.buildDirectory.dir("runtime-licenses").get().asFile.walkTopDown()
                .filter { it.isFile }.sortedBy { it.path }.map(::identity).toList())
        manifest.get().asFile.apply { parentFile.mkdirs(); writeText(JsonOutput.prettyPrint(JsonOutput.toJson(document)) + "\n") }
    }
}

tasks.register("assembleJvm") {
    group = "build"
    description = "编译完整 JVM 产品并记录本轮实际构件，不运行测试。"
    dependsOn(subprojects.map { "${it.path}:assemble" })
    dependsOn(writeJvmManifest, "generateGoProtocol")
}
