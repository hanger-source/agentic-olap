package com.olap.prototype.query

import com.olap.prototype.calculation.Calculation
import com.olap.prototype.calculation.CalculationOwner
import com.olap.prototype.calculation.MetricRef
import com.olap.prototype.expression.NativeScalarFunction
import java.util.Collections

/** 公开计算的符号、来源与直接键事实，不推导SQL类型、基数或优化候选。 */
object QueryStageAnalysis {
    class ScopeId(path: List<String>) {
        val path: List<String> = java.util.List.copyOf(path)
        init { this.path.forEach(::text) }
        fun child(id: String): ScopeId = ScopeId(path + ScopedInputContract.requireId(id))
        fun metric(callId: String): ScopeId = ScopeId(path + listOf("\$metric", ScopedInputContract.requireId(callId)))
        fun member(callId: String): ScopeId = ScopeId(path + listOf("\$member", ScopedInputContract.requireId(callId)))
        fun level(id: String): ScopeId = ScopeId(path + listOf("\$level", ScopedInputContract.requireId(id)))
        fun externalPath(): String? = if (path.isEmpty()) null else path.joinToString(".")
        fun depth(): Int = path.size - path.count { it in setOf("\$metric", "\$level", "\$member") }
        override fun equals(other: Any?): Boolean = other is ScopeId && path == other.path
        override fun hashCode(): Int = path.hashCode()
        override fun toString(): String = "ScopeId(path=$path)"
        companion object { fun root(): ScopeId = ScopeId(emptyList()) }
    }
    data class SymbolId(val scope: ScopeId, val position: Int) { init { require(position >= 0) { "symbol position must be nonnegative" } } }

    sealed interface Origin { val scope: ScopeId }
    data class MetricOrigin(override val scope: ScopeId, val callId: String, val owner: CalculationOwner, val metric: MetricRef) : Origin { init { ScopedInputContract.requireId(callId) } }
    data class DimensionOrigin(override val scope: ScopeId, val dimension: ScopedInputContract.DimensionRef) : Origin

    sealed interface MemberMeaning { val role: String }
    data class MappedMemberDimension(val dimension: ScopedInputContract.DimensionRef, override val role: String) : MemberMeaning { init { text(role) } }
    data class MemberDimension(val dimensionId: String, val dimensionVersion: String, override val role: String) : MemberMeaning { init { text(dimensionId); version(dimensionVersion); text(role) } }
    data class MemberProperty(val valueType: ScalarValueType, override val role: String) : MemberMeaning { init { text(role) } }
    data class MemberContainer(val shape: SetOutputContract.Shape, override val role: String) : MemberMeaning { init { SetOutputContract.requireSourceContainer(shape); text(role) } }
    data class MemberOrigin(override val scope: ScopeId, val sourceId: String, val sourceVersion: String, val slot: String, val meaning: MemberMeaning) : Origin { init { text(sourceId); version(sourceVersion); text(slot) } }
    fun dimension(origin: Origin): ScopedInputContract.DimensionRef? = when (origin) {
        is DimensionOrigin -> origin.dimension
        is MemberOrigin -> (origin.meaning as? MappedMemberDimension)?.dimension
        is SetOrigin -> (origin.shape as? SetOutputContract.Dimension)?.dimension
        else -> null
    }
    data class OperationOrigin(override val scope: ScopeId, val operationIndex: Int, val outputIndex: Int, val operationType: String) : Origin {
        init { text(operationType); require(operationIndex >= 0 && outputIndex >= 0) { "operation position must be nonnegative" } }
    }
    data class InputPresenceOrigin(override val scope: ScopeId) : Origin
    data class GroupingOrigin(override val scope: ScopeId, val operationIndex: Int, val definition: GroupingLevels.Bound) : Origin { init { require(operationIndex >= -1) { "invalid grouping position" } } }
    data class OmittedDimensionOrigin(override val scope: ScopeId, val source: SymbolId, val grouping: SymbolId, val dimension: ScopedInputContract.DimensionRef, val levelId: String, val levelCode: Long) : Origin { init { text(levelId); require(levelCode >= 0) { "invalid omitted dimension level" } } }
    data class SetOrigin(override val scope: ScopeId, val operation: Calculation.SetKind, val all: Boolean, val outputIndex: Int, val shape: SetOutputContract.Shape) : Origin { init { require(outputIndex >= 0) { "negative set output index" } } }

