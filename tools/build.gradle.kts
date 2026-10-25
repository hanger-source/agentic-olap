plugins { application }
application { mainClass.set("com.olap.development.DevelopmentMainKt") }
dependencies {
    implementation(project(":examples"))
    implementation(project(":server"))
    implementation(project(":json-io"))
    implementation(libs.kotlinx.serialization.json)
}

for (scenario in listOf("orders", "procurement", "subscriptions", "populations", "kernel")) {
    tasks.register<JavaExec>(scenario) {
        group = "verification"
        description = "通过真实服务和 MCP 执行 $scenario 完整业务用法；仅显式调用。"
        dependsOn(rootProject.tasks.named("serverDistribution"))
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass.set(application.mainClass)
        args("scenario", scenario, rootProject.layout.buildDirectory.dir("distribution").get().asFile.absolutePath,
            providers.gradleProperty("artifacts").orElse(rootProject.file("../.tmp/$scenario").absolutePath).get(), rootProject.rootDir.parentFile.absolutePath)
    }
}

tasks.register<JavaExec>("installedProduct") {
    group = "verification"
    description = "从实际 npm tarball 安装，操作来源、定义和 Query，并核验重启与完整消息。"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set(application.mainClass)
    doFirst {
        args = listOf("installed", providers.gradleProperty("tarballs").orElse(rootProject.layout.buildDirectory.dir("npm/tarballs").get().asFile.absolutePath).get(),
            providers.gradleProperty("artifacts").orElse(rootProject.file("../.tmp/installed-product").absolutePath).get(),
            rootProject.rootDir.parentFile.absolutePath, providers.gradleProperty("installMode").orElse("tarball").get())
    }
}

tasks.register<JavaExec>("runtime") {
    group = "verification"
    description = "通过真实服务、持续 REPL 与 macOS 权限执行核心运行试验。"
    dependsOn(rootProject.tasks.named("serverDistribution"))
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set(application.mainClass)
    args("runtime", rootProject.layout.buildDirectory.dir("distribution").get().asFile.absolutePath,
        providers.gradleProperty("artifacts").orElse(rootProject.file("../.tmp/runtime").absolutePath).get(), rootProject.rootDir.parentFile.absolutePath)
}

tasks.register<JavaExec>("httpSource") {
    group = "verification"
    description = "通过真实 HTTP SourceAccess、显式结构登记与正式 MCP 执行订单完整用法。"
    dependsOn(":integration-tests:prepareHttpSourceFixture")
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set(application.mainClass)
    args("scenario", "http-source", rootProject.layout.buildDirectory.dir("distribution").get().asFile.absolutePath,
        providers.gradleProperty("artifacts").orElse(rootProject.file("../.tmp/http-source").absolutePath).get(),
        rootProject.rootDir.parentFile.absolutePath, rootProject.layout.buildDirectory.file("http-source/launch.json").get().asFile.absolutePath)
}

tasks.register<JavaExec>("bootstrap") {
    group = "verification"
    description = "独立运行安装引导，观察正式 Maven 下载、缓存并发与摘要失败边界。"
    dependsOn(rootProject.tasks.named("writeJvmManifest"), ":bootstrap:shadowJar")
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set(application.mainClass)
    args("bootstrap", project(":bootstrap").layout.buildDirectory.file("libs/agentic-olap-bootstrap.jar").get().asFile.absolutePath,
        rootProject.layout.buildDirectory.file("jvm-artifacts.json").get().asFile.absolutePath,
        providers.gradleProperty("artifacts").orElse(rootProject.file("../.tmp/bootstrap").absolutePath).get())
}
