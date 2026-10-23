package com.olap.prototype.expression

import com.olap.prototype.calculation.Calculation
import com.olap.prototype.connector.CompilationSupport
import com.olap.prototype.connector.NativeCompilationCapabilityException
import com.olap.prototype.contract.JdbcParameter
import com.olap.prototype.query.PostOperationContract
import com.olap.prototype.query.PostOperationTraversal
import com.olap.prototype.query.QueryStageAnalysis
import com.olap.prototype.query.ResultAggregation
import com.olap.prototype.query.ResultExpression
import com.olap.prototype.query.ResultExpressionTree
import com.olap.prototype.query.ScopedInputContract
import org.apache.calcite.rel.RelNode
import org.apache.calcite.rel.core.JoinRelType
import org.apache.calcite.rel.logical.LogicalJoin
import org.apache.calcite.rex.RexNode
import org.apache.calcite.sql.`fun`.SqlStdOperatorTable as Ops
import org.apache.calcite.sql.type.SqlTypeName
import org.apache.calcite.tools.RelBuilder
import org.apache.calcite.util.ImmutableBitSet
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.math.BigDecimal

/** 已分析完成关系的原生发射，类型检验与执行共用同一Rel/Rex算法。 */
object CompletedRelationCompiler {
    private val log = LoggerFactory.getLogger(CompletedRelationCompiler::class.java)
    fun compile(builder: RelBuilder, analysis: QueryStageAnalysis.Analysis, display: MutableMap<String, String>,
        parameters: MutableList<JdbcParameter>, relations: Map<String, RelNode>, checks: MutableList<RelationalCheck>) {
        Pipeline(builder, analysis, display, parameters, relations, checks).compile()
    }

