import com.google.protobuf.gradle.*

plugins { alias(libs.plugins.protobuf) }

sourceSets.main { proto.srcDir(rootProject.file("protocol")) }
protobuf {
    protoc { artifact = "com.google.protobuf:protoc:4.33.2" }
    if (System.getenv("JITPACK") != "true") {
        plugins {
            create("go") { path = rootProject.layout.buildDirectory.file("tools/protoc-gen-go").get().asFile.absolutePath }
        }
        generateProtoTasks {
            ofSourceSet("main").configureEach {
                dependsOn(rootProject.tasks.named("buildProtocGenGo"))
                plugins { create("go") { option("paths=import"); option("module=github.com/hanger-source/agentic-olap/service/internal/protocol") } }
            }
        }
    }
}

dependencies { api(libs.protobuf.java) }
