package com.olap.prototype.engine.catalog

data class ExecutableDimension(val dimensionId: String, val dimensionVersion: String, val dimensionCode: String?,
    val dimensionCodeNamespace: String, val dimensionLocalCode: String?, val displayName: String?)
