package com.olap.prototype.release.contract

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import com.olap.prototype.calculation.MetricDefinition
import com.olap.prototype.contract.JdbcColumnType
import com.olap.prototype.contract.NativeTableDependency
import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.contract.ProducerContracts
import com.olap.prototype.event.EventIdentity
import com.olap.prototype.event.RelationAccessPath
import com.olap.prototype.metric.MetricBusinessMeaning
import com.olap.prototype.query.SetOutputContract
import com.olap.prototype.warehouse.contract.PhysicalSourceConstraints
import com.olap.prototype.warehouse.contract.SnapshotUniqueKey
import java.util.Collections

class EventExecutionSnapshot(val topologyId: String, val topologyVersion: Long,
    @param:JsonProperty(required = true) val eventTimeLogicUnitId: String?,
    @param:JsonProperty(required = true) val eventTimeLogicUnitVersion: Long?,
    val identity: EventIdentity, roles: Map<String, RelationAccessPath>, val baseFilterJson: String?) {
    val roles: Map<String, RelationAccessPath> = java.util.Map.copyOf(roles)
    init {
        ProducerContracts.requireText(topologyId, "event_snapshot.topologyId"); ProducerContracts.requireVersion(topologyVersion, "event_snapshot.topologyVersion")
        ProducerContracts.requireVersionedRefPair(eventTimeLogicUnitId, eventTimeLogicUnitVersion, "event_snapshot time role")
        if (eventTimeLogicUnitId != null) ProducerContracts.requireText(eventTimeLogicUnitId, "event_snapshot.eventTimeLogicUnitId")
    }
    override fun equals(other: Any?): Boolean = other is EventExecutionSnapshot && topologyId == other.topologyId && topologyVersion == other.topologyVersion && eventTimeLogicUnitId == other.eventTimeLogicUnitId && eventTimeLogicUnitVersion == other.eventTimeLogicUnitVersion && identity == other.identity && roles == other.roles && baseFilterJson == other.baseFilterJson
    override fun hashCode(): Int = listOf(topologyId, topologyVersion, eventTimeLogicUnitId, eventTimeLogicUnitVersion, identity, roles, baseFilterJson).hashCode()
}
class TopologyExecutionSnapshot(val topologyId: String, val topologyVersion: Long, val topologyImplementationNamespaceCode: String, val topologyImplementationNamespaceDisplayName: String, val backboneLogicalTableId: String, val backboneLogicalTableVersion: Long, joinPathRefs: List<ReleaseJoinPathRef>) {
    val joinPathRefs: List<ReleaseJoinPathRef> = java.util.List.copyOf(joinPathRefs)
    init {
        ProducerContracts.requireText(topologyId, "topology_snapshot.topologyId"); ProducerContracts.requireVersion(topologyVersion, "topology_snapshot.topologyVersion")
        ProducerContracts.requireText(topologyImplementationNamespaceCode, "topology_snapshot.topologyImplementationNamespaceCode")
        ProducerContracts.requireText(topologyImplementationNamespaceDisplayName, "topology_snapshot.topologyImplementationNamespaceDisplayName")
        ProducerContracts.requireText(backboneLogicalTableId, "topology_snapshot.backboneLogicalTableId"); ProducerContracts.requireVersion(backboneLogicalTableVersion, "topology_snapshot.backboneLogicalTableVersion")
    }
    override fun equals(other: Any?): Boolean = other is TopologyExecutionSnapshot && topologyId == other.topologyId && topologyVersion == other.topologyVersion && topologyImplementationNamespaceCode == other.topologyImplementationNamespaceCode && topologyImplementationNamespaceDisplayName == other.topologyImplementationNamespaceDisplayName && backboneLogicalTableId == other.backboneLogicalTableId && backboneLogicalTableVersion == other.backboneLogicalTableVersion && joinPathRefs == other.joinPathRefs
    override fun hashCode(): Int = listOf(topologyId, topologyVersion, topologyImplementationNamespaceCode, topologyImplementationNamespaceDisplayName, backboneLogicalTableId, backboneLogicalTableVersion, joinPathRefs).hashCode()
}
data class MetricExecutionSnapshot(val definition: MetricDefinition, val businessMeaning: MetricBusinessMeaning)

