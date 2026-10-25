package com.olap.prototype.management.governance

import com.olap.prototype.calculation.*
import com.olap.prototype.dsl.Computations
import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.event.EventIdentity
import com.olap.prototype.event.EventRef
import com.olap.prototype.event.RelationAccessPath
import com.olap.prototype.expression.SemanticExpression
import com.olap.prototype.codec.SemanticProto
import com.olap.prototype.codec.ProtoBytes
import com.olap.prototype.governance.contract.*
import com.olap.prototype.integration.RuntimeDeploymentLimits
import com.olap.prototype.member.*
import com.olap.prototype.metric.MetricBusinessMeaning
import com.olap.prototype.query.*
import com.olap.prototype.query.contract.*
import com.olap.prototype.query.definition.*
import com.olap.prototype.release.catalog.*
import com.olap.prototype.release.contract.*
import com.olap.prototype.storage.PlatformDatabase
import com.olap.prototype.time.TemporalWindow
import com.olap.prototype.warehouse.contract.*
import java.nio.file.Path
import java.time.LocalDate
import java.time.LocalDateTime

/** 原嵌套客户场景的作者数据与两份独立采用；结果断言由各行为试验拥有。 */
internal class NestedCustomerFixture(val database: PlatformDatabase, directory: Path) {
    lateinit var f: GovernanceOrderFixture
        private set
    val child: MetricRecord
    val parent: MetricRecord
    val measured: MetricRecord
    val definition: MetricCalculation
    val memberDefinition: MemberSourceDefinition.Projection
    val internal = linkedMapOf<String, SubjectAreaMetricRefRecord>()
    val fixed = linkedMapOf<String, SubjectAreaMetricRefRecord>()
    val primary = linkedMapOf<String, SubjectAreaMetricRefRecord>()
    val bindings = linkedMapOf<String, CalculationBindings>()
    val july = range("2026-07-01", "2026-08-01")
    val meaning = MetricBusinessMeaning("先按客户汇总并判定资格，再按经营组平均", "元", "独立采用的客户总体", listOf("范围与门槛属于实际调用"))
    val queries: QueryDefinitionService

