package com.olap.prototype.query

import com.olap.prototype.calculation.Calculation
import com.olap.prototype.calculation.CalculationBindings
import com.olap.prototype.calculation.MetricDefinition
import com.olap.prototype.contract.InputContractException
import com.olap.prototype.metric.MetricValueContract

/** 集合按共同语义槽位合并；重复规则属于SetOp，技术类型合并属于Calcite。 */
data class SetOutputContract(val name: String, val shape: Shape) {
    init { PostOperationContract.requireAlias(name) }
    sealed interface Shape
    /** 来源容器的顶层形态；数量和属性仅能作为递归叶子。 */
    sealed interface Container : Shape {
        companion object {
            fun contract(): Map<String, Any> = mapOf("sourceContainerRules" to mapOf(
                "topLevel" to listOf("ARRAY", "ROW"),
                "nestedContainers" to listOf("ARRAY", "ROW"),
                "leaves" to mapOf("PROPERTY" to "DECLARED_SCALAR_PROPERTY", "QUANTITY" to "FIXED_UNIT_REQUIRED"),
                "forbiddenLeaves" to listOf("DIMENSION", "DIMENSION_SLOT", "MEMBER_DIMENSION", "SCALAR"),
                "violationCodes" to mapOf("topLevel" to InputContractException.Rule.SOURCE_CONTAINER_KIND.code,
                    "leaf" to InputContractException.Rule.SOURCE_CONTAINER_LEAF.code,
                    "quantityUnit" to InputContractException.Rule.SOURCE_CONTAINER_QUANTITY_UNIT.code)))
        }
    }
    data class Dimension(val dimension: ScopedInputContract.DimensionRef) : Shape
    data class DimensionSlot(val slot: String) : Shape { init { ScopedInputContract.requireId(slot) } }
    data class MemberDimension(val slot: String, val dimensionId: String, val dimensionVersion: Long, val role: String) : Shape {
        init { ScopedInputContract.requireId(slot); MetricDefinition.text(dimensionId, "set semantic dimension"); MetricDefinition.version(dimensionVersion); MetricDefinition.text(role, "set member role") }
    }
    data class Property(val role: String, val valueType: ScalarValueType) : Shape { init { MetricDefinition.text(role, "set property role") } }
    /** 已完成的非数量标量，不声明成员角色、身份或数量保证。 */
    data class Scalar(val valueType: ScalarType) : Shape
    enum class ScalarType { TEXT, ENUM, BOOLEAN, DATE, LOCAL_TIMESTAMP, OFFSET_TIMESTAMP }
    data class Quantity(val valueContract: MetricValueContract) : Shape {
        init { val result = valueContract.result; require(result !is MetricValueContract.Decimal || result.rounding == MetricValueContract.Rounding.NONE) { "set quantities require lossless common output capacity; round each branch explicitly before the set" } }
    }
    class Row(fields: List<Shape>) : Container {
        val fields: List<Shape> = java.util.List.copyOf(fields)
        init { require(this.fields.isNotEmpty()) { "set row must declare its ordinal fields" } }
        override fun equals(other: Any?): Boolean = other is Row && fields == other.fields
        override fun hashCode(): Int = fields.hashCode()
    }
    data class Array(val element: Shape) : Container
    fun adopted(bindings: CalculationBindings): SetOutputContract = SetOutputContract(name, adopted(shape, bindings))