class LogicUnitExecutionSnapshot(val formatVersion: String, val physicalExecutionBindingId: String, val physicalExecutionBindingVersion: String, val logicUnitId: String, val logicUnitVersion: Long, val valueType: String?, val valueShape: SetOutputContract.Shape?, val logicalTableId: String, val logicalTableVersion: Long, val physicalTableId: String, val physicalTableVersion: Long, val dataSourceId: String, val catalogName: String?, val schemaName: String?, val tableName: String, val dialect: String, logicUnitBindings: Map<String, JsonNode>, physicalColumnBindings: Map<String, String>, physicalColumnTypes: Map<String, JdbcColumnType>, nativeTableDependencies: Map<String, NativeTableDependency>,
    @param:JsonProperty(value = "sourceConstraints", required = true) val sourceConstraints: PhysicalSourceConstraints) {
    private val frozenLogicUnitBindings: Map<String, JsonNode> = Collections.unmodifiableMap(logicUnitBindings.mapValuesTo(linkedMapOf()) { it.value.deepCopy<JsonNode>() })
    val logicUnitBindings: Map<String, JsonNode> get() = Collections.unmodifiableMap(frozenLogicUnitBindings.mapValuesTo(linkedMapOf()) { it.value.deepCopy<JsonNode>() })
    val physicalColumnBindings: Map<String, String> = java.util.Map.copyOf(physicalColumnBindings)
    val physicalColumnTypes: Map<String, JdbcColumnType> = java.util.Map.copyOf(physicalColumnTypes)
    val nativeTableDependencies: Map<String, NativeTableDependency> = java.util.Map.copyOf(nativeTableDependencies)
    init {
        if (formatVersion != FORMAT_VERSION) throw ProducerContractException("unsupported LogicUnit execution snapshot format: $formatVersion")
        if ((valueType == null) == (valueShape == null)) throw ProducerContractException("LogicUnit snapshot requires one scalar or container type")
        if (valueShape != null) SetOutputContract.requireSourceContainer(valueShape)
    }
    override fun equals(other: Any?): Boolean = other is LogicUnitExecutionSnapshot && formatVersion == other.formatVersion && physicalExecutionBindingId == other.physicalExecutionBindingId && physicalExecutionBindingVersion == other.physicalExecutionBindingVersion && logicUnitId == other.logicUnitId && logicUnitVersion == other.logicUnitVersion && valueType == other.valueType && valueShape == other.valueShape && logicalTableId == other.logicalTableId && logicalTableVersion == other.logicalTableVersion && physicalTableId == other.physicalTableId && physicalTableVersion == other.physicalTableVersion && dataSourceId == other.dataSourceId && catalogName == other.catalogName && schemaName == other.schemaName && tableName == other.tableName && dialect == other.dialect && frozenLogicUnitBindings == other.frozenLogicUnitBindings && physicalColumnBindings == other.physicalColumnBindings && physicalColumnTypes == other.physicalColumnTypes && nativeTableDependencies == other.nativeTableDependencies && sourceConstraints == other.sourceConstraints
    override fun hashCode(): Int = listOf(formatVersion, physicalExecutionBindingId, physicalExecutionBindingVersion, logicUnitId, logicUnitVersion, valueType, valueShape, logicalTableId, logicalTableVersion, physicalTableId, physicalTableVersion, dataSourceId, catalogName, schemaName, tableName, dialect, frozenLogicUnitBindings, physicalColumnBindings, physicalColumnTypes, nativeTableDependencies, sourceConstraints).hashCode()
    companion object { const val FORMAT_VERSION = "olap-logic-unit-execution/v5" }
}
data class SourceUniqueKeySnapshot(val proofId: String, val physicalTableId: String, val physicalTableVersion: Long, val discoverySnapshotId: String, val schemaHash: String, val sourceIdentityHash: String, val key: SnapshotUniqueKey) {
    init {
        require(listOf(proofId, physicalTableId, discoverySnapshotId, schemaHash, sourceIdentityHash).none { it.isBlank() }) { "source unique snapshot requires exact identities" }
        require(physicalTableVersion > 0) { "exact physical version is required" }
    }
}
