package com.olap.prototype.connector

import com.olap.fixtures.http.HttpSqlConnector
import com.olap.fixtures.http.HttpSqlService
import com.olap.prototype.api.SqlResultValues
import com.olap.prototype.connector.loader.ConnectorPlugins
import com.olap.prototype.codec.GovernanceProto
import com.olap.prototype.codec.ProtoBytes
import com.olap.prototype.contract.*
import com.olap.prototype.engine.calcite.CandidateValueCompiler
import com.olap.prototype.engine.calcite.CalciteQueryCompiler
import com.olap.prototype.engine.catalog.ExecutablePhysicalExecutionBinding
import com.olap.prototype.expression.*
import com.olap.prototype.integration.RuntimeDeploymentLimits
import com.olap.prototype.integration.TestConnectors
import com.olap.prototype.metric.MetricValueContract
import com.olap.prototype.query.*
import com.olap.prototype.query.PostOperationContract.Derive
import com.olap.prototype.query.PostOperationContract.JoinInput
import com.olap.prototype.query.PostOperationContract.OrderKey
import com.olap.prototype.query.PostOperationContract.Project
import com.olap.prototype.query.PostOperationContract.Regroup
import com.olap.prototype.query.PostOperationContract.ResultAggregate
import com.olap.prototype.query.PostOperationContract.ResultGrouping
import com.olap.prototype.query.PostOperationContract.Sort
import com.olap.prototype.query.PostOperationContract.ValidatedOperation
import com.olap.prototype.query.ResultExpression.*
import com.olap.prototype.api.ResultValueType
import com.olap.prototype.util.OlapJsonUtils
import com.olap.prototype.warehouse.*
import com.olap.prototype.warehouse.contract.*
import com.olap.prototype.warehouse.inspection.*
import com.olap.prototype.warehouse.onboarding.SourceStructureRegistrationRequest
import org.apache.calcite.rex.RexNode
import org.apache.calcite.rel.RelNode
import org.apache.calcite.sql.type.SqlTypeName
import org.apache.calcite.tools.Frameworks
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.HexFormat
import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream

