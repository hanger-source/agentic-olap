package com.olap.fixtures.http

import com.olap.prototype.api.*
import com.olap.prototype.connector.*
import com.olap.prototype.contract.*
import com.olap.prototype.expression.NativeScalarFunction
import com.olap.prototype.query.ScalarValueType
import com.olap.prototype.warehouse.*
import com.olap.prototype.warehouse.contract.*
import org.apache.calcite.sql.*
import org.apache.calcite.sql.parser.SqlParserPos
import org.apache.calcite.sql.type.OperandTypes
import org.apache.calcite.sql.type.ReturnTypes
import org.apache.calcite.sql.type.SqlTypeName
import java.io.*
import java.math.BigDecimal
import java.math.BigInteger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.sql.Types
import java.time.*

/** HTTP 插件仅复用纯 SQL 映射，组内不带 JDBC provider 或驱动。 */
class HttpSqlConnector : OlapConnector {
    override val id = "http-sql-fixture"
    override val version = "1.0.0"
    override val apiVersion = OlapConnector.API_VERSION
    override val compilation: CompilationSupport get() {
        val constructor = Class.forName("com.olap.connectors.duckdb.DuckdbCompilation").getDeclaredConstructor().apply { isAccessible = true }
        val delegate = constructor.newInstance() as CompilationSupport
        return object : CompilationSupport by delegate {
            override val parameterSubmission = CompilationSupport.ParameterSubmission.TYPED_LITERALS
            override val nativeScalarFunctions = listOf(NativeScalarFunction("latest_declared_partition", listOf(
                NativeScalarFunction.Parameter(NativeScalarFunction.ParameterKind.PHYSICAL_TABLE, null), NativeScalarFunction.Parameter(NativeScalarFunction.ParameterKind.SCALAR, ScalarValueType.TEXT)),
                NativeValueType("VARCHAR", 65536, null, true), null, NativeScalarFunction.Stability.STATEMENT))
            override fun requireNativeScalarFunction(id: String): NativeScalarFunction = nativeScalarFunctions.firstOrNull { it.id == id } ?: throw IllegalArgumentException("native scalar function is not declared by the exact connector: $id")
            override fun nativeScalarCall(function: String, arguments: List<CompilationSupport.NativeArgument>): SqlNode {
                require(function == "latest_declared_partition") { "undeclared fixture native function" }
                val table = arguments[0] as CompilationSupport.TableArgument
                val state = arguments[1] as CompilationSupport.ScalarArgument
                val pos = SqlParserPos.ZERO
                return SqlFunction("fixture_latest_partition", SqlKind.OTHER_FUNCTION, ReturnTypes.ARG0, null, OperandTypes.VARIADIC, SqlFunctionCategory.USER_DEFINED_FUNCTION)
                    .createCall(pos, SqlLiteral.createCharString(table.qualifiedPath.joinToString("."), pos), state.value)
            }
        }
    }
    override fun access(identity: ConnectorRef): SourceAccess = Access(identity, compilation)
    class Access(private val identity: ConnectorRef, private val compilation: CompilationSupport) : SourceAccess {
        override val configurationParameters = SourceConfigurationParameters(setOf("endpoint", "consistentChecks"), setOf("timeoutMillis"))
        private val readBasis = SourceReadBasis.Unknown("HTTP fixture did not return a source version or partition receipt")
        private val client = HttpClient.newHttpClient()
        override fun validateConfiguration(source: SourceConfiguration) {
            configurationParameters.requireConfiguration(source)
            require(source.identityParameters["consistentChecks"] is Boolean) { "HTTP consistent-check contract must be explicitly boolean" }
            require(identity == source.connectorRef && source.identityParameters.keys == setOf("endpoint", "consistentChecks") &&
                source.runtimeParameters.keys == setOf("timeoutMillis") && source.credentialEnvironmentVariables.keys == setOf("authorization")) {
                "explicit HTTP endpoint, consistent-check contract, request timeout and authorization reference required"
            }
            val endpoint = URI.create(source.identityParameters["endpoint"] as String)
            require(endpoint.scheme == "http" && endpoint.host == "127.0.0.1") { "fixture endpoint must be local HTTP" }
            timeoutMillis(source)
        }
        private fun timeoutMillis(source: SourceConfiguration): Long {
            val value = source.runtimeParameters["timeoutMillis"]
            require(value is Number) { "HTTP timeoutMillis must be an explicit positive integer" }
            val millis = value.toLong()
            require(millis > 0 && BigDecimal(value.toString()).compareTo(BigDecimal.valueOf(millis)) == 0) {
                "HTTP timeoutMillis must be an explicit positive integer within the signed 64-bit range"
            }
            return millis
        }
        override fun capabilities(source: SourceConfiguration): SourceCapabilities {
            validateConfiguration(source)
            return SourceCapabilities(if (source.identityParameters["consistentChecks"] == true) setOf(SourceCapabilities.Capability.CONSISTENT_READ, SourceCapabilities.Capability.CONSTRAINT_VERIFICATION) else emptySet())
        }
        override fun supportsConstraintVerification(evidence: PhysicalSourceConstraints): Boolean = true
        private fun constraints(source: SourceConfiguration, requirements: List<JdbcSourceRequirement>): List<JdbcRelationCheck> = requirements.flatMap { requirement -> requirement.constraintUses.map { use ->
            if (use.kind != JdbcSourceRequirement.ConstraintKind.PRIMARY_KEY) throw SourceCapabilityException(source.dataSourceId, "verify-constraint/${use.kind}", SourceCapabilities.Capability.CONSTRAINT_VERIFICATION)
            val keys = requirement.evidence.constraints.primaryKeyColumns
            check(keys.isNotEmpty()) { "required primary key declaration is absent" }
            val quoted = keys.map(compilation.dialect::quoteIdentifier)
            val table = requirement.table
            val path = compilation.tablePath(table.catalog, table.schema, table.table).map(compilation.dialect::quoteIdentifier)
            JdbcRelationCheck("SOURCE_PRIMARY_KEY_VIOLATION", "SELECT 1 FROM ${path.joinToString(".")} GROUP BY ${quoted.joinToString(",")} HAVING COUNT(*) > 1 OR ${quoted.joinToString(" OR ") { "$it IS NULL" }} LIMIT 1", emptyList())
        } }
        override fun execute(source: SourceConfiguration, request: SourceSqlRequest, control: WarehouseExecutionControl, budget: ResultMaterializationBudget, nativeMemoryBytes: Long, stageNanos: (String, Long) -> Unit): SourceExecutionResult {
            val response = try {
                request.requireCapabilities(source.dataSourceId, capabilities(source))
                query(source, request.operation, request.sql, request.parameters, constraints(source, request.sourceRequirements) + request.relationChecks, control, budget)
            } catch (failure: Exception) {
                throw SourceExecutionFailures.exception(source.bindingRef(), request.operation, "PREPARE_SOURCE", failure, control.cancellationEvidence(),
                    SourceRequestEvidence(null, SourceRequestAcceptance.NOT_SUBMITTED, SourceResponseState.NOT_RECEIVED,
                        SourceExecutionState.NOT_SUBMITTED, "HTTP", null), null)
            }
            try {
                val columns = response.labels.map { label ->
                    val alias = requireNotNull(request.aliasMapping[label])
                    val type = declaredType(requireNotNull(request.outputTypes[alias]))
                    ResultColumn(alias, request.aliasDisplayNames[label], label, type.typeName, type)
                }
                return SourceExecutionResult(ResultFrame(columns, response.values.mapIndexed { index, values -> ResultRow(index, columns.indices.map { ResultCell(columns[it].name, values[it]) }) }),
                    readBasis, response.request)
            } catch (failure: Exception) {
                throw SourceExecutionFailures.exception(source.bindingRef(), request.operation, "MATERIALIZE_RESULT", SourceResultMaterializationException(failure),
                    control.cancellationEvidence(), response.request, SourceReadEvidence(source.bindingRef(), readBasis, response.request))
            }
        }
        private fun declaredType(type: ResultValueType): JdbcColumnType = when (type) {
            is ResultValueType.Array -> JdbcColumnType(Types.ARRAY, "ARRAY", null, null, type.nullable)
            is ResultValueType.Row -> JdbcColumnType(Types.STRUCT, "ROW", null, null, type.nullable)
            is ResultValueType.Scalar -> JdbcColumnType(when (type.name) { "HUGEINT" -> Types.OTHER; "TIMESTAMP_NS", "TIMESTAMP_MS", "TIMESTAMP_S" -> Types.TIMESTAMP; else -> SqlTypeName.valueOf(type.name).jdbcOrdinal }, type.name, type.precision, type.scale, type.nullable)
        }
        private data class Response(val labels: List<String>, val values: List<List<Any?>>, val request: SourceRequestEvidence)
        private fun query(source: SourceConfiguration, operation: String, sql: String, parameters: List<JdbcParameter>, checks: List<JdbcRelationCheck>, control: WarehouseExecutionControl, budget: ResultMaterializationBudget): Response {
            var receipt = SourceRequestEvidence(null, SourceRequestAcceptance.NOT_SUBMITTED, SourceResponseState.NOT_RECEIVED, SourceExecutionState.NOT_SUBMITTED, "HTTP", null)
            var stage = "PREPARE_SOURCE"
            try {
                control.checkCancellation()
                if (parameters.isNotEmpty() || checks.any { it.parameters.isNotEmpty() }) throw SourceCapabilityException(source.dataSourceId, "execute-sql-text", SourceCapabilities.Capability.PARAMETER_BINDING)
                val violations = if (checks.isEmpty()) "SELECT CAST(NULL AS VARCHAR) AS code WHERE FALSE" else checks.joinToString(" UNION ALL ") { "SELECT ${compilation.dialect.quoteStringLiteral(it.code)} AS code WHERE EXISTS (${it.sql})" }
                val submitted = "WITH \"__http_result\" AS (SELECT ROW_NUMBER() OVER () AS \"__http_seq\", t.* FROM ($sql) t), \"__http_violations\" AS ($violations) " +
                    "SELECT 0 AS \"__http_seq\", v.code AS \"__http_violation\", r.* EXCLUDE (\"__http_seq\") FROM \"__http_violations\" v LEFT JOIN \"__http_result\" r ON FALSE UNION ALL " +
                    "SELECT r.\"__http_seq\", CAST(NULL AS VARCHAR), r.* EXCLUDE (\"__http_seq\") FROM \"__http_result\" r ORDER BY 1"
                val secret = System.getenv(source.credentialEnvironmentVariables.getValue("authorization")) ?: throw IllegalArgumentException("HTTP credential unavailable")
                val request = HttpRequest.newBuilder(URI.create(source.identityParameters["endpoint"] as String))
                    .timeout(Duration.ofMillis(timeoutMillis(source))).header("Authorization", secret).header("Content-Type", "text/plain; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(submitted, UTF_8)).build()
                stage = "EXECUTE_SQL"
                receipt = SourceRequestEvidence(null, SourceRequestAcceptance.SUBMITTED, SourceResponseState.NOT_RECEIVED, SourceExecutionState.UNKNOWN, "HTTP", null)
                val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
                stage = "READ_RESULT"
                receipt = receipt.copy(response = SourceResponseState.RECEIVED, status = response.statusCode().toString())
                DataInputStream(response.body()).use { input ->
                    check(response.statusCode() == 200) { "HTTP query rejected: ${response.statusCode()}" }
                    val count = input.readInt()
                    check(count in 3..1026) { "invalid HTTP result columns" }
                    if (readText(input) != "__http_seq" || readText(input) != "__http_violation") throw IOException("missing atomic check outcome")
                    val labels = (2 until count).map { readText(input) }
                    val rows = mutableListOf<List<Any?>>()
                    var previous = 0L
                    while (input.readBoolean()) {
                        control.checkCancellation(); budget.row()
                        val sequence = readValue(input, budget, 1) as Number
                        val violation = readValue(input, budget, 1)
                        if (sequence.toLong() == 0L) {
                            if (violation !is String) throw IOException("invalid guarantee outcome")
                            stage = "VERIFY_SOURCE"
                            throw SourceGuaranteeException(violation)
                        }
                        if (sequence.toLong() <= previous || violation != null) throw IOException("invalid ordered result outcome")
                        previous = sequence.toLong()
                        rows += (2 until count).map { readValue(input, budget, 1) }
                    }
                    receipt = receipt.copy(acceptance = SourceRequestAcceptance.ACCEPTED, execution = SourceExecutionState.SUCCEEDED)
                    return Response(labels, rows, receipt)
                }
            } catch (failure: Exception) {
                if (failure is InterruptedException) Thread.currentThread().interrupt()
                throw SourceExecutionFailures.exception(source.bindingRef(), operation, stage, failure, control.cancellationEvidence(), receipt,
                    if (receipt.execution == SourceExecutionState.SUCCEEDED) SourceReadEvidence(source.bindingRef(), readBasis, receipt) else null)
            }
        }
        override fun close() = client.close()
    }
    companion object {
        fun writeText(output: DataOutputStream, value: String) {
            val encoded = UTF_8.newEncoder().encode(CharBuffer.wrap(value))
            val bytes = ByteArray(encoded.remaining()); encoded.get(bytes); output.writeInt(bytes.size); output.write(bytes)
        }
        fun readText(input: DataInputStream): String {
            val size = input.readInt()
            if (size < 0 || size > 32 * 1024 * 1024) throw IOException("HTTP fixture text exceeds its wire limit")
            val bytes = input.readNBytes(size)
            if (bytes.size != size) throw EOFException("truncated HTTP fixture text")
            return UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
        }
        fun writeValue(output: DataOutputStream, value: Any?) {
            when (value) {
                null -> output.writeByte(0)
                is SqlResultValues.ArrayValue -> { output.writeByte(1); output.writeInt(value.elements.size); value.elements.forEach { writeValue(output, it) } }
                is SqlResultValues.RowValue -> { output.writeByte(2); output.writeInt(value.fields.size); value.fields.forEach { writeValue(output, it) } }
                else -> { output.writeByte(3); writeText(output, value.javaClass.simpleName); writeText(output, value.toString()) }
            }
        }
        fun readValue(input: DataInputStream, budget: ResultMaterializationBudget, depth: Int): Any? {
            budget.value(depth)
            val tag = input.readByte().toInt()
            if (tag == 0) return null
            if (tag == 1 || tag == 2) {
                val count = input.readInt()
                if (count < 0) throw IOException("negative collection size")
                budget.requireMinimumValues(count.toLong())
                val elements = (0 until count).map { readValue(input, budget, depth + 1) }
                return if (tag == 1) SqlResultValues.ArrayValue(elements) else SqlResultValues.RowValue(elements)
            }
            if (tag != 3) throw IOException("unknown value tag")
            val type = readText(input); val text = readText(input); budget.scalar(text)
            return when (type) {
                "BigDecimal" -> BigDecimal(text); "BigInteger" -> BigInteger(text); "Long" -> text.toLong(); "Integer" -> text.toInt(); "Short" -> text.toShort(); "Byte" -> text.toByte()
                "Double" -> text.toDouble(); "Float" -> text.toFloat(); "Boolean" -> text.toBoolean(); "String" -> text
                "LocalDate" -> LocalDate.parse(text); "LocalTime" -> LocalTime.parse(text); "OffsetTime" -> OffsetTime.parse(text); "LocalDateTime" -> LocalDateTime.parse(text); "OffsetDateTime" -> OffsetDateTime.parse(text)
                else -> throw IOException("unsupported typed HTTP value")
            }
        }
    }
}
