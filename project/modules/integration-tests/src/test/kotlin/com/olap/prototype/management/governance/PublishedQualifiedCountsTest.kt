package com.olap.prototype.management.governance

import com.olap.prototype.calculation.*
import com.olap.prototype.contract.JdbcColumnType
import com.olap.prototype.engine.EngineExecutionException
import com.olap.prototype.engine.executable.ExecutableBusinessResult
import com.olap.prototype.event.EventIdentity
import com.olap.prototype.event.EventRef
import com.olap.prototype.event.RelationAccessPath
import com.olap.prototype.governance.contract.*
import com.olap.prototype.integration.RuntimeDeploymentLimits
import com.olap.prototype.integration.TestConnectors
import com.olap.prototype.member.*
import com.olap.prototype.metric.MetricBusinessMeaning
import com.olap.prototype.metric.MetricValueContract
import com.olap.prototype.query.*
import com.olap.prototype.query.PostOperationContract.Derive
import com.olap.prototype.query.PostOperationContract.JoinInput
import com.olap.prototype.query.PostOperationContract.PostFilter
import com.olap.prototype.query.PostOperationContract.Project
import com.olap.prototype.query.PostOperationContract.Regroup
import com.olap.prototype.query.PostOperationContract.ResultAggregate
import com.olap.prototype.query.PostOperationContract.ResultGrouping
import com.olap.prototype.query.PostOperationContract.ValidatedOperation
import com.olap.prototype.query.ResultExpression.*
import com.olap.prototype.query.contract.MetricAdoption
import com.olap.prototype.query.contract.MemberQueryPermission
import com.olap.prototype.query.contract.StageAuthorization
import com.olap.prototype.query.definition.*
import com.olap.prototype.release.catalog.JdbcPublishedQueryEntryProvider
import com.olap.prototype.storage.PlatformDatabase
import com.olap.prototype.warehouse.contract.SourceAdoptionRef
import com.olap.prototype.warehouse.inspection.JdbcWarehouseInspector
import com.olap.prototype.warehouse.jdbc.JdbcSourceInspection
import com.olap.prototype.warehouse.onboarding.WarehouseDiscoveryCommand
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path

