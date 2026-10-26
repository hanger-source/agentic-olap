package com.olap.prototype.management.governance

import com.olap.prototype.calculation.*
import com.olap.prototype.codec.GovernanceProto
import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.engine.EngineExecutionException
import com.olap.prototype.governance.contract.*
import com.olap.prototype.member.MemberCondition
import com.olap.prototype.metric.*
import com.olap.prototype.query.*
import com.olap.prototype.query.contract.*
import com.olap.prototype.release.catalog.*
import com.olap.prototype.release.catalog.capability.*
import com.olap.prototype.release.contract.*
import com.olap.prototype.storage.PlatformDatabase
import com.olap.prototype.time.TemporalWindow
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CancellationException

class ImmutableGovernancePublicationTest {
    @Test
    fun exactActiveGraphDoesNotFollowNewIdentityVersions(@TempDir directory: Path) {
        PlatformDatabase.inMemory().use { database ->
            val f = GovernanceOrderFixture(database, directory); f.seed()
            introduceDrafts(f)
            assertEquals("ACTIVE", f.logical.logicalTableSnapshot("order_fact", 1).status)
            assertEquals("ACTIVE", f.logical.logicUnitSnapshot("order_fact.pay_amount", 1).status)
            assertEquals("DRAFT", f.logical.logicalTableSnapshot("order_fact", 2).status)
            val amount = f.physical.physicalTableColumnSnapshots(f.physicalId, 1).single { it.columnName == "pay_amount" }
            assertEquals("DRAFT", f.physical.physicalColumnSnapshot(amount.id, 2).status)
            f.activateSubjectGraph(); f.build()
            val frozen = f.artifacts.loadCatalog(ReleaseCatalogRequest(GovernanceOrderFixture.SCENARIO, 1, "release_trade", "1"))
            assertEquals(1L, frozen.objectVersionRefs.single { it.objectType == "LOGIC_UNIT" && it.objectId == "order_fact.pay_amount" }.objectVersion)
        }
    }

