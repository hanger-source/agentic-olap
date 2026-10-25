package com.olap.prototype.engine.executable

import com.olap.prototype.expression.ScalarFunction

import com.olap.prototype.connector.JdbcAccess
import com.olap.prototype.connector.CompilationSupport
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
import com.olap.prototype.expression.NativeExpressionScope
import com.olap.prototype.expression.NativeScalarFunction
import com.olap.prototype.expression.SemanticExpression
import com.olap.prototype.expression.SemanticNumericRex
import com.olap.prototype.integration.TestConnectors
import com.olap.prototype.metric.MetricValueContract
import com.olap.prototype.query.ScalarValueType
import org.apache.calcite.avatica.util.TimeUnit
import org.apache.calcite.rel.RelNode
import org.apache.calcite.rel.core.JoinRelType
import org.apache.calcite.rex.RexNode
import org.apache.calcite.rex.RexCorrelVariable
import org.apache.calcite.rex.RexSubQuery
import org.apache.calcite.sql.*
import org.apache.calcite.sql.`fun`.SqlStdOperatorTable as Ops
import org.apache.calcite.sql.parser.SqlParserPos
import org.apache.calcite.sql.parser.SqlParser
import org.apache.calcite.sql.util.SqlBasicVisitor
import org.apache.calcite.sql.type.OperandTypes
import org.apache.calcite.sql.type.ReturnTypes
import org.apache.calcite.sql.type.SqlTypeName
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
        support: CompilationSupport = TestConnectors.registry.require(source.spec.connectorRef).compilation): List<List<Any?>> {
        val connector = TestConnectors.registry.require(source.spec.connectorRef)
        val sql = CalciteSqlRendering.render(plan, support, parameters)
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
            statement.withList.forEach { definition ->
                val name = (definition as SqlWithItem).name.simple
                assertTrue(requireNotNull(references[name]) > 1, "$dialect emitted an unused shared relation: $name")
            }
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
                    return Ops.CAST.createCall(pos, value, SqlDataTypeSpec(SqlBasicTypeNameSpec(SqlTypeName.DECIMAL, 38, 18, pos), pos))
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
            val result = rows(source, plan, support = support)
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
