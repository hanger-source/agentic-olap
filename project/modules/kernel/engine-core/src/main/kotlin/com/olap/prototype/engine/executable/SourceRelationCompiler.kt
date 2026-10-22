package com.olap.prototype.engine.executable

import com.olap.prototype.calculation.Calculation
import com.olap.prototype.connector.SourceCapabilities
import com.olap.prototype.connector.SourceCapabilityException
import com.olap.prototype.contract.JdbcParameter
import com.olap.prototype.engine.calcite.FrozenCatalogSchema
import com.olap.prototype.engine.calcite.SemanticRexCompiler
import com.olap.prototype.engine.calcite.SourceRelMetadataQuery
import com.olap.prototype.engine.catalog.*
import com.olap.prototype.engine.catalog.ExecutableRelationPosition.Expression
import com.olap.prototype.engine.catalog.ExecutableRelationPosition.Id
import com.olap.prototype.expression.*
import com.olap.prototype.metric.MetricAggregation
import com.olap.prototype.query.*
import com.olap.prototype.warehouse.contract.JdbcTableConstraints
import org.apache.calcite.rel.RelNode
import org.apache.calcite.rel.core.*
import org.apache.calcite.rel.logical.LogicalProject
import org.apache.calcite.rex.*
import org.apache.calcite.sql.`fun`.SqlStdOperatorTable as Ops
import org.apache.calcite.sql.type.SqlTypeUtil
import org.apache.calcite.tools.RelBuilder
import org.apache.calcite.util.ImmutableBitSet

/** 已绑定来源的扫描、关联和存在性；关联粒度必须由真实原生约束证明。 */
internal class SourceRelationCompiler(private val sources: ResolvedInputSources) {
    fun source(input: ExecutableEventInput): ResolvedEventInput = sources.require(input)
    fun shareableTargets(owner: RelBuilder, input: ExecutableMetricInput, parameters: MutableList<JdbcParameter>): Boolean {
        val source = sources.require(input)
        for (join in source.joins) {
            if (join.joinPath.keys.isEmpty()) return false
            val b = RelBuilder.proto().create(owner.cluster, owner.relOptSchema)
            val columns = linkedMapOf<Id, Map<String, String>>()
            scan(b, join.target, source.positions.getValue(join.target), columns)
            val compiler = positionedCompiler(b, join.target, source.positions, { at, name -> b.field(column(columns, at, name)) }, emptyMap(), parameters, linkedSetOf())
            val keys = join.joinPath.keys.map { compiler.compile(ref(it.targetLogicUnitId, it.targetLogicUnitVersion), Expression.local(join.target)) }
            val projection = b.project(keys).build()
            if (projection.cluster.metadataQuery.areColumnsUnique(projection, ImmutableBitSet.range(keys.size), true) != true) return false
        }
        return true
    }
    fun eventRows(builder: RelBuilder, input: ExecutableEventInput, parameters: MutableList<JdbcParameter>): Rows =
        startRows(builder, sources.require(input), AssociationUse.Event(input), parameters).also { it.filter(it.predicates(input)) }
    fun backboneRows(builder: RelBuilder, source: ResolvedEventInput, parameters: MutableList<JdbcParameter>): Rows = startRows(builder, source, AssociationUse.Backbone, parameters)

