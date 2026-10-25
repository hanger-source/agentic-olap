package com.olap.prototype.engine.executable

import com.olap.prototype.connector.JdbcAccess
import com.olap.prototype.contract.JdbcParameter
import com.olap.prototype.engine.calcite.*
import com.olap.prototype.engine.catalog.ExecutablePhysicalExecutionBinding
import com.olap.prototype.engine.catalog.ExecutableRelationPosition.Id
import com.olap.prototype.expression.*
import com.olap.prototype.codec.SemanticProto
import com.olap.prototype.codec.ProtoBytes
import com.olap.prototype.integration.TestConnectors
import com.olap.prototype.metric.MetricValueContract
import com.olap.prototype.query.ScalarValueType
import com.olap.prototype.query.ResultAggregation
import com.olap.prototype.util.OlapJsonUtils
import org.apache.calcite.rel.RelNode
import org.apache.calcite.rel.RelVisitor
import org.apache.calcite.rel.core.Aggregate
import org.apache.calcite.rel.core.JoinRelType
import org.apache.calcite.rex.RexNode
import org.apache.calcite.sql.`fun`.SqlStdOperatorTable as Ops
import org.apache.calcite.tools.Frameworks
import org.apache.calcite.tools.RelBuilder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path
import java.sql.Types
import java.time.LocalDateTime
import java.util.Collections
import java.util.IdentityHashMap

