dependencies {
    api(project(":olap-contracts"))
    api(project(":warehouse-contracts"))
    api(project(":warehouse-jdbc"))
    api(project(":warehouse-inspection"))
    api(project(":warehouse-registry"))
    api(project(":governance-contracts"))
    api(libs.jackson.databind)
}
