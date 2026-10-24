import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins { application; alias(libs.plugins.shadow) }

application { mainClass.set("com.olap.prototype.bootstrap.BootstrapMainKt") }
dependencies { implementation(libs.kotlinx.serialization.json) }
tasks.named<ShadowJar>("shadowJar") {
    archiveFileName.set("agentic-olap-bootstrap.jar")
    manifest { attributes["Main-Class"] = application.mainClass.get() }
}
