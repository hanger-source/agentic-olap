package com.olap.prototype.engine.executable

import com.olap.prototype.calculation.Calculation
import com.olap.prototype.contract.JdbcParameter
import com.olap.prototype.engine.catalog.ExecutableRelationPosition.Expression
import com.olap.prototype.engine.catalog.ExecutableRelationPosition.Id
import com.olap.prototype.expression.*
import com.olap.prototype.query.GroupingLevels
import com.olap.prototype.query.ScopedInputContract
import com.olap.prototype.query.SourceJoinContract
import com.olap.prototype.query.ValueAlignmentContract
import org.apache.calcite.rel.RelNode
import org.apache.calcite.rex.RexNode
import org.apache.calcite.sql.`fun`.SqlStdOperatorTable as Ops
import org.apache.calcite.tools.RelBuilder
import java.math.BigDecimal
import java.util.IdentityHashMap

/** 事实聚合、快照、共享资格与人口，不解析位置或替代Calcite优化。 */
internal class FactRelationCompiler(val sourceRows: SourceRelationCompiler) {
    private val log = org.slf4j.LoggerFactory.getLogger(FactRelationCompiler::class.java)
    fun sharedFacts(builder: RelBuilder, scopes: List<ExecutableInputScope>, parameters: MutableList<JdbcParameter>): Map<ExecutableMetricInput, RelNode> {
        val groups = mutableListOf<MutableList<ExecutableMetricInput>>()
        scopes.flatMap { it.metrics }.forEach { input ->
            if (input.groupingLevels != null || input.objectRestrictions.isNotEmpty() || input.metric.factMeasure().snapshotRule != null || !sourceRows.shareableTargets(builder, input, parameters)) return@forEach
            val group = groups.firstOrNull { sharedFactShape(it.first(), input) && compatiblePositions(it, input) }
            if (group == null) groups.add(mutableListOf(input)) else group.add(input)
        }
        val result = IdentityHashMap<ExecutableMetricInput, RelNode>()
        groups.filter { it.size > 1 && it.drop(1).any { item -> !sameFactQualification(it.first(), item) } }.forEach { group ->
            val rows = sharedRows(builder, group, parameters)
            val projection = projectSharedInputs(builder, group, parameters, rows)
            val aggregate = aggregateSharedInputs(builder, projection)
            projectSharedCalls(builder, projection, aggregate, result)
        }
        return result
    }
    private fun sameFactQualification(left: ExecutableMetricInput, right: ExecutableMetricInput): Boolean =
        left.filterExpression == right.filterExpression && left.window() == right.window() && left.clocks() == right.clocks() &&
            left.relationConditions == right.relationConditions && sourceRows.source(left).joins == sourceRows.source(right).joins &&
            left.metric.factMeasure().privatePredicate == right.metric.factMeasure().privatePredicate
    private fun sharedFactShape(left: ExecutableMetricInput, right: ExecutableMetricInput): Boolean =
        left.event == right.event && left.topology == right.topology && left.timeAnchor == right.timeAnchor && left.timeRange == right.timeRange &&
            left.objectRestrictions == right.objectRestrictions && left.analysisDimensions.filterNot { it.filterOnly } == right.analysisDimensions.filterNot { it.filterOnly }
    private fun compatiblePositions(existing: List<ExecutableMetricInput>, candidate: ExecutableMetricInput): Boolean {
        val right = sourceRows.source(candidate)
        return existing.all { input ->
            val left = sourceRows.source(input)
            left.root == right.root && right.positions.all { (key, value) -> left.positions[key]?.let { it == value } ?: true } &&
                right.temporalClockBindings.all { (key, value) -> left.temporalClockBindings[key]?.let { it == value } ?: true } &&
                right.quantifiers.all { (key, value) -> left.quantifiers[key]?.let { it == value } ?: true } &&
                right.joins.all { join -> left.joins.none { it.target == join.target && it != join } }
        }
    }
    private fun sharedRows(b: RelBuilder, group: List<ExecutableMetricInput>, parameters: MutableList<JdbcParameter>): SourceRelationCompiler.Rows {
        val source = sourceRows.source(group.first())
        val all = group.map(sourceRows::source)
        val positions = all.flatMap { it.positions.entries }.associate { it.key to it.value }
        val joins = linkedMapOf<Id, ExecutablePhysicalJoin>()
        all.flatMap { it.joins }.forEach { joins.putIfAbsent(it.target, it) }
        val clocks = all.flatMap { it.temporalClockBindings.entries }.associate { it.key to it.value }
        val quantifiers = all.flatMap { it.quantifiers.entries }.associate { it.key to it.value }
        val merged = source.copy(positions = positions, joins = optionalPaths(source.root, joins.values.toList()), temporalClockBindings = clocks, quantifiers = quantifiers)
        return sourceRows.backboneRows(b, merged, parameters)
    }
    private fun optionalPaths(root: Id, joins: List<ExecutablePhysicalJoin>): List<ExecutablePhysicalJoin> {
        val pending = joins.toMutableList(); val ordered = mutableListOf<ExecutablePhysicalJoin>(); val available = linkedSetOf(root)
        while (pending.isNotEmpty()) {
            val next = pending.firstOrNull { it.source in available && available.containsAll(it.sourceTimes.values) } ?: error("shared source paths contain a cycle")
            ordered.add(next.copy(joinPath = next.joinPath.copy(joinType = "LEFT"), requiredMatch = false))
            available.add(next.target); pending.remove(next)
        }
        return ordered
    }
    private data class SharedFactColumn(val input: ExecutableMetricInput, val population: String, val predicate: String, val value: String, val aggregate: String, val matches: String)
    private data class SharedProjection(val dimensions: List<String>, val columns: List<SharedFactColumn>)
    private fun projectSharedInputs(b: RelBuilder, group: List<ExecutableMetricInput>, parameters: MutableList<JdbcParameter>, rows: SourceRelationCompiler.Rows): SharedProjection {
        val first = group.first()
        val projection = SharedProjection(dimensionAliases(first), group.mapIndexed { index, input -> SharedFactColumn(input,
            "__population_$index", "__predicate_$index", "__value_$index", "__aggregate_$index", "__matches_$index") })
        val values = mutableListOf<RexNode>(); val names = mutableListOf<String>()
        first.analysisDimensions.filterNot { it.filterOnly }.forEach { dimension ->
            val value = rows.compile(dimension.binding.expression, sourceRows.source(first).dimension(dimension.binding))
            FactAtomCompiler.requireUnitCode(value, dimension.binding, group); values.add(value); names.add(dimension.outputAlias)
        }
        projection.columns.forEach { column ->
            val population = sharedPopulation(b, column.input, rows)
            val atom = FactAtomCompiler.input(b, rows, column.input, population)
            values.add(population); names.add(column.population)
            values.add(requireNotNull(atom.predicate)); names.add(column.predicate)
            values.add(atom.value); names.add(column.value)
        }
        rows.project(values, names)
        b.filter(b.or(projection.columns.map { b.field(it.population) }))
        return projection
    }
    private fun sharedPopulation(b: RelBuilder, input: ExecutableMetricInput, rows: SourceRelationCompiler.Rows): RexNode {
        val conditions = rows.predicates(input)
        sourceRows.source(input).joins.filter { it.requiredMatch || SourceJoinContract.type(it.joinPath.joinType).requiresBackboneMatch() }.forEach { join ->
            join.joinPath.keys.forEach { key -> conditions.add(b.isNotNull(rows.compile(SourceRelationCompiler.ref(key.targetLogicUnitId, key.targetLogicUnitVersion), Expression.local(join.target)))) }
        }
        return b.and(conditions)
    }
    private fun aggregateSharedInputs(b: RelBuilder, projection: SharedProjection): RelNode {
        val aggregates = mutableListOf<RelBuilder.AggCall>()
        projection.columns.forEach { column ->
            aggregates.add(FactAtomCompiler.aggregate(b, column.input.metric.factMeasure(), b.field(column.value), b.field(column.predicate), column.aggregate))
            if (projection.dimensions.isNotEmpty()) aggregates.add(b.aggregateCall(Ops.COUNT, false, b.field(column.population), column.matches, b.literal(1)))
        }
        val aggregate = b.aggregate(b.groupKey(projection.dimensions.map(b::field)), aggregates).build()
        if (log.isDebugEnabled) log.debug("共享聚合人口依据：predicates={}, rel={}", aggregate.cluster.metadataQuery.getPulledUpPredicates(aggregate), aggregate)
        return aggregate
    }
    private fun projectSharedCalls(b: RelBuilder, layout: SharedProjection, aggregate: RelNode, result: MutableMap<ExecutableMetricInput, RelNode>) {
        layout.columns.forEach { column ->
            b.push(aggregate)
            if (layout.dimensions.isNotEmpty()) b.filter(b.call(Ops.GREATER_THAN, b.field(column.matches), b.literal(0)))
            log.debug("调用人口筛选：callId={}, rel={}", column.input.callId, b.peek())
            val projection = layout.dimensions.map(b::field).toMutableList<RexNode>()
            projection.add(SemanticNumericRex.output(b, b.field(column.aggregate), column.input.metric.factMeasure().valueContract))
            result[column.input] = b.project(projection, layout.dimensions + column.input.outputAlias, true).build()
        }
    }
    fun aggregateInputs(branches: List<ExecutableMetricInput>): List<List<ExecutableMetricInput>> {
        val groups = mutableListOf<MutableList<ExecutableMetricInput>>()
        branches.forEach { branch -> groups.firstOrNull { sameAggregateInput(it.first(), branch) }?.add(branch) ?: groups.add(mutableListOf(branch)) }
        return groups
    }
    private fun sameAggregateInput(left: ExecutableMetricInput, right: ExecutableMetricInput): Boolean =
        left.event == right.event && left.topology == right.topology && left.groupingLevels == right.groupingLevels && left.timeAnchor == right.timeAnchor &&
            sourceRows.source(left) == sourceRows.source(right) && left.objectRestrictions == right.objectRestrictions && left.analysisDimensions == right.analysisDimensions &&
            left.timeRange == right.timeRange && sourceRows.source(left).temporalClockBindings == sourceRows.source(right).temporalClockBindings &&
            left.metric.factMeasure().snapshotRule == right.metric.factMeasure().snapshotRule && left.filterExpression == right.filterExpression

