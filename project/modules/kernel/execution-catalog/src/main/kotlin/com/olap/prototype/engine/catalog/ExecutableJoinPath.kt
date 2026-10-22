package com.olap.prototype.engine.catalog

import com.olap.prototype.query.TemporalJoinContract

data class ExecutableJoinPath(val joinPathId: String, val joinPathVersion: String, val topologyId: String, val topologyVersion: String,
    val sourceLogicalTableId: String, val sourceLogicalTableVersion: String, val targetLogicalTableId: String,
    val targetLogicalTableVersion: String, val joinType: String, val cardinality: String,
    val temporalConstraint: TemporalJoinContract.Constraint?, val nullPolicy: String, val keys: List<ExecutableJoinKey> = emptyList())