    init {
        f = GovernanceOrderFixture(database, directory, listOf(ORDERS), listOf(SnapshotUniqueKey.Rule("customer_day", listOf("customer_id", "pay_time")))) { anchor ->
            SealedSourceDataset(SealedSourceDataset.FORMAT_VERSION, "golden-source-owner", "b".repeat(64), anchor,
                SealedSourceDataset.Scope("Asia/Shanghai", LocalDate.parse("2026-07-01"), LocalDate.parse("2026-09-01"), LocalDateTime.parse("2026-09-01T00:00:00")),
                listOf(SealedSourceDataset.Collection("registered.orders", JdbcTableConstraints.Table(f.sourceSnapshot.table.ref.catalog, "main", "orders"), listOf("registered-orders-source"))))
        }
        f.seed(); f.analysisDimension("global.customer", "客户", "customer", "order_fact.customer_id", "customer")
        val originalCustomerBinding = f.dimensions.listDimensionEventBindings("global.customer", 1).single()
        f.activate("DIMENSION", "global.customer"); f.activate("DIMENSION_EVENT_BINDING", originalCustomerBinding.id)
        f.activateSubjectGraph(false)
        f.subjects.createSubjectArea(SubjectAreaCreateRequest("travel", 1, "finance", "财务客户经营", "finance", "独立采用", "golden"))
        for ((dimension, code, level) in listOf(listOf("global.customer", "customer", "customer"), listOf("global.destination", "destination", "destination"), listOf("common.business_date", "business_date", "day"))) {
            val semantic = f.dimensions.listDimensionEventBindings(dimension, 1).single()
            val analysis = f.analysis.createAnalysisDimension(AnalysisDimensionCreateRequest("travel.finance", 1, code, code, "财务采用", level, level, "golden")).analysisDimension
            val proof = f.analysis.createAnalysisDimensionValueAlignment(AnalysisDimensionValueAlignmentCreateRequest(analysis.id, 1, level, "PROVEN_SAME_KEY",
                f.mapper.writeValueAsString(mapOf("logicUnitId" to semantic.logicUnitId)), "golden"))
            f.analysis.createAnalysisDimensionEventMapping(AnalysisDimensionEventMappingCreateRequest(analysis.id, 1, level, semantic.id, 1, "PROVEN_SAME_KEY", proof.id))
        }
        definition = CustomerCalculationDefinitions.average(f.paymentId)
        child = createMetric("customer_average", definition)
        parent = createMetric("published_customer_average", CustomerCalculationDefinitions.parent(child.id, definition))
        measured = createMetric("slotted_customer_average", CustomerCalculationDefinitions.measurement(definition.calculation.parameters.filterKeys { it != "minimum" }))
        memberDefinition = customerProjection(GovernanceOrderFixture.EVENT, originalCustomerBinding.id)
        val aEvent = f.events.createEvent(EventCreateRequest(GovernanceOrderFixture.TOPOLOGY, 1, "a_customers", "曾在A组消费客户", "trade", "order_fact.pay_time", 1,
            EventIdentity(listOf(EventIdentity.LogicUnitRef("order_fact.order_id", 1)), mapOf("order" to listOf(EventIdentity.LogicUnitRef("order_fact.order_id", 1)))), emptyMap(),
            listOf(EventCreateRequest.LogicUnitRefInput("order_fact.destination", 1), EventCreateRequest.LogicUnitRefInput("order_fact.customer_id", 1)), emptyList(), "资格独立于测量期间", "golden")).event
        StoredGovernanceDefinitions(database).execute("INSERT INTO event_slice_filter(id,event_id,event_version,predicate_proto,created_at) VALUES (?,?,?,?,?)", "a_customers_source", aEvent.id, 1,
            ProtoBytes.storage(SemanticProto.toProto(SemanticExpression.Operation(SemanticExpression.Operator.EQ, listOf(GovernanceOrderFixture.ref("order_fact.destination"), SemanticExpression.Literal("A"))))), f.now)
        val aBinding = f.dimensions.createDimensionEventBinding(DimensionEventBindingCreateRequest("global.customer", 1, aEvent.id, 1, "order_fact.customer_id", 1, null, RelationAccessPath(emptyList()), emptyMap(), "golden")).binding
        f.analysis.createAnalysisDimensionEventMapping(AnalysisDimensionEventMappingCreateRequest("travel.trade.customer", 1, "customer", aBinding.id, 1, "PROVEN_SAME_KEY", "alignment:customer"))
        f.activate("EVENT", aEvent.id); f.activate("DIMENSION_EVENT_BINDING", aBinding.id)
        for (subject in SUBJECTS) {
            val member = f.adoptions.createMemberSource(MemberSourceCreateRequest(subject, 1, "customers", "客户总体", memberDefinition, CalculationBindings.empty(), emptyMap(), "golden"))
            val adoption = CalculationBindings(mapOf("customer" to dimension(subject, "customer", "customer", "customer"), "time" to dimension(subject, "business_date", "day", "business_time")), mapOf("customers" to CalculationBindings.SourceCall(MemberRef(member.id, 1), emptyMap(), mapOf("customer" to "customer"), emptyMap(), emptyMap())), mapOf("groups" to listOf(dimension(subject, "destination", "destination", "destination"))), emptyMap(), emptyMap())
            bindings[subject] = adoption
            internal[subject] = adopt(subject, "qualified_measurement", child, adoption, mapOf("minimum" to Calculation.Scalar(ScalarValueType.NUMBER, "30")))
            fixed[subject] = adopt(subject, "fixed_july_average", measured, CalculationBindings(emptyMap(), emptyMap(), adoption.groupingCandidates, mapOf("qualified" to internal.getValue(subject).instance), emptyMap()), mapOf("window" to july))
            primary[subject] = adopt(subject, parent.code, parent, adoption)
            val scenario = f.scenarios.createScenario(ScenarioCreateRequest(subject, 1, "customers", "客户平均分析", "analyst", "完整算法调用", "golden")).scenario
            f.scenarios.createScenarioMetricRef(ScenarioMetricRefCreateRequest(scenario.id, 1, primary.getValue(subject).id, 1, true, 1))
            f.scenarios.createScenarioMetricRef(ScenarioMetricRefCreateRequest(scenario.id, 1, fixed.getValue(subject).id, 1, false, 3))
            if (subject == "travel.trade") {
                val aCustomers = f.adoptions.createMemberSource(MemberSourceCreateRequest(subject, 1, "a_customers", "曾在A组消费客户", customerProjection(aEvent.id, aBinding.id), CalculationBindings.empty(), emptyMap(), "golden"))
                val aAdoption = CalculationBindings(adoption.dimensions, mapOf("customers" to CalculationBindings.SourceCall(MemberRef(aCustomers.id, 1), emptyMap(), mapOf("customer" to "customer"), emptyMap(), emptyMap())), adoption.groupingCandidates, emptyMap(), emptyMap())
                val alternative = adopt(subject, "a_customers_average", parent, aAdoption)
                f.scenarios.createScenarioMetricRef(ScenarioMetricRefCreateRequest(scenario.id, 1, alternative.id, 1, false, 2))
            }
            f.scenarios.createScenarioMemberSourceRef(ScenarioMemberSourceRefCreateRequest(scenario.id, 1, member.id, 1, MemberQueryPermission(listOf("customer"), emptyList(), emptyMap(), false, emptyList())))
            f.scenarios.createScenarioAnalysisDimensionRef(ScenarioAnalysisDimensionRefCreateRequest(scenario.id, 1, "$subject.destination", 1, "destination", false, 1))
        }
        queries = QueryDefinitionService(JdbcQueryDefinitionRepository(database, RuntimeDeploymentLimits.io), JdbcPublishedQueryEntryProvider(database),
            NativeValidator { intent, capability, executionMode -> QueryNativeValidation.validate(intent, capability, f.connectors, executionMode) })
    }