class PublishedQualifiedCountsTest {
    @Test
    fun publishesTwoComputedQualificationsAndFiveCountsWithCoreMeans(@TempDir directory: Path) {
        val metadata = directory.resolve("qualified-count-platform")
        var sourceAdoptions: List<SourceAdoptionRef> = emptyList()
        lateinit var qualifiedReference: MemberRef
        PlatformDatabase.openSingleInstance(metadata).use { database ->
            val f = GovernanceOrderFixture(database, directory, listOf(
                "CREATE TABLE main.account_profiles(account VARCHAR, is_latest BOOLEAN)",
                """INSERT INTO main.account_profiles VALUES
                    ('A',TRUE),('A',TRUE),('A',FALSE),('B',TRUE),('C',TRUE),('D',FALSE),
                    ('',TRUE),(NULL,TRUE),('F',NULL)
                """.trimIndent(),
                "CREATE TABLE main.account_states(account VARCHAR, entry_ref VARCHAR, observed_label VARCHAR NOT NULL, state_code BIGINT, partition_label VARCHAR)",
                """INSERT INTO main.account_states VALUES
                    ('A','r1','20240101',1,'p1'),('A','r1','20240101',1,'p1'),
                    ('A','r1','20240102',1,'p2'),('A','r2','20240102',1,'p2'),
                    ('A',NULL,'20240103',1,'p3'),('A','other','20240104',0,'p4'),
                    ('A','unknown','20240105',NULL,'p5'),
                    ('A','before','20231231',1,'p0'),('A','after','20240120',1,'p20'),
                    ('B','b1','20240101',0,'p1'),('B',NULL,'20240102',NULL,'p2'),
                    ('D','d1','20240101',1,'p1'),('E','e1','20240101',1,'p1'),
                    ('','','20240101',1,'p1'),('','','20240101',1,'p1'),('','','20240119',1,'p19'),
                    (NULL,'null_account','20240101',1,'p1'),('F','f1','20240101',1,'p1')
                """.trimIndent()))
            f.seed()
            f.activateSubjectGraph(includeSubject = false)
            val profileEvent = register(f, "account_profiles", linkedMapOf("account" to ScalarValueType.TEXT, "is_latest" to ScalarValueType.BOOLEAN))
            val stateTypes = linkedMapOf("account" to ScalarValueType.TEXT, "entry_ref" to ScalarValueType.TEXT,
                "observed_label" to ScalarValueType.TEXT, "state_code" to ScalarValueType.NUMBER, "partition_label" to ScalarValueType.TEXT)
            val stateEvent = register(f, "account_states", stateTypes)
            f.dimensions.createDimension(DimensionCreateRequest(accountConcept, "中性账户", "账户", "data", "精确TEXT身份，空串有意义", "golden"))
            val analysis = f.analysis.createAnalysisDimension(AnalysisDimensionCreateRequest(GovernanceOrderFixture.SUBJECT, 1,
                "account", "账户", "两个来源显式同键", "account", "账户", "golden")).analysisDimension
            val eventBindings = listOf("account_profiles" to profileEvent, "account_states" to stateEvent).associate { (table, event) ->
                table to f.dimensions.createDimensionEventBinding(DimensionEventBindingCreateRequest(accountConcept, 1, event.eventId, 1,
                    "$table.account", 1, null, RelationAccessPath(emptyList()), emptyMap(), "golden")).binding
            }
            val proof = f.analysis.createAnalysisDimensionValueAlignment(AnalysisDimensionValueAlignmentCreateRequest(analysis.id, 1,
                "account", "PROVEN_SAME_KEY", f.mapper.writeValueAsString(mapOf(
                    "basis" to "来源作者确认两列逐字表达同一账户键，不做大小写、空白或空串归一化；NULL不构成身份",
                    "valueType" to "TEXT",
                    "positions" to eventBindings.values.map { binding -> mapOf(
                        "eventId" to binding.eventId, "eventVersion" to binding.eventVersion,
                        "logicUnitId" to binding.logicUnitId, "logicUnitVersion" to binding.logicUnitVersion,
                        "bindingId" to binding.id, "bindingVersion" to 1) })), "golden"))
            val mappings = eventBindings.values.map { binding ->
                f.analysis.createAnalysisDimensionEventMapping(AnalysisDimensionEventMappingCreateRequest(analysis.id, 1, "account", binding.id, 1,
                    "PROVEN_SAME_KEY", proof.id)).mapping.id
            }
            f.activate("DIMENSION", accountConcept)
            eventBindings.values.forEach { f.activate("DIMENSION_EVENT_BINDING", it.id) }
            val dimensions = mapOf("account" to CalculationBindings.Dimension(axis, "account", mappings))
            fun projection(table: String, event: EventRef, entity: String, role: String, types: Map<String, ScalarValueType>,
                filters: Map<String, List<MemberCondition.Operator>>): MemberSourceRecord {
                val outputs = types.mapValues { (name, type) ->
                    if (name == "account") MemberSourceDefinition.DimensionOutput("账户", "account", type, accountConcept, 1,
                        eventBindings.getValue(table).id, 1, null)
                    else MemberSourceDefinition.PropertyOutput(name, propertyRoles.getValue(name), type, "$table.$name", 1,
                        RelationAccessPath(emptyList()), if (name == "state_code") count else null)
                }
                return f.adoptions.createMemberSource(MemberSourceCreateRequest(GovernanceOrderFixture.SUBJECT, 1, table, table,
                    MemberSourceDefinition.Projection(event, MemberResult(entity, role, MemberShape.RAW_BAG, emptyList(), outputs,
                        filters, null, emptyMap(), "保留真实重复和NULL")), CalculationBindings.empty(), emptyMap(), "golden"))
            }
            val rawProfile = projection("account_profiles", profileEvent, "ProfileRawObservation", "profile_raw",
                linkedMapOf("account" to ScalarValueType.TEXT, "is_latest" to ScalarValueType.BOOLEAN),
                mapOf("is_latest" to listOf(MemberCondition.Operator.EQ)))
            val rawState = projection("account_states", stateEvent, "StateRawObservation", "state_raw", stateTypes,
                mapOf("observed_label" to listOf(MemberCondition.Operator.BETWEEN)))
            fun source(value: MemberSourceRecord, outputs: List<String>, arguments: Map<String, Calculation.Binding> = emptyMap()) =
                CalculationBindings.SourceCall(MemberRef(value.id, 1), arguments, outputs.associateWith { it }, emptyMap(), emptyMap())
            fun adoptComputed(code: String, definition: MemberSourceDefinition.Computed, members: Map<String, CalculationBindings.Member>): MemberSourceRecord {
                val saved = f.members.create(MemberDefinitionCreateRequest(code, code, definition, "正式共享资格计算", "data", "golden"))
                f.activate("MEMBER_DEFINITION", saved.id)
                return f.adoptions.adoptMemberDefinition(MemberDefinitionAdoptRequest(GovernanceOrderFixture.SUBJECT, 1, code, code,
                    MemberDefinitionRef(saved.id, 1), CalculationBindings(dimensions, members, emptyMap(), emptyMap(), emptyMap()), emptyMap(), "golden"))
            }
            val profile = adoptComputed("current_profile_accounts", profileDefinition(), mapOf("profile" to source(rawProfile, listOf("account", "is_latest"))))
            val qualified = adoptComputed("qualified_state", qualifiedDefinition(), mapOf(
                "state" to source(rawState, stateColumns), "profile_accounts" to source(profile, listOf("account"))))
            qualifiedReference = MemberRef(qualified.id, 1)
            f.scenarios.createScenarioMemberSourceRef(ScenarioMemberSourceRefCreateRequest(GovernanceOrderFixture.SCENARIO, 1, qualified.id, 1,
                MemberQueryPermission(stateColumns, emptyList(), emptyMap(), false, emptyList())))
            metricCodes.forEachIndexed { index, code ->
                val metric = f.metrics.createMetricCalculation(MetricCalculationCreateRequest(code, code, "data", metricDefinition(code),
                    "保存完整来源、期间、资格和计数政策", MetricBusinessMeaning("同一资格袋的独立完整计算", "个", "期间内存在状态且有当前档案的账户", emptyList()), "golden")).metric
                f.activate("METRIC", metric.id)
                f.adopt(metric.id, bindings = CalculationBindings(dimensions, mapOf("qualified_state" to source(qualified, stateColumns,
                    mapOf("begin_label" to Calculation.Argument("begin_label"), "end_label" to Calculation.Argument("end_label")))), emptyMap(), emptyMap(), emptyMap()))
                f.grantMetric(metric.id, order = index + 2)
            }
            f.scenarios.createScenarioAnalysisDimensionRef(ScenarioAnalysisDimensionRefCreateRequest(GovernanceOrderFixture.SCENARIO, 1,
                analysis.id, 1, "account", true, 3))
            f.scenarios.createScenarioPipelineRule(ScenarioPipelineRuleCreateRequest(GovernanceOrderFixture.SCENARIO, 1,
                "REGROUP", 1, StageAuthorization.current("REGROUP")))
            f.activate("SUBJECT_AREA", GovernanceOrderFixture.SUBJECT)
            sourceAdoptions = f.sourceAdoptions
        }

        // 重开后从正式保存的定义与采用构建同一发布，再保存并读取公开查询。
        PlatformDatabase.openSingleInstance(metadata).use { database ->
            val f = GovernanceOrderFixture(database, directory)
            f.sourceAdoptions = sourceAdoptions
            f.build("qualified_count_release")
            val publication = f.publication("qualified_count_release")
            val period = mapOf<String, Calculation.Value>("begin_label" to Calculation.Scalar(ScalarValueType.TEXT, "20240101"),
                "end_label" to Calculation.Scalar(ScalarValueType.TEXT, "20240119"))
            fun call(code: String, alias: String = code): Calculation.Call {
                val instance = f.adoptions.metricAdoption(MetricAdoption.identity(GovernanceOrderFixture.SUBJECT, code), 1).instance
                val arguments = LinkedHashMap(period)
                if (code in meanCodes) arguments["requested_days"] = Calculation.Scalar(ScalarValueType.NUMBER, "19")
                return Calculation.InstanceCall(code, instance, outputAlias = alias, arguments = arguments)
            }
            fun measures(calls: List<Calculation.Call>) = Calculation.Scope(Calculation.Measures(calls, listOf(Calculation.PublishedDimension(axis)),
                emptyList(), null, emptyMap(), Calculation.Condition(DimensionCondition.Unrestricted()), DimensionCondition.Unrestricted(), emptyList(),
                RowPopulationContract.calls(calls.map { it.callId }), null), emptyMap(), emptyList())
            val queries = QueryDefinitionService(JdbcQueryDefinitionRepository(database, RuntimeDeploymentLimits.io), JdbcPublishedQueryEntryProvider(database),
                NativeValidator { intent, capability, mode -> QueryNativeValidation.validate(intent, capability, f.connectors, mode) })
            val saved = queries.save(QueryDefinitionCommand("qualified_counts", null, requireNotNull(publication.queryCapability.capabilityHash),
                "两层资格、五个完整测量", f.intent(publication, measures(metricCodes.map { call(it) })), null, QueryExecutionMode.CORE_FINALIZATION), "golden")
            val reopened = queries.requireByIdAndVersion(saved.definitionId, saved.definitionVersion)
            val result = f.execute(publication, reopened.intent.computation, QueryExecutionMode.CORE_FINALIZATION).businessResult
            assertEquals(setOf("account") + metricCodes, result.columns.map { it.symbol.name }.toSet())
            val positions = result.columns.mapIndexed { index, column -> column.symbol.name to index }.toMap()
            fun scalar(row: List<ExecutableBusinessResult.Value>, name: String): String =
                (row[positions.getValue(name)] as ExecutableBusinessResult.ScalarValue).value
            val actual = result.rows.associate { row -> scalar(row, "account") to metricCodes.map { BigDecimal(scalar(row, it)) } }
            val expected = mapOf(
                "A" to listOf("2", "1", "3", "0.157894736842105263", "0.263157894736842105"),
                "B" to listOf("0", "1", "0", "0", "0.105263157894736842"),
                "" to listOf("1", "1", "2", "0.105263157894736842", "0.105263157894736842"))
            assertEquals(expected.size, result.rows.size, "完整输出不能被重复档案放大，也不能丢真实0组")
            assertEquals(expected.keys, actual.keys, "无状态、无当前档案与NULL身份无组；空串身份保留")
            expected.forEach { (account, values) -> values.zip(actual.getValue(account)).forEachIndexed { index, (value, number) ->
                assertEquals(0, BigDecimal(value).compareTo(number), "account=[$account], metric=${metricCodes[index]}")
            } }

            // 五量中的去重不能充当SEMI袋语义的证明，另读同发布的实际五列成员袋。
            val qualifiedRows = Calculation.Scope(Calculation.Members("qualified_rows", Calculation.MemberSource(qualifiedReference), stateColumns.map {
                Calculation.MemberProjection(it, it, if (it == "account") Calculation.PublishedDimension(axis) else null)
            }, null, emptyMap(), MemberCondition.Unrestricted(), period, emptyMap(), emptyMap()), emptyMap(), emptyList())
            val bag = f.execute(publication, qualifiedRows, QueryExecutionMode.CORE_FINALIZATION).businessResult
            val bagPositions = bag.columns.mapIndexed { index, column -> column.symbol.name to index }.toMap()
            val actualBag = bag.rows.map { row -> stateColumns.map { name -> when (val value = row[bagPositions.getValue(name)]) {
                is ExecutableBusinessResult.ScalarValue -> value.value
                is ExecutableBusinessResult.NullValue -> null
                else -> error("raw observation must remain a scalar or NULL")
            } } }.groupingBy { it }.eachCount()
            val expectedBag = listOf(
                listOf("A", "r1", "20240101", "1", "p1"), listOf("A", "r1", "20240101", "1", "p1"),
                listOf("A", "r1", "20240102", "1", "p2"), listOf("A", "r2", "20240102", "1", "p2"),
                listOf("A", null, "20240103", "1", "p3"), listOf("A", "other", "20240104", "0", "p4"),
                listOf("A", "unknown", "20240105", null, "p5"), listOf("B", "b1", "20240101", "0", "p1"),
                listOf("B", null, "20240102", null, "p2"), listOf("", "", "20240101", "1", "p1"),
                listOf("", "", "20240101", "1", "p1"), listOf("", "", "20240119", "1", "p19")
            ).groupingBy { it }.eachCount()
            assertEquals(expectedBag, actualBag, "SEMI不复制左袋，不删原始重复，保留五列和各自NULL")

            // 此关系算子真实消费已量化的精确商，来源不得把中间商改成DOUBLE后再求和。
            val quantized = measures(listOf(call("mean_daily_selected_entries", "mean_value")))
            val regrouped = Calculation.Scope(Calculation.Input("quantized"), mapOf("quantized" to Calculation.InputScope("ROOT", quantized)),
                listOf(Regroup(ResultGrouping(listOf("account"), listOf(ResultAggregate("mean_value",
                    ResultAggregation.Sum(mean, ResultAggregation.NullPolicy.IGNORE), "summed_mean", "已量化商的同键汇总")), null))))
            val failure = assertThrows(EngineExecutionException::class.java) { f.execute(publication, regrouped, QueryExecutionMode.CORE_FINALIZATION) }
            assertEquals("COMPILE_REL", failure.stage)
            assertEquals("EXECUTABLE_REL_COMPILATION_FAILED", failure.code)
            assertEquals(false, failure.sourceExecutionStarted, "不能提交不能兑现精确中间量化的来源计划")
            assertNull(failure.sourceReadEvidence)
            assertTrue(generateSequence<Throwable>(failure) { it.cause }.any {
                it.message?.contains("floating native arithmetic cannot satisfy an exact quantity contract") == true
            }, failure.message)
        }
    }

