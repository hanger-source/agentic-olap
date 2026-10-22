package com.olap.prototype.engine.catalog

import com.olap.prototype.contract.ProducerContracts

data class ExecutableTimeAnchor(val eventId: String, val eventVersion: String, val analysisDimensionId: String,
    val analysisDimensionVersion: String, val levelCode: String, val topologyId: String, val topologyVersion: String,
    val topologyImplementationNamespaceCode: String?, val topologyImplementationNamespaceDisplayName: String?,
    val effectiveTimeLogicUnitId: String, val effectiveTimeLogicUnitVersion: String, val effectiveTimeLogicUnitDisplayName: String?,
    val physicalExecutionBindingId: String, val physicalExecutionBindingVersion: String, val timeRole: String?) {
    companion object {
        fun key(eventId: String, eventVersion: String, analysisDimensionId: String, analysisDimensionVersion: String, levelCode: String): String =
            ProducerContracts.requireText(eventId, "time anchor event id") + "@" + ProducerContracts.requireText(eventVersion, "time anchor event version") + "->" +
                ProducerContracts.requireText(analysisDimensionId, "time anchor analysis dimension id") + "@" +
                ProducerContracts.requireText(analysisDimensionVersion, "time anchor analysis dimension version") + "#" + ProducerContracts.requireText(levelCode, "time anchor levelCode")
    }
}