class HttpSourceAccessTest {
    @Test fun nativeCompletedValuesPreserveBagContributionsConditionalErrorsAndArithmetic(@TempDir directory: Path) {
        val spec = JdbcConnectionSpec("native-values", TestConnectors.ref("duckdb"), "duckdb",
            "jdbc:duckdb:${directory.resolve("native-values.duckdb")}", null, null, "org.duckdb.DuckDBDriver")
        TestConnectors.open("duckdb", spec.jdbcUrl).use { connection -> connection.createStatement().use { sql ->
            sql.execute("CREATE TABLE facts(id INTEGER, record_key VARCHAR, qualifier VARCHAR)")
            sql.execute("CREATE TABLE details(record_key VARCHAR, start_text VARCHAR, end_text VARCHAR)")
            sql.execute("INSERT INTO facts VALUES (1,'multiple','yes'),(2,'false',''),(3,'unknown',NULL),(4,'null','yes'),(5,'absent','yes')")
            sql.execute("INSERT INTO details VALUES ('multiple','20260101','20260105'),('multiple','20260105','20260103'),('false','bad-date','bad-date'),('unknown','bad-date','bad-date'),('null','20260101',NULL)")
        } }
        val jdbc = (TestConnectors.registry.require(spec.connectorRef).access as JdbcAccess).jdbc
        HttpSqlService(jdbc, spec).use { server -> ConnectorPlugins.load(listOf(plugin(directory))).use { loaded ->
            val ref = loaded.registry.references().single()
            val source = SourceConfiguration(spec.dataSourceId, ref, "duckdb", mapOf("endpoint" to server.endpoint, "consistentChecks" to true),
                mapOf("timeoutMillis" to 30000), mapOf("authorization" to "OLAP_TEST_HTTP_AUTH"))
            val support = loaded.registry.require(ref).compilation
            val native = NativeExpressionScope(ref, support) { null }
            fun binding(table: String, columns: List<String>): ExecutablePhysicalExecutionBinding {
                val refs = columns.associateWith { "$table:$it@1" }
                return ExecutablePhysicalExecutionBinding(table, "1", null, null, null, null, table, "1", table, "1", source.dataSourceId,
                    ref, listOf("main", table), source.dialectId, null, "main", table, nativeEvidence(source, refs), emptyList(),
                    physicalColumnBindings = refs.entries.associate { it.value to it.key },
                    physicalColumnTypes = refs.entries.associate { it.value to if (it.key == "id") JdbcColumnType(java.sql.Types.INTEGER, "INTEGER", 10, 0, true)
                        else JdbcColumnType(java.sql.Types.VARCHAR, "VARCHAR", 65536, null, true) })
            }
            val bindings = listOf(binding("facts", listOf("id", "record_key", "qualifier")), binding("details", listOf("record_key", "start_text", "end_text")))
            fun symbol(name: String): ResultExpression = ResultSymbol(name)
            fun operation(operator: ResultOperator, vararg arguments: ResultExpression): ResultExpression = ResultOperation(operator, arguments.toList())
            fun call(function: String, vararg arguments: ResultExpression): ResultExpression = ResultNativeCall(ref, function, arguments.toList())
            fun date(name: String) = call("parse_day", symbol(name), ResultTypedLiteral(ScalarValueType.TEXT, "%Y%m%d"))
            val quantity = MetricValueContract(MetricValueContract.Fixed(MetricValueContract.ONE), MetricValueContract.Exact(), MetricValueContract.Integer(64), emptyMap(), MetricValueContract.Overflow.FAIL)
            val floating = MetricValueContract(quantity.unit, MetricValueContract.NativeFloating(), MetricValueContract.BinaryFloat(64), emptyMap(), quantity.overflow)
            val selected = ResultCaseWhen(listOf(ResultCaseBranch(operation(ResultOperator.NE, symbol("qualifier"), ResultTypedLiteral(ScalarValueType.TEXT, "")),
                call("calendar_days", date("detail__end_text"), date("detail__start_text")))), ResultLiteral(null))
            val joined = listOf(JoinInput("details", "detail", "LEFT", operation(ResultOperator.EQ, symbol("record_key"), symbol("detail__record_key"))),
                Derive(selected, "days", "合格关联贡献的日历天差"))
            fun compile(operations: List<ValidatedOperation>, join: Boolean = true): Pair<RelNode, List<JdbcParameter>> {
                val inputs = CalciteQueryCompiler.inputs(bindings, bindings.flatMap { it.physicalColumnBindings.keys }.toSet(), emptySet(), loaded.registry)
                val b = inputs.builder
                val root = b.scan(listOf("main", "facts")).build()
                val scope = QueryStageAnalysis.ScopeId.root()
                fun columns(relation: RelNode, at: QueryStageAnalysis.ScopeId) = relation.rowType.fieldNames.mapIndexed { index, name ->
                    QueryStageAnalysis.SourceColumn.categorical(name, QueryStageAnalysis.OperationOrigin(at, 0, index, PostOperationContract.PROJECT)) }
                val children = if (!join) emptyMap() else {
                    val right = b.scan(listOf("main", "details")).build()
                    val rightScope = scope.child("details")
                    val analysis = QueryStageAnalysis.analyze(rightScope, columns(right, rightScope), QueryStageAnalysis.UnknownSourceKey.INSTANCE,
                        emptyMap(), emptyList(), QueryStageAnalysis.IntrinsicPopulation(emptySet()), "details")
                    mapOf("details" to (analysis to right))
                }
                val functions = operations.flatMap { PostOperationTraversal.expressions(it) }.flatMap(ResultExpressionTree::nodes)
                    .filterIsInstance<ResultNativeCall>().associateWith { native.requireScalarCall(it.connectorRef, it.function, it.arguments.size) }
                val analysis = QueryStageAnalysis.analyze(scope, columns(root, scope), QueryStageAnalysis.UnknownSourceKey.INSTANCE,
                    children.mapValues { QueryStageAnalysis.Child("RELATION", it.value.first) }, operations,
                    QueryStageAnalysis.IntrinsicPopulation(emptySet()), "native completed values", nativeFunctions = functions)
                val parameters = mutableListOf<JdbcParameter>()
                b.push(root)
                CompletedRelationCompiler.compile(b, analysis, linkedMapOf(), parameters, children.mapValues { it.value.second }, mutableListOf(), native)
                return CalciteQueryCompiler.optimize(b.build(), parameters) to parameters
            }
            fun execute(operations: List<ValidatedOperation>, join: Boolean = true): List<List<Any?>> {
                val (plan, parameters) = compile(operations, join)
                val sql = CalciteSqlRendering.render(plan, support, parameters)
                val aliases = plan.rowType.fieldNames.associateWith { it }
                val request = SourceSqlRequest("execute-query", sql.sql, CalciteSqlRendering.orderedParameters(sql, parameters), aliases, aliases,
                    plan.rowType.fieldList.associate { it.name to SemanticNativeTypes.resultType(it.type) }, emptyList(), emptyList(), null)
                return DeadlineExecutionControl(10000).use { control -> SourceProvider(source, loaded.registry)
                    .execute(request, control, RuntimeDeploymentLimits.result, RuntimeDeploymentLimits.nativeMemoryBytes) { _, _ -> }
                    .frame.rows.map { row -> row.cells.map { it.value } } }
            }
            val displayed = joined + listOf(Project(listOf("id", "days")), Sort(listOf(OrderKey("id", "ASC", "LAST"), OrderKey("days", "ASC", "LAST"))))
            val rows = execute(displayed)
            assertEquals(listOf(1L, 1L, 2L, 3L, 4L, 5L), rows.map { (it[0] as Number).toLong() })
            assertEquals(listOf(-2L, 4L, null, null, null, null), rows.map { (it[1] as? Number)?.toLong() })
            val summed = execute(joined + Regroup(ResultGrouping(emptyList(), listOf(ResultAggregate("days", ResultAggregation.Sum(quantity, ResultAggregation.NullPolicy.IGNORE), "total", "有效贡献之和")), null)))
            assertEquals(2L, (summed.single().single() as Number).toLong())

            val nativeNumber = call("native_float", symbol("id"))
            val rawNumber = listOf(Derive(nativeNumber, "number", "原始行原生数值"))
            assertEquals(15.0, (execute(rawNumber + Regroup(ResultGrouping(emptyList(), listOf(ResultAggregate("number", ResultAggregation.Sum(floating, ResultAggregation.NullPolicy.IGNORE), "total", "行贡献之和")), null)), false).single().single() as Number).toDouble())
            val completedMeans = rawNumber + listOf(
                Regroup(ResultGrouping(listOf("record_key"), listOf(ResultAggregate("number", ResultAggregation.Average(floating, ResultAggregation.NullPolicy.IGNORE, MetricValueContract.Rounding.NONE), "mean", "每组均值")), null)),
                Derive(call("native_float", symbol("mean")), "transformed", "完成均值保留原观察"),
                Regroup(ResultGrouping(emptyList(), listOf(ResultAggregate("transformed", ResultAggregation.Sum(floating, ResultAggregation.NullPolicy.IGNORE), "total", "实际完成均值之和")), null)))
            assertEquals(15.0, (execute(completedMeans, false).single().single() as Number).toDouble())
            val requests = server.queries
            val exact = MetricValueContract(quantity.unit, MetricValueContract.Exact(), MetricValueContract.Decimal(38, 0, MetricValueContract.Rounding.NONE), emptyMap(), quantity.overflow)
            val attempts = listOf(
                listOf(Derive(ResultNumeric(ResultCast(nativeNumber, SemanticExpression.ValueType.DECIMAL, 38, 0, MetricValueContract.Rounding.NONE), exact, null), "washed", "禁止CAST升级")),
                listOf(Derive(ResultNumeric(ResultCaseWhen(listOf(ResultCaseBranch(ResultLiteral(true), nativeNumber)), ResultLiteral(null)), exact, null), "washed", "禁止CASE升级")),
                rawNumber + Regroup(ResultGrouping(emptyList(), listOf(ResultAggregate("number", ResultAggregation.Sum(exact, ResultAggregation.NullPolicy.IGNORE), "washed", "禁止聚合升级")), null)),
                rawNumber + Derive(ResultNumeric(ResultCast(call("number_text", symbol("number")), SemanticExpression.ValueType.DECIMAL, 38, 0, MetricValueContract.Rounding.NONE), exact, null), "washed", "禁止经文本擦除数量"))
            attempts.forEach { assertThrows(IllegalArgumentException::class.java) { compile(it, false) } }
            val mismatch = ResultNativeCall(TestConnectors.ref("duckdb"), "native_float", listOf(symbol("id")))
            val denied = assertThrows(IllegalArgumentException::class.java) { compile(listOf(Derive(mismatch, "wrong", "精确插件不得替换")), false) }
            assertTrue(requireNotNull(denied.message).contains("exact source compilation scope"))
            val tableFunction = ResultNativeCall(ref, "latest_declared_partition", listOf(ResultTypedLiteral(ScalarValueType.TEXT, "main.facts"), ResultTypedLiteral(ScalarValueType.TEXT, "OPEN")))
            assertThrows(IllegalArgumentException::class.java) { compile(listOf(Derive(tableFunction, "wrong", "结果层不引入表来源")), false) }
            assertEquals(requests, server.queries, "合同拒绝不得发出HTTP请求")

            TestConnectors.open("duckdb", spec.jdbcUrl).use { connection -> connection.createStatement().use { it.execute("UPDATE details SET end_text='bad-date' WHERE record_key='multiple'") } }
            val selectedFailure = assertThrows(SourceExecutionException::class.java) { execute(displayed) }
            assertEquals(SourceResponseState.RECEIVED, selectedFailure.failure.request.response)
            assertEquals("500", selectedFailure.failure.request.status, "合格脏日期必须到真实SQL来源失败")
        } }
    }