    private fun register(f: GovernanceOrderFixture, table: String, types: Map<String, ScalarValueType>): EventRef {
        val catalog = TestConnectors.open("duckdb", f.spec.jdbcUrl).use { it.catalog }
        val discovered = f.onboarding.discover(WarehouseDiscoveryCommand("duckdb", f.spec.sourceIdentityHash(), catalog, "main", table,
            emptyList(), 20, emptyMap(), emptyMap(), "golden"), RuntimeDeploymentLimits.CONTROL,
            JdbcSourceInspection(JdbcWarehouseInspector(f.connectors), f.connectors))
        assertEquals("SUCCEEDED", discovered.snapshot.status, discovered.snapshot.errorMessage)
        val source = discovered.tables.single()
        val physical = f.physical.createPhysicalTable(PhysicalTableCreateRequest("example.$table", table, table, "资格计数来源", "example", "data",
            "internal", "example", "duckdb", source.catalogName, source.schemaName,
            PhysicalTableCreateRequest.SourceAdoptionInput(source.id, emptyList()), "golden", discovered.columns.map {
                PhysicalTableCreateRequest.ColumnInput(it.columnName, it.columnName, it.typeName, "internal", requireNotNull(it.nullable), "真实来源列",
                    JdbcColumnType(it.jdbcType, it.typeName, it.precision, it.scale, it.nullable))
            }))
        f.sourceAdoptions += physical.sourceAdoptions.references
        f.activate("PHYSICAL_TABLE", physical.physicalTable.id)
        val columns = physical.physicalColumns.associate { it.columnName to it.id }
        f.logical.createLogicalTable(LogicalTableCreateRequest(physical.physicalTable.id, 1, table, table, LogicalTableRole.FACT, "来源原始袋", "data", "golden",
            types.map { (name, type) -> LogicalTableCreateRequest.LogicUnitInput(name, name, LogicUnitSourceKind.COLUMN, null,
                when (type) { ScalarValueType.TEXT -> LogicType.STRING; ScalarValueType.BOOLEAN -> LogicType.BOOLEAN; else -> LogicType.NUMBER },
                null, false, false, false, true, type == ScalarValueType.NUMBER,
                listOf(LogicalTableCreateRequest.SourceRefInput(columns.getValue(name), 1, LogicUnitSourceRefRole.DIRECT_SOURCE))) }))
        val topology = f.topologies.createTopology(TopologyCreateRequest(table, table, "example.qualified", "资格计数实现", table, 1, "data", "保留来源NULL与重复", "golden")).topology
        val event = f.events.createEvent(EventCreateRequest(topology.id, 1, "rows", table, "data", null, null, EventIdentity(emptyList(), emptyMap()), emptyMap(),
            types.filterValues { it != ScalarValueType.NUMBER }.keys.map { EventCreateRequest.LogicUnitRefInput("$table.$it", 1) },
            types.filterValues { it == ScalarValueType.NUMBER }.keys.map { EventCreateRequest.LogicUnitRefInput("$table.$it", 1) }, "原始观察袋", "golden")).event
        f.activate("LOGICAL_TABLE", table)
        f.activate("TOPOLOGY", topology.id)
        f.activate("EVENT", event.id)
        return EventRef(event.id, 1)
    }

