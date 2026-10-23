package com.olap.prototype.expression

import com.olap.prototype.connector.CompilationSupport
import com.olap.prototype.connector.NativeCompilationCapabilityException
import org.apache.calcite.rel.type.RelDataType
import org.apache.calcite.sql.*
import org.apache.calcite.sql.`fun`.SqlCase
import org.apache.calcite.sql.`fun`.SqlStdOperatorTable as Ops
import org.apache.calcite.sql.parser.SqlParserPos
import org.apache.calcite.sql.validate.SqlValidatorUtil
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.util.TreeSet

/** 核心构造完整输入上的展开位置、透传列和补空；插件只映射原生生成与取值表达式。 */
internal object CollectionExpansionSql {
    private val position = SqlParserPos.ZERO
    private val log = LoggerFactory.getLogger(CollectionExpansionSql::class.java)

    fun select(support: CompilationSupport, expansion: CollectionExpansionRel, from: SqlNode,
        fields: List<SqlNode>, tableAlias: String, uniqueAlias: (String) -> String): SqlSelect {
        val outputType = expansion.cluster.typeFactory.builder().apply {
            expansion.rowType.fieldList.takeLast(2).forEach { add(it.name, it.type) }
        }.build()
        val mapping = support.expandCollection(outputType, tableAlias)
        if (log.isDebugEnabled) log.debug(
            "原生集合展开映射：requestId={}, workerKind={}, relId={}, inputRelId={}, column={}, arrayType={}, outputType={}, placement={}, outer={}",
            MDC.get("requestId"), MDC.get("workerKind"), expansion.id, expansion.input.id, expansion.column,
            expansion.input.rowType.fieldList[expansion.column].type.fullTypeString, outputType.fullTypeString,
            when (mapping) {
                is CompilationSupport.CollectionExpansion.Relation -> "RELATION"
                is CompilationSupport.CollectionExpansion.InputProjection -> "INPUT_PROJECTION"
            }, expansion.outer)
        return when (mapping) {
            is CompilationSupport.CollectionExpansion.Relation -> {
                val native = mapping.generate(fields[expansion.column])
                val joined = SqlJoin(position, from, SqlLiteral.createBoolean(false, position),
                    (if (expansion.outer) JoinType.LEFT else JoinType.CROSS).symbol(position), native.relation,
                    (if (expansion.outer) JoinConditionType.ON else JoinConditionType.NONE).symbol(position),
                    if (expansion.outer) SqlLiteral.createBoolean(true, position) else null)
                val element = if (expansion.outer) choose(Ops.IS_NOT_NULL.createCall(position, native.ordinal),
                    native.element, nullValue(support, outputType.fieldList[0].type)) else native.element
                projection(fields + listOf(element, native.ordinal), expansion.rowType.fieldNames, joined)
            }
            is CompilationSupport.CollectionExpansion.InputProjection ->
                inputProjection(support, expansion, outputType, from, fields, mapping, uniqueAlias)
        }
    }

    private fun inputProjection(support: CompilationSupport, expansion: CollectionExpansionRel, outputType: RelDataType,
        from: SqlNode, fields: List<SqlNode>, mapping: CompilationSupport.CollectionExpansion.InputProjection,
        uniqueAlias: (String) -> String): SqlSelect {
        if (expansion.outer && !support.supports(CompilationSupport.Capability.COLLECTION_PROJECTION_OUTER))
            throw NativeCompilationCapabilityException(CompilationSupport.Capability.COLLECTION_PROJECTION_OUTER, "EXPAND_COLLECTION_OUTER")
        val inputNames = expansion.input.rowType.fieldNames
        val occupied = TreeSet(String.CASE_INSENSITIVE_ORDER).apply { addAll(inputNames) }
        fun column(name: String): String = SqlValidatorUtil.uniquify(name, occupied, SqlValidatorUtil.EXPR_SUGGESTER)
        val generatedName = column("__collection_generated")
        val arrayName = if (expansion.outer) column("__collection_array") else inputNames[expansion.column]
        val presentName = if (expansion.outer) column("__collection_present") else null
        var source = from
        var values = fields
        var names = inputNames
        if (presentName != null) {
            val array = fields[expansion.column]
            val present = Ops.GREATER_THAN.createCall(position, Ops.CARDINALITY.createCall(position, array),
                SqlLiteral.createExactNumeric("0", position))
            val component = requireNotNull(expansion.input.rowType.fieldList[expansion.column].type.componentType)
            val singleton = Ops.ARRAY_VALUE_CONSTRUCTOR.createCall(position, nullValue(support, component))
            val padded = choose(present, array, singleton)
            names = inputNames + listOf(arrayName, presentName)
            val alias = uniqueAlias("__collection_input")
            source = named(projection(fields + listOf(padded, present), names, from), alias)
            values = names.map { field(alias, it) }
        }
        val array = values[names.indexOf(arrayName)]
        val generated = projection(values + mapping.generate(array), names + generatedName, source)
        val alias = uniqueAlias("__collection_rows")
        val native = mapping.values(field(alias, arrayName), field(alias, generatedName))
        val element = if (presentName == null) native.element else choose(field(alias, presentName),
            native.element, nullValue(support, outputType.fieldList[0].type))
        val ordinal = if (presentName == null) native.ordinal else choose(field(alias, presentName),
            native.ordinal, nullValue(support, outputType.fieldList[1].type))
        return projection(inputNames.map { field(alias, it) } + listOf(element, ordinal), expansion.rowType.fieldNames,
            named(generated, alias))
    }

    private fun projection(values: List<SqlNode>, names: List<String>, from: SqlNode): SqlSelect {
        check(values.size == names.size) { "collection projection must retain its exact output width" }
        val columns = values.mapIndexed { index, value -> Ops.AS.createCall(position, value, SqlIdentifier(names[index], position)) }
        return SqlSelect(position, null, SqlNodeList(columns, position), from, null, null, null, null, null, null, null, null, null)
    }
    private fun named(query: SqlNode, alias: String): SqlNode = Ops.AS.createCall(position, query, SqlIdentifier(alias, position))
    private fun field(alias: String, column: String): SqlNode = SqlIdentifier(listOf(alias, column), position)
    private fun choose(present: SqlNode, value: SqlNode, absent: SqlNode): SqlNode = SqlCase(position, null,
        SqlNodeList(listOf(present), position), SqlNodeList(listOf(value), position), absent)
    private fun nullValue(support: CompilationSupport, type: RelDataType): SqlNode = Ops.CAST.createCall(position,
        SqlLiteral.createNull(position), requireNotNull(support.dialect.getCastSpec(type)) { "collection NULL requires its complete native type" })
}
