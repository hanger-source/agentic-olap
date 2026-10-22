package com.olap.prototype.engine.calcite

import com.olap.prototype.query.QueryExplanationView
import org.apache.calcite.avatica.util.TimeUnit
import org.apache.calcite.rel.RelNode
import org.apache.calcite.rel.core.Aggregate
import org.apache.calcite.rel.core.Correlate
import org.apache.calcite.rel.core.Join
import org.apache.calcite.rel.core.RepeatUnion
import org.apache.calcite.rel.core.Spool
import org.apache.calcite.rel.core.TableScan
import org.apache.calcite.rel.core.TableSpool
import org.apache.calcite.rel.externalize.RelJson
import org.apache.calcite.rel.externalize.RelJsonWriter
import org.apache.calcite.rex.RexLiteral
import org.apache.calcite.rex.RexNode
import org.apache.calcite.util.JsonBuilder
import org.apache.calcite.util.Pair
import java.util.IdentityHashMap

/** 真实Rel计划的可解释证据，不拥有编译组织或候选业务。 */
object CalcitePlanEvidence {
    /** 正式摘要由实际节点和边产生；RelJson仅保留为原生私有诊断。 */
    fun describe(plan: RelNode): List<QueryExplanationView.PlanNode> {
        val ids = IdentityHashMap<RelNode, String>()
        val scans = linkedMapOf<List<String>, String>()
        val nodes = mutableListOf<QueryExplanationView.PlanNode>()
        fun visit(node: RelNode): String {
            ids[node]?.let { return it }
            val inputs = node.inputs.map(::visit)
            val id = nodes.size.toString()
            val table = when (node) {
                is TableScan -> requireNotNull(node.table).qualifiedName
                is TableSpool -> requireNotNull(node.table).qualifiedName
                else -> null
            }
            val scan = table?.let { scans.getOrPut(it.toList()) { "scan-${scans.size + 1}" } }
            val join = when (node) {
                is Join -> node.joinType.name
                is Correlate -> node.joinType.name
                else -> null
            }
            val aggregate = node as? Aggregate
            nodes.add(QueryExplanationView.PlanNode(id, node.relTypeName, java.util.List.copyOf(inputs), scan, join,
                aggregate?.groupSet?.asList()?.toList(), aggregate?.aggCallList?.map { it.aggregation.name }))
            ids[node] = id
            return id
        }
        visit(plan)
        return java.util.List.copyOf(nodes)
    }

    fun write(plan: RelNode): String {
        val json = JsonBuilder()
        val writer = object : RelJsonWriter(json, { _ -> object : RelJson(json) {
            override fun toJson(value: Any?): Any? = if (value is Spool.Type) value.name else super.toJson(value)
            override fun toJson(node: RexNode): Any? {
                val unit = (node as? RexLiteral)?.value3 as? TimeUnit ?: return super.toJson(node)
                return json.map().apply {
                    put("literal", unit.name); put("enumClass", unit.declaringJavaClass.name); put("type", super.toJson(node.type as Any))
                }
            }
        } }) {
            override fun explain_(node: RelNode, values: List<Pair<String, Any?>>) {
                val attributes = values.toMutableList()
                attributes.add(Pair.of("resolvedRowType", node.rowType))
                if (node is RepeatUnion) {
                    if (node.iterationLimit < 0) attributes.add(Pair.of("iterationLimit", node.iterationLimit))
                    attributes.add(Pair.of("transientTable", node.transientTable?.qualifiedName))
                }
                super.explain_(node, attributes)
            }
        }
        plan.explain(writer)
        return writer.asString()
    }
}