    /** 列坐标、相关性和Rex属于同一行发射过程。 */
    inner class Rows internal constructor(val builder: RelBuilder, val root: Id, val positions: Map<Id, ExecutablePhysicalExecutionBinding>,
        val quantifiers: Map<List<String>, ResolvedQuantifier>, val parameters: MutableList<JdbcParameter>, val outer: OuterRows) {
        val columns = linkedMapOf<Id, Map<String, String>>()
        private val correlations = linkedSetOf<CorrelationId>()
        val fields: (Id, String) -> RexNode = { at, name -> if (at in columns) builder.field(column(columns, at, name)) else outer.fields(at, name) }
        private val expressions = positionedCompiler(builder, root, positions, fields, quantifiers, parameters, correlations)
        fun source(input: ExecutableEventInput): ResolvedEventInput = sources.require(input)
        fun compile(expression: SemanticExpression, position: Expression): RexNode = expressions.compile(expression, position)
        fun field(position: Id, column: String): RexNode = fields(position, column)
        internal fun associate(clocks: Map<String, TemporalClockBindings.Value>, use: AssociationUse, joins: List<ExecutablePhysicalJoin>) = RowAssociations(this, clocks, use).emit(joins)
        fun filter(predicates: List<RexNode>) { builder.filter(expressions.drainCorrelations(), predicates) }
        fun project(values: List<RexNode>, names: List<String>) { builder.projectNamed(values, names, true, expressions.drainCorrelations()) }

        fun predicates(input: ExecutableEventInput): MutableList<RexNode> {
            val source = sources.require(input)
            val predicates = mutableListOf<RexNode>()
            input.event.baseFilter?.let { predicates.add(expressions.compile(it, source.eventExpression)) }
            input.window()?.let { window ->
                val time = expressions.compile(requireNotNull(input.rangeExpression()), requireNotNull(source.rangeExpression))
                SemanticNativeTypes.requireTemporalType(time.type, window.valueType)
                fun boundary(text: String): RexNode {
                    val scalar = Calculation.Scalar(ScalarValueType.valueOf(window.valueType.name), text)
                    return SemanticCalciteRexCompiler.parameter(builder, parameters, SemanticNativeTypes.scalarParameterType(builder, scalar), scalar.jdbcValue())
                }
                window.start?.let { predicates.add(builder.call(Ops.GREATER_THAN_OR_EQUAL, time, boundary(it))) }
                window.end?.let { predicates.add(builder.call(Ops.LESS_THAN, time, boundary(it))) }
            }
            predicates.add(condition(input, input.filterExpression) { exists ->
                require(input is ExecutableMetricInput) { "member input has no public relationship filter" }
                val relation = requireNotNull(input.relationConditions[exists.instanceId]) { "bound relation occurrence is missing" }
                relationExists(relation.input, source.positions)
            })
            return predicates
        }
        private fun relationExists(input: ExecutableMemberInput.Projection, parent: Map<Id, ExecutablePhysicalExecutionBinding>): RexNode {
            val outer = correlatedFields(builder, fields, correlations)
            val child = RelBuilder.proto().create(builder.cluster, builder.relOptSchema)
            val rows = scopedRows(child, sources.require(input), OuterRows(parent.keys, outer), parameters)
            rows.filter(rows.predicates(input))
            rows.filter(listOf(MemberRelationCompiler.condition(child, rows, input, input.condition, parameters)))
            rows.project(listOf(child.literal(1)), listOf("__exists"))
            return RexSubQuery.exists(child.build())
        }
        private fun condition(branch: ExecutableEventInput, expression: DimensionCondition, quantifiers: (DimensionCondition.Exists) -> RexNode): RexNode = when (expression) {
            is DimensionCondition.Unrestricted -> builder.literal(true)
            is DimensionCondition.Exists -> quantifiers(expression)
            is DimensionCondition.All -> builder.and(expression.operands.map { condition(branch, it, quantifiers) })
            is DimensionCondition.Any -> builder.or(expression.operands.map { condition(branch, it, quantifiers) })
            is DimensionCondition.Not -> builder.not(condition(branch, expression.operand, quantifiers))
            is DimensionCondition.Predicate -> {
                val dimension = branch.analysisDimensions.firstOrNull { item -> expression.dimension.id == item.analysisDimension.analysisDimensionId &&
                    expression.dimension.version == item.analysisDimension.analysisDimensionVersion && expression.dimension.levelCode == item.levelCode }
                    ?: error("filter has no exact frozen dimension mapping")
                val field = expressions.compile(dimension.binding.expression, sources.require(branch).dimension(dimension.binding))
                if (branch is ExecutableMetricInput) FactAtomCompiler.requireUnitCode(field, dimension.binding, listOf(branch))
                val values = expression.values.map { SemanticCalciteRexCompiler.parameter(builder, parameters, SemanticNativeTypes.scalarParameterType(builder, it), it.jdbcValue()) }
                SemanticCalciteOperators.predicate(builder, field, expression.operator, values)
            }
        }
    }
    internal data class OuterRows(val positions: Set<Id>, val fields: (Id, String) -> RexNode) {
        companion object { fun none(): OuterRows = OuterRows(emptySet()) { position, _ -> error("source row has no outer relation position: $position") } }
    }
    private fun startRows(builder: RelBuilder, source: ResolvedEventInput, use: AssociationUse, parameters: MutableList<JdbcParameter>): Rows =
        Rows(builder, source.root, source.positions, source.quantifiers, parameters, OuterRows.none()).also {
            scan(builder, source.root, source.backboneExecutionBinding(), it.columns)
            it.associate(source.temporalClockBindings, use, source.joins)
        }
    private fun scopedRows(builder: RelBuilder, source: ResolvedQuantifier, outer: OuterRows, parameters: MutableList<JdbcParameter>): Rows =
        Rows(builder, source.predicate.local, source.positions, source.quantifiers, parameters, outer).also {
            builder.values(arrayOf("__exists_seed"), 1); it.associate(source.clocks, AssociationUse.Backbone, source.joins)
        }
    private fun scopedRows(builder: RelBuilder, source: ResolvedEventInput, outer: OuterRows, parameters: MutableList<JdbcParameter>): Rows =
        Rows(builder, source.root, source.positions, source.quantifiers, parameters, outer).also {
            builder.values(arrayOf("__exists_seed"), 1); it.associate(source.temporalClockBindings, AssociationUse.Backbone, source.joins)
        }
    internal sealed interface AssociationUse {
        data class Event(val input: ExecutableEventInput) : AssociationUse
        data object Backbone : AssociationUse
    }

