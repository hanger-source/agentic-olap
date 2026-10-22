dependencies {
    api(project(":connector-api"))
    api(project(":warehouse-jdbc"))
}

val driver by configurations.creating
dependencies { driver(libs.duckdb.jdbc) }
tasks.register<Sync>("copyDriver") { from(driver); into(layout.buildDirectory.dir("driver")) }
