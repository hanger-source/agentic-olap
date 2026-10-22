package com.olap.prototype.engine.catalog

data class ExecutableJoinKey(val order: Int, val sourceLogicUnitId: String, val sourceLogicUnitVersion: String,
    val targetLogicUnitId: String, val targetLogicUnitVersion: String)
