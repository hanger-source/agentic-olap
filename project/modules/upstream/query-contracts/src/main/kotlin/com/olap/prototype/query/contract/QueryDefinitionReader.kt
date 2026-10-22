package com.olap.prototype.query.contract

fun interface QueryDefinitionReader {
    fun requireByIdAndVersion(definitionId: String, definitionVersion: Long): QueryDefinitionView
}
