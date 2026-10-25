package com.olap.prototype.management.governance

import com.olap.prototype.calculation.*
import com.olap.prototype.expression.SemanticExpression
import com.olap.prototype.expression.SemanticExpressionInspector
import com.olap.prototype.governance.contract.DefinitionImpact.Reference
import com.olap.prototype.member.MemberSourceDefinition
import com.olap.prototype.member.MemberRef
import com.olap.prototype.metric.MetricValueContract
import com.olap.prototype.query.PostOperationTraversal
import com.olap.prototype.query.ResultAggregation
import com.olap.prototype.query.SetOutputContract

/** 只识别正式引用节点；不反射 getter，不从标量 id/version 猜依赖。 */
internal class DefinitionReferences(private val instances: (MetricInstanceRef) -> Reference, private val emit: (Reference) -> Unit) {
    fun expression(value: SemanticExpression) {
        SemanticExpressionInspector.references(value).forEach { ref ->
            reference(if (ref.referenceKind == SemanticExpression.ReferenceKind.PHYSICAL_COLUMN) "PHYSICAL_COLUMN" else "LOGIC_UNIT", ref.id, ref.version)
        }
        SemanticExpressionInspector.physicalTables(value).forEach { reference("PHYSICAL_TABLE", it.physicalTableId, it.physicalTableVersion) }
    }
    fun metric(value: MetricDefinition) {
        when (value) {
            is MetricDefinition.FactMeasure -> {
                reference("EVENT", value.event.eventId, value.event.eventVersion)
                expression(value.input); value.privatePredicate?.let(::expression)
                value.snapshotRule?.expressions()?.forEach(::expression)
                quantity(value.valueContract); value.snapshotRule?.meanValueContract?.let(::quantity)
            }
            is MetricCalculation -> { calculation(value.calculation); quantity(value.valueContract) }
        }
    }
    fun computation(value: MetricInstance) { emit(Reference("METRIC", value.definition.id, value.definition.version)); bindings(value.bindings) }
    fun bindings(value: CalculationBindings) {
        (value.dimensions.values + value.groupingCandidates.values.flatten()).forEach { binding ->
            emit(Reference("ANALYSIS_DIMENSION", binding.reference.id, binding.reference.version))
        }
        value.measurements.values.forEach { emit(instances(it)) }
        value.members.values.forEach(::memberBinding)
    }
    private fun memberBinding(value: CalculationBindings.Member) {
        when (value) {
            is CalculationBindings.SourceCall -> { reference("MEMBER_SOURCE", value.source.id, value.source.version); value.members.values.forEach(::memberBinding) }
            is CalculationBindings.LocalRef, is CalculationBindings.InputParameter -> Unit
        }
    }
    fun member(value: MemberSourceDefinition) {
        when (value) {
            is MemberSourceDefinition.Projection -> reference("EVENT", value.event.eventId, value.event.eventVersion)
            is MemberSourceDefinition.Computed -> calculation(value.calculation)
        }
        value.result.outputs.values.forEach { output ->
            when (output) {
                is MemberSourceDefinition.PropertyOutput -> reference("LOGIC_UNIT", output.logicUnitId, output.logicUnitVersion)
                is MemberSourceDefinition.ContainerPropertyOutput -> reference("LOGIC_UNIT", output.logicUnitId, output.logicUnitVersion)
                is MemberSourceDefinition.DimensionOutput -> reference("DIMENSION_EVENT_BINDING", output.bindingId, output.bindingVersion)
                is MemberSourceDefinition.ComputedDimensionOutput -> output.valueContract?.let(::quantity)
                is MemberSourceDefinition.ComputedPropertyOutput -> output.valueContract?.let(::quantity)
                is MemberSourceDefinition.ComputedContainerPropertyOutput -> shape(output.shape)
            }
        }
    }
    private fun calculation(value: Calculation) {
        value.fixedMemberSources().values.forEach { reference("MEMBER_SOURCE", it.id, it.version) }
        fun scope(current: Calculation.Scope) {
            current.stages.forEach { operation -> PostOperationTraversal.valueContracts(operation).forEach(::quantity) }
            when (val root = current.root) {
                is Calculation.ObservationStatistics -> root.statistics.forEach { statistic ->
                    when (statistic) {
                        is Calculation.Reduction -> (statistic.aggregate.aggregation as? ResultAggregation.Numeric)?.let { quantity(it.valueContract) }
                        is Calculation.WeightedMean -> { quantity(statistic.numeratorContract); quantity(statistic.weightSumContract); quantity(statistic.resultContract) }
                    }
                }
                is Calculation.SetCombination -> root.outputSlots.forEach { shape(it.shape) }
                else -> Unit
            }
            current.children().values.forEach { scope(it.computation) }
        }
        value.definitionBodies().values.forEach { body ->
            body.callSites().forEach { site -> (site.call.target as? Calculation.Fixed)?.let { emit(Reference("METRIC", it.metric.id, it.metric.version)) } }
            body.memberSlots.forEach { slot -> slot.outputs.values.filterIsInstance<Calculation.ScalarMemberOutput>().forEach { it.valueContract?.let(::quantity) } }
            body.parameters.values.forEach { it.valueContract?.let(::quantity) }
            body.relationSlots.forEach { slot -> slot.outputs.forEach { shape(it.shape) } }
            body.measurementSlots.forEach { slot -> quantity(slot.valueContract); slot.parameters.values.forEach { it.valueContract?.let(::quantity) } }
            scope(body.computation)
        }
    }
    private fun shape(value: SetOutputContract.Shape) {
        when (value) {
            is SetOutputContract.Quantity -> quantity(value.valueContract)
            is SetOutputContract.Row -> value.fields.forEach(::shape)
            is SetOutputContract.Array -> shape(value.element)
            is SetOutputContract.Dimension -> emit(Reference("ANALYSIS_DIMENSION", value.dimension.id, value.dimension.version))
            is SetOutputContract.MemberDimension -> reference("DIMENSION", value.dimensionId, value.dimensionVersion)
            is SetOutputContract.DimensionSlot, is SetOutputContract.Property, is SetOutputContract.Scalar -> Unit
        }
    }
    private fun quantity(value: MetricValueContract) { (value.unit as? MetricValueContract.EventDimension)?.let { reference("DIMENSION_EVENT_BINDING", it.bindingId, it.bindingVersion) } }
    private fun reference(type: String, id: String, version: Long) { emit(Reference(type, id, version.toString())) }
}

