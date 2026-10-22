package com.olap.prototype.management.governance

import com.olap.prototype.calculation.*
import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.governance.application.GovernanceApplication
import com.olap.prototype.governance.application.GovernanceOperations
import com.olap.prototype.governance.contract.*
import com.olap.prototype.integration.RuntimeDeploymentLimits
import com.olap.prototype.member.*
import com.olap.prototype.query.*
import com.olap.prototype.query.contract.MemberQueryPermission
import com.olap.prototype.release.contract.ReleaseCatalogRequest
import com.olap.prototype.storage.PlatformDatabase
import com.olap.prototype.warehouse.inspection.JdbcWarehouseInspector
import com.olap.prototype.warehouse.jdbc.JdbcSourceInspection
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.*

/** 正式作者分发、真实治理事务和 DuckDB 查询共同观察采用修订及冻结发布边界。 */
class MemberAdoptionRevisionTest {
    @Test
    fun carriedAdoptionCanExplicitlyChangeRuleWithoutChangingOldPublishedResults(@TempDir directory: Path) {
        PlatformDatabase.inMemory().use { database ->
            val c = ComputedOrderFixture(database, directory)
            c.publish()
            val f = c.f
            val previous = c.publication()
            val oldScope = QueryComputation.Scope(c.formed(mapOf("window" to c.july)), emptyMap(), emptyList())
            val oldQuery = c.freeze(oldScope)
            val run = commands(database, f)
            carry(database, f, "travel.trade")
            val source = f.adoptions.memberSource(c.inner.id, 2)
            val request = MemberAdoptionRevisionRequest(MemberRef(source.id, 2), MemberDefinitionRef(c.sharedRule.id, 1),
                MemberDefinitionRef(c.sharedRule.id, 2), source.displayName, source.bindings,
                mapOf("minimum_v2" to Calculation.Scalar(ScalarValueType.NUMBER, "50")), "golden")

            run("revise-member-adoption", request)
            val conflict = assertThrows(MemberAdoptionConflictException::class.java) { run("revise-member-adoption", request) }
            assertEquals(MemberDefinitionRef(c.sharedRule.id, 2), conflict.actualDefinition)
            f.activate("SUBJECT_AREA", "travel.trade", 2)
            f.builder().build(SubjectAreaReleaseBuildRequest("formed_orders", "2", "显式更新订单规则", "travel.trade", 2, "golden"))
            val current = f.artifacts.loadExecutableCatalog(ReleaseCatalogRequest("travel.trade.formed", 2, "formed_orders", "2"))
            val newRoot = QueryComputation.Members("formed", MemberRef(c.publicRows.id, 2), mapOf("window" to c.july),
                listOf(QueryComputation.Slot("order", "order"), QueryComputation.Slot("amount", "amount")), null, emptyMap(),
                MemberCondition.Unrestricted(), emptyMap(), emptyMap())
            val rows = f.execute(current, QueryComputation.Scope(newRoot, emptyMap(), emptyList())).result.resultFrame.rows
            assertEquals(listOf("3"), rows.map { it["order"] })
            assertEquals(70, (rows.single()["amount"] as Number).toInt())
            val retained = f.execute(previous, oldQuery.intent.computation).result.resultFrame.rows
            assertEquals(setOf("2", "3"), retained.map { it["order"] }.toSet())
            assertEquals(MemberDefinitionOrigin.Shared(MemberDefinitionRef(c.sharedRule.id, 1)), f.adoptions.memberSource(source.id, 1).origin)
            assertThrows(ProducerContractException::class.java) { run("revise-member-adoption", request) }
        }
    }

    @Test
    fun existingScenarioPermissionsRejectAndRollBackAnIncompatibleAdoption(@TempDir directory: Path) {
        PlatformDatabase.inMemory().use { database ->
            val c = ComputedOrderFixture(database, directory)
            c.publish()
            val f = c.f
            carry(database, f, "travel.shared")
            val old = f.adoptions.memberSource(c.sharedV1.id, 2)
            val original = c.revisedRule.definition as MemberSourceDefinition.Computed
            val result = original.result
            val narrow = MemberSourceDefinition.Computed(original.calculation, MemberResult(result.entity, result.role, result.shape,
                result.identitySlots, mapOf("order" to result.outputs.getValue("order")), emptyMap(), result.timeSlot,
                result.clockSlots, "只公开订单身份"))
            f.members.revise(GovernanceRevisionRequest(c.sharedRule.id, 2, MemberDefinitionCreateRequest(c.sharedRule.code,
                c.sharedRule.displayName, narrow, null, "trade", "golden")))
            f.activate("MEMBER_DEFINITION", c.sharedRule.id, 3)
            val request = MemberAdoptionRevisionRequest(MemberRef(old.id, 2), MemberDefinitionRef(c.sharedRule.id, 1),
                MemberDefinitionRef(c.sharedRule.id, 3), "修改显示名也应回滚", old.bindings,
                mapOf("minimum_v2" to Calculation.Scalar(ScalarValueType.NUMBER, "50")), "golden")
            val failure = assertThrows(ProducerContractException::class.java) { commands(database, f)("revise-member-adoption", request) }
            assertTrue(failure.message.contains("scenario member permission"))
            assertEquals(old, f.adoptions.memberSource(old.id, 2))
            // 输出索引与共享引用必须一起回滚，随后原上下文仍能激活、冻结并真实执行。
            database.connection().use { connection -> connection.prepareStatement("SELECT slot FROM subject_area_member_output WHERE source_id=? AND source_version=2 ORDER BY slot").use { statement ->
                statement.setString(1, old.id)
                statement.executeQuery().use { rows ->
                    val slots = mutableSetOf<String>()
                    while (rows.next()) slots.add(rows.getString(1))
                    assertEquals(setOf("order", "amount", "candidate_amount"), slots)
                }
            } }
            f.activate("SUBJECT_AREA", "travel.shared", 2)
            f.builder().build(SubjectAreaReleaseBuildRequest("shared_orders", "2", "拒绝修订后的原规则", "travel.shared", 2, "golden"))
            val publication = f.artifacts.loadExecutableCatalog(ReleaseCatalogRequest("travel.shared.orders", 2, "shared_orders", "2"))
            val root = QueryComputation.Members("shared", MemberRef(old.id, 2), mapOf("window" to c.july),
                listOf(QueryComputation.Slot("order", "order")), null, emptyMap(), MemberCondition.Unrestricted(), emptyMap(), emptyMap())
            val rows = f.execute(publication, QueryComputation.Scope(root, emptyMap(), emptyList())).result.resultFrame.rows
            assertEquals(setOf("1", "2", "3", "5"), rows.map { it["order"] }.toSet())
        }
    }

