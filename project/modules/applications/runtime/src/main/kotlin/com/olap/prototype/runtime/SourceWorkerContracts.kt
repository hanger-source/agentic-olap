package com.olap.prototype.runtime

import com.olap.prototype.source.contract.SourceInspection
import com.olap.prototype.warehouse.WarehouseSourceInspection
import com.olap.prototype.warehouse.contract.*
import com.olap.prototype.warehouse.inspection.WarehousePreview

/** 技术调查只携带获准来源身份；连接与凭据由监督者注入实际worker。 */
sealed interface SourceWorkerCommand {
    data class Discover(val inspection: SourceInspection.Snapshot,
        val temporalKeys: Map<String, List<JdbcTableConstraints.TemporalRule>>,
        val snapshotUniqueKeys: Map<String, List<SnapshotUniqueKey.Rule>>) : SourceWorkerCommand
    data class Preview(val inspection: SourceInspection.Preview) : SourceWorkerCommand
    data class Verify(val binding: SourceBindingRef, val declaration: SealedSourceDataset) : SourceWorkerCommand
}
sealed interface SourceWorkerResult {
    data class Discovery(val value: WarehouseSourceInspection.Discovery) : SourceWorkerResult
    data class Preview(val value: WarehousePreview) : SourceWorkerResult
    data object Verified : SourceWorkerResult
}
