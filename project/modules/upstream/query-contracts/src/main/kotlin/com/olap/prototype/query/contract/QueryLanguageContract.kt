package com.olap.prototype.query.contract

import com.olap.prototype.contract.ContractSelectionException
import com.olap.prototype.calculation.Calculation
import com.olap.prototype.query.*
import com.olap.prototype.time.TemporalWindow
import com.olap.prototype.util.OlapJsonUtils
import com.olap.prototype.util.WireTypeShape

/** 完整与局部发现共用真实语言owner，不定义第二套DSL。 */
object QueryLanguageContract {
    class Selection(val section: String?, val root: String?, val operation: String?, path: List<String>?, val variant: String?) {
        val path: List<String>? = path?.let { java.util.List.copyOf(it) }
        override fun equals(other: Any?): Boolean = other is Selection && section == other.section && root == other.root && operation == other.operation && path == other.path && variant == other.variant
        override fun hashCode(): Int = listOf(section, root, operation, path, variant).hashCode()
    }
    fun describe(selection: Selection): Map<String, Any?> {
        val selected = listOfNotNull(selection.section, selection.root, selection.operation).size
        val path = selection.path.orEmpty()
        if (selected > 1) throw ContractSelectionException("section, root and operation are mutually exclusive")
        val result = linkedMapOf<String, Any?>("formatVersion" to "olap-query-language-contract/v7", "discovery" to discoveryContract())
        when {
            selected == 0 -> {
                requireNoNavigation(path, selection.variant)
                result.putAll(mapOf("intentContract" to PublishedQueryContract.intentContract(), "timeRangeContract" to TemporalWindow.contract(),
                    "temporalClockContract" to TemporalClockBindings.contract(), "sourceEventConditionContract" to QueryComputation.sourceEventConditionContract(),
                    "filterValueContract" to ScalarValueType.contract(), "rowPopulationContract" to RowPopulationContract.contract(),
                    "calculationInputContract" to calculationInputContract(),
                    "scopedInputContract" to ScopedInputContract.contract(), "postOperationContract" to PostOperationSchema.contract()))
            }
            selection.operation != null -> {
                requireNoNavigation(path, selection.variant)
                if (selection.operation !in PostOperationContract.operationTypes()) throw ContractSelectionException("unknown post-operation contract: ${selection.operation}")
                result.putAll(mapOf("operation" to selection.operation, "arguments" to PostOperationSchema.argumentContract(selection.operation),
                    "aggregateValueContract" to PostOperationSchema.aggregateValueContract(), "statistics" to PostOperationSchema.statisticsContract()))
            }
            selection.root != null -> {
                val shapes = QueryComputation.wireShapes()
                val type = shapes.select(OlapJsonUtils.mapper().constructType(QueryComputation.Root::class.java), emptyList(), selection.root)
                result["root"] = selection.root; result["path"] = path
                selection.variant?.let { result["variant"] = it }
                result["inputShape"] = shapes.describe(type, "olap-wire-type-shape/v1", path, selection.variant)
                @Suppress("UNCHECKED_CAST")
                val rules = (QueryComputation.rootContracts()[selection.root] as Map<String, Any>).toMutableMap()
                rules.remove("wireShape"); result["rules"] = rules
                result["calculationInputContract"] = calculationInputContract()
            }
            selection.section == "intent" -> { requireNoNavigation(path, selection.variant); result["intentContract"] = PublishedQueryContract.intentContract() }
            selection.section == "scope" -> {
                result["scopedInputContract"] = ScopedInputContract.scopeContract()
                result["calculationInputContract"] = calculationInputContract()
                if (path.isNotEmpty() || selection.variant != null) {
                    result["path"] = path; selection.variant?.let { result["variant"] = it }
                    result["inputShape"] = QueryComputation.wireShapes().describe(OlapJsonUtils.mapper().constructType(QueryComputation.Scope::class.java), "olap-wire-type-shape/v1", path, selection.variant)
                }
            }
            else -> throw ContractSelectionException("unknown language section: ${selection.section}")
        }
        return WireTypeShape.withContentHash(result)
    }
    private fun requireNoNavigation(path: List<String>, variant: String?) {
        if (path.isNotEmpty() || variant != null) throw ContractSelectionException("path and variant require root or section=scope; operation arguments use their codec-owned contract")
    }
    fun calculationInputContract(): Map<String, Any> = mapOf(
        "parameterKinds" to Calculation.ParameterKind.entries.map { it.name },
        "condition" to mapOf("kind" to Calculation.ParameterKind.CONDITION.name, "valueType" to "SOURCE_CONDITION",
            "fields" to listOf("kind", "condition"), "conditionType" to "DimensionCondition",
            "consumption" to "ONLY_EXPLICIT_FACT_INVOCATION_SOURCE_CONDITION_BEFORE_AGGREGATION",
            "algorithmCalls" to "PASS_THROUGH_DECLARED_CONDITION_ARGUMENTS_SOURCE_CONDITION_MUST_BE_TRUE",
            "authorization" to "EFFECTIVE_CONDITION_FILTERS_AND_REQUIRED_RELATION_FILTERS_AT_EACH_EXACT_FACT_EVENT",
            "persistentValues" to "TRUE_ONLY_DEFINITION_LITERALS_DEFAULTS_AND_FIXED_ARGUMENTS_HAVE_NO_PUBLICATION_FILTER_REFERENCES"),
        "relations" to mapOf("declaration" to "INDEPENDENT_RELATION_SLOT_WITH_ORDERED_OUTPUTS_AND_EXPLICIT_KEYS",
            "arguments" to "ALL_AND_ONLY_REMAINING_RELATION_PARAMETERS_FROM_COMPLETED_CALLER_ROOT_INPUTS",
            "outputMapping" to "EXACT_FORMAL_OUTPUT_NAME_TO_COMPLETED_INPUT_SYMBOL",
            "meaning" to "PRESERVE_ACTUAL_SCALAR_QUANTITY_DIMENSION_CONTAINER_AND_COMPLETE_PROVED_KEY_MEANINGS",
            "context" to "ACTUAL_INPUT_SOURCE_MUST_RESOLVE_ONE_SUPPORTED_EXECUTION_CONTEXT",
            "authorization" to "AUTHORIZE_ORIGINAL_CALLER_INPUT_RECURSIVELY"))
    fun discoveryContract(): Map<String, Any?> = mapOf("selection" to "OMIT_FOR_COMPLETE_CONTRACT_OR_SELECT_EXACTLY_ONE_OF_SECTION_ROOT_OPERATION",
        "sections" to listOf("intent", "scope"), "roots" to ScopedInputContract.scopeContract()["rootKinds"], "operations" to PostOperationContract.operationTypes(),
        "path" to "OPTIONAL_EXACT_JSON_FIELD_NAME_ARRAY_RELATIVE_TO_SELECTED_ROOT_OR_SCOPE_COLLECTIONS_FOLLOW_ELEMENT_OR_VALUE_TYPE_NO_INDEXES",
        "variant" to "OPTIONAL_EXACT_NAMED_UNION_DISCRIMINATOR_AT_PATH_ENDPOINT", "unknownSelectors" to "REJECT", "contentHash" to "SHA256_OF_CANONICAL_RESPONSE_CONTENT_EXCLUDING_CONTENT_HASH")
}
