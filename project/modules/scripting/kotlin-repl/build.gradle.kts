import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

val compilerVersion = "2.4.21"
check(libs.versions.kotlin.get() == compilerVersion) { "the REPL compiler backport must match its exact Kotlin base" }

// 回补只属于编译器构建输入；主运行图不保留另一份原始 compiler JAR。
val compilerBackport = sourceSets.create("compilerBackport")
val compilerJar = configurations.named(compilerBackport.compileClasspathConfigurationName).map { configuration ->
    configuration.resolvedConfiguration.resolvedArtifacts.single {
        it.moduleVersion.id.group == "org.jetbrains.kotlin" && it.name == "kotlin-compiler-embeddable" &&
            it.moduleVersion.id.version == compilerVersion
    }.file
}

dependencies {
    compileOnly(kotlin("compiler-embeddable"))
    add(compilerBackport.implementationConfigurationName, kotlin("compiler-embeddable"))
    add(compilerBackport.implementationConfigurationName, kotlin("stdlib"))
    implementation("org.jetbrains.kotlin:kotlin-scripting-jvm-host:$compilerVersion") {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-compiler-embeddable")
    }
    implementation("org.jetbrains.kotlin:kotlin-scripting-compiler-embeddable:$compilerVersion") {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-compiler-embeddable")
    }
    // compiler-embeddable 2.4.21 POM 的独立运行依赖；stdlib 与 script-runtime 由宿主正常提供。
    runtimeOnly(kotlin("build-tools-api"))
    runtimeOnly(kotlin("daemon-embeddable"))
    runtimeOnly(kotlin("reflect"))
    runtimeOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.8.0") { isTransitive = false }
}

tasks.named<KotlinCompile>("compileCompilerBackportKotlin") {
    compilerOptions {
        languageVersion.set(KotlinVersion.KOTLIN_2_4)
        apiVersion.set(KotlinVersion.KOTLIN_2_4)
        // 保持官方模块的 internal 方法 JVM 名称，不把它重命名为本项目的源码集。
        moduleName.set("org.jetbrains.kotlin_resolve")
        freeCompilerArgs.addAll("-XXLanguage:+NameBasedDestructuring",
            "-opt-in=org.jetbrains.kotlin.fir.symbols.SymbolInternals",
            "-opt-in=org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess")
        freeCompilerArgs.add(compilerJar.map { "-Xfriend-paths=${it.absolutePath}" })
    }
}

tasks.named<Jar>("jar") {
    dependsOn(compilerBackport.classesTaskName)
    duplicatesStrategy = DuplicatesStrategy.FAIL
    from(compilerJar.map { zipTree(it) }) {
        exclude("META-INF/MANIFEST.MF")
        // 先明确退出官方原类；不依靠加载顺序覆盖相同类名。
        exclude { entry -> entry.path.endsWith(".class") && compilerBackport.output.classesDirs.files.any { it.resolve(entry.path).isFile } }
    }
    from(compilerBackport.output) {
        // 原 compiler 的完整 module metadata 继续拥有同一声明索引。
        exclude("META-INF/**")
    }
    doFirst {
        check(configurations.runtimeClasspath.get().resolvedConfiguration.resolvedArtifacts.none {
            it.moduleVersion.id.group == "org.jetbrains.kotlin" && it.name == "kotlin-compiler-embeddable"
        }) { "the REPL runtime must contain only its bundled compiler implementation" }
    }
}

tasks.named<Jar>("sourcesJar") { from(compilerBackport.allSource) }