    private fun profileDefinition(): MemberSourceDefinition.Computed {
        val outputs = linkedMapOf<String, Calculation.MemberOutput>("account" to Calculation.MemberDimension("account", null),
            "is_latest" to Calculation.MemberProperty("profile_latest", ScalarValueType.BOOLEAN, null))
        val root = Calculation.Members("current_profile_keys", Calculation.MemberParameter("profile"), listOf(Calculation.MemberProjection("account", "account", null)),
            null, emptyMap(), MemberCondition.Predicate("is_latest", MemberCondition.Operator.EQ, listOf(Calculation.Scalar(ScalarValueType.BOOLEAN, "true"))),
            emptyMap(), emptyMap(), emptyMap())
        val body = Calculation.Scope(root, emptyMap(), listOf(PostFilter(notNull("account")), Regroup(ResultGrouping(listOf("account"), emptyList(), null))))
        return MemberSourceDefinition.Computed(Calculation(listOf(accountSlot), listOf(Calculation.MemberSlot("profile", "ProfileRawObservation", "profile_raw",
            MemberShape.RAW_BAG, outputs, null)), emptyList(), emptyList(), emptyMap(), body),
            MemberResult("CurrentProfileAccountSet", "current_profile_accounts", MemberShape.UNIQUE_MEMBERS, listOf("account"),
                mapOf("account" to MemberSourceDefinition.ComputedDimensionOutput("账户", "account", ScalarValueType.TEXT, "account", "account", null)),
                emptyMap(), null, emptyMap(), "当前档案的唯一非NULL账户，保留空串"))
    }