    @Test fun nativeTableArgumentReadsLiveMetadataInOneSqlAndRequiresItsExactFrozenSource(@TempDir directory: Path) {
        val url = "jdbc:duckdb:${directory.resolve("native-metadata.duckdb")}"
        TestConnectors.open("duckdb", url).use { writer -> writer.createStatement().use { statement ->
            statement.execute("CREATE TABLE request_context(state VARCHAR NOT NULL)")
            statement.execute("INSERT INTO request_context VALUES ('OPEN')")
            statement.execute("CREATE TABLE orders(partition_key VARCHAR, amount DECIMAL(18,2))")
            statement.execute("INSERT INTO orders VALUES ('2026-01-01', 10.00)")
            // 空分区的目录事实不能由业务行的 MAX 推导。
            statement.execute("CREATE TABLE fixture_partitions(table_name VARCHAR, partition_key VARCHAR, state VARCHAR)")
            statement.execute("INSERT INTO fixture_partitions VALUES ('main.orders','2026-01-01','OPEN'),('main.orders','2026-01-03','OPEN')")
            statement.execute("CREATE MACRO fixture_latest_partition(table_arg, state_arg) AS (SELECT MAX(partition_key) FROM fixture_partitions WHERE table_name=table_arg AND state=state_arg)")
            statement.executeQuery("SELECT MAX(partition_key) FROM orders").use { rows -> assertTrue(rows.next()); assertEquals("2026-01-01", rows.getString(1)) }
        } }
        val spec = JdbcConnectionSpec("server-only", TestConnectors.ref("duckdb"), "duckdb", url, null, null, "org.duckdb.DuckDBDriver")
        val jdbc = (TestConnectors.registry.require(spec.connectorRef).access as JdbcAccess).jdbc
        HttpSqlService(jdbc, spec).use { server -> ConnectorPlugins.load(listOf(plugin(directory))).use { loaded ->
            val ref = loaded.registry.references().single()
            val source = SourceConfiguration("remote-http", ref, "duckdb", mapOf("endpoint" to server.endpoint, "consistentChecks" to true), mapOf("timeoutMillis" to 30000), mapOf("authorization" to "OLAP_TEST_HTTP_AUTH"))
            val provider = SourceProvider(source, loaded.registry)
            val table = NativeTableDependency("orders", 1, source.dataSourceId, source.dialectId, JdbcTableConstraints.Table(null, "main", "orders"), nativeEvidence(source, mapOf("partition_key" to "order_partition@1", "amount" to "order_amount@1")))
            val definition = SemanticExpression.NativeCall(ref, "latest_declared_partition", listOf(SemanticExpression.TableArgument("orders", 1),
                SemanticExpression.ScalarArgument(SemanticExpression.Reference(SemanticExpression.ReferenceKind.PHYSICAL_COLUMN, "context_state", 1))))
            val binding = ExecutablePhysicalExecutionBinding(
                bindingId = "context", bindingVersion = "1", eventId = null, eventVersion = null, logicUnitId = null, logicUnitVersion = null,
                logicalTableId = "context", logicalTableVersion = "1", physicalTableId = "request_context", physicalTableVersion = "1",
                dataSourceId = source.dataSourceId, connectorRef = ref, qualifiedTablePath = listOf("main", "request_context"), dialect = source.dialectId,
                catalogName = null, schemaName = "main", tableName = "request_context", sourceConstraints = nativeEvidence(source, mapOf("state" to "context_state@1")),
                snapshotUniqueKeys = emptyList(), nativeTableDependencies = mapOf(table.key() to table), logicUnitBindings = mapOf("latest_partition@1" to definition),
                physicalColumnBindings = mapOf("context_state@1" to "state"), physicalColumnTypes = mapOf("context_state@1" to JdbcColumnType(java.sql.Types.VARCHAR, "VARCHAR", 65536, null, false)))
            val frozen = ProtoBytes.encode(GovernanceProto.toProto(binding))
            val adopted = GovernanceProto.fromProto(ProtoBytes.decode(frozen, com.olap.prototype.wire.governance.ExecutablePhysicalExecutionBinding.getDefaultInstance()))
            val expression = SemanticExpression.Reference(SemanticExpression.ReferenceKind.LOGIC_UNIT, "latest_partition", 1)
            var requests = server.queries
            val plan = CandidateValueCompiler.compile(adopted, expression, 0, 10, mutableListOf(), loaded.registry)
            assertEquals(requests, server.queries, "编译不得向来源预取函数值")
            assertEquals(2, plan.sourceRequirements.size)
            val argument = plan.sourceRequirements.single { it.physicalTableId == "orders" }
            assertEquals("1", argument.physicalTableVersion)
            assertEquals(setOf(JdbcSourceRequirement.Use.NATIVE_TABLE), argument.uses)
            assertTrue(argument.columns.isEmpty(), "参数表不能伪造字段或冒充行来源")
            assertTrue(argument.constraintUses.isEmpty(), "仅作为原生函数参数的表不采用 NOT_NULL 行保证")
            assertTrue(plan.sourceRequirements.any { it.physicalTableId == "request_context" && it.readsRows() && "state" in it.columns })
            assertEquals(source.bindingRef(), JdbcSourceRequirement.requireSingleBinding(plan.sourceRequirements))
            listOf("2026-01-03", "2026-01-04").forEach { expected ->
                DeadlineExecutionControl(10000).use { control ->
                    assertEquals(listOf(expected), provider.execute(plan, control, ResultMaterializationLimits(100, 1000, 100000, 16), 1000000) { _, _ -> }.frame.rows.map { it.cells.first().value })
                }
                assertEquals(++requests, server.queries, "函数与查询必须在同一条来源 SQL 中执行")
                if (expected == "2026-01-03") TestConnectors.open("duckdb", url).use { writer -> writer.createStatement().use { it.execute("INSERT INTO fixture_partitions VALUES ('main.orders','2026-01-04','OPEN')") } }
            }
            TestConnectors.open("duckdb", url).use { writer -> writer.createStatement().use { statement -> statement.executeQuery("SELECT MAX(partition_key) FROM orders").use { rows -> assertTrue(rows.next()); assertEquals("2026-01-01", rows.getString(1), "元数据变化没有制造业务行") } } }
            val call = definition as SemanticExpression.NativeCall
            val unadopted = adopted.copy(logicUnitBindings = adopted.logicUnitBindings + ("latest_partition@1" to SemanticExpression.NativeCall(ref, call.function, listOf(SemanticExpression.TableArgument("orders", 2), call.arguments[1]))))
            val outside = assertThrows(RuntimeException::class.java) { CandidateValueCompiler.compile(unadopted, expression, 0, 10, mutableListOf(), loaded.registry) }
            assertTrue(outside.message!!.contains("orders@2"))
            val wrongSource = SourceConfiguration(source.dataSourceId, ref, source.dialectId, mapOf("endpoint" to server.endpoint, "consistentChecks" to false), source.runtimeParameters, source.credentialEnvironmentVariables)
            val crossed = adopted.copy(nativeTableDependencies = adopted.nativeTableDependencies + (table.key() to NativeTableDependency(table.physicalTableId, table.physicalTableVersion, table.dataSourceId, table.dialect, table.table, nativeEvidence(wrongSource, table.sourceConstraints.columnVersionRefs))))
            val crossBinding = assertThrows(IllegalArgumentException::class.java) { CandidateValueCompiler.compile(crossed, expression, 0, 10, mutableListOf(), loaded.registry) }
            assertTrue(crossBinding.message!!.contains("source binding"))
            assertEquals(requests, server.queries, "未采用版本和跨binding表必须在HTTP请求前拒绝")
            TestConnectors.open("duckdb", url).use { writer -> writer.createStatement().use { statement ->
                statement.execute("ALTER TABLE request_context ALTER COLUMN state DROP NOT NULL")
                statement.execute("UPDATE request_context SET state=NULL")
            } }
            DeadlineExecutionControl(10000).use { control ->
                val failure = assertThrows(SourceExecutionException::class.java) {
                    provider.execute(plan, control, ResultMaterializationLimits(100, 1000, 100000, 16), 1000000) { _, _ -> }
                }
                assertEquals("SOURCE_NOT_NULL_VIOLATION", failure.failure.code)
            }
            assertEquals(requests + 1, server.queries, "冻结的非空类型必须由同一 HTTP 来源请求中的真实见证兑现")
        } }
    }