/** 固定来源是精确采用依赖；同时收集直接来源与实参中的正式引用节点。 */
internal fun Calculation.fixedMemberSources(): Map<String, MemberRef> = linkedMapOf<String, MemberRef>().apply {
    fun members(values: Map<String, CalculationBindings.Member>, path: String) {
        values.forEach { (name, value) -> if (value is CalculationBindings.SourceCall) {
            put("$path/$name/source", value.source)
            members(value.members, "$path/$name/members")
        } }
    }
    fun scope(current: Calculation.Scope, path: String) {
        when (val root = current.root) {
            is Calculation.Members -> {
                (root.target as? Calculation.MemberSource)?.let { put("$path/root/source", it.source) }
                members(root.members, "$path/root/members")
            }
            is Calculation.Measures -> root.calls.forEach { call -> members(call.members, "$path/root/calls/${call.callId}/members") }
            else -> Unit
        }
        current.children().forEach { (name, child) -> scope(child.computation, "$path/inputs/$name/computation") }
    }
    definitionBodies().forEach { (path, body) ->
        val prefix = if (path.isEmpty()) "" else "$path/"
        body.memberSlots.forEach { slot -> slot.fixedSource?.let { put("${prefix}memberSlots/${slot.name}/fixedSource", it) } }
        scope(body.computation, "${prefix}computation")
    }
}