    private fun qualifiedDefinition(): MemberSourceDefinition.Computed {
        val state = memberScope("period_state_records", "state", stateColumns, MemberCondition.Predicate("observed_label", MemberCondition.Operator.BETWEEN,
            listOf(Calculation.Argument("begin_label"), Calculation.Argument("end_label"))), listOf(PostFilter(notNull("account"))))
        val profile = memberScope("current_profile_accounts", "profile_accounts", listOf("account"))
        val body = Calculation.Scope(Calculation.Input("state"), mapOf("state" to Calculation.InputScope("ROOT", state),
            "profile_accounts" to Calculation.InputScope("RELATION", profile)), listOf(
            JoinInput("profile_accounts", "matched_profile", "SEMI", ResultOperation(ResultOperator.EQ,
                listOf(ResultSymbol("account"), ResultSymbol("matched_profile__account")))), Project(stateColumns)))
        val slots = listOf(Calculation.MemberSlot("state", "StateRawObservation", "state_raw", MemberShape.RAW_BAG, stateOutputs(), null),
            Calculation.MemberSlot("profile_accounts", "CurrentProfileAccountSet", "current_profile_accounts", MemberShape.UNIQUE_MEMBERS,
                mapOf("account" to Calculation.MemberDimension("account", null)), null))
        val outputs = stateColumns.associateWith { name ->
            if (name == "account") MemberSourceDefinition.ComputedDimensionOutput("账户", "account", ScalarValueType.TEXT, "account", name, null)
            else MemberSourceDefinition.ComputedPropertyOutput(name, propertyRoles.getValue(name), if (name == "state_code") ScalarValueType.NUMBER else ScalarValueType.TEXT,
                name, if (name == "state_code") count else null)
        }
        return MemberSourceDefinition.Computed(Calculation(listOf(accountSlot), slots, emptyList(), emptyList(), periodParameters(), body),
            MemberResult("ProfileQualifiedStateObservation", "profile_match_state", MemberShape.RAW_BAG, emptyList(), outputs, emptyMap(), null, emptyMap(),
                "期间和非NULL身份筛选后SEMI当前档案，保持原状态五列及袋"))
    }

