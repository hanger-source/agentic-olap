package com.olap.prototype.engine.calcite

import com.olap.prototype.expression.SemanticRelMetadataQuery
import org.apache.calcite.plan.RelOptCluster
import org.apache.calcite.plan.RelOptUtil
import org.apache.calcite.rel.RelNode
import org.apache.calcite.rel.core.Join
import org.apache.calcite.rel.core.JoinRelType
import org.apache.calcite.rel.core.RepeatUnion
import org.apache.calcite.util.ArrowSet
import org.apache.calcite.util.ImmutableBitSet
import java.util.IdentityHashMap

/** 一次编译采用的来源连接保证；时态键不是全表唯一键。 */
class SourceRelMetadataQuery private constructor(cluster: RelOptCluster, private val singleMatches: MutableMap<Join, ImmutableBitSet>) :
    SemanticRelMetadataQuery(cluster) {
    companion object {
        fun install(cluster: RelOptCluster) {
            val matches = IdentityHashMap<Join, ImmutableBitSet>()
            cluster.setMetadataQuerySupplier { SourceRelMetadataQuery(cluster, matches) }
            cluster.invalidateMetadataQuery()
        }
        fun adoptSingleMatch(join: Join) {
            if (join.joinType !in setOf(JoinRelType.LEFT, JoinRelType.INNER)) return
            val metadata = join.cluster.metadataQuery as? SourceRelMetadataQuery ?: error("source join requires its compilation metadata owner")
            val left = ImmutableBitSet.range(join.left.rowType.fieldCount)
            metadata.singleMatches[join] = RelOptUtil.InputFinder.bits(join.condition).intersect(left)
            join.cluster.invalidateMetadataQuery()
        }
    }

    override fun areColumnsUnique(rel: RelNode, columns: ImmutableBitSet, ignoreNulls: Boolean): Boolean? {
        if (rel is RepeatUnion && !rel.all && columns.contains(ImmutableBitSet.range(rel.rowType.fieldCount))) return true
        if (rel is Join && singleMatches.containsKey(rel)) {
            val leftColumns = columns.intersect(ImmutableBitSet.range(rel.left.rowType.fieldCount))
            if (areColumnsUnique(rel.left, leftColumns, ignoreNulls) == true) return true
        }
        return super.areColumnsUnique(rel, columns, ignoreNulls)
    }

    override fun getFDs(rel: RelNode): ArrowSet {
        if (rel !is Join) return super.getFDs(rel)
        val width = rel.left.rowType.fieldCount
        val type = rel.joinType
        val dependencies = ArrowSet.Builder()
        if (type == JoinRelType.INNER) dependencies.addArrowSet(super.getFDs(rel)) else {
            // 外连接ON等式不能成为双向FD，只保留经NULL扩展后仍成立的输入事实。
            if (type in setOf(JoinRelType.LEFT, JoinRelType.SEMI, JoinRelType.ANTI)) dependencies.addArrowSet(getFDs(rel.left))
            else if (type in setOf(JoinRelType.RIGHT, JoinRelType.FULL)) addNullableSide(dependencies, rel.left, 0)
            if (type == JoinRelType.RIGHT) addShifted(dependencies, getFDs(rel.right), width)
            else if (type in setOf(JoinRelType.LEFT, JoinRelType.FULL)) addNullableSide(dependencies, rel.right, width)
        }
        singleMatches[rel]?.let { dependencies.addArrow(it, ImmutableBitSet.range(width, rel.rowType.fieldCount)) }
        return dependencies.build()
    }
    private fun addNullableSide(output: ArrowSet.Builder, input: RelNode, offset: Int) {
        getFDs(input).arrows.forEach { arrow ->
            if (arrow.determinants.asList().any { !input.rowType.fieldList[it].type.isNullable })
                output.addArrow(arrow.determinants.shift(offset), arrow.dependents.shift(offset))
        }
    }
    private fun addShifted(output: ArrowSet.Builder, input: ArrowSet, offset: Int) {
        input.arrows.forEach { output.addArrow(it.determinants.shift(offset), it.dependents.shift(offset)) }
    }
}
