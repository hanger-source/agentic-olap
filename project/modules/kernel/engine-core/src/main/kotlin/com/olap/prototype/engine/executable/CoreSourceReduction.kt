package com.olap.prototype.engine.executable

import com.olap.prototype.connector.CompilationSupport
import com.olap.prototype.connector.NativeAggregateKeys
import com.olap.prototype.contract.JdbcParameter
import com.olap.prototype.expression.*
import com.olap.prototype.metric.MetricValueContract
import com.olap.prototype.query.*
import com.olap.prototype.query.ResultExpression.ResultOperator
import com.olap.prototype.warehouse.WarehouseExecutionControl
import org.apache.calcite.rel.RelNode
import org.apache.calcite.rel.core.AggregateCall
import org.apache.calcite.rel.logical.LogicalAggregate
import org.apache.calcite.rel.type.RelDataType
import org.apache.calcite.rex.RexNode
import org.apache.calcite.rex.RexCall
import org.apache.calcite.rex.RexShuttle
import org.apache.calcite.sql.`fun`.SqlStdOperatorTable as Ops
import org.apache.calcite.sql.type.SqlTypeName
import org.apache.calcite.sql.type.SqlTypeUtil
import org.apache.calcite.tools.RelBuilder
import org.apache.calcite.util.ImmutableBitSet
import java.math.BigDecimal
import java.math.BigInteger

