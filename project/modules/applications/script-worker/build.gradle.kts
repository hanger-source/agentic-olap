plugins { application }

application { mainClass.set("com.olap.prototype.runtime.ScriptMainKt") }

dependencies {
    implementation(project(":kotlin-repl"))
    implementation(project(":dsl-api"))
    implementation(project(":olap-codec"))
    implementation(project(":json-io"))
    implementation(libs.kotlinx.serialization.json)
}
