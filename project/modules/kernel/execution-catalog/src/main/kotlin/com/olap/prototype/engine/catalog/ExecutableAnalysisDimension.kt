package com.olap.prototype.engine.catalog

data class ExecutableAnalysisDimension(val analysisDimensionId: String, val analysisDimensionVersion: String,
    val displayName: String?, val analysisDimensionCode: String, val outputAlias: String, val defaultLevelCode: String?,
    val levels: List<ExecutableAnalysisDimensionLevel> = emptyList(), val eventMappings: List<ExecutableAnalysisDimensionEventMapping> = emptyList())