    class Branch(val value: QueryValue, outputs: Map<QueryStageAnalysis.SymbolId, String>, meanings: Map<QueryStageAnalysis.SymbolId, QueryStageAnalysis.Symbol>, projected: Map<String, QueryValue>) {
        val outputs: Map<QueryStageAnalysis.SymbolId, String> = java.util.Map.copyOf(outputs)
        val meanings: Map<QueryStageAnalysis.SymbolId, QueryStageAnalysis.Symbol> = java.util.Map.copyOf(meanings)
        val projected: Map<String, QueryValue> = java.util.Map.copyOf(projected)
        override fun equals(other: Any?): Boolean = other is Branch && value == other.value && outputs == other.outputs && meanings == other.meanings && projected == other.projected
        override fun hashCode(): Int = listOf(value, outputs, meanings, projected).hashCode()
    }
    class Source(val shape: Shape, branches: List<Branch>, schema: Map<String, Shape>, val all: Boolean) : QueryValue.Source {
        val branches: List<Branch> = java.util.List.copyOf(branches)
        val schema: Map<String, Shape> = java.util.Map.copyOf(schema)
        init { require(this.branches.size >= 2) { "set source requires at least two branches" } }
        override fun bind(id: QueryStageAnalysis.SymbolId, symbols: Map<String, QueryStageAnalysis.SymbolId>, imports: Map<ValuePosition, ValuePosition>): QueryValue {
            val values = branches.map { BranchValues(it, symbols, schema).bind(shape, id) }
            values.forEach { QueryValueSemantics.requireSameMeaning(values.first(), it) }
            val merged = mergeQuantities(values)
            return if (all) QueryValueSemantics.copied(merged) else merged
        }
        override fun equals(other: Any?): Boolean = other is Source && shape == other.shape && branches == other.branches && schema == other.schema && all == other.all
        override fun hashCode(): Int = listOf(shape, branches, schema, all).hashCode()
    }

    /** 每个真实分支只拥有一次坐标重定位与共同单位证明，不复用其它分支临时状态。 */
    private class BranchValues(private val branch: Branch, private val symbols: Map<String, QueryStageAnalysis.SymbolId>, private val schema: Map<String, Shape>) {
        fun bind(shape: Shape, id: QueryStageAnalysis.SymbolId): QueryValue {
            requireNestedMeaning(shape, branch.value, branch.meanings)
            val ids = linkedMapOf<ValuePosition, ValuePosition>()
            branch.outputs.forEach { (source, name) -> ids[ValuePosition.column(source)] = ValuePosition.column(requireNotNull(symbols[name]) { "set unit key output is missing" }) }
            branch.projected.forEach { (name, value) -> QueryValue.coordinates(value, ValuePosition.column(requireNotNull(symbols[name])), ids) }
            val target = ValuePosition.column(id)
            val placed = QueryValue.relocate(QueryValue.remap(branch.value, ids), target, false)
            return SetOutputContract.bind(shape, normalize(placed, shape, target))
        }
        private fun shapeAt(position: ValuePosition): Shape {
            val name = symbols.entries.firstOrNull { it.value == position.owner }?.key ?: throw IllegalArgumentException("set unit key is outside its common output positions")
            return SetOutputContract.shapeAt(requireNotNull(schema[name]), position.path)
        }
        private fun normalize(value: QueryValue, shape: Shape, position: ValuePosition): QueryValue {
            if (shape is Row && value is QueryValue.Row) return QueryValue.Row(shape.fields.mapIndexed { index, field -> normalize(value.fields[index], field, position.field(index)) })
            if (shape is Array && value is QueryValue.Array) return QueryValue.Array(normalize(value.element, shape.element, position.element()))
            if (shape !is Quantity || value !is QueryValue.Measure) return value
            val actual = value.quantity
            val declared = shape.valueContract
            val unit = declared.unit
            if (unit is MetricValueContract.Fixed) {
                require(QueryQuantity.fixedIdentity(actual.unit) == unit.identity) { "set branch has a different fixed output unit" }
                return QueryValue.Measure(QueryQuantity(actual.declaration, QueryQuantity.Fixed(unit.identity), actual.provenance))
            }
            val axis = when (unit) { is MetricValueContract.AnalysisDimension -> unit.axis; is MetricValueContract.SlotDimension -> unit.axis; else -> throw IllegalArgumentException("set output units must use fixed, adopted or local common-slot identities") }
            val keys = mutableListOf<QueryQuantity.Key>()
            when (val actualUnit = actual.unit) {
                is QueryQuantity.Dimension -> {
                    require(MetricValueContract.compatibleAxes(axis, actualUnit.axis) && actualUnit.axis.members.all { member -> axis.members.any { it.member.sameValue(member.member) && it.unit == member.unit } }) {
                        "common set unit axis must contain every branch member mapping without changing its unit identity"
                    }
                    actualUnit.keys.forEach { key ->
                        if (key.position.owner in symbols.values) unitReference(shapeAt(key.position), unit, axis)?.let { keys.add(QueryQuantity.Key(it, key.position)) }
                    }
                }
                is QueryQuantity.SelectedMember -> {
                    require(MetricValueContract.compatibleAxes(axis, actualUnit.axis) && axis.members.any { it.member.sameValue(actualUnit.member) && it.unit == actualUnit.identity }) { "selected set unit member differs from its common axis" }
                    branch.projected.forEach { (name, projected) -> selectedKeys(projected, ValuePosition.column(requireNotNull(symbols[name])), actualUnit, unit, axis, position, keys) }
                }
                is QueryQuantity.Fixed -> kotlin.Unit
            }
            require(keys.isNotEmpty()) { "dynamic set quantities require a proved retained common unit position" }
            return QueryValue.Measure(QueryQuantity(actual.declaration, QueryQuantity.Dimension(axis, keys.distinct()), actual.provenance))
        }
        private fun selectedKeys(value: QueryValue, target: ValuePosition, selected: QueryQuantity.SelectedMember, declared: MetricValueContract.Unit,
            axis: MetricValueContract.UnitAxis, quantity: ValuePosition, result: MutableList<QueryQuantity.Key>) {
            when (value) {
                is QueryValue.Row -> value.fields.forEachIndexed { index, field -> selectedKeys(field, target.field(index), selected, declared, axis, quantity, result) }
                is QueryValue.Array -> selectedKeys(value.element, target.element(), selected, declared, axis, quantity, result)
                is QueryValue.Plain -> {
                    val source = value.source
                    if (source == null || !sameElement(quantity, target)) return
                    val owner = branch.meanings[source.owner] ?: return
                    if (owner.id.scope != selected.selection.source.owner.scope) return
                    val matches = when (val reference = selected.reference) {
                        is QueryQuantity.MemberSlotRef -> owner.origin is QueryStageAnalysis.MemberOrigin && reference.sourceId == owner.origin.sourceId && reference.sourceVersion.toString() == owner.origin.sourceVersion && reference.slot == owner.origin.slot
                        is QueryQuantity.AnalysisDimensionRef -> QueryStageAnalysis.dimension(owner.origin) == reference.dimension
                        is QueryQuantity.SemanticDimensionRef -> false
                    }
                    if (matches) unitReference(shapeAt(target), declared, axis)?.let { result.add(QueryQuantity.Key(it, target)) }
                }
                is QueryValue.Measure -> kotlin.Unit
            }
        }
    }

