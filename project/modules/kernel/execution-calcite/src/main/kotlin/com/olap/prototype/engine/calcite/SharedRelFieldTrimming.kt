package com.olap.prototype.engine.calcite

import com.olap.prototype.expression.CollectionExpansionRel
import org.apache.calcite.rel.RelNode
import org.apache.calcite.rel.RelVisitor
import org.apache.calcite.rel.type.RelDataTypeField
import org.apache.calcite.sql2rel.RelFieldTrimmer
import org.apache.calcite.tools.RelBuilder
import org.apache.calcite.util.ImmutableBitSet
import org.apache.calcite.util.mapping.MappingType
import org.apache.calcite.util.mapping.Mappings
import java.util.Collections
import java.util.IdentityHashMap

/** 共享节点按所有消费者的列需求裁剪一次；不把同一关系拆成逐消费者的副本。 */
internal object SharedRelFieldTrimming {
    fun trim(root: RelNode): RelNode {
        val uses = IdentityHashMap<RelNode, Int>()
        object : RelVisitor() {
            override fun visit(node: RelNode, ordinal: Int, parent: RelNode?) {
                val count = uses[node] ?: 0
                uses[node] = count + 1
                if (count == 0) super.visit(node, ordinal, parent)
            }
        }.go(root)
        val shared = Collections.newSetFromMap(IdentityHashMap<RelNode, Boolean>())
        uses.forEach { (node, count) -> if (count > 1) shared.add(node) }
        if (shared.isEmpty()) return trimmer(root).trim(root)
        val required = IdentityHashMap<RelNode, ImmutableBitSet>()
        // 丢弃逐分支裁剪产物，只汇总原图中各共享节点实际承载的列（包括排序、关联依赖）。
        trimmer(root, shared, required, collecting = true).trim(root)
        return trimmer(root, shared, required).trim(root)
    }

    private fun trimmer(
        root: RelNode,
        shared: Set<RelNode> = emptySet(),
        required: IdentityHashMap<RelNode, ImmutableBitSet> = IdentityHashMap(),
        collecting: Boolean = false
    ): RelFieldTrimmer = object : RelFieldTrimmer(null, RelBuilder.proto().create(root.cluster, null)) {
        private val trimmed = IdentityHashMap<RelNode, TrimResult>()

        override fun trimChild(parent: RelNode, child: RelNode, fields: ImmutableBitSet, extraFields: Set<RelDataTypeField>): TrimResult =
            trimShared(child, fields, extraFields) { used -> super.trimChild(parent, child, used, extraFields) }

        override fun trimChild(parent: RelNode, child: RelNode, offset: Int, end: Int, fields: ImmutableBitSet,
                               extraFields: Set<RelDataTypeField>): TrimResult =
            // Calcite Join 已把 fields 转为子输入坐标；offset/end 仅用于查找父关联字段。
            trimShared(child, fields, extraFields) { used -> super.trimChild(parent, child, offset, end, used, extraFields) }

        private fun trimShared(child: RelNode, fields: ImmutableBitSet, extraFields: Set<RelDataTypeField>,
                               trim: (ImmutableBitSet) -> TrimResult): TrimResult {
            if (child !in shared) return trim(fields)
            require(extraFields.isEmpty()) { "shared semantic relations require explicitly declared columns" }
            if (!collecting) {
                val allFields = required.getValue(child)
                check(allFields.contains(fields)) { "shared relation column demand changed during trimming" }
                return trimmed.getOrPut(child) { trim(allFields) }
            }
            val result = trim(fields)
            val carried = ImmutableBitSet.builder()
            for (index in 0 until result.right.sourceCount) {
                if (result.right.getTargetOpt(index) >= 0) carried.set(index)
            }
            required[child] = (required[child] ?: ImmutableBitSet.of()).union(fields).union(carried.build())
            return result
        }

        override fun trimFields(rel: RelNode, fieldsUsed: ImmutableBitSet, extraFields: Set<RelDataTypeField>): TrimResult {
            if (rel !is CollectionExpansionRel) return super.trimFields(rel, fieldsUsed, extraFields)
            require(extraFields.isEmpty()) { "typed collection expansion has no dynamic extra fields" }
            val width = rel.input.rowType.fieldCount
            val required = fieldsUsed.intersect(ImmutableBitSet.range(width)).union(ImmutableBitSet.of(rel.column))
            val input = trimChild(rel, rel.input, required, emptySet())
            if (input.left === rel.input) return result(rel, Mappings.createIdentity(rel.rowType.fieldCount))
            val expanded = CollectionExpansionRel(input.left, input.right.getTarget(rel.column), rel.elementAlias, rel.ordinalAlias, rel.outer)
            val mapping = Mappings.create(MappingType.INVERSE_SURJECTION, rel.rowType.fieldCount, expanded.rowType.fieldCount)
            for (index in 0 until width) input.right.getTargetOpt(index).takeIf { it >= 0 }?.let { mapping.set(index, it) }
            mapping.set(width, input.left.rowType.fieldCount)
            mapping.set(width + 1, input.left.rowType.fieldCount + 1)
            return result(expanded, mapping, rel)
        }
    }
}
