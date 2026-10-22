package com.olap.prototype.engine.executable

import com.olap.prototype.calculation.CalculationBindings
import com.olap.prototype.connector.ConnectorRegistry
import com.olap.prototype.engine.catalog.*
import com.olap.prototype.expression.*
import com.olap.prototype.query.ScopedInputContract
import com.olap.prototype.warehouse.contract.SourceBindingRef

/** 为实际事件选择冻结映射、关系位置、原生类型及来源依赖。 */
internal class ExecutableDimensionBindings(private val catalog: ExecutableCatalog, private val connectors: ConnectorRegistry) {
    private val relations = catalog.relations()
    fun alias(reference: ScopedInputContract.DimensionRef): String = requireNotNull(catalog.analysisDimensions["${reference.id}@${reference.version}"]) { "grouping family dimension is not frozen" }.outputAlias
    fun timeAnchor(event: ExecutableEvent, timeRange: ExecutableTimeRange?): ExecutableTimeAnchor? {
        if (timeRange == null) return null
        timeRange.window()
        val key = ExecutableTimeAnchor.key(event.eventId, event.eventVersion, timeRange.timeDimensionId, timeRange.timeDimensionVersion, timeRange.levelCode)
        return requireNotNull(catalog.timeAnchors[key]) { "ExecutableCatalog 缺少匹配查询时间分析维度的事件时间锚点: $key" }
    }
    fun include(bound: MutableList<ExecutableBoundAnalysisDimension>, candidate: ExecutableBoundAnalysisDimension) {
        val index = bound.indexOfFirst { existing -> existing.analysisDimension.analysisDimensionId == candidate.analysisDimension.analysisDimensionId &&
            existing.analysisDimension.analysisDimensionVersion == candidate.analysisDimension.analysisDimensionVersion && existing.outputAlias == candidate.outputAlias &&
            existing.levelCode == candidate.levelCode && existing.event.eventId == candidate.event.eventId && existing.event.eventVersion == candidate.event.eventVersion }
        if (index < 0) bound.add(candidate) else if (!candidate.filterOnly) bound[index] = bound[index].copy(filterOnly = false)
    }
    fun bind(event: ExecutableEvent, ref: ScopedInputContract.DimensionRef, filterOnly: Boolean, adopted: CalculationBindings.Dimension? = null): ExecutableBoundAnalysisDimension {
        val analysis = requireNotNull(catalog.analysisDimensions["${ref.id}@${ref.version}"]) { "计算分组或条件引用的维度不在 ExecutableCatalog 中: ${ref.id}@${ref.version}" }
        require(ref.levelCode.isNotBlank()) { "analysisDimensionRef.levelCode is required" }
        val available = if (adopted == null) analysis.eventMappings else catalog.dimensionMappings(adopted)
        val matches = available.filter { it.levelCode == ref.levelCode && it.eventId == event.eventId && it.eventVersion == event.eventVersion }
        require(matches.size == 1) { "dimension must have one exact selected mapping for its actual event: $ref" }
        val mapping = matches.single()
        val binding = requireNotNull(catalog.dimensionBindings["${mapping.dimensionBindingId}@${mapping.dimensionBindingVersion}"]) { "analysis dimension mapping 指向的 dimension binding 不存在" }
        require(event.eventId == binding.eventId && event.eventVersion == binding.eventVersion) { "mapping.event 版本引用不一致" }
        val dimension = requireNotNull(catalog.dimensions["${mapping.dimensionId}@${mapping.dimensionVersion}"]) { "analysis dimension mapping 指向的 dimension 不存在" }
        val unit = requireNotNull(catalog.logicUnits["${mapping.logicUnitId}@${mapping.logicUnitVersion}"]) { "analysis dimension mapping 指向的 logic unit 不存在" }
        val topology = catalog.topologies.getValue("${event.topologyId}@${event.topologyVersion}")
        val joins = binding.joinPathRefs.map { relations.joinPath(topology, it.joinPathId, it.joinPathVersion) }
        val physical = requireNotNull(catalog.physicalExecutionBindings["${binding.physicalExecutionBindingId}@${binding.physicalExecutionBindingVersion}"]) { "dimension binding 缺少可执行 physical execution binding: ${binding.bindingId}@${binding.bindingVersion}" }
        val type = SemanticExpressionValueType.resolve(binding.expression, { reference ->
            require(reference.referenceKind != SemanticExpression.ReferenceKind.PHYSICAL_COLUMN) { "dimension type must use exact LogicUnit references" }
            requireNotNull(catalog.logicUnits["${reference.id}@${reference.version}"]?.valueType) { "dimension exact typed LogicUnit is missing" }
        }, { nativeFunction(physical, it) })
        return ExecutableBoundAnalysisDimension(analysis, analysis.outputAlias, ref.levelCode, event, dimension, binding, unit, type,
            joins, physical, filterOnly, mapping.valueAlignmentKey, mapping.valueAlignmentStatus)
    }
    private fun nativeFunction(source: ExecutablePhysicalExecutionBinding, call: SemanticExpression.NativeCall): NativeScalarFunction {
        val expected = SourceBindingRef(source.dataSourceId, source.sourceConstraints.sourceIdentityHash)
        return NativeExpressionScope(source.connectorRef, connectors.require(source.connectorRef).compilation) { reference ->
            source.nativeTableDependencies[reference.key()]?.also { it.requireSource(expected, source.connectorRef, source.dialect) }
        }.requireCall(call)
    }
}
