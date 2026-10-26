package com.olap.prototype.engine.executable

import com.olap.prototype.calculation.*
import com.olap.prototype.connector.ConnectorRegistry
import com.olap.prototype.engine.catalog.ExecutableCatalog
import com.olap.prototype.member.MemberRef
import com.olap.prototype.member.MemberSource
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
        return completion.finish(CalculationBinding(this, request.computation, ScopeId.root(), 0).bind(null))
    }

    fun bindInline(root: Calculation.Compute, parent: CalculationBinding, caller: ScopeAssembly, callerSyntax: Calculation.Scope,
        callerScope: ScopeId, lexicalScope: ScopeId, depth: Int): ExecutableInputScope {
        val adoption = root.bindings
        if (adoption is Calculation.ExplicitBindings) catalog.requireBindings(root.calculation, adoption.bindings, callerSyntax)
        val application = CalculationInstantiation.compute(root, parent.definition, parent.bindings, parent.arguments)
        catalog.requireBoundBindings(root.calculation, application.bindings)
        val members = if (adoption is Calculation.ParentSlots) adoption.memberSlots.mapValues { (_, slot) -> parent.fullMember(slot, callerScope, depth) } else emptyMap()
        val relations = if (adoption is Calculation.ParentSlots) adoption.relationSlots.mapValues { (slot, source) ->
            this.relations.adopt(root.calculation, application.bindings, slot, parent.fullRelation(source))
        } else emptyMap()
        val invocation = CalculationBinding(this, root, application.bindings, application.arguments, lexicalScope, depth,
            parent.owner, parent.displayName, parent.templateStack(), members, relations)
        if (adoption is Calculation.ExplicitBindings) invocation.bindExternal(adoption.bindings.members, adoption.bindings.relations,
            MemberEnvironment(caller.callerInputs(), callerScope, callerScope), callerScope, depth, parent.templateStack())
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