    /** 关联按键→时态→原生粒度证明→人口完成后才进入available。 */
    private inner class RowAssociations(private val rows: Rows, private val clocks: Map<String, TemporalClockBindings.Value>, private val use: AssociationUse) {
        private val available = (rows.outer.positions + rows.columns.keys).toCollection(linkedSetOf())
        fun emit(joins: List<ExecutablePhysicalJoin>) { joins.forEach(::emit) }
        private fun emit(join: ExecutablePhysicalJoin) {
            check(join.source in available && join.target !in available) { "join occurrences must be emitted in dependency order" }
            scan(rows.builder, join.target, rows.positions.getValue(join.target), rows.columns)
            val fields: (Id, String) -> RexNode = { at, name -> if (at in rows.columns)
                rows.builder.field(2, if (at == join.target) 1 else 0, column(rows.columns, at, name)) else rows.outer.fields(at, name) }
            val compiler = positionedCompiler(rows.builder, rows.root, rows.positions, fields, rows.quantifiers, rows.parameters, linkedSetOf())
            JoinEmission(join, compiler).emit()
        }
        private inner class JoinEmission(private val join: ExecutablePhysicalJoin, private val compiler: SemanticRexCompiler) {
            private val path = join.joinPath
            private val conditions = mutableListOf<RexNode>()
            private val targetKeys = mutableListOf<RexNode>()
            private val targetIntervals = mutableListOf<List<RexNode>>()
            fun emit() {
                require(path.keys.isNotEmpty()) { "frozen join requires explicit keys" }
                path.keys.forEach { key ->
                    val target = compiler.compile(ref(key.targetLogicUnitId, key.targetLogicUnitVersion), Expression.local(join.target))
                    targetKeys.add(target)
                    conditions.add(rows.builder.equals(compiler.compile(ref(key.sourceLogicUnitId, key.sourceLogicUnitVersion), Expression.local(join.source)), target))
                }
                path.temporalConstraint?.intervals?.forEachIndexed(::interval)
                population(proveGrain())
            }
            private fun interval(index: Int, interval: TemporalJoinContract.Interval) {
                val from = compiler.compile(ref(interval.targetValidFromLogicUnitId, interval.targetValidFromLogicUnitVersion.toString()), Expression.local(join.target))
                val to = compiler.compile(ref(interval.targetValidToLogicUnitId, interval.targetValidToLogicUnitVersion.toString()), Expression.local(join.target))
                targetIntervals.add(listOf(from, to))
                val time = sourceTime(index, interval.sourceTime)
                require(time.type.sqlTypeName == from.type.sqlTypeName && from.type.sqlTypeName == to.type.sqlTypeName) { "temporal clock and interval endpoints must have the same temporal interpretation" }
                conditions.add(rows.builder.call(if (interval.validFromInclusive) Ops.GREATER_THAN_OR_EQUAL else Ops.GREATER_THAN, time, from))
                val upper = rows.builder.call(if (interval.validToInclusive) Ops.LESS_THAN_OR_EQUAL else Ops.LESS_THAN, time, to)
                conditions.add(if (interval.openEndedValidTo) rows.builder.or(rows.builder.isNull(to), upper) else upper)
            }
            private fun sourceTime(index: Int, clock: TemporalJoinContract.Clock): RexNode = when (clock) {
                is TemporalJoinContract.LogicUnitTime -> {
                    val at = requireNotNull(join.sourceTimes[index]) { "source clock position is missing" }
                    check(at in available) { "source clock position must precede its target" }
                    compiler.compile(ref(clock.logicUnitId, clock.logicUnitVersion.toString()), Expression.local(at))
                }
                is TemporalJoinContract.QueryClock -> {
                    val slot = requireNotNull(join.clockSlots[index]) { "compiled join clock slot is missing" }
                    val value = requireNotNull(clocks[slot]) { "positioned query clock is missing" }
                    require(value.valueType == clock.valueType) { "query clock differs from frozen temporal interpretation" }
                    val scalar = Calculation.Scalar(ScalarValueType.valueOf(value.valueType.name), value.value)
                    SemanticCalciteRexCompiler.parameter(rows.builder, rows.parameters, SemanticNativeTypes.scalarParameterType(rows.builder, scalar), scalar.jdbcValue())
                }
            }
            private fun proveGrain(): Boolean {
                val event = use as? AssociationUse.Event ?: return false
                val single = sourceSingleMatch(rows.builder, join, targetKeys, targetIntervals, rows.columns.getValue(join.target))
                requireSourceGrain(event.input, join, single, rows.builder, targetKeys)
                return single
            }
            private fun population(single: Boolean) {
                val direction = SourceJoinContract.type(path.joinType)
                val emitted = when {
                    join.requiredMatch -> JoinRelType.INNER
                    (use as? AssociationUse.Event)?.input is ExecutableMemberInput.Projection -> JoinRelType.valueOf(direction.name)
                    direction.requiresBackboneMatch() -> JoinRelType.INNER
                    else -> JoinRelType.LEFT
                }
                rows.builder.join(emitted, rows.builder.and(conditions)); available.add(join.target)
                if (single) (rows.builder.peek() as? Join)?.let(SourceRelMetadataQuery::adoptSingleMatch)
            }
        }
    }