    /** 列程序只跨越人口和字段身份均保持的阶段；观察边界拥有其独立核验。 */
    private class Pipeline(private val b: RelBuilder, private val analysis: QueryStageAnalysis.Analysis,
        private val display: MutableMap<String, String>, private val parameters: MutableList<JdbcParameter>,
        private val relations: Map<String, RelNode>, private val checks: MutableList<RelationalCheck>) {
        private val system = b.typeFactory.typeSystem as? SemanticCalciteTypeSystem ?: error("completed relation compilation requires its bound execution type system")
        private val columns = ColumnProgram(b)
        private val observations = CompletedObservationCompiler(b, parameters, relations, checks)

        fun compile() {
            requireCapabilities(system, analysis)
            analysis.stages.forEach(::emit)
        }

        private fun emit(stage: QueryStageAnalysis.Stage) {
            check(stage.inputs.map { it.name } == b.peek().rowType.fieldNames) { "native stage input differs from analyzed symbols or order" }
            val op = stage.operation
            if (op !is PostOperationContract.JoinInput) {
                requireWeightedObjects(op)
                requireAllocationPopulation(op)
            }
            when (op) {
                is PostOperationContract.Derive -> appendColumn(op) { builder ->
                    CompletedExpressionCompiler.compile(builder, op.expression, parameters)
                }
                is PostOperationContract.PartitionAggregate -> appendColumn(op) { builder ->
                    SemanticNumericRex.aggregateOutput(builder, CompletedWindows.partitionAggregate(builder, op), op.aggregation)
                }
                is PostOperationContract.WindowAggregate -> appendColumn(op) { builder ->
                    SemanticNumericRex.aggregateOutput(builder, CompletedWindows.windowAggregate(builder, op), op.aggregation)
                }
                is PostOperationContract.OffsetValue -> appendColumn(op) { builder ->
                    builder.aggregateCall(if (op.function == "LAG") Ops.LAG else Ops.LEAD,
                        listOf(builder.field(op.input), builder.literal(op.distance)))
                        .over().partitionBy(op.partitionBy.map(builder::field))
                        .orderBy(CompletedWindows.orderKeys(builder, op.orderKeys)).toRex()
                }
                is PostOperationContract.OrderedRank -> appendColumn(op) { builder ->
                    builder.aggregateCall(if (op.operationType == PostOperationContract.ROW_NUMBER) Ops.ROW_NUMBER else Ops.DENSE_RANK, emptyList())
                        .over().partitionBy(op.partitionBy.map(builder::field))
                        .orderBy(CompletedWindows.orderKeys(builder, op.orderKeys)).toRex()
                }
                is PostOperationContract.Rank -> appendColumn(op) { builder ->
                    builder.aggregateCall(Ops.RANK, emptyList()).over().partitionBy(op.partitionBy.map(builder::field))
                        .orderBy(listOf(if (op.direction == "DESC") builder.desc(builder.field(op.orderBy)) else builder.field(op.orderBy))).toRex()
                }
                is PostOperationContract.Cumulative -> appendColumn(op) { builder ->
                    val expression = builder.sum(SemanticNumericRex.arithmetic(builder, builder.field(op.input), op.aggregation.valueContract))
                        .over().partitionBy(op.partitionBy.map(builder::field))
                        .orderBy(listOf(if (op.direction == "DESC") builder.desc(builder.field(op.orderBy)) else builder.field(op.orderBy)))
                        .rowsBetween(builder.unboundedPreceding(), builder.currentRow()).toRex()
                    SemanticNumericRex.output(builder, expression, op.aggregation.valueContract)
                }
                is PostOperationContract.JoinInput -> atBoundary { join(stage, op) }
                is PostOperationContract.SelectVersions -> atBoundary { observations.selectVersions(op) }
                is PostOperationContract.ObserveWindow -> atBoundary {
                    observations.observeWindow(op)
                    display[op.outputAlias] = op.outputDisplayName
                }
                is PostOperationContract.ExpandCollection -> atBoundary { expand(op) }
                is PostOperationContract.PostFilter -> atBoundary { filter(op) }
                is PostOperationContract.Sort -> atBoundary { b.sort(CompletedWindows.orderKeys(b, op.orderKeys)) }
                is PostOperationContract.TopN -> atBoundary { b.sortLimit(0, op.limit, CompletedWindows.orderKeys(b, op.orderKeys)) }
                is PostOperationContract.Project -> atBoundary {
                    b.project(op.symbols.map(b::field), op.symbols, true)
                    display.keys.retainAll(op.symbols)
                }
                is PostOperationContract.Regroup -> atBoundary {
                    regroup(b, op.grouping)
                    display.keys.retainAll(op.grouping.groupBy)
                    op.grouping.groupingLevels?.let { display[it.outputAlias] = it.outputAlias }
                    op.grouping.aggregates.forEach { display[it.outputAlias] = it.outputDisplayName }
                }
                is PostOperationContract.AncestorClosure -> atBoundary {
                    AncestorRelationCompiler.compile(b, op, checks)
                    display.clear()
                    (op.descendantAliases + op.ancestorAliases).forEach { display[it] = it }
                }
            }
        }

        private fun appendColumn(operation: PostOperationContract.OutputOperation, expression: (RelBuilder) -> RexNode) {
            columns.append(operation.outputAlias, expression)
            display[operation.outputAlias] = operation.outputDisplayName
        }

        private inline fun atBoundary(compile: () -> Unit) {
            columns.boundary()
            compile()
        }

        private fun join(stage: QueryStageAnalysis.Stage, join: PostOperationContract.JoinInput) {
            require(!hasUniformAllocation(join.condition)) { "uniform allocation requires the completed associated population and cannot be a join condition" }
            val left = b.build()
            var right = requireNotNull(relations[join.inputId]) { "completed relation input is required" }
            b.push(right)
            val names = right.rowType.fieldNames.map { ScopedInputContract.metricSymbol(join.occurrenceId, it) }
            right = b.project(b.fields(), names, true).build()
            if (join.joinType !in setOf("SEMI", "ANTI")) names.forEach { display[it] = it }
            b.push(left).push(right).join(JoinRelType.INNER, b.literal(true))
            requireWeightedObjects(join)
            var condition = CompletedExpressionCompiler.compile(b, join.condition, parameters)
            require(condition.type.sqlTypeName == SqlTypeName.BOOLEAN) { "JOIN_INPUT condition requires a Calcite BOOLEAN expression" }
            val key = stage.key as? QueryStageAnalysis.UniqueKey
            if (join.joinType in setOf("INNER", "LEFT") && key != null) {
                val equalities = TextIdentityJoins.retainedKeyEqualities(b, join.condition, left, right)
                if (equalities.isNotEmpty()) condition = b.and(listOf(condition) + equalities)
            }
            b.build()
            val joined = LogicalJoin.create(left, right, emptyList(), condition, emptySet(), JoinRelType.valueOf(join.joinType))
            if (join.joinType in setOf("INNER", "LEFT") && key != null) {
                val retained = stage.outputs.indices.filter { stage.outputs[it].id in key.columns }
                check(retained.size == key.columns.size) { "retained join key has unavailable symbols" }
                require(joined.cluster.metadataQuery.areColumnsUnique(joined, ImmutableBitSet.of(retained), false) == true) {
                    "JOIN_INPUT ${join.occurrenceId} at scope ${analysis.scope.path} does not prove retained key ${retained.map { stage.outputs[it].name }} in the native relation"
                }
            }
            b.push(joined)
        }

        private fun expand(expand: PostOperationContract.ExpandCollection) {
            if (!system.supports(CompilationSupport.Capability.COLLECTION_EXPANSION))
                throw NativeCompilationCapabilityException(CompilationSupport.Capability.COLLECTION_EXPANSION, expand.operationType)
            val input = b.field(expand.input)
            require(input.type.sqlTypeName == SqlTypeName.ARRAY && input.type.componentType != null) { "EXPAND_COLLECTION requires a determined ARRAY element type" }
            val column = input.index
            val left = b.build()
            b.push(CollectionExpansionRel(left, column, expand.elementAlias, expand.ordinalAlias, expand.mode == "OUTER"))
            display[expand.elementAlias] = expand.elementAlias; display[expand.ordinalAlias] = expand.ordinalAlias
        }

        private fun filter(filter: PostOperationContract.PostFilter) {
            val predicate = CompletedExpressionCompiler.compile(b, filter.expression, parameters)
            require(predicate.type.sqlTypeName == SqlTypeName.BOOLEAN) { "POST_FILTER requires a Calcite BOOLEAN expression" }
            if (!hasUniformAllocation(filter.expression)) { b.filter(predicate); return }
            val names = b.peek().rowType.fieldNames.toList()
            val alias = SemanticNativeTypes.internalAlias(names.toMutableSet(), "__allocation_filter_")
            b.project(b.fields() + predicate, names + alias, true).filter(b.field(alias)).project(names.map(b::field), names, true)
        }

        private fun expressionNodes(operation: PostOperationContract.ValidatedOperation): Sequence<ResultExpression> =
            PostOperationTraversal.expressions(operation).asSequence().flatMap { ResultExpressionTree.nodes(it).asSequence() }

        private fun requireWeightedObjects(operation: PostOperationContract.ValidatedOperation) {
            val identities = expressionNodes(operation).filterIsInstance<ResultExpression.ResultWeightedContribution>()
                .mapNotNull { (it.observationUnit as? Calculation.ObjectsBy)?.keys }.distinct().toList()
            identities.forEach { requireKnownNativeIdentity(it, "weighted object", "WEIGHTED_OBSERVATION_OBJECT_IDENTITY_NULL") }
        }
        private fun requireAllocationPopulation(operation: PostOperationContract.ValidatedOperation) {
            val identities = expressionNodes(operation).filterIsInstance<ResultExpression.ResultUniformAllocation>()
                .map { (it.sourceKeys + it.memberKeys).distinct() }.distinct().toList()
            identities.forEach { requireKnownNativeIdentity(it, "uniform allocation population", "ALLOCATION_POPULATION_IDENTITY_NULL") }
        }
        private fun requireKnownNativeIdentity(identity: List<String>, meaning: String, code: String) {
            val input = b.build()
            RelationalIdentity.requireUnique(input, identity, meaning)
            RelationalIdentity.checkKnown(b, input, identity, code, checks, analysis.scope)
            b.push(input)
        }
    }

