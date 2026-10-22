package com.olap.prototype.engine.executable

import com.olap.prototype.calculation.*
import com.olap.prototype.connector.ConnectorRegistry
import com.olap.prototype.engine.catalog.ExecutableCatalog
import com.olap.prototype.member.MemberRef
import com.olap.prototype.member.MemberSource
import com.olap.prototype.query.QueryComputation
import com.olap.prototype.query.QueryStageAnalysis.ScopeId

/** 单次请求的目录、规则owner与完成边界；无跨请求绑定缓存。 */
internal class ExecutableBindingSession(val catalog: ExecutableCatalog, connectors: ConnectorRegistry) {
    val dimensions = ExecutableDimensionBindings(catalog, connectors)
    val completion = ExecutableBindingCompletion()
    val members = MemberSources(this, catalog)
    val relations = RelationInputs()
    val metrics = MetricBinding(this, catalog, dimensions, members)

    fun bind(request: ExecutableQueryRequest): ExecutableInputScope {
        require(request.formatVersion == ExecutableQueryRequest.FORMAT_VERSION) { "unsupported kernel query format: ${request.formatVersion}" }
        require(request.catalogId.isNotBlank() && request.catalogId == catalog.manifest.catalogId) { "query catalog identity differs from its exact computation directory" }
        catalog.validate()
        require(request.requestId.isNotBlank()) { "requestId is required" }
        return completion.finish(bindQueryScope(request.computation, ScopeId.root(), 0, null))
    }
    fun bindQueryScope(syntax: QueryComputation.Scope, scope: ScopeId, depth: Int, mode: String?): ExecutableInputScope = QueryScopeBinding(this, catalog, syntax, scope, depth, mode).bind()

    fun bindInline(root: QueryComputation.Compute, caller: ScopeAssembly, callerScope: ScopeId, lexicalScope: ScopeId, depth: Int): ExecutableInputScope {
        catalog.requireBindings(root.calculation, root.bindings)
        val arguments = CalculationInstantiation.arguments(root.calculation.parameters, emptyMap(), root.arguments, root.bindings)
        val actual = CalculationInstantiation.relationArguments(root.calculation, CalculationInstantiation.memberArguments(root.calculation, root.bindings, root.members), root.relations)
        val bindings = CalculationBindings(actual.dimensions, CalculationInstantiation.resolveMembers(actual.members, actual, arguments), actual.groupingCandidates, actual.measurements, actual.relations)
        catalog.requireBoundBindings(root.calculation, bindings)
        val invocation = CalculationBinding(this, root, bindings, arguments, callerScope, lexicalScope, depth)
        invocation.bindExternal(root.members, root.relations, MemberEnvironment(caller.callerInputs(), callerScope, callerScope), callerScope, depth, linkedSetOf())
        return invocation.bind("ROOT")
    }

    fun bindMemberCalculation(source: MemberSource.Computed, selection: MemberInvocationBinding.Bound,
        supplied: Map<String, CalculationBindings.Member>, suppliedRelations: Map<String, CalculationBindings.Relation>, caller: MemberEnvironment, bodyScope: ScopeId, depth: Int, stack: MutableSet<String>): ExecutableInputScope {
        val calculation = source.definition.calculation
        val bindings = CalculationInstantiation.relationArguments(calculation, CalculationInstantiation.memberArguments(calculation, source.bindings, supplied), suppliedRelations)
        catalog.requireBoundBindings(calculation, bindings)
        MetricSource.closure(MemberRef(source.id, source.version), supplied, suppliedRelations, { catalog.requireMetric(it).definition }, catalog::requireMetricInstance, catalog::requireMemberSource)
        val invocation = CalculationBinding(this, source, selection, bindings, bodyScope, depth)
        invocation.bindExternal(supplied, suppliedRelations, caller.memberBody(bodyScope), caller.localOwnerScope, depth, stack)
        return invocation.bind(null)
    }

    companion object {
        fun versionKey(id: String, version: String, field: String = "executable version reference"): String {
            require(id.isNotBlank() && version.isNotBlank()) { "$field id/version is required" }
            return "$id@$version"
        }
    }
}
