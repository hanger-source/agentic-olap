import com.google.protobuf.gradle.*

plugins { alias(libs.plugins.protobuf) }

sourceSets.main { proto.srcDir(rootProject.file("protocol")) }
protobuf {
    protoc { artifact = "com.google.protobuf:protoc:4.33.2" }
}

dependencies { api(libs.protobuf.java) }
