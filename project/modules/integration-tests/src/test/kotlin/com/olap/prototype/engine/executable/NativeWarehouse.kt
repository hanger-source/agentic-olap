package com.olap.prototype.engine.executable

import com.olap.prototype.api.ResultFrame
import com.olap.prototype.connector.JdbcAccess
import com.olap.prototype.connector.SourceProvider
import com.olap.prototype.connector.SourceSqlRequest
import com.olap.prototype.contract.JdbcColumnType
import com.olap.prototype.engine.catalog.ExecutablePhysicalExecutionBinding
import com.olap.prototype.expression.SemanticExpression
import com.olap.prototype.integration.RuntimeDeploymentLimits
import com.olap.prototype.integration.TestConnectors
import com.olap.prototype.util.OlapJsonUtils
import com.olap.prototype.warehouse.DeadlineExecutionControl
import com.olap.prototype.warehouse.contract.*
import com.olap.prototype.warehouse.jdbc.JdbcConnectionFactory
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.util.HexFormat
import java.util.UUID

/** 真正数据库及完整观察证据；逻辑资产和业务预期由试验本身声明。 */
internal class NativeWarehouse(directory: Path, dialect: String, statements: List<String>) : AutoCloseable {
    val spec: JdbcConnectionSpec
    var connection: Connection
        private set
    init {
        val url = when (dialect) {
            "duckdb" -> "jdbc:duckdb:${directory.resolve("source-${UUID.randomUUID()}.duckdb")}"
            "h2" -> "jdbc:h2:mem:native_${UUID.randomUUID()};DATABASE_TO_UPPER=false"
            else -> throw IllegalArgumentException("test database is not declared: $dialect")
        }
        spec = JdbcConnectionSpec("source", TestConnectors.ref(dialect), dialect, url, null, null,
            if (dialect == "duckdb") "org.duckdb.DuckDBDriver" else "org.h2.Driver")
        connection = TestConnectors.open(dialect, url)
        connection.createStatement().use { ddl -> statements.forEach { ddl.execute(it) } }
    }
    fun adopt(table: String, logicUnits: Map<String, String>): ExecutablePhysicalExecutionBinding {
        val metadata = connection.metaData
        val coordinate = JdbcTableConstraints.Table(connection.catalog, connection.schema, table)
        val constraints = (TestConnectors.registry.require(spec.connectorRef).access as JdbcAccess).jdbc
            .constraints(connection, coordinate, RuntimeDeploymentLimits.CONTROL)
        val refs = linkedMapOf<String, String>()
        val names = linkedMapOf<String, String>()
        val types = linkedMapOf<String, JdbcColumnType>()
        metadata.getColumns(coordinate.catalog, coordinate.schema, table, null).use { rows ->
            while (rows.next()) {
                val name = rows.getString("COLUMN_NAME")
                val ref = "$table:$name@1"
                val precision = rows.getInt("COLUMN_SIZE").let { if (rows.wasNull()) null else it }
                val scale = rows.getInt("DECIMAL_DIGITS").let { if (rows.wasNull()) null else it }
                refs[name] = ref; names[ref] = name
                types[ref] = JdbcColumnType(rows.getInt("DATA_TYPE"), rows.getString("TYPE_NAME"), precision, scale,
                    rows.getInt("NULLABLE") == DatabaseMetaData.columnNullable)
            }
        }
        val expressions = logicUnits.map { (id, name) ->
            require(name in refs) { "declared logical input has no actual column: $id" }
            "$id@1" to SemanticExpression.Reference(SemanticExpression.ReferenceKind.PHYSICAL_COLUMN, "$table:$name", 1)
        }.toMap()
        val hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
            OlapJsonUtils.mapper().writeValueAsBytes(mapOf("columns" to types, "constraints" to constraints))))
        val contract = PhysicalDataContract(PhysicalTableRef(table, 1), spec.dataSourceId, coordinate,
            refs.mapValues { (name, ref) -> PhysicalDataContract.Column(ref, types.getValue(ref)) }, constraints, emptyList(), emptyList(), "native-complete-table")
        val evidence = PhysicalSourceEvidence("native-complete-description", "native-complete-table", hash,
            StructureProvenance(StructureProvenance.Kind.DATABASE_CATALOG, "integration-test-catalog", "fixture-v1"),
            metadata.databaseProductName, metadata.databaseProductVersion, metadata.driverName, metadata.driverVersion,
            refs.mapValues { (_, ref) -> types.getValue(ref) }, constraints, emptyList(), emptyList())
        return ExecutablePhysicalExecutionBinding(table, "1", null, null, null, null, table, "1",
            TestConnectors.registry.require(spec.connectorRef).compilation.tablePath(coordinate.catalog, coordinate.schema, table),
            contract, TestConnectors.adoption(contract, spec.source(), evidence),
            logicUnitBindings = expressions, physicalColumnBindings = names, physicalColumnTypes = types)
    }
    fun finishSetup() {
        val factory = JdbcConnectionFactory(TestConnectors.registry)
        if (spec.dialectId == "duckdb") {
            connection.close(); connection = factory.openReadOnly(spec)
        } else {
            val reader = factory.openReadOnly(spec); connection.close(); connection = reader
        }
    }
    fun execute(compiled: ExecutableLoweringResult): List<List<Any?>> {
        val frame = frame(compiled)
        val result = ExecutableBusinessResults.produce(frame, compiled.outputLayout.scopes.values.single().contract, compiled.compilationSupport)
        ExecutableBusinessResults.requireBudget(result, RuntimeDeploymentLimits.result, RuntimeDeploymentLimits.CONTROL)
        return frame.rows.map { row -> row.cells.map { it.value } }
    }
    fun business(compiled: ExecutableLoweringResult): ExecutableBusinessResult =
        ExecutableBusinessResults.produce(frame(compiled), compiled.outputLayout.scopes.values.single().contract, compiled.compilationSupport)
            .also { ExecutableBusinessResults.requireBudget(it, RuntimeDeploymentLimits.result, RuntimeDeploymentLimits.CONTROL) }

    private fun frame(compiled: ExecutableLoweringResult): ResultFrame = DeadlineExecutionControl(120_000).use { control ->
        SourceProvider(spec.source(), TestConnectors.registry).execute(compiled.toSourceRequest("execute-query", null),
            control, RuntimeDeploymentLimits.nativeMemoryBytes) { _, _ -> }.frame
    }
    override fun close() { connection.close() }
}
