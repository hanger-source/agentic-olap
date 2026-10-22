package com.olap.prototype.engine.catalog

data class ExecutableAnalysisDimensionEventMapping(val mappingId: String, val analysisDimensionId: String,
    val analysisDimensionVersion: String, val levelCode: String, val eventId: String, val eventVersion: String,
    val dimensionId: String, val dimensionVersion: String, val dimensionBindingId: String, val dimensionBindingVersion: String,
    val logicUnitId: String, val logicUnitVersion: String, val valueAlignmentKey: String?, val valueAlignmentStatus: String)
