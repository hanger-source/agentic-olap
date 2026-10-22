package com.olap.prototype.governance.contract

import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.contract.ProducerContracts.requireText
import com.olap.prototype.contract.ProducerContracts.requireVersion
import java.time.Instant

object GovernanceContract {
    @JvmStatic fun requireGovernanceLifecycleStatus(value: String?, field: String): String =
        requireText(value, field).also {
            if (it !in setOf("DRAFT", "ACTIVE", "DEPRECATED", "ARCHIVED"))
                throw ProducerContractException("$field must be one of DRAFT, ACTIVE, DEPRECATED, ARCHIVED")
        }

    @JvmStatic fun requireReferenceableVersion(status: String?, objectType: String, objectId: String, version: Long?) {
        if (status != "ACTIVE" && status != "DEPRECATED")
            throw ProducerContractException("approved exact version is required: $objectType#$objectId#$version status=$status")
    }
    @JvmStatic fun requireLong(value: Long?, field: String) { required(value, field) }
    @JvmStatic fun requireBoolean(value: Boolean?, field: String) { required(value, field) }
    @JvmStatic fun requireInteger(value: Int?, field: String) { required(value, field) }
    @JvmStatic fun requireInstant(value: Instant?, field: String) { required(value, field) }
    private fun <T : Any> required(value: T?, field: String): T = value
        ?: throw ProducerContractException("$field is required")
    @JvmStatic fun requireLogicUnitSourceRefRole(value: String?, field: String): String = requireEnum<LogicUnitSourceRefRole>(value, field).name
    @JvmStatic fun requireLogicType(value: String?, field: String): LogicType = requireEnum(value, field)
    @JvmStatic fun requireEnumValue(value: Enum<*>?, field: String): String = required(value, field).name
    private inline fun <reified E : Enum<E>> requireEnum(value: String?, field: String): E {
        val text = requireText(value, field)
        return enumValues<E>().firstOrNull { it.name == text }
            ?: throw ProducerContractException("$field unsupported: $text; allowed=${enumValues<E>().contentToString()}")
    }
    @JvmStatic fun requireMainObject(table: String, id: String?, code: String?, displayName: String?, owner: String?, status: String?, currentVersion: Long?, createdBy: String?, createdAt: Instant?) {
        requireText(id, "$table.id"); requireText(code, "$table.code"); requireText(displayName, "$table.display_name")
        requireText(owner, "$table.owner"); requireGovernanceLifecycleStatus(status, "$table.status")
        requireVersion(currentVersion, "$table.current_version"); requireText(createdBy, "$table.created_by"); requireInstant(createdAt, "$table.created_at")
    }
    @JvmStatic fun requireVersionObject(table: String, objectColumn: String, id: String?, objectId: String?, version: Long?, versionStatus: String?, createdBy: String?, createdAt: Instant?) {
        requireText(id, "$table.id"); requireText(objectId, "$table.$objectColumn"); requireVersion(version, "$table.version")
        requireGovernanceLifecycleStatus(versionStatus, "$table.version_status"); requireText(createdBy, "$table.created_by"); requireInstant(createdAt, "$table.created_at")
    }
}

data class GovernanceRevisionRequest<T>(val objectId: String, val sourceVersion: Long, val definition: T)
data class GovernanceVersionActivationRequest(val objectType: GovernanceActivationObjectType, val objectId: String, val objectVersion: Long, val activatedBy: String, val reason: String)
data class GovernanceVersionActivationResult(val objectType: String, val objectId: String, val objectVersion: Long, val previousActiveVersion: Long?, val status: String, val activatedAt: Instant)
data class GovernanceVersionDeprecationRequest(val objectType: GovernanceActivationObjectType, val objectId: String, val objectVersion: Long, val deprecatedBy: String, val reason: String)
data class GovernanceVersionDeprecationResult(val objectType: String, val objectId: String, val objectVersion: Long, val status: String, val deprecatedAt: Instant)