class CalciteExpressionExecutionTest {
    private fun frozenExpression(expression: SemanticExpression): SemanticExpression = SemanticProto.fromProto(
        ProtoBytes.decode(ProtoBytes.encode(SemanticProto.toProto(expression)), com.olap.prototype.wire.semantic.SemanticExpression.getDefaultInstance()))
    @TempDir lateinit var directory: Path
    private fun quantity(arithmetic: MetricValueContract.Arithmetic, result: MetricValueContract.Result) = MetricValueContract(
        MetricValueContract.Fixed(MetricValueContract.UnitIdentity("trial", "money", 1)), arithmetic, result, emptyMap(), MetricValueContract.Overflow.FAIL)
    private fun builder(binding: ExecutablePhysicalExecutionBinding): RelBuilder =
        CalciteQueryCompiler.inputs(listOf(binding), binding.physicalColumnBindings.keys, emptySet(), TestConnectors.registry).builder.scan(FrozenCatalogSchema.path(binding))
    private fun localCompiler(b: RelBuilder, binding: ExecutablePhysicalExecutionBinding, parameters: MutableList<JdbcParameter>): SemanticRexCompiler {
        val root = Id.backbone()
        return SemanticRexCompiler(b, root, mapOf(root to binding), { _, column -> b.field(column) }, parameters,
            { _, _ -> throw IllegalArgumentException("scalar fixture declares no relationship quantifiers") }, linkedSetOf())
    }
    private fun rows(source: NativeWarehouse, plan: RelNode, parameters: List<JdbcParameter> = emptyList()): List<List<Any?>> {
        val connector = TestConnectors.registry.require(source.spec.connectorRef)
        val sql = CalciteSqlRendering.render(plan, connector.compilation, parameters)
        val ordered = CalciteSqlRendering.orderedParameters(sql, parameters)
        return source.connection.prepareStatement(sql.sql).use { statement ->
            ordered.forEachIndexed { index, parameter -> parameter.bind(statement, index + 1) }
            statement.executeQuery().use { rs -> buildList {
                while (rs.next()) add((1..rs.metaData.columnCount).map { (connector.access as JdbcAccess).jdbc.scalar(rs, it) })
            } }
        }
    }
    private fun decimal(value: Any?, expected: String) = assertEquals(0, BigDecimal(expected).compareTo(BigDecimal(requireNotNull(value).toString())))
    private fun aggregateNodes(plan: RelNode): Set<RelNode> {
        val nodes = Collections.newSetFromMap(IdentityHashMap<RelNode, Boolean>())
        object : RelVisitor() { override fun visit(node: RelNode, ordinal: Int, parent: RelNode?) {
            if (node is Aggregate) nodes.add(node)
            super.visit(node, ordinal, parent)
        } }.go(plan)
        return nodes
    }
    @Test fun executesDeclaredTieRoundingOnBothWarehouses() {
        for (dialect in listOf("h2", "duckdb")) NativeWarehouse(directory, dialect, listOf(
            "CREATE TABLE values_to_round(amount DECIMAL(18,2))", "INSERT INTO values_to_round VALUES (-1.35),(-1.25),(1.25),(1.35)")).use { source ->
            val b = builder(source.adopt("values_to_round", emptyMap()))
            val value = b.field("amount")
            val declaration = quantity(MetricValueContract.Exact(), MetricValueContract.Decimal(30, 1, MetricValueContract.Rounding.NONE))
            val rounded = mutableListOf<RexNode>(value)
            listOf(MetricValueContract.Rounding.HALF_UP, MetricValueContract.Rounding.HALF_DOWN, MetricValueContract.Rounding.HALF_EVEN)
                .forEach { rounded += SemanticNumericRex.output(b, SemanticNumericRex.round(b, value, 1, it), declaration) }
            b.project(rounded, listOf("amount", "half_up", "half_down", "half_even"), true).sort(b.field("amount"))
            val result = rows(source, CalciteQueryCompiler.optimize(b.build(), emptyList()))
            val expected = listOf(listOf("-1.4", "-1.3", "-1.4"), listOf("-1.3", "-1.2", "-1.2"), listOf("1.3", "1.2", "1.2"), listOf("1.4", "1.3", "1.4"))
            assertEquals(expected.size, result.size)
            expected.forEachIndexed { row, values -> values.forEachIndexed { column, amount -> decimal(result[row][column + 1], amount) } }
        }
    }
    @Test fun preservesLargeExactSumsAndExposesNativeMeanPrecision() {
        for (dialect in listOf("h2", "duckdb")) NativeWarehouse(directory, dialect, listOf(
            "CREATE TABLE large_quantities(amount DECIMAL(18,0))", "INSERT INTO large_quantities VALUES (9007199254740993),(9007199254740993)")).use { source ->
            val b = builder(source.adopt("large_quantities", emptyMap())); val value = b.field("amount")
            val exactSum = quantity(MetricValueContract.Exact(), MetricValueContract.Decimal(38, 0, MetricValueContract.Rounding.NONE))
            val exactMean = quantity(MetricValueContract.Exact(), MetricValueContract.Decimal(38, 10, MetricValueContract.Rounding.NONE))
            SemanticNumericRex.aggregate(b, ResultAggregation.Sum(exactSum, ResultAggregation.NullPolicy.IGNORE), value)
            // 直接观察来源 AVG 的技术精度；不把有限样本冒充正式均值目标或误差保证。
            b.aggregate(b.groupKey(), b.sum(false, "total", value), b.avg(false, "mean", value))
            if (dialect == "duckdb") assertThrows(IllegalArgumentException::class.java) { SemanticNumericRex.output(b, b.field("mean"), exactMean) }
            b.project(listOf(SemanticNumericRex.output(b, b.field("total"), exactSum), b.field("mean")), listOf("total", "mean"), true)
            val result = rows(source, CalciteQueryCompiler.optimize(b.build(), emptyList())).single()
            decimal(result[0], "18014398509481986")
            if (dialect == "h2") decimal(result[1], "9007199254740993")
            else assertEquals(BigDecimal.ONE, BigDecimal("9007199254740993").subtract(BigDecimal.valueOf((result[1] as Number).toDouble())).abs())
        }
    }

