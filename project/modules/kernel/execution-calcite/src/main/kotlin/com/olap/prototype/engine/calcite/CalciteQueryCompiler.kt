package com.olap.prototype.engine.calcite

import com.olap.prototype.connector.ConnectorRegistry
import com.olap.prototype.contract.JdbcParameter
import com.olap.prototype.contract.JdbcSourceRequirement
import com.olap.prototype.engine.catalog.ExecutablePhysicalExecutionBinding
import com.olap.prototype.expression.CalciteCompilation
import com.olap.prototype.expression.SemanticExpression
import org.apache.calcite.plan.hep.HepPlanner
import org.apache.calcite.plan.hep.HepProgramBuilder
import org.apache.calcite.rel.RelNode
import org.apache.calcite.rel.core.Sort
import org.apache.calcite.rel.logical.LogicalProject
import org.apache.calcite.rel.rules.CoreRules
import org.apache.calcite.rel.type.RelDataType
import org.apache.calcite.rex.*
import org.apache.calcite.tools.RelBuilder
import java.util.IdentityHashMap

/** 只拥有Calcite扩展，不依赖治理仓库或应用生命周期。 */
object CalciteQueryCompiler {
    fun inputs(bindings: Collection<ExecutablePhysicalExecutionBinding>, columns: Set<String>, nativeTables: Set<SemanticExpression.TableArgument>, connectors: ConnectorRegistry): Inputs =
        Inputs(FrozenCatalogSchema.bind(bindings, columns, nativeTables, connectors))

    class Inputs internal constructor(private val schema: FrozenCatalogSchema.SchemaBinding) {
        val builder: RelBuilder = CalciteCompilation.builder(schema.connector.compilation, schema.schema).also { SourceRelMetadataQuery.install(it.cluster) }
        fun sourceRequirements(): List<JdbcSourceRequirement> = schema.sourceRequirements()
    }

    fun optimize(raw: RelNode, parameters: List<JdbcParameter>): RelNode {
        val program = HepProgramBuilder()
            .addRuleInstance(CoreRules.CALC_TO_WINDOW).addRuleInstance(CoreRules.CALC_SPLIT)
            .addRuleInstance(CoreRules.FILTER_MERGE).addRuleInstance(CoreRules.PROJECT_MERGE)
            .addRuleInstance(CoreRules.FILTER_PROJECT_TRANSPOSE).addRuleInstance(CoreRules.PROJECT_REMOVE)
            .addRuleInstance(CoreRules.PROJECT_TO_LOGICAL_PROJECT_AND_WINDOW).addRuleInstance(CoreRules.PROJECT_WINDOW_TRANSPOSE)
            .addRuleInstance(CoreRules.PROJECT_MERGE).addRuleInstance(CoreRules.PROJECT_REMOVE).build()
        val optimizer = HepPlanner(program)
        optimizer.setRoot(ParameterCanonicalization(raw, parameters).rewrite(raw))
        var optimized = optimizer.findBestExp()
        optimized = SharedRelFieldTrimming.trim(optimized)
        check(optimized.rowType.fieldCount == raw.rowType.fieldCount) { "Calcite optimization changed the output column count" }
        // 输出名由显式Project承担；Sort必须仍在外层保持展示顺序。
        return if (optimized is Sort) optimized.copy(optimized.traitSet, outputProjection(optimized.input, raw.rowType.fieldNames),
            optimized.collation, optimized.offset, optimized.fetch) else outputProjection(optimized, raw.rowType.fieldNames)
    }

    private data class ParameterKey(val type: RelDataType, val valueClass: Class<*>, val value: JdbcParameter)
    private class ParameterCanonicalization(private val raw: RelNode, private val parameters: List<JdbcParameter>) {
        private val indexes = linkedMapOf<ParameterKey, Int>()
        private val visited = IdentityHashMap<RelNode, RelNode>()
        fun rewrite(node: RelNode): RelNode {
            visited[node]?.let { return it }
            val inputs = node.inputs.map(::rewrite)
            val changed = inputs.indices.any { inputs[it] !== node.getInput(it) }
            val rewritten = (if (changed) node.copy(node.traitSet, inputs) else node).accept(object : RexShuttle() {
                override fun visitDynamicParam(parameter: RexDynamicParam): RexNode {
                    val value = parameters[parameter.index]
                    val first = indexes.getOrPut(ParameterKey(parameter.type, value.javaClass, value)) { parameter.index }
                    return if (first == parameter.index) parameter else raw.cluster.rexBuilder.makeDynamicParam(parameter.type, first)
                }
                override fun visitSubQuery(query: RexSubQuery): RexNode {
                    val rewritten = super.visitSubQuery(query) as RexSubQuery
                    return rewritten.clone(rewrite(query.rel))
                }
            })
            visited[node] = rewritten
            return rewritten
        }
    }
    private fun outputProjection(input: RelNode, names: List<String>): RelNode = LogicalProject.create(input, emptyList(),
        (0 until input.rowType.fieldCount).map { input.cluster.rexBuilder.makeInputRef(input, it) }, names, emptySet())
}