    @Test fun loadsAnHttpOnlyPluginAndReadsExactLiveValuesWithoutCatalogOrDatabaseCoordinates(@TempDir directory: Path) {
        val url = "jdbc:duckdb:${directory.resolve("service-owned.duckdb")}"
        TestConnectors.open("duckdb", url).use { writer -> writer.createStatement().use { statement ->
            statement.execute("CREATE TABLE amounts(amount DECIMAL(38,2))")
            statement.execute("INSERT INTO amounts VALUES (1234567890123456789012345.67),(NULL)")
        } }
        val serverSpec = JdbcConnectionSpec("server-only", TestConnectors.ref("duckdb"), "duckdb", url, null, null, "org.duckdb.DuckDBDriver")
        val jdbc = (TestConnectors.registry.require(serverSpec.connectorRef).access as JdbcAccess).jdbc
        HttpSqlService(jdbc, serverSpec).use { server -> ConnectorPlugins.load(listOf(plugin(directory))).use { loaded ->
            val ref = loaded.registry.references().single()
            val source = SourceConfiguration("remote-http", ref, "duckdb", mapOf("endpoint" to server.endpoint, "consistentChecks" to true), mapOf("timeoutMillis" to 30000), mapOf("authorization" to "OLAP_TEST_HTTP_AUTH"))
            val provider = SourceProvider(source, loaded.registry)
            listOf(SourceCapabilities.Capability.PARAMETER_BINDING, SourceCapabilities.Capability.TRANSACTION_SNAPSHOT, SourceCapabilities.Capability.STRUCTURE_INSPECTION).forEach { assertFalse(it in provider.capabilities.supported) }
            val scalar = ResultValueType.Scalar("DECIMAL", 38, 2, true)
            val aliases = linkedMapOf("amount" to "amount", "items" to "items", "record" to "record")
            val request = SourceSqlRequest("execute-query", "SELECT amount AS amount, [CAST(NULL AS DECIMAL(38,2)), amount] AS items, ROW(amount, CAST(NULL AS VARCHAR)) AS record FROM amounts ORDER BY amount NULLS LAST", emptyList(), aliases, aliases,
                mapOf("amount" to scalar, "items" to ResultValueType.Array(scalar, true), "record" to ResultValueType.Row(listOf(ResultValueType.Field("amount", scalar), ResultValueType.Field("empty", ResultValueType.Scalar("VARCHAR", 65536, 0, true))), true)), emptyList(), emptyList(), null)
            DeadlineExecutionControl(10000).use { control ->
                val limits = ResultMaterializationLimits(100, 1000, 100000, 16)
                typedLiterals(provider, loaded.registry.require(ref).compilation, control, limits)
                val frame = provider.execute(request, control, limits, 1000000) { _, _ -> }.frame
                assertEquals(BigDecimal("1234567890123456789012345.67"), frame.rows.first()["amount"])
                assertNull(frame.rows[1]["amount"])
                val items = frame.rows.first()["items"] as SqlResultValues.ArrayValue
                assertNull(items.elements.first()); assertEquals(frame.rows.first()["amount"], items.elements[1])
                assertNull((frame.rows.first()["record"] as SqlResultValues.RowValue).fields[1])
                val candidates = provider.execute(SourceSqlRequest("read-candidate-values", "SELECT amount FROM amounts WHERE amount IS NOT NULL", emptyList(), mapOf("amount" to "candidate"), mapOf("amount" to "候选金额"), mapOf("candidate" to scalar), emptyList(), emptyList(), null), control, limits, 1000000) { _, _ -> }.frame.rows.map { it.cells.first().value }
                assertEquals(listOf(BigDecimal("1234567890123456789012345.67")), candidates)
                assertEquals(listOf(1.25), provider.execute(SourceSqlRequest("read-candidate-values", "SELECT CAST(1.25 AS DOUBLE) AS candidate", emptyList(), mapOf("candidate" to "candidate"), mapOf("candidate" to "候选值"), mapOf("candidate" to ResultValueType.Scalar("DOUBLE", 15, null, false)), emptyList(), emptyList(), null), control, limits, 1000000) { _, _ -> }.frame.rows.map { it.cells.first().value })
                TestConnectors.open("duckdb", url).use { writer -> writer.createStatement().use { it.execute("INSERT INTO amounts VALUES (2.01)") } }
                assertEquals(3, provider.execute(request, control, limits, 1000000) { _, _ -> }.frame.rows.size)
            }
            val noSnapshot = SourceProvider(SourceConfiguration(source.dataSourceId, ref, source.dialectId, mapOf("endpoint" to server.endpoint, "consistentChecks" to false), source.runtimeParameters, source.credentialEnvironmentVariables), loaded.registry)
            DeadlineExecutionControl(10000).use { control ->
                val limits = ResultMaterializationLimits(100, 1000, 100000, 16)
                assertEquals(3, noSnapshot.execute(request, control, limits, 1000000) { _, _ -> }.frame.rows.size)
                val checked = SourceSqlRequest(request.operation, request.sql, request.parameters, request.aliasMapping, request.aliasDisplayNames, request.outputTypes, emptyList(), listOf(JdbcRelationCheck("RELATION_CHECK_FAILED", "SELECT 1 WHERE FALSE", emptyList())), null)
                val requestsBeforeRejection = server.queries
                val unavailable = assertThrows(SourceExecutionException::class.java) { noSnapshot.execute(checked, control, limits, 1000000) { _, _ -> }.frame }
                assertEquals(SourceCapabilities.Capability.CONSISTENT_READ.name, unavailable.failure.capability)
                assertEquals("execute-query", unavailable.failure.operation)
                assertEquals(requestsBeforeRejection, server.queries, "能力不足必须在发送 HTTP 前拒绝")
                val rejected = SourceSqlRequest(checked.operation, "SELECT amount FROM amounts WHERE FALSE", emptyList(), mapOf("amount" to "amount"), mapOf("amount" to "amount"), mapOf("amount" to scalar), emptyList(), listOf(JdbcRelationCheck("EMPTY_RESULT_STILL_REQUIRES_CHECK", "SELECT 1", emptyList())), null)
                val violation = assertThrows(SourceExecutionException::class.java) { provider.execute(rejected, control, limits, 1000000) { _, _ -> }.frame }
                assertEquals("EMPTY_RESULT_STILL_REQUIRES_CHECK", violation.failure.code)
            }
            val missing = assertThrows(SourceCapabilityException::class.java) { loaded.registry.require(ref).access.snapshot(WarehouseSchemaSnapshotRequest(source, null, "main", null, emptyList(), 10), RuntimeDeploymentLimits.CONTROL) }
            assertEquals("remote-http", missing.dataSourceId); assertEquals(SourceCapabilities.Capability.STRUCTURE_INSPECTION, missing.capability)
            val evidence = PhysicalSourceConstraints("declared-test", "declared-schema", source.sourceIdentityHash(), ref, null, null, null, null, JdbcTableConstraints(listOf("amount"), emptyList(), emptyList(), emptyList()), mapOf("amount" to "amount@1"), emptyList(), StructureProvenance(StructureProvenance.Kind.DECLARED, "fixture-configuration", "v1"))
            val requirement = JdbcSourceRequirement(source.dataSourceId, ref, source.dialectId, "amounts", "1", JdbcTableConstraints.Table(null, "main", "amounts"), evidence, mapOf("amount" to JdbcColumnType(java.sql.Types.DECIMAL, "DECIMAL", 38, 2, false)), setOf(JdbcSourceRequirement.Use.ROWS), emptyList(), setOf(JdbcSourceRequirement.ConstraintUse(JdbcSourceRequirement.ConstraintKind.PRIMARY_KEY, null)))
            val guarded = SourceSqlRequest(request.operation, request.sql, request.parameters, request.aliasMapping, request.aliasDisplayNames, request.outputTypes, listOf(requirement), emptyList(), null)
            DeadlineExecutionControl(10000).use { control ->
                val rejected = assertThrows(SourceExecutionException::class.java) { provider.execute(guarded, control, ResultMaterializationLimits(100, 1000, 100000, 16), 1000000) { _, _ -> }.frame }
                assertEquals("SOURCE_PRIMARY_KEY_VIOLATION", rejected.failure.code)
            }
        } }
    }

