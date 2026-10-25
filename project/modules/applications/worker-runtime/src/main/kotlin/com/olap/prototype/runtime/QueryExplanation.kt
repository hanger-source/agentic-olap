package com.olap.prototype.runtime

import com.olap.prototype.engine.compiler.CompiledQuery
import com.olap.prototype.engine.compiler.CompiledScopeExplanation
import com.olap.prototype.engine.catalog.ExecutableRelationPosition
import com.olap.prototype.engine.executable.ExecutableRelationInput
import com.olap.prototype.query.QueryExplanationView as View
import com.olap.prototype.query.QueryStageAnalysis
import com.olap.prototype.query.RowPopulationContract
import com.olap.prototype.contract.JdbcSourceRequirement
import com.olap.prototype.contract.JdbcSourceRequirement.ConstraintKind

/** 只投影引擎已经产出的事实；原生计划JSON是Calcite只读诊断，不是作者语义输入。 */
internal object QueryExplanation {
    fun describe(query: CompiledQuery): View {
        val analyses = query.stageAnalyses.associateBy { it.scope }
        val coreScopes = query.coreFinalizationPlan?.scopes.orEmpty()
        val sourceOutputScope = coreScopes.firstOrNull() ?: QueryStageAnalysis.ScopeId.root()
        val scopes = query.scopes.map { scope ->
            val analysis = analyses.getValue(scope.scope)
            val root = root(scope.root)
            View.Scope(scope.scope, scope.inputMode, root,
                scope.qualifications.map { View.Qualification(it.objectsScope, it.objectKeys, it.target,
                    "FILTER_TARGET_RECORDS_WITHOUT_MULTIPLICATION", it.recordSlots, it.recordMeanings) },
                scope.children.map { View.Child(it.kind.name, it.name, it.scope) }, analysis.key,
                if (root is View.Root.Relation) {
                    val actual = analyses.getValue(root.inputScope)
                    View.RelationMatch("MATCHED", root.inputScope, actual.outputs, actual.key, root.requirements, root.outputs,
                        "INHERITED_FROM_EXACT_INPUT_SCOPE", "PRESERVE_ACTUAL_MEANING_NO_IMPLICIT_REGROUP_DEDUPLICATION_OR_MISSING_GROUP_COMPLETION")
                } else null,
                analysis.outputs, analysis.selectedObservations,
                analysis.stages.map { View.Stage(it.index, it.operation.operationType, it.operation, it.inputs.map { symbol -> symbol.id },
                    it.imports.map { symbol -> symbol.id }, it.outputs, it.key,
                    if (scope.scope in coreScopes) View.ExecutionLocation.CORE else View.ExecutionLocation.SOURCE) },
                if (scope.scope in coreScopes && scope.scope != sourceOutputScope) View.ExecutionLocation.CORE else View.ExecutionLocation.SOURCE)
        }
        val scale = query.compilationScale?.let { v ->
            fun size(s: com.olap.prototype.engine.compiler.CompilationScale.RelationSize) = View.RelationSize(s.nodes, s.edges, s.scalarCalls, s.numericChecks, s.repeatedNumericChecks)
            View.CompilationScale(size(v.raw), size(v.optimized), size(v.sqlRelation), v.sqlBytes, v.sqlCaseExpressions, v.sqlNumericChecks, v.numericBindings, v.reusedNumericChecks)
        }
        val requirements = query.executableLoweringResult?.let { lowered ->
            val request = lowered.toSourceRequest("execute-query", null)
            View.ExecutionRequirements(request.requiredCapabilities.map { it.name }, request.relationChecks.map { check ->
                View.RelationCheck(check.code, check.origin?.scope, check.origin?.keys ?: emptyList(), check.origin?.reason)
            }, request.sourceRequirements.flatMap { it.constraintUses }.map { it.kind.name }.distinct().sorted(), false,
                request.sourceRequirements.filter { it.constraintUses.isNotEmpty() }
                    .sortedWith(compareBy({ it.dataSourceId }, { it.physicalTableId }, { it.physicalTableVersion }))
                    .map(::sourceConstraints))
        }
        val boundary = if (query.coreFinalizationPlan == null) "ONE_EXACT_SOURCE_BINDING" else "ONE_EXACT_SOURCE_BINDING_THEN_CORE_SCALAR_FINALIZATION"
        return View(query.requestId, View.Summary(scopes.size, query.resolvedSources.size, boundary, RowPopulationContract.contract(),
            "NOT_OBSERVED", "NOT_OBSERVED", sourceOutputScope), scopes, query.resolvedSources.map(::source), query.sqlPlan, scale, requirements)
    }
    private fun sourceConstraints(source: JdbcSourceRequirement): View.SourceConstraints {
        val constraints = source.constraintUses.sortedWith(compareBy({ it.kind.name }, { it.name ?: "" })).map { use ->
            val declared = source.evidence.constraints
            val columns = when (use.kind) {
                ConstraintKind.NOT_NULL -> listOf(requireNotNull(use.name))
                ConstraintKind.PRIMARY_KEY -> declared.primaryKeyColumns
                ConstraintKind.UNIQUE_KEY -> declared.uniqueKeys.single { it.name == use.name }.columns
                ConstraintKind.FOREIGN_KEY -> declared.importedKeys.filter { it.name == use.name }.sortedBy { it.keySequence }.map { it.column }
                ConstraintKind.TEMPORAL_KEY -> declared.temporalKeys.single { it.rule.name == use.name }.rule.columns().sorted()
                ConstraintKind.SNAPSHOT_UNIQUE_KEY -> source.snapshotUniqueKeys.single { it.rule.name == use.name }.rule.columns
            }
            val adoptedFor = when (use.kind) {
                ConstraintKind.NOT_NULL -> "NON_NULL_NATIVE_COLUMN_TYPE"
                ConstraintKind.PRIMARY_KEY, ConstraintKind.UNIQUE_KEY, ConstraintKind.SNAPSHOT_UNIQUE_KEY -> "OPTIMIZER_KEY_METADATA"
                ConstraintKind.FOREIGN_KEY -> "OPTIMIZER_REFERENCE_METADATA"
                ConstraintKind.TEMPORAL_KEY -> "TEMPORAL_RELATION_RULE"
            }
            View.SourceConstraint(use.kind, use.name, columns.associateWith { source.evidence.columnVersionRefs.getValue(it) }, adoptedFor)
        }
        return View.SourceConstraints(source.bindingRef(), source.connectorRef, source.dialect, source.physicalTableId,
            source.physicalTableVersion, source.table.catalog, source.table.schema, source.table.table, constraints)
    }
    private fun requirement(v: ExecutableRelationInput.Requirement) = View.RelationRequirement(v.outputSlots, v.outputs, v.keys)
    private fun root(v: CompiledScopeExplanation.Root): View.Root = when (v) {
        is CompiledScopeExplanation.Root.Measures -> View.Root.Measures(v.rowPopulation, v.calls, v.timeRange, v.clocks, v.outputCondition)
        is CompiledScopeExplanation.Root.Members -> View.Root.Members(when (val origin = v.source.origin) {
            is CompiledScopeExplanation.MemberOrigin.Projection -> View.MemberOrigin.Projection(origin.id, origin.version)
            is CompiledScopeExplanation.MemberOrigin.Computed -> View.MemberOrigin.Computed(origin.id, origin.version, origin.computationScope)
            is CompiledScopeExplanation.MemberOrigin.Local -> View.MemberOrigin.Local(origin.inputScope)
        }, v.source.outputs, v.source.condition, v.source.range)
        is CompiledScopeExplanation.Root.Input -> View.Root.Input(v.inputId)
        is CompiledScopeExplanation.Root.Relation -> View.Root.Relation(v.inputScope, v.outputs, v.keys, v.requirements.map(::requirement))
        is CompiledScopeExplanation.Root.ObjectMeasure -> View.Root.ObjectMeasure(v.roles)
        is CompiledScopeExplanation.Root.AlignMeasurements -> View.Root.AlignMeasurements(v.keys, v.observationKeys, v.population)
        is CompiledScopeExplanation.Root.ObservationStatistics -> View.Root.ObservationStatistics(v.unit, v.outerKeys)
        is CompiledScopeExplanation.Root.SetInputs -> View.Root.SetInputs(v.operation, v.all, v.inputs)
    }
    private fun position(v: ExecutableRelationPosition.Id) = View.RelationPosition(v.scope, v.role, v.path)
    private fun position(v: CompiledQuery.PositionEvidence) = View.Position(position(v.position), v.bindingId, v.bindingVersion)
    private fun join(v: CompiledQuery.JoinEvidence) = View.Join(v.joinPathId, v.joinPathVersion, position(v.source), position(v.target), v.sourceTimes.mapValues { position(it.value) }, v.clockSlots, v.requiredMatch)
    private fun quantified(v: CompiledQuery.QuantifiedEvidence): View.Quantified = View.Quantified(v.occurrence, position(v.parent), v.positions.map(::position), v.joins.map(::join), v.clocks, v.quantifiers.map(::quantified))
    private fun source(v: CompiledQuery.SourceEvidence): View.Source = View.Source(v.scope, when (val identity = v.identity) {
        is CompiledQuery.FactIdentity -> View.SourceIdentity.Fact(identity.callId, identity.owner, identity.metric, identity.grouping, identity.filter)
        is CompiledQuery.MemberIdentity -> View.SourceIdentity.Member(identity.sourceId, identity.sourceVersion, identity.outputs, identity.meanings, identity.condition)
    }, v.eventId, v.eventVersion, v.eventIdentity, v.range, v.clocks, v.positions.map(::position), v.joins.map(::join), v.quantifiers.map(::quantified),
        v.relations.map { View.RelationSource(it.instanceId, it.filterRefId, source(it.source)) })

}