    fun branch(builder: RelBuilder, group: List<ExecutableMetricInput>, parameters: MutableList<JdbcParameter>, objects: (ExecutableObjectRestriction) -> RelNode): RelNode {
        val input = group.first(); val levels = input.groupingLevels
        if (levels != null && input.metric.factMeasure().snapshotRule != null) {
            val variants = levels.levels.map { branch(builder, group, parameters, it, objects) }
            return CompletedRelations.combineLevels(builder, variants, levels.levels, dimensionAliases(input), levels.outputAlias, group.map { it.outputAlias })
        }
        return branch(builder, group, parameters, null, objects)
    }
    fun branch(builder: RelBuilder, group: List<ExecutableMetricInput>, parameters: MutableList<JdbcParameter>, selectedLevel: GroupingLevels.BoundLevel?, objects: (ExecutableObjectRestriction) -> RelNode): RelNode {
        val branch = group.first()
        val rows = qualifiedRows(builder, branch, parameters, objects)
        val dimensions = dimensionAliases(branch).filter { selectedLevel == null || it in selectedLevel.groupBy }
        val projection = projectFactInputs(builder, group, rows, dimensions)
        val levels = if (selectedLevel == null) branch.groupingLevels else null
        aggregateFacts(builder, projection, levels)
        return factOutputs(builder, projection, levels)
    }
    private fun qualifiedRows(builder: RelBuilder, branch: ExecutableMetricInput, parameters: MutableList<JdbcParameter>, objects: (ExecutableObjectRestriction) -> RelNode): SourceRelationCompiler.Rows {
        val rows = sourceRows.eventRows(builder, branch, parameters)
        branch.objectRestrictions.forEach { restriction ->
            val keys = branch.event.identity.entityRoles.getValue((restriction.target as Calculation.EventRole).role).map {
                rows.compile(SemanticExpression.Reference(SemanticExpression.ReferenceKind.LOGIC_UNIT, it.logicUnitId, it.logicUnitVersion), requireNotNull(sourceRows.source(branch).metricExpression))
            }
            SourceRelationCompiler.qualifyObjects(builder, objects(restriction), restriction.objectKeys, keys)
        }
        return rows
    }
    private fun projectFactInputs(builder: RelBuilder, group: List<ExecutableMetricInput>, rows: SourceRelationCompiler.Rows, dimensions: List<String>): FactAtomCompiler.Projection {
        val branch = group.first(); val projection = FactAtomCompiler.Projection.create(dimensions, group); val values = mutableListOf<RexNode>()
        branch.analysisDimensions.filter { !it.filterOnly && it.outputAlias in dimensions }.forEach { dimension ->
            val value = rows.compile(dimension.binding.expression, sourceRows.source(branch).dimension(dimension.binding))
            FactAtomCompiler.requireUnitCode(value, dimension.binding, group); values.add(value)
        }
        projection.columns.forEach { column -> val atom = FactAtomCompiler.input(builder, rows, column.input, null); values.add(atom.value); if (column.predicate != null) values.add(requireNotNull(atom.predicate)) }
        val snapshot = branch.metric.factMeasure().snapshotRule
        if (snapshot == null) rows.project(values, projection.names()) else FactSnapshotCompiler.compile(builder, rows, requireNotNull(sourceRows.source(branch).metricExpression), snapshot, projection, values)
        return projection
    }
    private fun aggregateFacts(builder: RelBuilder, projection: FactAtomCompiler.Projection, levels: GroupingLevels.Bound?) {
        val calls = projection.columns.map { FactAtomCompiler.aggregate(builder, it.measure(), builder.field(it.value), it.predicate?.let(builder::field), it.aggregate) }.toMutableList()
        if (levels != null && projection.dimensions.isNotEmpty()) calls.add(builder.aggregateCall(Ops.GROUPING, projection.dimensions.map(builder::field)).`as`(levels.outputAlias))
        builder.aggregate(RelationalAggregates.groupKey(builder, projection.dimensions, levels), calls)
    }
    private fun factOutputs(builder: RelBuilder, layout: FactAtomCompiler.Projection, levels: GroupingLevels.Bound?): RelNode {
        val projection = layout.dimensions.map(builder::field).toMutableList<RexNode>(); val aliases = layout.dimensions.toMutableList()
        if (levels != null) { projection.add(if (layout.dimensions.isEmpty()) builder.rexBuilder.makeBigintLiteral(BigDecimal.ZERO) else builder.field(levels.outputAlias)); aliases.add(levels.outputAlias) }
        layout.columns.forEach { column ->
            val input = column.input
            require(input.outputAlias !in aliases) { "duplicate metric invocation output: ${input.outputAlias}" }
            projection.add(SemanticNumericRex.output(builder, builder.field(column.aggregate), input.metric.factMeasure().valueContract)); aliases.add(input.outputAlias)
        }
        return builder.project(projection, aliases, true).build()
    }
    fun requireAlignment(branches: List<ExecutableMetricInput>, aliases: List<String>) {
        if (branches.isEmpty()) return
        val grain = dimensionRefs(branches.first()); ScopedInputContract.requireGrainSubset(grain, grain)
        check(branches.all { aliases == dimensionAliases(it) && grain == dimensionRefs(it) }) { "branch dimension refs or aliases differ" }
        requireValueAlignment(branches, grain.toSet())
    }
    fun requireValueAlignment(branches: List<ExecutableMetricInput>, grain: Set<ScopedInputContract.DimensionRef>) {
        val mappings = linkedMapOf<String, MutableList<ValueAlignmentContract.AlignmentRef>>()
        branches.forEach { branch -> branch.analysisDimensions.filterNot { it.filterOnly }.forEach { dimension ->
            val asset = dimension.analysisDimension; val ref = ScopedInputContract.DimensionRef(asset.analysisDimensionId, asset.analysisDimensionVersion, dimension.levelCode)
            if (ref in grain) mappings.getOrPut("${ref.id}@${ref.version}#${ref.levelCode}") { mutableListOf() }.add(ValueAlignmentContract.AlignmentRef(
                "${branch.event.eventId}@${branch.event.eventVersion}", dimension.valueAlignmentKey, dimension.valueAlignmentStatus))
        } }
        mappings.forEach { (ref, values) -> ValueAlignmentContract.requireCrossEventAlignment(values, "compiledInputs.alignment[$ref]") }
    }
    private fun dimensionAliases(branch: ExecutableMetricInput): List<String> = branch.analysisDimensions.filterNot { it.filterOnly }.map { it.outputAlias }
    private fun dimensionRefs(branch: ExecutableMetricInput): List<ScopedInputContract.DimensionRef> = branch.analysisDimensions.filterNot { it.filterOnly }.map {
        ScopedInputContract.DimensionRef(it.analysisDimension.analysisDimensionId, it.analysisDimension.analysisDimensionVersion, it.levelCode)
    }
}