    fun publish() { SUBJECTS.forEach { f.activate("SUBJECT_AREA", it); f.builder().build(SubjectAreaReleaseBuildRequest("$it.release", "1", "独立客户平均发布", it, 1, "golden", f.sourceAdoptions)) } }
    fun publication(subject: String, version: String = "1", subjectVersion: Long = 1) = f.artifacts.loadExecutableCatalog(ReleaseCatalogRequest("$subject.customers", subjectVersion, "$subject.release", version))
    fun createMetric(code: String, definition: MetricCalculation): MetricRecord = f.metrics.createMetricCalculation(MetricCalculationCreateRequest(code, "客户经营平均", "trade", definition, "客户层归约", meaning, "golden")).metric.also { f.activate("METRIC", it.id) }
    fun adopt(subject: String, code: String, metric: MetricRecord, bindings: CalculationBindings, arguments: Map<String, Calculation.Value> = emptyMap(), subjectVersion: Long = 1, metricVersion: Long = 1): SubjectAreaMetricRefRecord =
        f.adoptions.createSubjectAreaMetricRef(SubjectAreaMetricRefCreateRequest(subject, subjectVersion, code, metric.displayName, meaning, metric.id, metricVersion, bindings, arguments, "golden")).subjectAreaMetricRef
    fun dimension(subject: String, code: String, level: String, role: String) = CalculationBindings.Dimension(ScopedInputContract.DimensionRef("$subject.$code", "1", level), role,
        f.analysis.listAnalysisDimensionEventMappings("$subject.$code", 1).filter { it.levelCode == level }.map { it.id })
    fun save(publication: PublishedExecutableCatalog, id: String, scope: Calculation.Scope): QueryDefinitionView {
        val coordinate = publication.coordinate
        val saved = queries.save(QueryDefinitionCommand(id, null, requireNotNull(publication.queryCapability.capabilityHash), "独立客户平均", f.intent(publication, scope), null), "golden")
        return queries.requireByIdAndVersion(saved.definitionId, saved.definitionVersion)
    }
    fun execute(publication: PublishedExecutableCatalog, saved: QueryDefinitionView) = f.execute(publication, saved.intent.computation)
    private fun customerProjection(event: String, binding: String) = MemberSourceDefinition.Projection(EventRef(event, 1), MemberResult("客户", "customer", MemberShape.UNIQUE_MEMBERS,
        listOf("customer"), mapOf("customer" to MemberSourceDefinition.DimensionOutput("客户", "customer", ScalarValueType.TEXT, "global.customer", 1, binding, 1, null)), emptyMap(), null, emptyMap(), null))

    companion object {
        val SUBJECTS = listOf("travel.trade", "travel.finance")
        const val ORDERS = "INSERT INTO orders VALUES ('1',10,'A','2026-07-01','c1'),('2',20,'A','2026-07-02','c1'),('3',70,'A','2026-07-03','c2'),('4',5,'B','2026-07-01','c3'),('5',15,'B','2026-07-02','c3'),('6',100,'B','2026-08-01','c4')"
        fun range(start: String, end: String) = Calculation.Range(TemporalWindow(TemporalWindow.ValueType.DATE, TemporalWindow.BOUNDARY, start, end))
        fun invocation(id: String, instance: MetricInstanceRef, grouping: List<ScopedInputContract.DimensionRef>, start: String, end: String, minimum: String) =
            Computations.invoke(instance, id, arguments = mapOf("groups" to Calculation.Grouping(grouping), "window" to range(start, end), "minimum" to Calculation.Scalar(ScalarValueType.NUMBER, minimum)))
        fun scope(calls: List<Calculation.Call>, grouping: List<ScopedInputContract.DimensionRef>, levels: GroupingLevels? = null) =
            Computations.measure(calls, grouping.map { Calculation.PublishedDimension(it) }, RowPopulationContract.calls(calls.map { it.callId }), groupingLevels = levels)
    }
}
