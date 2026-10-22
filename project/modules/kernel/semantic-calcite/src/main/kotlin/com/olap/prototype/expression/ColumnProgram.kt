package com.olap.prototype.expression

import org.apache.calcite.plan.RelOptUtil
import org.apache.calcite.rel.logical.LogicalCalc
import org.apache.calcite.rex.RexNode
import org.apache.calcite.rex.RexOver
import org.apache.calcite.rex.RexProgramBuilder
import org.apache.calcite.tools.RelBuilder

/** 连续行保持计算；只有窗口读取前窗口时才建立关系输入边界。 */
internal class ColumnProgram(private val builder: RelBuilder) {
    private var program: LogicalCalc? = null

    fun boundary() { program = null }

    private fun begin(): LogicalCalc = program ?: run {
        val input = builder.build()
        val identity = RexProgramBuilder(input.rowType, builder.rexBuilder).apply { addIdentity() }
        LogicalCalc.create(input, identity.program).also { program = it; builder.push(it) }
    }

    fun append(alias: String, expressionFactory: (RelBuilder) -> RexNode) {
        var current = begin()
        val expression = expressionFactory(builder)
        if (RexOver.containsOver(expression) && RelOptUtil.InputFinder.bits(expression).asList().any {
                RexOver.containsOver(current.program.expandLocalRef(current.program.projectList[it]))
            }) {
            // 前窗口的结果必须成为关系输入，不能留作同一Window中的输出引用。
            boundary()
            current = begin()
        }
        val next = RexProgramBuilder(current.rowType, builder.rexBuilder).apply {
            addIdentity(); addProject(expression, alias)
        }
        val merged = RexProgramBuilder.mergePrograms(next.program, current.program, builder.rexBuilder)
        builder.build()
        program = LogicalCalc.create(current.input, merged).also(builder::push)
    }
}
