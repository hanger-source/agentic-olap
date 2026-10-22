package com.olap.prototype.release.catalog

import com.olap.prototype.codec.GovernanceProto
import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.member.*
import com.olap.prototype.metric.MetricAggregation
import com.olap.prototype.query.QueryComputation
import com.olap.prototype.query.ScalarValueType
import com.olap.prototype.query.contract.*
import com.olap.prototype.release.catalog.FrozenPublicationFixture.Companion.CITY
import com.olap.prototype.release.catalog.FrozenPublicationFixture.Companion.DESTINATION
import com.olap.prototype.release.catalog.FrozenPublicationFixture.Companion.EVENT
import com.olap.prototype.release.catalog.FrozenPublicationFixture.Companion.LOCAL
import com.olap.prototype.release.catalog.FrozenPublicationFixture.Companion.call
import com.olap.prototype.release.catalog.FrozenPublicationFixture.Companion.computation
import com.olap.prototype.release.catalog.FrozenPublicationFixture.Companion.connectors
import com.olap.prototype.release.catalog.FrozenPublicationFixture.Companion.execute
import com.olap.prototype.release.catalog.FrozenPublicationFixture.Companion.fact
import com.olap.prototype.release.catalog.FrozenPublicationFixture.Companion.frozen
import com.olap.prototype.release.catalog.FrozenPublicationFixture.Companion.mapper
import com.olap.prototype.release.catalog.FrozenPublicationFixture.Companion.metricReferences
import com.olap.prototype.release.catalog.FrozenPublicationFixture.Companion.records
import com.olap.prototype.release.catalog.FrozenPublicationFixture.Companion.ref
import com.olap.prototype.release.catalog.FrozenPublicationFixture.Companion.request
import com.olap.prototype.release.catalog.capability.PublishedQuerySources
import com.olap.prototype.release.catalog.capability.ScenarioMemberReference
import com.olap.prototype.release.contract.ReleaseCatalogView
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path

class FrozenMemberPopulationTest {
    private val assembler = ReleaseExecutableCatalogAssembler(connectors)

    @Test
    fun keepsFactPopulationWhileMemberRelationsRetainTheirDeclaredOuterRows(@TempDir directory: Path) {
        listOf("INNER", "LEFT", "RIGHT", "FULL").forEach { direction ->
            FrozenPublicationFixture(directory, "duckdb", true, listOf(
                "INSERT INTO orders VALUES (6,'2026-06-01',11,NULL),(7,'2026-06-01',13,42)", "INSERT INTO destinations VALUES (99,'孤立目标')")).use { fixture ->
                val base = fixture.release(mapOf("count" to fact(com.olap.prototype.expression.SemanticExpression.Literal(BigDecimal.ONE), MetricAggregation.COUNT, contract = records()),
                    "amount" to fact(ref("orders.amount"), MetricAggregation.SUM)), false, direction = direction)
                val definition = MemberSourceDefinition.Projection(EVENT, MemberResult("目的地", "destination", MemberShape.UNIQUE_MEMBERS, listOf("id"), mapOf(
                    "id" to MemberSourceDefinition.PropertyOutput("目的地身份", "destination", ScalarValueType.NUMBER, "destination.id", 1, DESTINATION, null),
                    "label" to MemberSourceDefinition.PropertyOutput("名称", "label", ScalarValueType.TEXT, "destination.name", 1, DESTINATION, null)), emptyMap(), null, emptyMap(), null))
                val member = PublishedMemberSource(MemberSource.Projection("destinations", 1, definition, mapOf("id" to ref("destination.id"), "label" to ref("destination.name"))),
                    "subject", "destinations", "目的地成员")
                val release = appendMember(fixture, base, member)
                val catalog = memberPublication(release, "destinations")
                val required = direction in setOf("INNER", "RIGHT")
                listOf(false, true).forEach { shared ->
                    val calls = if (shared) listOf(call(catalog, "counted", "count"), call(catalog, "amount", "amount"), call(catalog, "counted_again", "count"))
                        else listOf(call(catalog, "counted", "count"))
                    val rows = execute(fixture, catalog, request(calls, listOf(CITY), timed = false))
                    val counts = rows.associate { it.first() as String? to (it[1] as Number).toLong() }
                    val expected = mutableMapOf<String?, Long>("杭州" to 3L, "南京" to 1L, "其他" to 1L)
                    if (!required) expected[null] = 2L
                    assertEquals(expected, counts, "$direction shared=$shared")
                    if (shared) {
                        assertEquals(if (required) 1099 else 1123, rows.sumOf { (it[2] as Number).toInt() })
                        rows.forEach { assertEquals((it[1] as Number).toLong(), (it[3] as Number).toLong()) }
                    }
                }
                PublishedQueryDefinitionFixture(directory, "$direction-population", release, catalog).use { definitions ->
                    val members = memberScope("destinations", listOf("id", "label"))
                    val saved = definitions.service.save(definitions.command("destination_members", members), "tester")
                    val restored = definitions.service.requireByIdAndVersion(saved.definitionId, saved.definitionVersion)
                    val rows = execute(fixture, catalog, restored.intent.computation)
                    val expected = mutableSetOf("7", "8", "9")
                    if (direction in setOf("LEFT", "FULL")) expected += "NULL"
                    if (direction in setOf("RIGHT", "FULL")) expected += "99"
                    assertEquals(expected, rows.map { it.first()?.toString() ?: "NULL" }.toSet())
                    assertEquals(expected.size, rows.size)
                    rows.filter { it.first() == null }.forEach { assertNull(it[1]) }
                    if (direction in setOf("RIGHT", "FULL")) fixture.connection.createStatement().use { sql ->
                        sql.executeQuery("SELECT COUNT(*) FROM orders o $direction JOIN destinations d ON o.destination=d.id").use {
                            assertTrue(it.next()); assertEquals(if (required) 6 else 8, it.getInt(1))
                        }
                    }
                }
            }
        }
    }

