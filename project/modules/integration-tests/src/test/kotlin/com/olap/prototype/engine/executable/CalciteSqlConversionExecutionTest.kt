package com.olap.prototype.engine.executable

import com.olap.prototype.expression.ScalarFunction

import com.olap.prototype.connector.JdbcAccess
import com.olap.prototype.connector.AggregateSql
import com.olap.prototype.connector.CompilationSupport
import com.olap.prototype.connector.FiniteDecimalTypes
import com.olap.prototype.connector.NativeCompilationCapabilityException
import com.olap.prototype.contract.JdbcParameter
import com.olap.prototype.contract.NativeTableDependency
import com.olap.prototype.contract.NativeValueType
import com.olap.prototype.engine.calcite.CalciteQueryCompiler
import com.olap.prototype.engine.calcite.FrozenCatalogSchema
import com.olap.prototype.engine.calcite.SemanticRexCompiler
import com.olap.prototype.engine.catalog.ExecutablePhysicalExecutionBinding
import com.olap.prototype.engine.catalog.ExecutableRelationPosition.Id
import com.olap.prototype.expression.CalciteSqlRendering
import com.olap.prototype.expression.CalciteCompilation
import com.olap.prototype.expression.CompletedRelationCompiler
import com.olap.prototype.expression.NativeExpressionScope
import com.olap.prototype.expression.NativeScalarFunction
import com.olap.prototype.expression.SemanticExpression
import com.olap.prototype.expression.SemanticNumericRex
import com.olap.prototype.integration.TestConnectors
import com.olap.prototype.metric.MetricValueContract
import com.olap.prototype.query.ScalarValueType
import com.olap.prototype.query.PostOperationContract
import com.olap.prototype.query.QueryQuantity
import com.olap.prototype.query.QueryStageAnalysis
import com.olap.prototype.query.ResultExpression
import org.apache.calcite.avatica.util.TimeUnit
import org.apache.calcite.rel.RelNode
import org.apache.calcite.rel.core.JoinRelType
import org.apache.calcite.rel.core.Aggregate
import org.apache.calcite.rel.core.AggregateCall
import org.apache.calcite.rel.logical.LogicalCalc
import org.apache.calcite.rel.type.DelegatingTypeSystem
import org.apache.calcite.rel.type.RelDataType
import org.apache.calcite.rel.type.RelDataTypeFactory
import org.apache.calcite.rel.type.RelDataTypeSystem
import org.apache.calcite.rex.RexNode
import org.apache.calcite.rex.RexBuilder
import org.apache.calcite.rex.RexCall
import org.apache.calcite.rex.RexLiteral
import org.apache.calcite.rex.RexProgram
import org.apache.calcite.rex.RexCorrelVariable
import org.apache.calcite.rex.RexSubQuery
import org.apache.calcite.sql.*
import org.apache.calcite.sql.`fun`.SqlStdOperatorTable as Ops
import org.apache.calcite.sql.parser.SqlParserPos
import org.apache.calcite.sql.parser.SqlParser
import org.apache.calcite.sql.util.SqlBasicVisitor
import org.apache.calcite.sql.util.SqlString
import org.apache.calcite.sql.`fun`.SqlCase
import org.apache.calcite.sql.`fun`.SqlBasicAggFunction
import org.apache.calcite.sql.`fun`.SqlTrimFunction
import org.apache.calcite.sql.dialect.H2SqlDialect
import org.apache.calcite.sql.type.OperandTypes
import org.apache.calcite.sql.type.ReturnTypes
import org.apache.calcite.sql.type.SqlTypeName
import org.apache.calcite.sql.type.SqlTypeUtil
import org.apache.calcite.tools.RelBuilder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path
import java.sql.SQLException
import java.sql.Types
import java.time.LocalDateTime
import java.util.Locale

