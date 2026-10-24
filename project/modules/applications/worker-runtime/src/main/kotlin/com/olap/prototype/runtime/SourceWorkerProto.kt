package com.olap.prototype.runtime

import com.olap.prototype.codec.GovernanceProto
import com.olap.prototype.codec.QuerySourceProto
import com.olap.prototype.source.contract.SourceInspection
import com.olap.prototype.warehouse.WarehouseSourceInspection
import com.olap.prototype.wire.query.*
import com.olap.prototype.wire.query.SourceWorkerCommand as WCommand
import com.olap.prototype.wire.query.SourceWorkerResult as WResult

object SourceWorkerProto {
    fun toProto(v: SourceWorkerCommand): WCommand = WCommand.newBuilder().apply {
        when (v) {
            is SourceWorkerCommand.Discover -> setDiscover(SourceWorkerDiscover.newBuilder()
                .setInspection(GovernanceProto.toProto(v.inspection))
                .addAllTemporalKeys(v.temporalKeys.toSortedMap().map { (table, rules) -> SourceTemporalRules.newBuilder().setTable(table).addAllRules(rules.map(GovernanceProto::toProto)).build() })
                .addAllSnapshotUniqueKeys(v.snapshotUniqueKeys.toSortedMap().map { (table, rules) -> SourceSnapshotRules.newBuilder().setTable(table).addAllRules(rules.map(GovernanceProto::toProto)).build() }))
            is SourceWorkerCommand.Preview -> setPreview(GovernanceProto.toProto(v.inspection))
            is SourceWorkerCommand.Verify -> setVerify(SourceWorkerVerify.newBuilder().setBinding(QuerySourceProto.toProto(v.binding)).setDeclaration(GovernanceProto.toProto(v.declaration)))
        }
    }.build()
    fun fromProto(v: WCommand): SourceWorkerCommand = when (v.kindCase) {
        WCommand.KindCase.DISCOVER -> v.discover.let { r ->
            require(r.hasInspection())
            require(r.temporalKeysList.map { it.table }.distinct().size == r.temporalKeysCount && r.snapshotUniqueKeysList.map { it.table }.distinct().size == r.snapshotUniqueKeysCount) { "duplicate source rule table" }
            SourceWorkerCommand.Discover(GovernanceProto.fromProto(r.inspection),
                r.temporalKeysList.associate { it.table to it.rulesList.map(GovernanceProto::fromProto) }, r.snapshotUniqueKeysList.associate { it.table to it.rulesList.map(GovernanceProto::fromProto) })
        }
        WCommand.KindCase.PREVIEW -> SourceWorkerCommand.Preview(GovernanceProto.fromProto(v.preview))
        WCommand.KindCase.VERIFY -> v.verify.let { require(it.hasBinding() && it.hasDeclaration()); SourceWorkerCommand.Verify(QuerySourceProto.fromProto(it.binding), GovernanceProto.fromProto(it.declaration)) }
        else -> error("source worker command kind is required")
    }
    fun toProto(v: SourceWorkerResult): WResult = WResult.newBuilder().apply {
        when (v) {
            is SourceWorkerResult.Discovery -> setDiscovery(SourceWorkerDiscovery.newBuilder().setSnapshot(GovernanceProto.toProto(v.value.snapshot))
                .addAllUniqueKeys(v.value.uniqueKeys.toSortedMap().map { (table, keys) -> SourceSnapshotKeys.newBuilder().setTable(table).addAllKeys(keys.map(GovernanceProto::toProto)).build() }))
            is SourceWorkerResult.Preview -> setPreview(GovernanceProto.toProto(v.value))
            SourceWorkerResult.Verified -> setVerified(true)
        }
    }.build()
    fun fromProto(v: WResult): SourceWorkerResult = when (v.kindCase) {
        WResult.KindCase.DISCOVERY -> v.discovery.let { r ->
            require(r.hasSnapshot() && r.uniqueKeysList.map { it.table }.distinct().size == r.uniqueKeysCount)
            SourceWorkerResult.Discovery(WarehouseSourceInspection.Discovery(GovernanceProto.fromProto(r.snapshot), r.uniqueKeysList.associate { it.table to it.keysList.map(GovernanceProto::fromProto) }))
        }
        WResult.KindCase.PREVIEW -> SourceWorkerResult.Preview(GovernanceProto.fromProto(v.preview))
        WResult.KindCase.VERIFIED -> { require(v.verified); SourceWorkerResult.Verified }
        else -> error("source worker result kind is required")
    }
}