enum class LogicType(val displayName: String) {
    DATE("日期"), LOCAL_TIMESTAMP("无偏移本地时间戳"), OFFSET_TIMESTAMP("带偏移时间戳"),
    NUMBER("数值"), STRING("文本"), BOOLEAN("布尔"), ENUM("枚举"), ID("标识")
}
enum class LogicUnitSourceRefRole { DIRECT_SOURCE, EXPRESSION_DEPENDENCY }
enum class LogicUnitSourceKind(val sourceRefRole: LogicUnitSourceRefRole) {
    COLUMN(LogicUnitSourceRefRole.DIRECT_SOURCE), EXPRESSION(LogicUnitSourceRefRole.EXPRESSION_DEPENDENCY)
}
enum class LogicalTableRole { FACT, DIM, BRIDGE, SNAPSHOT, AGGREGATE }
enum class TopologyNodeRole { DIM_NODE, BRIDGE_NODE, SNAPSHOT_NODE, AGGREGATE_NODE }

interface PhysicalAssetRegistrar { fun createPhysicalTable(request: PhysicalTableCreateRequest): PhysicalTableCreateResult }
interface PhysicalAssetReferenceResolver {
    fun requirePhysicalTable(physicalTableId: String, field: String)
    fun requirePhysicalTableVersion(physicalTableId: String, version: Long?, field: String)
    fun requirePhysicalColumn(physicalColumnId: String, field: String)
    fun requirePhysicalColumnVersion(physicalColumnId: String, version: Long?, field: String)
}

enum class GovernanceActivationObjectType {
    PHYSICAL_TABLE, LOGICAL_TABLE, TOPOLOGY, EVENT, METRIC, MEMBER_DEFINITION, DIMENSION, DIMENSION_EVENT_BINDING, BUSINESS_LINE, SUBJECT_AREA;
    companion object {
        @JvmStatic fun deprecationContract(): Map<String, Any> = mapOf(
            "independentObjectTypes" to entries.map { it.name }, "requiredStatus" to "ACTIVE_EXACT_VERSION",
            "targetStatus" to "DEPRECATED", "ownedVersions" to "EXACT_OWNER_MEMBERS_DEPRECATED_ATOMICALLY",
            "dependentAggregates" to "NO_IMPLICIT_CASCADE_OR_VERSION_REPLACEMENT",
            "newOperations" to "EXACT_DEPENDENCIES_MUST_BE_ACTIVE_OR_DEPRECATED",
            "frozenPublications" to "UNCHANGED_CONTENT_USE_REQUIRES_EXPLICIT_DEPLOYMENT_AUTHORIZATION", "reason" to "REQUIRED_BUSINESS_REASON")
        @JvmStatic fun contract(): Map<String, Any> = mapOf(
            "independentObjectTypes" to entries.map { it.name }, "requiredStatus" to "DRAFT", "upstreamDependencyStatus" to "ACTIVE_OR_DEPRECATED_EXACT_VERSION",
            "physicalSourceRequirements" to listOf("Exact successful registered discovery table, schema hash and complete observed column types", "Frozen source evidence matches the exact owned physical column versions", "Logical tables require source evidence on every exact physical dependency"),
            "ownedVersionTypes" to mapOf("PHYSICAL_TABLE" to listOf("PHYSICAL_COLUMN"), "LOGICAL_TABLE" to listOf("LOGIC_UNIT"), "TOPOLOGY" to listOf("JOIN_PATH"), "SUBJECT_AREA" to listOf("ANALYSIS_DIMENSION", "SCENARIO")),
            "ownedActivation" to "ATOMIC_WITH_OWNER_NOT_SEPARATELY_ACTIVATED",
            "subjectAreaRequirements" to listOf("Exact approved business line and metric/dimension/binding dependencies", "Every owned analysis dimension has exact event mappings", "Every owned scenario grants at least one exact metric or member source", "Scenario analysis dimensions belong to the same exact subject area version"),
            "revisionMembership" to "EXPLICIT_NEW_VERSION_MEMBERS_NOT_AUTOMATICALLY_COPIED")
    }
}
