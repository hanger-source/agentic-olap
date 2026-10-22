package com.olap.prototype.management.governance

import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.governance.contract.BusinessLineCreateRequest
import com.olap.prototype.governance.contract.GovernanceRevisionRequest
import com.olap.prototype.storage.PlatformDatabase
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.sql.SQLFeatureNotSupportedException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 真实存储损坏、并发所有权、读快照和事务，不依据私有实现结构作判断。 */
class GovernanceStorageBoundaryTest {
    @Test
    fun refusesIncompleteOrUnsupportedStoresWithoutRepairingCommittedData(@TempDir directory: Path) {
        listOf("partial-initialization", "missing-format", "unsupported-format").forEach { damage ->
            val location = Files.createDirectory(directory.resolve(damage))
            if (damage == "partial-initialization") {
                val schema = requireNotNull(PlatformDatabase::class.java.getResourceAsStream("/db/schema/platform-schema.sql"))
                    .use { String(it.readAllBytes(), Charsets.UTF_8) }
                storedConnection(location).use { connection -> connection.createStatement().use { it.execute(schema.substring(0, schema.indexOf(';') + 1)) } }
            } else {
                PlatformDatabase.openSingleInstance(location).use { database ->
                    BusinessLineGovernanceService(database).createBusinessLine(BusinessLineCreateRequest("commerce", "已提交业务线", "经营团队", "原定义", "agent"))
                }
                storedConnection(location).use { connection -> connection.createStatement().use {
                    it.execute(if (damage == "missing-format") "DROP TABLE platform_storage_format" else "UPDATE platform_storage_format SET format_version=999")
                } }
            }
            assertThrows(IllegalStateException::class.java, { PlatformDatabase.openSingleInstance(location).use { } }, damage)
            storedConnection(location).use { connection -> connection.createStatement().use { sql ->
                if (damage != "partial-initialization") sql.executeQuery("SELECT code FROM business_line WHERE id='commerce'").use {
                    assertTrue(it.next()); assertEquals("commerce", it.getString(1))
                }
                if (damage == "unsupported-format") sql.executeQuery("SELECT format_version FROM platform_storage_format").use {
                    assertTrue(it.next()); assertEquals(999, it.getInt(1))
                } else assertThrows(SQLException::class.java) { sql.executeQuery("SELECT format_version FROM platform_storage_format") }
            } }
        }
    }

    @Test
    fun refusesSameFormatStoresWhoseActualConstraintsAllowInvalidHistory(@TempDir directory: Path) {
        listOf("multiple-active-versions", "dangling-identity").forEach { damage ->
            val location = directory.resolve(damage)
            PlatformDatabase.openSingleInstance(location).use { database ->
                BusinessLineGovernanceService(database).createBusinessLine(BusinessLineCreateRequest("commerce", "业务线", "经营团队", "原定义", "agent"))
                database.connection().use { connection -> connection.createStatement().use { it.execute("UPDATE business_line_version SET version_status='ACTIVE' WHERE business_line_id='commerce'") } }
            }
            storedConnection(location).use { connection -> connection.createStatement().use { sql ->
                if (damage == "multiple-active-versions") {
                    sql.execute("DROP INDEX ux_business_line_version_active")
                    sql.execute("INSERT INTO business_line_version (id,business_line_id,version,version_status,content_json,created_by,created_at) VALUES ('commerce:v2','commerce',2,'ACTIVE','{}','tester',CURRENT_TIMESTAMP)")
                } else {
                    sql.execute("ALTER TABLE business_line_version DROP CONSTRAINT fk_business_line_version_identity")
                    sql.execute("INSERT INTO business_line_version (id,business_line_id,version,version_status,content_json,created_by,created_at) VALUES ('missing:v1','missing',1,'DRAFT','{}','tester',CURRENT_TIMESTAMP)")
                }
            } }
            assertThrows(IllegalStateException::class.java, { PlatformDatabase.openSingleInstance(location).use { } }, damage)
            storedConnection(location).use { connection -> connection.createStatement().use { sql ->
                sql.executeQuery(if (damage == "multiple-active-versions") "SELECT count(*) FROM business_line_version WHERE business_line_id='commerce' AND version_status='ACTIVE'"
                    else "SELECT count(*) FROM business_line_version v LEFT JOIN business_line b ON b.id=v.business_line_id WHERE b.id IS NULL").use {
                    assertTrue(it.next()); assertEquals(if (damage == "multiple-active-versions") 2 else 1, it.getInt(1))
                }
            } }
        }
    }

