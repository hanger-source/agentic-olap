package com.olap.prototype.engine.executable

import com.olap.prototype.connector.JdbcAccess
import com.olap.prototype.connector.CompilationSupport
import com.olap.prototype.connector.NativeAggregateMappingException
import com.olap.prototype.contract.JdbcParameter
import com.olap.prototype.contract.NativeValueType
import com.olap.prototype.engine.calcite.CalciteQueryCompiler
import com.olap.prototype.engine.calcite.FrozenCatalogSchema
import com.olap.prototype.expression.*
import com.olap.prototype.integration.TestConnectors
import com.olap.prototype.metric.MetricValueContract
import com.olap.prototype.metric.MeanTarget
import com.olap.prototype.query.ResultAggregation
import com.olap.prototype.query.GroupingLevels
import com.olap.prototype.query.ScalarValueType
import org.apache.calcite.rel.RelNode
import org.apache.calcite.rel.type.DelegatingTypeSystem
import org.apache.calcite.rel.type.RelDataType
import org.apache.calcite.rel.type.RelDataTypeFactory
import org.apache.calcite.rex.RexNode
import org.apache.calcite.sql.SqlNode
import org.apache.calcite.sql.SqlDataTypeSpec
import org.apache.calcite.sql.SqlBasicTypeNameSpec
import org.apache.calcite.sql.parser.SqlParserPos
import org.apache.calcite.sql.type.SqlTypeName
import org.apache.calcite.sql.`fun`.SqlStdOperatorTable as Ops
import org.apache.calcite.tools.RelBuilder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.file.Path
import java.sql.SQLException

/** 数学输入独立决定预期，实际优化和原生数据库决定执行或失败。 */
class NumericContractExecutionTest {
    @Test fun discoveredAverageMappingDrivesNativeExecutionAndSeparateOutputRounding(@TempDir directory: Path) {
        for (dialect in listOf("h2", "duckdb")) NativeWarehouse(directory, dialect,
            listOf("CREATE TABLE numbers(n BIGINT)", "INSERT INTO numbers VALUES (0),(0),(1),(NULL)")).use { source ->
            val support = TestConnectors.registry.require(source.spec.connectorRef).compilation
            val discovered = NativeAggregateTypes.describe(support, ResultAggregation.Kind.AVG,
                NativeValueType("BIGINT", null, null, true), false) as NativeAggregateMapping.Mapped
            source.connection.createStatement().use { statement -> statement.executeQuery("SELECT AVG(n) FROM numbers").use { rows ->
                assertTrue(rows.next())
                if (dialect == "h2") {
                    assertEquals(rows.metaData.getPrecision(1), discovered.resultType.precision)
                    assertEquals(rows.metaData.getScale(1), discovered.resultType.scale)
                }
                val contract = MetricValueContract(MetricValueContract.Fixed(MetricValueContract.ONE),
                    if (dialect == "h2") MetricValueContract.Exact() else MetricValueContract.NativeFloating(),
                    if (dialect == "h2") MetricValueContract.Decimal(12, 2, MetricValueContract.Rounding.HALF_UP)
                    else MetricValueContract.BinaryFloat(64), emptyMap(), MetricValueContract.Overflow.FAIL)
                val aggregate = ResultAggregation.Average(contract, ResultAggregation.NullPolicy.IGNORE, MeanTarget.SOURCE_NATIVE)
                val b = builder(source)
                val value = SemanticNumericRex.aggregate(b, aggregate, b.field("n"))
                b.aggregate(b.groupKey(), b.aggregateCall(RelationalAggregates.aggregateFunction(aggregate, support), listOf(value)).`as`("average"))
                NativeAggregateTypes.requireAverage(b, "average", aggregate.meanTarget, contract, listOf(MetricValueContract.Exact()))
                b.project(listOf(SemanticNumericRex.aggregateOutput(b, b.field("average"), aggregate)), listOf("result"), true)
                val actual = execute(source, b.build()).single().single()
                if (dialect == "h2") assertEquals(rows.getBigDecimal(1).setScale(2, RoundingMode.HALF_UP), actual)
                else assertEquals(rows.getDouble(1), (actual as Number).toDouble())
                val invalid = builder(source)
                val rejected = if (dialect == "h2") aggregate.copy(meanTarget = MeanTarget.MATHEMATICAL)
                    else aggregate.copy(valueContract = MetricValueContract(MetricValueContract.Fixed(MetricValueContract.ONE),
                        MetricValueContract.Exact(), MetricValueContract.Decimal(12, 2, MetricValueContract.Rounding.HALF_UP), emptyMap(), MetricValueContract.Overflow.FAIL))
                val argument = SemanticNumericRex.aggregate(invalid, rejected, invalid.field("n"))
                invalid.aggregate(invalid.groupKey(), invalid.aggregateCall(RelationalAggregates.aggregateFunction(rejected, support), listOf(argument)).`as`("average"))
                val failure = assertThrows(NativeAggregateMappingException::class.java) {
                    NativeAggregateTypes.requireAverage(invalid, "average", rejected.meanTarget, rejected.valueContract, listOf(MetricValueContract.Exact()))
                }
                assertEquals(if (dialect == "h2") NativeAggregateMappingException.Reason.MATHEMATICAL_QUANTIZATION_UNPROVED
                    else NativeAggregateMappingException.Reason.INPUT_GUARANTEE_UPGRADE, failure.reason)
            } }
        }
    }

