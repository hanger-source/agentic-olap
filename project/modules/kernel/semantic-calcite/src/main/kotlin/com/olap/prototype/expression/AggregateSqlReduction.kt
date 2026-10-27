package com.olap.prototype.expression

import com.olap.prototype.connector.AggregateSql
import org.apache.calcite.rel.core.Aggregate
import org.apache.calcite.sql.*
import org.apache.calcite.sql.`fun`.SqlStdOperatorTable as Ops
import org.apache.calcite.sql.parser.SqlParserPos

/** 原分组完成值进入独立归约关系；完整技术组键留在核心，不以扫描序或哈希代替身份。 */
internal object AggregateSqlReduction {
    private val pos = SqlParserPos.ZERO

    fun complete(
        owner: Aggregate,
        packed: SqlNode,
        mappings: List<AggregateSql>,
        declare: (String, List<String>, SqlNode) -> SqlIdentifier,
    ): SqlSelect {
        require(owner.groupType == Aggregate.Group.SIMPLE) {
            "native aggregate reduction requires SIMPLE grouping; grouping-set occurrences have no reduction mapping"
        }
        val groupCount = owner.groupCount
        val columns = owner.rowType.fieldNames.indices.map { "__c$it" }
        val base = declare("__aggregate", columns, packed)
        val groups = (0 until maxOf(1, groupCount)).map { SqlIdentifier("__g$it", pos) }
        val value = SqlIdentifier("__value", pos)
        val completed = mutableMapOf<Int, SqlIdentifier>()
        var from: SqlNode = base

        mappings.forEachIndexed { index, mapping ->
            if (mapping !is AggregateSql.Reduction) return@forEachIndexed
            val inputs = groups.indices.map { key ->
                alias(if (groupCount == 0) SqlLiteral.createExactNumeric("0", pos)
                    else field(base, columns[key]), groups[key].simple)
            } + alias(field(base, columns[groupCount + index]), value.simple)
            val names = groups.map { it.simple } + value.simple
            val work = declare("__aggregate_work", names, select(inputs, base))
            val reduced = declare("__aggregate_reduced", names,
                namedOutput(mapping.reduce(work, java.util.List.copyOf(groups), value), names))
            val conditions = groups.indices.map { key ->
                Ops.IS_NOT_DISTINCT_FROM.createCall(pos,
                    if (groupCount == 0) SqlLiteral.createExactNumeric("0", pos) else field(base, columns[key]),
                    field(reduced, groups[key].simple))
            }
            val condition = conditions.reduce { left, right -> Ops.AND.createCall(pos, left, right) }
            from = SqlJoin(pos, from, SqlLiteral.createBoolean(false, pos), JoinType.LEFT.symbol(pos),
                reduced, JoinConditionType.ON.symbol(pos), condition)
            completed[index] = reduced
        }

        val outputs = owner.rowType.fieldNames.mapIndexed { ordinal, name ->
            val reduction = completed[ordinal - groupCount]
            alias(if (reduction == null) field(base, columns[ordinal]) else field(reduction, value.simple), name)
        }
        return select(outputs, from)
    }

    private fun field(relation: SqlIdentifier, name: String) = SqlIdentifier(relation.names + name, pos)
    private fun alias(value: SqlNode, name: String) = Ops.AS.createCall(pos, value, SqlIdentifier(name, pos))
    private fun select(columns: List<SqlNode>, from: SqlNode) = SqlSelect(pos, null, SqlNodeList(columns, pos),
        from, null, null, null, null, null, null, null, null, null)

    internal fun projection(query: SqlNode, width: Int): SqlSelect {
        var body = query
        while (body is SqlWith || body is SqlOrderBy) body = when (body) {
            is SqlWith -> body.body
            is SqlOrderBy -> body.query
            else -> error("unreachable aggregate query wrapper")
        }
        val selected = requireNotNull(body as? SqlSelect) { "native aggregate work query requires an explicit final SELECT" }
        val outputs = requireNotNull(selected.selectList)
        require(outputs.size == width && outputs.none { it is SqlIdentifier && it.isStar }) {
            "native aggregate work query must return all technical group columns and one value"
        }
        return selected
    }

    private fun namedOutput(query: SqlNode, columns: List<String>): SqlSelect {
        projection(query, columns.size)
        // 来源程序自己的 ORDER BY/HAVING 等仍引用它自己的输出名；核心只在外层按列序命名。
        val name = SqlIdentifier("__native_aggregate", pos)
        val source = Ops.AS.createCall(pos, query, name, *columns.map { SqlIdentifier(it, pos) }.toTypedArray())
        return select(columns.map { alias(field(name, it), it) }, source)
    }
}