    private fun metricDefinition(code: String): MetricCalculation {
        val stages = mutableListOf<ValidatedOperation>()
        fun eligible(input: String) = ResultCaseWhen(listOf(ResultCaseBranch(ResultOperation(ResultOperator.EQ,
            listOf(ResultSymbol("state_code"), ResultQuantityLiteral(BigDecimal.ONE, count))), ResultSymbol(input))), ResultLiteral(null))
        fun regroup(input: String, distinct: Boolean, output: String) = Regroup(ResultGrouping(listOf("account"),
            listOf(ResultAggregate(input, ResultAggregation.Count(distinct, ResultAggregation.NullPolicy.IGNORE), output, "非NULL计数")), null))
        when (code) {
            "selected_entry_count" -> { stages += Derive(eligible("entry_ref"), "eligible_entry", "只让条件TRUE条目参与"); stages += regroup("eligible_entry", true, "value") }
            "state_account_count" -> stages += regroup("account", true, "value")
            "selected_day_count" -> { stages += Derive(eligible("observed_label"), "eligible_day", "条件TRUE日期，独立于条目NULL"); stages += regroup("eligible_day", true, "value") }
            "mean_daily_selected_entries" -> {
                stages += Derive(eligible("entry_ref"), "eligible_entry", "NULL条目不计票")
                stages += Regroup(ResultGrouping(listOf("account", "observed_label", "eligible_entry"), emptyList(), null))
                stages += regroup("eligible_entry", false, "completed_count")
            }
            "mean_daily_state_accounts" -> stages += regroup("observed_label", true, "completed_count")
            else -> error("unknown metric $code")
        }
        val parameters = LinkedHashMap(periodParameters())
        if (code in meanCodes) {
            parameters["requested_days"] = Calculation.Parameter(Calculation.ParameterKind.SCALAR, "NUMBER", emptyList(), null, days)
            stages += Derive(ResultNumeric(ResultOperation(ResultOperator.DIVIDE,
                listOf(ResultSymbol("completed_count"), ResultArgument("requested_days", ScalarValueType.NUMBER))), mean, null), "value", "请求期间固定19日，计数完成后只量化一次")
        }
        stages += Project(listOf("account", "value"))
        val root = memberScope("qualified_state_records", "qualified_state", stateColumns)
        return MetricCalculation(Calculation(listOf(accountSlot), listOf(Calculation.MemberSlot("qualified_state", "ProfileQualifiedStateObservation", "profile_match_state",
            MemberShape.RAW_BAG, stateOutputs(), null)), emptyList(), emptyList(), parameters, Calculation.Scope(root.root, emptyMap(), stages)),
            "value", listOf("account"), if (code in meanCodes) mean else count)
    }

