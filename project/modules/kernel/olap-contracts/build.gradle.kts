dependencies {
    api(project(":warehouse-contracts"))
    api(libs.jackson.databind)
    api(libs.jackson.datatype.jsr310)
    api(libs.slf4j.api)
    api(libs.jackson.module.kotlin)
    api(kotlin("reflect"))
}