/** 真实SQL区分作用域、人口、完成阶段、非确定观察和技术容量。 */
class CalciteSqlConversionExecutionTest {
    private fun source(directory: Path, dialect: String) = NativeWarehouse(directory, dialect, listOf(
        "CREATE TABLE observations(id INTEGER,region VARCHAR(8),amount DECIMAL(18,2))",
        "INSERT INTO observations VALUES (1,'A',1),(2,'A',2),(3,'B',NULL),(4,'C',10)"))
    private fun builder(source: NativeWarehouse, binding: ExecutablePhysicalExecutionBinding = source.adopt("observations", emptyMap())): RelBuilder =
        CalciteQueryCompiler.inputs(listOf(binding), binding.physicalColumnBindings.keys, emptySet(), TestConnectors.registry).builder.scan(FrozenCatalogSchema.path(binding))
    private fun rows(source: NativeWarehouse, plan: RelNode, parameters: List<JdbcParameter> = emptyList(),
        support: CompilationSupport = TestConnectors.registry.require(source.spec.connectorRef).compilation): List<List<Any?>> =
        rows(source, CalciteSqlRendering.render(plan, support, parameters), parameters)
    private fun rows(source: NativeWarehouse, sql: SqlString, parameters: List<JdbcParameter> = emptyList()): List<List<Any?>> {
        val connector = TestConnectors.registry.require(source.spec.connectorRef)
        val ordered = CalciteSqlRendering.orderedParameters(sql, parameters)
        return source.connection.prepareStatement(sql.sql).use { statement ->
            ordered.forEachIndexed { index, parameter -> parameter.bind(statement, index + 1) }
            statement.executeQuery().use { rs -> buildList {
                while (rs.next()) add((1..rs.metaData.columnCount).map { (connector.access as JdbcAccess).jdbc.scalar(rs, it) })
            } }
        }
    }
    private fun decimal(value: Any?, expected: String) = assertEquals(0, BigDecimal(expected).compareTo(BigDecimal(requireNotNull(value).toString())))
    private fun compiler(b: RelBuilder, binding: ExecutablePhysicalExecutionBinding, parameters: MutableList<JdbcParameter>): SemanticRexCompiler {
        val position = Id.backbone()
        return SemanticRexCompiler(b, position, mapOf(position to binding), { _, name -> b.field(name) }, parameters,
            { _, _ -> throw IllegalArgumentException("scalar trial has no relation quantifiers") }, linkedSetOf())
    }
    @Test fun finiteWindowBoundsUsePreviousObservation(@TempDir directory: Path) {
        for (dialect in listOf("h2", "duckdb")) source(directory, dialect).use { source ->
            val b = builder(source)
            val rolling = b.sum(b.field("amount")).over().orderBy(b.field("id")).rowsBetween(b.preceding(b.literal(1)), b.currentRow()).toRex()
            b.project(listOf(b.field("id"), rolling), listOf("id", "rolling"), true).sort(b.field("id"))
            val result = rows(source, CalciteQueryCompiler.optimize(b.build(), emptyList()))
            assertEquals(4, result.size)
            listOf("1", "3", "2", "10").forEachIndexed { index, expected -> decimal(result[index][1], expected) }
        }
    }
    @Test fun calendarRangeBoundsUseTheDeclaredInterval(@TempDir directory: Path) {
        for (dialect in listOf("h2", "duckdb")) source(directory, dialect).use { source ->
            source.connection.createStatement().use { statement ->
                statement.execute("ALTER TABLE observations ADD range_at TIMESTAMP(6)")
                statement.execute("UPDATE observations SET range_at=CASE id WHEN 1 THEN TIMESTAMP '2026-01-01 00:00:00' WHEN 2 THEN TIMESTAMP '2026-01-02 00:00:00' WHEN 3 THEN TIMESTAMP '2026-01-04 00:00:00' ELSE TIMESTAMP '2026-01-05 00:00:00' END")
            }
            val b = builder(source)
            val interval = b.rexBuilder.makeIntervalLiteral(BigDecimal("86400000"), SqlIntervalQualifier(TimeUnit.DAY, 10, null, 6, SqlParserPos.ZERO))
            val rolling = b.sum(b.field("amount")).over().orderBy(b.field("range_at")).rangeBetween(b.preceding(interval), b.currentRow()).toRex()
            b.project(listOf(b.field("id"), rolling), listOf("id", "rolling"), true).sort(b.field("id"))
            val result = rows(source, CalciteQueryCompiler.optimize(b.build(), emptyList()))
            assertEquals(4, result.size); decimal(result[0][1], "1"); decimal(result[1][1], "3"); assertNull(result[2][1]); decimal(result[3][1], "10")
        }
    }
    @Test fun emptyValuesPreserveDeclaredDecimalAndTimestampTypes(@TempDir directory: Path) {
        for (dialect in listOf("h2", "duckdb")) source(directory, dialect).use { source ->
            val b = builder(source); b.build()
            val factory = b.typeFactory
            val type = factory.builder().add("amount", factory.createTypeWithNullability(factory.createSqlType(SqlTypeName.DECIMAL, 18, 2), true))
                .add("at", factory.createTypeWithNullability(factory.createSqlType(SqlTypeName.TIMESTAMP, 6), true)).build()
            val sql = CalciteSqlRendering.render(b.values(type).build(), TestConnectors.registry.require(TestConnectors.ref(dialect)).compilation)
            source.connection.createStatement().use { statement -> statement.executeQuery(sql.sql).use { rs ->
                assertFalse(rs.next()); assertTrue(rs.metaData.getColumnType(1) in setOf(Types.DECIMAL, Types.NUMERIC))
                assertEquals(18, rs.metaData.getPrecision(1)); assertEquals(2, rs.metaData.getScale(1)); assertEquals(Types.TIMESTAMP, rs.metaData.getColumnType(2))
            } }
        }
    }
    @Test fun parameterizedGroupingHasOneBoundExpressionIdentity(@TempDir directory: Path) {
        for (dialect in listOf("h2", "duckdb")) source(directory, dialect).use { source ->
            val b = builder(source)
            val prefix = b.rexBuilder.makeDynamicParam(b.typeFactory.createSqlType(SqlTypeName.VARCHAR, 8), 0)
            b.project(listOf(b.call(Ops.CONCAT, prefix, b.field("region")), b.field("amount")), listOf("cohort", "amount"), true)
            b.aggregate(b.groupKey(b.field("cohort")), b.sum(false, "total", b.field("amount"))).sort(b.field("cohort"))
            val result = rows(source, b.build(), listOf(JdbcParameter.of(Types.VARCHAR, "VARCHAR", 8, 0, "group-")))
            assertEquals(listOf("group-A", "group-B", "group-C"), result.map { it[0] })
            decimal(result[0][1], "3"); assertNull(result[1][1]); decimal(result[2][1], "10")
        }
    }
    @Test fun frozenColumnSubsetAndOrderSurviveSharedWideTableConsumers(@TempDir directory: Path) {
        for (dialect in listOf("h2", "duckdb")) NativeWarehouse(directory, dialect, listOf(
            "CREATE TABLE wide_observations(extra_key INTEGER,extra_amount INTEGER,keep_amount INTEGER,keep_key INTEGER,keep_gate INTEGER,extra_tail INTEGER)",
            "INSERT INTO wide_observations VALUES (900,800,10,2,1,700),(901,801,20,2,1,701),(902,802,NULL,4,1,702),(903,803,7,4,0,703),(904,804,5,NULL,1,704),(905,805,100,6,1,705)"
        )).use { source ->
            val adopted = source.adopt("wide_observations", emptyMap())
            val selected = listOf("keep_gate", "keep_key", "keep_amount").associateTo(linkedMapOf()) { name ->
                adopted.physicalColumnBindings.entries.single { it.value == name }.toPair()
            }
            // 保留真实宽表合同及采用，只让本次精确绑定读取这三列，顺序也不同于物理表。
            val binding = adopted.copy(physicalColumnBindings = selected,
                physicalColumnTypes = selected.keys.associateWith { adopted.physicalColumnTypes.getValue(it) })
            val b = builder(source, binding)
            b.filter(b.equals(b.field("keep_gate"), b.literal(1)))
            val gated = b.build()
            b.push(gated).project(listOf(b.field("keep_key"), b.field("keep_amount"), b.field("keep_gate")),
                listOf("keep_key", "keep_amount", "keep_gate"), true)
                .filter(b.or(b.isNull(b.field("keep_amount")), b.lessThan(b.field("keep_amount"), b.literal(50))))
            val shared = b.build()
            b.push(shared).aggregate(b.groupKey(b.field("keep_key")), b.sum(false, "total", b.field("keep_amount")))
            b.project(listOf(b.field("keep_key"), b.field("total")), listOf("amount_key", "total"), true)
            val amounts = b.build()
            b.push(shared).aggregate(b.groupKey(b.field("keep_key")),
                b.count(false, "source_rows"), b.count(false, "known_amounts", b.field("keep_amount")))
            b.project(listOf(b.field("keep_key"), b.field("source_rows"), b.field("known_amounts")),
                listOf("count_key", "source_rows", "known_amounts"), true)
            val counts = b.build()
            b.push(amounts).push(counts).join(JoinRelType.INNER,
                b.call(Ops.IS_NOT_DISTINCT_FROM, b.field(2, 0, "amount_key"), b.field(2, 1, "count_key")))
            b.project(listOf(b.field("amount_key"), b.field("total"), b.field("source_rows"), b.field("known_amounts")),
                listOf("key", "total", "source_rows", "known_amounts"), true)
                .sort(b.call(Ops.COALESCE, b.field("key"), b.literal(-1)))
            val result = rows(source, b.build())
            assertEquals(3, result.size)
            assertNull(result[0][0]); decimal(result[0][1], "5"); decimal(result[0][2], "1"); decimal(result[0][3], "1")
            decimal(result[1][0], "2"); decimal(result[1][1], "30"); decimal(result[1][2], "2"); decimal(result[1][3], "2")
            decimal(result[2][0], "4"); assertNull(result[2][1]); decimal(result[2][2], "1"); decimal(result[2][3], "0")

            // 不读取业务列的扫描仍表示真实行袋；COUNT(*)不能因零列投影失去或合并行。
            val zeroColumns = builder(source, adopted.copy(physicalColumnBindings = emptyMap(), physicalColumnTypes = emptyMap()))
            zeroColumns.aggregate(zeroColumns.groupKey(), zeroColumns.count(false, "source_rows"))
            decimal(rows(source, zeroColumns.build()).single().single(), "6")
        }
    }
    @Test fun semiAndAntiDoNotCaptureProjectedLeftFieldsInTheRightScope(@TempDir directory: Path) {
        for (dialect in listOf("h2", "duckdb")) source(directory, dialect).use { source ->
            source.connection.createStatement().use { it.execute("DELETE FROM observations"); it.execute("INSERT INTO observations VALUES (0,'左''🧭',1),(1,'左''🧭',2),(1,'左''🧭',3),(NULL,'左''🧭',4),(2,'右''🧭',9)") }
            for (join in listOf(JoinRelType.SEMI, JoinRelType.ANTI)) {
                val b = builder(source)
                b.filter(b.equals(b.field("region"), b.literal("左'🧭")))
                b.project(listOf(b.call(Ops.PLUS, b.field("id"), b.literal(1)), b.field("amount"), b.field("region")), listOf("id", "amount", "region"), true)
                b.scan(FrozenCatalogSchema.path(source.adopt("observations", emptyMap())))
                b.filter(b.equals(b.field("region"), b.literal("右'🧭"))).project(listOf(b.field("id")), listOf("id"), true)
                b.join(join, b.equals(b.field(2, 0, "id"), b.field(2, 1, "id"))).sort(b.field("amount"))
                val result = rows(source, b.build())
                assertEquals(2, result.size)
                assertEquals(listOf("左'🧭", "左'🧭"), result.map { it[2] })
                if (join == JoinRelType.SEMI) { decimal(result[0][1], "2"); decimal(result[1][1], "3") }
                else { decimal(result[0][1], "1"); decimal(result[1][1], "4"); assertNull(result[1][0]) }
            }
        }
    }
    @Test fun eachConsumerFiltersItsSharedAggregatePopulation(@TempDir directory: Path) {
        for (dialect in listOf("h2", "duckdb")) source(directory, dialect).use { source ->
            val b = builder(source)
            b.aggregate(b.groupKey(b.field("region")), b.sum(false, "total", b.field("amount")))
            val complete = b.build()
            b.push(complete).filter(b.isNotNull(b.field("total"))); val observed = b.build()
            b.push(complete).filter(b.greaterThan(b.field("total"), b.literal(5))); val large = b.build()
            b.push(observed).push(large).union(true).sort(b.field("region"))
            val result = rows(source, b.build())
            assertEquals(listOf("A", "C", "C"), result.map { it[0] })
            listOf("3", "10", "10").forEachIndexed { index, value -> decimal(result[index][1], value) }
        }
    }
    @Test fun typedAggregateSqlGuardsOnlyItsActualFiniteInputAcrossSharedConsumers(@TempDir directory: Path) {
        source(directory, "h2").use { source ->
            source.connection.createStatement().use {
                it.execute("ALTER TABLE observations ADD finite_amount DECFLOAT(100)")
                it.execute("UPDATE observations SET finite_amount=amount")
                it.execute("UPDATE observations SET finite_amount=1.00000000000000000001 WHERE id=1")
                it.execute("UPDATE observations SET amount=amount*1000")
            }
            val baseSupport = TestConnectors.registry.require(source.spec.connectorRef).compilation
            // 这里只执行受守卫的有限样本，不声明H2的全域Finite SUM具有ExactOrFail保证。
            val sum = SqlBasicAggFunction.create(SqlKind.SUM, { binding ->
                if (FiniteDecimalTypes.isFinite(binding.getOperandType(0))) FiniteDecimalTypes.create(true)
                else baseSupport.nativeAggregate(SqlKind.SUM).inferReturnType(binding)
            }, OperandTypes.NUMERIC)
            val support = object : CompilationSupport by baseSupport {
                override val typeSystem = object : DelegatingTypeSystem(baseSupport.typeSystem) {
                    override fun deriveDecimalMultiplyType(factory: RelDataTypeFactory, left: RelDataType, right: RelDataType): RelDataType? {
                        if (FiniteDecimalTypes.isFinite(left) || FiniteDecimalTypes.isFinite(right)) {
                            require(SqlTypeUtil.isExactNumeric(left) && SqlTypeUtil.isExactNumeric(right))
                            return FiniteDecimalTypes.create(left.isNullable || right.isNullable)
                        }
                        return baseSupport.typeSystem.deriveDecimalMultiplyType(factory, left, right)
                    }
                }
                override val dialect: SqlDialect = object : H2SqlDialect(H2SqlDialect.DEFAULT_CONTEXT.withDataTypeSystem(typeSystem)) {
                    override fun prepareUnparse(node: RexNode): RexNode {
                        if (node is RexCall && node.kind == SqlKind.TIMES && node.operands.any { FiniteDecimalTypes.isFinite(it.type) }) {
                            val identity = node.operands.singleOrNull { !FiniteDecimalTypes.isFinite(it.type) }
                            require(identity is RexLiteral &&
                                BigDecimal(identity.value3.toString()).compareTo(BigDecimal.ONE) == 0) {
                                "finite multiplication requires a known exact literal-one identity"
                            }
                            return node.operands.single { FiniteDecimalTypes.isFinite(it.type) }
                        }
                        return baseSupport.dialect.prepareUnparse(node)
                    }
                    override fun getCastSpec(type: RelDataType): SqlNode? = baseSupport.dialect.getCastSpec(type)
                    override fun quoteStringLiteral(builder: StringBuilder, charsetName: String?, value: String) =
                        baseSupport.dialect.quoteStringLiteral(builder, charsetName, value)
                }
                override fun nativeAggregate(kind: SqlKind): SqlAggFunction = if (kind == SqlKind.SUM) sum else baseSupport.nativeAggregate(kind)
                override fun aggregateSql(owner: Aggregate, call: AggregateCall, arguments: List<SqlNode>, expression: SqlNode): AggregateSql {
                    if (call.aggregation.kind != SqlKind.SUM ||
                        !FiniteDecimalTypes.isFinite(owner.input.rowType.fieldList[call.argList.single()].type)) return AggregateSql.Direct(expression)
                    require(FiniteDecimalTypes.isFinite(call.type) && arguments.size == 1 && !call.isDistinct &&
                        !call.hasFilter() && call.collation.fieldCollations.isEmpty())
                    val pos = SqlParserPos.ZERO
                    val largest = Ops.MAX.createCall(pos, Ops.ABS.createCall(pos, arguments.single()))
                    val accepted = Ops.OR.createCall(pos, Ops.IS_NULL.createCall(pos, largest),
                        Ops.LESS_THAN_OR_EQUAL.createCall(pos, largest, SqlLiteral.createExactNumeric("100", pos)))
                    val text = SqlDataTypeSpec(SqlBasicTypeNameSpec(SqlTypeName.VARCHAR, 128, pos), pos)
                    val decimal = SqlDataTypeSpec(SqlBasicTypeNameSpec(SqlTypeName.DECIMAL, 18, 2, pos), pos)
                    val rejected = Ops.CAST.createCall(pos, Ops.CONCAT.createCall(pos,
                        SqlLiteral.createCharString("finite aggregate input outside trial domain:", pos),
                        Ops.CAST.createCall(pos, largest, text)), decimal)
                    return AggregateSql.Direct(SqlCase(pos, null, SqlNodeList(listOf(accepted), pos), SqlNodeList(listOf(expression), pos), rejected))
                }
            }
            val binding = source.adopt("observations", emptyMap())
            val schema = FrozenCatalogSchema.bind(listOf(binding), binding.physicalColumnBindings.keys, emptySet(), TestConnectors.registry).schema
            val b = CalciteCompilation.builder(support, schema).scan(FrozenCatalogSchema.path(binding))
            val input = b.build()
            val ledgerUnit = MetricValueContract.UnitIdentity("trial", "ledger", 1)
            val sourceQuantity = MetricValueContract(MetricValueContract.Fixed(MetricValueContract.ONE), MetricValueContract.Exact(),
                MetricValueContract.FiniteDecimal, emptyMap(), MetricValueContract.Overflow.FAIL)
            val literalQuantity = MetricValueContract(MetricValueContract.Fixed(ledgerUnit), MetricValueContract.Exact(),
                MetricValueContract.Integer(32), emptyMap(), MetricValueContract.Overflow.FAIL)
            val assignedQuantity = MetricValueContract(MetricValueContract.Fixed(ledgerUnit), MetricValueContract.Exact(),
                MetricValueContract.FiniteDecimal, mapOf("assign_ledger" to MetricValueContract.FixedBinary(
                    MetricValueContract.BinaryOperator.MULTIPLY, MetricValueContract.ONE, ledgerUnit, ledgerUnit)), MetricValueContract.Overflow.FAIL)
            fun assign(value: BigDecimal): RelNode {
                b.push(input)
                val scope = QueryStageAnalysis.ScopeId.root()
                val columns = input.rowType.fieldNames.mapIndexed { index, name ->
                    val origin = QueryStageAnalysis.OperationOrigin(scope, 0, index, PostOperationContract.PROJECT)
                    if (name == "finite_amount") QueryStageAnalysis.SourceColumn.quantity(name, origin, emptyList(),
                        QueryQuantity.Source.fixed(sourceQuantity, QueryQuantity.RawSource(listOf("id"))))
                    else QueryStageAnalysis.SourceColumn.categorical(name, origin)
                }
                val operation = PostOperationContract.Derive(ResultExpression.ResultNumeric(
                    ResultExpression.ResultOperation(ResultExpression.ResultOperator.MULTIPLY,
                        listOf(ResultExpression.ResultSymbol("finite_amount"), ResultExpression.ResultQuantityLiteral(value, literalQuantity))),
                    assignedQuantity, "assign_ledger"), "assigned_amount", "ledger amount")
                val analysis = QueryStageAnalysis.analyze(scope, columns, QueryStageAnalysis.UnknownSourceKey.INSTANCE, emptyMap(),
                    listOf(operation), QueryStageAnalysis.IntrinsicPopulation(emptySet()), "explicit ledger assignment")
                assertEquals(QueryQuantity.Fixed(ledgerUnit), analysis.outputs.last().quantity?.unit)
                CompletedRelationCompiler.compile(b, analysis, linkedMapOf(), mutableListOf(), emptyMap(), mutableListOf(),
                    NativeExpressionScope(source.spec.connectorRef, support) { null })
                return b.build()
            }
            b.push(assign(BigDecimal.ONE)).project(listOf(b.field("region"), b.field("assigned_amount"), b.field("amount"),
                b.cast(b.field("amount"), SqlTypeName.REAL)), listOf("region", "finite_amount", "amount", "floating_amount"), true)
            b.aggregate(b.groupKey(b.field("region")),
                b.aggregateCall(support.nativeAggregate(SqlKind.SUM), listOf(b.field("finite_amount"))).`as`("total"),
                b.aggregateCall(support.nativeAggregate(SqlKind.SUM), listOf(b.field("amount"))).`as`("fixed_total"),
                b.aggregateCall(support.nativeAggregate(SqlKind.SUM), listOf(b.field("floating_amount"))).`as`("floating_total"))
            val completed = b.build()
            b.push(completed).filter(b.isNotNull(b.field("total"))); val known = b.build()
            b.push(completed).filter(b.isNull(b.field("total"))); val missing = b.build()
            b.push(known).push(missing).union(true).sort(b.field("region"))
            val shared = b.build()
            val result = rows(source, shared, support = support)
            assertEquals(listOf("A", "B", "C"), result.map { it[0] })
            val expected = listOf(listOf("3.00000000000000000001", "3000", "3000"),
                listOf(null, null, null), listOf("10", "10000", "10000"))
            expected.forEachIndexed { row, values -> values.forEachIndexed { column, value ->
                if (value == null) assertNull(result[row][column + 1]) else decimal(result[row][column + 1], value)
            } }
            val unsupported = assertThrows(IllegalArgumentException::class.java) { CalciteSqlRendering.compile(assign(BigDecimal("2")), support) }
            assertTrue(unsupported.message.orEmpty().contains("known exact literal-one identity"))
            source.connection.createStatement().use { it.execute("UPDATE observations SET finite_amount=1000 WHERE region='C'") }
            val failure = assertThrows(SQLException::class.java) { rows(source, shared, support = support) }
            assertTrue(failure.message.orEmpty().contains("finite aggregate input outside trial domain"))
            b.push(input).aggregate(b.groupKey(b.field("region")),
                b.aggregateCall(support.nativeAggregate(SqlKind.SUM), listOf(b.field("amount"))).`as`("total")).sort(b.field("region"))
            val ordinary = rows(source, b.build(), support = support)
            assertEquals(listOf("A", "B", "C"), ordinary.map { it[0] })
            decimal(ordinary[0][1], "3000"); assertNull(ordinary[1][1]); decimal(ordinary[2][1], "10000")
        }
    }
    @Test fun aggregateReductionPreservesCompletedGroupsBagsAndEmptyPopulations(@TempDir directory: Path) {
        for (dialect in listOf("h2", "duckdb")) source(directory, dialect).use { source ->
            source.connection.createStatement().use {
                it.execute("DELETE FROM observations")
                it.execute("INSERT INTO observations VALUES (1,'A',1),(1,'A',1),(3,'A',2),(NULL,'B',NULL),(NULL,'B',NULL),(6,NULL,4),(6,NULL,4),(7,'C',0)")
            }
            val baseSupport = TestConnectors.registry.require(source.spec.connectorRef).compilation
            // 原SUM放入一个数组工作值，再从中取回，实际工作载体与最终数值类型不同；这里只验证SQL装配，
            // 不声明任一来源具有无界Finite累加保证。
            val support = object : CompilationSupport by baseSupport {
                override fun aggregateSql(owner: Aggregate, call: AggregateCall, arguments: List<SqlNode>, expression: SqlNode): AggregateSql {
                    if (call.aggregation.kind != SqlKind.SUM) return AggregateSql.Direct(expression)
                    require(arguments.size == 1 && !call.isDistinct && !call.hasFilter() && call.collation.fieldCollations.isEmpty())
                    val packed = Ops.ARRAY_VALUE_CONSTRUCTOR.createCall(expression.parserPosition, expression)
                    return AggregateSql.Reduction(packed) { work, groups, value ->
                        val pos = SqlParserPos.ZERO
                        val outputName = SqlIdentifier("native_result", pos)
                        val reduced = Ops.AS.createCall(pos,
                            Ops.ITEM.createCall(pos, value, SqlLiteral.createExactNumeric("1", pos)), outputName)
                        val order = SqlNodeList(listOf(outputName), pos)
                        val selected = SqlSelect(pos, null, SqlNodeList(groups + reduced, pos), work, null,
                            null,
                            null, null, null, null, null, null, null)
                        if (owner.aggCallList.indexOf(call) == 0) {
                            selected.setOrderBy(order)
                            selected
                        } else SqlOrderBy(pos, selected, order, null, null)
                    }
                }
            }
            val b = builder(source)
            val input = b.build()
            fun complete(empty: Boolean, grouped: Boolean): RelNode {
                b.push(input)
                if (empty) b.filter(b.lessThan(b.field("id"), b.literal(0)))
                b.aggregate(if (grouped) b.groupKey(b.field("region")) else b.groupKey(),
                    b.sum(false, "amount_total", b.field("amount")),
                    b.sum(false, "id_total", b.field("id")),
                    b.count(false, "records"))
                return b.build()
            }
            val completed = complete(empty = false, grouped = true)
            val result = rows(source, completed, support = support)
            assertEquals(4, result.size)
            val grouped = result.associateBy { it[0] }
            assertEquals(setOf("A", "B", "C", null), grouped.keys)
            val expected = mapOf(
                "A" to listOf("4", "5", "3"),
                "B" to listOf(null, null, "2"),
                "C" to listOf("0", "7", "1"),
                null to listOf("8", "12", "2")
            )
            expected.forEach { (region, values) -> values.forEachIndexed { column, value ->
                val actual = grouped.getValue(region)[column + 1]
                if (value == null) assertNull(actual) else decimal(actual, value)
            } }

            b.push(completed).filter(b.greaterThan(b.field("amount_total"), b.literal(0))).sort(b.field("id_total"))
            val selected = b.build()
            assertEquals(listOf("A", null), rows(source, selected, support = support).map { it[0] })
            b.push(selected).aggregate(b.groupKey(),
                b.sum(false, "amount_again", b.field("amount_total")),
                b.sum(false, "ids_again", b.field("id_total")),
                b.sum(false, "records_again", b.field("records")))
            val reduced = rows(source, b.build(), support = support).single()
            listOf("12", "17", "5").forEachIndexed { column, value -> decimal(reduced[column], value) }

            assertTrue(rows(source, complete(empty = true, grouped = true), support = support).isEmpty())
            val emptyGlobal = rows(source, complete(empty = true, grouped = false), support = support).single()
            assertNull(emptyGlobal[0]); assertNull(emptyGlobal[1]); decimal(emptyGlobal[2], "0")
            val global = rows(source, complete(empty = false, grouped = false), support = support).single()
            listOf("12", "24", "8").forEachIndexed { column, value -> decimal(global[column], value) }
        }
    }
    @Test fun fourConsumersShareStatementStableQualificationBeforeIndependentAggregates(@TempDir directory: Path) {
        for (dialect in listOf("h2", "duckdb")) source(directory, dialect).use { source ->
            source.connection.createStatement().use {
                it.execute("INSERT INTO observations VALUES (5,'D',100),(6,'E',50),(7,NULL,8)")
                it.execute("CREATE TABLE profiles(region VARCHAR(8),profile_day VARCHAR(8))")
                it.execute("INSERT INTO profiles VALUES ('A','20000102'),('A','20000102'),('B','20000102'),('C','20000102'),('D','20000102'),('E','20000101'),(NULL,'20000102')")
            }
            val observations = source.adopt("observations", emptyMap())
            val profiles = source.adopt("profiles", emptyMap())
            val profileTable = SemanticExpression.TableArgument(profiles.physicalTableId, profiles.physicalTableVersion.toLong())
            val profileDependency = NativeTableDependency(profiles.dataContract, profiles.adoption)
            val declaration = NativeScalarFunction("latest_profile_day", listOf(NativeScalarFunction.Parameter(
                NativeScalarFunction.ParameterKind.PHYSICAL_TABLE, null)), NativeValueType("VARCHAR", 8, null, true),
                null, NativeScalarFunction.Stability.STATEMENT)
            val baseSupport = TestConnectors.registry.require(source.spec.connectorRef).compilation
            val support = object : CompilationSupport by baseSupport {
                override val nativeScalarFunctions = listOf(declaration)
                override fun requireNativeScalarFunction(id: String): NativeScalarFunction {
                    require(id == declaration.id) { "native scalar function is outside the trial's exact declaration: $id" }
                    return declaration
                }
                override fun nativeScalarCall(function: String, arguments: List<CompilationSupport.NativeArgument>): SqlNode {
                    require(function == declaration.id)
                    val table = arguments.single() as CompilationSupport.TableArgument
                    val pos = SqlParserPos.ZERO
                    val query = SqlSelect(pos, null, SqlNodeList(listOf(Ops.MAX.createCall(pos, SqlIdentifier("profile_day", pos))), pos),
                        SqlIdentifier(table.qualifiedPath, pos), null, null, null, null, null, null, null, null, null)
                    return Ops.SCALAR_QUERY.createCall(pos, query)
                }
            }
            val bindings = listOf(observations, profiles.copy(nativeTableDependencies = mapOf(profileTable.key() to profileDependency)))
            val b = CalciteQueryCompiler.inputs(bindings, bindings.flatMap { it.physicalColumnBindings.keys }.toSet(),
                setOf(profileTable), TestConnectors.registry).builder.scan(FrozenCatalogSchema.path(observations))
            val parameter = b.rexBuilder.makeDynamicParam(b.typeFactory.createSqlType(SqlTypeName.VARCHAR, 8), 0)
            b.filter(b.notEquals(b.field("region"), parameter))
            b.scan(FrozenCatalogSchema.path(profiles))
            val native = NativeExpressionScope(source.spec.connectorRef, support) { table ->
                require(table.key() == profileTable.key())
                profileDependency
            }
            val latest = native.compile(b, SemanticExpression.NativeCall(source.spec.connectorRef, declaration.id, listOf(profileTable))) {
                error("latest profile day has no scalar arguments")
            }
            b.filter(b.equals(b.field("profile_day"), latest)).project(listOf(b.field("region")), listOf("qualified_region"), true)
            b.join(JoinRelType.SEMI, b.equals(b.field(2, 0, "region"), b.field(2, 1, "qualified_region")))
            val completed = b.build()
            val calls = listOf("total", "known", "records", "maximum").mapIndexed { index, quantity ->
                b.push(completed)
                val aggregate = when (quantity) {
                    "total" -> b.sum(false, quantity, b.field("amount"))
                    "known" -> b.count(false, quantity, b.field("amount"))
                    "records" -> b.count(false, quantity)
                    else -> b.max(quantity, b.field("amount"))
                }
                b.aggregate(b.groupKey("region"), aggregate)
                b.project(listOf(b.field("region"), b.call(Ops.DIVIDE, b.field(quantity), b.literal(BigDecimal("2.0")))),
                    listOf("region_$index", "average_$index"), true)
                b.build()
            }
            b.push(calls.first())
            calls.drop(1).forEachIndexed { index, call ->
                b.push(call)
                b.join(JoinRelType.INNER, b.equals(b.field(2, 0, "region_0"), b.field(2, 1, "region_${index + 1}")))
            }
            b.project(listOf(b.field("region_0")) + (0..3).map { b.field("average_$it") },
                listOf("region", "amount", "known", "records", "maximum"), true).sort(b.field("region"))
            val parameters = listOf(JdbcParameter.of(Types.VARCHAR, "VARCHAR", 8, 0, "D"))
            val plan = CalciteQueryCompiler.optimize(b.build(), parameters)
            val sql = CalciteSqlRendering.render(plan, support, parameters)
            val statement = SqlParser.create(sql.sql).parseQuery() as SqlWith
            val references = mutableMapOf<String, Int>()
            statement.accept(object : SqlBasicVisitor<Void>() {
                override fun visit(identifier: SqlIdentifier): Void? {
                    val name = identifier.names.last()
                    references[name] = (references[name] ?: 0) + 1
                    return null
                }
            })
            assertEquals(1, references["observations"], "$dialect duplicated the qualified input in generated SQL")
            assertEquals(2, references["profiles"], "$dialect duplicated the SEMI qualification or its native latest-day lookup")
            assertEquals(if (support.parameterSubmission == CompilationSupport.ParameterSubmission.BIND_PARAMETERS)
                parameters else emptyList(), CalciteSqlRendering.orderedParameters(sql, parameters))
            val result = rows(source, plan, parameters, support)
            assertEquals(listOf("A", "B", "C"), result.map { it[0] })
            val expected = listOf(listOf("1.5", "1", "1", "1"), listOf(null, "0", "0.5", null), listOf("5", "0.5", "0.5", "5"))
            expected.forEachIndexed { row, values -> values.forEachIndexed { column, value ->
                if (value == null) assertNull(result[row][column + 1]) else decimal(result[row][column + 1], value)
            } }
            source.connection.createStatement().use { it.execute("INSERT INTO profiles VALUES ('C','20000103')") }
            val nextStatement = rows(source, plan, parameters, support)
            assertEquals(listOf("C"), nextStatement.map { it[0] })
            expected.last().forEachIndexed { column, value -> decimal(nextStatement.single()[column + 1], requireNotNull(value)) }
        }
    }
    @Test fun eachConsumerEvaluatesItsOwnNonDeterministicObservation(@TempDir directory: Path) {
        for (dialect in listOf("h2", "duckdb")) source(directory, dialect).use { source ->
            source.connection.createStatement().use { it.execute("CREATE SEQUENCE draws START WITH 1") }
            val nextValue = object : SqlFunction("NEXTVAL", SqlKind.OTHER_FUNCTION, ReturnTypes.BIGINT, null, OperandTypes.CHARACTER, SqlFunctionCategory.SYSTEM) {
                override fun isDeterministic() = false
            }
            val b = builder(source)
            b.project(listOf(b.field("id"), b.call(nextValue, b.literal("draws"))), listOf("id", "draw"), true)
            val observations = b.build()
            b.push(observations).push(observations).join(JoinRelType.INNER, b.equals(b.field(2, 0, "id"), b.field(2, 1, "id")))
            b.project(listOf(b.field(0), b.field(1), b.field(3)), listOf("id", "left_draw", "right_draw"), true).sort(b.field("id"))
            val result = rows(source, b.build())
            assertEquals(listOf(1L, 2L, 3L, 4L), result.map { (it[0] as Number).toLong() })
            result.forEach { assertNotEquals((it[1] as Number).toLong(), (it[2] as Number).toLong(), "$dialect incorrectly shared sequence observation for id=${it[0]}") }
        }
    }
    @Test fun typedNativeScalarSharesRowIntermediatesWithoutEvaluatingUnselectedErrors(@TempDir directory: Path) {
        for (dialect in listOf("h2", "duckdb")) source(directory, dialect).use { source ->
            source.connection.createStatement().use {
                it.execute("ALTER TABLE observations ADD raw_text VARCHAR(32)")
                it.execute("ALTER TABLE observations ADD should_parse BOOLEAN")
                it.execute("UPDATE observations SET raw_text=CASE id WHEN 1 THEN ' 1_0.25 ' WHEN 2 THEN ' 2.5 ' WHEN 4 THEN 'bad' END, should_parse=(id<>4), region=LOWER(region)")
                it.execute("INSERT INTO observations(id,region,amount,raw_text,should_parse) VALUES (5,'d',NULL,'unknown-invalid',NULL)")
            }
            val finite = dialect == "h2"
            val decimalFunction = NativeScalarFunction("tripled_decimal", listOf(NativeScalarFunction.Parameter(
                NativeScalarFunction.ParameterKind.SCALAR, ScalarValueType.TEXT)),
                if (finite) NativeValueType("FINITE_DECIMAL", null, null, true) else NativeValueType("DECIMAL", 38, 18, true),
                MetricValueContract.Exact(), NativeScalarFunction.Stability.IMMUTABLE)
            val upperFunction = NativeScalarFunction("upper_region", listOf(NativeScalarFunction.Parameter(
                NativeScalarFunction.ParameterKind.SCALAR, ScalarValueType.TEXT)), NativeValueType("VARCHAR", 8, null, true),
                null, NativeScalarFunction.Stability.IMMUTABLE)
            val baseSupport = TestConnectors.registry.require(source.spec.connectorRef).compilation
            lateinit var unpassedAmount: RexNode
            var useUnpassedAmount = false
            val support = object : CompilationSupport by baseSupport {
                // DECFLOAT(100)仅兑现本试验输入，不声明生产H2支持全域Finite运算。
                override val typeSystem: RelDataTypeSystem = if (!finite) baseSupport.typeSystem else object : DelegatingTypeSystem(baseSupport.typeSystem) {
                    override fun deriveDecimalPlusType(factory: RelDataTypeFactory, left: RelDataType, right: RelDataType): RelDataType? {
                        if (FiniteDecimalTypes.isFinite(left) || FiniteDecimalTypes.isFinite(right)) {
                            require(SqlTypeUtil.isExactNumeric(left) && SqlTypeUtil.isExactNumeric(right))
                            return FiniteDecimalTypes.create(left.isNullable || right.isNullable)
                        }
                        return baseSupport.typeSystem.deriveDecimalPlusType(factory, left, right)
                    }
                }
                override val dialect: SqlDialect = if (!finite) baseSupport.dialect else object : H2SqlDialect(H2SqlDialect.DEFAULT_CONTEXT.withDataTypeSystem(baseSupport.typeSystem)) {
                    override fun getCastSpec(type: RelDataType): SqlNode? = if (FiniteDecimalTypes.isFinite(type))
                        SqlDataTypeSpec(SqlAlienSystemTypeNameSpec("DECFLOAT", SqlTypeName.DECIMAL, 100, SqlParserPos.ZERO), SqlParserPos.ZERO)
                    else baseSupport.dialect.getCastSpec(type)
                    override fun quoteStringLiteral(builder: StringBuilder, charsetName: String?, value: String) =
                        baseSupport.dialect.quoteStringLiteral(builder, charsetName, value)
                }
                override val nativeScalarFunctions = listOf(decimalFunction, upperFunction)
                override fun requireNativeScalarFunction(id: String): NativeScalarFunction = nativeScalarFunctions.single { it.id == id }
                override fun nativeScalarExpression(builder: RexBuilder, function: NativeScalarFunction, call: RexCall): RexNode {
                    if (function.id != decimalFunction.id) return call
                    if (useUnpassedAmount) return builder.makeCast(call.type, unpassedAmount)
                    val trimmed = builder.makeCall(Ops.TRIM, builder.makeFlag(SqlTrimFunction.Flag.BOTH),
                        builder.makeLiteral(" "), call.operands.single())
                    val normalized = builder.makeCall(Ops.REPLACE, trimmed, builder.makeLiteral("_"), builder.makeLiteral(""))
                    val parsed = builder.makeCast(call.type, normalized)
                    val hasSeparator = builder.makeCall(Ops.GREATER_THAN,
                        builder.makeCall(Ops.POSITION, builder.makeLiteral("_"), call.operands.single()), builder.makeExactLiteral(BigDecimal.ZERO))
                    val present = builder.makeCall(Ops.IS_NOT_NULL, call.operands.single())
                    val missing = builder.makeNullLiteral(call.type)
                    val twice = builder.makeCall(Ops.PLUS, parsed, parsed)
                    val tripled = builder.makeCall(Ops.PLUS, twice, parsed)
                    // 先在A域消费一次；随后B→A域消费两次。¬A兄弟域仍须取得自己的值。
                    val first = builder.makeCall(call.type, Ops.CASE, listOf(hasSeparator, parsed, missing))
                    val rest = builder.makeCall(call.type, Ops.CASE, listOf(present,
                        builder.makeCall(call.type, Ops.CASE, listOf(hasSeparator, twice, tripled)), missing))
                    val retained = builder.makeCall(call.type, Ops.COALESCE,
                        listOf(first, builder.makeExactLiteral(BigDecimal.ZERO, call.type)))
                    return builder.makeCall(call.type, Ops.PLUS, listOf(retained, rest))
                }
                override fun nativeScalarCall(function: String, arguments: List<CompilationSupport.NativeArgument>): SqlNode {
                    require(function == upperFunction.id) { "tripled decimal must use its typed expression mapping" }
                    return Ops.UPPER.createCall(SqlParserPos.ZERO, (arguments.single() as CompilationSupport.ScalarArgument).value)
                }
            }
            val binding = source.adopt("observations", emptyMap())
            val schema = FrozenCatalogSchema.bind(listOf(binding), binding.physicalColumnBindings.keys, emptySet(), TestConnectors.registry).schema
            val b = CalciteCompilation.builder(support, schema).scan(FrozenCatalogSchema.path(binding))
            unpassedAmount = b.field("amount")
            val native = NativeExpressionScope(source.spec.connectorRef, support) { null }
            val parsed = native.compileScalar(b, source.spec.connectorRef, decimalFunction.id, listOf(b.field("raw_text")))
            val upper = native.compileScalar(b, source.spec.connectorRef, upperFunction.id, listOf(b.field("region")))
            val selected = b.rexBuilder.makeCall(parsed.type, Ops.CASE, listOf(b.field("should_parse"), parsed, b.literal(null)))
            val outputs = listOf(b.field("id"), selected, upper)
            val input = b.build()
            val program = RexProgram.create(input.rowType, outputs, null, listOf("id", "amount", "region"), b.rexBuilder)
            b.push(LogicalCalc.create(input, program)).sort(b.field("id"))
            val plan = b.build()
            val rendered = CalciteSqlRendering.compile(plan, support)
            var replacements = 0
            SqlParser.create(rendered.sql.sql).parseQuery().accept(object : SqlBasicVisitor<Void>() {
                override fun visit(call: SqlCall): Void? {
                    if (call.operator.name.equals("REPLACE", ignoreCase = true)) replacements++
                    return super.visit(call)
                }
            })
            assertTrue(replacements in 1..2, "$dialect duplicated normalization after the B→A guard instead of reusing A; the disjoint ¬A population still needs its own value, and this does not count physical evaluations")
            val result = rows(source, rendered.sql)
            assertEquals(listOf(1, 2, 3, 4, 5), result.map { (it[0] as Number).toInt() })
            assertEquals(listOf("A", "A", "B", "C", "D"), result.map { it[2] }, "$dialect changed the ordinary native SQL leaf mapping")
            decimal(result[0][1], "30.75"); decimal(result[1][1], "7.5")
            assertNull(result[2][1]); assertNull(result[3][1]); assertNull(result[4][1])
            source.connection.createStatement().use { it.execute("UPDATE observations SET should_parse=TRUE WHERE id=4") }
            assertThrows(SQLException::class.java) { rows(source, rendered.sql) }
            val rawType = requireNotNull(input.rowType.getField("raw_text", true, false)).type
            val volatileText = b.rexBuilder.makeCast(rawType, b.rexBuilder.makeCall(Ops.RAND))
            val volatileCall = native.compileScalar(b, source.spec.connectorRef, decimalFunction.id, listOf(volatileText))
            val volatileProgram = RexProgram.create(input.rowType, listOf(volatileCall), null, listOf("amount"), b.rexBuilder)
            val volatileCalc = LogicalCalc.create(input, volatileProgram)
            val unstable = assertThrows(IllegalArgumentException::class.java) { CalciteSqlRendering.compile(volatileCalc, support) }
            assertTrue(unstable.message.orEmpty().contains("statement-stable"))
            useUnpassedAmount = true
            val rejected = assertThrows(IllegalArgumentException::class.java) { CalciteSqlRendering.compile(plan, support) }
            assertTrue(rejected.message.orEmpty().contains("outside its actual arguments"))
        }
    }
    @Test fun oneRegroupReusesGuardedNativeValuesAfterInnerMatching(@TempDir directory: Path) {
        for (dialect in listOf("h2", "duckdb")) NativeWarehouse(directory, dialect, listOf(
            "CREATE TABLE observations(id INTEGER,object_key VARCHAR(16),activity_day DATE,amount_text VARCHAR(32),should_parse BOOLEAN,existing_decimal DECIMAL(38,18))",
            "INSERT INTO observations VALUES " +
                "(1,'matched',DATE '2026-01-01','10.25',TRUE,NULL),(1,'matched',DATE '2026-01-01','10.25',TRUE,NULL)," +
                "(2,'null_value',DATE '2026-01-01',NULL,TRUE,NULL),(3,'case_guard',DATE '2026-01-01','case-invalid',FALSE,NULL)," +
                "(4,'coalesce_guard',DATE '2026-01-01','coalesce-invalid',TRUE,3.50),(5,'unmatched',DATE '2026-01-01','unmatched-invalid',TRUE,NULL)," +
                "(6,NULL,DATE '2026-01-01','null-key-invalid',TRUE,NULL),(7,'all_null',DATE '2026-01-01',NULL,TRUE,NULL)," +
                "(8,'wrong_day',DATE '2026-01-01','wrong-day-invalid',TRUE,NULL),(9,'unknown_guard',DATE '2026-01-01','unknown-invalid',NULL,NULL)",
            "CREATE TABLE ownership(object_key VARCHAR(16),activity_day DATE,region VARCHAR(8))",
            "INSERT INTO ownership VALUES ('matched',DATE '2026-01-01','A'),('null_value',DATE '2026-01-01','A')," +
                "('case_guard',DATE '2026-01-01','A'),('coalesce_guard',DATE '2026-01-01','A'),('unknown_guard',DATE '2026-01-01','A')," +
                "('all_null',DATE '2026-01-01','B'),(NULL,DATE '2026-01-01','C'),('wrong_day',DATE '2026-01-02','D')")).use { source ->
            val declaration = NativeScalarFunction("parse_decimal", listOf(NativeScalarFunction.Parameter(
                NativeScalarFunction.ParameterKind.SCALAR, ScalarValueType.TEXT)), NativeValueType("DECIMAL", 38, 18, true),
                MetricValueContract.Exact(), NativeScalarFunction.Stability.IMMUTABLE)
            val baseSupport = TestConnectors.registry.require(source.spec.connectorRef).compilation
            var parserExpansions = 0
            val support = object : CompilationSupport by baseSupport {
                override val nativeScalarFunctions = listOf(declaration)
                override fun requireNativeScalarFunction(id: String): NativeScalarFunction {
                    require(id == declaration.id)
                    return declaration
                }
                override fun nativeScalarCall(function: String, arguments: List<CompilationSupport.NativeArgument>): SqlNode {
                    require(function == declaration.id)
                    parserExpansions++
                    val value = (arguments.single() as CompilationSupport.ScalarArgument).value
                    val pos = SqlParserPos.ZERO
                    val type = SqlDataTypeSpec(SqlBasicTypeNameSpec(SqlTypeName.DECIMAL, 38, 18, pos), pos)
                    return SqlCase(pos, null, SqlNodeList(listOf(Ops.IS_NULL.createCall(pos, value)), pos),
                        SqlNodeList(listOf(Ops.CAST.createCall(pos, SqlLiteral.createNull(pos), type)), pos),
                        Ops.CAST.createCall(pos, value, type))
                }
            }
            val observations = source.adopt("observations", emptyMap())
            val ownership = source.adopt("ownership", emptyMap())
            val bindings = listOf(observations, ownership)
            val b = CalciteQueryCompiler.inputs(bindings, bindings.flatMap { it.physicalColumnBindings.keys }.toSet(),
                emptySet(), TestConnectors.registry).builder.scan(FrozenCatalogSchema.path(observations))
            b.scan(FrozenCatalogSchema.path(ownership))
            b.join(JoinRelType.INNER, b.and(
                b.equals(b.field(2, 0, "object_key"), b.field(2, 1, "object_key")),
                b.equals(b.field(2, 0, "activity_day"), b.field(2, 1, "activity_day"))))
            b.project(listOf(b.field("region"), b.field("should_parse"), b.field("existing_decimal"), b.field("amount_text")),
                listOf("region", "should_parse", "existing_decimal", "amount_text"), true)
            val native = NativeExpressionScope(source.spec.connectorRef, support) { null }
            val parsed = native.compileScalar(b, source.spec.connectorRef, declaration.id, listOf(b.field("amount_text")))
            val guarded = b.call(Ops.CASE, b.field("should_parse"),
                b.call(Ops.COALESCE, b.field("existing_decimal"), parsed), b.rexBuilder.makeNullLiteral(parsed.type))
            b.project(listOf(b.field("region"), guarded), listOf("region", "parsed_number"), true)
            val money = MetricValueContract(MetricValueContract.Fixed(MetricValueContract.UnitIdentity("iso4217", "CNY", 1)),
                MetricValueContract.Exact(), MetricValueContract.Decimal(38, 18, MetricValueContract.Rounding.NONE),
                emptyMap(), MetricValueContract.Overflow.FAIL)
            val amount = SemanticNumericRex.output(b, b.call(Ops.MULTIPLY, b.field("parsed_number"), b.literal(BigDecimal.ONE)), money)
            b.project(listOf(b.field("region"), b.field("parsed_number"), amount), listOf("region", "parsed_number", "amount"), true)
            b.aggregate(b.groupKey(b.field("region")), b.sum(false, "total", b.field("amount")),
                b.count(false, "parsed_count", b.field("parsed_number")), b.count(false, "amount_count", b.field("amount")))
                .sort(b.field("region"))
            val plan = CalciteQueryCompiler.optimize(b.build(), emptyList())
            val rendered = CalciteSqlRendering.compile(plan, support)
            val result = rows(source, rendered.sql)
            var emittedCases = 0
            SqlParser.create(rendered.sql.sql).parseQuery().accept(object : SqlBasicVisitor<Void>() {
                override fun visit(call: SqlCall): Void? {
                    if (call.kind == SqlKind.CASE) emittedCases++
                    return super.visit(call)
                }
            })
            assertEquals(emittedCases, rendered.caseExpressions, "$dialect omitted native parser CASE expressions from the executed SQL scale")
            assertEquals(listOf("A", "B"), result.map { it[0] }, "$dialect changed INNER matching or group population")
            decimal(result[0][1], "24.00")
            assertEquals(listOf(3L, 3L), result[0].drop(2).map { (it as Number).toLong() }, "$dialect lost repeated matched occurrences")
            assertNull(result[1][1])
            assertEquals(listOf(0L, 0L), result[1].drop(2).map { (it as Number).toLong() }, "$dialect converted NULL values into counted observations")
            // 编译发射只展开一份原生调用；不据此推导数据库物化或物理求值次数。
            assertEquals(1, parserExpansions, "$dialect expanded the same guarded parser separately for SUM/COUNT consumers")
            source.connection.createStatement().use { it.execute("UPDATE observations SET should_parse=TRUE WHERE object_key='case_guard'") }
            assertThrows(SQLException::class.java) { rows(source, plan, support = support) }
        }
    }
    @Test fun existsQuantityConditionsPreserveCorrelationAndConditionalEvaluationSlots(@TempDir directory: Path) {
        val contract = MetricValueContract(MetricValueContract.Fixed(MetricValueContract.ONE), MetricValueContract.NativeFloating(),
            MetricValueContract.BinaryFloat(64), emptyMap(), MetricValueContract.Overflow.FAIL)
        for (dialect in listOf("h2", "duckdb")) NativeWarehouse(directory, dialect, listOf(
            "CREATE TABLE observations(id INTEGER,amount DOUBLE)",
            "INSERT INTO observations VALUES (1,1),(2,8),(3,NULL),(4,1E308)")).use { source ->
            fun plan(includeOverflow: Boolean): RelNode {
                val outer = builder(source)
                var variable: RexCorrelVariable? = null
                outer.variable { variable = it }
                val correlation = requireNotNull(variable)
                val child = RelBuilder.proto().create(outer.cluster, outer.relOptSchema)
                    .scan(FrozenCatalogSchema.path(source.adopt("observations", emptyMap())))
                val checked = SemanticNumericRex.output(child, child.call(Ops.MULTIPLY, child.field("amount"), child.literal(2)), contract)
                val selected = child.call(Ops.CASE, child.lessThan(child.field("id"), child.literal(if (includeOverflow) 5 else 4)), checked, child.literal(0))
                child.filter(child.equals(child.field("id"), child.rexBuilder.makeFieldAccess(correlation, 0)), child.lessThan(selected, child.literal(10)))
                child.project(listOf(child.literal(1)), listOf("member"), true)
                outer.filter(setOf(correlation.id), RexSubQuery.exists(child.build()))
                outer.project(outer.field("id")).sort(outer.field("id"))
                return outer.build()
            }
            val selected = plan(false)
            val support = TestConnectors.registry.require(source.spec.connectorRef).compilation
            if (!support.supports(CompilationSupport.Capability.CORRELATED_DERIVED_PROJECTION)) {
                val failure = assertThrows(NativeCompilationCapabilityException::class.java) { CalciteSqlRendering.compile(selected, support) }
                assertEquals(CompilationSupport.Capability.CORRELATED_DERIVED_PROJECTION, failure.capability)
                assertEquals("SQL_EVALUATION_BINDING", failure.operation)
                val outer = builder(source)
                var variable: RexCorrelVariable? = null
                outer.variable { variable = it }
                val correlation = requireNotNull(variable)
                val child = RelBuilder.proto().create(outer.cluster, outer.relOptSchema)
                    .scan(FrozenCatalogSchema.path(source.adopt("observations", emptyMap())))
                child.filter(child.equals(child.field("id"), child.rexBuilder.makeFieldAccess(correlation, 0)))
                    .project(listOf(child.literal(1)), listOf("member"), true)
                outer.filter(setOf(correlation.id), RexSubQuery.exists(child.build())).project(outer.field("id")).sort(outer.field("id"))
                assertEquals(listOf(1, 2, 3, 4), rows(source, outer.build()).map { (it.single() as Number).toInt() })
                return@use
            }
            val rendered = CalciteSqlRendering.compile(selected, support)
            var failures = 0
            SqlParser.create(rendered.sql.sql).parseQuery().accept(object : SqlBasicVisitor<Void>() {
                override fun visit(literal: SqlLiteral): Void? {
                    if (literal is SqlCharStringLiteral && literal.nlsString.value.startsWith("numeric contract violation")) failures++
                    return null
                }
            })
            assertEquals(2, rendered.numericChecks)
            assertEquals(rendered.numericChecks, failures, "$dialect inlined numeric checks again inside EXISTS")
            assertEquals(listOf(1, 4), rows(source, selected).map { (it.single() as Number).toInt() })
            assertThrows(SQLException::class.java) { rows(source, plan(true)) }
        }
    }
    @Test fun singleValueFailsOnlyForActualMultipleRows(@TempDir directory: Path) {
        for (dialect in listOf("h2", "duckdb")) source(directory, dialect).use { source ->
            for (size in 0..2) {
                source.connection.createStatement().use { statement ->
                    statement.execute("DELETE FROM observations")
                    if (size > 0) statement.execute("INSERT INTO observations VALUES (1,'A',7)")
                    if (size > 1) statement.execute("INSERT INTO observations VALUES (2,'A',7)")
                }
                val b = builder(source)
                b.aggregate(b.groupKey(), b.aggregateCall(Ops.SINGLE_VALUE, listOf(b.field("amount"))).`as`("single"))
                val plan = b.build()
                if (size == 2) assertThrows(SQLException::class.java) { rows(source, plan) }
                else { val result = rows(source, plan); assertEquals(1, result.size); if (size == 0) assertNull(result[0][0]) else decimal(result[0][0], "7") }
            }
        }
    }
    @Test fun explicitNullOrderingSurvivesDifferentSessionDefaults(@TempDir directory: Path) {
        for (dialect in listOf("h2", "duckdb")) source(directory, dialect).use { source ->
            source.connection.createStatement().use { it.execute("INSERT INTO observations VALUES (NULL,'D',8)"); it.execute(if (dialect == "h2") "SET DEFAULT_NULL_ORDERING HIGH" else "SET default_null_order='NULLS_FIRST'") }
            val b = builder(source); b.project(b.field("id")); b.sort(if (dialect == "h2") b.nullsFirst(b.field("id")) else b.nullsLast(b.field("id")))
            val result = rows(source, b.build()); assertEquals(5, result.size); assertNull(result[if (dialect == "h2") 0 else 4][0])
        }
    }
    @Test fun groupedTotalsFinishBeforeOverallMaximum(@TempDir directory: Path) {
        for (dialect in listOf("h2", "duckdb")) source(directory, dialect).use { source ->
            val b = builder(source); b.aggregate(b.groupKey(b.field("region")), b.sum(false, "total", b.field("amount")))
            b.project(listOf(b.call(Ops.PLUS, b.field("total"), b.literal(1))), listOf("derived"), true)
            b.aggregate(b.groupKey(), b.max("maximum", b.field("derived")))
            val result = rows(source, b.build()); assertEquals(1, result.size); decimal(result[0][0], "11")
        }
    }
    @Test fun rankingUsesTheCompletedGroupValueInsteadOfAnOrdinalConstant(@TempDir directory: Path) {
        for (dialect in listOf("h2", "duckdb")) source(directory, dialect).use { source ->
            val b = builder(source); b.aggregate(b.groupKey(b.field("region")), b.sum(false, "total", b.field("amount")))
            val rank = b.aggregateCall(Ops.RANK, emptyList()).over().orderBy(b.nullsLast(b.desc(b.field("total")))).toRex()
            b.project(listOf(b.field("region"), b.field("total"), rank), listOf("region", "total", "rank"), true).sort(b.field("region"))
            val result = rows(source, CalciteQueryCompiler.optimize(b.build(), emptyList()))
            assertEquals(listOf("A", "B", "C"), result.map { it[0] }); assertEquals(listOf(2L, 3L, 1L), result.map { (it[2] as Number).toLong() })
        }
    }
    @Test fun calendarWeeksRemainMondayInUsLocale(@TempDir directory: Path) {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            source(directory, "h2").use { source ->
                source.connection.createStatement().use { it.execute("ALTER TABLE observations ADD observed_at TIMESTAMP(6)"); it.execute("UPDATE observations SET observed_at=CASE id WHEN 1 THEN TIMESTAMP '2026-03-01 23:59:59.999999' WHEN 4 THEN NULL ELSE TIMESTAMP '2026-03-02 00:00:00' END") }
                val binding = source.adopt("observations", mapOf("observed" to "observed_at")); val b = builder(source, binding)
                val parameters = mutableListOf<JdbcParameter>(); val compiler = compiler(b, binding, parameters)
                val week = SemanticExpression.Call(ScalarFunction.DATE_TRUNC, listOf(SemanticExpression.Literal("week"), SemanticExpression.Reference(SemanticExpression.ReferenceKind.LOGIC_UNIT, "observed", 1)))
                b.project(listOf(b.field("id"), compiler.compile(week)), listOf("id", "week"), true).sort(b.field("id"))
                val result = rows(source, CalciteQueryCompiler.optimize(b.build(), parameters), parameters)
                assertEquals(LocalDateTime.parse("2026-02-23T00:00:00"), result[0][1]); assertEquals(LocalDateTime.parse("2026-03-02T00:00:00"), result[1][1]); assertNull(result[3][1])
            }
        } finally { Locale.setDefault(original) }
    }
    @Test fun duckDbCastsRetainNanosecondsAndSigned128BitCapacity(@TempDir directory: Path) {
        source(directory, "duckdb").use { source ->
            source.connection.createStatement().use { it.execute("ALTER TABLE observations ADD moment_text VARCHAR(64)"); it.execute("ALTER TABLE observations ADD integer_text VARCHAR(64)"); it.execute("UPDATE observations SET moment_text='2026-01-01 12:30:00.123456789',integer_text='170141183460469231731687303715884105727'") }
            val b = builder(source)
            val values = listOf(0, 3, 6, 9).mapTo(mutableListOf<RexNode>()) { b.cast(b.field("moment_text"), SqlTypeName.TIMESTAMP, it) }
            values += b.cast(b.field("integer_text"), SqlTypeName.DECIMAL, 39, 0)
            b.project(values, listOf("seconds", "milliseconds", "microseconds", "nanoseconds", "huge_integer"), true)
            val result = rows(source, b.build()).first()
            listOf("2026-01-01T12:30:00", "2026-01-01T12:30:00.123", "2026-01-01T12:30:00.123456", "2026-01-01T12:30:00.123456789")
                .forEachIndexed { index, value -> assertEquals(LocalDateTime.parse(value), result[index]) }
            assertEquals("170141183460469231731687303715884105727", result[4].toString())
        }
    }
    @Test fun calendarShiftsRespectMonthEndsLeapYearsAndUnknownTimes(@TempDir directory: Path) {
        for (dialect in listOf("h2", "duckdb")) source(directory, dialect).use { source ->
            source.connection.createStatement().use { it.execute("ALTER TABLE observations ADD observed_at TIMESTAMP(6)"); it.execute("UPDATE observations SET observed_at=CASE id WHEN 1 THEN TIMESTAMP '2026-03-31 12:30:00.123456' WHEN 2 THEN TIMESTAMP '2024-03-31 12:30:00.123456' ELSE NULL END") }
            val binding = source.adopt("observations", mapOf("observed" to "observed_at")); val b = builder(source, binding)
            val parameters = mutableListOf<JdbcParameter>(); val compiler = compiler(b, binding, parameters)
            val units = listOf("MONTH", "QUARTER", "YEAR", "WEEK", "DAY", "HOUR", "SECOND"); val offsets = listOf(-1, 1, 1, -1, -1, 2, -2)
            val expressions = mutableListOf<RexNode>(b.field("id"))
            units.forEachIndexed { index, unit -> expressions += compiler.compile(SemanticExpression.Call(ScalarFunction.TIMESTAMPADD, listOf(SemanticExpression.Literal(unit), SemanticExpression.Literal(offsets[index]), SemanticExpression.Reference(SemanticExpression.ReferenceKind.LOGIC_UNIT, "observed", 1)))) }
            b.project(expressions, listOf("id") + units, true).sort(b.field("id"))
            val result = rows(source, CalciteQueryCompiler.optimize(b.build(), parameters), parameters)
            assertEquals(4, result.size)
            listOf("2026-02-28T12:30:00.123456", "2026-06-30T12:30:00.123456", "2027-03-31T12:30:00.123456", "2026-03-24T12:30:00.123456", "2026-03-30T12:30:00.123456", "2026-03-31T14:30:00.123456", "2026-03-31T12:29:58.123456")
                .forEachIndexed { index, value -> assertEquals(LocalDateTime.parse(value), result[0][index + 1]) }
            assertEquals(LocalDateTime.parse("2024-02-29T12:30:00.123456"), result[1][1])
            assertTrue(result[2].drop(1).all { it == null }); assertTrue(result[3].drop(1).all { it == null })
        }
    }
}