    private fun memberScope(callId: String, slot: String, columns: List<String>, condition: MemberCondition = MemberCondition.Unrestricted(),
        stages: List<ValidatedOperation> = emptyList()) = Calculation.Scope(Calculation.Members(callId, Calculation.MemberParameter(slot),
        columns.map { Calculation.MemberProjection(it, it, null) }, null, emptyMap(), condition, emptyMap(), emptyMap(), emptyMap()), emptyMap(), stages)

    private fun stateOutputs() = linkedMapOf<String, Calculation.MemberOutput>("account" to Calculation.MemberDimension("account", null),
        "entry_ref" to Calculation.MemberProperty("entry_reference", ScalarValueType.TEXT, null),
        "observed_label" to Calculation.MemberProperty("observed_label", ScalarValueType.TEXT, null),
        "state_code" to Calculation.MemberProperty("state_code", ScalarValueType.NUMBER, count),
        "partition_label" to Calculation.MemberProperty("partition_label", ScalarValueType.TEXT, null))

    private fun periodParameters() = linkedMapOf("begin_label" to Calculation.Parameter(Calculation.ParameterKind.SCALAR, "TEXT", emptyList(), null, null),
        "end_label" to Calculation.Parameter(Calculation.ParameterKind.SCALAR, "TEXT", emptyList(), null, null))

