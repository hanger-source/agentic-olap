package com.olap.prototype.query

import com.olap.prototype.calculation.Calculation
import com.olap.prototype.calculation.MemberInvocationBinding
import com.olap.prototype.calculation.MetricDefinition
import com.olap.prototype.calculation.MetricRef
import com.olap.prototype.member.MemberCondition
import com.olap.prototype.member.MemberRef
import com.olap.prototype.metric.MetricAggregation
import com.olap.prototype.metric.MetricSnapshotRule
import com.olap.prototype.metric.MetricValueContract
import java.math.BigDecimal
import java.util.Collections
import java.util.TreeMap

/** 绑定完成的值含义；声明归指标/阶段，单位键归实际符号。 */
data class QueryQuantity(val declaration: MetricValueContract, val unit: Unit, val provenance: Provenance) {
    sealed interface Provenance
    class Raw(val source: ValuePosition?, observations: List<ValuePosition>) : Provenance {
        val observations: List<ValuePosition> = positions(observations)
        override fun equals(other: Any?): Boolean = other is Raw && source == other.source && observations == other.observations
        override fun hashCode(): Int = listOf(source, observations).hashCode()
    }
    class Constant(value: String) : Provenance {
        val value: String = BigDecimal(value).stripTrailingZeros().toPlainString()
        override fun equals(other: Any?): Boolean = other is Constant && value == other.value
        override fun hashCode(): Int = value.hashCode()
    }
    class Snapshot(val reduction: MetricSnapshotRule.Reduction, timeAxes: List<ValuePosition>, boundClocks: Map<String, TemporalClockBindings.Value>) {
        val timeAxes: List<ValuePosition> = positions(timeAxes)
        val boundClocks: Map<String, TemporalClockBindings.Value> = Collections.unmodifiableMap(TreeMap(boundClocks))
        override fun equals(other: Any?): Boolean = other is Snapshot && reduction == other.reduction && timeAxes == other.timeAxes && boundClocks == other.boundClocks
        override fun hashCode(): Int = listOf(reduction, timeAxes, boundClocks).hashCode()
    }
    class Fact(val definition: MetricRef, val aggregation: MetricAggregation, val distinct: Boolean,
        val source: ValuePosition, observations: List<ValuePosition>, val snapshot: Snapshot?) : Provenance {
        val observations: List<ValuePosition> = positions(observations)
        override fun equals(other: Any?): Boolean = other is Fact && definition == other.definition && aggregation == other.aggregation && distinct == other.distinct && source == other.source && observations == other.observations && snapshot == other.snapshot
        override fun hashCode(): Int = listOf(definition, aggregation, distinct, source, observations, snapshot).hashCode()
    }
    class SelectedSnapshot(val input: Provenance, timeAxes: List<ValuePosition>, val asOf: Calculation.Scalar?) : Provenance {
        val timeAxes: List<ValuePosition> = positions(timeAxes)
        init {
            require(this.timeAxes.isNotEmpty()) { "selected snapshot requires its actual time axis" }
            require(asOf == null || asOf.scalarType in setOf(ScalarValueType.DATE, ScalarValueType.LOCAL_TIMESTAMP, ScalarValueType.OFFSET_TIMESTAMP)) { "selected snapshot point must be temporal" }
        }
        override fun equals(other: Any?): Boolean = other is SelectedSnapshot && input == other.input && timeAxes == other.timeAxes && asOf == other.asOf
        override fun hashCode(): Int = listOf(input, timeAxes, asOf).hashCode()
    }
    class Reduction(val aggregation: ResultAggregation.Kind, val input: Provenance, observations: List<ValuePosition>) : Provenance {
        val observations: List<ValuePosition> = positions(observations)
        override fun equals(other: Any?): Boolean = other is Reduction && aggregation == other.aggregation && input == other.input && observations == other.observations
        override fun hashCode(): Int = listOf(aggregation, input, observations).hashCode()
    }
    class UniformAllocation(val input: Provenance, sourceKeys: List<ValuePosition>, memberKeys: List<ValuePosition>) : Provenance {
        val sourceKeys: List<ValuePosition> = positions(sourceKeys)
        val memberKeys: List<ValuePosition> = positions(memberKeys)
        init { require(this.sourceKeys.isNotEmpty() && this.memberKeys.isNotEmpty()) { "uniform allocation requires source and associated member identities" } }
        override fun equals(other: Any?): Boolean = other is UniformAllocation && input == other.input && sourceKeys == other.sourceKeys && memberKeys == other.memberKeys
        override fun hashCode(): Int = listOf(input, sourceKeys, memberKeys).hashCode()
    }
    class Linear(terms: List<Provenance>) : Provenance {
        val terms: List<Provenance> = contributions(terms, true)
        override fun equals(other: Any?): Boolean = other is Linear && terms == other.terms
        override fun hashCode(): Int = terms.hashCode()
    }
    enum class Nature { RATIO, PRODUCT, WEIGHTED_VALUE, WEIGHTED_WEIGHT, ABSOLUTE, RANK }
    class Observed(val nature: Nature, components: List<Provenance>, observations: List<ValuePosition>, val knownObservation: Boolean) : Provenance {
        val components: List<Provenance> = contributions(components, false)
        val observations: List<ValuePosition> = positions(observations)
        override fun equals(other: Any?): Boolean = other is Observed && nature == other.nature && components == other.components && observations == other.observations && knownObservation == other.knownObservation
        override fun hashCode(): Int = listOf(nature, components, observations, knownObservation).hashCode()
    }
    data class Order(val position: ValuePosition, val direction: String, val nulls: String) {
        init { require(direction in setOf("ASC", "DESC") && nulls in setOf("FIRST", "LAST")) { "quantity window order must be explicit" } }
    }
    class Window(val aggregation: ResultAggregation.Kind, val input: Provenance, observations: List<ValuePosition>, partition: List<ValuePosition>, order: List<Order>,
        val frame: WindowFrameContract.Frame?, val cumulative: Boolean,
        val totalOrder: Boolean) : Provenance {
        val observations: List<ValuePosition> = positions(observations)
        val partition: List<ValuePosition> = positions(partition)
        val order: List<Order> = java.util.List.copyOf(order)
        init { require(!cumulative || aggregation == ResultAggregation.Kind.SUM && this.order.isNotEmpty()) { "cumulative quantity needs SUM and an actual order" } }
        override fun equals(other: Any?): Boolean = other is Window && aggregation == other.aggregation && input == other.input && observations == other.observations && partition == other.partition && order == other.order && frame == other.frame && cumulative == other.cumulative && totalOrder == other.totalOrder
        override fun hashCode(): Int = listOf(aggregation, input, observations, partition, order, frame, cumulative, totalOrder).hashCode()
    }
    class Offset(val input: Provenance, partition: List<ValuePosition>, order: List<Order>, val function: String, val distance: Int, val totalOrder: Boolean) : Provenance {
        val partition: List<ValuePosition> = positions(partition)
        val order: List<Order> = java.util.List.copyOf(order)
        init { require(function in setOf("LAG", "LEAD") && distance >= 1 && this.order.isNotEmpty()) { "quantity offset needs an explicit ordered neighbour" } }
        override fun equals(other: Any?): Boolean = other is Offset && input == other.input && partition == other.partition && order == other.order && function == other.function && distance == other.distance && totalOrder == other.totalOrder
        override fun hashCode(): Int = listOf(input, partition, order, function, distance, totalOrder).hashCode()
    }
    class Copied(input: Provenance) : Provenance {
        val input: Provenance = if (input is Copied) input.input else input
        override fun equals(other: Any?): Boolean = other is Copied && input == other.input
        override fun hashCode(): Int = input.hashCode()
    }
    data class Delta(val cumulative: Window) : Provenance
    class Alternatives(branches: List<Provenance>) : Provenance {
        val branches: List<Provenance> = contributions(branches, false)
        override fun equals(other: Any?): Boolean = other is Alternatives && branches == other.branches
        override fun hashCode(): Int = branches.hashCode()
    }