    @Test fun nativeAverageRefusalIsDiscoverableAndCompilationStillRejectsIt(@TempDir directory: Path) {
        NativeWarehouse(directory, "h2", listOf("CREATE TABLE numbers(n BIGINT)")).use { source ->
            val original = TestConnectors.registry.require(source.spec.connectorRef).compilation
            val unsupported = object : CompilationSupport by original {
                override val typeSystem = object : DelegatingTypeSystem(original.typeSystem) {
                    override fun deriveAvgAggType(factory: RelDataTypeFactory, input: RelDataType): RelDataType =
                        throw UnsupportedOperationException("private backend mapping detail")
                }
            }
            assertInstanceOf(NativeAggregateMapping.Unavailable::class.java, NativeAggregateTypes.describe(unsupported,
                ResultAggregation.Kind.AVG, NativeValueType("BIGINT", null, null, true), false))
            val b = CalciteCompilation.builder(unsupported)
            b.values(b.typeFactory.builder().add("n", org.apache.calcite.sql.type.SqlTypeName.BIGINT).build())
            assertThrows(NativeAggregateMappingException::class.java) {
                b.aggregate(b.groupKey(), b.aggregateCall(RelationalAggregates.aggregateFunction(ResultAggregation.Kind.AVG, unsupported), listOf(b.field("n"))).`as`("average"))
            }
        }
    }

