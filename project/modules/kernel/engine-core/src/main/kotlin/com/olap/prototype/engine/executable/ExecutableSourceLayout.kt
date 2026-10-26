package com.olap.prototype.engine.executable

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import com.olap.prototype.api.ResultFrame
import com.olap.prototype.api.ResultValueType
import com.olap.prototype.connector.CompilationSupport
import com.olap.prototype.expression.SemanticNativeTypes
import com.olap.prototype.query.QueryStageAnalysis.ScopeId
import com.olap.prototype.warehouse.WarehouseExecutionControl
import org.apache.calcite.rel.type.RelDataType
import org.apache.calcite.sql.type.SqlTypeName
import org.apache.calcite.sql.type.SqlTypeUtil
import java.util.Collections

/** 一次来源SQL的物理列布局；完成作用域仍各自持有真实语义输出合同。 */
class ExecutableSourceLayout(fields: List<Field>, scopes: Map<ScopeId, Scope>) {
    val fields: List<Field> = java.util.List.copyOf(fields)
    val scopes: Map<ScopeId, Scope> = Collections.unmodifiableMap(LinkedHashMap(scopes))

    data class Field(val name: String, @get:JsonIgnore val nativeType: RelDataType) {
        init { require(name.isNotBlank()) { "source physical column name must be explicit" } }
        @get:JsonProperty("nativeTypeDescription") val nativeTypeDescription: String get() = nativeType.fullTypeString
        @get:JsonProperty("type") val type: ResultValueType get() = SemanticNativeTypes.resultType(nativeType)
    }

    class Scope(val contract: ExecutableOutputContract, columns: Map<String, String>, val presenceColumn: String?) {
        val columns: Map<String, String> = Collections.unmodifiableMap(LinkedHashMap(columns))
        init {
            require(this.columns.keys.toList() == contract.columns.map { it.symbol.name }) { "source layout must map every actual scope output in order" }
            require(this.columns.values.all(String::isNotBlank) && this.columns.values.distinct().size == this.columns.size) { "scope outputs require distinct physical columns" }
            require(presenceColumn == null || presenceColumn.isNotBlank() && presenceColumn !in this.columns.values) { "source presence must be a separate physical column" }
        }
    }

    init {
        require(this.fields.isNotEmpty() && this.fields.map { it.name }.distinct().size == this.fields.size) { "source physical columns must be nonempty and distinct" }
        require(this.scopes.isNotEmpty()) { "source layout requires actual completed scopes" }
        val physical = this.fields.associateBy { it.name }
        val payload = this.scopes.values.flatMap { it.columns.values }
        val values = payload.toSet()
        val presence = this.scopes.values.mapNotNull { it.presenceColumn }
        require(payload.distinct().size == payload.size) { "completed scopes require independent physical payloads" }
        require(presence.distinct().size == presence.size && presence.none(values::contains)) { "source presence columns must be independent of scope payloads" }
        require(physical.keys == values + presence) { "source layout must contain only actual scope payloads and transport presence" }
        require(if (this.scopes.size == 1) presence.isEmpty() else presence.size == this.scopes.size) { "multiple source scopes require one presence column each; a single scope keeps its native layout" }
        this.scopes.forEach { (scope, output) ->
            require(output.contract.outputScope == scope) { "source layout scope differs from its actual output contract" }
            require(physical.keys.containsAll(output.columns.values)) { "scope output is absent from the source physical layout" }
            output.contract.columns.forEach { field ->
                val type = physical.getValue(output.columns.getValue(field.symbol.name)).nativeType
                require(SqlTypeUtil.equalSansNullability(field.nativeType, type)) { "source transport must retain each scope output's actual native type" }
            }
            output.presenceColumn?.let { name ->
                val type = requireNotNull(physical[name]) { "source presence is absent from the physical layout" }.nativeType
                require(type.sqlTypeName == SqlTypeName.BOOLEAN && !type.isNullable) { "source presence requires a nonnull physical BOOLEAN column" }
            }
        }
    }

    /** 每条运输行只属于一个cut；各cut恢复自己的完整原袋，存在但输出NULL仍是一行。 */
    fun materialize(frame: ResultFrame, support: CompilationSupport, control: WarehouseExecutionControl): Map<ScopeId, List<List<ExecutableBusinessResult.Value>>> {
        control.checkCancellation()
        val names = fields.map { it.name }
        val physicalNames = names.toSet()
        check(frame.columns.map { it.name } == names) { "source physical result columns differ from compiled layout or order" }
        fields.forEachIndexed { index, field ->
            ExecutableResultTypes.requireJdbc(field.nativeType, requireNotNull(frame.columns[index].jdbcType) { "source SQL type contract is required" }, support)
        }
        val columns = scopes.mapValues { (_, output) -> output.contract.columns.mapIndexed { index, field ->
            ExecutableOutputContract.column("c$index", field, null)
        } }
        val materialized = scopes.keys.associateWithTo(linkedMapOf()) { mutableListOf<List<ExecutableBusinessResult.Value>>() }
        frame.rows.forEach { row ->
            control.checkCancellation()
            val cells = linkedMapOf<String, Any?>()
            row.cells.forEach { cell ->
                check(cell.column in physicalNames && cell.column !in cells) { "source physical row has a duplicate or unknown column" }
                cells[cell.column] = cell.value
            }
            check(cells.keys == physicalNames) { "source physical row has missing cells" }
            fields.forEach { field -> ExecutableResultTypes.requireValue(field.nativeType, cells.getValue(field.name)) }
            val present = scopes.entries.filter { (_, output) ->
                output.presenceColumn?.let { name -> when (cells.getValue(name)) {
                    true -> true
                    false -> false
                    else -> error("source presence must be TRUE or FALSE")
                } } ?: true
            }
            check(present.size == 1) { "one source transport row must belong to exactly one completed scope" }
            val (scope, output) = present.single()
            scopes.forEach { (other, absent) ->
                if (other != scope) check(absent.columns.values.all { cells.getValue(it) == null }) { "absent source scope has a nonnull transport payload" }
            }
            val values = output.contract.columns.map { field ->
                val raw = cells.getValue(output.columns.getValue(field.symbol.name))
                ExecutableResultTypes.requireValue(field.nativeType, raw)
                ExecutableBusinessResults.value(raw, field.nativeType)
            }.also { ExecutableQuantityValues.require(columns.getValue(scope), it) }
            materialized.getValue(scope).add(java.util.List.copyOf(values))
        }
        control.checkCancellation()
        return Collections.unmodifiableMap(materialized.mapValuesTo(linkedMapOf()) { (_, rows) -> Collections.unmodifiableList(rows) })
    }

    companion object {
        fun single(contract: ExecutableOutputContract): ExecutableSourceLayout = ExecutableSourceLayout(
            contract.columns.map { Field(it.symbol.name, it.nativeType) },
            mapOf(contract.outputScope to Scope(contract, contract.columns.associate { it.symbol.name to it.symbol.name }, null)))
    }
}
