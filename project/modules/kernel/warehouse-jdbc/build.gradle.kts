dependencies {
    implementation(project(":json-io"))
    api(project(":semantic-calcite"))
    api(project(":connector-api"))
    api(project(":olap-contracts"))
    api(project(":warehouse-contracts"))
    api(libs.slf4j.api)
}
