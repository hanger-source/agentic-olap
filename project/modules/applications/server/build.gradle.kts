plugins { application }

application { mainClass.set("com.olap.prototype.server.ServerMainKt") }

dependencies {
    implementation(project(":worker-runtime"))
    implementation(project(":json-io"))
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.datatype.jsr310)
    implementation(libs.mcp.server)
    implementation(libs.protobuf.java.util)
    implementation(libs.ktor.server.double.receive)
    implementation(libs.ktor.server.body.limit)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.jna)
}
