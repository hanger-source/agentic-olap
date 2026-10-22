dependencies {
    implementation(project(":json-io"))
    implementation(project(":olap-codec"))
    api(project(":warehouse-registry"))
    api(project(":semantic-calcite"))
    api(project(":olap-contracts"))
    api(project(":warehouse-contracts"))
    api(project(":query-contracts"))
    api(project(":release-contracts"))
    api(project(":governance-contracts"))
    api(project(":platform-storage"))
    api(libs.jackson.databind)
}
