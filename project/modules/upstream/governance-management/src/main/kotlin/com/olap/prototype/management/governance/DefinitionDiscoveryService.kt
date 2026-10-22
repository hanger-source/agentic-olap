package com.olap.prototype.management.governance

import com.olap.prototype.codec.GovernanceProto
import com.olap.prototype.codec.ProtoBytes
import com.olap.prototype.governance.contract.*
import com.olap.prototype.governance.contract.DefinitionDiscovery.Request
import com.olap.prototype.governance.contract.DefinitionDiscovery.Summary
import com.olap.prototype.governance.contract.DefinitionDiscovery.Page
import com.olap.prototype.metric.MetricBusinessMeaning
import com.olap.prototype.storage.PlatformDatabase
import java.sql.ResultSet
import java.util.Locale

/** 有界工作目录只公开摘要；完整正文由精确版本读取。 */
class DefinitionDiscoveryService(private val database: PlatformDatabase) {
    fun search(request: Request): Page = database.inReadSnapshot {
        val kinds = request.assetTypes.sorted()
        val selections = kinds.map { kind ->
            val asset = GovernanceDraftVersions.Asset.valueOf(kind); val table = "${asset.table}_version"
            "SELECT '$kind' AS asset_type,v.${asset.column} AS asset_id,v.version,v.version_status,v.content_proto FROM $table v WHERE v.version_status IN ('DRAFT','ACTIVE') AND NOT EXISTS (SELECT 1 FROM $table candidate WHERE candidate.${asset.column}=v.${asset.column} AND candidate.version_status='DRAFT' AND (v.version_status='ACTIVE' OR candidate.version>v.version))"
        }
        val rows = mutableListOf<Summary>()
        val needle = request.text?.lowercase(Locale.ROOT)
        var skipped = 0
        database.connection().use { connection -> connection.prepareStatement(selections.joinToString(" UNION ALL ") + " ORDER BY asset_type,asset_id,version").use { statement ->
            statement.executeQuery().use { result ->
                while (rows.size <= request.limit && result.next()) {
                    val summary = summary(result)
                    val meaning = summary.businessMeaning?.let { listOf(it.definition, it.unit, it.grain) + it.limitations }.orEmpty()
                    if (needle != null && (listOfNotNull(summary.code, summary.displayName, summary.description, summary.owner) + meaning).none { needle in it.lowercase(Locale.ROOT) }) continue
                    if (skipped < request.offset) skipped++ else rows.add(summary)
                }
            }
        } }
        Page(rows.take(request.limit), if (rows.size > request.limit) Math.addExact(request.offset, request.limit) else null)
    }

    private fun summary(row: ResultSet): Summary {
        val type = row.getString("asset_type")
        val bytes = ProtoBytes.stored(row.getString("content_proto"))
        fun fields(code: String?, displayName: String?, description: String? = null, owner: String? = null, meaning: MetricBusinessMeaning? = null) =
            Summary(type, row.getString("asset_id"), row.getLong("version"), row.getString("version_status"), code, displayName, description, owner, meaning)
        if (type == "MEMBER_DEFINITION") {
            val value = GovernanceProto.fromProto(ProtoBytes.decode(bytes, com.olap.prototype.wire.governance.MemberDefinitionRecord.getDefaultInstance()))
            return fields(value.code, value.displayName, value.description, value.owner)
        }
        return when (val value = GovernanceProto.definition(ProtoBytes.decode(bytes, com.olap.prototype.wire.governance.GovernanceDefinition.getDefaultInstance()))) {
            is GovernanceDefinition.PhysicalTable -> value.record.let { fields(it.code, it.displayName, it.description, it.owner) }
            is GovernanceDefinition.PhysicalColumn -> value.record.let { fields(it.columnName, it.displayName, it.description) }
            is GovernanceDefinition.LogicalTable -> value.record.let { fields(it.code, it.displayName, it.description, it.owner) }
            is GovernanceDefinition.LogicUnit -> value.record.let { fields(it.code, it.displayName) }
            is GovernanceDefinition.Topology -> value.record.let { fields(it.code, it.displayName, it.description, it.owner) }
            is GovernanceDefinition.JoinPath -> value.record.let { fields(it.code, it.code) }
            is GovernanceDefinition.Event -> value.record.let { fields(it.code, it.displayName, it.description, it.owner) }
            is GovernanceDefinition.Metric -> value.record.let { fields(it.code, it.displayName, it.description, it.owner, value.businessMeaning) }
            is GovernanceDefinition.Dimension -> value.record.let { fields(it.code, it.displayName, it.description, it.owner) }
            is GovernanceDefinition.DimensionBinding -> value.record.let { fields(null, null) }
            is GovernanceDefinition.BusinessLine -> value.record.let { fields(it.code, it.displayName, it.description, it.owner) }
            is GovernanceDefinition.SubjectArea -> value.record.let { fields(it.code, it.displayName, it.description, it.owner) }
            is GovernanceDefinition.AnalysisDimension -> value.record.let { fields(it.code, it.displayName, it.description) }
            is GovernanceDefinition.Scenario -> value.record.let { fields(it.code, it.displayName, it.description, it.owner) }
        }
    }
}
