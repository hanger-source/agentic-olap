package com.olap.prototype.runtime

import com.olap.prototype.engine.compiler.CompiledQuery
import com.olap.prototype.query.QueryStageAnalysis
import com.olap.prototype.query.RowPopulationContract
import com.olap.prototype.util.OlapJsonUtils

/** 编译结果的公开投影。物理列、SQL、凭据和私有原生错误不进入业务解释。 */
internal object QueryExplanation {
    fun describe(query: CompiledQuery): Map<String, Any?> {
        val analyses = query.stageAnalyses.associateBy { it.scope }
        val scopes = query.scopes.map { scope ->
            val analysis = analyses.getValue(scope.scope)
            mapOf("scope" to scope.scope, "inputMode" to scope.inputMode, "root" to scope.root,
                "qualifications" to scope.qualifications.map { restriction ->
                    mapOf("objectsScope" to restriction.objectsScope, "objectKeys" to restriction.objectKeys,
                        "target" to restriction.target, "effect" to "FILTER_TARGET_RECORDS_WITHOUT_MULTIPLICATION")
                }, "children" to scope.children, "finalGrain" to key(analysis.key),
                "relationInputMatch" to relationMatch(scope.root, analyses),
                "outputs" to analysis.outputs, "selectedObservations" to analysis.selectedObservations,
                "stages" to analysis.stages.map { stage ->
                    mapOf("index" to stage.index, "operation" to stage.operation.operationType,
                        "contract" to stage.operation,
                        "inputs" to stage.inputs.map { it.id }, "imports" to stage.imports.map { it.id },
                        "outputs" to stage.outputs, "grain" to key(stage.key))
                })
        }
        val plan = plan(query.sqlRelationalPlanJson)
        return mapOf("formatVersion" to "olap-query-explanation/v1", "requestId" to query.requestId,
            "summary" to mapOf("scopeCount" to scopes.size, "sourceInputCount" to query.resolvedSources.size,
                "executionBoundary" to "ONE_EXACT_SOURCE_BINDING", "populationContract" to RowPopulationContract.contract(),
                "actualSourceStatistics" to "NOT_OBSERVED", "rowNullAttribution" to "NOT_OBSERVED"),
            "scopes" to scopes,
            "sources" to query.resolvedSources.map { source ->
                mapOf("scope" to source.scope, "identity" to source.identity, "eventId" to source.eventId,
                    "eventVersion" to source.eventVersion, "recordIdentity" to source.eventIdentity,
                    "range" to source.range, "clocks" to source.clocks,
                    "positions" to source.positions, "joins" to source.joins, "relations" to source.relations)
            },
            "plan" to plan,
            "compilationScale" to query.compilationScale)
    }

    private fun relationMatch(root: Map<String, Any?>, analyses: Map<QueryStageAnalysis.ScopeId, QueryStageAnalysis.Analysis>): Map<String, Any?>? {
        if (root["kind"] != "RELATION") return null
        val inputScope = root.getValue("inputScope") as QueryStageAnalysis.ScopeId
        val actual = analyses.getValue(inputScope)
        return mapOf("status" to "MATCHED", "inputScope" to inputScope, "actualOutputs" to actual.outputs,
            "actualGrain" to key(actual.key), "requirements" to root.getValue("requirements"),
            "projection" to root.getValue("outputs"), "population" to "INHERITED_FROM_EXACT_INPUT_SCOPE",
            "effect" to "PRESERVE_ACTUAL_MEANING_NO_IMPLICIT_REGROUP_DEDUPLICATION_OR_MISSING_GROUP_COMPLETION")
    }

    private fun key(key: QueryStageAnalysis.Key): Map<String, Any?> = when (key) {
        is QueryStageAnalysis.UniqueKey -> mapOf("kind" to "PROVEN_UNIQUE", "columns" to key.columns)
        QueryStageAnalysis.UnknownKey.INSTANCE -> mapOf("kind" to "BAG_OR_UNPROVEN", "columns" to null)
    }

    private fun plan(encoded: String?): List<Map<String, Any?>> {
        if (encoded == null) return emptyList()
        val scans = linkedMapOf<String, String>()
        val nodes = OlapJsonUtils.mapper().readTree(encoded).required("rels")
        var previousId: String? = null
        return nodes.map { node ->
            // RelJson 对紧邻前驱的单输入省略 inputs；这是编码规则，并非无输入节点。
            val inputs = node.get("inputs")?.map { it.asText() }
                ?: listOf(requireNotNull(previousId) { "implicit relation input requires its preceding node" })
            val item = linkedMapOf<String, Any?>("id" to node.required("id").asText(),
                "operator" to node.required("relOp").asText(), "inputs" to inputs)
            node.get("table")?.let { table -> item["scan"] = scans.getOrPut(table.toString()) { "scan-${scans.size + 1}" } }
            node.get("joinType")?.let { item["joinType"] = it.asText() }
            node.get("group")?.let { item["groupColumns"] = it }
            node.get("aggs")?.let { aggregates -> item["aggregations"] = aggregates.map { it.path("agg").asText() } }
            previousId = node.required("id").asText()
            item
        }
    }
}
