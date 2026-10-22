package com.olap.prototype.query.contract

import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.contract.ProducerContracts
import com.olap.prototype.query.ScalarValueType

class FilterCandidate(val filterRefId: String, val filterTargetType: String, val filterTargetTypeDisplayName: String,
    val queryFieldKind: String, val filterTargetObjectId: String, val filterTargetObjectVersion: Long, val filterTargetDisplayName: String,
    val releaseObjectRefId: String, operators: List<String>, filterValueTypes: Map<String, String>, val filterControlKind: String,
    val filterValueCardinality: String, val valueProviderId: String?, val valueProviderType: String?, val valueProviderLogicUnitId: String?,
    val valueProviderLogicUnitVersion: Long?, eventMappings: List<AnalysisDimensionEventMappingCandidate>, val requiredByDefault: Boolean, val sortOrder: Int) {
    val operators: List<String> = java.util.List.copyOf(operators)
    val filterValueTypes: Map<String, String> = java.util.Map.copyOf(filterValueTypes)
    val eventMappings: List<AnalysisDimensionEventMappingCandidate> = java.util.List.copyOf(eventMappings)
    init {
        ProducerContracts.requireText(filterRefId, "filterRefId"); ProducerContracts.requireText(filterTargetType, "filterTargetType")
        ProducerContracts.requireText(filterTargetTypeDisplayName, "filterTargetTypeDisplayName")
        if (queryFieldKind !in setOf("DIMENSION", "TIME_DIMENSION")) throw ProducerContractException("queryFieldKind must be DIMENSION / TIME_DIMENSION: $queryFieldKind")
        ProducerContracts.requireText(filterTargetObjectId, "filterTargetObjectId"); ProducerContracts.requireVersion(filterTargetObjectVersion, "filterTargetObjectVersion")
        ProducerContracts.requireText(filterTargetDisplayName, "filterTargetDisplayName"); ProducerContracts.requireText(releaseObjectRefId, "releaseObjectRefId")
        if (this.operators.isEmpty()) throw ProducerContractException("filterCandidate.operators must not be empty: $filterRefId")
        if (this.filterValueTypes.isEmpty()) throw ProducerContractException("filterValueTypes must cover published levels")
        this.filterValueTypes.forEach { (level, type) ->
            ProducerContracts.requireText(level, "filterValueTypes.levelCode")
            try { ScalarValueType.valueOf(ProducerContracts.requireText(type, "filterValueType")) }
            catch (failure: IllegalArgumentException) { throw ProducerContractException("filterValueType unsupported: $type") }
        }
        if (filterControlKind !in setOf("TEXT_INPUT", "NUMBER_INPUT", "DATE_PICKER", "BOOLEAN_TOGGLE", "SEARCHABLE_SELECT", "SEARCHABLE_MULTI_SELECT")) throw ProducerContractException("filterControlKind unsupported: $filterControlKind")
        if (filterValueCardinality !in setOf("NONE", "SINGLE", "MULTI", "RANGE", "OPERATOR_DEPENDENT")) throw ProducerContractException("filterValueCardinality unsupported: $filterValueCardinality")
        if (valueProviderId == null) {
            if (valueProviderType != null || valueProviderLogicUnitId != null || valueProviderLogicUnitVersion != null) throw ProducerContractException("absent candidate provider must not carry source metadata")
        } else {
            ProducerContracts.requireText(valueProviderId, "valueProviderId")
            if (valueProviderType != "LOGIC_UNIT_DISTINCT") throw ProducerContractException("valueProviderType unsupported: $valueProviderType")
            ProducerContracts.requireText(valueProviderLogicUnitId, "valueProviderLogicUnitId"); ProducerContracts.requireVersion(valueProviderLogicUnitVersion, "valueProviderLogicUnitVersion")
        }
        if (filterTargetType == "ANALYSIS_DIMENSION") {
            if (this.eventMappings.isEmpty()) throw ProducerContractException("eventMappings must be produced for analysis dimension filters")
            if (this.eventMappings.map { it.levelCode }.toSet() != this.filterValueTypes.keys) throw ProducerContractException("filterValueTypes must exactly cover mapped levels")
        }
    }
    fun filterValueType(levelCode: String): String = filterValueTypes[levelCode] ?: throw ProducerContractException("filter level has no published scalar type: $levelCode")
    override fun equals(other: Any?): Boolean = other is FilterCandidate && filterRefId == other.filterRefId && filterTargetType == other.filterTargetType && filterTargetTypeDisplayName == other.filterTargetTypeDisplayName && queryFieldKind == other.queryFieldKind && filterTargetObjectId == other.filterTargetObjectId && filterTargetObjectVersion == other.filterTargetObjectVersion && filterTargetDisplayName == other.filterTargetDisplayName && releaseObjectRefId == other.releaseObjectRefId && operators == other.operators && filterValueTypes == other.filterValueTypes && filterControlKind == other.filterControlKind && filterValueCardinality == other.filterValueCardinality && valueProviderId == other.valueProviderId && valueProviderType == other.valueProviderType && valueProviderLogicUnitId == other.valueProviderLogicUnitId && valueProviderLogicUnitVersion == other.valueProviderLogicUnitVersion && eventMappings == other.eventMappings && requiredByDefault == other.requiredByDefault && sortOrder == other.sortOrder
    override fun hashCode(): Int = listOf(filterRefId, filterTargetType, filterTargetTypeDisplayName, queryFieldKind, filterTargetObjectId, filterTargetObjectVersion, filterTargetDisplayName, releaseObjectRefId, operators, filterValueTypes, filterControlKind, filterValueCardinality, valueProviderId, valueProviderType, valueProviderLogicUnitId, valueProviderLogicUnitVersion, eventMappings, requiredByDefault, sortOrder).hashCode()
}

