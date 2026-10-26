package com.olap.prototype.release.catalog.capability

import com.olap.prototype.calculation.MetricDefinition
import com.olap.prototype.calculation.MetricSource
import com.olap.prototype.connector.CompilationSupport
import com.olap.prototype.connector.ConnectorRegistry
import com.olap.prototype.connector.ResolvedConnector
import com.olap.prototype.contract.NativeTableDependency
import com.olap.prototype.contract.NativeValueType
import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.engine.catalog.ExecutableCatalog
import com.olap.prototype.engine.catalog.ExecutablePhysicalExecutionBinding
import com.olap.prototype.event.EventRef
import com.olap.prototype.expression.*
import com.olap.prototype.member.MemberRef
import com.olap.prototype.member.MemberSource
import com.olap.prototype.member.MemberSourceDefinition
import com.olap.prototype.metric.MetricSnapshotRule
import com.olap.prototype.metric.MetricAggregation
import com.olap.prototype.metric.MetricValueContract
import com.olap.prototype.query.ScalarValueType
import com.olap.prototype.query.ScopedInputContract
import com.olap.prototype.query.contract.*
import com.olap.prototype.release.catalog.QueryCompilationCatalog
import com.olap.prototype.release.contract.ReleaseDimensionMapping
import com.olap.prototype.warehouse.contract.SourceBindingRef
import org.apache.calcite.rex.RexNode
import org.apache.calcite.sql.type.SqlTypeName
import org.apache.calcite.tools.RelBuilder

