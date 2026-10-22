package com.olap.prototype.management.governance

import com.olap.prototype.codec.GovernanceProto
import com.olap.prototype.codec.ProtoBytes
import com.olap.prototype.calculation.MetricDefinition
import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.contract.ProducerContracts.requireText
import com.olap.prototype.contract.ProducerContracts.requireVersion
import com.olap.prototype.event.EventIdentity
import com.olap.prototype.event.RelationAccessPath
import com.olap.prototype.governance.contract.*
import com.olap.prototype.metric.MetricBusinessMeaning
import com.olap.prototype.storage.PlatformDatabase
import java.time.Instant

/** 只从指定版本正文读取事实；逻辑成员和所有者必须与同一版本引用相符。 */
class GovernanceSnapshotReader(private val database: PlatformDatabase) {
    internal val versionRefs = GovernanceVersionRefRepository(database)
    private val physical = PhysicalAssetSnapshots(this)
    private val logical = LogicalModelSnapshots(this)
    private val topology = TopologySnapshots(this)
    private val events = EventSnapshots(this)
    private val metrics = MetricSnapshots(this)
    private val dimensions = DimensionSnapshots(this)
    private val business = BusinessLineSnapshots(this)
    private val subjects = SubjectAreaSnapshots(this)
    private val analysis = AnalysisDimensionSnapshots(this)
    private val scenarios = ScenarioSnapshots(this)

    fun definition(assetType: String, objectId: String, version: Long): GovernanceDefinition {
        val asset = GovernanceDraftVersions.Asset.entries.firstOrNull { it.name == requireText(assetType, "assetType") }
            ?: throw ProducerContractException("unsupported versioned assetType: $assetType")
        return versionSnapshot("${asset.table}_version", asset.column, objectId, version).content
    }
    fun physicalTable(id: String, version: Long): PhysicalTableRecord = physical.physicalTable(id, version)
    fun physicalColumn(id: String, version: Long): PhysicalColumnRecord = physical.physicalColumn(id, version)
    fun physicalTableColumns(id: String, version: Long): List<PhysicalColumnRecord> = physical.physicalTableColumns(id, version)
    internal fun physicalTableContent(id: String, version: Long): GovernanceDefinition.PhysicalTable = versionSnapshot("physical_table_version", "physical_table_id", id, version).content()
    fun logicalTable(id: String, version: Long): LogicalTableRecord = logical.logicalTable(id, version)
    fun logicUnit(id: String, version: Long): LogicUnitRecord = logical.logicUnit(id, version)
    fun logicalTableLogicUnits(id: String, version: Long): List<LogicalTableLogicUnitRefRecord> {
        logicalTable(id, version)
        return versionRefs.listLogicalTableLogicUnitRefs(id, version).onEach { ref ->
            if (logicUnit(ref.logicUnitId, ref.logicUnitVersion).logicalTableId != id)
                throw ProducerContractException("logical_table snapshot contains foreign logic_unit: $id@$version -> ${ref.logicUnitId}@${ref.logicUnitVersion}")
        }
    }
    fun logicUnitOwner(id: String, version: Long): LogicalTableLogicUnitRefRecord {
        val unit = logicUnit(id, version)
        val owner = versionRefs.findLogicalTableRefByLogicUnitVersion(id, version)
        if (unit.logicalTableId != owner.logicalTableId) throw ProducerContractException("logic_unit snapshot owner mismatch: $id@$version")
        logicalTable(owner.logicalTableId, owner.logicalTableVersion)
        return owner
    }
    fun topology(id: String, version: Long): TopologyRecord = topology.topology(id, version)
    fun joinPath(id: String, version: Long): JoinPathRecord = topology.joinPath(id, version)
    fun event(id: String, version: Long): EventRecord = events.event(id, version)
    fun eventRoles(id: String, version: Long): Map<String, RelationAccessPath> = events.eventRoles(id, version)
    fun eventIdentity(id: String, version: Long): EventIdentity = events.eventIdentity(id, version)
    fun metric(id: String, version: Long): MetricRecord = metrics.metric(id, version)
    fun metricBusinessMeaning(id: String, version: Long): MetricBusinessMeaning = metrics.metricBusinessMeaning(id, version)
    fun metricExecutionDefinition(id: String, version: Long): MetricDefinition = metrics.metricExecutionDefinition(id, version)
    fun dimension(id: String, version: Long): DimensionRecord = dimensions.dimension(id, version)
    fun dimensionEventBinding(id: String, version: Long): DimensionEventBindingRecord = dimensions.dimensionEventBinding(id, version)
    fun dimensionBindingRoles(id: String, version: Long): Map<String, RelationAccessPath> = dimensions.dimensionBindingRoles(id, version)
    fun dimensionBindingAccessPath(id: String, version: Long): RelationAccessPath = dimensions.dimensionBindingAccessPath(id, version)
    fun businessLine(id: String, version: Long): BusinessLineRecord = business.businessLine(id, version)
    fun subjectArea(id: String, version: Long): SubjectAreaRecord = subjects.subjectArea(id, version)
    fun analysisDimension(id: String, version: Long): AnalysisDimensionRecord = analysis.analysisDimension(id, version)
    fun scenario(id: String, version: Long): ScenarioRecord = scenarios.scenario(id, version)

    internal fun versionSnapshot(table: String, column: String, id: String, version: Long): VersionSnapshot {
        requireText(id, "$table.$column"); requireVersion(version, "$table.version")
        return try {
            GovernanceJdbcSupport.query(database, "SELECT content_proto, version_status, created_by, created_at, activated_by, activated_at FROM $table WHERE $column=? AND version=?", listOf(id, version)) { rows ->
                VersionSnapshot(GovernanceProto.definition(ProtoBytes.decode(ProtoBytes.stored(requireText(rows.getString("content_proto"), "$table.content_proto")), com.olap.prototype.wire.governance.GovernanceDefinition.getDefaultInstance())), requireText(rows.getString("version_status"), "$table.version_status"), requireText(rows.getString("created_by"), "$table.created_by"), rows.getTimestamp("created_at").toInstant(), rows.getString("activated_by"), GovernanceJdbcSupport.instant(rows, "activated_at"))
            }.singleOrNull() ?: throw ProducerContractException("$table snapshot not found: $id@$version")
        } catch (failure: ProducerContractException) { throw failure }
        catch (failure: Exception) { throw IllegalStateException("Failed to read $table snapshot: $id@$version", failure) }
    }
    internal class VersionSnapshot(val content: GovernanceDefinition, val versionStatus: String, val createdBy: String, val createdAt: Instant, val activatedBy: String?, val activatedAt: Instant?) {
        inline fun <reified T : GovernanceDefinition> content(): T = content as? T ?: throw ProducerContractException("stored definition has a different asset kind")
        fun requireId(expected: String, actual: String) { require(expected == actual) { "stored definition differs from its exact asset identity" } }
    }
}
