dependencies {
    api(project(":semantic-calcite"))
    api(project(":query-contracts"))
    api(project(":olap-contracts"))
    api(project(":release-contracts"))
    api(project(":execution-catalog"))
    api(project(":warehouse-jdbc"))
    api(project(":platform-storage"))
    api(libs.jackson.databind)
}