    @Test fun retainsActualHttpResponseFactsWhenLocalDeliveryFailsAndRejectsUnsupportedPinsBeforeSubmission(@TempDir directory: Path) {
        val spec = JdbcConnectionSpec("http-evidence", TestConnectors.ref("duckdb"), "duckdb",
            "jdbc:duckdb:${directory.resolve("http-evidence.duckdb")}", null, null, "org.duckdb.DuckDBDriver")
        TestConnectors.open("duckdb", spec.jdbcUrl).use { connection -> connection.createStatement().use { statement ->
            statement.execute("CREATE TABLE amounts(amount INTEGER)")
            statement.execute("INSERT INTO amounts VALUES (1),(2)")
        } }
        val jdbc = (TestConnectors.registry.require(spec.connectorRef).access as JdbcAccess).jdbc
        HttpSqlService(jdbc, spec).use { server -> ConnectorPlugins.load(listOf(plugin(directory))).use { loaded ->
            val source = SourceConfiguration("http-evidence", loaded.registry.references().single(), "duckdb",
                mapOf("endpoint" to server.endpoint, "consistentChecks" to true), mapOf("timeoutMillis" to 30000), mapOf("authorization" to "OLAP_TEST_HTTP_AUTH"))
            val provider = SourceProvider(source, loaded.registry)
            val types = mapOf("amount" to ResultValueType.Scalar("INTEGER", 10, 0, true))
            val sql = "SELECT amount FROM amounts ORDER BY amount"
            val request = SourceSqlRequest("execute-query", sql, emptyList(), mapOf("amount" to "amount"),
                mapOf("amount" to "金额"), types, emptyList(), emptyList(), null)
            val limits = ResultMaterializationLimits(10, 100, 1000, 4)
            DeadlineExecutionControl(10000).use { control ->
                val complete = provider.execute(request, control, limits, 1000000) { _, _ -> }
                assertEquals(listOf(1, 2), complete.frame.rows.map { it.cells.single().value })
                assertEquals(SourceExecutionState.SUCCEEDED, complete.request.execution)
                assertInstanceOf(SourceReadBasis.Unknown::class.java, complete.readBasis)
                assertFalse(SourceCapabilities.Capability.PINNED_READ in provider.capabilities.supported)

                val incompatible = SourceSqlRequest(request.operation, sql, emptyList(), mapOf("missing_label" to "amount"),
                    mapOf("missing_label" to "金额"), types, emptyList(), emptyList(), null)
                val beforeMaterialization = server.queries
                val materialization = assertThrows(SourceExecutionException::class.java) {
                    provider.execute(incompatible, control, limits, 1000000) { _, _ -> }
                }
                assertEquals(beforeMaterialization + 1, server.queries)
                assertEquals("EXECUTABLE_SOURCE_RESULT_FAILED", materialization.failure.code)
                assertEquals("MATERIALIZE_RESULT", materialization.failure.stage)
                assertEquals(SourceFailureCategory.MATERIALIZATION, materialization.failure.category)
                assertEquals(SourceRequestAcceptance.ACCEPTED, materialization.failure.request.acceptance)
                assertEquals(SourceResponseState.RECEIVED, materialization.failure.request.response)
                assertEquals(SourceExecutionState.SUCCEEDED, materialization.failure.request.execution)
                assertEquals("HTTP", materialization.failure.request.protocol)
                assertEquals("200", materialization.failure.request.status)
                val read = requireNotNull(materialization.readEvidence)
                assertEquals(materialization.failure.request, read.request)
                assertInstanceOf(SourceReadBasis.Unknown::class.java, read.basis)

                val beforeBudget = server.queries
                val budget = assertThrows(SourceExecutionException::class.java) {
                    provider.execute(request, control, ResultMaterializationLimits(1, 100, 1000, 4), 1000000) { _, _ -> }
                }
                assertEquals(beforeBudget + 1, server.queries)
                assertEquals("RESULT_ROWS_EXCEEDED", budget.failure.code)
                assertEquals("READ_RESULT", budget.failure.stage)
                assertEquals(SourceFailureCategory.RESOURCE_LIMIT, budget.failure.category)
                assertEquals(SourceResponseState.RECEIVED, budget.failure.request.response)
                assertEquals(SourceExecutionState.UNKNOWN, budget.failure.request.execution)
                assertEquals("HTTP", budget.failure.request.protocol)
                assertEquals("200", budget.failure.request.status)
                assertNull(budget.readEvidence)

                val pin = SourceSqlRequest(request.operation, sql, emptyList(), request.aliasMapping, request.aliasDisplayNames,
                    types, emptyList(), emptyList(), SourceReadBasis.Versioned("fixture-owner", "requested-version", emptyMap(), SourcePinning.EXACT_VERSION))
                val beforePin = server.queries
                val unsupported = assertThrows(SourceExecutionException::class.java) {
                    provider.execute(pin, control, limits, 1000000) { _, _ -> }
                }
                assertEquals(beforePin, server.queries, "pin能力缺失必须在来源请求发出前拒绝")
                assertEquals("SOURCE_CAPABILITY_MISSING", unsupported.failure.code)
                assertEquals(SourceCapabilities.Capability.PINNED_READ.name, unsupported.failure.capability)
                assertEquals(source.bindingRef(), unsupported.failure.binding)
                assertEquals(SourceRequestAcceptance.NOT_SUBMITTED, unsupported.failure.request.acceptance)
                assertEquals(SourceResponseState.NOT_RECEIVED, unsupported.failure.request.response)
                assertEquals(SourceExecutionState.NOT_SUBMITTED, unsupported.failure.request.execution)
                assertNull(unsupported.readEvidence)
            }
        } }
    }