    private fun notNull(symbol: String) = ResultOperation(ResultOperator.NOT, listOf(ResultOperation(ResultOperator.IS_NULL, listOf(ResultSymbol(symbol)))))

    companion object {
        private const val accountConcept = "example.qualified_account"
        private val axis = ScopedInputContract.DimensionRef("${GovernanceOrderFixture.SUBJECT}.account", "1", "account")
        private val accountSlot = Calculation.DimensionSlot("account", accountConcept, 1, "account")
        private val count = GovernanceOrderFixture.records()
        private val days = MetricValueContract(MetricValueContract.Fixed(MetricValueContract.ONE), MetricValueContract.Exact(),
            MetricValueContract.Decimal(20, 1, MetricValueContract.Rounding.NONE), emptyMap(), MetricValueContract.Overflow.FAIL)
        private val mean = MetricValueContract(MetricValueContract.Fixed(MetricValueContract.ONE), MetricValueContract.Exact(),
            MetricValueContract.Decimal(38, 18, MetricValueContract.Rounding.HALF_UP), emptyMap(), MetricValueContract.Overflow.FAIL)
        private val stateColumns = listOf("account", "entry_ref", "observed_label", "state_code", "partition_label")
        private val propertyRoles = mapOf("is_latest" to "profile_latest", "entry_ref" to "entry_reference", "observed_label" to "observed_label",
            "state_code" to "state_code", "partition_label" to "partition_label")
        private val metricCodes = listOf("selected_entry_count", "state_account_count", "selected_day_count", "mean_daily_selected_entries", "mean_daily_state_accounts")
        private val meanCodes = metricCodes.takeLast(2).toSet()
    }
}
