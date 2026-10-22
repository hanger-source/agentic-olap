dependencies {
    api(project(":warehouse-contracts"))
    api(libs.kotlinx.serialization.json)
    api(libs.jackson.databind)
    implementation(libs.jackson.datatype.jsr310)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.slf4j.api)
}