    private fun positionedCompiler(builder: RelBuilder, root: Id, positions: Map<Id, ExecutablePhysicalExecutionBinding>, fields: (Id, String) -> RexNode,
        quantifiers: Map<List<String>, ResolvedQuantifier>, parameters: MutableList<JdbcParameter>, correlations: MutableSet<CorrelationId>): SemanticRexCompiler =
        SemanticRexCompiler(builder, root, positions, fields, parameters, { exists, context ->
            val source = requireNotNull(quantifiers[context.quantifier(exists.instanceId)]) { "quantifier position is missing from the bound source" }
            val outer = correlatedFields(builder, fields, correlations)
            val child = RelBuilder.proto().create(builder.cluster, builder.relOptSchema)
            val rows = scopedRows(child, source, OuterRows(positions.keys, outer), parameters)
            rows.filter(listOf(rows.compile(exists.predicate, source.predicate)))
            rows.project(listOf(child.literal(1)), listOf("__exists")); RexSubQuery.exists(child.build())
        }, correlations)

    private fun requireSourceGrain(input: ExecutableEventInput, join: ExecutablePhysicalJoin, singleMatch: Boolean, builder: RelBuilder, targetKeys: List<RexNode>) {
        if (input !is ExecutableMetricInput) return
        val fact = input.metric.factMeasure()
        fun roles(expression: SemanticExpression?): Set<String> = expression?.let { SemanticExpressionInspector.references(it).filter { ref -> ref.referenceKind == SemanticExpression.ReferenceKind.ROLE_LOGIC_UNIT }.map { ref -> requireNotNull(ref.role) }.toSet() } ?: emptySet()
        val privateRoles = roles(fact.privatePredicate); val inputRoles = roles(fact.input)
        val privateOnly = requireNotNull(sources.require(input).metricExpression).roles.any { (role, at) -> at.role == join.target.role && role in privateRoles && role !in inputRoles }
        require(!privateOnly || !SourceJoinContract.type(join.joinPath.joinType).requiresBackboneMatch()) { "private row predicates require optional access to preserve fact population" }
        if (singleMatch || privateOnly && fact.snapshotRule == null && (fact.distinct || fact.aggregation in setOf(MetricAggregation.MIN, MetricAggregation.MAX))) return
        val width = builder.peek(2, 0).rowType.fieldCount
        val keys = if (targetKeys.all { it is RexInputRef }) targetKeys.mapTo(hashSetOf()) { (it as RexInputRef).index - width } else emptySet()
        if (keys.isNotEmpty()) requireAvailableKeys(builder.peek(), keys, join.joinPath.joinPathId)
        throw IllegalArgumentException("source join lacks an adopted uniqueness guarantee for its matching target keys: ${join.joinPath.joinPathId}@${join.joinPath.joinPathVersion}")
    }