    @Test
    fun concurrentPermissionWriterMustReadTheRevisedAdoptionAfterTheContextLock(@TempDir directory: Path) {
        PlatformDatabase.inMemory().use { database ->
            val c = ComputedOrderFixture(database, directory)
            c.publish()
            val f = c.f
            carry(database, f, "travel.shared")
            val previous = f.adoptions.memberSource(c.sharedV1.id, 2)
            val source = f.adoptions.adoptMemberDefinition(MemberDefinitionAdoptRequest("travel.shared", 2, "editable", "待采用订单",
                MemberDefinitionRef(c.sharedRule.id, 1), previous.bindings, previous.fixedArguments, "golden"))
            val scenario = f.scenarios.createScenario(ScenarioCreateRequest("travel.shared", 2, "concurrent", "并发授权",
                "trade", null, "golden")).scenario
            val original = c.revisedRule.definition as MemberSourceDefinition.Computed
            val result = original.result
            val narrow = MemberSourceDefinition.Computed(original.calculation, MemberResult(result.entity, result.role, result.shape,
                result.identitySlots, mapOf("order" to result.outputs.getValue("order")), emptyMap(), result.timeSlot, result.clockSlots, "订单身份"))
            f.members.revise(GovernanceRevisionRequest(c.sharedRule.id, 2, MemberDefinitionCreateRequest(c.sharedRule.code,
                c.sharedRule.displayName, narrow, null, "trade", "golden")))
            f.activate("MEMBER_DEFINITION", c.sharedRule.id, 3)
            Executors.newSingleThreadExecutor().use { executor ->
                lateinit var writer: Future<ScenarioMemberSourceRefRecord>
                database.inTransaction {
                    database.connection().use { connection -> connection.prepareStatement("SELECT id FROM subject_area WHERE id=? FOR UPDATE").use { statement ->
                        statement.setString(1, "travel.shared")
                        statement.executeQuery().use { assertTrue(it.next()) }
                    } }
                    val started = CountDownLatch(1)
                    writer = executor.submit<ScenarioMemberSourceRefRecord> {
                        started.countDown()
                        f.scenarios.createScenarioMemberSourceRef(ScenarioMemberSourceRefCreateRequest(scenario.id, 1, source.id, 2,
                            MemberQueryPermission(listOf("amount"), emptyList(), emptyMap(), false, emptyList())))
                    }
                    assertTrue(started.await(5, TimeUnit.SECONDS))
                    assertThrows(TimeoutException::class.java) { writer.get(250, TimeUnit.MILLISECONDS) }
                    commands(database, f)("revise-member-adoption", MemberAdoptionRevisionRequest(MemberRef(source.id, 2),
                        MemberDefinitionRef(c.sharedRule.id, 1), MemberDefinitionRef(c.sharedRule.id, 3), source.displayName,
                        source.bindings, mapOf("minimum_v2" to Calculation.Scalar(ScalarValueType.NUMBER, "50")), "golden"))
                }
                val failure = assertThrows(ExecutionException::class.java) { writer.get(5, TimeUnit.SECONDS) }
                assertInstanceOf(ProducerContractException::class.java, failure.cause)
                assertTrue(f.scenarios.memberSourceRefs(scenario.id, 1).isEmpty())
                assertEquals(MemberDefinitionOrigin.Shared(MemberDefinitionRef(c.sharedRule.id, 3)), f.adoptions.memberSource(source.id, 2).origin)
            }
        }
    }

    private fun carry(database: PlatformDatabase, f: GovernanceOrderFixture, subject: String) {
        val revisions = SubjectContextRevisionService(database, f.subjects, f.analysis, f.adoptions, f.scenarios)
        val source = f.subjects.subjectAreaSnapshot(subject, 1)
        revisions.revise(SubjectContextRevisionRequest(subject, 1, SubjectAreaCreateRequest(source.businessLineId, source.businessLineVersion,
            source.code, source.displayName, source.owner, source.description, "golden"), revisions.selection(SubjectContextSelectionRequest(subject, 1)).selection))
    }

    private fun commands(database: PlatformDatabase, f: GovernanceOrderFixture): (String, Any) -> Any {
        val operations = GovernanceOperations(GovernanceApplication(database, f.connectors), emptyMap(), f.onboarding)
        val inspection = JdbcSourceInspection(JdbcWarehouseInspector(f.connectors), f.connectors)
        return { operation, request -> operations.dispatchAuthenticated(operation, request,
            RuntimeDeploymentLimits.CONTROL, "golden", inspection) {} }
    }
}