enum class FilterLeafOperator { EQ, NE, GT, LT, GTE, LTE, IN, BETWEEN, IS_NULL, IS_NOT_NULL }

object FilterLeafOperatorPolicy {
    private val canonical: List<String> = java.util.List.copyOf(FilterLeafOperator.entries.map { it.name })
    fun canonicalOperators(): List<String> = canonical
    fun requireCanonicalOperator(operator: String?, field: String): String {
        val value = operator?.trim()
        require(value in canonical) { "$field unsupported operator: $value; supported operators: $canonical" }
        return requireNotNull(value)
    }
    fun requireOperatorSet(policyJson: String, field: String): Set<String> {
        val root = try { com.olap.prototype.util.OlapJsonUtils.mapper().readTree(policyJson) }
        catch (failure: Exception) { throw IllegalArgumentException("$field must be valid JSON", failure) }
        require(root != null && root.isObject) { "$field must be a JSON object" }
        val operators = root["operators"]
        require(operators != null && operators.isArray) { "$field.operators is required" }
        val values = linkedSetOf<String>()
        operators.forEach { item ->
            require(item.isTextual && item.textValue().isNotBlank()) { "$field.operators must contain non-empty text values" }
            val operator = requireCanonicalOperator(item.textValue(), "$field.operators")
            require(values.add(operator)) { "$field.operators contains duplicate operator: $operator" }
        }
        require(values.isNotEmpty()) { "$field.operators must not be empty" }
        return java.util.Collections.unmodifiableSet(values)
    }
    fun policyJson(operators: List<String>, field: String): String {
        require(operators.isNotEmpty()) { "$field.operators must not be empty" }
        val values = linkedSetOf<String>()
        operators.forEach { operator ->
            val value = requireCanonicalOperator(operator, "$field.operators")
            require(values.add(value)) { "$field.operators contains duplicate operator: $value" }
        }
        return com.olap.prototype.util.OlapJsonUtils.mapper().createObjectNode().also { node -> node.putArray("operators").also { array -> values.forEach { array.add(it) } } }.toString()
    }
    fun normalizeExecutableOperator(operator: String): String = when (operator.trim().uppercase(java.util.Locale.ROOT)) {
        "=", "EQ" -> "EQ"; "NE" -> "NE"; "IS_NULL" -> "IS_NULL"; "IS_NOT_NULL" -> "IS_NOT_NULL"
        ">", "GT" -> "GT"; "<", "LT" -> "LT"; ">=", "GTE" -> "GTE"; "<=", "LTE" -> "LTE"; "IN" -> "IN"; "BETWEEN" -> "BETWEEN"
        else -> throw IllegalArgumentException("过滤操作符不支持: $operator")
    }
}