/** 已编译执行资产的公开类型投影，不重建冻结快照、不连接执行库。 */
class PublishedNativeTypes(mappings: List<ReleaseDimensionMapping>, private val assets: ExecutableCatalog, private val connectors: ConnectorRegistry) {
    private val mappings = java.util.List.copyOf(mappings)
    private val units = assets.logicUnits.mapValues { (_, unit) -> requireNotNull(assets.physicalExecutionBindings["${unit.physicalExecutionBindingId}@${unit.physicalExecutionBindingVersion}"]) { "exact frozen physical binding is required" } }
    private val members = assets.memberSources
    private val contexts = linkedMapOf<SourceBindingRef, QueryExecutionTypes.Context>()
    private val nativeTables = linkedMapOf<QueryExecutionTypes.Context, MutableMap<String, NativeTableDependency>>()
    private val sourcesByLogical = linkedMapOf<String, MutableList<SourceBindingRef>>()
    private val connectorsByGroup = sortedMapOf<String, ResolvedConnector>()
    init {
        assets.physicalExecutionBindings.values.sortedWith(compareBy<ExecutablePhysicalExecutionBinding> { it.dataSourceId }
            .thenBy { it.adoption.binding.sourceIdentityHash }.thenBy { it.bindingId }.thenBy { it.bindingVersion }).forEach { physical ->
            val source = bindingRef(physical)
            val connector = connectors.require(physical.connectorRef)
            val group = contexts[source]?.executionGroup ?: "source-group-${contexts.size}"
            val priorConnector = connectorsByGroup.putIfAbsent(group, connector)
            if (priorConnector != null && priorConnector.ref != connector.ref) throw ProducerContractException("one frozen execution group has conflicting connector identities")
            val context = QueryExecutionTypes.Context(group, physical.dialect, connector.access.supportsConstraintVerification(physical.dataContract, physical.adoption))
            val previous = contexts.putIfAbsent(source, context)
            if (previous != null && previous != context) throw ProducerContractException("one frozen source has conflicting native execution conditions")
        }
        assets.physicalExecutionBindings.values.forEach { physical ->
            val source = bindingRef(physical)
            val logicalSources = sourcesByLogical.getOrPut("${physical.logicalTableId}@${physical.logicalTableVersion}") { mutableListOf() }
            if (source !in logicalSources) logicalSources.add(source)
            val tables = nativeTables.getOrPut(contexts.getValue(source)) { linkedMapOf() }
            physical.nativeTableDependencies.values.forEach { dependency ->
                dependency.requireSource(source, physical.connectorRef, physical.dialect)
                val previous = tables.putIfAbsent(dependency.key(), dependency)
                if (previous != null && previous != dependency) throw ProducerContractException("conflicting exact native table dependency")
            }
        }
    }
    fun produce(metrics: List<MetricCandidate>, members: List<MemberCandidate>, dimensions: List<AnalysisDimensionCandidate>): QueryExecutionTypes {
        val metricTypes = sortedMapOf<String, QueryExecutionTypes.ContextBinding>()
        metrics.forEach { metric ->
            val binding = contextBinding(metric.sources, metric.memberParameters.keys, metric.relationParameters.keys)
            if (binding is QueryExecutionTypes.FixedContext) {
                metric.sources.forEach { source -> (source.origin as? MetricSource.Fact)?.let { fact -> factType(assets.requireMetric(fact.metric).definition as MetricDefinition.FactMeasure, binding.context) } }
                SemanticNumericRex.resultType(builder(binding.context), metric.valueContract, true)
            }
            metricTypes[metric.instance.id] = binding
        }
        val memberTypes = members.associateTo(sortedMapOf()) { key(it.sourceId, it.sourceVersion) to nativeMember(it) }
        val dimensionTypes = dimensions.flatMap { dimension -> dimension.eventMappings.map { mapping ->
            val matches = mappings.filter { it.analysisDimensionId == dimension.analysisDimensionId && it.analysisDimensionVersion == dimension.analysisDimensionVersion &&
                it.levelCode == mapping.levelCode && it.eventId == mapping.eventId && it.eventVersion == mapping.eventVersion }
            if (matches.size != 1) throw ProducerContractException("exact native dimension mapping is required: ${dimension.analysisDimensionId}")
            nativeDimension(matches.single())
        } }
        return QueryExecutionTypes(QueryExecutionTypes.FORMAT_VERSION, metricTypes, memberTypes, dimensionTypes)
    }
    fun definitions(): QueryNativeDefinitions = QueryNativeDefinitions(QueryCompilationCatalog.project(assets, connectors))
    private fun nativeDimension(mapping: ReleaseDimensionMapping): QueryExecutionTypes.Dimension {
        val context = eventContext(EventRef(mapping.eventId, mapping.eventVersion))
        val value = expression(builder(context), context, mapping.expression)
        return QueryExecutionTypes.Dimension(ScopedInputContract.DimensionRef(mapping.analysisDimensionId, mapping.analysisDimensionVersion.toString(), mapping.levelCode),
            mapping.eventId, mapping.eventVersion, context, SemanticNativeTypes.describe(value.type))
    }
    private fun nativeMember(candidate: MemberCandidate): QueryExecutionTypes.Member {
        val source = requireNotNull(members[key(candidate.sourceId, candidate.sourceVersion)]) { "exact member source is required" }
        if (source is MemberSource.Computed) {
            val sources = MetricSource.resolve(MemberRef(source.id, source.version), { assets.requireMetric(it).definition },
                { requireNotNull(assets.metricInstances[it.id]) { "exact frozen member measurement is required" } },
                { requireNotNull(members[key(it.id, it.version)]) { "exact frozen member is required" } })
            return QueryExecutionTypes.ComputedMember(contextBinding(sources, candidate.memberParameters.keys, candidate.relationParameters.keys))
        }
        source as MemberSource.Projection
        val context = eventContext(source.definition.event)
        val outputs = sortedMapOf<String, NativeValueType>()
        candidate.outputs.forEach { (slot, output) ->
            val builder = builder(context)
            var value = expression(builder, context, source.outputExpressions.getValue(slot))
            val numericUnit = source.definition.result.outputs.values.mapNotNull(MemberSourceDefinition::rootValueContract).any { contract ->
                (contract.unit as? MetricValueContract.SlotDimension)?.let { it.slot == slot && it.axis.memberType == ScalarValueType.NUMBER } == true
            }
            if (numericUnit) SemanticNumericRex.requireExactMemberCode(value)
            value = SemanticNumericRex.output(builder, value, MemberCandidate.rootValueContract(output))
            outputs[slot] = SemanticNativeTypes.describe(value.type)
        }
        return QueryExecutionTypes.ProjectionMember(context, outputs)
    }
    private fun contextBinding(sources: List<MetricSource>, memberParameters: Set<String>, relationParameters: Set<String>): QueryExecutionTypes.ContextBinding {
        val contexts = sources.map { eventContext(it.event) }.distinct()
        if (contexts.size == 1) return QueryExecutionTypes.FixedContext(contexts.single())
        if (contexts.isEmpty() && (memberParameters.isNotEmpty() || relationParameters.isNotEmpty())) return QueryExecutionTypes.Inputs(memberParameters, relationParameters)
        throw ProducerContractException("calculation has no single supported fixed source or explicit member/relation input context")
    }
    private fun factType(fact: MetricDefinition.FactMeasure, context: QueryExecutionTypes.Context) {
        val builder = builder(context)
        fact.privatePredicate?.let { requirePredicate(expression(builder, context, it)) }
        fact.snapshotRule?.keys()?.forEach { expression(builder, context, it.expression()) }
        val unit = fact.valueContract.unit
        if (unit is MetricValueContract.EventDimension && unit.axis.memberType == ScalarValueType.NUMBER) mappings.filter { it.dimensionBindingId == unit.bindingId && it.dimensionBindingVersion == unit.bindingVersion }.forEach {
            SemanticNumericRex.requireExactMemberCode(expression(builder, context, it.expression))
        }
        val raw = expression(builder, context, fact.input)
        var input = if (fact.aggregation == MetricAggregation.AVG || fact.snapshotRule?.reduction == MetricSnapshotRule.Reduction.MEAN)
            SemanticNumericRex.averageInput(builder, raw)
        else SemanticNumericRex.factInput(builder, raw, fact.aggregation, fact.valueContract)
        val snapshot = fact.snapshotRule
        if (snapshot?.reduction == MetricSnapshotRule.Reduction.MEAN) input = aggregateType(builder, input, MetricAggregation.AVG, requireNotNull(snapshot.meanValueContract), compilation(context))
        aggregateType(builder, input, fact.aggregation, fact.valueContract, compilation(context))
    }
    private fun aggregateType(builder: RelBuilder, input: RexNode, aggregation: MetricAggregation, contract: MetricValueContract, support: CompilationSupport): RexNode {
        val value = SemanticNumericRex.factAggregate(builder, aggregation, input, contract)
        // 发布没有本次 Query 的 groupSets/filter/window；声明容量不冒充真实 AVG 目标已经兑现。
        if (aggregation == MetricAggregation.AVG) return builder.rexBuilder.makeInputRef(SemanticNumericRex.resultType(builder, contract, true), 0)
        builder.values(builder.typeFactory.builder().add("input", value.type).build())
        builder.aggregate(builder.groupKey(), builder.aggregateCall(RelationalAggregates.factAggregateFunction(aggregation, support), listOf(builder.field(0))).`as`("value"))
        val result = SemanticNumericRex.output(builder, builder.field(0), contract)
        builder.build()
        return result
    }
    private fun eventContext(reference: EventRef): QueryExecutionTypes.Context {
        val event = requireNotNull(assets.events[key(reference.eventId, reference.eventVersion)]) { "frozen event is required" }
        val topology = requireNotNull(assets.topologies["${event.topologyId}@${event.topologyVersion}"]) { "frozen topology is required" }
        val sources = sourcesByLogical["${topology.backboneLogicalTableId}@${topology.backboneLogicalTableVersion}"].orEmpty()
        if (sources.size != 1) throw ProducerContractException("event has no single frozen native source: ${reference.eventId}")
        return contexts.getValue(sources.single())
    }
    private fun expression(builder: RelBuilder, context: QueryExecutionTypes.Context, expression: SemanticExpression): RexNode = ExpressionTypes(builder, context).compile(expression)

