package com.olap.prototype.engine.executable

import com.olap.prototype.api.ResultFrame
import com.olap.prototype.api.ResultValueType
import com.olap.prototype.api.SqlResultValues
import com.olap.prototype.connector.SourceProvider
import com.olap.prototype.connector.SourceSqlRequest
import com.olap.prototype.integration.RuntimeDeploymentLimits
import com.olap.prototype.integration.TestConnectors
import com.olap.prototype.connector.SourceExecutionException
import com.olap.prototype.warehouse.contract.SourceCancellationEvidence
import com.olap.prototype.warehouse.contract.SourceExecutionState
import com.olap.prototype.warehouse.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

class RuntimeResourceExecutionTest {
    private val nestedType = ResultValueType.Array(ResultValueType.Row(listOf(
        ResultValueType.Field("C1", ResultValueType.Scalar("INTEGER", 10, 0, false)),
        ResultValueType.Field("C2", ResultValueType.Scalar("VARCHAR", null, null, true))), false), false)

    private fun read(provider: SourceProvider, sql: String, aliases: Map<String, String>, limits: ResultMaterializationLimits,
        types: Map<String, ResultValueType>, control: WarehouseExecutionControl = RuntimeDeploymentLimits.CONTROL): ResultFrame =
        provider.execute(SourceSqlRequest("execute-query", sql, emptyList(), aliases, aliases, types, emptyList(), emptyList(), null),
            control, limits, RuntimeDeploymentLimits.nativeMemoryBytes) { _, _ -> }.frame

    @Test fun materializesCompleteNestedValuesOrRejectsTheirActualCapacity(@TempDir directory: Path) {
        for (dialect in listOf("h2", "duckdb")) NativeWarehouse(directory, dialect, listOf(
            "CREATE TABLE observations(id INTEGER, label VARCHAR)", "INSERT INTO observations VALUES (1,'é'),(2,NULL),(3,'é')")).use { source ->
            source.finishSetup()
            val provider = SourceProvider(source.spec.source(), TestConnectors.registry)
            val nested = "SELECT ARRAY[ROW(1,'é'),ROW(1,CAST(NULL AS VARCHAR))] AS payload"
            val types = mapOf("payload" to nestedType)
            val complete = read(provider, nested, mapOf("payload" to "payload"), ResultMaterializationLimits(1, 7, 4, 3), types)
            val array = complete.rows.first().cells.first().value as SqlResultValues.ArrayValue
            assertEquals(2, array.elements.size)
            val first = array.elements[0] as SqlResultValues.RowValue
            val second = array.elements[1] as SqlResultValues.RowValue
            assertEquals(1, (first.fields[0] as Number).toInt()); assertEquals("é", first.fields[1])
            assertEquals(1, (second.fields[0] as Number).toInt()); assertNull(second.fields[1])
            val pressures = mapOf("RESULT_VALUES_EXCEEDED" to ResultMaterializationLimits(1, 6, 100, 4),
                "RESULT_BYTES_EXCEEDED" to ResultMaterializationLimits(1, 100, 3, 4),
                "RESULT_DEPTH_EXCEEDED" to ResultMaterializationLimits(1, 100, 100, 2))
            pressures.forEach { (expected, limits) ->
                assertEquals(expected, assertThrows(SourceExecutionException::class.java, {
                    read(provider, nested, mapOf("payload" to "payload"), limits, types)
                }, dialect).failure.code)
            }
            val records = "SELECT id, label FROM observations ORDER BY id"
            val aliases = mapOf("id" to "id", "label" to "label")
            val output = TestConnectors.sqlOutputTypes(source.spec, records, aliases, mapOf("id" to true, "label" to true))
            assertEquals("RESULT_ROWS_EXCEEDED", assertThrows(SourceExecutionException::class.java) {
                read(provider, records, aliases, ResultMaterializationLimits(2, 100, 100, 4), output)
            }.failure.code)
            val after = read(provider, records, aliases, ResultMaterializationLimits(3, 6, 7, 1), output)
            assertEquals(listOf("é", null, "é"), after.rows.map { it.cells[1].value })
        }
    }