    @Test fun mathematicalAverageMatchesOneFinalQuantizationForAggregatesAndWindows(@TempDir directory: Path) {
        val expected = BigDecimal.ONE.divide(BigDecimal(3), 10, RoundingMode.HALF_DOWN)
        val contract = MetricValueContract(MetricValueContract.Fixed(MetricValueContract.ONE), MetricValueContract.Exact(),
            MetricValueContract.Decimal(29, 10, MetricValueContract.Rounding.HALF_DOWN), emptyMap(), MetricValueContract.Overflow.FAIL)
        NativeWarehouse(directory, "h2", listOf("CREATE TABLE numbers(n BIGINT)",
            "INSERT INTO numbers VALUES (0),(0),(1),(NULL)")).use { source ->
            val support = TestConnectors.registry.require(source.spec.connectorRef).compilation
            val aggregation = ResultAggregation.Average(contract, ResultAggregation.NullPolicy.IGNORE, MeanTarget.MATHEMATICAL)
            for (window in listOf(false, true)) {
                val b = builder(source)
                val input = SemanticNumericRex.aggregate(b, aggregation, b.field("n"))
                val function = RelationalAggregates.aggregateFunction(aggregation, support)
                val value = if (window) {
                    val over = b.aggregateCall(function, listOf(input)).over()
                        .rowsBetween(b.unboundedPreceding(), b.unboundedFollowing()).toRex()
                    NativeAggregateTypes.requireAverage(b, over, function, aggregation.meanTarget, contract, listOf(MetricValueContract.Exact()))
                    over
                } else {
                    b.aggregate(b.groupKey(), b.aggregateCall(function, listOf(input)).`as`("average"))
                    NativeAggregateTypes.requireAverage(b, "average", aggregation.meanTarget, contract, listOf(MetricValueContract.Exact()))
                    b.field("average")
                }
                b.project(listOf(SemanticNumericRex.aggregateOutput(b, value, aggregation)), listOf("result"), true)
                assertEquals(List(if (window) 4 else 1) { listOf(expected) }, execute(source, b.build()), "window=$window")
            }
        }

        // 达到 H2 实际容量时 AVG 不再增加原生 scale；两次量化会真正改变这组人口的答案。
        NativeWarehouse(directory, "h2", listOf("CREATE TABLE numbers(n DECIMAL(100000,2))",
            "INSERT INTO numbers VALUES (0.14),(0.15),(0.15),(NULL)")).use { source ->
            val once = BigDecimal("0.44").divide(BigDecimal(3), 1, RoundingMode.HALF_UP)
            val native = source.connection.createStatement().use { statement ->
                statement.executeQuery("SELECT AVG(n) FROM numbers").use { rows -> assertTrue(rows.next()); rows.getBigDecimal(1) }
            }
            val twice = native.setScale(1, RoundingMode.HALF_UP)
            assertNotEquals(once, twice)
            val output = MetricValueContract(MetricValueContract.Fixed(MetricValueContract.ONE), MetricValueContract.Exact(),
                MetricValueContract.Decimal(4, 1, MetricValueContract.Rounding.HALF_UP), emptyMap(), MetricValueContract.Overflow.FAIL)
            val support = TestConnectors.registry.require(source.spec.connectorRef).compilation
            val b = builder(source)
            val aggregation = ResultAggregation.Average(output, ResultAggregation.NullPolicy.IGNORE, MeanTarget.MATHEMATICAL)
            b.aggregate(b.groupKey(), b.aggregateCall(RelationalAggregates.aggregateFunction(aggregation, support),
                listOf(SemanticNumericRex.aggregate(b, aggregation, b.field("n")))).`as`("average"))
            val failure = assertThrows(NativeAggregateMappingException::class.java) {
                NativeAggregateTypes.requireAverage(b, "average", aggregation.meanTarget, output, listOf(MetricValueContract.Exact()))
            }
            assertEquals(NativeAggregateMappingException.Reason.MATHEMATICAL_QUANTIZATION_UNPROVED, failure.reason)
            NativeAggregateTypes.requireAverage(b, "average", MeanTarget.SOURCE_NATIVE, output, listOf(MetricValueContract.Exact()))
            b.project(listOf(SemanticNumericRex.aggregateOutput(b, b.field("average"), aggregation)), listOf("result"), true)
            assertEquals(listOf(listOf(twice)), execute(source, b.build()))
        }
    }