    @Test fun sharesIdenticalFrozenCutoffsWhileKeepingDifferentMicrosecondRanges() {
        NativeWarehouse(directory, "duckdb", listOf(
            "CREATE TABLE clock_amounts(unselected_text VARCHAR,owner VARCHAR,occurred_at TIMESTAMP,amount DECIMAL(18,2))",
            "INSERT INTO clock_amounts VALUES ('not-an-amount','A',TIMESTAMP '2026-01-01 00:00:00.000000',1),('not-an-amount','A',TIMESTAMP '2026-01-01 00:00:00.000001',10),('not-an-amount','A',TIMESTAMP '2026-01-01 00:00:00.000002',100),('not-an-amount','A',TIMESTAMP '2026-01-01 00:00:00.000003',1000)",
            "CREATE TABLE owners(unselected_text VARCHAR,owner VARCHAR)", "INSERT INTO owners VALUES('not-a-clock','A')")).use { source ->
            val binding = source.adopt("clock_amounts", emptyMap()); val owner = source.adopt("owners", emptyMap())
            val selected = listOf("clock_amounts:occurred_at@1", "clock_amounts:amount@1", "clock_amounts:owner@1", "owners:owner@1").toSet()
            val b = CalciteQueryCompiler.inputs(listOf(binding, owner), selected, emptySet(), TestConnectors.registry).builder
            val parameters = mutableListOf<JdbcParameter>()
            val branches = listOf("2026-01-01T00:00:00.000002", "2026-01-01T00:00:00.000002", "2026-01-01T00:00:00.000003").map { cutoff ->
                b.scan(FrozenCatalogSchema.path(binding)); b.scan(FrozenCatalogSchema.path(owner))
                b.join(JoinRelType.LEFT, b.equals(b.field(2, 0, "owner"), b.field(2, 1, "owner")))
                val time = b.field("occurred_at"); val bound = b.rexBuilder.makeDynamicParam(time.type, parameters.size)
                parameters += JdbcParameter.of(Types.TIMESTAMP, "TIMESTAMP", time.type.precision, 0, LocalDateTime.parse(cutoff))
                b.filter(b.lessThan(time, bound)); b.aggregate(b.groupKey(), b.sum(false, "total", b.field("amount")), b.countStar("receipts")); b.build()
            }
            b.push(branches[0]).push(branches[1]).join(JoinRelType.INNER, b.literal(true))
            b.push(branches[2]).join(JoinRelType.INNER, b.literal(true))
            b.project(b.fields(), listOf("first_total", "first_count", "same_total", "same_count", "later_total", "later_count"), true)
            val raw = b.build(); val optimized = CalciteQueryCompiler.optimize(raw, parameters)
            assertEquals(2, aggregateNodes(optimized).size, "相同截止共享，差一微秒的范围独立计算")
            for (plan in listOf(raw, optimized)) {
                val actual = rows(source, plan, parameters).single()
                decimal(actual[0], "11"); decimal(actual[2], "11"); decimal(actual[4], "111")
                assertEquals(listOf(2, 2, 3), listOf(actual[1], actual[3], actual[5]).map { (it as Number).toInt() })
            }
            val sql = CalciteSqlRendering.render(optimized, TestConnectors.registry.require(source.spec.connectorRef).compilation)
            source.connection.prepareStatement("EXPLAIN (FORMAT JSON) ${sql.sql}").use { query ->
                CalciteSqlRendering.orderedParameters(sql, parameters).forEachIndexed { index, parameter -> parameter.bind(query, index + 1) }
                query.executeQuery().use { rs -> assertTrue(rs.next()); val plan = OlapJsonUtils.mapper().readTree(rs.getString(2))
                    assertEquals(2, plan.findValues("name").count { it.asText().trim() == "SEQ_SCAN" }, "SQL转换保留共同计算") }
            }
        }
    }
    @Test fun retainsCommonAggregationWhenConsumersNeedDifferentOutputs() {
        val b = RelBuilder.create(Frameworks.newConfigBuilder().build())
        b.values(arrayOf("buyer", "amount"), "A", 1, "A", 2, "B", 3)
        b.aggregate(b.groupKey("buyer"), b.sum(false, "total", b.field("amount")), b.countStar("receipts"))
        val common = b.build(); b.push(common).project(b.field("buyer"), b.field("total")); val totals = b.build()
        b.push(common).project(b.field("buyer"), b.field("receipts")); val counts = b.build()
        b.push(totals).push(counts).join(JoinRelType.INNER, b.equals(b.field(2, 0, 0), b.field(2, 1, 0)))
        b.project(listOf(b.field(0), b.field(1), b.field(3)), listOf("buyer", "total", "receipts")).sort(b.field("buyer"))
        val raw = b.build(); val optimized = CalciteQueryCompiler.optimize(raw, emptyList())
        assertEquals(1, aggregateNodes(optimized).size, "字段消费者不能拆散共同聚合")
        TestConnectors.open("duckdb", "jdbc:duckdb:").use { connection -> connection.createStatement().use { statement ->
            listOf(raw, optimized).forEach { plan -> statement.executeQuery(CalciteSqlRendering.render(plan, TestConnectors.registry.require(TestConnectors.ref("duckdb")).compilation).sql).use { rs ->
                assertTrue(rs.next()); assertEquals("A", rs.getString(1)); assertEquals(3, rs.getInt(2)); assertEquals(2, rs.getInt(3))
                assertTrue(rs.next()); assertEquals("B", rs.getString(1)); assertEquals(3, rs.getInt(2)); assertEquals(1, rs.getInt(3)); assertFalse(rs.next())
            } }
        } }
    }
    @Test fun executesPartitionEvidenceBeforeRegroupAndRankingWithoutLosingUnknowns() {
        NativeWarehouse(directory, "duckdb", listOf("CREATE TABLE amounts (buyer VARCHAR, amount DECIMAL(18,2))",
            "INSERT INTO amounts VALUES ('A',1.10),('A',2.20),('B',3.30),('B',NULL),('C',4.40)")).use { source ->
            val b = builder(source.adopt("amounts", emptyMap())); val partition = listOf(b.field("buyer"))
            val sum = b.sum(b.field("amount")).over().partitionBy(partition).rowsBetween(b.unboundedPreceding(), b.unboundedFollowing()).toRex()
            val known = b.aggregateCall(Ops.COUNT, listOf(b.field("amount"))).over().partitionBy(partition).rowsBetween(b.unboundedPreceding(), b.unboundedFollowing()).toRex()
            val all = b.aggregateCall(Ops.COUNT, emptyList()).over().partitionBy(partition).rowsBetween(b.unboundedPreceding(), b.unboundedFollowing()).toRex()
            val propagated = b.call(Ops.CASE, b.equals(known, all), sum, b.rexBuilder.makeNullLiteral(sum.type))
            b.project(listOf(b.field("buyer"), propagated), listOf("buyer", "propagated"), true)
            b.aggregate(b.groupKey(b.field("buyer")), b.aggregateCall(Ops.MAX, listOf(b.field("propagated"))).`as`("buyer_total"),
                b.countStar("unused_source_rows"), b.aggregateCall(Ops.MIN, listOf(b.field("propagated"))).`as`("unused_minimum"))
            val completeKnown = b.aggregateCall(Ops.COUNT, listOf(b.field("buyer_total"))).over().rowsBetween(b.unboundedPreceding(), b.unboundedFollowing()).toRex()
            val completeTotal = b.sum(b.field("buyer_total")).over().rowsBetween(b.unboundedPreceding(), b.unboundedFollowing()).toRex()
            val rank = b.aggregateCall(Ops.ROW_NUMBER, emptyList()).over().orderBy(b.field("buyer")).toRex()
            b.project(listOf(b.field("buyer"), b.field("buyer_total"), completeKnown, completeTotal, rank), listOf("buyer", "buyer_total", "complete_known", "complete_total", "position"), true)
            b.filter(b.lessThanOrEqual(b.field("position"), b.literal(2))).sort(b.field("buyer"))
            val raw = b.build(); val optimized = CalciteQueryCompiler.optimize(raw, emptyList())
            fun aggregateCalls(plan: RelNode): Int {
                var calls = 0
                object : RelVisitor() { override fun visit(node: RelNode, ordinal: Int, parent: RelNode?) {
                    if (node is Aggregate) calls += node.aggCallList.size
                    super.visit(node, ordinal, parent)
                } }.go(plan)
                return calls
            }
            assertTrue(aggregateCalls(optimized) < aggregateCalls(raw), "未使用聚合退出，窗口与未知传播保留")
            listOf(raw, optimized).forEach { plan ->
                val actual = rows(source, plan)
                assertEquals(listOf("A", "B"), actual.map { it[0] }); decimal(actual[0][1], "3.30"); assertNull(actual[1][1])
                actual.forEach { assertEquals(2L, (it[2] as Number).toLong()); decimal(it[3], "7.70") }
            }
        }
    }
    @Test fun elapsedTimeUsesCompleteUnitsBigIntegersAndThreeValuedComparisons() {
        NativeWarehouse(directory, "duckdb", listOf("CREATE TABLE elapsed (position INTEGER, started TIMESTAMP, ended TIMESTAMP)",
            """INSERT INTO elapsed VALUES
                (1,'2026-03-08 02:30:00.123456','2026-03-10 02:30:00.123456'),
                (2,'2026-03-08 02:30:00.123456','2026-03-10 02:30:00.123457'),
                (3,'2026-03-10 02:30:00.123457','2026-03-08 02:30:00.123456'),
                (4,'2026-03-08 23:59:59','2026-03-09 00:00:00'),(5,'2026-03-08 02:30:00',NULL)""")).use { source ->
            val binding = source.adopt("elapsed", mapOf("started" to "started", "ended" to "ended"))
            fun elapsed(unit: String) = SemanticExpression.Call(ScalarFunction.TIMESTAMPDIFF, listOf(SemanticExpression.Literal(unit), SemanticExpression.Reference(SemanticExpression.ReferenceKind.LOGIC_UNIT, "started", 1), SemanticExpression.Reference(SemanticExpression.ReferenceKind.LOGIC_UNIT, "ended", 1)))
            val microseconds = elapsed("MICROSECOND"); val hours = elapsed("HOUR")
            assertEquals(ScalarValueType.NUMBER, SemanticExpressionValueType.resolve(hours, { ScalarValueType.LOCAL_TIMESTAMP },
                { call -> TestConnectors.registry.require(call.connectorRef).compilation.requireNativeScalarFunction(call.function) }))
            val late = SemanticExpression.Operation(SemanticExpression.Operator.GT, listOf(microseconds, SemanticExpression.Literal(172800000000L)))
            val parameters = mutableListOf<JdbcParameter>(); val b = builder(binding); val compiler = localCompiler(b, binding, parameters)
            b.project(listOf(b.field("position"), compiler.compile(frozenExpression(microseconds)), compiler.compile(hours), compiler.compile(late)),
                listOf("position", "microseconds", "hours", "late"), true).sort(b.field("position"))
            val raw = b.build(); val optimized = CalciteQueryCompiler.optimize(raw, parameters)
            listOf(raw, optimized).forEach { plan ->
                val evidence = OlapJsonUtils.mapper().readTree(CalcitePlanEvidence.write(plan))
                val units = evidence.findValues("enumClass")
                assertFalse(units.isEmpty()); assertTrue(units.all { it.asText() == org.apache.calcite.avatica.util.TimeUnit::class.java.name })
            }
            val result = rows(source, optimized, parameters)
            assertEquals(5, result.size)
            val micros = listOf(172800000000L, 172800000001L, -172800000001L, 1000000L)
            val hourValues = listOf(48L, 48L, -48L, 0L)
            for (index in micros.indices) {
                assertEquals(micros[index], (result[index][1] as Number).toLong()); assertEquals(hourValues[index], (result[index][2] as Number).toLong()); assertEquals(index == 1, result[index][3])
            }
            assertTrue(result[4].drop(1).all { it == null })
        }
    }
    @Test fun calendarTruncationUsesCalciteValidationAndMondayWeeksOnRealWarehouse() {
        val value = SemanticExpression.Reference(SemanticExpression.ReferenceKind.LOGIC_UNIT, "value", 1)
        val week = SemanticExpression.Call(ScalarFunction.DATE_TRUNC, listOf(SemanticExpression.Literal("week"), value))
        val precise = SemanticExpression.Cast(value, SemanticExpression.ValueType.TIMESTAMP, 6, null, MetricValueContract.Rounding.NONE)
        val month = SemanticExpression.Call(ScalarFunction.DATE_TRUNC, listOf(SemanticExpression.Literal("MONTH"), precise))
        val text = SemanticExpression.Cast(value, SemanticExpression.ValueType.STRING, 64, null, MetricValueContract.Rounding.NONE)
        fun resolve(expression: SemanticExpression, type: ScalarValueType) = SemanticExpressionValueType.resolve(expression, { type },
            { call -> TestConnectors.registry.require(call.connectorRef).compilation.requireNativeScalarFunction(call.function) })
        assertEquals(ScalarValueType.LOCAL_TIMESTAMP, resolve(week, ScalarValueType.LOCAL_TIMESTAMP))
        assertEquals(ScalarValueType.LOCAL_TIMESTAMP, resolve(month, ScalarValueType.LOCAL_TIMESTAMP))
        assertEquals(ScalarValueType.TEXT, resolve(text, ScalarValueType.LOCAL_TIMESTAMP))
        assertThrows(RuntimeException::class.java) { resolve(week, ScalarValueType.NUMBER) }
        NativeWarehouse(directory, "duckdb", listOf("CREATE TABLE clocks(observed_at TIMESTAMP)",
            "INSERT INTO clocks VALUES ('2026-03-01 23:59:59.999999'),('2026-03-02 00:00:00'),(NULL)")).use { source ->
            val binding = source.adopt("clocks", mapOf("value" to "observed_at")); val b = builder(binding)
            val parameters = mutableListOf<JdbcParameter>(); val compiler = localCompiler(b, binding, parameters)
            b.project(listOf(b.field("observed_at"), compiler.compile(week), compiler.compile(month), compiler.compile(text)),
                listOf("observed_at", "week_start", "month_start", "clock_text"), true).sort(b.field("observed_at"))
            val result = rows(source, CalciteQueryCompiler.optimize(b.build(), parameters), parameters)
            assertEquals(3, result.size)
            assertEquals(LocalDateTime.parse("2026-02-23T00:00:00"), result[0][1]); assertEquals(LocalDateTime.parse("2026-03-01T00:00:00"), result[0][2]); assertEquals("2026-03-01 23:59:59.999999", result[0][3])
            assertEquals(LocalDateTime.parse("2026-03-02T00:00:00"), result[1][1]); assertEquals(LocalDateTime.parse("2026-03-01T00:00:00"), result[1][2]); assertTrue(result[2].drop(1).all { it == null })
        }
    }
    @Test fun castsEachDecimalBeforeAggregationAndPreservesNanosecondInputOnBothWarehouses() {
        for (dialect in listOf("h2", "duckdb")) NativeWarehouse(directory, dialect, listOf(
            "CREATE TABLE amounts(amount DECIMAL(18,5))", "INSERT INTO amounts VALUES (1.99996),(0.00006),(NULL)",
            "CREATE TABLE clocks(observed_at ${if (dialect == "h2") "TIMESTAMP(9)" else "TIMESTAMP_NS"})",
            "INSERT INTO clocks VALUES ('2026-03-08 02:30:00.123456789')")).use { source ->
            val amount = source.adopt("amounts", mapOf("value" to "amount"))
            val decimal = frozenExpression(SemanticExpression.Cast(SemanticExpression.Reference(SemanticExpression.ReferenceKind.LOGIC_UNIT, "value", 1),
                SemanticExpression.ValueType.DECIMAL, 18, 4, com.olap.prototype.metric.MetricValueContract.Rounding.HALF_UP))
            assertEquals(ScalarValueType.NUMBER, SemanticExpressionValueType.resolve(decimal, { ScalarValueType.NUMBER },
                { call -> TestConnectors.registry.require(call.connectorRef).compilation.requireNativeScalarFunction(call.function) }))
            val parameters = mutableListOf<JdbcParameter>(); val b = builder(amount); val compiler = localCompiler(b, amount, parameters)
            b.project(listOf(compiler.compile(decimal)), listOf("rounded"), true)
            b.aggregate(b.groupKey(), b.aggregateCall(Ops.SUM, b.field(0)).`as`("total"))
            val raw = b.build(); val optimized = CalciteQueryCompiler.optimize(raw, parameters)
            val plans = listOf(raw, optimized).map { OlapJsonUtils.mapper().readTree(CalcitePlanEvidence.write(it)).required("rels") }
            plans.forEach { plan ->
                val scales = plan.flatMap { node -> node.required("resolvedRowType").required("fields").toList() }
                    .filter { it.path("type").asText() == "DECIMAL" }.map { it.required("scale").intValue() }.toSet()
                assertTrue(scales.containsAll(listOf(5, 4)), dialect)
            }
            val sql = CalciteSqlRendering.render(optimized, TestConnectors.registry.require(source.spec.connectorRef).compilation)
            source.connection.prepareStatement(sql.sql).use { statement -> statement.executeQuery().use { rs ->
                assertTrue(rs.next()); assertEquals(BigDecimal("2.0001"), rs.getBigDecimal(1))
                val output = plans[1].last().required("resolvedRowType").required("fields")[0]
                assertEquals("DECIMAL", output.required("type").asText()); assertEquals(output.required("scale").intValue(), rs.metaData.getScale(1), dialect)
            } }
            val nullBuilder = builder(amount); val nullCompiler = localCompiler(nullBuilder, amount, parameters)
            nullBuilder.filter(nullBuilder.isNull(nullCompiler.compile(decimal))); nullBuilder.aggregate(nullBuilder.groupKey(), nullBuilder.count(false, "unknown_rows"))
            val unknown = rows(source, CalciteQueryCompiler.optimize(nullBuilder.build(), parameters), parameters).single().single() as Number
            assertEquals(1L, unknown.toLong(), dialect)
            val clock = source.adopt("clocks", mapOf("value" to "observed_at"))
            val timestamp = frozenExpression(SemanticExpression.Cast(SemanticExpression.Reference(SemanticExpression.ReferenceKind.LOGIC_UNIT, "value", 1),
                SemanticExpression.ValueType.TIMESTAMP, null, null, com.olap.prototype.metric.MetricValueContract.Rounding.NONE))
            parameters.clear()
            val request = CandidateValueCompiler.compile(clock, timestamp, 0, 10, parameters, TestConnectors.registry)
            source.connection.prepareStatement(request.sql).use { statement -> statement.executeQuery().use { rs ->
                assertTrue(rs.next())
                val jdbc = (TestConnectors.registry.require(source.spec.connectorRef).access as JdbcAccess).jdbc
                assertEquals(LocalDateTime.parse("2026-03-08T02:30:00.123456789"), jdbc.scalar(rs, 1))
            } }
        }
    }
}
