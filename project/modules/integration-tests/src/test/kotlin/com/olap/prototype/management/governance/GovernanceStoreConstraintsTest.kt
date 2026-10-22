package com.olap.prototype.management.governance

import com.olap.prototype.storage.PlatformDatabase
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.sql.SQLException

/** 用不经过服务的SQL区分真实存储约束与应用端校验。 */
class GovernanceStoreConstraintsTest {
    @Test
    fun databaseRejectsDanglingVersionReferenceWithoutServiceValidation() {
        PlatformDatabase.inMemory().use { database -> database.connection().use { connection -> connection.createStatement().use { sql ->
            assertThrows(SQLException::class.java) {
                sql.execute("""
                    INSERT INTO physical_table_version
                      (id,physical_table_id,version,version_status,content_proto,created_by,created_at)
                    VALUES ('missing:v1','missing',1,'ACTIVE','{}','tester',CURRENT_TIMESTAMP)
                """.trimIndent())
            }
        } } }
    }

    @Test
    fun databaseRejectsTwoActiveVersionsForOneIdentity() {
        PlatformDatabase.inMemory().use { database -> database.connection().use { connection -> connection.createStatement().use { sql ->
            sql.execute("INSERT INTO business_line (id,code,created_by,created_at) VALUES ('travel','travel','tester',CURRENT_TIMESTAMP)")
            sql.execute("INSERT INTO business_line_version (id,business_line_id,version,version_status,content_proto,created_by,created_at) VALUES ('travel:v1','travel',1,'ACTIVE','{}','tester',CURRENT_TIMESTAMP)")
            assertThrows(SQLException::class.java) {
                sql.execute("INSERT INTO business_line_version (id,business_line_id,version,version_status,content_proto,created_by,created_at) VALUES ('travel:v2','travel',2,'ACTIVE','{}','tester',CURRENT_TIMESTAMP)")
            }
            sql.execute("INSERT INTO business_line_version (id,business_line_id,version,version_status,content_proto,created_by,created_at) VALUES ('travel:v2','travel',2,'DRAFT','{}','tester',CURRENT_TIMESTAMP)")
        } } }
    }
}