    @Test fun decimalAveragePreservesNativeAndApproximateInputGuaranteesAcrossProjection(@TempDir directory: Path) {
        NativeWarehouse(directory, "h2", listOf("CREATE TABLE numbers(n BIGINT)",
            "INSERT INTO numbers VALUES (0),(0),(1),(NULL)")).use { source ->
            val guarantees = linkedMapOf<String, MetricValueContract.Arithmetic>(
                "decimal_from_float" to MetricValueContract.NativeFloating(),
                "decimal_with_error" to MetricValueContract.Approximate("-2", "2", "0.01", "0"),
                "decimal_without_error" to MetricValueContract.Approximate("-2", "2", "0", "0"))
            val declarations = guarantees.map { (name, guarantee) -> NativeScalarFunction(name,
                listOf(NativeScalarFunction.Parameter(NativeScalarFunction.ParameterKind.SCALAR, ScalarValueType.NUMBER)),
                NativeValueType("DECIMAL", 19, 0, true), guarantee, NativeScalarFunction.Stability.IMMUTABLE) }.associateBy { it.id }
            val original = TestConnectors.registry.require(source.spec.connectorRef).compilation
            val support = object : CompilationSupport by original {
                override val nativeScalarFunctions = declarations.values.toList()
                override fun requireNativeScalarFunction(id: String): NativeScalarFunction = declarations.getValue(id)
                override fun nativeScalarCall(function: String, arguments: List<CompilationSupport.NativeArgument>): SqlNode {
                    require(function in declarations)
                    val pos = SqlParserPos.ZERO
                    var value = (arguments.single() as CompilationSupport.ScalarArgument).value
                    if (function == "decimal_from_float") value = Ops.CAST.createCall(pos, value,
                        SqlDataTypeSpec(SqlBasicTypeNameSpec(SqlTypeName.DOUBLE, pos), pos))
                    return Ops.CAST.createCall(pos, value, SqlDataTypeSpec(SqlBasicTypeNameSpec(SqlTypeName.DECIMAL, 19, 0, pos), pos))
                }
            }
            fun contract(arithmetic: MetricValueContract.Arithmetic) = MetricValueContract(MetricValueContract.Fixed(MetricValueContract.ONE),
                arithmetic, MetricValueContract.Decimal(29, 10, MetricValueContract.Rounding.HALF_DOWN), emptyMap(), MetricValueContract.Overflow.FAIL)
            val expected = BigDecimal.ONE.divide(BigDecimal(3), 10, RoundingMode.HALF_DOWN)
            for ((function, guarantee) in guarantees) {
                val b = builder(source)
                val native = NativeExpressionScope(source.spec.connectorRef, support) { null }
                val raw = native.compile(b, SemanticExpression.NativeCall(source.spec.connectorRef, function,
                    listOf(SemanticExpression.ScalarArgument(SemanticExpression.Reference(SemanticExpression.ReferenceKind.LOGIC_UNIT, "n", 1))))) { b.field("n") }
                val actualGuarantees = SemanticNumericRex.inputGuarantees(raw)
                b.project(listOf(raw), listOf("value"), true)
                val exact = contract(MetricValueContract.Exact())
                val aggregation = ResultAggregation.Average(exact, ResultAggregation.NullPolicy.IGNORE, MeanTarget.MATHEMATICAL)
                b.aggregate(b.groupKey(), b.aggregateCall(RelationalAggregates.aggregateFunction(aggregation, support),
                    listOf(SemanticNumericRex.aggregate(b, aggregation, b.field("value")))).`as`("average"))
                val upgrade = assertThrows(NativeAggregateMappingException::class.java) {
                    NativeAggregateTypes.requireAverage(b, "average", aggregation.meanTarget, exact, actualGuarantees)
                }
                assertEquals(NativeAggregateMappingException.Reason.INPUT_GUARANTEE_UPGRADE, upgrade.reason, function)
                val accepted = if (function == "decimal_with_error") {
                    val propagation = assertThrows(NativeAggregateMappingException::class.java) {
                        NativeAggregateTypes.requireAverage(b, "average", aggregation.meanTarget, contract(guarantee), actualGuarantees)
                    }
                    assertEquals(NativeAggregateMappingException.Reason.APPROXIMATION_PROPAGATION_UNAVAILABLE, propagation.reason)
                    contract(MetricValueContract.NativeFloating())
                } else contract(guarantee)
                NativeAggregateTypes.requireAverage(b, "average", aggregation.meanTarget, accepted, actualGuarantees)
                b.project(listOf(SemanticNumericRex.output(b, b.field("average"), accepted)), listOf("result"), true)
                assertEquals(listOf(listOf(expected)), execute(source, b.build()), function)
            }
        }
    }

    private fun quantity(minimum: String, maximum: String, bits: Int) = MetricValueContract(
        MetricValueContract.Fixed(MetricValueContract.ONE), MetricValueContract.Approximate(minimum, maximum, "1E-320", "1E-12"),
        MetricValueContract.BinaryFloat(bits), emptyMap(), MetricValueContract.Overflow.FAIL)
    private fun builder(warehouse: NativeWarehouse): RelBuilder {
        val binding = warehouse.adopt("numbers", emptyMap())
        return CalciteQueryCompiler.inputs(listOf(binding), binding.physicalColumnBindings.keys, emptySet(), TestConnectors.registry).builder
            .scan(FrozenCatalogSchema.path(binding))
    }
    private fun execute(warehouse: NativeWarehouse, relation: RelNode, parameters: List<JdbcParameter> = emptyList()): List<List<Any?>> {
        val connector = TestConnectors.registry.require(warehouse.spec.connectorRef)
        val sql = CalciteSqlRendering.render(CalciteQueryCompiler.optimize(relation, parameters), connector.compilation, parameters)
        val ordered = CalciteSqlRendering.orderedParameters(sql, parameters)
        return warehouse.connection.prepareStatement(sql.sql).use { query ->
            ordered.forEachIndexed { index, parameter -> parameter.bind(query, index + 1) }
            query.executeQuery().use { rows ->
                buildList { while (rows.next()) add((1..rows.metaData.columnCount).map { (connector.access as JdbcAccess).jdbc.scalar(rows, it) }) }
            }
        }
    }