    /** 活动展开集和Rex只属于当前builder内的一次类型推导，不跨关系位置缓存。 */
    private inner class ExpressionTypes(private val builder: RelBuilder, private val context: QueryExecutionTypes.Context) {
        private val resolving = linkedSetOf<String>()
        private val compiler: SemanticCalciteRexCompiler
        init {
            val connector = connectorsByGroup.getValue(context.executionGroup)
            val tables = nativeTables[context].orEmpty()
            val scope = NativeExpressionScope(connector.ref, connector.compilation) { requireNotNull(tables[it.key()]) { "native table is outside the frozen publication closure" } }
            compiler = SemanticCalciteRexCompiler(builder, mutableListOf()) { call, operands -> scope.compile(builder, call, operands) }
        }
        fun compile(expression: SemanticExpression): RexNode {
            SemanticExpressionInspector.quantifiers(expression)
            return compiler.compile(expression, ::reference) { exists ->
                requirePredicate(compile(exists.predicate))
                builder.rexBuilder.makeInputRef(builder.typeFactory.createSqlType(SqlTypeName.BOOLEAN), 0)
            }
        }
        private fun reference(reference: SemanticExpression.Reference): RexNode {
            if (reference.referenceKind == SemanticExpression.ReferenceKind.PHYSICAL_COLUMN) throw ProducerContractException("public source expression requires an exact semantic reference")
            val key = key(reference.id, reference.version)
            val unit = requireNotNull(units[key]) { "exact frozen native LogicUnit is required: $key" }
            if (context != contexts[bindingRef(unit)]) throw ProducerContractException("expression spans incompatible execution sources")
            if (!resolving.add(key) || resolving.size > 64) throw ProducerContractException("native source expression has a cycle or exceeds its scope budget")
            try {
                val definition = requireNotNull(unit.logicUnitBindings[key]) { "exact frozen LogicUnit expression is required" }
                return compiler.compile(definition, { physical ->
                    if (physical.referenceKind == SemanticExpression.ReferenceKind.ROLE_LOGIC_UNIT) throw ProducerContractException("generic LogicUnit cannot own relationship roles")
                    if (physical.referenceKind != SemanticExpression.ReferenceKind.PHYSICAL_COLUMN) this.reference(physical)
                    else {
                        val type = requireNotNull(unit.physicalColumnTypes[this@PublishedNativeTypes.key(physical.id, physical.version)]) { "frozen native column type is required" }
                        val nativeType = compilation(context).jdbcType(builder.typeFactory, type)
                        // 先核验精确插件交付的原生类型，不能让下游谓词或运算掩盖未定容量。
                        SemanticNativeTypes.describe(nativeType)
                        builder.rexBuilder.makeInputRef(nativeType, 0)
                    }
                })
            } finally { resolving.remove(key) }
        }
    }
    private fun requirePredicate(predicate: RexNode) { if (predicate.type.sqlTypeName != SqlTypeName.BOOLEAN) throw ProducerContractException("frozen source predicate requires a native BOOLEAN value") }
    private fun key(id: String, version: Long): String = "$id@$version"
    private fun builder(context: QueryExecutionTypes.Context): RelBuilder = CalciteCompilation.builder(compilation(context))
    private fun bindingRef(physical: ExecutablePhysicalExecutionBinding): SourceBindingRef = physical.adoption.binding
    private fun compilation(context: QueryExecutionTypes.Context): CompilationSupport = requireNotNull(connectorsByGroup[context.executionGroup]) { "exact connector for execution group is unavailable" }.compilation
}
