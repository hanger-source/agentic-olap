plugins { application }

application { mainClass.set("com.olap.prototype.runtime.ApplicationMainKt") }

dependencies {
    api(project(":dsl-api"))
    api(project(":olap-protocol"))
    api(project(":olap-codec"))
    implementation(project(":json-io"))
    implementation(libs.kotlinx.serialization.json)
    api(project(":connector-loader"))
    api(project(":query-definition"))
    api(project(":governance-application"))
    api(project(":release-catalog"))
    api(project(":warehouse-onboarding"))
    api(project(":warehouse-registry"))
    api(project(":warehouse-inspection"))
    api(project(":engine-core"))
    api(libs.jackson.databind)
    api(libs.h2)
    api(libs.logback.classic)
    api(project(":governance-management"))
    api(project(":query-contracts"))
    api(project(":execution-calcite"))
    api(project(":connector-api"))
    api(project(":release-contracts"))
    api(project(":warehouse-contracts"))
    api(libs.protobuf.java)
}