    @Test fun executesEveryExplicitGroupingSetWithItsOwnLevelAndAmount(@TempDir directory: Path) {
        NativeWarehouse(directory, "duckdb", listOf(
            "CREATE TABLE numbers(d0 VARCHAR,d1 VARCHAR,d2 VARCHAR,d3 VARCHAR,d4 VARCHAR,d5 VARCHAR,n DECIMAL(18,2))",
            "INSERT INTO numbers VALUES ('a','b','c','d','e','f',11.25)"
        )).use { source ->
            val dimensions = (0..5).map { "d$it" }
            val levels = GroupingLevels("level", (0L..63L).map { mask ->
                GroupingLevels.Level("level_$mask", dimensions.filterIndexed { index, _ -> mask and (1L shl (5 - index)) == 0L })
            }).bind(dimensions)
            val b = builder(source)
            b.aggregate(RelationalAggregates.groupKey(b, dimensions, levels), b.sum(false, "total", b.field("n")),
                b.aggregateCall(Ops.GROUPING, dimensions.map(b::field)).`as`("level"))
            val rows = execute(source, b.build())
            assertEquals(64, rows.size)
            assertEquals((0L..63L).toSet(), rows.map { (it.last() as Number).toLong() }.toSet())
            rows.forEach { row ->
                val mask = (row.last() as Number).toLong()
                assertEquals(listOf("a", "b", "c", "d", "e", "f").mapIndexed { index, value ->
                    if (mask and (1L shl (5 - index)) == 0L) value else null
                }, row.take(6), "level=$mask")
                assertEquals(0, BigDecimal("11.25").compareTo(BigDecimal(row[6].toString())), "level=$mask")
            }
        }
    }
    @Test fun rejectsIntermediateOverflowBeforeAReciprocalMasksIt(@TempDir directory: Path) {
        for (dialect in listOf("h2", "duckdb")) NativeWarehouse(directory, dialect,
            listOf("CREATE TABLE numbers(n DOUBLE)", "INSERT INTO numbers VALUES (1E308)")).use { source ->
            val b = builder(source)
            val product = b.call(Ops.MULTIPLY, b.field("n"), b.literal(2))
            val reciprocal = b.call(Ops.DIVIDE, b.literal(1), product)
            b.project(listOf(SemanticNumericRex.output(b, reciprocal, quantity("-1E308", "1E308", 64))), listOf("result"), true)
            assertThrows(SQLException::class.java, { execute(source, b.build()) }, dialect)
        }
    }
    @Test fun checksDeclaredIntermediateDomainsWithoutConstrainingUndeclaredProducts(@TempDir directory: Path) {
        for (dialect in listOf("h2", "duckdb")) NativeWarehouse(directory, dialect,
            listOf("CREATE TABLE numbers(n DOUBLE)", "INSERT INTO numbers VALUES (1000)")).use { source ->
            val b = builder(source)
            val product = b.call(Ops.MULTIPLY, b.field("n"), b.field("n"))
            val ratio = b.call(Ops.DIVIDE, product, b.literal(1_000_000))
            b.project(listOf(SemanticNumericRex.output(b, ratio, quantity("0", "2", 64))), listOf("result"), true)
            assertEquals(1.0, (execute(source, b.build()).first().first() as Number).toDouble())
            val invalid = builder(source)
            val restrictedProduct = SemanticNumericRex.output(invalid, invalid.call(Ops.MULTIPLY, invalid.field("n"), invalid.field("n")), quantity("0", "2", 64))
            invalid.project(listOf(SemanticNumericRex.output(invalid, invalid.call(Ops.DIVIDE, restrictedProduct, invalid.literal(1_000_000)), quantity("0", "2", 64))), listOf("result"), true)
            assertThrows(SQLException::class.java, { execute(source, invalid.build()) }, dialect)
        }
    }
    @Test fun validatesFloat32AfterConversionAndPreservesNull(@TempDir directory: Path) {
        for (dialect in listOf("h2", "duckdb")) NativeWarehouse(directory, dialect, listOf("CREATE TABLE numbers(n DOUBLE)",
            "INSERT INTO numbers VALUES (0.1),(NULL),(3.4028234663852886E38),(1E308)")).use { source ->
            val b = builder(source)
            b.filter(b.or(b.isNull(b.field("n")), b.lessThan(b.field("n"), b.literal(1))))
            b.project(listOf(SemanticNumericRex.output(b, b.field("n"), quantity("0.1", "0.1", 32))), listOf("result"), true)
            val rows = execute(source, b.build())
            assertEquals(2, rows.size); assertEquals(1, rows.count { it.first() == null })
            val value = rows.mapNotNull { it.first() }.single()
            assertInstanceOf(Float::class.javaObjectType, value); assertEquals("0.1", value.toString())
            val maximum = builder(source)
            maximum.filter(maximum.and(maximum.greaterThan(maximum.field("n"), maximum.rexBuilder.makeApproxLiteral(BigDecimal.ONE)),
                maximum.lessThan(maximum.field("n"), maximum.rexBuilder.makeApproxLiteral(BigDecimal("1E100")))))
            maximum.project(listOf(SemanticNumericRex.output(maximum, maximum.field("n"), quantity("-1E308", "1E308", 32))), listOf("result"), true)
            assertEquals(Float.MAX_VALUE, execute(source, maximum.build()).first().first())
            val overflow = builder(source)
            overflow.filter(overflow.greaterThan(overflow.field("n"), overflow.rexBuilder.makeApproxLiteral(BigDecimal("1E100"))))
            overflow.project(listOf(SemanticNumericRex.output(overflow, overflow.field("n"), quantity("-1E308", "1E308", 32))), listOf("result"), true)
            assertThrows(SQLException::class.java) { execute(source, overflow.build()) }
        }
    }
    @Test fun distinguishesOrdinaryAndSafeDivisionAtTheSourceEmitter(@TempDir directory: Path) {
        for (dialect in listOf("h2", "duckdb")) NativeWarehouse(directory, dialect,
            listOf("CREATE TABLE numbers(n DOUBLE)", "INSERT INTO numbers VALUES (0),(NULL),(2)")).use { source ->
            for (operation in listOf(SemanticExpression.Operator.DIVIDE, SemanticExpression.Operator.SAFE_DIVIDE)) {
                val b = builder(source)
                val parameters = mutableListOf<JdbcParameter>()
                val expression = SemanticExpression.Operation(operation, listOf(SemanticExpression.Literal(BigDecimal.ONE),
                    SemanticExpression.Reference(SemanticExpression.ReferenceKind.LOGIC_UNIT, "n", 1)))
                val value = SemanticCalciteRexCompiler(b, parameters) { _, _ -> throw IllegalArgumentException("numeric fixture does not authorize native source calls") }
                    .compile(expression, { b.field("n") })
                b.project(listOf(SemanticNumericRex.output(b, value, quantity("-10", "10", 64))), listOf("result"), true)
                val relation = b.build()
                if (operation == SemanticExpression.Operator.DIVIDE) assertThrows(SQLException::class.java, { execute(source, relation, parameters) }, dialect)
                else {
                    val rows = execute(source, relation, parameters)
                    assertEquals(3, rows.size); assertEquals(2, rows.count { it.first() == null })
                    assertEquals(0.5, (rows.mapNotNull { it.first() }.single() as Number).toDouble())
                }
            }
        }
    }
    @Test fun rejectsOverflowInsideAggregateInputAndAggregateOutput(@TempDir directory: Path) {
        NativeWarehouse(directory, "duckdb", listOf("CREATE TABLE numbers(n DOUBLE)", "INSERT INTO numbers VALUES (1E308),(1E308)")).use { source ->
            for (inFormula in listOf(true, false)) {
                val b = builder(source)
                val contract = quantity("-1E308", "1E308", 64)
                var input: RexNode = b.field("n")
                if (inFormula) input = b.call(Ops.DIVIDE, b.literal(1), b.call(Ops.MULTIPLY, input, b.literal(2)))
                input = SemanticNumericRex.aggregate(b, ResultAggregation.Sum(contract, ResultAggregation.NullPolicy.IGNORE), input)
                b.aggregate(b.groupKey(), b.sum(false, "total", input))
                val total = SemanticNumericRex.output(b, b.field("total"), contract)
                b.project(listOf(SemanticNumericRex.output(b, b.call(Ops.DIVIDE, b.literal(1), total), contract)), listOf("result"), true)
                assertThrows(SQLException::class.java) { execute(source, b.build()) }
            }
        }
    }
}
