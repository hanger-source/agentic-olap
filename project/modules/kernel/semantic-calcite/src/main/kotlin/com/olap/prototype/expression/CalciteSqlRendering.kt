package com.olap.prototype.expression

import com.olap.prototype.connector.CompilationSupport
import com.olap.prototype.contract.JdbcParameter
import org.apache.calcite.plan.hep.HepPlanner
import org.apache.calcite.plan.hep.HepProgramBuilder
import org.apache.calcite.rel.RelNode
import org.apache.calcite.rel.RelVisitor
import org.apache.calcite.rel.core.Join
import org.apache.calcite.rel.core.JoinRelType
import org.apache.calcite.rel.rules.AggregateProjectConstantToDummyJoinRule
import org.apache.calcite.rel.rules.CoreRules
import org.apache.calcite.rex.RexShuttle
import org.apache.calcite.rex.RexSubQuery
import org.apache.calcite.rex.RexNode
import org.apache.calcite.sql.*
import org.apache.calcite.sql.util.SqlShuttle
import org.apache.calcite.sql.util.SqlString
import java.util.IdentityHashMap

/** SQL 方言准备、AST 与参数顺序的共同出口，不拥有业务绑定或语义规划。 */
object CalciteSqlRendering {
    data class Rendering(val sql: SqlString, val relation: RelNode, val numericBindings: Int, val reusedNumericChecks: Int,
        val numericChecks: Int, val caseExpressions: Int)

    fun render(plan: RelNode, support: CompilationSupport, parameters: List<JdbcParameter> = emptyList()): SqlString = compile(plan, support, parameters).sql

    fun compile(plan: RelNode, support: CompilationSupport, parameters: List<JdbcParameter> = emptyList()): Rendering {
        val dialect = support.dialect
        object : RelVisitor() {
            override fun visit(node: RelNode, ordinal: Int, parent: RelNode?) {
                check(node !is Join || node.joinType != JoinRelType.FULL || dialect.supportsJoinType(node.joinType)) {
                    "FULL join has no verified SQL mapping for dialect: ${dialect.databaseProduct}"
                }
                super.visit(node, ordinal, parent)
            }
        }.go(plan)
        // 每个查询根只做一次方言准备；之后的标量求值边界与公共关系引用共享同一节点身份。
        val nativePlan = prepare(plan, dialect)
        val evaluation = SqlEvaluationProjection()
        val relation = evaluation.lower(nativePlan)
        var statement = ParameterizedRelToSqlConverter(support, evaluation.boundaries).statement(relation)
        if (support.parameterSubmission == CompilationSupport.ParameterSubmission.TYPED_LITERALS) {
            statement = requireNotNull(statement.accept(object : SqlShuttle() {
                override fun visit(parameter: SqlDynamicParam): SqlNode = support.parameterLiteral(parameters[parameter.index])
            })) { "SQL parameter substitution must preserve the statement" }
        }
        statement.accept(object : SqlShuttle() {
            override fun visit(call: SqlCall): SqlNode {
                check(call.kind != SqlKind.TIMESTAMP_DIFF || support.supports(CompilationSupport.Capability.ELAPSED_TIMESTAMPDIFF)) {
                    "elapsed TIMESTAMPDIFF has no verified Calcite dialect mapping: ${dialect.databaseProduct}"
                }
                return requireNotNull(super.visit(call)) { "SQL validation must preserve each call" }
            }
        })
        var numericChecks = 0
        statement = requireNotNull(statement.accept(object : SqlShuttle() {
            override fun visit(call: SqlCall): SqlNode {
                val converted = super.visit(call) as SqlCall
                // 类型化调用在完整实参上只映射一次；统计和渲染共同读取插件已展开的实际 AST。
                return when (val operator = converted.operator) {
                    is SemanticNumericRex.NumericCheck -> {
                        numericChecks++
                        operator.expand(converted, dialect)
                    }
                    is SemanticNumericRex.ScaleCheck -> {
                        numericChecks++
                        operator.expand(converted, dialect)
                    }
                    is NativeScalarOperator -> operator.expand(converted)
                    else -> converted
                }
            }
        })) { "native SQL expansion must preserve the statement" }
        var cases = 0
        statement.accept(object : SqlShuttle() {
            override fun visit(call: SqlCall): SqlNode {
                if (call.kind == SqlKind.CASE) cases++
                return requireNotNull(super.visit(call))
            }
        })
        return Rendering(statement.toSqlString(dialect), relation, evaluation.numericBindings, evaluation.reusedChecks, numericChecks, cases)
    }

    private fun prepare(plan: RelNode, dialect: SqlDialect): RelNode {
        val rules = buildList {
            if (!dialect.supportsGroupByLiteral()) add(AggregateProjectConstantToDummyJoinRule.Config.DEFAULT.toRule())
            if (!dialect.supportsJoinType(JoinRelType.RIGHT)) add(CoreRules.JOIN_COMMUTE_RIGHT_TO_LEFT)
            if (!dialect.supportsOrderByLiteral()) add(CoreRules.SORT_REMOVE_CONSTANT_KEYS)
        }
        val roots = IdentityHashMap<RelNode, RelNode>()
        fun root(plan: RelNode): RelNode = roots[plan] ?: run {
            val nodes = IdentityHashMap<RelNode, RelNode>()
            fun visit(node: RelNode): RelNode = nodes[node] ?: run {
                val inputs = node.inputs.map(::visit)
                val current = if (inputs.indices.any { inputs[it] !== node.getInput(it) }) node.copy(node.traitSet, inputs) else node
                val rewritten = current.accept(object : RexShuttle() {
                    override fun visitSubQuery(query: RexSubQuery): RexNode {
                        val values = super.visitSubQuery(query) as RexSubQuery
                        val relation = root(values.rel)
                        return if (relation === values.rel) values else values.clone(relation)
                    }
                })
                rewritten.also { nodes[node] = it }
            }
            val rewritten = visit(plan)
            val prepared = if (rules.isEmpty()) rewritten else {
                val planner = HepPlanner(HepProgramBuilder().addRuleCollection(rules).build())
                planner.setRoot(rewritten)
                planner.findBestExp()
            }
            prepared.also { roots[plan] = it }
        }
        return root(plan)
    }

    fun orderedParameters(sql: SqlString, boundParameters: List<JdbcParameter>): List<JdbcParameter> =
        sql.dynamicParameters?.map(boundParameters::get) ?: emptyList()
}