    private fun typedLiterals(provider: SourceProvider, support: CompilationSupport, control: WarehouseExecutionControl, limits: ResultMaterializationLimits) {
        val builder = CalciteCompilation.builder(support, Frameworks.createRootSchema(true))
        builder.values(arrayOf("seed"), 1)
        val names = listOf("text", "amount", "date", "timestamp", "enabled", "double_zero", "real_zero", "subnormal")
        val expected = listOf("中文😀 '? \\ ; DROP TABLE amounts; --", BigDecimal("1234567890123456789012345.67"), LocalDate.of(2024, 2, 29), LocalDateTime.parse("2024-02-29T12:34:56.123456789"), true, -0.0, -0.0f, Double.MIN_VALUE)
        val factory = builder.typeFactory
        val types = listOf(factory.createSqlType(SqlTypeName.VARCHAR, 128), factory.createSqlType(SqlTypeName.DECIMAL, 38, 2), factory.createSqlType(SqlTypeName.DATE), factory.createSqlType(SqlTypeName.TIMESTAMP, 9), factory.createSqlType(SqlTypeName.BOOLEAN), factory.createSqlType(SqlTypeName.DOUBLE), factory.createSqlType(SqlTypeName.REAL), factory.createSqlType(SqlTypeName.DOUBLE))
        val parameters = mutableListOf<JdbcParameter>()
        val expressions: List<RexNode> = names.indices.map { SemanticCalciteRexCompiler.parameter(builder, parameters, types[it], expected[it]) }
        builder.project(expressions, names, true)
        val plan = builder.build()
        val sql = CalciteSqlRendering.render(plan, support, parameters)
        val aliases = names.associateWith { it }
        val outputs = plan.rowType.fieldList.associate { it.name to SemanticNativeTypes.resultType(it.type) }
        val request = SourceSqlRequest("execute-query", sql.sql, CalciteSqlRendering.orderedParameters(sql, parameters), aliases, aliases, outputs, emptyList(), emptyList(), null)
        val frame = provider.execute(request, control, limits, 1000000) { _, _ -> }.frame
        names.indices.forEach { assertEquals(expected[it], frame.rows.first()[names[it]]) }
    }

