package com.olap.prototype.release.catalog

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.olap.prototype.calculation.*
import com.olap.prototype.engine.catalog.ExecutableCatalog
import com.olap.prototype.engine.catalog.ExecutableTopology
import com.olap.prototype.event.RelationAccessPath
import com.olap.prototype.expression.SemanticExpression
import com.olap.prototype.member.MemberCondition
import com.olap.prototype.metric.MetricAggregation
import com.olap.prototype.query.*
import com.olap.prototype.release.catalog.FrozenPublicationFixture.Companion.CITY
import com.olap.prototype.release.catalog.FrozenPublicationFixture.Companion.DESTINATION
import com.olap.prototype.release.catalog.FrozenPublicationFixture.Companion.LOCAL
import com.olap.prototype.release.catalog.FrozenPublicationFixture.Companion.adoptedInstance
import com.olap.prototype.release.catalog.FrozenPublicationFixture.Companion.amount
import com.olap.prototype.release.catalog.FrozenPublicationFixture.Companion.bind
import com.olap.prototype.release.catalog.FrozenPublicationFixture.Companion.bindFrozen
import com.olap.prototype.release.catalog.FrozenPublicationFixture.Companion.call
import com.olap.prototype.release.catalog.FrozenPublicationFixture.Companion.cityAmounts
import com.olap.prototype.release.catalog.FrozenPublicationFixture.Companion.connectors
import com.olap.prototype.release.catalog.FrozenPublicationFixture.Companion.eq
import com.olap.prototype.release.catalog.FrozenPublicationFixture.Companion.execute
import com.olap.prototype.release.catalog.FrozenPublicationFixture.Companion.fact
import com.olap.prototype.release.catalog.FrozenPublicationFixture.Companion.frozen
import com.olap.prototype.release.catalog.FrozenPublicationFixture.Companion.mapper
import com.olap.prototype.release.catalog.FrozenPublicationFixture.Companion.metricReferences
import com.olap.prototype.release.catalog.FrozenPublicationFixture.Companion.money
import com.olap.prototype.release.catalog.FrozenPublicationFixture.Companion.records
import com.olap.prototype.release.catalog.FrozenPublicationFixture.Companion.ref
import com.olap.prototype.release.catalog.FrozenPublicationFixture.Companion.request
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path

class FrozenRelationExecutionTest {
    private val assembler = ReleaseExecutableCatalogAssembler(connectors)

    @Test
    fun keepsAmountAndDistinctCustomerPredicateInIndependentGrains(@TempDir directory: Path) {
        listOf("h2", "duckdb").forEach { dialect -> FrozenPublicationFixture(directory, dialect, false).use { fixture ->
            val eligible = SemanticExpression.Exists("qualified_destination", SemanticExpression.LocalParent(), DESTINATION,
                eq(ref("destination.name"), SemanticExpression.Literal("杭州")))
            val catalog = frozen(fixture.release(mapOf("amount" to fact(ref("orders.amount"), MetricAggregation.SUM),
                "customers" to fact(ref("orders.destination"), MetricAggregation.COUNT, true, eligible, records())), true))
            val result = execute(fixture, catalog, request(listOf(call(catalog, "payment_total", "amount"), call(catalog, "qualified_customers", "customers"))))
            assertEquals(0, BigDecimal("100").compareTo(result.first()[0] as BigDecimal))
            assertEquals(1L, (result.first()[1] as Number).toLong())
            // 独立反例SQL显示扇出关联后直接SUM会得到160，不能用错误共同人口冒充通过。
            fixture.connection.createStatement().use { sql -> sql.executeQuery("SELECT SUM(o.amount) FROM orders o LEFT JOIN destinations d ON o.destination=d.id WHERE o.occurred < DATE '2026-06-08'").use {
                assertTrue(it.next()); assertEquals(0, BigDecimal("160").compareTo(it.getBigDecimal(1)))
            } }
        } }
    }

