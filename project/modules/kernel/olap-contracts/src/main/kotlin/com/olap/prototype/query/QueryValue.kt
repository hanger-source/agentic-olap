package com.olap.prototype.query

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.olap.prototype.metric.MetricValueContract

/** 编译符号的值含义；多值结果在元素位置保留单位，消费者不重放表达式。 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes(JsonSubTypes.Type(value = QueryValue.Plain::class, name = "PLAIN"), JsonSubTypes.Type(value = QueryValue.Measure::class, name = "QUANTITY"), JsonSubTypes.Type(value = QueryValue.Row::class, name = "ROW"), JsonSubTypes.Type(value = QueryValue.Array::class, name = "ARRAY"))
sealed interface QueryValue {
    sealed interface Source {
        fun bind(id: QueryStageAnalysis.SymbolId, symbols: Map<String, QueryStageAnalysis.SymbolId>, imports: Map<ValuePosition, ValuePosition>): QueryValue
    }
    class Categorical : Source {
        override fun bind(id: QueryStageAnalysis.SymbolId, symbols: Map<String, QueryStageAnalysis.SymbolId>, imports: Map<ValuePosition, ValuePosition>): QueryValue = plain(id)
        override fun equals(other: Any?): Boolean = other is Categorical
        override fun hashCode(): Int = 0
    }
    class Structured(val shape: SetOutputContract.Shape, observations: List<String>) : Source {
        val observations: List<String> = java.util.List.copyOf(observations)
        init { SetOutputContract.requireSourceContainer(shape) }
        override fun bind(id: QueryStageAnalysis.SymbolId, symbols: Map<String, QueryStageAnalysis.SymbolId>, imports: Map<ValuePosition, ValuePosition>): QueryValue =
            sourceShape(shape, ValuePosition.column(id), observations.map { ValuePosition.column(requireNotNull(symbols[it]) { "container source identity is not retained" }) })
        override fun equals(other: Any?): Boolean = other is Structured && shape == other.shape && observations == other.observations
        override fun hashCode(): Int = listOf(shape, observations).hashCode()
    }
    data class QuantitySource(val quantity: QueryQuantity.Source) : Source {
        override fun bind(id: QueryStageAnalysis.SymbolId, symbols: Map<String, QueryStageAnalysis.SymbolId>, imports: Map<ValuePosition, ValuePosition>): QueryValue = Measure(quantity.bind(id, symbols).remap(imports))
    }
    data class Imported(val owner: QueryStageAnalysis.SymbolId, val value: QueryValue) : Source {
        override fun bind(id: QueryStageAnalysis.SymbolId, symbols: Map<String, QueryStageAnalysis.SymbolId>, imports: Map<ValuePosition, ValuePosition>): QueryValue = remap(value, imports)
    }
    class Plain(@param:JsonProperty(value = "source", required = true) val source: ValuePosition?, positions: List<ValuePosition>) : QueryValue {
        val positions: List<ValuePosition> = java.util.List.copyOf(positions)
        init { require(this.positions.distinct().size == this.positions.size) { "categorical value repeats a proved coordinate" } }
        override fun equals(other: Any?): Boolean = other is Plain && source == other.source && positions == other.positions
        override fun hashCode(): Int = listOf(source, positions).hashCode()
    }
    data class Measure(val quantity: QueryQuantity) : QueryValue
    class Row(fields: List<QueryValue>) : QueryValue {
        val fields: List<QueryValue> = java.util.List.copyOf(fields)
        override fun equals(other: Any?): Boolean = other is Row && fields == other.fields
        override fun hashCode(): Int = fields.hashCode()
    }
    data class Array(val element: QueryValue) : QueryValue

    companion object {
        fun plain(id: QueryStageAnalysis.SymbolId): Plain = ValuePosition.column(id).let { Plain(it, listOf(it)) }
        fun anonymous(): Plain = Plain(null, emptyList())
        private fun sourceShape(shape: SetOutputContract.Shape, position: ValuePosition, observations: List<ValuePosition>): QueryValue = when (shape) {
            is SetOutputContract.Array -> Array(sourceShape(shape.element, position.element(), observations))
            is SetOutputContract.Row -> Row(shape.fields.mapIndexed { index, field -> sourceShape(field, position.field(index), observations) })
            is SetOutputContract.Property -> Plain(position, listOf(position))
            is SetOutputContract.Quantity -> Measure(QueryQuantity(shape.valueContract, QueryQuantity.Fixed((shape.valueContract.unit as MetricValueContract.Fixed).identity), QueryQuantity.Raw(position, observations)))
            else -> throw IllegalArgumentException("source container contains unbound semantic meaning")
        }
        fun remap(value: QueryValue, symbols: Map<ValuePosition, ValuePosition>): QueryValue = when (value) {
            is Plain -> Plain(value.source, value.positions.map { symbols[it] ?: it }.distinct())
            is Measure -> Measure(value.quantity.remap(symbols))
            is Row -> Row(value.fields.map { remap(it, symbols) })
            is Array -> Array(remap(value.element, symbols))
        }
        fun scalarReferences(value: QueryValue, result: MutableSet<ValuePosition>) {
            when (value) { is Plain -> result.addAll(value.positions); is Row -> value.fields.forEach { scalarReferences(it, result) }; else -> Unit }
        }
        fun requireUnitsRetained(columns: Collection<QueryStageAnalysis.Symbol>) {
            val retained = hashSetOf<ValuePosition>()
            columns.forEach { retained.add(ValuePosition.column(it.id)); scalarReferences(it.value, retained) }
            columns.forEach { requireUnits(it.value, retained) }
        }
        private fun requireUnits(value: QueryValue, retained: Set<ValuePosition>) {
            when (value) {
                is Plain -> Unit
                is Measure -> {
                    val unit = value.quantity.unit
                    require(unit !is QueryQuantity.Dimension || unit.keys.any { it.position in retained }) { "quantity cannot hide its currency or measurement-unit key" }
                }
                is Row -> {
                    val local = retained.toMutableSet()
                    value.fields.forEach { scalarReferences(it, local) }
                    value.fields.forEach { requireUnits(it, local) }
                }
                is Array -> {
                    val local = retained.toMutableSet()
                    scalarReferences(value.element, local)
                    requireUnits(value.element, local)
                }
            }
        }
        fun coordinates(value: QueryValue, target: ValuePosition, result: MutableMap<ValuePosition, ValuePosition>) {
            when (value) {
                is Plain -> value.positions.forEach { result.putIfAbsent(it, target) }
                is Row -> value.fields.forEachIndexed { index, field -> coordinates(field, target.field(index), result) }
                is Array -> coordinates(value.element, target.element(), result)
                is Measure -> Unit
            }
        }
        fun relocate(value: QueryValue, target: ValuePosition, sameObservation: Boolean): QueryValue = relocate(value, target, sameObservation, emptyMap())
        private fun relocate(value: QueryValue, target: ValuePosition, sameObservation: Boolean, inherited: Map<ValuePosition, ValuePosition>): QueryValue {
            val mappings = LinkedHashMap(inherited)
            return when (value) {
                is Row -> {
                    val local = linkedMapOf<ValuePosition, ValuePosition>()
                    value.fields.forEachIndexed { index, field -> localCoordinates(field, target.field(index), local) }
                    mappings.putAll(local)
                    Row(value.fields.mapIndexed { index, field -> relocate(field, target.field(index), sameObservation, mappings) })
                }
                is Array -> Array(relocate(value.element, target.element(), sameObservation, mappings))
                is Measure -> Measure(value.quantity.remap(mappings))
                is Plain -> Plain(value.source, (listOf(target) + if (sameObservation) value.positions else emptyList()).distinct())
            }
        }
        private fun localCoordinates(value: QueryValue, target: ValuePosition, result: MutableMap<ValuePosition, ValuePosition>) {
            when (value) { is Plain -> value.positions.forEach { result.putIfAbsent(it, target) }; is Row -> value.fields.forEachIndexed { index, field -> localCoordinates(field, target.field(index), result) }; else -> Unit }
        }
        fun at(position: ValuePosition, symbols: Map<QueryStageAnalysis.SymbolId, QueryStageAnalysis.Symbol>): QueryValue {
            val owner = requireNotNull(symbols[position.owner]) { "value coordinate is outside the compiled source closure" }
            return position.path.fold(owner.value) { value, part ->
                when {
                    part == -1 && value is Array -> value.element
                    part >= 0 && value is Row && part < value.fields.size -> value.fields[part]
                    else -> throw IllegalArgumentException("value coordinate does not identify a declared field or element")
                }
            }
        }
        fun requirePositions(value: QueryValue, symbols: Map<QueryStageAnalysis.SymbolId, QueryStageAnalysis.Symbol>) {
            when (value) {
                is Plain -> {
                    value.source?.let { require(at(it, symbols) is Plain) { "categorical provenance must identify a categorical source field" } }
                    value.positions.forEach { require(at(it, symbols) is Plain) { "categorical coordinate must identify a categorical value field" } }
                }
                is Row -> value.fields.forEach { requirePositions(it, symbols) }
                is Array -> requirePositions(value.element, symbols)
                is Measure -> {
                    val provenance = linkedSetOf<ValuePosition>()
                    QueryQuantity.references(value.quantity.provenance, provenance)
                    provenance.forEach { position -> val actual = at(position, symbols); require(actual is Plain || actual is Measure) { "quantity provenance must identify actual scalar observations" } }
                    when (val unit = value.quantity.unit) {
                        is QueryQuantity.Dimension -> unit.keys.forEach { key ->
                            val plain = at(key.position, symbols) as? Plain
                            require(plain?.source != null) { "unit key must identify a categorical field with declared semantic provenance" }
                            requireUnitMeaning(key.reference, unit.axis, requireNotNull(plain.source), symbols)
                        }
                        is QueryQuantity.SelectedMember -> require(at(unit.selection.source, symbols) is Measure) { "unit selection witness must identify a source quantity field" }
                        else -> Unit
                    }
                }
            }
        }
        private fun requireUnitMeaning(reference: QueryQuantity.UnitReference, axis: MetricValueContract.UnitAxis, source: ValuePosition,
            symbols: Map<QueryStageAnalysis.SymbolId, QueryStageAnalysis.Symbol>) {
            val owner = requireNotNull(symbols[source.owner]) { "unit provenance is outside the compiled source closure" }
            val dimension = QueryStageAnalysis.dimension(owner.origin)
            val shape: SetOutputContract.Shape = when {
                owner.origin is QueryStageAnalysis.SetOrigin -> source.path.fold(owner.origin.shape) { shape, part ->
                    when { part == -1 && shape is SetOutputContract.Array -> shape.element; part >= 0 && shape is SetOutputContract.Row && part < shape.fields.size -> shape.fields[part]; else -> throw IllegalArgumentException("unit provenance has no declared common set field") }
                }
                source.path.isEmpty() && dimension != null -> SetOutputContract.Dimension(dimension)
                source.path.isEmpty() && owner.origin is QueryStageAnalysis.MemberOrigin && owner.origin.meaning is QueryStageAnalysis.MemberDimension -> {
                    val meaning = owner.origin.meaning
                    SetOutputContract.MemberDimension(owner.origin.slot, meaning.dimensionId, meaning.dimensionVersion.toLong(), meaning.role)
                }
                else -> throw IllegalArgumentException("unit key provenance is not a declared semantic dimension")
            }
            val matches = when (reference) {
                is QueryQuantity.AnalysisDimensionRef -> shape is SetOutputContract.Dimension && shape.dimension == reference.dimension
                is QueryQuantity.SemanticDimensionRef -> shape is SetOutputContract.MemberDimension && shape.dimensionId == reference.dimensionId && shape.dimensionVersion == reference.dimensionVersion && shape.role == reference.role
                is QueryQuantity.MemberSlotRef -> owner.origin is QueryStageAnalysis.MemberOrigin && source.path.isEmpty() && reference.sourceId == owner.origin.sourceId && reference.sourceVersion.toString() == owner.origin.sourceVersion && reference.slot == owner.origin.slot
            }
            require(matches) { "unit key has a different semantic source, dimension or role" }
            if (shape is SetOutputContract.MemberDimension) require(shape.dimensionId == axis.dimensionId && shape.dimensionVersion == axis.dimensionVersion && shape.role == axis.role) { "unit key has a different semantic axis" }
        }
    }
}