    companion object {
        private fun nativeEvidence(source: SourceConfiguration, columns: Map<String, String>) = PhysicalSourceConstraints("declared-http-native", "declared-native-schema-v1", source.sourceIdentityHash(), source.connectorRef, null, null, null, null, JdbcTableConstraints(emptyList(), emptyList(), emptyList(), emptyList()), columns, emptyList(), StructureProvenance(StructureProvenance.Kind.DECLARED, "fixture-native-configuration", "v1"))

        @JvmStatic fun main(args: Array<String>) {
            val input = Path.of(args[0]).toRealPath()
            val output = Files.createDirectory(Path.of(args[1]))
            val configured = OlapJsonUtils.mapper().readValue(input.resolve("data-sources.json").toFile(), Array<SourceConfiguration>::class.java).first()
            val spec = JdbcConnectionSpec.from(configured)
            val manifests = OlapJsonUtils.mapper().readValue(Path.of(args[2]).toFile(), Array<String>::class.java).map(Path::of)
            ConnectorPlugins.load(manifests).use { deployed ->
                val jdbc = (deployed.registry.require(spec.connectorRef).access as JdbcAccess).jdbc
                HttpSqlService(jdbc, spec).use { server ->
                    val manifest = plugin(output)
                    val ref = OlapJsonUtils.mapper().readValue(manifest.toFile(), ConnectorPlugins.Manifest::class.java).connectorRef
                    val source = SourceConfiguration(configured.dataSourceId, ref, "duckdb", mapOf("endpoint" to server.endpoint, "consistentChecks" to true), mapOf("timeoutMillis" to 30000), mapOf("authorization" to "OLAP_TEST_HTTP_AUTH"))
                    OlapJsonUtils.mapper().writeValue(output.resolve("http-sources.json").toFile(), listOf(source))
                    OlapJsonUtils.mapper().writeValue(output.resolve("connectors.json").toFile(), listOf(manifest.toString()))
                    val structures = linkedMapOf<String, SourceStructureRegistrationRequest.Table>()
                    jdbc.openReadOnly(spec, Properties()).use { connection ->
                        mapOf("orders" to "order_id", "payments" to "payment_id", "refunds" to "refund_id", "fees" to "fee_id").forEach { (table, key) ->
                            val columns = connection.createStatement().use { statement -> statement.executeQuery("SELECT * FROM $table LIMIT 0").use { rows ->
                                val metadata = rows.metaData
                                (1..metadata.columnCount).map { i -> WarehouseColumnMetadata(metadata.getColumnName(i), metadata.getColumnType(i), metadata.getColumnTypeName(i), i, metadata.getColumnName(i) != key, metadata.getPrecision(i), metadata.getScale(i), false, false, null, null) }
                            } }
                            structures[table] = SourceStructureRegistrationRequest.Table(WarehouseTableSummary(WarehouseTableRef(null, "main", table), "TABLE", null), columns, JdbcTableConstraints(listOf(key), emptyList(), emptyList(), emptyList()))
                        }
                    }
                    OlapJsonUtils.mapper().writeValue(output.resolve("declared-structure.json").toFile(), structures)
                    Runtime.getRuntime().addShutdownHook(Thread { server.close(); deployed.close() })
                    println("HTTP_FIXTURE_READY ${server.endpoint}")
                    Files.writeString(output.resolve("ready.json"), "{\"ready\":true}\n")
                    CountDownLatch(1).await()
                }
            }
        }

        fun plugin(directory: Path): Path {
            val jar = directory.resolve("http-sql-fixture.jar")
            JarOutputStream(Files.newOutputStream(jar)).use { output -> JarFile(System.getProperty("olap.connector.duckdb.jar")).use { backend ->
                // Kotlin 的 lambda/局部实现也是插件的真实实现字节，按同一顶层类前缀装入。
                val location = Path.of(HttpSqlConnector::class.java.protectionDomain.codeSource.location.toURI())
                val prefix = "com/olap/fixtures/http/HttpSqlConnector"
                fun append(path: String, bytes: ByteArray) { output.putNextEntry(JarEntry(path)); output.write(bytes); output.closeEntry() }
                if (Files.isDirectory(location)) {
                    Files.list(location.resolve("com/olap/fixtures/http")).use { files -> files.filter { it.fileName.toString().startsWith("HttpSqlConnector") && it.fileName.toString().endsWith(".class") }.sorted().forEach { append("com/olap/fixtures/http/${it.fileName}", Files.readAllBytes(it)) } }
                } else JarFile(location.toFile()).use { host -> host.entries().asSequence().filter { it.name.startsWith(prefix) && it.name.endsWith(".class") }.forEach { entry -> host.getInputStream(entry).use { append(entry.name, it.readAllBytes()) } } }
                backend.entries().asSequence().filter { it.name.matches(Regex("com/olap/connectors/duckdb/(DuckdbCompilation|DuckdbNativeTypes|DuckdbSourceTypes|OlapDuckDBSqlDialect)(\\$[^/]*)?\\.class")) }.forEach { entry -> backend.getInputStream(entry).use { append(entry.name, it.readAllBytes()) } }
                append("META-INF/services/${OlapConnector::class.java.name}", "${HttpSqlConnector::class.java.name}\n".toByteArray(Charsets.UTF_8))
            } }
            val content = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar))
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(jar.fileName.toString().toByteArray(Charsets.UTF_8)); digest.update(0.toByte()); digest.update(content)
            val ref = ConnectorRef("http-sql-fixture", "1.0.0", HexFormat.of().formatHex(digest.digest()))
            val manifest = directory.resolve("connector.json")
            OlapJsonUtils.mapper().writeValue(manifest.toFile(), ConnectorPlugins.Manifest(ref, OlapConnector.API_VERSION, listOf(ConnectorPlugins.Artifact(jar.fileName.toString(), HexFormat.of().formatHex(content)))))
            return manifest.toAbsolutePath()
        }
    }
}
