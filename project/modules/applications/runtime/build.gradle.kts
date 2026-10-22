import com.google.protobuf.gradle.*

plugins { application; alias(libs.plugins.protobuf) }

application { mainClass.set("com.olap.prototype.runtime.ApplicationMainKt") }
sourceSets.main { proto.srcDir(rootProject.file("protocol")) }
protobuf {
    protoc { artifact = "com.google.protobuf:protoc:4.33.2" }
    // JitPack 只发布 JVM 构件；本地产品构建同时生成 Go 协议。
    if (System.getenv("JITPACK") != "true") {
        plugins {
            create("go") { path = rootProject.layout.buildDirectory.file("tools/protoc-gen-go").get().asFile.absolutePath }
        }
        generateProtoTasks {
            ofSourceSet("main").configureEach {
                dependsOn(rootProject.tasks.named("buildProtocGenGo"))
                plugins { create("go") { option("paths=source_relative") } }
            }
        }
    }
}

dependencies {
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
