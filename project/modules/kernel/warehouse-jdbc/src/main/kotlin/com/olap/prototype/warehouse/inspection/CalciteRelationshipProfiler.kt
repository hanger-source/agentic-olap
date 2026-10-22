package com.olap.prototype.warehouse.inspection

import com.olap.prototype.connector.CompilationSupport
import com.olap.prototype.contract.JdbcColumnType
import com.olap.prototype.contract.JdbcParameter
import com.olap.prototype.expression.CalciteCompilation
import com.olap.prototype.expression.CalciteSqlRendering
import com.olap.prototype.expression.SemanticCalciteRexCompiler
import org.apache.calcite.rel.RelNode
import org.apache.calcite.rel.core.JoinRelType
import org.apache.calcite.rel.type.RelDataType
import org.apache.calcite.rel.type.RelDataTypeFactory
import org.apache.calcite.rex.RexNode
import org.apache.calcite.schema.SchemaPlus
import org.apache.calcite.schema.impl.AbstractSchema
import org.apache.calcite.schema.impl.AbstractTable
import org.apache.calcite.sql.`fun`.SqlStdOperatorTable as Ops
import org.apache.calcite.sql.type.SqlTypeName
import org.apache.calcite.tools.Frameworks
import org.apache.calcite.tools.RelBuilder

