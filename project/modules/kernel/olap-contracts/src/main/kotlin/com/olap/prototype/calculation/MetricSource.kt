package com.olap.prototype.calculation

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.olap.prototype.event.EventRef
import com.olap.prototype.member.*
import com.olap.prototype.query.ScopedInputContract
import java.util.Collections
import java.util.TreeMap

/** 采用后来源的实际出现位置；事实调用与成员根不被定义去重抹平。 */
class MetricSource(callPath: List<String>, val origin: Origin, val event: EventRef) {
    val callPath: List<String> = java.util.List.copyOf(callPath)
    init { this.callPath.forEach { MetricDefinition.text(it, "source position") } }
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
    @JsonSubTypes(JsonSubTypes.Type(value = Fact::class, name = "FACT"), JsonSubTypes.Type(value = Member::class, name = "MEMBER"))
    sealed interface Origin
    data class Fact(val metric: MetricRef) : Origin
    class Member(val sourceId: String, val sourceVersion: Long, val role: String, slots: List<String>) : Origin {
        val slots: List<String> = java.util.List.copyOf(slots)
        init {
            MetricDefinition.text(sourceId, "source member"); MetricDefinition.version(sourceVersion); MetricDefinition.text(role, "source member role")
            require(this.slots.isNotEmpty() && this.slots.distinct().size == this.slots.size) { "member source slots must be explicit and distinct" }
        }
        override fun equals(other: Any?): Boolean = other is Member && sourceId == other.sourceId && sourceVersion == other.sourceVersion && role == other.role && slots == other.slots
        override fun hashCode(): Int = listOf(sourceId, sourceVersion, role, slots).hashCode()
    }
    sealed interface Dependency
    data class MetricDependency(val reference: MetricRef) : Dependency
    data class InstanceDependency(val reference: MetricInstanceRef) : Dependency
    data class MemberDependency(val reference: MemberRef) : Dependency
    class Closure(dependencies: List<Dependency>, sources: List<MetricSource>) {
        val dependencies: List<Dependency> = java.util.List.copyOf(dependencies)
        val sources: List<MetricSource> = java.util.List.copyOf(sources)
        override fun equals(other: Any?): Boolean = other is Closure && dependencies == other.dependencies && sources == other.sources
        override fun hashCode(): Int = listOf(dependencies, sources).hashCode()
    }
    override fun equals(other: Any?): Boolean = other is MetricSource && callPath == other.callPath && origin == other.origin && event == other.event
    override fun hashCode(): Int = listOf(callPath, origin, event).hashCode()

    companion object {
        fun closure(metricRoots: Collection<MetricInstanceRef>, memberRoots: Collection<MemberRef>, metrics: (MetricRef) -> MetricDefinition,
            instances: (MetricInstanceRef) -> MetricInstance, members: (MemberRef) -> MemberSource,
            onDependency: ((Dependency, List<String>) -> Unit)? = null): Closure = Traversal(metrics, instances, members, false, onDependency).run {
            metricRoots.forEach { instance(it, emptyList(), false) }
            memberRoots.forEach { member(it, emptyList(), null, null, false) }
            result()
        }
        fun definitions(roots: Collection<MetricRef>, metrics: (MetricRef) -> MetricDefinition, instances: (MetricInstanceRef) -> MetricInstance, members: (MemberRef) -> MemberSource): Closure =
            Traversal(metrics, instances, members, true).run {
                roots.forEach { metric(it, emptyMap(), emptyMap(), emptyList(), linkedSetOf(), false) }
                result()
            }
        fun resolve(metric: MetricRef, bindings: CalculationBindings, metrics: (MetricRef) -> MetricDefinition, instances: (MetricInstanceRef) -> MetricInstance,
            members: (String, Long) -> MemberSource): List<MetricSource> = Traversal(metrics, instances, { members(it.id, it.version) }, false).run {
            metric(metric, bindings.members, bindings.measurements, emptyList(), linkedSetOf(), true)
            result().sources
        }
        fun resolve(source: MemberRef, metrics: (MetricRef) -> MetricDefinition, instances: (MetricInstanceRef) -> MetricInstance, members: (MemberRef) -> MemberSource): List<MetricSource> =
            Traversal(metrics, instances, members, false).run { member(source, emptyList(), null, null, true); result().sources }
        fun closure(source: MemberRef, supplied: Map<String, CalculationBindings.Member>, relations: Map<String, CalculationBindings.Relation>, metrics: (MetricRef) -> MetricDefinition,
            instances: (MetricInstanceRef) -> MetricInstance, members: (MemberRef) -> MemberSource): Closure = Traversal(metrics, instances, members, false).run {
            member(source, emptyList(), CalculationBindings.SourceCall(source, emptyMap(), emptyMap(), supplied, relations), null, true)
            result()
        }
    }