    sealed interface SourceProvenance { fun bind(source: ValuePosition, symbols: Map<String, QueryStageAnalysis.SymbolId>): Provenance }
    class RawSource(observations: List<String>) : SourceProvenance {
        val observations: List<String> = java.util.List.copyOf(observations)
        override fun bind(source: ValuePosition, symbols: Map<String, QueryStageAnalysis.SymbolId>): Provenance = Raw(source, sourcePositions(observations, symbols))
        override fun equals(other: Any?): Boolean = other is RawSource && observations == other.observations
        override fun hashCode(): Int = observations.hashCode()
    }
    class SnapshotSource(val reduction: MetricSnapshotRule.Reduction, timeColumns: List<String>, boundClocks: Map<String, TemporalClockBindings.Value>) {
        val timeColumns: List<String> = java.util.List.copyOf(timeColumns)
        val boundClocks: Map<String, TemporalClockBindings.Value> = Collections.unmodifiableMap(TreeMap(boundClocks))
        override fun equals(other: Any?): Boolean = other is SnapshotSource && reduction == other.reduction && timeColumns == other.timeColumns && boundClocks == other.boundClocks
        override fun hashCode(): Int = listOf(reduction, timeColumns, boundClocks).hashCode()
    }
    class FactSource(val definition: MetricRef, val aggregation: MetricAggregation, val distinct: Boolean, observations: List<String>, val snapshot: SnapshotSource?) : SourceProvenance {
        val observations: List<String> = java.util.List.copyOf(observations)
        override fun bind(source: ValuePosition, symbols: Map<String, QueryStageAnalysis.SymbolId>): Provenance = Fact(definition, aggregation, distinct, source,
            sourcePositions(observations, symbols), snapshot?.let { Snapshot(it.reduction, sourcePositions(it.timeColumns, symbols), it.boundClocks) })
        override fun equals(other: Any?): Boolean = other is FactSource && definition == other.definition && aggregation == other.aggregation && distinct == other.distinct && observations == other.observations && snapshot == other.snapshot
        override fun hashCode(): Int = listOf(definition, aggregation, distinct, observations, snapshot).hashCode()
    }
    data class ImportedProvenance(val provenance: Provenance) : SourceProvenance {
        override fun bind(source: ValuePosition, symbols: Map<String, QueryStageAnalysis.SymbolId>): Provenance = provenance
    }

