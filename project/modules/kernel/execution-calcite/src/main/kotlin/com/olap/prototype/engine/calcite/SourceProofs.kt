package com.olap.prototype.engine.calcite

import com.olap.prototype.contract.JdbcSourceRequirement
import com.olap.prototype.contract.JdbcSourceRequirement.ConstraintUse

/** 关系规则与优化器实际读取的保证，只在优化结束后交出快照。 */
internal class SourceProofs(private val sources: FrozenSourceTables) {
    private val adopted = sources.tables.keys.associateWithTo(linkedMapOf()) { linkedSetOf<ConstraintUse>() }
    fun adopt(path: List<String>, proof: ConstraintUse) { adopted.getValue(path).add(proof) }
    fun adopt(path: List<String>, proofs: List<ConstraintUse>) { adopted.getValue(path).addAll(proofs) }
    fun requirements(): List<JdbcSourceRequirement> = sources.sources.map { source ->
        val constraints = if (source.readsRows()) adopted.getValue(sources.connector.compilation.tablePath(source.table.catalog, source.table.schema, source.table.table))
            .filterTo(linkedSetOf()) { it.kind != JdbcSourceRequirement.ConstraintKind.NOT_NULL || it.name in source.columns }
        else emptySet()
        JdbcSourceRequirement(source.dataSourceId, source.connectorRef, source.dialect, source.physicalTableId, source.physicalTableVersion,
            source.table, source.evidence, source.columns, source.uses, source.snapshotUniqueKeys, constraints)
    }
}