    @Test
    fun freezesOnlyProvenMemberProjectionsAndExecutesTheirDeclaredPopulation(@TempDir directory: Path) {
        listOf(false, true).forEach { unique -> MemberShape.entries.forEach { shape -> FrozenPublicationFixture(directory, "duckdb", unique).use { fixture ->
            val base = fixture.release(emptyMap(), false)
            val identity = if (shape == MemberShape.UNIQUE_MEMBERS) "orders.destination" else "orders.id"
            val definition = MemberSourceDefinition.Projection(EVENT, MemberResult("订单归属", "membership", shape,
                if (shape == MemberShape.RAW_BAG) emptyList() else listOf("id"), mapOf(
                    "id" to MemberSourceDefinition.PropertyOutput("成员身份", "membership", ScalarValueType.NUMBER, identity, 1, LOCAL, null),
                    "label" to MemberSourceDefinition.PropertyOutput("关联名称", "label", ScalarValueType.TEXT, "destination.name", 1, DESTINATION, null)), emptyMap(), null, emptyMap(), null))
            val member = PublishedMemberSource(MemberSource.Projection("members", 1, definition, mapOf("id" to ref(identity), "label" to ref("destination.name"))), "subject", "members", "成员试验")
            val release = appendMember(fixture, base, member)
            val catalog = memberPublication(release, "members")
            PublishedQueryDefinitionFixture(directory, "$shape-$unique", release, catalog).use { definitions ->
                listOf(false, true).forEach { attribute ->
                    val computation = memberScope("members", if (attribute) listOf("id", "label") else listOf("id"))
                    val command = definitions.command(if (attribute) "attributes" else "identities", computation)
                    val input = computation
                    if (attribute && !unique && shape != MemberShape.RAW_BAG) {
                        val failure = assertThrows(ProducerContractException::class.java) { definitions.service.save(command, "tester") }
                        assertTrue(failure.message.orEmpty().contains("COMPILE_REL"), failure.message)
                        assertNull(definitions.service.findByIdAndVersion("attributes", 1))
                        assertThrows(IllegalArgumentException::class.java) { execute(fixture, catalog, input) }
                    } else {
                        val saved = definitions.service.save(command, "tester")
                        val rows = execute(fixture, catalog, definitions.service.requireByIdAndVersion(saved.definitionId, saved.definitionVersion).intent.computation)
                        val actual = rows.map { (it.first() as Number).toInt() }.sorted()
                        val expected = when {
                            shape == MemberShape.RAW_BAG && attribute && !unique -> listOf(1, 1, 1, 2, 2, 2, 3, 4, 5, 5, 5)
                            shape == MemberShape.UNIQUE_MEMBERS -> listOf(7, 8, 9)
                            else -> listOf(1, 2, 3, 4, 5)
                        }
                        assertEquals(expected, actual)
                        if (attribute && shape == MemberShape.RAW_BAG && !unique) {
                            val bag = rows.groupingBy { listOf((it.first() as Number).toInt(), it[1]) }.eachCount()
                            assertEquals(mapOf(listOf(1, "杭州") to 2, listOf(1, "南京") to 1, listOf(2, "杭州") to 2, listOf(2, "南京") to 1,
                                listOf(5, "杭州") to 2, listOf(5, "南京") to 1, listOf(3, "南京") to 1, listOf(4, null) to 1), bag)
                        } else if (attribute) rows.forEach { row ->
                            val id = (row.first() as Number).toInt()
                            val label = if (shape == MemberShape.UNIQUE_MEMBERS) when (id) { 7 -> "杭州"; 8 -> "南京"; else -> "其他" }
                                else when (id) { 3 -> "南京"; 4 -> "其他"; else -> "杭州" }
                            assertEquals(label, row[1])
                        }
                    }
                }
            }
            fixture.connection.createStatement().use { sql -> sql.executeQuery("SELECT COUNT(*) FROM orders o LEFT JOIN destinations d ON o.destination=d.id").use {
                assertTrue(it.next()); assertEquals(if (unique) 5 else 11, it.getInt(1))
            } }
        } } }
    }

    private fun memberScope(id: String, names: List<String>) = QueryComputation.Scope(QueryComputation.Members("members", MemberRef(id, 1), emptyMap(), names.map { QueryComputation.Slot(it, it) }, null, emptyMap(), MemberCondition.Unrestricted(), emptyMap(), emptyMap()), emptyMap(), emptyList())

    private fun appendMember(fixture: FrozenPublicationFixture, base: ReleaseCatalogView, member: PublishedMemberSource) = ReleaseCatalogView(
        base.scenarioId, base.scenarioVersion, base.scenarioDisplayName, base.releaseId, base.releaseVersion, base.releaseDisplayName,
        base.objectVersionRefs + fixture.objectRef("MEMBER_SOURCE", member.source.id, GovernanceProto.toProto(member)), base.metricAnalysisDimensionCapability,
        base.dimensionMappings, base.timeAnchorRule, base.unavailableReason, base.diagnostics)

    private fun memberPublication(release: ReleaseCatalogView, sourceId: String): PublishedExecutableCatalog {
        val assets = assembler.compileAssets(release, metricReferences(release))
        val sources = PublishedQuerySources(ReleaseObjectIndex(release.objectVersionRefs), assets)
        val candidate = sources.member(ScenarioMemberReference(sourceId, 1, MemberQueryPermission(listOf("id", "label"), emptyList(), emptyMap(), false, emptyList())), emptySet())
        return frozen(release, members = listOf(candidate), assets = assets)
    }
}