    sealed interface Unit
    data class Fixed(val identity: MetricValueContract.UnitIdentity) : Unit
    sealed interface UnitReference
    data class SemanticDimensionRef(val dimensionId: String, val dimensionVersion: Long, val role: String) : UnitReference {
        init { MetricDefinition.text(dimensionId, "unit semantic dimension"); MetricDefinition.version(dimensionVersion); MetricDefinition.text(role, "unit dimension role") }
    }
    data class AnalysisDimensionRef(val dimension: ScopedInputContract.DimensionRef) : UnitReference
    data class MemberSlotRef(val sourceId: String, val sourceVersion: Long, val slot: String) : UnitReference {
        init { MetricDefinition.text(sourceId, "unit member source"); MetricDefinition.version(sourceVersion); MetricDefinition.text(slot, "unit member slot") }
    }
    data class Key(val reference: UnitReference, val position: ValuePosition)
    class Dimension(val axis: MetricValueContract.UnitAxis, keys: List<Key>) : Unit {
        val keys: List<Key> = java.util.List.copyOf(keys)
        init { require(this.keys.isNotEmpty() && this.keys.map { it.position }.distinct().size == this.keys.size) { "unit needs distinct proved result key positions" } }
        override fun equals(other: Any?): Boolean = other is Dimension && axis == other.axis && keys == other.keys
        override fun hashCode(): Int = listOf(axis, keys).hashCode()
    }
    sealed interface SelectionTarget
    data class DimensionFilter(val filterRefId: String) : SelectionTarget { init { MetricDefinition.text(filterRefId, "unit dimension filter") } }
    data class MemberFilter(val source: MemberSlotRef) : SelectionTarget
    data class ResultFilter(val scope: QueryStageAnalysis.ScopeId, val operationIndex: Int, val key: QueryStageAnalysis.SymbolId, val member: Calculation.Scalar) : SelectionTarget {
        init { require(operationIndex >= 0) { "invalid unit selection operation" } }
    }
    data class Selection(val source: ValuePosition, val target: SelectionTarget)
    class SelectedMember(val reference: UnitReference, val axis: MetricValueContract.UnitAxis, member: Calculation.Scalar, val identity: MetricValueContract.UnitIdentity, val selection: Selection) : Unit {
        val member: Calculation.Scalar
        init {
            val matches = axis.members.filter { it.member.sameValue(member) }
            require(matches.size == 1 && matches.single().unit == identity) { "selected unit member is not its declared exact unit identity" }
            this.member = matches.single().member
        }
        override fun equals(other: Any?): Boolean = other is SelectedMember && reference == other.reference && axis == other.axis && member == other.member && identity == other.identity && selection == other.selection
        override fun hashCode(): Int = listOf(reference, axis, member, identity, selection).hashCode()
    }

