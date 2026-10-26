package com.olap.prototype.engine.executable

import com.olap.prototype.api.ResultFrame
import com.olap.prototype.api.SqlResultValues
import com.olap.prototype.connector.CompilationSupport
import com.olap.prototype.warehouse.WarehouseExecutionControl
import com.olap.prototype.warehouse.ResultMaterializationBudget
import com.olap.prototype.warehouse.ResultMaterializationLimits
import com.olap.prototype.engine.executable.ExecutableBusinessResult.*
import com.olap.prototype.engine.executable.ExecutableBusinessResult.ValueKind.*
import org.apache.calcite.rel.type.RelDataType
import org.apache.calcite.sql.type.SqlTypeName
import java.math.BigDecimal
import java.math.BigInteger
import java.time.*

/** 按编译输出合同核验来源列，再无损物化与核验数量。 */
object ExecutableBusinessResults {
    /** 最终业务值拥有结果预算；来源工作行、原生状态及lookahead不消费此预算。 */
    fun requireBudget(result: ExecutableBusinessResult, limits: ResultMaterializationLimits, control: WarehouseExecutionControl) {
        val budget = ResultMaterializationBudget(limits)
        fun account(value: Value, depth: Int) {
            control.checkCancellation()
            budget.value(depth)
            when (value) {
                is ScalarValue -> budget.scalar(value.value)
                is ArrayValue -> value.value.forEach { account(it, depth + 1) }
                is RowValue -> value.value.forEach { account(it, depth + 1) }
                is NullValue -> Unit
            }
        }
        result.rows.forEach { row -> control.checkCancellation(); budget.row(); row.forEach { account(it, 1) } }
    }
    fun produce(frame: ResultFrame, contract: ExecutableOutputContract, support: CompilationSupport): ExecutableBusinessResult {
        val aliases = contract.columns.map { it.symbol.name }
        check(aliases == frame.columns.map { it.name }) { "source result columns differ from compiled output contract or order" }
        val columns = aliases.indices.map { index ->
            val jdbc = frame.columns[index]; val field = contract.columns[index]
            ExecutableResultTypes.requireJdbc(field.nativeType, requireNotNull(jdbc.jdbcType) { "source SQL type contract is required" }, support)
            ExecutableOutputContract.column("c$index", field, jdbc.displayName)
        }
        val rows = frame.rows.map { row ->
            val cells = linkedMapOf<String, Any?>()
            row.cells.forEach { cell ->
                check(cell.column !in cells && cell.column in aliases) { "result row has duplicate or unknown column" }
                cells[cell.column] = cell.value
            }
            check(cells.keys == aliases.toSet()) { "result row has missing cells" }
            aliases.mapIndexed { index, alias ->
                val nativeType = contract.columns[index].nativeType
                ExecutableResultTypes.requireValue(nativeType, cells[alias])
                value(cells[alias], nativeType)
            }
        }
        return assemble(contract, columns, rows) {}
    }
    internal fun materialized(contract: ExecutableOutputContract, rows: List<List<Value>>, displayNames: Map<String, String>,
        control: WarehouseExecutionControl): ExecutableBusinessResult =
        assemble(contract, contract.columns.mapIndexed { index, field -> ExecutableOutputContract.column("c$index", field, displayNames[field.symbol.name]) }, rows, control::checkCancellation)

    private fun assemble(contract: ExecutableOutputContract, columns: List<Column>, rows: List<List<Value>>, checkCancellation: () -> Unit): ExecutableBusinessResult {
        rows.forEach { values -> checkCancellation(); check(values.size == columns.size) { "materialized result width differs from its output contract" }; ExecutableQuantityValues.require(columns, values) }
        val scopes = contract.scopeInputs.filter { it.scope != contract.outputScope }.associate { requireNotNull(it.scope.externalPath()) to inputColumns(it.fields) }
        val root = contract.scopeInputs.first { it.scope == contract.outputScope }
        return ExecutableBusinessResult(inputColumns(root.fields), scopes, columns, contract.sourceSymbols, rows)
    }
    private fun inputColumns(fields: List<ExecutableOutputContract.Field>): List<Column> = fields.mapIndexed { index, field -> ExecutableOutputContract.column("i$index", field, null) }
    internal fun value(value: Any?, type: RelDataType): Value = when (value) {
        null -> NullValue()
        is SqlResultValues.ArrayValue -> ArrayValue(value.elements.map { value(it, requireNotNull(type.componentType)) })
        is SqlResultValues.RowValue -> RowValue(value.fields.mapIndexed { index, field -> value(field, type.fieldList[index].type) })
        is BigDecimal -> ScalarValue(DECIMAL, value.toPlainString())
        is BigInteger, is Long, is Int, is Short, is Byte -> ScalarValue(if (type.sqlTypeName == SqlTypeName.DECIMAL) DECIMAL else INTEGER, value.toString())
        is Double, is Float -> ScalarValue(FLOAT, value.toString())
        is Boolean -> ScalarValue(BOOLEAN, value.toString())
        is String -> ScalarValue(TEXT, value)
        is LocalDate -> ScalarValue(DATE, value.toString())
        is LocalTime -> ScalarValue(LOCAL_TIME, value.toString())
        is OffsetTime -> ScalarValue(OFFSET_TIME, value.toString())
        is LocalDateTime -> ScalarValue(LOCAL_TIMESTAMP, value.toString())
        is Instant -> ScalarValue(INSTANT, value.toString())
        is OffsetDateTime -> ScalarValue(OFFSET_TIMESTAMP, value.toString())
        else -> error("unsupported result value type: ${value.javaClass.name}")
    }
}
