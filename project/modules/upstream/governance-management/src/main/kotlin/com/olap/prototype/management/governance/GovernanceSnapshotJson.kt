package com.olap.prototype.management.governance

import com.fasterxml.jackson.databind.JsonNode
import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.contract.ProducerContracts.requireText
import com.olap.prototype.event.RelationAccessPath
import com.olap.prototype.query.ScopedInputContract
import com.olap.prototype.util.OlapJsonUtils
import java.util.Collections

/** 正文 JSON 原语不选择版本，不决定聚合的字段与引用规则。 */
internal object GovernanceSnapshotJson {
    fun rolePaths(content: JsonNode): Map<String, RelationAccessPath> {
        val roles = content.required("roles")
        if (!roles.isObject) throw ProducerContractException("role bindings must be explicit")
        val result = sortedMapOf<String, RelationAccessPath>()
        roles.fields().forEachRemaining { entry ->
            ScopedInputContract.requireId(entry.key)
            result[entry.key] = try { OlapJsonUtils.mapper().treeToValue(entry.value, RelationAccessPath::class.java) }
            catch (failure: Exception) { throw ProducerContractException("invalid exact role access path", failure) }
        }
        return Collections.unmodifiableMap(result)
    }
    fun requiredText(root: JsonNode, field: String, rootField: String = "physical_table_version.content_json"): String =
        requireText(optionalText(root, field), "$rootField.$field")
    fun optionalText(root: JsonNode, field: String): String? = root.get(field)?.takeUnless { it.isNull }?.asText()
    fun requiredCoordinate(root: JsonNode, field: String, rootField: String): String =
        root.get(field)?.takeUnless { it.isNull }?.asText() ?: throw ProducerContractException("$rootField.$field is required")
    fun optionalLong(root: JsonNode, field: String): Long? = root.get(field)?.takeUnless { it.isNull }?.longValue()
    fun requiredBoolean(root: JsonNode, field: String, rootField: String): Boolean =
        root.get(field)?.takeIf { it.isBoolean }?.booleanValue() ?: throw ProducerContractException("$rootField.$field must be boolean")
    fun requiredInteger(root: JsonNode, field: String, rootField: String): Int =
        root.get(field)?.takeIf { it.isIntegralNumber }?.intValue() ?: throw ProducerContractException("$rootField.$field must be integer")
    fun requiredLong(root: JsonNode, field: String, rootField: String): Long =
        optionalLong(root, field)?.takeIf { it > 0 } ?: throw ProducerContractException("$rootField.$field is required and must be positive")
    fun requireMatchingId(expected: String, actual: String, objectType: String) {
        if (expected != actual) throw ProducerContractException("$objectType snapshot id mismatch: expected=$expected actual=$actual")
    }
    fun requireOptionalMatchingText(root: JsonNode, field: String, expected: String, rootField: String) {
        val actual = optionalText(root, field)
        if (actual != null && actual != expected) throw ProducerContractException("$rootField.$field conflicts with version relation")
    }
    fun requireOptionalMatchingLong(root: JsonNode, field: String, expected: Long, rootField: String) {
        val actual = optionalLong(root, field)
        if (actual != null && actual != expected) throw ProducerContractException("$rootField.$field conflicts with version relation")
    }
}