    /** 根编译前的明确单位绑定，仅在这里把列名转为稳定符号。 */
    data class Source(val declaration: MetricValueContract, val unit: SourceUnit, val provenance: SourceProvenance) {
        fun bind(source: QueryStageAnalysis.SymbolId, symbols: Map<String, QueryStageAnalysis.SymbolId>): QueryQuantity = QueryQuantity(declaration,
            when (unit) {
                is FixedSource -> Fixed(unit.identity)
                is DimensionSource -> Dimension(unit.axis, unit.keys.map { Key(it.reference, ValuePosition.column(requireNotNull(symbols[it.column]) { "unit dimension must be retained in the source result" })) })
                is MemberSource -> SelectedMember(unit.reference, unit.axis, unit.member, unit.identity, Selection(ValuePosition.column(source), unit.target))
                is ImportedSource -> unit.unit
            }, provenance.bind(ValuePosition.column(source), symbols))
        companion object {
            fun fixed(declaration: MetricValueContract, provenance: SourceProvenance): Source {
                val fixed = declaration.unit as? MetricValueContract.Fixed ?: throw IllegalArgumentException("source does not declare a fixed unit")
                return Source(declaration, FixedSource(fixed.identity), provenance)
            }
            fun imported(quantity: QueryQuantity): Source = Source(quantity.declaration, ImportedSource(quantity.unit), ImportedProvenance(quantity.provenance))
        }
    }
    sealed interface SourceUnit
    data class FixedSource(val identity: MetricValueContract.UnitIdentity) : SourceUnit
    data class SourceKey(val reference: UnitReference, val column: String) { init { MetricDefinition.text(column, "unit key column") } }
    class DimensionSource(val axis: MetricValueContract.UnitAxis, keys: List<SourceKey>) : SourceUnit {
        val keys: List<SourceKey> = java.util.List.copyOf(keys)
        init { require(this.keys.isNotEmpty()) { "unit key is required" } }
        override fun equals(other: Any?): Boolean = other is DimensionSource && axis == other.axis && keys == other.keys
        override fun hashCode(): Int = listOf(axis, keys).hashCode()
    }
    data class MemberSource(val reference: UnitReference, val axis: MetricValueContract.UnitAxis, val member: Calculation.Scalar, val identity: MetricValueContract.UnitIdentity, val target: SelectionTarget) : SourceUnit
    data class ImportedSource(val unit: Unit) : SourceUnit

