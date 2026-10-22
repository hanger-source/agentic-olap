import groovy.json.JsonSlurper

sourceSets.main { kotlin.srcDirs("orders", "procurement", "subscriptions", "kernel"); kotlin.include("**/*.kt") }
dependencies { implementation(project(":runtime")) }
tasks.named<Jar>("sourcesJar") { include("**/*.kt", "META-INF/LICENSE") }

for ((name, main) in mapOf("orders" to "Orders", "procurement" to "Procurement", "subscriptions" to "Subscriptions", "kernel" to "Kernel")) {
    tasks.register<JavaExec>(name) {
        group = "examples"
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass.set(main)
        doFirst {
            val input = providers.gradleProperty("exampleArguments").get()
            val values = JsonSlurper().parse(file(input)) as List<*>
            require(values.all { it is String }) { "exampleArguments must contain only strings" }
            args = values.map { it as String }
        }
    }
}
