package com.olap.prototype.management.governance

import com.olap.prototype.event.RelationAccessPath
import com.olap.prototype.governance.contract.TopologyRecord
import com.olap.prototype.query.TemporalJoinContract

interface TopologyReferenceResolver {
    fun topologySnapshot(topologyId: String, topologyVersion: Long): TopologyRecord
    fun temporalConstraint(joinPathId: String, joinPathVersion: Long): TemporalJoinContract.Constraint?
    fun requireAccessPath(topologyId: String, topologyVersion: Long, path: RelationAccessPath, field: String)
    fun requirePositionedLogicUnit(topologyId: String, topologyVersion: Long, path: RelationAccessPath, logicUnitId: String, logicUnitVersion: Long, field: String)
    fun requireRelatedTopology(topologyId: String, topologyVersion: Long, path: RelationAccessPath, targetTopologyId: String, targetTopologyVersion: Long, field: String)
    fun requireReachableLogicUnit(topologyId: String, topologyVersion: Long?, logicUnitId: String, logicUnitVersion: Long?, field: String)
}
