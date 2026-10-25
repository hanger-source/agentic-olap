package com.olap.prototype.management.governance

import com.olap.prototype.calculation.*
import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.governance.contract.*
import com.olap.prototype.integration.RuntimeDeploymentLimits
import com.olap.prototype.member.*
import com.olap.prototype.metric.MetricBusinessMeaning
import com.olap.prototype.query.*
import com.olap.prototype.query.contract.*
import com.olap.prototype.query.definition.*
import com.olap.prototype.release.catalog.*
import com.olap.prototype.storage.PlatformDatabase
import java.nio.file.Path

/** 私有来源→共享规则→独立采用→公开计算成员的真实治理装配。 */
internal class ComputedOrderFixture(val database: PlatformDatabase, directory: Path) {
    val f = GovernanceOrderFixture(database, directory, listOf(NestedCustomerFixture.ORDERS))
    val july = NestedCustomerFixture.range("2026-07-01", "2026-08-01")
    val august = NestedCustomerFixture.range("2026-08-01", "2026-09-01")
    val projection: MemberSourceRecord
    val sharedRule: MemberDefinitionRecord
    val revisedRule: MemberDefinitionRecord
    val inner: MemberSourceRecord
    val publicRows: MemberSourceRecord
    val sharedV1: MemberSourceRecord
    val sharedV2: MemberSourceRecord
    val raw: MemberSourceRecord
    val rawMetric: MetricRecord
    val sumDefinition = CustomerCalculationDefinitions.amount("SUM", MemberShape.RECORDS)
    val metrics = linkedMapOf<String, SubjectAreaMetricRefRecord>()
    val queries: QueryDefinitionService
    val meaning = MetricBusinessMeaning("对实际形成订单金额聚合", "元", "明确期间和门槛形成的订单", listOf("身份、数量与重复语义来自完成关系"))
    private var sequence = 0

    init {
        f.seed(); f.activateSubjectGraph(false)
        projection = member("private_orders", OrderMemberDefinitions.projection(MemberShape.RECORDS))
        sharedRule = f.members.create(MemberDefinitionCreateRequest("qualified_period_orders", "达到门槛的期间订单规则", OrderMemberDefinitions.computed("minimum", true), "规则共享，来源与门槛属于采用", "trade", "golden"))
        f.activate("MEMBER_DEFINITION", sharedRule.id)
        inner = f.adoptions.adoptMemberDefinition(MemberDefinitionAdoptRequest("travel.trade", 1, "qualified_orders", "固定门槛的期间订单", MemberDefinitionRef(sharedRule.id, 1),
            sourceBindings(projection), mapOf("minimum" to Calculation.Scalar(ScalarValueType.NUMBER, "10")), "golden"))
        f.subjects.createSubjectArea(SubjectAreaCreateRequest("travel", 1, "shared", "共享订单规则采用", "trade", "独立来源与门槛", "golden"))
        val other = f.adoptions.createMemberSource(MemberSourceCreateRequest("travel.shared", 1, "orders", "独立订单来源", OrderMemberDefinitions.projection(MemberShape.RECORDS), CalculationBindings.empty(), emptyMap(), "golden"))
        sharedV1 = f.adoptions.adoptMemberDefinition(MemberDefinitionAdoptRequest("travel.shared", 1, "qualified_v1", "原口径采用", MemberDefinitionRef(sharedRule.id, 1), sourceBindings(other), mapOf("minimum" to Calculation.Scalar(ScalarValueType.NUMBER, "10")), "golden"))
        revisedRule = f.members.revise(GovernanceRevisionRequest(sharedRule.id, 1, MemberDefinitionCreateRequest(sharedRule.code, sharedRule.displayName,
            OrderMemberDefinitions.computed("minimum_v2", true), "独立修订门槛参数", "trade", "golden")))
        f.activate("MEMBER_DEFINITION", sharedRule.id, 2)
        sharedV2 = f.adoptions.adoptMemberDefinition(MemberDefinitionAdoptRequest("travel.shared", 1, "qualified_v2", "新口径采用", MemberDefinitionRef(sharedRule.id, 2), sourceBindings(other), mapOf("minimum_v2" to Calculation.Scalar(ScalarValueType.NUMBER, "20")), "golden"))
        val sharedScenario = f.scenarios.createScenario(ScenarioCreateRequest("travel.shared", 1, "orders", "双口径成员查询", "analyst", "精确共享版本", "golden")).scenario
        for (source in listOf(sharedV1, sharedV2)) f.scenarios.createScenarioMemberSourceRef(ScenarioMemberSourceRefCreateRequest(sharedScenario.id, 1, source.id, 1,
            MemberQueryPermission(listOf("order", "amount"), emptyList(), emptyMap(), false, emptyList())))
        publicRows = member("public_orders", OrderMemberDefinitions.computed("lower", false), sourceBindings(inner, mapOf("window" to Calculation.Argument("window"))), mapOf("lower" to Calculation.Scalar(ScalarValueType.NUMBER, "20")))
        val body = sumDefinition.calculation
        val origin = body.memberSlots.single()
        val internal = MetricCalculation(Calculation(emptyList(), listOf(origin, Calculation.MemberSlot("origin", origin.entity, origin.role, origin.shape, origin.outputs, null)), emptyList(), emptyList(), emptyMap(), Calculation.Scope(body.computation.root, mapOf("formed" to Calculation.InputScope("ROOT", Calculation.Scope(Calculation.Members("internal", "origin",
            listOf(Calculation.MemberProjection("order", "order"), Calculation.MemberProjection("amount", "amount")), null, emptyMap(), MemberCondition.Unrestricted()), emptyMap(), emptyList()))), body.computation.stages)),
            "value", emptyList(), GovernanceOrderFixture.money())
        for ((code, definition) in linkedMapOf("sum" to sumDefinition, "average" to CustomerCalculationDefinitions.amount("AVG", MemberShape.RECORDS), "private_sum" to sumDefinition, "internal_sum" to internal)) {
            val metric = createMetric("formed_$code", definition)
            val members = when (code) {
                "private_sum" -> mapOf("rows" to source(inner, july))
                "internal_sum" -> mapOf("rows" to CalculationBindings.LocalRef("formed", OrderMemberDefinitions.slots), "origin" to source(inner, july))
                else -> mapOf("rows" to CalculationBindings.InputParameter())
            }
            metrics[code] = f.adoptions.createSubjectAreaMetricRef(SubjectAreaMetricRefCreateRequest("travel.trade", 1, code, metric.displayName, meaning, metric.id, 1,
                CalculationBindings(emptyMap(), members, emptyMap(), emptyMap(), emptyMap()), emptyMap(), "golden")).subjectAreaMetricRef
        }
        raw = member("raw_orders", OrderMemberDefinitions.projection(MemberShape.RAW_BAG))
        rawMetric = createMetric("raw_order_sum", CustomerCalculationDefinitions.amount("SUM", MemberShape.RAW_BAG))
        f.scenarios.createScenario(ScenarioCreateRequest("travel.trade", 1, "formed", "形成订单分析", "analyst", "完整成员链", "golden"))
        metrics.values.forEachIndexed { i, adoption -> f.scenarios.createScenarioMetricRef(ScenarioMetricRefCreateRequest("travel.trade.formed", 1, adoption.id, 1, false, i + 1)) }
        f.scenarios.createScenarioMemberSourceRef(ScenarioMemberSourceRefCreateRequest("travel.trade.formed", 1, publicRows.id, 1,
            MemberQueryPermission(listOf("order", "amount"), listOf("candidate_amount"), mapOf("amount" to listOf(MemberCondition.Operator.GTE)), false, emptyList())))
        f.scenarios.createScenarioMemberSourceRef(ScenarioMemberSourceRefCreateRequest("travel.trade.formed", 1, raw.id, 1, MemberQueryPermission(listOf("amount"), emptyList(), emptyMap(), false, emptyList())))
        queries = QueryDefinitionService(JdbcQueryDefinitionRepository(database, RuntimeDeploymentLimits.io), JdbcPublishedQueryEntryProvider(database),
            NativeValidator { intent, capability, executionMode -> QueryNativeValidation.validate(intent, capability, f.connectors, executionMode) })
    }