    @Test
    fun refusesUndeclaredRelatedPositionsAndMissingFrozenPaths(@TempDir directory: Path) {
        FrozenPublicationFixture(directory, "duckdb", true).use { fixture ->
            val definitions = mapOf("amount" to fact(ref("orders.amount"), MetricAggregation.SUM, predicate = eq(ref("destination.name"), SemanticExpression.Literal("杭州"))))
            assertThrows(RuntimeException::class.java) { assembler.compileAssets(fixture.release(definitions, true), emptyList()) }
            val missing = RelationAccessPath(listOf(RelationAccessPath.JoinRef("destination_path", 2)))
            val exists = SemanticExpression.Exists("qualification", SemanticExpression.LocalParent(), missing, SemanticExpression.Literal(true))
            assertThrows(RuntimeException::class.java) { assembler.compileAssets(fixture.release(mapOf("amount" to fact(ref("orders.amount"), MetricAggregation.SUM, predicate = exists)), true), emptyList()) }
        }
    }

    @Test
    fun directKernelCatalogRejectsWrongTopologyBrokenPathsAndMisplacedRoles(@TempDir directory: Path) {
        FrozenPublicationFixture(directory, "duckdb", true).use { fixture ->
            val release = fixture.release(amount(), true)
            val assets = assembler.compileAssets(release, metricReferences(release))
            val base = mapper.valueToTree<ObjectNode>(assets)
            val topology = assets.topologies.getValue("payment_topology@1")
            (base.get("topologies") as ObjectNode).set<JsonNode>("unrelated@1", mapper.valueToTree(ExecutableTopology("unrelated", topology.topologyVersion,
                topology.topologyImplementationNamespaceCode, topology.topologyImplementationNamespaceDisplayName, topology.backboneLogicalTableId,
                topology.backboneLogicalTableVersion, emptyList(), emptyList())))
            val valid = mapper.treeToValue(base, ExecutableCatalog::class.java)
            valid.validate()
            val wrongTopology = changed(valid) { objectAt(it, "joinPaths", "destination_path@1").put("topologyId", "unrelated") }
            assertThrows(IllegalArgumentException::class.java) { wrongTopology.validate() }
            val brokenPath = changed(valid) { objectAt(it, "dimensionBindings", "city_binding@1").set<JsonNode>("accessPath",
                mapper.valueToTree(RelationAccessPath(listOf(DESTINATION.joinPathRefs.first(), DESTINATION.joinPathRefs.first())))) }
            assertThrows(IllegalArgumentException::class.java) { brokenPath.validate() }
            val misplacedRole = changed(valid) {
                objectAt(it, "events", "payment@1").set<JsonNode>("roles", mapper.valueToTree(mapOf("destination" to DESTINATION)))
                objectAt(it, "events", "payment@1").set<JsonNode>("baseFilter", mapper.valueToTree(eq(SemanticExpression.Reference(
                    SemanticExpression.ReferenceKind.ROLE_LOGIC_UNIT, "orders.amount", 1, "destination"), SemanticExpression.Literal(BigDecimal.ONE))))
            }
            assertThrows(IllegalArgumentException::class.java) { misplacedRole.validate() }
        }
    }

    @Test
    fun rejectsMetricCatalogWhenSourceEventTimeAnchorIsMissing(@TempDir directory: Path) {
        FrozenPublicationFixture(directory, "duckdb", true).use { fixture ->
            val catalog = frozen(fixture.release(amount(), false))
            assertThrows(IllegalArgumentException::class.java) { bindFrozen(catalog, request(listOf(call(catalog, "payment_total", "amount")))) }
        }
    }

    @Test
    fun lowersSqlWithSelectedReleaseTimeAnchorAndPhysicalBinding(@TempDir directory: Path) {
        listOf("h2", "duckdb").forEach { dialect -> FrozenPublicationFixture(directory, dialect, true).use { fixture ->
            val catalog = frozen(fixture.release(amount(), true))
            assertEquals(listOf(listOf(BigDecimal("100.00"))), execute(fixture, catalog, request(listOf(call(catalog, "payment_total", "amount")))))
        } }
    }

    @Test
    fun executesFrozenJoinPathForCrossTableAnalysisDimension(@TempDir directory: Path) {
        FrozenPublicationFixture(directory, "duckdb", true).use { fixture ->
            val catalog = frozen(fixture.release(amount(), true))
            assertEquals(mapOf("杭州" to BigDecimal("30.00"), "南京" to BigDecimal("30.00"), "其他" to BigDecimal("40.00")),
                cityAmounts(execute(fixture, catalog, request(listOf(call(catalog, "payment_total", "amount")), listOf(CITY)))))
            FrozenPublicationFixture(directory, "duckdb", false).use { duplicated ->
                val unsafe = frozen(duplicated.release(amount(), true))
                assertThrows(RuntimeException::class.java) { execute(duplicated, unsafe, request(listOf(call(unsafe, "payment_total", "amount")), listOf(CITY))) }
            }
        }
    }