    fun remap(keys: Map<ValuePosition, ValuePosition>): QueryQuantity = QueryQuantity(declaration,
        if (unit is Dimension) Dimension(unit.axis, unit.keys.map { Key(it.reference, keys[it.position] ?: it.position) }) else unit, remap(provenance, keys))

    companion object {
        private fun sourcePositions(names: List<String>, symbols: Map<String, QueryStageAnalysis.SymbolId>): List<ValuePosition> =
            positions(names.map { ValuePosition.column(requireNotNull(symbols[it]) { "quantity observation column is unavailable: $it" }) })
        private fun positions(values: Collection<ValuePosition>): List<ValuePosition> {
            require(values.distinct().size == values.size) { "quantity repeats an observation coordinate" }
            return java.util.List.copyOf(values)
        }
        private fun contributions(values: List<Provenance>, linear: Boolean): List<Provenance> {
            val result = linkedSetOf<Provenance>()
            values.forEach { value -> when { linear && value is Linear -> result.addAll(value.terms); !linear && value is Alternatives -> result.addAll(value.branches); else -> result.add(value) } }
            require(result.size <= ScopedInputContract.MAX_INPUTS) { "quantity contributions exceed their input budget" }
            return java.util.List.copyOf(result)
        }
        fun references(value: Provenance, result: MutableSet<ValuePosition>) {
            when (value) {
                is Raw -> { value.source?.let { result.add(it) }; result.addAll(value.observations) }
                is Constant -> kotlin.Unit
                is Fact -> { result.add(value.source); result.addAll(value.observations); value.snapshot?.let { result.addAll(it.timeAxes) } }
                is Reduction -> { result.addAll(value.observations); references(value.input, result) }
                is Linear -> value.terms.forEach { references(it, result) }
                is Observed -> { result.addAll(value.observations); value.components.forEach { references(it, result) } }
                is Window -> { result.addAll(value.observations); result.addAll(value.partition); value.order.forEach { result.add(it.position) }; references(value.input, result) }
                is Offset -> { result.addAll(value.partition); value.order.forEach { result.add(it.position) }; references(value.input, result) }
                is Copied -> references(value.input, result)
                is Delta -> references(value.cumulative, result)
                is SelectedSnapshot -> { result.addAll(value.timeAxes); references(value.input, result) }
                is UniformAllocation -> { result.addAll(value.sourceKeys); result.addAll(value.memberKeys); references(value.input, result) }
                is Alternatives -> value.branches.forEach { references(it, result) }
            }
        }
        fun alternatives(values: List<Provenance>): Provenance {
            val distinct = contributions(values, false)
            require(distinct.isNotEmpty()) { "quantity alternatives require actual values" }
            return distinct.singleOrNull() ?: Alternatives(distinct)
        }
        fun remap(value: Provenance, symbols: Map<ValuePosition, ValuePosition>): Provenance {
            fun positions(values: List<ValuePosition>): List<ValuePosition> = values.map { symbols[it] ?: it }.distinct()
            fun orders(values: List<Order>): List<Order> = values.map { Order(symbols[it.position] ?: it.position, it.direction, it.nulls) }
            return when (value) {
                is Raw -> Raw(value.source, positions(value.observations))
                is Constant -> value
                is Fact -> Fact(value.definition, value.aggregation, value.distinct, value.source, positions(value.observations), value.snapshot?.let { Snapshot(it.reduction, positions(it.timeAxes), it.boundClocks) })
                is Reduction -> Reduction(value.aggregation, remap(value.input, symbols), positions(value.observations))
                is Linear -> Linear(value.terms.map { remap(it, symbols) })
                is Observed -> Observed(value.nature, value.components.map { remap(it, symbols) }, positions(value.observations), value.knownObservation)
                is Window -> Window(value.aggregation, remap(value.input, symbols), positions(value.observations), positions(value.partition), orders(value.order), value.frame, value.cumulative, value.totalOrder)
                is Offset -> Offset(remap(value.input, symbols), positions(value.partition), orders(value.order), value.function, value.distance, value.totalOrder)
                is Copied -> Copied(remap(value.input, symbols))
                is Delta -> Delta(remap(value.cumulative, symbols) as Window)
                is SelectedSnapshot -> SelectedSnapshot(remap(value.input, symbols), positions(value.timeAxes), value.asOf)
                is UniformAllocation -> UniformAllocation(remap(value.input, symbols), positions(value.sourceKeys), positions(value.memberKeys))
                is Alternatives -> Alternatives(value.branches.map { remap(it, symbols) })
            }
        }
        fun fixedIdentity(unit: Unit): MetricValueContract.UnitIdentity? = when (unit) { is Fixed -> unit.identity; is SelectedMember -> unit.identity; is Dimension -> null }
        fun sameUnit(left: Unit, right: Unit): Boolean {
            val first = fixedIdentity(left); val second = fixedIdentity(right)
            if (first != null || second != null) return first != null && second != null && first == second
            left as Dimension; right as Dimension
            return MetricValueContract.compatibleAxes(left.axis, right.axis) && left.keys.any { a -> right.keys.any { b -> a.reference == b.reference && a.position == b.position } }
        }
        fun conjunctiveSelections(condition: DimensionCondition): List<DimensionCondition.Predicate> = when (condition) {
            is DimensionCondition.Predicate -> listOf(condition)
            is DimensionCondition.All -> condition.operands.flatMap(::conjunctiveSelections)
            else -> emptyList()
        }
        private fun memberSelections(condition: MemberCondition): List<MemberCondition.Predicate> = when (condition) {
            is MemberCondition.Predicate -> listOf(condition)
            is MemberCondition.All -> condition.operands.flatMap(::memberSelections)
            else -> emptyList()
        }

        fun source(declaration: MetricValueContract, unitDimensions: List<ScopedInputContract.DimensionRef>, grouping: Map<ScopedInputContract.DimensionRef, String>,
            condition: DimensionCondition, provenance: SourceProvenance): Source {
            if (declaration.unit is MetricValueContract.Fixed) return Source.fixed(declaration, provenance)
            val axis = when (val unit = declaration.unit) {
                is MetricValueContract.EventDimension -> unit.axis
                is MetricValueContract.AnalysisDimension -> {
                    require(unitDimensions == listOf(unit.dimension)) { "unit candidates differ from the exact adopted analysis dimension" }
                    unit.axis
                }
                else -> throw IllegalArgumentException("unit dimension slot must be adopted before quantity binding")
            }
            val selected = conjunctiveSelections(condition).filter { it.operator == MemberCondition.Operator.EQ && it.dimension in unitDimensions && it.values.size == 1 }.flatMap { selection ->
                val value = selection.values.single()
                axis.members.filter { it.member.sameValue(value) }.map { member -> MemberSource(AnalysisDimensionRef(selection.dimension), axis, value, member.unit, DimensionFilter(selection.filterRefId)) }
            }
            if (selected.isNotEmpty()) {
                require(selected.map { it.identity }.distinct().size == 1) { "unit constraints do not identify one common fixed unit" }
                // 每条证据都独立证明同一单位；只按证据名称稳定选择，不改业务owner。
                return Source(declaration, selected.minBy { (it.target as DimensionFilter).filterRefId }, provenance)
            }
            val keys = unitDimensions.filter { it in grouping }.map { SourceKey(AnalysisDimensionRef(it), requireNotNull(grouping[it])) }
            require(keys.isNotEmpty()) { "dynamic currency or measurement unit requires a retained key or an approved exact member restriction" }
            return Source(declaration, DimensionSource(axis, keys), provenance)
        }

        fun memberSource(declaration: MetricValueContract, actual: MetricValueContract, source: MemberRef, selection: MemberInvocationBinding.Bound, provenance: SourceProvenance): Source {
            if (declaration.unit is MetricValueContract.Fixed) return Source.fixed(declaration, provenance)
            val local = actual.unit as? MetricValueContract.SlotDimension ?: throw IllegalArgumentException("member quantity has no declared local unit slot")
            val reference = MemberSlotRef(source.id, source.version, local.slot)
            val bound = declaration.unit as? MetricValueContract.AnalysisDimension
            val axis = bound?.axis ?: local.axis
            val proofs = memberSelections(selection.condition).filter { it.slot == local.slot && it.operator == MemberCondition.Operator.EQ && it.values.size == 1 }.flatMap { predicate ->
                val value = predicate.values.single() as Calculation.Scalar
                axis.members.filter { it.member.sameValue(value) }.map { member -> MemberSource(reference, axis, value, member.unit, MemberFilter(reference)) }
            }
            if (proofs.isNotEmpty()) {
                require(proofs.map { it.identity }.distinct().size == 1) { "member restrictions identify contradictory units" }
                val proof = proofs.first()
                return Source(declaration, MemberSource(bound?.let { AnalysisDimensionRef(it.dimension) } ?: reference, axis, proof.member, proof.identity, proof.target), provenance)
            }
            val keys = selection.outputs.filterValues { it == local.slot }.map { (alias, _) ->
                var key: UnitReference = reference
                val meaning = selection.meanings[alias]
                if (meaning is QueryStageAnalysis.MappedMemberDimension) {
                    require(meaning.dimension in (selection.unitDimensions[local.slot] ?: emptyList())) { "member unit key has no exact approved dimension mapping" }
                    key = AnalysisDimensionRef(meaning.dimension)
                }
                require(bound == null || key is AnalysisDimensionRef && key.dimension == bound.dimension) { "member quantity does not retain its adopted unit dimension" }
                SourceKey(key, alias)
            }
            require(keys.isNotEmpty()) { "member quantity must retain its local unit key or restrict it to an explicitly declared member" }
            return Source(declaration, DimensionSource(axis, keys), provenance)
        }

        fun calculationSource(declaration: MetricValueContract, quantities: List<QueryQuantity>, grouping: Map<ScopedInputContract.DimensionRef, String>): Source {
            require(quantities.isNotEmpty()) { "calculation requires a completed measure output" }
            quantities.forEach { QueryValueSemantics.requireDeclaration(declaration, it) }
            val actual = quantities.first()
            quantities.forEach { quantity ->
                require(fixedIdentity(actual.unit) == fixedIdentity(quantity.unit)) { "calculation levels have contradictory fixed unit restrictions" }
                val first = actual.unit; val second = quantity.unit
                require(first !is Dimension || second !is Dimension || MetricValueContract.compatibleAxes(first.axis, second.axis)) { "calculation levels use different unit axes" }
            }
            val provenance = ImportedProvenance(alternatives(quantities.map { it.provenance }))
            when (val unit = declaration.unit) {
                is MetricValueContract.Fixed -> {
                    require(fixedIdentity(actual.unit) == unit.identity) { "calculation output unit differs from its declared fixed unit" }
                    return Source(declaration, ImportedSource(actual.unit), provenance)
                }
                is MetricValueContract.AnalysisDimension -> {
                    val selected = actual.unit
                    if (selected is SelectedMember) {
                        require(MetricValueContract.compatibleAxes(selected.axis, unit.axis) && (selected.reference as? AnalysisDimensionRef)?.dimension == unit.dimension) { "calculation unit restriction differs from its adopted axis" }
                        return Source(declaration, ImportedSource(selected), provenance)
                    }
                    require(selected is Dimension && MetricValueContract.compatibleAxes(selected.axis, unit.axis) && selected.keys.any { (it.reference as? AnalysisDimensionRef)?.dimension == unit.dimension }) { "calculation output has no proved adopted unit axis" }
                    return source(declaration, listOf(unit.dimension), grouping, DimensionCondition.Unrestricted(), provenance)
                }
                else -> throw IllegalArgumentException("calculation unit slot must be adopted before quantity binding")
            }
        }
    }
}