    fun publish() { f.activate("SUBJECT_AREA", "travel.trade"); f.activate("SUBJECT_AREA", "travel.shared"); f.build("formed_orders"); f.builder().build(SubjectAreaReleaseBuildRequest("shared_orders", "1", "双口径发布", "travel.shared", 1, "golden", f.sourceAdoptions)) }
    fun publication() = f.publication("formed_orders", scenario = "travel.trade.formed")
    fun sharedPublication() = f.publication("shared_orders", scenario = "travel.shared.orders")
    fun member(code: String, definition: MemberSourceDefinition, bindings: CalculationBindings = CalculationBindings.empty(), arguments: Map<String, Calculation.Value> = emptyMap()) =
        f.adoptions.createMemberSource(MemberSourceCreateRequest("travel.trade", 1, code, code, definition, bindings, arguments, "golden"))
    fun createMetric(code: String, definition: MetricCalculation): MetricRecord = f.metrics.createMetricCalculation(MetricCalculationCreateRequest(code, code, "trade", definition, "真实成员聚合", meaning, "golden")).metric.also { f.activate("METRIC", it.id) }
    fun freeze(scope: QueryComputation.Scope): QueryDefinitionView {
        val publication = publication()
        return queries.save(QueryDefinitionCommand("computed_members_${++sequence}", null, requireNotNull(publication.queryCapability.capabilityHash), "实际形成订单", f.intent(publication, scope), null), "golden").let { queries.requireByIdAndVersion(it.definitionId, it.definitionVersion) }
    }
    fun execute(scope: QueryComputation.Scope) = f.execute(publication(), freeze(scope).intent.computation)
    fun formed(arguments: Map<String, Calculation.Value>, outputs: List<QueryComputation.Slot> = listOf(QueryComputation.Slot("order", "order"), QueryComputation.Slot("amount", "amount")),
        condition: MemberCondition = MemberCondition.Unrestricted()) = QueryComputation.Members("formed", MemberRef(publicRows.id, 1), arguments, outputs, null, emptyMap(), condition, emptyMap(), emptyMap())
    fun source(source: MemberSourceRecord, window: Calculation.Range) = CalculationBindings.SourceCall(MemberRef(source.id, 1), mapOf("window" to window), OrderMemberDefinitions.slots, emptyMap(), emptyMap())
    fun sourceBindings(source: MemberSourceRecord, arguments: Map<String, Calculation.Binding> = emptyMap()) = CalculationBindings(emptyMap(), mapOf("rows" to CalculationBindings.SourceCall(MemberRef(source.id, 1), arguments, OrderMemberDefinitions.slots, emptyMap(), emptyMap())), emptyMap(), emptyMap(), emptyMap())
}
