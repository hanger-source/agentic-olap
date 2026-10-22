dependencies {
    implementation(project(":json-io"))
    implementation(project(":olap-codec"))
    implementation(project(":dsl-api"))
    api(project(":release-catalog"))
    api(project(":warehouse-onboarding"))
    api(project(":engine-core"))
    api(project(":governance-contracts"))
    api(project(":governance-management"))
    api(project(":release-contracts"))
    api(project(":query-contracts"))
    api(project(":query-definition"))
}