    @Test
    fun concurrentFirstOpensShareCommittedDataAndReleaseOnlyTheirOwnedConnections(@TempDir directory: Path) {
        val owners = ConcurrentLinkedQueue<PlatformDatabase>()
        val ready = CountDownLatch(3)
        val start = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(3)
        try {
            val attempts = (1..3).map {
                workers.submit<PlatformDatabase> {
                    ready.countDown()
                    check(start.await(10, TimeUnit.SECONDS)) { "storage open start timed out" }
                    PlatformDatabase.openSingleInstance(directory).also(owners::add)
                }
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            val first = attempts[0].get(20, TimeUnit.SECONDS)
            val second = attempts[1].get(20, TimeUnit.SECONDS)
            val third = attempts[2].get(20, TimeUnit.SECONDS)
            BusinessLineGovernanceService(first).createBusinessLine(BusinessLineCreateRequest("commerce", "共同持久库", "经营团队", "原定义", "agent"))
            assertEquals("共同持久库", BusinessLineGovernanceService(second).businessLineSnapshot("commerce", 1).displayName)
            val outstanding = first.connection()
            first.close()
            assertTrue(outstanding.isClosed)
            assertThrows(SQLException::class.java) { first.connection() }
            BusinessLineGovernanceService(second).reviseBusinessLine(GovernanceRevisionRequest("commerce", 1,
                BusinessLineCreateRequest("commerce", "另一个owner提交", "经营团队", "修订定义", "agent")))
            assertEquals("另一个owner提交", BusinessLineGovernanceService(third).businessLineSnapshot("commerce", 2).displayName)
            second.close()
            assertEquals("共同持久库", BusinessLineGovernanceService(third).businessLineSnapshot("commerce", 1).displayName)
            third.close()
            PlatformDatabase.openSingleInstance(directory).use { reopened ->
                assertEquals("另一个owner提交", BusinessLineGovernanceService(reopened).businessLineSnapshot("commerce", 2).displayName)
            }
        } finally {
            start.countDown(); workers.shutdownNow()
            try { assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS)) }
            finally { owners.forEach { it.close() } }
        }
    }

    @Test
    fun dependencyInvestigationKeepsOneSnapshotAcrossConcurrentVersionInsertion(@TempDir directory: Path) {
        PlatformDatabase.openSingleInstance(directory).use { reader -> PlatformDatabase.openSingleInstance(directory).use { writer ->
            val reading = BusinessLineGovernanceService(reader)
            val writing = BusinessLineGovernanceService(writer)
            writing.createBusinessLine(BusinessLineCreateRequest("commerce", "商业", "经营团队", "原口径", "agent"))
            fun versions(): Set<Long> = reader.connection().use { connection -> connection.createStatement().use { sql ->
                sql.executeQuery("SELECT version FROM business_line_version WHERE business_line_id='commerce'").use { rows -> buildSet { while (rows.next()) add(rows.getLong(1)) } }
            } }
            fun laterVisible(): Boolean = reader.connection().use { connection -> connection.createStatement().use { sql -> sql.executeQuery("SELECT id FROM business_line WHERE id='later'").use { it.next() } } }
            reader.inReadSnapshot {
                assertEquals(setOf(1L), versions())
                assertEquals(1L, reading.listBusinessLines().first().currentVersion)
                writing.reviseBusinessLine(GovernanceRevisionRequest("commerce", 1, BusinessLineCreateRequest("commerce", "商业", "经营团队", "修订口径", "agent")))
                assertEquals(2L, writing.listBusinessLines().first().currentVersion)
                writing.createBusinessLine(BusinessLineCreateRequest("later", "调查开始后新增业务线", "经营团队", "新身份和新版本", "agent"))
                assertFalse(laterVisible())
                assertEquals(setOf(1L), versions())
                assertEquals(1L, reading.listBusinessLines().first().currentVersion)
            }
            reader.inReadSnapshot {
                assertTrue(laterVisible())
                assertEquals(setOf(1L, 2L), versions())
                assertEquals(2L, reading.listBusinessLines().first().currentVersion)
            }
        } }
    }

    @Test
    fun competingRevisionsAndFailedReplacementPreserveCommittedHistory(@TempDir directory: Path) {
        PlatformDatabase.openSingleInstance(directory).use { first -> PlatformDatabase.openSingleInstance(directory).use { second ->
            val service = BusinessLineGovernanceService(first)
            service.createBusinessLine(BusinessLineCreateRequest("commerce", "商业", "经营团队", "原定义", "agent"))
            val lateFailure = IllegalStateException("调查完成后主动中止本次修订")
            assertSame(lateFailure, assertThrows(IllegalStateException::class.java) {
                first.inTransaction {
                    first.connection().unwrap(Connection::class.java).use { borrowed ->
                        assertThrows(SQLFeatureNotSupportedException::class.java) { borrowed.unwrap(org.h2.jdbc.JdbcConnection::class.java) }
                        borrowed.createStatement().use { sql -> sql.executeQuery("SELECT count(*) FROM business_line_version WHERE business_line_id='commerce'").use {
                            assertTrue(it.next()); assertEquals(1L, it.getLong(1))
                        } }
                    }
                    assertEquals("商业", service.businessLineSnapshot("commerce", 1).displayName)
                    service.reviseBusinessLine(GovernanceRevisionRequest("commerce", 1, BusinessLineCreateRequest("commerce", "待回滚修订", "经营团队", "已调查定义", "agent")))
                    assertEquals("待回滚修订", service.businessLineSnapshot("commerce", 2).displayName)
                    throw lateFailure
                }
            })
            val independent = BusinessLineGovernanceService(second)
            assertEquals("商业", independent.businessLineSnapshot("commerce", 1).displayName)
            assertEquals("DRAFT", independent.businessLineSnapshot("commerce", 1).status)
            assertEquals(1L, requireNotNull(independent.findBusinessLine("commerce")).currentVersion)
            assertEquals(1L, versionCount(second))
            val ready = CountDownLatch(2)
            val start = CountDownLatch(1)
            val workers = Executors.newFixedThreadPool(2)
            try {
                val attempts = listOf(first, second).map { database -> workers.submit<Boolean> {
                    ready.countDown(); check(start.await(10, TimeUnit.SECONDS)) { "revision start timed out" }
                    try {
                        BusinessLineGovernanceService(database).reviseBusinessLine(GovernanceRevisionRequest("commerce", 1,
                            BusinessLineCreateRequest("commerce", "修订定义", "经营团队", "调查所得", "agent")))
                        true
                    } catch (stale: ProducerContractException) {
                        if (!stale.message.orEmpty().contains("stale sourceVersion")) throw stale
                        false
                    }
                } }
                assertTrue(ready.await(10, TimeUnit.SECONDS)); start.countDown()
                assertEquals(1, attempts.count { it.get(20, TimeUnit.SECONDS) })
            } finally { start.countDown(); workers.shutdownNow(); assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS)) }
            PlatformDatabase.openSingleInstance(directory).use { reopened ->
                val history = BusinessLineGovernanceService(reopened)
                assertEquals("商业", history.businessLineSnapshot("commerce", 1).displayName)
                assertEquals("ARCHIVED", history.businessLineSnapshot("commerce", 1).status)
                assertEquals("DRAFT", history.businessLineSnapshot("commerce", 2).status)
                assertThrows(ProducerContractException::class.java) {
                    history.reviseBusinessLine(GovernanceRevisionRequest("commerce", 2, BusinessLineCreateRequest("commerce", "", "经营团队", "不完整调查", "agent")))
                }
                assertEquals("DRAFT", history.businessLineSnapshot("commerce", 2).status)
                assertEquals(2L, requireNotNull(history.findBusinessLine("commerce")).currentVersion)
                assertEquals(2L, versionCount(first))
            }
        } }
    }

    private fun versionCount(database: PlatformDatabase): Long = database.connection().use { connection -> connection.createStatement().use { sql ->
        sql.executeQuery("SELECT count(*) FROM business_line_version WHERE business_line_id='commerce'").use { check(it.next()); it.getLong(1) }
    } }
}

internal fun storedConnection(directory: Path): Connection = DriverManager.getConnection(
    "jdbc:h2:file:${directory.resolve("olap-platform")};MODE=PostgreSQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=0", "sa", "")