    companion object {
        fun qualifyObjects(builder: RelBuilder, objects: RelNode, objectKeys: List<String>, targetKeys: List<RexNode>) {
            require(objectKeys.isNotEmpty() && objectKeys.size == targetKeys.size) { "object qualification must map the complete nonempty identity tuple" }
            builder.push(objects)
            val conditions = objectKeys.indices.map { index ->
                val target = targetKeys[index]; val actual = builder.field(2, 1, objectKeys[index])
                require(SqlTypeUtil.equalSansNullability(builder.typeFactory, target.type, actual.type)) { "object qualification identity types differ at tuple position $index" }
                SemanticCalciteOperators.codeEquals(builder, target, actual)
            }
            builder.join(JoinRelType.SEMI, builder.and(conditions))
        }
        private fun correlatedFields(builder: RelBuilder, fields: (Id, String) -> RexNode, correlations: MutableSet<CorrelationId>): (Id, String) -> RexNode {
            var created: RexCorrelVariable? = null
            builder.variable { created = it }
            val variable = requireNotNull(created)
            correlations.add(variable.id)
            return { position, name -> when (val field = fields(position, name)) {
                is RexInputRef -> builder.rexBuilder.makeFieldAccess(variable, field.index)
                is RexFieldAccess -> field
                else -> error("related source fields must retain their exact local or ancestor position")
            } }
        }
        private fun requireAvailableKeys(target: RelNode, fields: Set<Int>, joinId: String) {
            when (target) {
                is TableScan -> requireNotNull(target.table) { "source relationship scan requires its exact table" }.unwrap(FrozenCatalogSchema.UnavailableKeys::class.java)?.let { unavailable ->
                    if (unavailable.keys.any { fields.containsAll(it.asList()) }) throw SourceCapabilityException(unavailable.dataSourceId, "source-join/$joinId", SourceCapabilities.Capability.CONSTRAINT_VERIFICATION)
                }
                is Project -> {
                    val values = fields.map { target.projects[it] }
                    if (values.all { it is RexInputRef }) requireAvailableKeys(target.input, values.mapTo(hashSetOf()) { (it as RexInputRef).index }, joinId)
                }
                is Filter -> requireAvailableKeys(target.input, fields, joinId)
                is Sort -> requireAvailableKeys(target.input, fields, joinId)
            }
        }
        private fun sourceSingleMatch(builder: RelBuilder, join: ExecutablePhysicalJoin, targetKeys: List<RexNode>, targetIntervals: List<List<RexNode>>, columns: Map<String, String>): Boolean {
            val target = builder.peek(); val width = builder.peek(2, 0).rowType.fieldCount
            val localKeys = targetKeys.map { RexUtil.shift(it, -width) }
            val projection = LogicalProject.create(target, emptyList(), localKeys, localKeys.indices.map { "__join_key_$it" }, emptySet())
            return projection.cluster.metadataQuery.areColumnsUnique(projection, ImmutableBitSet.range(localKeys.size), true) == true || temporalSourceGrain(target, width, join, targetKeys, targetIntervals, columns)
        }
        private fun temporalSourceGrain(target: RelNode, sourceWidth: Int, join: ExecutablePhysicalJoin, keys: List<RexNode>, endpoints: List<List<RexNode>>, columns: Map<String, String>): Boolean {
            val temporal = join.joinPath.temporalConstraint ?: return false
            val tables = target.cluster.metadataQuery.getTableReferences(target) ?: return false
            if (tables.size != 1) return false
            val guarantees = tables.single().table.unwrap(FrozenCatalogSchema.TemporalKeys::class.java) ?: return false
            if (guarantees.keys.isEmpty()) return false
            val names = linkedSetOf<String>()
            keys.forEach { names.add(sourceColumn(target, RexUtil.shift(it, -sourceWidth), columns) ?: return false) }
            val intervals = endpoints.mapIndexed { index, values ->
                val interval = temporal.intervals[index]
                val from = sourceColumn(target, RexUtil.shift(values[0], -sourceWidth), columns) ?: return false
                val to = sourceColumn(target, RexUtil.shift(values[1], -sourceWidth), columns) ?: return false
                JdbcTableConstraints.TemporalInterval(from, to, interval.validFromInclusive, interval.validToInclusive, interval.openEndedValidTo)
            }
            guarantees.keys.firstOrNull { names.containsAll(it.rule.equalityColumns) && intervals.containsAll(it.rule.intervals) }?.let {
                guarantees.adopt(it.rule.name); return true
            }
            return false
        }
        private fun sourceColumn(target: RelNode, expression: RexNode, columns: Map<String, String>): String? {
            val field = expression as? RexInputRef ?: return null
            val alias = target.rowType.fieldList[field.index].name
            return columns.entries.firstOrNull { it.value == alias }?.key
        }
        private fun scan(builder: RelBuilder, position: Id, binding: ExecutablePhysicalExecutionBinding, columns: MutableMap<Id, Map<String, String>>) {
            builder.scan(FrozenCatalogSchema.path(binding))
            val names = builder.peek().rowType.fieldNames
            val mapping = names.mapIndexed { index, name -> name to "scan_${columns.size}_$index" }.toMap()
            builder.project(builder.fields(), mapping.values.toList(), true)
            check(columns.putIfAbsent(position, mapping) == null) { "relation position was scanned twice" }
        }
        private fun column(columns: Map<Id, Map<String, String>>, owner: Id, name: String): String =
            requireNotNull(requireNotNull(columns[owner]) { "relationship position has not been scanned" }[name]) { "frozen column is not in the scan row type" }
        fun ref(id: String, version: String): SemanticExpression.Reference = SemanticExpression.Reference(SemanticExpression.ReferenceKind.LOGIC_UNIT, id, version.toLong())
    }
}
