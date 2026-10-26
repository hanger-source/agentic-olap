package com.olap.prototype.management.governance

import com.olap.prototype.metric.MetricAggregation
import com.olap.prototype.calculation.*
import com.olap.prototype.contract.InputContractException
import com.olap.prototype.engine.EngineExecutionException
import com.olap.prototype.event.EventIdentity
import com.olap.prototype.event.EventRef
import com.olap.prototype.event.RelationAccessPath
import com.olap.prototype.governance.contract.*
import com.olap.prototype.member.*
import com.olap.prototype.metric.MetricValueContract
import com.olap.prototype.query.*
import com.olap.prototype.query.contract.*
import com.olap.prototype.query.definition.*
import com.olap.prototype.release.catalog.JdbcPublishedQueryEntryProvider
import com.olap.prototype.storage.PlatformDatabase
import com.olap.prototype.integration.RuntimeDeploymentLimits
import com.olap.prototype.time.TemporalWindow
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path

class PublishedRelationMembersTest {
    @Test
    fun inlineNominalUnitsExecuteWithoutPublishedMetricsAndKeepQuantityAndStageChecks(@TempDir directory: Path) {
        PlatformDatabase.inMemory().use { database ->
            val f = GovernanceOrderFixture(database, directory, listOf(
                "INSERT INTO orders VALUES ('1',100,'A','2026-07-01','p1'),('2',300,'A','2026-07-02','p1')," +
                    "('3',NULL,'A','2026-07-02','p2'),('4',NULL,'B','2026-07-01','p3')," +
                    "('5',500,'C','2026-07-01','p4'),('6',9000,'A','2026-08-01','p5')"))
            f.seedDefinitions(); f.approveDefinitions()
            f.subjects.createSubjectArea(SubjectAreaCreateRequest("travel", 1, "trade", "记录分析", "analyst", "本次内联计算", "golden"))
            val nativeUnit = MetricValueContract.UnitIdentity("example", "source-scale", 1)
            val reportUnit = MetricValueContract.UnitIdentity("example", "report-scale", 1)
            val ratioUnit = MetricValueContract.UnitIdentity("example", "report-per-participant", 1)
            val otherUnit = MetricValueContract.UnitIdentity("example", "other-scale", 1)
            val raw = MetricValueContract(MetricValueContract.Fixed(nativeUnit), MetricValueContract.Exact(),
                MetricValueContract.Decimal(18, 2, MetricValueContract.Rounding.NONE), emptyMap(), MetricValueContract.Overflow.FAIL)
            fun floating(unit: MetricValueContract.UnitIdentity, equations: Map<String, MetricValueContract.UnitEquation>) = MetricValueContract(
                MetricValueContract.Fixed(unit), MetricValueContract.NativeFloating(), MetricValueContract.BinaryFloat(64), equations, MetricValueContract.Overflow.FAIL)
            val local = RelationAccessPath(emptyList())
            val member = f.adoptions.createMemberSource(MemberSourceCreateRequest(GovernanceOrderFixture.SUBJECT, 1, "records", "原尺度记录",
                MemberSourceDefinition.Projection(EventRef(GovernanceOrderFixture.EVENT, 1), MemberResult("记录", "record", MemberShape.RAW_BAG,
                    emptyList(), mapOf(
                        "group" to MemberSourceDefinition.PropertyOutput("分组", "group", ScalarValueType.TEXT, "order_fact.destination", 1, local, null),
                        "participant" to MemberSourceDefinition.PropertyOutput("参与者引用", "participant", ScalarValueType.TEXT, "order_fact.customer_id", 1, local, null),
                        "amount" to MemberSourceDefinition.PropertyOutput("原数值", "amount", ScalarValueType.NUMBER, "order_fact.pay_amount", 1, local, raw),
                        "paid" to MemberSourceDefinition.PropertyOutput("记录日期", "day", ScalarValueType.DATE, "order_fact.pay_time", 1, local, null)),
                    mapOf("group" to listOf(MemberCondition.Operator.IN)), "paid", emptyMap(), "保留原人口及NULL")),
                CalculationBindings.empty(), emptyMap(), "golden"))
            val scenario = f.scenarios.createScenario(ScenarioCreateRequest(GovernanceOrderFixture.SUBJECT, 1, "inline", "内联记录报告", "analyst", "不采用指标", "golden")).scenario
            f.scenarios.createScenarioMemberSourceRef(ScenarioMemberSourceRefCreateRequest(scenario.id, 1, member.id, 1,
                MemberQueryPermission(listOf("group", "participant", "amount"), emptyList(), mapOf("group" to listOf(MemberCondition.Operator.IN)), true, emptyList())))
            val expressions = ExpressionAuthorization.current()
            f.scenarios.createScenarioPipelineRule(ScenarioPipelineRuleCreateRequest(scenario.id, 1, PostOperationContract.DERIVE, 1,
                StageAuthorization(PostOperationContract.DERIVE, emptySet(), emptySet(), ExpressionAuthorization(
                    expressions.kinds - ExpressionAuthorization.Kind.ROUND, expressions.operators, expressions.functions, expressions.castTypes, expressions.literalTypes))))
            f.scenarios.createScenarioPipelineRule(ScenarioPipelineRuleCreateRequest(scenario.id, 1, PostOperationContract.REGROUP, 2,
                StageAuthorization.current(PostOperationContract.REGROUP)))
            f.activate("SUBJECT_AREA", GovernanceOrderFixture.SUBJECT)
            assertEquals(0, f.build("inline_units").metricsFrozen)
            val publication = f.publication("inline_units", scenario = scenario.id)
            assertTrue(publication.queryCapability.metricCandidates.isEmpty())
            val report = floating(reportUnit, emptyMap())
            val ratio = floating(ratioUnit, mapOf("per_participant" to MetricValueContract.FixedBinary(
                MetricValueContract.BinaryOperator.DIVIDE, reportUnit, MetricValueContract.ONE, ratioUnit)))
            val root = Calculation.Members("records", Calculation.MemberSource(MemberRef(member.id, 1)), listOf("group", "participant", "amount").map { Calculation.MemberProjection(it, it, null) }, (TemporalWindow(TemporalWindow.ValueType.DATE, TemporalWindow.BOUNDARY, "2026-07-01", "2026-07-03"))?.let { Calculation.Range(it) }, emptyMap(), MemberCondition.Predicate("group", MemberCondition.Operator.IN, listOf("A", "B", "D").map { Calculation.Scalar(ScalarValueType.TEXT, it) }), emptyMap(), emptyMap(), emptyMap())
            fun divide(left: ResultExpression, right: ResultExpression) = ResultExpression.ResultOperation(ResultExpression.ResultOperator.DIVIDE, listOf(left, right))
            fun scope(factor: String = "100", from: MetricValueContract.UnitIdentity = nativeUnit,
                declaredOutput: MetricValueContract.UnitIdentity = reportUnit): Calculation.Scope {
                val scaled = floating(declaredOutput, mapOf("scale" to MetricValueContract.Scale(
                    MetricValueContract.BinaryOperator.DIVIDE, from, reportUnit, "100")))
                return Calculation.Scope(root, emptyMap(), listOf(
                    PostOperationContract.Derive(ResultExpression.ResultNumeric(divide(ResultExpression.ResultSymbol("amount"),
                        ResultExpression.ResultLiteral(BigDecimal(factor))), scaled, "scale"), "row_report", "逐行原尺度除100"),
                    PostOperationContract.Regroup(PostOperationContract.ResultGrouping(listOf("group"), listOf(
                        PostOperationContract.ResultAggregate("row_report", ResultAggregation.Sum(report, ResultAggregation.NullPolicy.IGNORE), "total", "报告数值之和"),
                        PostOperationContract.ResultAggregate("participant", ResultAggregation.Count(true, ResultAggregation.NullPolicy.IGNORE), "participants", "同人口参与者引用去重数")), null)),
                    PostOperationContract.Derive(ResultExpression.ResultNumeric(divide(ResultExpression.ResultSymbol("total"),
                        ResultExpression.ResultSymbol("participants")), ratio, "per_participant"), "ratio", "完成量除同人口计数")))
            }
            val rows = f.execute(publication, scope()).result.resultFrame.rows.associateBy { it["group"] }
            assertEquals(setOf("A", "B"), rows.keys)
            val a = rows.getValue("A")
            assertEquals(4.0, (a["total"] as Number).toDouble())
            assertEquals(2L, (a["participants"] as Number).toLong())
            assertEquals(2.0, (a["ratio"] as Number).toDouble())
            val b = rows.getValue("B")
            assertNull(b["total"]); assertEquals(1L, (b["participants"] as Number).toLong()); assertNull(b["ratio"])

            // 同一正式发布下，错误方程在来源提交之前失败，位置来自实际计算节点。
            for ((invalid, expectedRule) in listOf(scope(factor = "10") to InputContractException.Rule.RESULT_UNIT_EQUATION_MISMATCH,
                scope(from = otherUnit) to InputContractException.Rule.RESULT_UNIT_EQUATION_MISMATCH,
                scope(declaredOutput = otherUnit) to InputContractException.Rule.RESULT_UNIT_MISMATCH)) {
                val failure = assertThrows(EngineExecutionException::class.java) { f.execute(publication, invalid) }
                assertEquals("ANALYZE_STAGES", failure.stage)
                assertEquals(false, failure.sourceExecutionStarted)
                val contract = generateSequence<Throwable>(failure) { it.cause }.filterIsInstance<InputContractException>().single()
                assertEquals(expectedRule, contract.rule)
                assertEquals(if (expectedRule == InputContractException.Rule.RESULT_UNIT_MISMATCH) listOf("input", "postOps", 0, "expression", "valueContract", "unit")
                    else listOf("input", "postOps", 0, "expression", "expression"), contract.details["fieldPath"])
            }
            val approved = scope()
            val forbidden = Calculation.Scope(approved.root, approved.inputs, approved.stages + PostOperationContract.Derive(
                ResultExpression.ResultNumeric(ResultExpression.ResultRound(ResultExpression.ResultSymbol("ratio"), 1, MetricValueContract.Rounding.HALF_UP), ratio, null),
                "rounded", "场景未批准ROUND"))
            val denied = assertThrows(EngineExecutionException::class.java) { f.execute(publication, forbidden) }
            assertEquals("AUTHORIZE_INPUT", denied.stage)
            assertEquals("EXECUTABLE_QUERY_NOT_PUBLISHED", denied.code)
            assertTrue(denied.message.contains("ROUND"), denied.message)
            assertNull(denied.compiledQuery)
        }
    }

