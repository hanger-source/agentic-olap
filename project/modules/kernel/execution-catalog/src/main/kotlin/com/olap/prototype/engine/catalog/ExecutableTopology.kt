package com.olap.prototype.engine.catalog

data class ExecutableTopology(val topologyId: String, val topologyVersion: String, val topologyImplementationNamespaceCode: String?,
    val topologyImplementationNamespaceDisplayName: String?, val backboneLogicalTableId: String, val backboneLogicalTableVersion: String,
    val eventRefs: List<ExecutableEventRef> = emptyList(), val joinPathRefs: List<ExecutableJoinPathRef> = emptyList())
