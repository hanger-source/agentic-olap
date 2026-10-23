package com.olap.prototype.engine.calcite

import com.olap.prototype.contract.JdbcColumnType
import com.olap.prototype.contract.JdbcSourceRequirement.ConstraintKind
import com.olap.prototype.contract.JdbcSourceRequirement.ConstraintUse
import com.olap.prototype.warehouse.contract.JdbcTableConstraints
import org.apache.calcite.rel.RelReferentialConstraint
import org.apache.calcite.rel.RelReferentialConstraintImpl
import org.apache.calcite.rel.type.RelDataType
import org.apache.calcite.rel.type.RelDataTypeFactory
import org.apache.calcite.schema.Statistic
import org.apache.calcite.schema.Statistics
import org.apache.calcite.schema.impl.AbstractTable
import org.apache.calcite.util.ImmutableBitSet
import org.apache.calcite.util.mapping.IntPair

/** 只有原生元数据查询实际读取保证时才记录采用证据。 */
internal class FrozenCalciteTable(private val table: FrozenSourceTables.Table, sources: FrozenSourceTables, private val proofs: SourceProofs) : AbstractTable() {
    private val tables = sources.tables
    private val resolved = sources.connector
    private val path = table.path
    private val names = table.names
    private val frozen = table.columns

    override fun getRowType(factory: RelDataTypeFactory): RelDataType = factory.builder().apply {
        names.forEach { name ->
            val declared = frozen.getValue(name)
            val native = resolved.compilation.jdbcType(factory, declared)
            require(native.isNullable || declared.nullable == false) { "native source type cannot strengthen declared column nullability" }
            if (!native.isNullable) proofs.adopt(path, ConstraintUse(ConstraintKind.NOT_NULL, name))
            add(name, native)
        }
    }.build()

    override fun <C : Any?> unwrap(type: Class<C>): C? {
        if (type == FrozenCatalogSchema.UnavailableKeys::class.java && table.unavailableConstraints != null) {
            val declared = table.unavailableConstraints.evidence.constraints
            val keys = mutableListOf<ImmutableBitSet>()
            addKey(keys, declared.primaryKeyColumns)
            declared.uniqueKeys.filter { it.filterCondition == null && it.expressions == null }.forEach { addKey(keys, it.columns) }
            return type.cast(FrozenCatalogSchema.UnavailableKeys(table.unavailableConstraints.dataSourceId, keys.toList()))
        }
        if (type == FrozenCatalogSchema.TemporalKeys::class.java) return type.cast(FrozenCatalogSchema.TemporalKeys(
            table.verifiedConstraints?.temporalKeys ?: emptyList()) { name -> proofs.adopt(path, ConstraintUse(ConstraintKind.TEMPORAL_KEY, name)) })
        return super.unwrap(type)
    }

    private data class Keys(val keys: List<ImmutableBitSet>, val proofs: List<ConstraintUse>)
    private data class References(val references: List<RelReferentialConstraint>, val proofs: List<ConstraintUse>)

    private fun keys(): Keys {
        val adopted = requireNotNull(table.verifiedConstraints)
        val keys = mutableListOf<ImmutableBitSet>()
        val used = mutableListOf<ConstraintUse>()
        if (addKey(keys, adopted.primaryKeyColumns)) used.add(ConstraintUse(ConstraintKind.PRIMARY_KEY, null))
        adopted.uniqueKeys.forEach { key ->
            if (key.filterCondition == null && key.expressions == null && addKey(keys, key.columns)) used.add(ConstraintUse(ConstraintKind.UNIQUE_KEY, key.name))
        }
        table.sources.forEach { source -> source.snapshotUniqueKeys.forEach { proof ->
            if (names.containsAll(proof.rule.columns)) {
                val key = ImmutableBitSet.of(proof.rule.columns.map(names::indexOf))
                if (key !in keys) { keys.add(key); used.add(ConstraintUse(ConstraintKind.SNAPSHOT_UNIQUE_KEY, proof.rule.name)) }
            }
        } }
        return Keys(keys.toList(), used.toList())
    }

    private fun references(): References {
        val adopted = requireNotNull(table.verifiedConstraints)
        val references = mutableListOf<RelReferentialConstraint>()
        val used = mutableListOf<ConstraintUse>()
        val grouped = adopted.importedKeys.filter { it.name != null }.groupBy { it.name }
        grouped.values.forEach { columns ->
            val key = columns.sortedBy { it.keySequence }
            val target = key.first().referencedTable
            val targetPath = resolved.compilation.tablePath(target.catalog, target.schema, target.table)
            val targetTable = tables[targetPath] ?: return@forEach
            if (targetTable.verifiedConstraints == null) return@forEach
            val targetNames = targetTable.columns.keys.toList()
            val valid = key.withIndex().all { (index, column) -> column.keySequence == index + 1 && column.referencedTable == target &&
                column.column in names && column.referencedColumn in targetNames && frozen.getValue(column.column).nullable == false }
            if (valid && key.isNotEmpty()) {
                val pairs = key.map { IntPair.of(names.indexOf(it.column), targetNames.indexOf(it.referencedColumn)) }
                references.add(RelReferentialConstraintImpl.of(path, targetPath, pairs))
                used.add(ConstraintUse(ConstraintKind.FOREIGN_KEY, key.first().name))
            }
        }
        return References(references.toList(), used.toList())
    }

    override fun getStatistic(): Statistic {
        if (table.verifiedConstraints == null) return Statistics.UNKNOWN
        val keys = keys()
        val references = references()
        val statistics = Statistics.of(null, keys.keys, references.references, emptyList())
        return object : Statistic {
            override fun getKeys(): List<ImmutableBitSet> { proofs.adopt(path, keys.proofs); return keys.keys }
            override fun isKey(columns: ImmutableBitSet): Boolean {
                val result = statistics.isKey(columns)
                if (result) keys.keys.forEachIndexed { index, key -> if (columns.contains(key)) proofs.adopt(path, keys.proofs[index]) }
                return result
            }
            override fun getReferentialConstraints(): List<RelReferentialConstraint> { proofs.adopt(path, references.proofs); return references.references }
        }
    }

    private fun addKey(keys: MutableList<ImmutableBitSet>, columns: List<String>): Boolean {
        if (columns.isEmpty() || !names.containsAll(columns) || columns.any { frozen.getValue(it).nullable != false }) return false
        val key = ImmutableBitSet.of(columns.map(names::indexOf))
        if (key in keys) return false
        keys.add(key); return true
    }
}