    @Test
    fun relatedExistenceFiltersPreserveTheAmountPopulation(@TempDir directory: Path) {
        PlatformDatabase.inMemory().use { database ->
            val f = GovernanceOrderFixture(database, directory, listOf("INSERT INTO orders VALUES ('1',10,'A','2026-07-01','c1'),('2',20,'A','2026-07-02','c1'),('3',70,'A','2026-07-03','c2'),('4',5,'B','2026-07-01','c3'),('5',15,'B','2026-07-02','c3'),('6',100,'B','2026-08-01','c4')"))
            f.seedDefinitions()
            val columns = f.physical.physicalTableColumnSnapshots(f.physicalId, 1).associate { it.columnName to it.id }
            fun unit(code: String, type: LogicType, primary: Boolean = false) = LogicalTableCreateRequest.LogicUnitInput(code, code, LogicUnitSourceKind.COLUMN,
                null, type, null, false, primary, false, true, code == "pay_amount",
                listOf(LogicalTableCreateRequest.SourceRefInput(columns.getValue(code), 1, LogicUnitSourceRefRole.DIRECT_SOURCE)))
            f.logical.createLogicalTable(LogicalTableCreateRequest(f.physicalId, 1, "registered_orders", "登记订单关系", LogicalTableRole.FACT,
                "独立关联记录位置", "data", "golden", listOf(unit("order_id", LogicType.STRING, true), unit("customer_id", LogicType.STRING), unit("pay_amount", LogicType.NUMBER))))
            f.topologies.createTopologyLogicalTableRef(TopologyLogicalTableRefCreateRequest(GovernanceOrderFixture.TOPOLOGY, 1, "registered_orders", 1, TopologyNodeRole.BRIDGE_NODE))
            val path = f.topologies.createJoinPath(JoinPathCreateRequest(GovernanceOrderFixture.TOPOLOGY, 1, "same_customer", "order_fact", 1,
                "registered_orders", 1, "INNER", "MANY_TO_MANY", null, "SQL_EQUALS_NULL_NEVER_MATCHES", "golden",
                listOf(JoinPathCreateRequest.KeyRefInput("order_fact.customer_id", 1, "registered_orders.customer_id", 1)))).joinPath
            val topology = f.topologies.createTopology(TopologyCreateRequest("registered", "登记关系", "trade.registered", "登记订单实现空间",
                "registered_orders", 1, "data", "关联源主干", "golden")).topology
            fun identity(unit: String) = EventIdentity(listOf(EventIdentity.LogicUnitRef(unit, 1)), emptyMap())
            val registered = f.events.createEvent(EventCreateRequest(topology.id, 1, "orders", "登记订单", "data", null, null,
                identity("registered_orders.order_id"), emptyMap(), listOf(EventCreateRequest.LogicUnitRefInput("registered_orders.pay_amount", 1)),
                emptyList(), "关联记录", "golden")).event
            val parent = f.events.createEvent(EventCreateRequest(GovernanceOrderFixture.TOPOLOGY, 1, "related_orders", "可筛选的订单金额", "data", null, null,
                identity("order_fact.order_id"), mapOf("customer_orders" to RelationAccessPath(listOf(RelationAccessPath.JoinRef(path.id, 1)))), emptyList(),
                listOf(EventCreateRequest.LogicUnitRefInput("order_fact.pay_amount", 1)), "关联存在性筛选", "golden")).event
            val metric = f.metrics.createMetric(MetricCreateRequest(
                code = "related_amount",
                displayName = "订单金额",
                owner = "data",
                definition = MetricDefinition.FactMeasure(
                    event = com.olap.prototype.event.EventRef(parent.id, 1),
                    input = GovernanceOrderFixture.ref("order_fact.pay_amount"),
                    aggregation = MetricAggregation.SUM,
                    distinct = false,
                    privatePredicate = null,
                    roles = emptyMap(),
                    snapshotRule = null,
                    valueContract = GovernanceOrderFixture.money(),
                ),
                description = "关联记录数量不放大订单金额",
                businessMeaning = GovernanceOrderFixture.amountMeaning(),
                createdBy = "golden"
            )).metric
            f.activate("LOGICAL_TABLE", "registered_orders"); f.activate("TOPOLOGY", topology.id); f.activate("EVENT", registered.id)
            f.approveDefinitions(); f.seedSubjectDraft(); f.activateSubjectGraph(false)
            f.activate("EVENT", parent.id); f.activate("METRIC", metric.id); f.adopt(metric.id)
            val local = RelationAccessPath(emptyList())
            val member = f.adoptions.createMemberSource(MemberSourceCreateRequest(GovernanceOrderFixture.SUBJECT, 1, "registered_orders", "关联登记订单",
                MemberSourceDefinition.Projection(EventRef(registered.id, 1), MemberResult("订单", "order", MemberShape.RECORDS, listOf("order"), mapOf(
                    "order" to MemberSourceDefinition.PropertyOutput("订单", "order", ScalarValueType.TEXT, "registered_orders.order_id", 1, local, null),
                    "amount" to MemberSourceDefinition.PropertyOutput("金额", "amount", ScalarValueType.NUMBER, "registered_orders.pay_amount", 1, local, GovernanceOrderFixture.money())),
                    mapOf("amount" to listOf(MemberCondition.Operator.GT)), null, emptyMap(), null)), CalculationBindings.empty(), emptyMap(), "golden"))
            val scenario = f.scenarios.createScenario(ScenarioCreateRequest(GovernanceOrderFixture.SUBJECT, 1, "related", "关联订单筛选", "analyst", "同一发布改变阈值", "golden")).scenario
            f.grantMetric(metric.id, scenario.id)
            f.scenarios.createScenarioMemberSourceRef(ScenarioMemberSourceRefCreateRequest(scenario.id, 1, member.id, 1,
                MemberQueryPermission(listOf("order"), emptyList(), mapOf("amount" to listOf(MemberCondition.Operator.GT)), false, emptyList())))
            val filter = f.scenarios.createScenarioRelationFilter(ScenarioRelationFilterCreateRequest(scenario.id, 1, "customer_orders", "客户关联订单", parent.id, 1,
                "customer_orders", member.id, 1, RelationFilterPermission(mapOf("amount" to listOf(MemberCondition.Operator.GT)), false, emptyList()), false, 1)).definition
            f.activate("SUBJECT_AREA", GovernanceOrderFixture.SUBJECT); f.build("related_release")
            val publication = f.publication("related_release", scenario = scenario.id)
            val definitions = QueryDefinitionService(JdbcQueryDefinitionRepository(database, RuntimeDeploymentLimits.io), JdbcPublishedQueryEntryProvider(database),
                NativeValidator { intent, capability, executionMode -> QueryNativeValidation.validate(intent, capability, f.connectors, executionMode) })
            for ((threshold, expected) in linkedMapOf("0" to "220", "15" to "200", "40" to "170", "90" to "100")) {
                val condition = DimensionCondition.Exists("eligible_order", filter.relation.id,
                    MemberCondition.Predicate("amount", MemberCondition.Operator.GT, listOf(Calculation.Scalar(ScalarValueType.NUMBER, threshold))), null, emptyMap())
                val scope = Calculation.Scope(Calculation.Measures(listOf(Calculation.InstanceCall("amount", f.adoptions.metricAdoption(MetricAdoption.identity(GovernanceOrderFixture.SUBJECT, metric.code), 1).instance, outputAlias = "total")), emptyList(), emptyList(), null, emptyMap(), Calculation.Condition(condition), DimensionCondition.Unrestricted(), emptyList(), RowPopulationContract.calls(listOf("amount")), null), emptyMap(), emptyList())
                val saved = definitions.save(QueryDefinitionCommand("related_$threshold", null, requireNotNull(publication.queryCapability.capabilityHash), "关联订单阈值", f.intent(publication, scope), null), "golden")
                val reloaded = definitions.requireByIdAndVersion(saved.definitionId, saved.definitionVersion).intent.computation
                val rows = f.execute(publication, reloaded).result.resultFrame.rows
                assertEquals(1, rows.size)
                assertEquals(0, BigDecimal(expected).compareTo(BigDecimal(rows.single().cells.single().value.toString())), "threshold=$threshold")
            }
        }
    }
}