/** 来源运输精确充分统计，正式REGROUP的除法与量化仍由共同核心执行。 */
internal class CoreSourceReduction private constructor(
    val stage: QueryStageAnalysis.Stage,
    val fields: List<ExecutableSourceLayout.Field>,
    private val grouping: PostOperationContract.ResultGrouping,
    private val total: String,
    private val states: List<WorkState>,
    private val computation: CoreRegroup,
) {
    val outputTypes get() = computation.outputTypes
    private data class WorkState(val count: String, val sum: String?)

    fun complete(rows: List<Map<String, CoreValue>>, control: WarehouseExecutionControl): List<Map<String, CoreValue>> {
        check(grouping.groupBy.isNotEmpty() || rows.size == 1) { "a global source work reduction must return its one actual group" }
        val identities = hashSetOf<List<CoreValueKeys.Key>>()
        return rows.map { row ->
            control.checkCancellation()
            val keys = grouping.groupBy.associateTo(LinkedHashMap()) { it to row.getValue(it) }
            check(identities.add(keys.map { (name, value) -> CoreValueKeys.key(value, outputTypes.getValue(name)) })) {
                "source work reduction returned a duplicate exact group identity"
            }
            val population = count(row.getValue(total))
            val partials = states.map { state ->
                val known = count(row.getValue(state.count))
                check(known <= population) { "source work nonnull population exceeds its group population" }
                val sum = state.sum?.let { name -> when (val value = row.getValue(name)) {
                    CoreValue.Missing -> null
                    is CoreValue.Numeric -> value.value
                    else -> error("source work sum requires an exact numeric value")
                } }
                CoreRegroup.Partial(known, sum, known != population)
            }
            computation.complete(keys, partials, control)
        }
    }

    private fun count(value: CoreValue): BigInteger {
        val count = when (value) {
            CoreValue.Missing -> BigInteger.ZERO // 无分组空袋的SUM为NULL。
            is CoreValue.Numeric -> value.value.integer()
            else -> error("source work count requires an exact integer")
        }
        check(count.signum() >= 0 && count <= BigInteger.valueOf(Long.MAX_VALUE)) { "source work population exceeds INTEGER64" }
        return count
    }

    data class Lowered(val relation: RelNode, val reduction: CoreSourceReduction)

    sealed interface Selection {
        data class Work(val lowered: Lowered) : Selection
        data class Bag(val attemptedReductionStage: Int?, val reason: String) : Selection
    }

    private sealed interface WorkingType {
        data class Available(val type: RelDataType) : WorkingType
        data class Unavailable(val reason: String) : WorkingType
    }

    companion object {
        /** 物理候选只接受可证明保持的前置操作与可合并统计，不以失败重试另一种计算。 */
        fun compile(builder: RelBuilder, source: CoreFinalizationGraph.SourceInput, input: RelNode,
            parameters: MutableList<JdbcParameter>, native: NativeExpressionScope): Selection {
            val remaining = source.analysis.stages.drop(source.cut.stageCount)
            val position = remaining.indexOfFirst { it.operation is PostOperationContract.Regroup }
            if (position < 0) return Selection.Bag(null, "NO_CORE_REGROUP")
            val stage = remaining[position]
            fun bag(reason: String) = Selection.Bag(stage.index, reason)
            evaluationInstability(input)?.let { return bag("INPUT_EVALUATION_STABILITY_UNPROVEN: $it") }
            val preceding = remaining.take(position)
            if (preceding.any { stage -> when (val op = stage.operation) {
                is PostOperationContract.Project -> false
                is PostOperationContract.PostFilter -> !nullPredicate(op.expression)
                else -> true
            } }) return bag("PRECEDING_STAGE_NOT_SUPPORTED_BY_SOURCE_REDUCTION")
            if (preceding.any { stage -> PostOperationTraversal.expressions(stage.operation).any { expression ->
                ResultExpressionTree.nodes(expression).filterIsInstance<ResultExpression.ResultSymbol>().any { symbol ->
                    input.rowType.getField(symbol.symbol, true, false)?.type?.sqlTypeName in setOf(SqlTypeName.ROW, SqlTypeName.ARRAY)
                }
            } }) return bag("PRECEDING_CONTAINER_FILTER_NOT_SUPPORTED_BY_SOURCE_REDUCTION")
            val grouping = (stage.operation as PostOperationContract.Regroup).grouping
            if (grouping.groupingLevels != null || grouping.aggregates.isEmpty() || grouping.aggregates.any {
                it.aggregation !is ResultAggregation.Sum && it.aggregation !is ResultAggregation.Average &&
                    (it.aggregation as? ResultAggregation.Count)?.distinct != false
            }) return bag("AGGREGATION_SHAPE_NOT_SUPPORTED_BY_SOURCE_REDUCTION")
            builder.push(input)
            if (preceding.isNotEmpty()) {
                val last = preceding.last()
                CompletedRelationCompiler.compile(builder, QueryStageAnalysis.Analysis(source.cut.scope,
                    remaining.first().inputs, preceding, last.outputs, last.key, emptyList()),
                    linkedMapOf(), parameters, emptyMap(), mutableListOf(), native)
            }
            val relation = builder.build()
            val inputTypes = relation.rowType.fieldList.associate { it.name to CoreFinalizationExpressions.sourceType(it.type) }
            val at = listOf<Any>("input") + source.cut.scope.path + listOf("postOps", stage.index)
            val computation = CoreRegroup.compile(grouping, stage, inputTypes, at)
            val workTypes = grouping.aggregates.map { aggregate ->
                if (aggregate.aggregation is ResultAggregation.Count) null
                else when (val candidate = workingType(builder, requireNotNull(relation.rowType.getField(aggregate.input, true, false)).type,
                    stage.inputs.first { it.name == aggregate.input }.quantity?.declaration, native.support)) {
                    is WorkingType.Available -> candidate.type
                    is WorkingType.Unavailable -> return bag("${candidate.reason}: ${aggregate.input}")
                }
            }
            val occupied = relation.rowType.fieldNames.toMutableSet()
            fun alias() = SemanticNativeTypes.internalAlias(occupied, "__core_work_")
            builder.push(relation)
            val expressions = mutableListOf<RexNode>()
            val names = mutableListOf<String>()
            grouping.groupBy.forEach { name -> expressions += builder.field(name); names += name }
            var groupCount = grouping.groupBy.size
            for (name in grouping.groupBy) {
                val value = builder.field(name)
                if (value.type.sqlTypeName in setOf(SqlTypeName.CHAR, SqlTypeName.VARCHAR)) {
                    val key = native.support.textIdentityKey(builder, value)
                    if (key == null) { builder.build(); return bag("EXACT_TEXT_GROUP_KEY_UNAVAILABLE: $name") }
                    check(key.type.sqlTypeName in setOf(SqlTypeName.BINARY, SqlTypeName.VARBINARY) && key.type.isNullable == value.type.isNullable) {
                        "exact text grouping key must retain NULL and use a binary representation"
                    }
                    expressions += key
                    names += alias()
                    groupCount++
                } else if (!SqlTypeUtil.isExactNumeric(value.type) && value.type.sqlTypeName !in setOf(SqlTypeName.NULL, SqlTypeName.BOOLEAN)) {
                    builder.build(); return bag("GROUP_KEY_TYPE_NOT_SUPPORTED_BY_SOURCE_REDUCTION: $name")
                }
            }
            val counterType = builder.typeFactory.createSqlType(SqlTypeName.DECIMAL, 20, 0)
            fun append(expression: RexNode): Int { expressions += expression; names += alias(); return expressions.lastIndex }
            val totalInput = append(builder.rexBuilder.makeExactLiteral(BigDecimal.ONE, counterType))
            val arguments = grouping.aggregates.mapIndexed { index, aggregate ->
                val value = builder.field(aggregate.input)
                val known = builder.call(Ops.CASE, builder.isNull(value), builder.literal(0), builder.literal(1))
                val knownInput = append(builder.rexBuilder.makeCast(counterType, known))
                val sumInput = workTypes[index]?.let { append(builder.rexBuilder.makeCast(it, value)) }
                knownInput to sumInput
            }
            val projected = builder.project(expressions, names, true).build()
            val keys = ImmutableBitSet.range(groupCount)
            val function = native.support.nativeAggregate(org.apache.calcite.sql.SqlKind.SUM)
            val calls = mutableListOf<AggregateCall>()
            fun sum(index: Int): String {
                val name = alias()
                calls += AggregateCall.create(function, false, listOf(index), keys.cardinality(), projected, null, name)
                return name
            }
            val total = sum(totalInput)
            val states = arguments.map { (known, value) -> WorkState(sum(known), value?.let(::sum)) }
            val aggregate = LogicalAggregate.create(projected, emptyList(), keys, listOf(keys), calls)
            // 此处询问的是实际键、实际输入、实际调用；DECIMAL标签本身不是精确保证。
            if (!NativeAggregateKeys.preservesExactIdentity(aggregate, native.support)) return bag("EXACT_GROUP_IDENTITY_UNPROVEN")
            calls.forEach { call ->
                if (native.support.aggregateBehavior(aggregate, call) != NativeAggregateBehavior.ExactOrFail) {
                    val arguments = call.argList.joinToString { projected.rowType.fieldList[it].type.fullTypeString }
                    return bag("ACTUAL_NATIVE_WORK_EXACT_OR_FAIL_UNAVAILABLE: ${call.aggregation.kind}($arguments) -> ${call.type.fullTypeString}")
                }
            }
            calls.forEach { NativeAggregateTypes.requireExactWork(aggregate, it) }
            calls.forEach { call ->
                val inputType = projected.rowType.fieldList[call.argList.single()].type
                if (!contains(call.type, inputType)) return bag("ACTUAL_NATIVE_WORK_RESULT_CAPACITY_INSUFFICIENT: ${call.name}")
            }
            builder.push(aggregate)
            val retained = grouping.groupBy + calls.map { it.name }
            val work = builder.project(retained.map(builder::field), retained, true).build()
            val fields = work.rowType.fieldList.map { ExecutableSourceLayout.Field(it.name, it.type) }
            return Selection.Work(Lowered(work, CoreSourceReduction(stage, fields, grouping, total, states, computation)))
        }

        private fun nullPredicate(expression: ResultExpression): Boolean = when (expression) {
            is ResultExpression.ResultLiteral -> expression.value == null || expression.value is Boolean
            is ResultExpression.ResultOperation -> when (expression.operator) {
                ResultOperator.NOT, ResultOperator.AND, ResultOperator.OR -> expression.operands.all(::nullPredicate)
                ResultOperator.IS_NULL -> expression.operands.single() is ResultExpression.ResultSymbol
                else -> false
            }
            else -> false
        }

        private fun evaluationInstability(input: RelNode): String? {
            var unstable: String? = null
            val visitor = object : RexShuttle() {
                override fun visitCall(call: RexCall): RexNode {
                    if (!SemanticCalciteOperators.statementStable(call) && unstable == null)
                        unstable = (call.operator as? NativeScalarOperator)?.declaration?.id ?: call.operator.name
                    return super.visitCall(call)
                }
            }
            val visited = hashSetOf<RelNode>()
            fun visit(node: RelNode) { if (visited.add(node)) { node.accept(visitor); node.inputs.forEach(::visit) } }
            visit(input)
            return unstable
        }

        /** 完整原生域被输入数量合同包含后，才允许省去逐原值运输核验。 */
        private fun workingType(builder: RelBuilder, input: RelDataType, declaration: MetricValueContract?, support: CompilationSupport): WorkingType {
            if (declaration == null) return WorkingType.Unavailable("INPUT_QUANTITY_UNAVAILABLE")
            if (declaration.arithmetic !is MetricValueContract.Exact || !SqlTypeUtil.isExactNumeric(input))
                return WorkingType.Unavailable("EXACT_INPUT_ARITHMETIC_UNPROVEN")
            if (declaration.unit !is MetricValueContract.Fixed) return WorkingType.Unavailable("VARIABLE_UNIT_WORK_REDUCTION_UNAVAILABLE")
            val (precision, scale) = capacity(input) ?: return WorkingType.Unavailable("COMMON_FIXED_INPUT_CAPACITY_UNAVAILABLE")
            val domainCovered = when (val result = declaration.result) {
                is MetricValueContract.Integer -> input.sqlTypeName != SqlTypeName.DECIMAL && when (input.sqlTypeName) {
                    SqlTypeName.TINYINT -> 8
                    SqlTypeName.SMALLINT -> 16
                    SqlTypeName.INTEGER -> 32
                    SqlTypeName.BIGINT -> 64
                    else -> Int.MAX_VALUE
                } <= result.bits
                is MetricValueContract.Decimal -> result.scale >= scale && result.precision - result.scale >= precision - scale
                is MetricValueContract.BinaryFloat -> false
                MetricValueContract.FiniteDecimal -> true
            }
            // 每组合法INTEGER64次数的完整输入域：p位定点累计最多需要p+19位，不用样本估界。
            if (!domainCovered) return WorkingType.Unavailable("INPUT_QUANTITY_DOES_NOT_COVER_NATIVE_DOMAIN")
            if (precision + 19 > support.maximumDeclaredDecimalPrecision) return WorkingType.Unavailable("WORK_CAPACITY_EXCEEDS_DECLARED_NATIVE_MAXIMUM")
            val type = builder.typeFactory.createSqlType(SqlTypeName.DECIMAL, precision + 19, scale)
            if (type.precision != precision + 19 || type.scale != scale) return WorkingType.Unavailable("WORK_TYPE_CAPACITY_NOT_PRESERVED")
            return WorkingType.Available(builder.typeFactory.createTypeWithNullability(type, input.isNullable))
        }

        private fun capacity(type: RelDataType): Pair<Int, Int>? = when (type.sqlTypeName) {
            SqlTypeName.TINYINT -> 3 to 0
            SqlTypeName.SMALLINT -> 5 to 0
            SqlTypeName.INTEGER -> 10 to 0
            SqlTypeName.BIGINT -> 19 to 0
            SqlTypeName.DECIMAL -> if (type.precision > 0 && type.scale >= 0) type.precision to type.scale else null
            else -> null
        }
        private fun contains(result: RelDataType, input: RelDataType): Boolean {
            val (rp, rs) = capacity(result) ?: return false
            val (ip, ins) = capacity(input) ?: return false
            return rs >= ins && rp - rs >= ip - ins
        }
    }
}
