dependencies {
    testImplementation(project(":json-io"))
    testImplementation(project(":connector-loader"))
    testImplementation(project(":connector-h2"))
    testImplementation(project(":connector-duckdb"))
    testImplementation(project(":runtime"))
    api(project(":query-definition"))
    api(project(":runtime"))
    api(project(":olap-contracts"))
    api(project(":warehouse-contracts"))
    api(project(":warehouse-jdbc"))
    api(project(":warehouse-inspection"))
    api(project(":warehouse-registry"))
    api(project(":execution-catalog"))
    api(project(":engine-core"))
    api(project(":platform-storage"))
    api(project(":query-contracts"))
    api(project(":release-contracts"))
    api(project(":governance-contracts"))
    api(project(":governance-management"))
    api(project(":release-catalog"))
    api(project(":governance-application"))
    api(project(":warehouse-onboarding"))
    testImplementation(libs.h2)
    testImplementation(libs.duckdb.jdbc)
    testImplementation(libs.junit.jupiter)
}

tasks.withType<Test>().configureEach {
    environment("OLAP_TEST_HTTP_AUTH", "Bearer fixture-query-access")
    dependsOn(":connector-h2:shadowJar", ":connector-duckdb:shadowJar")
    doFirst {
        systemProperty("olap.test.runtime.classpath", sourceSets.test.get().runtimeClasspath.asPath)
        for (backend in listOf("h2", "duckdb")) {
            val plugin = project(":connector-$backend").tasks.named<Jar>("shadowJar").get().archiveFile.get().asFile
            systemProperty("olap.connector.$backend.jar", plugin.absolutePath)
        }
    }
}

tasks.register("prepareHttpSourceFixture") {
    dependsOn(tasks.named("testClasses"))
    doLast {
        val input = providers.gradleProperty("fixtureArguments").get()
        val values = groovy.json.JsonSlurper().parse(file(input)) as List<*>
        require(values.all { it is String }) { "fixtureArguments must contain only strings" }
        val launcher = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }.get()
        val command = listOf(launcher.executablePath.asFile.absolutePath,
            "-Dolap.connector.duckdb.jar=${providers.gradleProperty("duckdbConnectorJar").get()}",
            "-cp", sourceSets.test.get().runtimeClasspath.asPath,
            "com.olap.prototype.connector.HttpSourceAccessTest") + values.map { it as String }
        val output = file(providers.gradleProperty("fixtureLaunchOutput").get())
        output.parentFile.mkdirs()
        output.writeText(groovy.json.JsonOutput.toJson(mapOf("command" to command)) + "\n")
    }
}