    companion object {
        fun requireSourceContainer(shape: Shape, path: List<Any> = emptyList()): Container {
            if (shape !is Container) throw InputContractException.violation(InputContractException.Rule.SOURCE_CONTAINER_KIND,
                path, "source container requires an ARRAY or ROW shape", "ARRAY_OR_ROW", sourceShapeKind(shape))
            requireSourceFields(shape, path)
            return shape
        }
        private fun requireSourceFields(shape: Shape, path: List<Any>) {
            when (shape) {
                is Array -> requireSourceFields(shape.element, path + "element")
                is Row -> shape.fields.forEachIndexed { index, field -> requireSourceFields(field, path + listOf("fields", index)) }
                is Property -> Unit
                is Quantity -> if (shape.valueContract.unit !is MetricValueContract.Fixed) throw InputContractException.violation(
                    InputContractException.Rule.SOURCE_CONTAINER_QUANTITY_UNIT, path + listOf("valueContract", "unit"),
                    "source container quantities require an explicit fixed unit", "FIXED_UNIT", when (shape.valueContract.unit) {
                        is MetricValueContract.EventDimension -> "EVENT_DIMENSION_UNIT"
                        is MetricValueContract.SlotDimension -> "SLOT_DIMENSION_UNIT"
                        is MetricValueContract.AnalysisDimension -> "ANALYSIS_DIMENSION_UNIT"
                        is MetricValueContract.Fixed -> "FIXED_UNIT"
                    })
                else -> throw InputContractException.violation(InputContractException.Rule.SOURCE_CONTAINER_LEAF,
                    path, "source container leaves require a property or a quantity with a fixed unit",
                    "PROPERTY_OR_FIXED_QUANTITY_OR_CONTAINER", sourceShapeKind(shape))
            }
        }
        private fun sourceShapeKind(shape: Shape): String = when (shape) {
            is Array -> "ARRAY"
            is Row -> "ROW"
            is Property -> "PROPERTY"
            is Quantity -> "QUANTITY"
            is Dimension -> "DIMENSION"
            is DimensionSlot -> "DIMENSION_SLOT"
            is MemberDimension -> "MEMBER_DIMENSION"
            is Scalar -> "SCALAR"
        }
        private fun adopted(shape: Shape, bindings: CalculationBindings): Shape = when (shape) {
            is DimensionSlot -> Dimension(requireNotNull(bindings.dimensions[shape.slot]) { "set dimension slot is not adopted" }.reference)
            is Quantity -> Quantity(shape.valueContract.adopted(bindings))
            is Row -> Row(shape.fields.map { adopted(it, bindings) })
            is Array -> Array(adopted(shape.element, bindings))
            else -> shape
        }
        fun requireMeaning(shape: Shape, symbol: QueryStageAnalysis.Symbol) {
            val origin = symbol.origin
            when (shape) {
                is Dimension -> require(QueryStageAnalysis.dimension(origin) == shape.dimension) { "set branches must retain the exact declared dimension/version/level" }
                is MemberDimension -> {
                    val meaning = when (origin) {
                        is QueryStageAnalysis.MemberOrigin -> origin.meaning
                        is QueryStageAnalysis.SetOrigin -> (origin.shape as? MemberDimension)?.let { QueryStageAnalysis.MemberDimension(it.dimensionId, it.dimensionVersion.toString(), it.role) }
                        else -> null
                    }
                    require(meaning is QueryStageAnalysis.MemberDimension && meaning.dimensionId == shape.dimensionId && meaning.dimensionVersion == shape.dimensionVersion.toString() && meaning.role == shape.role) { "set member dimensions must have the exact declared identity and business role" }
                }
                is Property -> when (origin) {
                    is QueryStageAnalysis.MemberOrigin -> require(origin.meaning is QueryStageAnalysis.MemberProperty && origin.meaning.role == shape.role && origin.meaning.valueType == shape.valueType) { "set property changes its source business role or scalar type" }
                    is QueryStageAnalysis.SetOrigin -> if (origin.shape is Property) require(origin.shape.role == shape.role && origin.shape.valueType == shape.valueType) { "set property changes the preceding set slot's declared role or scalar type" }
                    else -> Unit
                }
                is DimensionSlot -> throw IllegalArgumentException("set dimension slot must be adopted before value binding")
                else -> Unit
            }
        }
        fun requireValueMeaning(shape: Shape, symbol: QueryStageAnalysis.Symbol) { requireNestedMeaning(shape, symbol.value, mapOf(symbol.id to symbol)); bind(shape, symbol.value) }
        /** 完成关系只核已有含义；不为普通列声明业务角色，也不重绑数量来源。 */
        fun requireCompletedMeaning(shape: Shape, symbol: QueryStageAnalysis.Symbol, symbols: Map<QueryStageAnalysis.SymbolId, QueryStageAnalysis.Symbol>) {
            requireMeaning(shape, symbol)
            requireNestedMeaning(shape, symbol.value, symbols, true)
            requireCompletedValue(shape, symbol.value)
        }
        private fun requireCompletedValue(shape: Shape, actual: QueryValue) {
            when {
                shape is Quantity -> {
                    val value = QueryValueSemantics.measure(actual)
                    QueryValueSemantics.requireDeclaration(shape.valueContract, value)
                    MetricValueContract.requireNumericValue(shape.valueContract, value.declaration)
                }
                shape is Row && actual is QueryValue.Row && shape.fields.size == actual.fields.size ->
                    shape.fields.zip(actual.fields).forEach { (field, value) -> requireCompletedValue(field, value) }
                shape is Array && actual is QueryValue.Array -> requireCompletedValue(shape.element, actual.element)
                shape is Scalar && actual is QueryValue.Plain -> Unit
                (shape is Dimension || shape is MemberDimension || shape is Property) && actual is QueryValue.Plain -> Unit
                else -> throw IllegalArgumentException("completed relation changes categorical, quantity or collection meaning")
            }
        }
        fun bind(shape: Shape, actual: QueryValue): QueryValue = when {
            shape is Quantity -> {
                val value = QueryValueSemantics.measure(actual)
                QueryValueSemantics.requireDeclaration(shape.valueContract, value)
                MetricValueContract.requireNumericValue(shape.valueContract, value.declaration)
                QueryValue.Measure(QueryQuantity(shape.valueContract, value.unit,
                    QueryQuantity.Declared(value.provenance, value.declaration, shape.valueContract)))
            }
            shape is Row && actual is QueryValue.Row && shape.fields.size == actual.fields.size -> QueryValue.Row(shape.fields.zip(actual.fields).map { (field, value) -> bind(field, value) })
            shape is Array && actual is QueryValue.Array -> QueryValue.Array(bind(shape.element, actual.element))
            shape is Scalar && actual is QueryValue.Plain -> actual
            (shape is Dimension || shape is MemberDimension || shape is Property) && actual is QueryValue.Plain -> actual
            else -> throw IllegalArgumentException("set position changes categorical, quantity or collection meaning")
        }
        private fun mergeQuantities(values: List<QueryValue>): QueryValue = when (val value = values.first()) {
            is QueryValue.Plain -> QueryValue.Plain(value.positions.firstOrNull(), value.positions)
            is QueryValue.Measure -> QueryValue.Measure(QueryQuantity(value.quantity.declaration, value.quantity.unit, QueryQuantity.alternatives(values.map { (it as QueryValue.Measure).quantity.provenance })))
            is QueryValue.Row -> QueryValue.Row(value.fields.indices.map { index -> mergeQuantities(values.map { (it as QueryValue.Row).fields[index] }) })
            is QueryValue.Array -> QueryValue.Array(mergeQuantities(values.map { (it as QueryValue.Array).element }))
        }
        private fun shapeAt(initial: Shape, path: List<Int>): Shape = path.fold(initial) { shape, part ->
            when { part == -1 && shape is Array -> shape.element; part >= 0 && shape is Row && part < shape.fields.size -> shape.fields[part]; else -> throw IllegalArgumentException("set unit key does not identify a scalar common field") }
        }
        private fun unitReference(shape: Shape, declared: MetricValueContract.Unit, axis: MetricValueContract.UnitAxis): QueryQuantity.UnitReference? = when {
            shape is Dimension && declared is MetricValueContract.AnalysisDimension && shape.dimension == declared.dimension -> QueryQuantity.AnalysisDimensionRef(shape.dimension)
            shape is MemberDimension && declared is MetricValueContract.SlotDimension && shape.slot == declared.slot && shape.dimensionId == axis.dimensionId && shape.dimensionVersion == axis.dimensionVersion && shape.role == axis.role -> QueryQuantity.SemanticDimensionRef(shape.dimensionId, shape.dimensionVersion, shape.role)
            else -> null
        }
        private fun sameElement(quantity: ValuePosition, key: ValuePosition): Boolean {
            val element = key.path.lastIndexOf(-1)
            return element < 0 || quantity.owner == key.owner && quantity.path.size > element && quantity.path.subList(0, element + 1) == key.path.subList(0, element + 1)
        }
        private fun requireNestedMeaning(shape: Shape, value: QueryValue, meanings: Map<QueryStageAnalysis.SymbolId, QueryStageAnalysis.Symbol>, requireDeclaredProperty: Boolean = false) {
            when {
                shape is Scalar && value is QueryValue.Plain -> {
                    val source = value.source
                    if (source != null) {
                        val owner = meanings[source.owner]
                        val declared = (owner?.origin as? QueryStageAnalysis.SetOrigin)?.shape
                        require(declared != null && shapeAt(declared, source.path) == shape) {
                            "completed scalar cannot erase a dimension identity or declared member property role"
                        }
                    }
                }
                value is QueryValue.Plain && (shape is Dimension || shape is MemberDimension || shape is Property) -> {
                    val source = value.source
                    val owner = source?.let { meanings[it.owner] }
                    val origin = owner?.origin
                    val declared = when (origin) { is QueryStageAnalysis.SetOrigin -> origin.shape; is QueryStageAnalysis.MemberOrigin -> (origin.meaning as? QueryStageAnalysis.MemberContainer)?.shape; else -> null }
                    if (declared != null) {
                        val actual = shapeAt(declared, requireNotNull(source).path)
                        when (shape) {
                            is Dimension -> require(actual is Dimension && shape.dimension == actual.dimension) { "nested set dimension changes its exact identity" }
                            is MemberDimension -> require(actual is MemberDimension && shape.dimensionId == actual.dimensionId && shape.dimensionVersion == actual.dimensionVersion && shape.role == actual.role) { "nested set member dimension changes identity or role" }
                            is Property -> require(actual is Property && shape.role == actual.role && shape.valueType == actual.valueType) { "nested set property changes role or scalar type" }
                            else -> Unit
                        }
                    } else if (owner != null) {
                        require(requireNotNull(source).path.isEmpty()) { "nested value provenance is not a declared semantic field" }
                        if (requireDeclaredProperty && shape is Property) require(origin is QueryStageAnalysis.MemberOrigin && origin.meaning is QueryStageAnalysis.MemberProperty) {
                            "completed relation property requires an actual declared member or set property role"
                        }
                        requireMeaning(shape, owner)
                    } else require(shape is Property && !requireDeclaredProperty) { "completed categorical value must retain its exact declared source meaning" }
                }
                shape is Row && value is QueryValue.Row && shape.fields.size == value.fields.size -> shape.fields.zip(value.fields).forEach { (field, actual) -> requireNestedMeaning(field, actual, meanings, requireDeclaredProperty) }
                shape is Array && value is QueryValue.Array -> requireNestedMeaning(shape.element, value.element, meanings, requireDeclaredProperty)
            }
        }
        fun columns(scope: QueryStageAnalysis.ScopeId, operation: Calculation.SetKind, all: Boolean, projections: List<Calculation.SetProjection>, outputs: List<SetOutputContract>,
            children: Map<String, QueryStageAnalysis.Child>, sourceSymbols: Map<QueryStageAnalysis.SymbolId, QueryStageAnalysis.Symbol>): List<QueryStageAnalysis.SourceColumn> {
            val projected = mutableListOf<List<QueryStageAnalysis.Symbol>>()
            val references = mutableListOf<Map<QueryStageAnalysis.SymbolId, String>>()
            val meanings = mutableListOf<Map<QueryStageAnalysis.SymbolId, QueryStageAnalysis.Symbol>>()
            projections.forEach { projection ->
                val child = requireNotNull(children[projection.inputId]) { "set input is not declared" }
                require(child.mode == "ROOT" && projection.symbols.size == outputs.size) { "set branches must consume ROOT inputs with their explicit common width" }
                val ids = linkedMapOf<QueryStageAnalysis.SymbolId, String>()
                val columns = projection.symbols.mapIndexed { position, name ->
                    val symbol = requireNotNull(child.analysis.outputs.firstOrNull { it.name == name }) { "set projection reads an unavailable completed output" }
                    ids[symbol.id] = outputs[position].name
                    (symbol.value as? QueryValue.Plain)?.positions?.filter { it.path.isEmpty() }?.forEach { ids[it.owner] = outputs[position].name }
                    symbol
                }
                projected.add(columns); references.add(ids)
                val sourceMeanings = LinkedHashMap(sourceSymbols)
                child.analysis.inputs.forEach { sourceMeanings[it.id] = it }
                child.analysis.stages.forEach { stage -> (stage.inputs + stage.imports + stage.outputs).forEach { sourceMeanings[it.id] = it } }
                child.analysis.outputs.forEach { sourceMeanings[it.id] = it }
                meanings.add(sourceMeanings)
            }
            val schema = outputs.associate { it.name to it.shape }
            return outputs.mapIndexed { position, output ->
                val dependencies = projected.map { it[position].id }
                val branches = projected.indices.map { branch ->
                    Branch(projected[branch][position].value, references[branch], meanings[branch], outputs.indices.associate { outputs[it].name to projected[branch][it].value })
                }
                QueryStageAnalysis.SourceColumn(output.name, QueryStageAnalysis.SetOrigin(scope, operation, all, position, output.shape), dependencies, Source(output.shape, branches, schema, all))
            }
        }
    }
}