    class SourceColumn(val name: String, val origin: Origin, dependencies: List<SymbolId>, val value: QueryValue.Source) {
        val dependencies: List<SymbolId> = java.util.List.copyOf(dependencies)
        init { text(name) }
        override fun equals(other: Any?): Boolean = other is SourceColumn && name == other.name && origin == other.origin && dependencies == other.dependencies && value == other.value
        override fun hashCode(): Int = listOf(name, origin, dependencies, value).hashCode()
        companion object {
            fun categorical(name: String, origin: Origin, dependencies: List<SymbolId> = emptyList()): SourceColumn = SourceColumn(name, origin, dependencies, QueryValue.Categorical())
            fun quantity(name: String, origin: Origin, dependencies: List<SymbolId>, quantity: QueryQuantity.Source): SourceColumn = SourceColumn(name, origin, dependencies, QueryValue.QuantitySource(quantity))
            fun imported(name: String, symbol: Symbol): SourceColumn = SourceColumn(name, symbol.origin, listOf(symbol.id), QueryValue.Imported(symbol.id, symbol.value))
        }
    }
    class Symbol(val id: SymbolId, val name: String, val origin: Origin, dependencies: List<SymbolId>, val value: QueryValue) {
        val dependencies: List<SymbolId> = java.util.List.copyOf(dependencies)
        init { text(name) }
        val quantity: QueryQuantity? get() = (value as? QueryValue.Measure)?.quantity
        override fun equals(other: Any?): Boolean = other is Symbol && id == other.id && name == other.name && origin == other.origin && dependencies == other.dependencies && value == other.value
        override fun hashCode(): Int = listOf(id, name, origin, dependencies, value).hashCode()
    }
    sealed interface Key
    enum class UnknownKey : Key { INSTANCE }
    class UniqueKey(columns: Set<SymbolId>) : Key {
        val columns: Set<SymbolId> = java.util.Set.copyOf(columns)
        override fun equals(other: Any?): Boolean = other is UniqueKey && columns == other.columns
        override fun hashCode(): Int = columns.hashCode()
    }
    sealed interface SourceKey
    enum class UnknownSourceKey : SourceKey { INSTANCE }
    class UniqueSourceKey(columns: List<String>) : SourceKey {
        val columns: List<String> = java.util.List.copyOf(columns)
        override fun equals(other: Any?): Boolean = other is UniqueSourceKey && columns == other.columns
        override fun hashCode(): Int = columns.hashCode()
    }
    class Stage(val index: Int, val operation: PostOperationContract.ValidatedOperation, inputs: List<Symbol>, imports: List<Symbol>, outputs: List<Symbol>, val key: Key) {
        val inputs: List<Symbol> = java.util.List.copyOf(inputs)
        val imports: List<Symbol> = java.util.List.copyOf(imports)
        val outputs: List<Symbol> = java.util.List.copyOf(outputs)
        override fun equals(other: Any?): Boolean = other is Stage && index == other.index && operation == other.operation && inputs == other.inputs && imports == other.imports && outputs == other.outputs && key == other.key
        override fun hashCode(): Int = listOf(index, operation, inputs, imports, outputs, key).hashCode()
    }
    data class Child(val mode: String, val analysis: Analysis) { init { ScopedInputContract.requireMode(mode) } }
    sealed interface Population
    class MetricPopulation(val selection: RowPopulationContract.Selection, rootInputs: Set<String>) : Population {
        val rootInputs: Set<String> = java.util.Set.copyOf(rootInputs)
        init { this.rootInputs.forEach { ScopedInputContract.requireId(it) } }
        override fun equals(other: Any?): Boolean = other is MetricPopulation && selection == other.selection && rootInputs == other.rootInputs
        override fun hashCode(): Int = listOf(selection, rootInputs).hashCode()
    }
    class IntrinsicPopulation(rootInputs: Set<String>) : Population {
        val rootInputs: Set<String> = java.util.Set.copyOf(rootInputs)
        init { this.rootInputs.forEach { ScopedInputContract.requireId(it) } }
        override fun equals(other: Any?): Boolean = other is IntrinsicPopulation && rootInputs == other.rootInputs
        override fun hashCode(): Int = rootInputs.hashCode()
    }
    class Analysis(val scope: ScopeId, inputs: List<Symbol>, stages: List<Stage>, outputs: List<Symbol>, val key: Key, selectedObservations: List<SelectedObservation>) {
        val inputs: List<Symbol> = java.util.List.copyOf(inputs)
        val stages: List<Stage> = java.util.List.copyOf(stages)
        val outputs: List<Symbol> = java.util.List.copyOf(outputs)
        val selectedObservations: List<SelectedObservation> = java.util.List.copyOf(selectedObservations)
        fun outputNames(): List<String> = outputs.map { it.name }
        fun alignmentGrain(): Map<String, ScopedInputContract.DimensionRef> {
            val unique = key as? UniqueKey ?: throw IllegalArgumentException("input has no proven final key; bag relations require explicit regrouping before alignment")
            val result = linkedMapOf<String, ScopedInputContract.DimensionRef>()
            outputs.filter { it.id in unique.columns }.forEach { symbol -> result[symbol.name] = requireNotNull(dimension(symbol.origin)) { "input final key must contain retained exact semantic dimensions" } }
            check(result.size == unique.columns.size) { "final key contains unavailable symbols" }
            return Collections.unmodifiableMap(result)
        }
        override fun equals(other: Any?): Boolean = other is Analysis && scope == other.scope && inputs == other.inputs && stages == other.stages && outputs == other.outputs && key == other.key && selectedObservations == other.selectedObservations
        override fun hashCode(): Int = listOf(scope, inputs, stages, outputs, key, selectedObservations).hashCode()
    }
    class SelectedObservation(timeAxes: List<ValuePosition>, val asOf: Calculation.Scalar?) {
        val timeAxes: List<ValuePosition> = java.util.List.copyOf(timeAxes)
        override fun equals(other: Any?): Boolean = other is SelectedObservation && timeAxes == other.timeAxes && asOf == other.asOf
        override fun hashCode(): Int = listOf(timeAxes, asOf).hashCode()
    }
    fun symbols(analyses: Collection<Analysis>): Map<SymbolId, Symbol> {
        val result = linkedMapOf<SymbolId, Symbol>()
        fun collect(symbol: Symbol) { val prior = result.putIfAbsent(symbol.id, symbol); check(prior == null || prior == symbol) { "one source symbol identity has conflicting meanings" } }
        analyses.forEach { analysis ->
            analysis.inputs.forEach(::collect); analysis.outputs.forEach(::collect)
            analysis.stages.forEach { stage -> stage.inputs.forEach(::collect); stage.imports.forEach(::collect); stage.outputs.forEach(::collect) }
        }
        return Collections.unmodifiableMap(result)
    }
    /** 仅绑定后的观察统计根授予其确切统计阶段；不属于作者输入或跨进程协议。 */
    class ObservationReduction(val stageIndex: Int, keys: List<String>) {
        val keys: List<String> = java.util.List.copyOf(keys)
        init { require(stageIndex >= 0 && this.keys.isNotEmpty()) { "object observation reduction requires its actual stage and complete identity" } }
    }

    fun analyze(scope: ScopeId, sourceColumns: List<SourceColumn>, sourceKey: SourceKey, children: Map<String, Child>, operations: List<PostOperationContract.ValidatedOperation>, population: Population,
        field: String, sourceObservations: List<SelectedObservation> = emptyList(),
        nativeFunctions: Map<ResultExpression.ResultNativeCall, NativeScalarFunction> = emptyMap(), observationReduction: ObservationReduction? = null): Analysis =
        QueryScopeAnalyzer(scope, children, population, field, nativeFunctions, observationReduction).analyze(sourceColumns, sourceKey, operations, sourceObservations)
    private fun text(value: String) { require(value.isNotBlank()) { "identity must be explicit" } }
    private fun version(value: String) { require(value.matches(Regex("[1-9][0-9]*"))) { "asset version must be exact" } }
}