/** Group composite keys before bidirectional matching; never expand the raw-row Cartesian product. */
internal object CalciteRelationshipProfiler {
    private val counts = listOf("row_count", "null_key_rows", "non_null_key_groups", "duplicate_non_null_key_groups", "rows_with_duplicated_non_null_keys", "unmatched_rows", "single_match_rows", "multiple_match_rows", "matched_pairs", "maximum_matches_per_row")
    class Compiled(val sql: String, parameters: List<JdbcParameter>) {
        val parameters: List<JdbcParameter> = java.util.List.copyOf(parameters)
        override fun equals(other: Any?): Boolean = other is Compiled && sql == other.sql && parameters == other.parameters
        override fun hashCode(): Int = 31 * sql.hashCode() + parameters.hashCode()
    }
    fun compile(request: WarehouseRelationshipProfileRequest, sourceColumns: List<WarehouseColumnMetadata>, targetColumns: List<WarehouseColumnMetadata>, support: CompilationSupport): Compiled {
        val root = Frameworks.createRootSchema(true)
        val tables = linkedMapOf<List<String>, MutableMap<String, JdbcColumnType>>()
        val sourceRef = request.sourceInput.table; val targetRef = request.targetInput.table
        val sourcePath = support.tablePath(sourceRef.catalog, sourceRef.schema, sourceRef.table)
        val targetPath = support.tablePath(targetRef.catalog, targetRef.schema, targetRef.table)
        columns(tables, sourcePath, request.sourceInput.columns, sourceColumns)
        columns(tables, targetPath, request.targetInput.columns, targetColumns)
        tables.forEach { (path, fields) -> table(root, path, fields, support) }
        val builder = CalciteCompilation.builder(support, root)
        val parameters = mutableListOf<JdbcParameter>()
        val source = grouped(builder, sourcePath, request.sourceInput, parameters)
        val target = grouped(builder, targetPath, request.targetInput, parameters)
        val keys = request.sourceInput.keyExpressions.size
        repeat(keys) { index ->
            val factory = builder.typeFactory
            require(factory.createTypeWithNullability(source.rowType.fieldList[index].type, false) == factory.createTypeWithNullability(target.rowType.fieldList[index].type, false)) { "复合键类型不同，调查不能隐式转换或猜测身份: $index" }
        }
        val forward = direction(builder, source, target, keys, 0)
        val backward = direction(builder, target, source, keys, 1)
        builder.push(forward).push(backward).union(true)
        val sql = CalciteSqlRendering.render(builder.build(), support, parameters)
        return Compiled(sql.sql, CalciteSqlRendering.orderedParameters(sql, parameters))
    }
    private fun columns(tables: MutableMap<List<String>, MutableMap<String, JdbcColumnType>>, path: List<String>, selected: List<WarehouseRelationshipProfileRequest.Column>, discovered: List<WarehouseColumnMetadata>) {
        val types = tables.getOrPut(path) { linkedMapOf() }
        selected.forEach { binding ->
            val column = discovered.firstOrNull { it.name == binding.column } ?: throw IllegalArgumentException("不存在精确列引用: ${binding.column}")
            val type = JdbcColumnType(requireNotNull(column.jdbcType) { "source column JDBC type is unknown" }, requireNotNull(column.typeName) { "source column native type is unknown" }, column.precision, column.scale, column.nullable)
            val previous = types.putIfAbsent(binding.column, type)
            require(previous == null || previous == type) { "同一精确表的 JDBC 列类型不一致" }
        }
    }
    private fun table(root: SchemaPlus, path: List<String>, columns: Map<String, JdbcColumnType>, support: CompilationSupport) {
        var owner = root
        path.dropLast(1).forEach { component -> owner = owner.getSubSchema(component) ?: owner.add(component, AbstractSchema()) }
        owner.add(path.last(), object : AbstractTable() {
            override fun getRowType(factory: RelDataTypeFactory): RelDataType = factory.builder().also { fields -> columns.forEach { (name, type) -> fields.add(name, support.jdbcType(factory, type)) } }.build()
        })
    }
    private fun grouped(builder: RelBuilder, path: List<String>, input: WarehouseRelationshipProfileRequest.Input, parameters: MutableList<JdbcParameter>): RelNode {
        builder.scan(path)
        val bindings = input.columns.associate { "${it.physicalColumnId}@${it.physicalColumnVersion}" to it.column }
        val compiler = SemanticCalciteRexCompiler(builder, parameters) { _, _ -> throw IllegalArgumentException("technical relationship profiling does not authorize native source calls") }
        val projection = input.keyExpressions.map { expression -> compiler.compile(expression, { ref -> builder.field(bindings["${ref.id}@${ref.version}"] ?: throw IllegalArgumentException("缺少已核验的精确物理引用")) }) }
        builder.project(projection, input.keyExpressions.indices.map { "k$it" }, true)
        builder.aggregate(builder.groupKey(builder.fields()), builder.count(false, "key_row_count"))
        return builder.build()
    }
    private fun direction(builder: RelBuilder, source: RelNode, target: RelNode, keys: Int, direction: Int): RelNode {
        builder.push(source).push(target)
        builder.join(JoinRelType.LEFT, builder.and((0 until keys).map { builder.equals(builder.field(2, 0, it), builder.field(2, 1, it)) }))
        val sourceCount = builder.cast(builder.field(keys), SqlTypeName.DECIMAL, 38, 0)
        val targetRows = builder.field(2 * keys + 1)
        val targetType = builder.typeFactory.createTypeWithNullability(builder.typeFactory.createSqlType(SqlTypeName.DECIMAL, 38, 0), targetRows.type.isNullable)
        val targetCount = source.cluster.rexBuilder.makeCast(targetType, targetRows)
        val zero = builder.cast(builder.literal(0), SqlTypeName.DECIMAL, 38, 0)
        val one = builder.cast(builder.literal(1), SqlTypeName.DECIMAL, 38, 0)
        val nullKey = builder.or((0 until keys).map { builder.isNull(builder.field(it)) })
        val duplicate = builder.and(builder.not(nullKey), builder.call(Ops.GREATER_THAN, sourceCount, one))
        val matches = builder.call(Ops.COALESCE, targetCount, zero)
        fun choose(condition: RexNode, yes: RexNode, no: RexNode): RexNode = builder.call(Ops.CASE, condition, yes, no)
        builder.project(listOf(sourceCount, choose(nullKey, sourceCount, zero), choose(builder.not(nullKey), one, zero), choose(duplicate, one, zero), choose(duplicate, sourceCount, zero),
            choose(builder.isNull(targetRows), sourceCount, zero), choose(builder.equals(targetCount, one), sourceCount, zero), choose(builder.call(Ops.GREATER_THAN, targetCount, one), sourceCount, zero),
            builder.call(Ops.MULTIPLY, sourceCount, matches), matches), counts, true)
        builder.aggregate(builder.groupKey(), counts.mapIndexed { index, name -> if (index == counts.lastIndex) builder.max(name, builder.field(index)) else builder.sum(false, name, builder.field(index)) })
        builder.project(listOf(builder.literal(direction)) + counts.indices.map { builder.call(Ops.COALESCE, builder.field(it), zero) }, listOf("direction") + counts, true)
        return builder.build()
    }
}