    @Test
    fun commitsOneImmutableAuthorizedPublicationAndRollsBackInterruptedFinalization(@TempDir directory: Path) {
        PlatformDatabase.inMemory().use { database ->
            val f = GovernanceOrderFixture(database, directory, listOf("INSERT INTO orders VALUES ('1',120.50,'杭州','2026-07-21','c1'),('2',999,'杭州','2026-08-01','c2')")); f.seed()
            val timeBinding = f.dimensions.listDimensionEventBindings("common.business_date", 1).single()
            assertThrows(IllegalArgumentException::class.java) { f.analysis.createAnalysisDimensionEventMapping(AnalysisDimensionEventMappingCreateRequest(
                "travel.trade.business_date", 1, "day", timeBinding.id, 1, "PROVEN_SAME_KEY", null)) }
            assertThrows(ProducerContractException::class.java) { f.analysis.createAnalysisDimensionEventMapping(AnalysisDimensionEventMappingCreateRequest(
                "travel.trade.business_date", 1, "day", timeBinding.id, 1, "EXPLICIT_MAPPING", "alignment:business_date")) }
            val provider = f.analysis.createAnalysisDimensionValueProvider(AnalysisDimensionValueProviderCreateRequest("travel.trade.business_date", 1, "day", timeBinding.id, 1, "ON_DEMAND", "golden")).provider
            val timeFilter = f.scenarios.createScenarioFilterRef(ScenarioFilterRefCreateRequest(GovernanceOrderFixture.SCENARIO, 1, "ANALYSIS_DIMENSION", "travel.trade.business_date", 1,
                provider.id, listOf(FilterLeafOperator.BETWEEN, FilterLeafOperator.GTE), false, 1)).ref
            val cityFilter = f.scenarios.createScenarioFilterRef(ScenarioFilterRefCreateRequest(GovernanceOrderFixture.SCENARIO, 1, "ANALYSIS_DIMENSION", "travel.trade.destination", 1,
                null, listOf(FilterLeafOperator.EQ), false, 2)).ref
            f.scenarios.createScenarioPipelineRule(ScenarioPipelineRuleCreateRequest(GovernanceOrderFixture.SCENARIO, 1, "DERIVE", 1, StageAuthorization.current("DERIVE")))
            f.activateSubjectGraph(); introduceDrafts(f)
            val interrupted = object : ReleaseArtifactWriter {
                override fun saveCatalog(catalog: ReleaseCatalogView, executable: PublishedExecutableCatalog, at: Instant): ReleaseCatalogSnapshot = f.artifacts.saveCatalog(catalog, executable, at)
                override fun finalizeArtifact(releaseId: String, releaseVersion: String, subjectAreaId: String, subjectAreaVersion: Long, actor: String, at: Instant): ReleaseArtifactRecord {
                    f.artifacts.finalizeArtifact(releaseId, releaseVersion, subjectAreaId, subjectAreaVersion, actor, at)
                    throw CancellationException("publisher interrupted after final storage write before commit")
                }
            }
            assertThrows(CancellationException::class.java) { f.builder(interrupted).build(SubjectAreaReleaseBuildRequest("interrupted_release", "1", "提交前中断", GovernanceOrderFixture.SUBJECT, 1, "golden", f.sourceAdoptions)) }
            assertFalse(CanonicalReleaseRepository(database).existsRelease("interrupted_release", "1"))
            assertThrows(ProducerContractException::class.java) { f.artifacts.loadArtifact("interrupted_release", "1") }
            assertThrows(ProducerContractException::class.java) { f.capabilities.load(QueryCapabilityRequest(GovernanceOrderFixture.SCENARIO, 1, "interrupted_release", "1")) }
            val built = f.build(); assertEquals("BUILT", built.buildStatus); assertEquals(1, built.metricsFrozen); assertEquals(2, built.analysisDimensionsFrozen)
            f.build("release_trade_second", "2")
            val entrypoints = ReleasedScenarioEntrypointRepository(database::connection)
            assertEquals(setOf("release_trade", "release_trade_second"), entrypoints.list().map { it.releaseId }.toSet())
            database.connection().use { connection -> connection.prepareStatement("DELETE FROM release_artifact WHERE release_id=? AND release_version=?").use {
                it.setString(1, "release_trade_second"); it.setString(2, "2"); assertEquals(1, it.executeUpdate())
            } }
            assertEquals(listOf("release_trade"), entrypoints.list().map { it.releaseId })
            val coordinate = ReleaseCatalogRequest(GovernanceOrderFixture.SCENARIO, 1, "release_trade", "1")
            val publication = f.publication()
            val capability = publication.queryCapability
            val originalHash = f.artifacts.catalogHash(coordinate)
            val originalBytes = GovernanceProto.toProto(publication).toByteArray()
            val city = ScopedInputContract.DimensionRef("travel.trade.destination", "1", "destination")
            val window = Calculation.TimeRange(Calculation.PublishedDimension(ScopedInputContract.DimensionRef("travel.trade.business_date", "1", "day")), Calculation.Range(TemporalWindow(TemporalWindow.ValueType.DATE, TemporalWindow.BOUNDARY, "2026-07-01", "2026-08-01")))
            val instance = f.adoptions.metricAdoption(MetricAdoption.identity(GovernanceOrderFixture.SUBJECT, "pay_amount"), 1).instance
            fun scope(condition: DimensionCondition = DimensionCondition.Unrestricted(), stages: List<PostOperationContract.ValidatedOperation> = emptyList()) = Calculation.Scope(Calculation.Measures(listOf(Calculation.InstanceCall("measure", instance, outputAlias = "pay_amount")), (listOf(city)).map { Calculation.PublishedDimension(it) }, emptyList(), window, emptyMap(), Calculation.Condition(condition), DimensionCondition.Unrestricted(), emptyList(), RowPopulationContract.calls(listOf("measure")), null), emptyMap(), stages)
            val rows = f.execute(publication, scope()).result.resultFrame.rows
            assertEquals(1, rows.size); assertEquals("杭州", rows.single()["destination"]); assertEquals(0, BigDecimal("120.50").compareTo(BigDecimal(rows.single()["pay_amount"].toString())))
            val amount = GovernanceOrderFixture.money()
            val twiceAmount = MetricValueContract(amount.unit, amount.arithmetic, amount.result,
                mapOf("scale" to MetricValueContract.PreserveUnitScale(MetricValueContract.BinaryOperator.MULTIPLY, "2")), amount.overflow)
            val twice = PostOperationContract.Derive(ResultExpression.ResultNumeric(ResultExpression.ResultOperation(ResultExpression.ResultOperator.MULTIPLY,
                listOf(ResultExpression.ResultSymbol("pay_amount"), ResultExpression.ResultLiteral(BigDecimal("2")))), twiceAmount, "scale"), "twice", "两倍金额")
            assertEquals(0, BigDecimal("241").compareTo(BigDecimal(f.execute(publication, scope(stages = listOf(twice))).result.resultFrame.rows.single()["twice"].toString())))
            StoredGovernanceDefinitions(database).execute("UPDATE scenario_pipeline_rule SET status='DRAFT' WHERE scenario_id=? AND scenario_version=1 AND rule_type='DERIVE'", GovernanceOrderFixture.SCENARIO)
            val reloaded = JdbcPublishedQueryEntryProvider(database).readPublication(QueryCapabilityRequest(GovernanceOrderFixture.SCENARIO, 1, "release_trade", "1")).executable
            assertEquals(0, BigDecimal("241").compareTo(BigDecimal(f.execute(reloaded, scope(stages = listOf(twice))).result.resultFrame.rows.single()["twice"].toString())))
            val denied = assertThrows(EngineExecutionException::class.java) { f.execute(reloaded, scope(stages = listOf(PostOperationContract.Project(listOf("pay_amount"))))) }
            assertEquals("AUTHORIZE_INPUT", denied.stage); assertEquals("EXECUTABLE_QUERY_NOT_PUBLISHED", denied.code)
            for (value in listOf("杭州", "上海")) {
                val selected = scope(DimensionCondition.Predicate(cityFilter.id, city, MemberCondition.Operator.EQ, listOf(Calculation.Scalar(ScalarValueType.TEXT, value))))
                assertEquals(if (value == "杭州") 1 else 0, f.execute(publication, selected).result.resultFrame.rows.size)
            }
            assertEquals(listOf("BETWEEN", "GTE"), capability.filterCandidates.single { it.filterRefId == timeFilter.id }.operators)
            assertNull(capability.filterCandidates.single { it.filterRefId == cityFilter.id }.valueProviderId)
            assertThrows(RuntimeException::class.java) { f.build() }
            f.metrics.reviseMetric(GovernanceRevisionRequest(f.paymentId, 1, MetricCreateRequest(
                code = "pay_amount",
                displayName = "平均支付金额",
                owner = "trade",
                definition = MetricDefinition.FactMeasure(
                    event = com.olap.prototype.event.EventRef(GovernanceOrderFixture.EVENT, 1),
                    input = GovernanceOrderFixture.ref("order_fact.pay_amount"),
                    aggregation = MetricAggregation.AVG,
                    distinct = false,
                    privatePredicate = null,
                    roles = emptyMap(),
                    snapshotRule = null,
                    valueContract = CustomerCalculationDefinitions.nativeMeanMoney(),
                    meanTarget = MeanTarget.SOURCE_NATIVE,
                ),
                description = "修订口径",
                businessMeaning = MetricBusinessMeaning("平均支付金额", "元", "订单", emptyList()),
                createdBy = "golden"
            )))
            assertEquals("SUM", f.metrics.metricSnapshot(f.paymentId, 1).factMeasure().aggregation.name)
            assertEquals("AVG", f.metrics.metricSnapshot(f.paymentId, 2).factMeasure().aggregation.name)
            assertEquals(originalHash, f.artifacts.catalogHash(coordinate)); assertArrayEquals(originalBytes, GovernanceProto.toProto(f.publication()).toByteArray())
            database.inTransaction {
                database.connection().use { connection ->
                    for (kind in listOf("METRIC", "ANALYSIS_DIMENSION")) {
                        val point = connection.setSavepoint()
                        try {
                            connection.prepareStatement("DELETE FROM release_object_ref WHERE release_id='release_trade' AND release_version='1' AND object_type=?").use {
                                it.setString(1, kind); assertTrue(it.executeUpdate() > 0)
                            }
                            assertThrows(ProducerContractException::class.java) { ReleaseCatalogProducer(JdbcReleaseCatalogRepository(database::connection)).produce(coordinate) }
                            assertArrayEquals(originalBytes, GovernanceProto.toProto(f.publication()).toByteArray())
                        } finally { connection.rollback(point) }
                    }
                }
            }
        }
    }

