package com.olap.prototype.engine.calcite

import com.olap.prototype.connector.ConnectorRegistry
import com.olap.prototype.connector.SourceSqlRequest
import com.olap.prototype.contract.JdbcParameter
import com.olap.prototype.engine.catalog.ExecutablePhysicalExecutionBinding
import com.olap.prototype.engine.catalog.ExecutableRelationPosition
import com.olap.prototype.expression.CalciteSqlRendering
import com.olap.prototype.expression.SemanticExpression
import com.olap.prototype.expression.SemanticNativeTypes
import org.apache.calcite.rel.RelNode
import org.apache.calcite.tools.RelBuilder

/** 候选值的单列、非空、去重与分页，以及完整实际来源执行请求。 */
object CandidateValueCompiler {
    fun page(completed: RelNode, offset: Int, limit: Int): RelNode {
        require(completed.rowType.fieldCount == 1 && offset >= 0 && limit > 0) { "candidate values require one completed column and valid page bounds" }
        val builder = RelBuilder.proto().create(completed.cluster, null)
        builder.push(completed).filter(builder.isNotNull(builder.field(0))).distinct().sortLimit(offset.toLong(), limit.toLong(), builder.field(0))
        return builder.build()
    }

    fun compile(binding: ExecutablePhysicalExecutionBinding, expression: SemanticExpression, offset: Int, limit: Int,
        parameters: MutableList<JdbcParameter>, connectors: ConnectorRegistry): SourceSqlRequest {
        require(offset >= 0 && limit > 0) { "candidate page bounds are invalid" }
        require(binding.sourceConstraints != null) { "candidate source requires exact frozen investigation evidence" }
        val bindings = listOf(binding)
        val refs = SemanticRexCompiler.physicalDependencies(listOf(expression), bindings)
        val inputs = CalciteQueryCompiler.inputs(bindings, refs.columns, refs.tables, connectors)
        val b = inputs.builder
        b.scan(FrozenCatalogSchema.path(binding))
        val root = ExecutableRelationPosition.Id.backbone()
        val compiler = SemanticRexCompiler(b, root, mapOf(root to binding), { _, column -> b.field(column) }, parameters,
            { _, _ -> throw IllegalArgumentException("generic candidate LogicUnit does not own relation quantifiers") }, linkedSetOf())
        b.project(listOf(compiler.compile(expression)), listOf("candidate"), true)
        b.filter(b.isNotNull(b.field(0))).distinct().sortLimit(offset.toLong(), limit.toLong(), b.field(0))
        val relation = CalciteQueryCompiler.optimize(b.build(), parameters)
        val statement = CalciteSqlRendering.render(relation, connectors.require(binding.connectorRef).compilation, parameters)
        return SourceSqlRequest("read-candidate-values", statement.sql, CalciteSqlRendering.orderedParameters(statement, parameters),
            mapOf("candidate" to "candidate"), mapOf("candidate" to "candidate"),
            mapOf("candidate" to SemanticNativeTypes.resultType(relation.rowType.fieldList.first().type)), inputs.sourceRequirements(), emptyList(), null)
    }
}
