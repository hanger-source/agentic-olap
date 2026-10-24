dependencies {
    api(project(":json-io"))
    api(project(":olap-contracts"))
    api(project(":olap-protocol"))
    api(project(":governance-contracts"))
    api(project(":query-contracts"))
    api(project(":release-contracts"))
    api(project(":execution-catalog"))
    api(project(":dsl-api"))
    api(libs.protobuf.java)
}