    private fun introduceDrafts(f: GovernanceOrderFixture) {
        val columns = f.physical.physicalTableColumnSnapshots(f.physicalId, 1)
        f.physical.updatePhysicalTableColumns(PhysicalTableColumnsUpdateRequest(f.physicalId, 1, "golden", columns.map {
            PhysicalTableColumnsUpdateRequest.ColumnInput(it.columnName, "新草稿${it.displayName}", it.dataType, it.description, it.nullable, it.jdbcColumnType)
        }))
        val units = f.logical.listLogicalTableLogicUnitRefs("order_fact", 1).map { f.logical.logicUnitSnapshot(it.logicUnitId, it.logicUnitVersion) }.map { unit ->
            LogicalTableCreateRequest.LogicUnitInput(unit.code, unit.displayName, LogicUnitSourceKind.valueOf(unit.sourceKind), unit.expression,
                unit.logicType?.let(LogicType::valueOf), unit.valueShape, unit.isTime, unit.isPrimaryKey, unit.isForeignKey, unit.filterable, unit.aggregatable,
                f.logical.listLogicUnitSourceRefs(unit.id, 1).map { LogicalTableCreateRequest.SourceRefInput(it.physicalColumnId, it.physicalColumnVersion, LogicUnitSourceRefRole.valueOf(it.refRole)) })
        }
        f.logical.reviseLogicalTable(GovernanceRevisionRequest("order_fact", 1,
            LogicalTableCreateRequest(f.physicalId, 1, "order_fact", "新草稿交易订单", LogicalTableRole.FACT, "旧版本仍精确有效", "data", "golden", units)))
    }
}
