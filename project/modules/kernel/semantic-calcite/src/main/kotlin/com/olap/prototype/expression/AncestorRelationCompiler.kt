package com.olap.prototype.expression

import com.olap.prototype.connector.CompilationSupport
import com.olap.prototype.query.PostOperationContract.AncestorClosure
import org.apache.calcite.rel.core.JoinRelType
import org.apache.calcite.sql.`fun`.SqlStdOperatorTable as Ops
import org.apache.calcite.sql.type.SqlTypeName
import org.apache.calcite.sql.type.SqlTypeUtil
import org.apache.calcite.tools.RelBuilder

/** 无界有限可达闭包；固定点身份只携带端点，不携带路径或深度。 */
internal object AncestorRelationCompiler {
    fun compile(b: RelBuilder, closure: AncestorClosure, checks: MutableList<RelationalCheck>) {
        require((b.typeFactory.typeSystem as? SemanticCalciteTypeSystem)?.supports(CompilationSupport.Capability.RECURSIVE_ANCESTORS) == true) {
            "ANCESTOR_CLOSURE requires a verified native recursive SQL mapping"
        }
        val input = b.build()
        val width = closure.sourceKeys.size
        val names = closure.descendantAliases + closure.ancestorAliases
        repeat(width) { index ->
            val source = input.rowType.fieldList[input.rowType.fieldNames.indexOf(closure.sourceKeys[index])].type
            val parent = input.rowType.fieldList[input.rowType.fieldNames.indexOf(closure.parentKeys[index])].type
            require(SqlTypeUtil.equalSansNullability(b.typeFactory, source, parent) && source.sqlTypeName !in
                setOf(SqlTypeName.REAL, SqlTypeName.FLOAT, SqlTypeName.DOUBLE, SqlTypeName.ARRAY, SqlTypeName.ROW, SqlTypeName.MAP)) {
                "ancestor endpoints require matching exact categorical scalar types"
            }
        }
        b.push(input).filter(b.or(closure.sourceKeys.map { b.isNull(b.field(it)) }))
        b.project(listOf(b.literal(1)), listOf("violation"), true).limit(0, 1)
        checks.add(RelationalCheck("ANCESTOR_SOURCE_KEY_NULL", b.build()))
        b.push(input).filter(b.and(b.or(closure.parentKeys.map { b.isNull(b.field(it)) }), b.or(closure.parentKeys.map { b.isNotNull(b.field(it)) })))
        b.project(listOf(b.literal(1)), listOf("violation"), true).limit(0, 1)
        checks.add(RelationalCheck("ANCESTOR_PARENT_KEY_PARTIAL_NULL", b.build()))
        b.push(input).filter(b.and(closure.parentKeys.map { b.isNotNull(b.field(it)) }))
        b.project((closure.sourceKeys + closure.parentKeys).map(b::field), names, true).distinct()
        val edges = b.build()
        val transientName = "__olap_ancestor_${checks.size}"
        b.push(edges).transientScan(transientName, edges.rowType).push(edges)
        val comparisons = (0 until width).map { b.call(Ops.EQUALS, b.field(2, 0, width + it), b.field(2, 1, it)) }
        b.join(JoinRelType.INNER, b.and(comparisons))
        b.project((0 until width).map(b::field) + (0 until width).map { b.field(3 * width + it) }, names, true)
        b.repeatUnion(transientName, false, -1)
        val closed = b.build()
        b.push(closed).filter(b.and((0 until width).map { b.call(Ops.EQUALS, b.field(it), b.field(width + it)) }))
            .project(listOf(b.literal(1)), listOf("violation"), true).limit(0, 1)
        checks.add(RelationalCheck("ANCESTOR_CYCLE", b.build()))
        b.push(closed)
        if (closure.includeSelf) {
            for (slots in listOf(closure.sourceKeys, closure.parentKeys)) {
                b.push(input).filter(b.and(slots.map { b.isNotNull(b.field(it)) }))
                b.project((slots + slots).map(b::field), names, true)
            }
            b.union(false, 3)
        }
    }
}
