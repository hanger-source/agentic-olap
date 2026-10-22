dependencies {
    api(project(":olap-contracts"))
    api(project(":governance-contracts"))
    api(project(":query-contracts"))
    api(project(":release-contracts"))
    api(project(":warehouse-contracts"))
    implementation(kotlin("reflect"))
}