    private fun hasUniformAllocation(expression: ResultExpression): Boolean = ResultExpressionTree.nodes(expression).any { it is ResultExpression.ResultUniformAllocation }

    private fun requireCapabilities(system: SemanticCalciteTypeSystem, analysis: QueryStageAnalysis.Analysis) {
        val collectionStage = analysis.stages.firstOrNull { stage -> when (val op = stage.operation) {
            is PostOperationContract.AggregateOutput -> op.aggregation is ResultAggregation.Collect
            is PostOperationContract.ObserveWindow -> op.aggregation is ResultAggregation.Collect
            is PostOperationContract.Regroup -> op.grouping.aggregates.any { it.aggregation is ResultAggregation.Collect }
            else -> false
        } }
        if (collectionStage != null && !system.supports(CompilationSupport.Capability.COLLECTION_RESULTS))
            throw NativeCompilationCapabilityException(CompilationSupport.Capability.COLLECTION_RESULTS, collectionStage.operation.operationType)
    }

    private fun regroup(b: RelBuilder, grouping: PostOperationContract.ResultGrouping) {
        val reserved = (b.peek().rowType.fieldNames + grouping.aggregates.map { it.outputAlias }).toCollection(linkedSetOf())
        val calls = mutableListOf<RelBuilder.AggCall>()
        val knownCounts = linkedMapOf<String, String>()
        val mayEmitEmptyGroup = grouping.groupBy.isEmpty() || grouping.groupingLevels?.levels?.any { it.groupBy.isEmpty() } == true
        var rowCount: String? = null
        fun internalAlias() = SemanticNativeTypes.internalAlias(reserved, "__regroup_count_")
        grouping.aggregates.forEach { aggregate ->
            val aggregation = aggregate.aggregation
            val input = SemanticNumericRex.aggregate(b, aggregation, b.field(aggregate.input))
            val function = RelationalAggregates.aggregateFunction(aggregate.aggregation, (b.typeFactory.typeSystem as SemanticCalciteTypeSystem).nativeSupport)
            if (aggregation is ResultAggregation.Collect && log.isDebugEnabled) log.debug(
                "原生集合聚合输入：requestId={}, workerKind={}, inputRelId={}, input={}, inputType={}, function={}, distinct={}, groupBy={}, groupingSets={}",
                MDC.get("requestId"), MDC.get("workerKind"), b.peek().id, aggregate.input, input.type.fullTypeString, function.name, aggregation.distinct,
                grouping.groupBy, grouping.groupingLevels?.levels?.map { it.groupBy })
            calls.add(b.aggregateCall(function, listOf(input)).distinct(RelationalAggregates.distinct(aggregate.aggregation)).`as`(aggregate.outputAlias))
            if (aggregation is ResultAggregation.Collect && mayEmitEmptyGroup && rowCount == null) rowCount = internalAlias()
            if (aggregation is ResultAggregation.Scalar && aggregation.nullPolicy == ResultAggregation.NullPolicy.PROPAGATE) {
                val known = internalAlias(); calls.add(b.count(input).`as`(known)); knownCounts[aggregate.outputAlias] = known
                if (rowCount == null) rowCount = internalAlias()
            }
        }
        rowCount?.let { calls.add(b.count().`as`(it)) }
        val levels = grouping.groupingLevels
        if (levels != null && grouping.groupBy.isNotEmpty()) calls.add(b.aggregateCall(Ops.GROUPING, grouping.groupBy.map(b::field)).`as`(levels.outputAlias))
        b.aggregate(RelationalAggregates.groupKey(b, grouping.groupBy, levels), calls)
        val names = grouping.groupBy.toMutableList()
        val values = grouping.groupBy.map(b::field).toMutableList<RexNode>()
        if (levels != null) {
            names.add(levels.outputAlias)
            values.add(if (grouping.groupBy.isEmpty()) b.rexBuilder.makeBigintLiteral(BigDecimal.ZERO) else b.field(levels.outputAlias))
        }
        grouping.aggregates.forEach { aggregate ->
            var value: RexNode = b.field(aggregate.outputAlias)
            if (aggregate.aggregation is ResultAggregation.Collect) {
                val nativeType = value.type
                if (mayEmitEmptyGroup)
                    value = b.call(Ops.CASE, b.equals(b.field(requireNotNull(rowCount)), b.literal(0)), b.rexBuilder.makeNullLiteral(value.type), value)
                if (log.isDebugEnabled) log.debug(
                    "原生集合聚合输出：requestId={}, workerKind={}, relId={}, output={}, nativeType={}, completedType={}, emptyGroupGuard={}",
                    MDC.get("requestId"), MDC.get("workerKind"), b.peek().id, aggregate.outputAlias, nativeType.fullTypeString, value.type.fullTypeString, mayEmitEmptyGroup)
            }
            knownCounts[aggregate.outputAlias]?.let { known -> value = b.call(Ops.CASE, b.equals(b.field(known), b.field(requireNotNull(rowCount))), value, b.rexBuilder.makeNullLiteral(value.type)) }
            names.add(aggregate.outputAlias); values.add(SemanticNumericRex.aggregateOutput(b, value, aggregate.aggregation))
        }
        b.project(values, names, true)
    }
}
