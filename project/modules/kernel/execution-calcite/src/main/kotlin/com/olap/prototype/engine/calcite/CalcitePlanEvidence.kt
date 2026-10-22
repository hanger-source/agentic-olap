package com.olap.prototype.engine.calcite

import org.apache.calcite.avatica.util.TimeUnit
import org.apache.calcite.rel.RelNode
import org.apache.calcite.rel.core.RepeatUnion
import org.apache.calcite.rel.core.Spool
import org.apache.calcite.rel.externalize.RelJson
import org.apache.calcite.rel.externalize.RelJsonWriter
import org.apache.calcite.rex.RexLiteral
import org.apache.calcite.rex.RexNode
import org.apache.calcite.util.JsonBuilder
import org.apache.calcite.util.Pair

/** 真实Rel计划的可解释证据，不拥有编译组织或候选业务。 */
object CalcitePlanEvidence {
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