    @Test fun reportsFailedMaterializationAndRollbackBeforeAcceptingAnotherRead(@TempDir directory: Path) {
        for (dialect in listOf("h2", "duckdb")) NativeWarehouse(directory, dialect, listOf(
            "CREATE TABLE observations(id INTEGER)", "INSERT INTO observations VALUES (1),(2),(3)")).use { source ->
            source.finishSetup()
            val provider = SourceProvider(source.spec.source(), TestConnectors.registry)
            val sql = "SELECT id FROM observations ORDER BY id"
            val aliases = mapOf("id" to "id")
            val types = TestConnectors.sqlOutputTypes(source.spec, sql, aliases, mapOf("id" to true))
            val stages = mutableListOf<Pair<String, Long>>()
            val request = SourceSqlRequest("execute-query", sql, emptyList(), aliases, aliases, types, emptyList(), emptyList(), null)
            val failure = assertThrows(SourceExecutionException::class.java) {
                provider.execute(request, RuntimeDeploymentLimits.CONTROL, ResultMaterializationLimits(2, 10, 100, 1),
                    RuntimeDeploymentLimits.nativeMemoryBytes) { name, nanos -> stages += name to nanos }
            }
            assertEquals("RESULT_ROWS_EXCEEDED", failure.failure.code)
            val names = stages.map { it.first }
            assertTrue(names.indexOf("JDBC_EXECUTE_QUERY") >= 0, dialect)
            assertTrue(names.indexOf("JDBC_MATERIALIZE") > names.indexOf("JDBC_EXECUTE_QUERY"), dialect)
            assertTrue(names.indexOf("JDBC_ROLLBACK") > names.indexOf("JDBC_MATERIALIZE"), dialect)
            assertTrue(stages.all { it.second >= 0 }, dialect)
            val after = read(provider, sql, aliases, ResultMaterializationLimits(3, 10, 100, 1), types)
            assertEquals(listOf(1, 2, 3), after.rows.map { (it.cells.single().value as Number).toInt() }, dialect)
        }
    }

    /** 进程归Go试验；此处独立观察真实驱动取消调用和取消后的可继续访问。 */
    @Test @Timeout(8) fun cancelsActiveNativeStatementsAndKeepsLaterAccessUsable(@TempDir directory: Path) {
        for (dialect in listOf("h2", "duckdb")) NativeWarehouse(directory, dialect, listOf("CREATE TABLE singleton(id INTEGER)")).use { source ->
            source.finishSetup()
            val provider = SourceProvider(source.spec.source(), TestConnectors.registry)
            fun range(size: Long) = if (dialect == "h2") "SYSTEM_RANGE(1, $size) n(x)" else "range(1, $size) n(x)"
            val warm = "SELECT SUM(SIN(x)) AS total FROM ${range(1000)}"
            val aliases = mapOf("total" to "total")
            val types = TestConnectors.sqlOutputTypes(source.spec, warm, aliases, mapOf("total" to true))
            read(provider, warm, aliases, RuntimeDeploymentLimits.result, types)
            val calls = AtomicInteger()
            DeadlineExecutionControl(500).use { deadline ->
                val observed = object : WarehouseExecutionControl {
                    override fun cancellationEvidence() = deadline.cancellationEvidence()
                    override fun checkCancellation() = deadline.checkCancellation()
                    override fun registerCancellationAction(action: WarehouseExecutionControl.CancellationAction): WarehouseExecutionControl.Registration =
                        deadline.registerCancellationAction { calls.incrementAndGet(); action.cancel() }
                }
                val long = "SELECT SUM(SIN(x)) AS total FROM ${range(1_000_000_000)}"
                val interrupted = assertThrows(SourceExecutionException::class.java) {
                    read(provider, long, aliases, RuntimeDeploymentLimits.result, types, observed)
                }
                assertNotEquals(SourceExecutionState.CANCELLED, interrupted.failure.request.execution)
                assertFalse(interrupted.failure.cancellation is SourceCancellationEvidence.NotRequested)
                assertFalse(interrupted.failure.cancellation is SourceCancellationEvidence.Confirmed)
            }
            assertTrue(calls.get() > 0, "实际驱动取消动作必须被调用：$dialect")
            val afterSql = "SELECT SUM(x) AS total FROM ${range(if (dialect == "h2") 3 else 4)}"
            val after = read(provider, afterSql, aliases, RuntimeDeploymentLimits.result,
                TestConnectors.sqlOutputTypes(source.spec, afterSql, aliases, mapOf("total" to true)))
            assertEquals(6, (after.rows.first().cells.first().value as Number).toInt())
        }
    }
}
