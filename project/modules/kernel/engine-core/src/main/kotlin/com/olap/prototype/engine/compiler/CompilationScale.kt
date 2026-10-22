package com.olap.prototype.engine.compiler

import com.olap.prototype.expression.CalciteSqlRendering
import com.olap.prototype.expression.SemanticNumericRex
import org.apache.calcite.rel.RelNode
import org.apache.calcite.rex.RexCall
import org.apache.calcite.rex.RexNode
import org.apache.calcite.rex.RexOver
import org.apache.calcite.rex.RexShuttle
import java.util.Collections
import java.util.IdentityHashMap

/** 编译时直接观察的结构规模；不代表来源实际扫描次数、求值次数或执行失败原因。 */
data class CompilationScale(val raw: RelationSize, val optimized: RelationSize, val sqlRelation: RelationSize,
    val sqlBytes: Int, val sqlCaseExpressions: Int, val sqlNumericChecks: Int,
    val numericBindings: Int, val reusedNumericChecks: Int) {
    data class RelationSize(val nodes: Int, val edges: Int, val scalarCalls: Int, val numericChecks: Int, val repeatedNumericChecks: Int)

    companion object {
        fun capture(raw: RelNode, optimized: RelNode, rendered: CalciteSqlRendering.Rendering): CompilationScale = CompilationScale(
            relation(raw), relation(optimized), relation(rendered.relation), rendered.sql.sql.toByteArray(Charsets.UTF_8).size,
            rendered.caseExpressions, rendered.numericChecks, rendered.numericBindings, rendered.reusedNumericChecks)

        private fun relation(root: RelNode): RelationSize {
            val visited = Collections.newSetFromMap(IdentityHashMap<RelNode, Boolean>())
            var edges = 0; var calls = 0; var checks = 0; var repeated = 0
            fun visit(node: RelNode) {
                if (!visited.add(node)) return
                val signatures = mutableSetOf<Pair<String, String>>()
                node.accept(object : RexShuttle() {
                    override fun visitCall(call: RexCall): RexNode {
                        calls++
                        if (SemanticNumericRex.isNumericCheck(call)) {
                            checks++
                            if (!signatures.add(call.toString() to call.type.fullTypeString)) repeated++
                        }
                        return super.visitCall(call)
                    }
                    override fun visitOver(over: RexOver): RexNode { calls++; return super.visitOver(over) }
                })
                edges += node.inputs.size
                node.inputs.forEach(::visit)
            }
            visit(root)
            return RelationSize(visited.size, edges, calls, checks, repeated)
        }
    }
}