    @Test
    fun executesFrozenCompoundDimensionDependenciesBeyondItsPrimaryBinding(@TempDir directory: Path) {
        FrozenPublicationFixture(directory, "duckdb", true).use { fixture ->
            val expression = SemanticExpression.Operation(SemanticExpression.Operator.ADD, listOf(ref("orders.amount"),
                SemanticExpression.Reference(SemanticExpression.ReferenceKind.ROLE_LOGIC_UNIT, "destination.id", 1, "destination")))
            val catalog = frozen(fixture.release(amount(), true, expression, LOCAL, mapOf("destination" to DESTINATION)))
            val predicate = DimensionCondition.Predicate("compound", CITY, MemberCondition.Operator.EQ, listOf(Calculation.Scalar(ScalarValueType.NUMBER, "17.00")))
            val input = request(listOf(call(catalog, "payment_total", "amount")), events = listOf(QueryComputation.EventCondition("payment", "1", predicate)))
            assertEquals(listOf(listOf(BigDecimal("10.00"))), execute(fixture, catalog, input))
            val damaged = changed(catalog.catalog) { objectAt(it, "dimensionBindings", "city_binding@1").set<JsonNode>("roles", mapper.createObjectNode()) }
            assertThrows(RuntimeException::class.java) { bind(damaged, input) }
        }
    }

    @Test
    fun executesFrozenCalculationWithoutAssigningItAFactEvent(@TempDir directory: Path) {
        FrozenPublicationFixture(directory, "duckdb", true).use { fixture ->
            val calculation = MetricCalculation(Calculation(emptyList(), emptyList(), emptyList(), emptyList(), emptyMap(), Calculation.Scope(Calculation.Measures(
                listOf(Calculation.Call("payment", Calculation.Fixed(MetricRef("amount", "1")), "value", emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), Calculation.Condition(DimensionCondition.Unrestricted()))),
                emptyList(), emptyList(), null, null, emptyMap(), Calculation.Population(listOf("payment")), null), emptyMap(), emptyList())), "value", emptyList(), money())
            val catalog = frozen(fixture.release(amount() + ("complete_amount" to calculation), true))
            assertEquals(listOf(listOf(BigDecimal("1099.00"))), execute(fixture, catalog, request(listOf(call(catalog, "completed", "complete_amount")), timed = false)))
        }
    }

    @Test
    fun executesExplicitEventFilterAfterFrozenJoinAndRejectsInvalidScopes(@TempDir directory: Path) {
        FrozenPublicationFixture(directory, "duckdb", true).use { fixture ->
            val catalog = frozen(fixture.release(amount(), true))
            listOf("杭州", "不存在的目的地").forEach { city ->
                val condition = DimensionCondition.Predicate("city_filter", CITY, MemberCondition.Operator.EQ, listOf(Calculation.Scalar(ScalarValueType.TEXT, city)))
                val calls = listOf(call(catalog, "payment_total", "amount"))
                val rows = execute(fixture, catalog, request(calls, events = listOf(QueryComputation.EventCondition("payment", "1", condition))))
                if (city == "杭州") assertEquals(listOf(listOf(BigDecimal("30.00"))), rows) else assertNull(rows.first().first())
                assertThrows(IllegalArgumentException::class.java) { bindFrozen(catalog, request(calls, events = listOf(QueryComputation.EventCondition("payment", "2", condition)))) }
            }
        }
    }

    private fun changed(catalog: ExecutableCatalog, change: (ObjectNode) -> Unit): ExecutableCatalog = mapper.treeToValue(mapper.valueToTree<ObjectNode>(catalog).also(change), ExecutableCatalog::class.java)
    private fun objectAt(root: ObjectNode, vararg path: String): ObjectNode = path.fold(root as JsonNode) { node, field -> node.required(field) } as ObjectNode
}