    /** 同一遍历维护混合依赖、活动栈与已完成高度；capture保留逐位置来源与实际计费。 */
    private class Traversal(private val metrics: (MetricRef) -> MetricDefinition, private val instances: (MetricInstanceRef) -> MetricInstance,
        private val members: (MemberRef) -> MemberSource, private val declarationGraph: Boolean,
        private val onDependency: ((Dependency, List<String>) -> Unit)? = null) {
        private val dependencies = linkedSetOf<Dependency>()
        private val sources = mutableListOf<MetricSource>()
        private val active = linkedSetOf<Any>()
        private val completedDepths = hashMapOf<Any, Int>()
        private val activeDepths = mutableListOf<Int>()
        private val trail = mutableListOf<String>()
        private var expanded = 0
        fun result(): Closure = Closure(dependencies.toList(), sources)
        private fun enter(identity: Any, label: String, capture: Boolean): Boolean {
            require(identity !in active) { "calculation dependency cycle: ${trail.joinToString(" -> ")} -> $label" }
            if (!capture) completedDepths[identity]?.let { height ->
                require(trail.size + height <= ScopedInputContract.MAX_DEPTH) { "calculation dependency closure exceeds its depth budget: $trail" }
                extendParentDepth(height)
                return false
            }
            require(trail.size < ScopedInputContract.MAX_DEPTH && (!capture || ++expanded <= ScopedInputContract.MAX_INPUTS)) { "calculation dependency closure exceeds its input budget: $trail" }
            active.add(identity); trail.add(label); activeDepths.add(1)
            return true
        }
        private fun exit(identity: Any) {
            val height = activeDepths.removeLast()
            completedDepths[identity] = maxOf(completedDepths[identity] ?: 0, height)
            trail.removeLast(); active.remove(identity); extendParentDepth(height)
        }
        private fun extendParentDepth(childDepth: Int) {
            if (activeDepths.isNotEmpty()) activeDepths[activeDepths.lastIndex] = maxOf(activeDepths.last(), childDepth + 1)
        }
        private data class MemberPosition(val reference: MemberRef, val members: Map<String, CalculationBindings.Member>)
        private data class InstancePosition(val reference: MetricInstanceRef, val members: Map<String, CalculationBindings.Member>)
        private data class MetricPosition(val reference: MetricRef, val members: Map<String, CalculationBindings.Member>, val measurements: Map<String, MetricInstanceRef>)

        fun instance(reference: MetricInstanceRef, path: List<String>, capture: Boolean, supplied: Map<String, CalculationBindings.Member>? = null) {
            onDependency?.invoke(InstanceDependency(reference), trail + "instance $reference")
            val instance = instances(reference)
            val bindings = if (supplied == null) instance.bindings else when (val definition = metrics(instance.definition)) {
                is MetricCalculation -> CalculationInstantiation.memberBindings(definition.calculation, instance.bindings, supplied)
                is MetricDefinition.FactMeasure -> { require(supplied.isEmpty()) { "fact measurements do not declare member parameters" }; instance.bindings }
            }
            val identity = InstancePosition(reference, bindings.members)
            if (!enter(identity, "instance $reference", capture)) return
            try {
                metric(instance.definition, bindings.members, bindings.measurements, path, linkedSetOf(), capture)
                dependencies.add(InstanceDependency(reference))
            } finally { exit(identity) }
        }

        fun metric(reference: MetricRef, bindings: Map<String, CalculationBindings.Member>, measurements: Map<String, MetricInstanceRef>, path: List<String>, fixedSegment: MutableSet<MetricRef>, capture: Boolean) {
            onDependency?.invoke(MetricDependency(reference), trail + "metric $reference")
            require(fixedSegment.add(reference)) { "fixed metric dependency cycle: ${trail.joinToString(" -> ")} -> $reference" }
            val identity = MetricPosition(reference, bindings, measurements)
            if (!enter(identity, "metric $reference", capture)) { fixedSegment.remove(reference); return }
            try {
                when (val definition = metrics(reference)) {
                    is MetricDefinition.FactMeasure -> if (capture) sources.add(MetricSource(path, Fact(reference), definition.event))
                    is MetricCalculation -> calculation(definition.calculation, bindings, measurements, path, fixedSegment, capture)
                }
                dependencies.add(MetricDependency(reference))
            } finally { fixedSegment.remove(reference); exit(identity) }
        }

        fun member(reference: MemberRef, path: List<String>, call: CalculationBindings.SourceCall?, root: Calculation.Members?, capture: Boolean) {
            onDependency?.invoke(MemberDependency(reference), trail + "member $reference")
            val source = members(reference)
            val bindings = when (source) {
                is MemberSource.Computed -> if (call == null) source.bindings else CalculationInstantiation.relationBindings(source.definition.calculation,
                    CalculationInstantiation.memberBindings(source.definition.calculation, source.bindings, call.members), call.relations)
                is MemberSource.Projection -> CalculationBindings.empty()
            }
            val identity = MemberPosition(reference, bindings.members)
            if (!enter(identity, "member $reference", capture)) return
            try {
                require(reference == MemberRef(source.id, source.version)) { "member source differs from its exact dependency reference" }
                when (source) {
                    is MemberSource.Computed -> calculation(source.definition.calculation, bindings.members, bindings.measurements, path, linkedSetOf(), capture)
                    is MemberSource.Projection -> {
                        val result = source.definition.result
                        val used = result.identitySlots.toSortedSet()
                        if (root == null) used.addAll(result.outputs.keys)
                        else {
                            val actual = requireNotNull(call)
                            root.outputs.forEach { used.add(requireNotNull(actual.outputSlots[it.slot]) { "member output slot is not bound" }) }
                            conditionSlots(root.condition, actual.outputSlots, used)
                            if (root.range != null) used.add(requireNotNull(result.timeSlot) { "member source range is not declared" })
                        }
                        if (capture && used.isNotEmpty()) sources.add(MetricSource(path, Member(source.id, source.version, result.role, used.toList()), source.definition.event))
                    }
                }
                dependencies.add(MemberDependency(reference))
            } finally { exit(identity) }
        }

        private fun calculation(calculation: Calculation, bindings: Map<String, CalculationBindings.Member>, measurements: Map<String, MetricInstanceRef>, path: List<String>, fixedSegment: MutableSet<MetricRef>, capture: Boolean) {
            calculation.memberSlots.forEach { slot -> slot.fixedSource?.let { member(it, path, null, null, false) } }
            if (declarationGraph) {
                bindings.values.filterIsInstance<CalculationBindings.SourceCall>().forEach { member(it.source, path, it, null, false) }
                measurements.values.forEach { instance(it, path, false) }
                calculation.callSites().forEach { site ->
                    val fixed = site.call.target as? Calculation.Fixed
                    if (fixed != null) metric(fixed.metric, emptyMap(), emptyMap(), path + site.path + listOf("\$metric", site.call.callId), fixedSegment, false)
                }
            } else scope(calculation.computation, bindings, measurements, path, fixedSegment, capture)
        }

        private fun scope(scope: Calculation.Scope, bindings: Map<String, CalculationBindings.Member>, measurements: Map<String, MetricInstanceRef>, path: List<String>, fixedSegment: MutableSet<MetricRef>, capture: Boolean) {
            require(path.size <= ScopedInputContract.MAX_DEPTH * 2) { "calculation source positions exceed the depth budget" }
            when (val root = scope.root) {
                is Calculation.Members -> {
                    val selected = requireNotNull(bindings[root.slot]) { "member slot is not bound" }
                    if (selected is CalculationBindings.SourceCall) member(selected.source, path + listOf("\$member", root.callId), selected, root, capture)
                }
                is Calculation.Measures -> root.calls.forEach { call ->
                    val position = path + listOf("\$metric", call.callId)
                    val supplied = CalculationInstantiation.memberBindings(call, bindings)
                    when (val target = call.target) {
                        is Calculation.Measurement -> instance(requireNotNull(measurements[target.slot]) { "measurement slot is not bound" }, position, capture, supplied)
                        is Calculation.Fixed -> {
                            val childMeasurements = TreeMap<String, MetricInstanceRef>()
                            call.measurementSlots.forEach { (child, parent) -> childMeasurements[child] = requireNotNull(measurements[parent]) { "nested measurement slot is not bound" } }
                            metric(target.metric, supplied, childMeasurements, position, fixedSegment, capture)
                        }
                    }
                }
                else -> Unit
            }
            scope.children().forEach { (id, child) -> this.scope(child.computation, bindings, measurements, path + listOf("\$input", id), fixedSegment, capture) }
        }
        private fun conditionSlots(condition: MemberCondition, bindings: Map<String, String>, used: MutableSet<String>) {
            when (condition) {
                is MemberCondition.Unrestricted -> Unit
                is MemberCondition.Predicate -> used.add(requireNotNull(bindings[condition.slot]) { "member predicate slot is not adopted" })
                is MemberCondition.All -> condition.operands.forEach { conditionSlots(it, bindings, used) }
                is MemberCondition.Any -> condition.operands.forEach { conditionSlots(it, bindings, used) }
                is MemberCondition.Not -> conditionSlots(condition.operand, bindings, used)
            }
        }
    }
}
