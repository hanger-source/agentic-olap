package com.olap.prototype.engine.executable

import com.olap.prototype.calculation.*
import com.olap.prototype.connector.JdbcAccess
import com.olap.prototype.connector.SourceProvider
import com.olap.prototype.connector.SourceExecutionException
import com.olap.prototype.engine.EngineExecutionException
import com.olap.prototype.engine.OlapEngine
import com.olap.prototype.engine.catalog.*
import com.olap.prototype.engine.compiler.CompiledQuery
import com.olap.prototype.engine.compiler.OlapSemanticCompiler
import com.olap.prototype.engine.context.OlapQueryContext
import com.olap.prototype.event.*
import com.olap.prototype.expression.*
import com.olap.prototype.integration.RuntimeDeploymentLimits
import com.olap.prototype.integration.TestConnectors
import com.olap.prototype.member.*
import com.olap.prototype.metric.*
import com.olap.prototype.query.*
import com.olap.prototype.query.ResultExpression.*
import com.olap.prototype.query.PostOperationContract.ValidatedOperation
import com.olap.prototype.time.TemporalWindow
import com.olap.prototype.util.OlapJsonUtils
import com.olap.prototype.warehouse.contract.*
import com.olap.prototype.warehouse.jdbc.JdbcTemporalVerifier
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

class TemporalInputExecutionTest {
    private val unit = MetricValueContract.Fixed(MetricValueContract.UnitIdentity("iso4217", "USD", 1))
    private val exact = MetricValueContract(unit, MetricValueContract.Exact(), MetricValueContract.Decimal(38, 2, MetricValueContract.Rounding.NONE), emptyMap(), MetricValueContract.Overflow.FAIL)
    private val nativeFloatingMean = MetricValueContract(unit, MetricValueContract.NativeFloating(), MetricValueContract.BinaryFloat(64), emptyMap(), MetricValueContract.Overflow.FAIL)
    private val decimalMean = MetricValueContract(unit, MetricValueContract.Exact(), MetricValueContract.Decimal(38, 12, MetricValueContract.Rounding.NONE), emptyMap(), MetricValueContract.Overflow.FAIL)
    private val ordinal = MetricValueContract(MetricValueContract.Fixed(MetricValueContract.ONE), MetricValueContract.Exact(), MetricValueContract.Integer(64), emptyMap(), MetricValueContract.Overflow.FAIL)
    private val local = RelationAccessPath(emptyList())
    private val unrestricted = DimensionCondition.Unrestricted()
    private class Definitions(base: ExecutableCatalog) {
        val manifest = base.manifest
        val metrics = base.metrics.toMutableMap(); val instances = base.metricInstances.toMutableMap(); val members = base.memberSources.toMutableMap()
        val relations = base.relationFilters.toMutableMap(); val analyses = base.analysisDimensions.toMutableMap(); val events = base.events.toMutableMap()
        val topologies = base.topologies.toMutableMap(); val dimensions = base.dimensions.toMutableMap(); val bindings = base.dimensionBindings.toMutableMap()
        val units = base.logicUnits.toMutableMap(); val anchors = base.timeAnchors.toMutableMap(); val joins = base.joinPaths.toMutableMap(); val physical = base.physicalExecutionBindings.toMutableMap()
        fun catalog() = ExecutableCatalog(manifest, metrics, instances, members, relations, analyses, events, topologies, dimensions, bindings, units, anchors, joins, physical)
    }
    private fun compile(catalog: ExecutableCatalog, input: ExecutableInputScope): ExecutableLoweringResult {
        val graph = ExecutableInputGraph.index(input)
        return CalciteRelCompiler(TestConnectors.registry).compile(graph, ExecutableStageAnalysis.analyze(graph, TestConnectors.registry), ExecutableSourceResolver().resolve(catalog, graph), OlapQueryContext("temporal")).lowering
    }
    private fun historyWarehouse(directory: Path) = NativeWarehouse(directory, "duckdb", listOf(
        "CREATE TABLE facts(id INTEGER PRIMARY KEY, entity INTEGER, occurred DATE, amount DECIMAL(18,2))",
        "CREATE TABLE history(id INTEGER PRIMARY KEY, entity INTEGER, region VARCHAR, valid_from DATE NOT NULL, valid_to DATE, known_from TIMESTAMP(6) NOT NULL, known_to TIMESTAMP(6))",
        "INSERT INTO facts VALUES (1,1,'2026-06-01',100),(2,1,'2026-07-01',200),(3,2,'2026-06-01',40),(4,3,'2026-06-01',50)",
        "INSERT INTO history VALUES (1,1,'原记录','2026-01-01','2026-07-01','2026-01-01','2026-02-01'),(2,1,'更正记录','2026-01-01','2026-07-01','2026-02-01',NULL),(3,1,'下一期间','2026-07-01',NULL,'2026-01-01',NULL),(4,2,NULL,'2026-01-01',NULL,'2026-01-01',NULL)",
        "CREATE TABLE clocks(entity INTEGER PRIMARY KEY, occurred DATE)", "INSERT INTO clocks VALUES (1,'2026-07-01'),(2,'2026-06-01')"))
    private fun historyCatalog(warehouse: NativeWarehouse, verified: Boolean): Definitions {
        val facts = warehouse.adopt("facts", listOf("id", "entity", "occurred", "amount").associate { "facts.$it" to it })
        var history = warehouse.adopt("history", listOf("id", "entity", "region", "valid_from", "valid_to", "known_from", "known_to").associate { "history.$it" to it })
        if (verified) {
            val columns = history.physicalColumnBindings.map { (ref, name) -> name to history.physicalColumnTypes.getValue(ref) }.toMap()
            val table = JdbcTableConstraints.Table(history.catalogName, history.schemaName, history.tableName)
            val rule = JdbcTableConstraints.TemporalRule("history_rectangles", listOf("entity"), listOf(JdbcTableConstraints.TemporalInterval("valid_from", "valid_to", true, false, true), JdbcTableConstraints.TemporalInterval("known_from", "known_to", true, false, true)))
            val proofs = JdbcTemporalVerifier.verify(warehouse.connection, warehouse.spec, table, columns, listOf(rule), RuntimeDeploymentLimits.CONTROL,
                (TestConnectors.registry.require(warehouse.spec.connectorRef).access as JdbcAccess).jdbc)
            val evidence = history.adoption.evidence; val native = history.dataContract.constraints
            val constraints = JdbcTableConstraints(native.primaryKeyColumns, native.uniqueKeys, native.importedKeys, proofs)
            val hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(OlapJsonUtils.mapper().writeValueAsBytes(mapOf("columns" to columns, "constraints" to constraints))))
            val old = history.dataContract
            val contract = PhysicalDataContract(old.reference, old.dataSourceId, old.table, old.columns, constraints, old.snapshotUniqueKeys, old.sourceCollections, old.originDiscoveredTableId)
            val observed = PhysicalSourceEvidence(evidence.discoverySnapshotId, evidence.discoveredTableId, hash,
                StructureProvenance(StructureProvenance.Kind.DATABASE_CATALOG, "integration-test-catalog", "fixture-v1"), evidence.databaseProductName, evidence.databaseProductVersion,
                evidence.driverName, evidence.driverVersion, evidence.columns, constraints, evidence.snapshotUniqueKeys, evidence.sourceCollections)
            history = history.copy(dataContract = contract, adoption = TestConnectors.adoption(contract, warehouse.spec.source(), observed))
        }
        val temporal = TemporalJoinContract.Constraint(listOf(TemporalJoinContract.Interval(TemporalJoinContract.LogicUnitClock("facts.occurred", 1, emptyList()), "history.valid_from", 1, "history.valid_to", 1, true, false, true),
            TemporalJoinContract.Interval(TemporalJoinContract.QueryClock(TemporalWindow.ValueType.LOCAL_TIMESTAMP, "知识截止"), "history.known_from", 1, "history.known_to", 1, true, false, true)))
        val join = ExecutableJoinPath("history", "1", "fact_history", "1", "facts", "1", "history", "1", "LEFT", "MANY_TO_ONE", temporal, "SQL_EQUALS_NULL_NEVER_MATCHES", listOf(ExecutableJoinKey(1, "facts.entity", "1", "history.entity", "1")))
        val topology = ExecutableTopology("fact_history", "1", "fact_history", null, "facts", "1", joinPathRefs = listOf(ExecutableJoinPathRef("history", "1")))
        val event = ExecutableEvent("facts", "1", "fact_history", "1", null, EventIdentity(listOf(EventIdentity.LogicUnitRef("facts.id", 1)), emptyMap()), emptyMap(), null, "facts.occurred", "1")
        return Definitions(ExecutableCatalog(ExecutableCatalogManifest("history", ExecutableCatalogManifest.SCHEMA_VERSION), physicalExecutionBindings = mapOf("facts@1" to facts, "history@1" to history),
            events = mapOf("facts@1" to event), topologies = mapOf("fact_history@1" to topology), joinPaths = mapOf("history@1" to join)))
    }
    private fun historyInput(definitions: Definitions, cutoff: String): ExecutableInputScope {
        val access = RelationAccessPath(listOf(RelationAccessPath.JoinRef("history", 1)))
        val clocks = mapOf(TemporalClockBindings.slot("dimension:historical_region@1", access.joinPathRefs, 1) to TemporalClockBindings.Value(TemporalWindow.ValueType.LOCAL_TIMESTAMP, cutoff))
        val event = definitions.events.getValue("facts@1")
        val binding = ExecutableDimensionBinding("historical_region", "1", "region", "1", "facts", "1", "fact_history", "1", "history.region", "1", SemanticExpression.Reference(SemanticExpression.ReferenceKind.LOGIC_UNIT, "history.region", 1), access, emptyMap(), emptyList(), "history", "1")
        val dimension = ExecutableBoundAnalysisDimension(ExecutableAnalysisDimension("historical_region", "1", "历史地区", "historical_region", "region", null), "region", "value", event,
            ExecutableDimension("region", "1", null, "history", null, null), binding, ExecutableLogicUnit("history.region", "1", ScalarValueType.TEXT, null, null, null, "region", null, "history", "1"), ScalarValueType.TEXT,
            emptyList(), definitions.physical.getValue("history@1"), false, "same_historical_region", "PROVEN_SAME_KEY")
        val metric = ExecutableMetric(MetricRef("history_amount", "1"), MetricDefinition.FactMeasure(EventRef("facts", 1), SemanticExpression.Reference(SemanticExpression.ReferenceKind.LOGIC_UNIT, "facts.amount", 1), MetricAggregation.SUM, false, null, emptyMap(), null, exact))
        val selection = ExecutableMeasureSelection(mapOf("region" to ScopedInputContract.DimensionRef("historical_region", "1", "value")), emptyMap(), null, null, clocks)
        val invocation = ExecutableMetricCall.Invocation(QueryStageAnalysis.ScopeId.root(), "payment", CalculationOwner.Metric(MetricInstanceRef("payment")), "value", "value", metric, emptyMap(), CalculationBindings.empty(), null, emptyMap(), emptyMap())
        val fact = ExecutableMetricInput(invocation, selection, event, definitions.topologies.getValue("fact_history@1"), null, null, listOf(dimension), unrestricted, emptyMap(), emptyList())
        return ExecutableInputScope("ROOT", ExecutableInputRoot.Measures(listOf(ExecutableMetricCall.Fact(fact)), selection, RowPopulationContract.calls(listOf("payment")), unrestricted), emptyList(), emptyMap(), emptyList())
    }
    private fun historyMembers(definitions: Definitions, shape: MemberShape, identity: String, selected: List<String>, cutoff: String): ExecutableInputScope {
        val event = definitions.events.getValue("facts@1"); val access = RelationAccessPath(listOf(RelationAccessPath.JoinRef("history", 1)))
        val units = mapOf("record" to "facts.id", "entity" to "facts.entity", "matched_entity" to "history.entity", "history_id" to "history.id", "region" to "history.region")
        val outputs = units.mapValues { (slot, unit) -> MemberSourceDefinition.PropertyOutput(slot, slot, if (slot == "region") ScalarValueType.TEXT else ScalarValueType.NUMBER, unit, 1, if (unit.startsWith("history.")) access else local, null) }
        val source = MemberSource.Projection("historical_members", 1, MemberSourceDefinition.Projection(EventRef("facts", 1), MemberResult("历史成员", "members", shape, listOf(identity), outputs, emptyMap(), null, emptyMap(), null)),
            units.mapValues { SemanticExpression.Reference(SemanticExpression.ReferenceKind.LOGIC_UNIT, it.value, 1) })
        val used = (selected + identity).toSet(); val clocks = linkedMapOf<String, TemporalClockBindings.Value>()
        val intervals = requireNotNull(definitions.joins.getValue("history@1").temporalConstraint).intervals
        used.filter { units.getValue(it).startsWith("history.") }.forEach { slot -> intervals.forEachIndexed { index, interval ->
            (interval.sourceTime as? TemporalJoinContract.QueryClock)?.let { query -> clocks[TemporalClockBindings.slot("member:historical_members@1:$slot", access.joinPathRefs, index)] =
                TemporalClockBindings.Value(query.valueType, if (query.valueType == TemporalWindow.ValueType.DATE) "2026-06-01" else cutoff) }
        } }
        val selection = MemberInvocationBinding.Bound(selected.associateWith { it }, selected.associateWith { QueryStageAnalysis.MemberProperty(MemberSourceDefinition.scalar(outputs.getValue(it)).valueType, it) },
            selected.associateWith { null }, emptyMap(), used, MemberCondition.Unrestricted(), null, clocks, emptyMap())
        return ExecutableInputScope("ROOT", ExecutableInputRoot.Members(ExecutableMemberInput.Projection("historical_members", QueryStageAnalysis.ScopeId.root(), source, event,
            definitions.topologies.getValue("fact_history@1"), selection)), emptyList(), emptyMap(), emptyList())
    }
    @Test fun provesRecordIdentityAndMemberAttributesAtTheirActualTemporalOccurrences(@TempDir directory: Path) {
        historyWarehouse(directory).use { warehouse ->
            warehouse.connection.createStatement().use { it.execute("INSERT INTO facts VALUES (5,4,'2026-06-01',60)") }; warehouse.finishSetup()
            var sealed: ExecutableLoweringResult? = null
            for (businessQueryClock in listOf(false, true)) {
                val definitions = historyCatalog(warehouse, true)
                if (businessQueryClock) {
                    val path = definitions.joins.getValue("history@1"); val intervals = requireNotNull(path.temporalConstraint).intervals.toMutableList(); val first = intervals.first()
                    intervals[0] = TemporalJoinContract.Interval(TemporalJoinContract.QueryClock(TemporalWindow.ValueType.DATE, "业务时点"), first.targetValidFromLogicUnitId, first.targetValidFromLogicUnitVersion,
                        first.targetValidToLogicUnitId, first.targetValidToLogicUnitVersion, first.validFromInclusive, first.validToInclusive, first.openEndedValidTo)
                    definitions.joins["history@1"] = path.copy(temporalConstraint = TemporalJoinContract.Constraint(intervals))
                }
                for (cutoff in listOf("2026-01-15T00:00:00", "2026-02-01T00:00:00")) {
                    val compiled = compile(definitions.catalog(), historyMembers(definitions, MemberShape.RECORDS, "record", listOf("record", "history_id", "region"), cutoff)); sealed = compiled
                    val actual = warehouse.execute(compiled).associateBy { (it.first() as Number).toInt() }; assertEquals(5, actual.size)
                    assertEquals(if (cutoff.startsWith("2026-01")) "原记录" else "更正记录", actual.getValue(1)[2]); assertEquals(if (businessQueryClock) actual.getValue(1)[2] else "下一期间", actual.getValue(2)[2])
                    assertEquals(4, (actual.getValue(3)[1] as Number).toInt()); assertNull(actual.getValue(3)[2]); assertNull(actual.getValue(4)[1]); assertNull(actual.getValue(5)[1])
                    val members = historyMembers(definitions, MemberShape.UNIQUE_MEMBERS, "entity", listOf("entity", "region"), cutoff)
                    if (businessQueryClock) { val rows = warehouse.execute(compile(definitions.catalog(), members)); assertEquals(4, rows.size); assertEquals(1, rows.count { it[1] != null }) }
                    else assertThrows(IllegalArgumentException::class.java) { compile(definitions.catalog(), members) }
                    val ambiguous = historyMembers(definitions, MemberShape.UNIQUE_MEMBERS, "matched_entity", listOf("matched_entity", "entity"), cutoff)
                    assertThrows(IllegalArgumentException::class.java) { compile(definitions.catalog(), ambiguous) }
                }
            }
            val unproved = historyCatalog(warehouse, false)
            assertThrows(IllegalArgumentException::class.java) { compile(unproved.catalog(), historyMembers(unproved, MemberShape.RECORDS, "record", listOf("record", "region"), "2026-02-01T00:00:00")) }
            warehouse.connection.close()
            TestConnectors.open(warehouse.spec.dialectId, warehouse.spec.jdbcUrl).use { changed -> changed.createStatement().use { it.execute("INSERT INTO history VALUES (5,1,'冲突历史','2026-01-01',NULL,'2026-01-01',NULL)") } }
            warehouse.finishSetup()
            val drift = assertThrows(SourceExecutionException::class.java) { warehouse.execute(requireNotNull(sealed)) }
            assertEquals("SOURCE_DATA_ANCHOR_DRIFT", drift.failure.code)
            assertEquals("VERIFY_SOURCE", drift.failure.stage)
            assertEquals(SourceFailureCategory.CONTRACT, drift.failure.category)
            assertEquals(warehouse.spec.source().bindingRef(), drift.failure.binding)
        }
    }
    @Test fun appliesBothTemporalIntervalsWithIndependentKnowledgeClocksAndACompleteSourceGuarantee(@TempDir directory: Path) {
        historyWarehouse(directory).use { warehouse ->
            warehouse.finishSetup(); val definitions = historyCatalog(warehouse, true)
            val primary = historyInput(definitions, "2026-01-15T00:00:00"); val revision = historyInput(definitions, "2026-02-01T00:00:00")
            val child = ExecutableInputScope("KEY_ALIGNMENT", revision.root, revision.objectRestrictions, revision.additionalInputs, revision.postOps)
            val input = ExecutableInputScope(primary.inputMode, ExecutableInputRoot.Measures(primary.metricCalls, primary.measures().selection,
                RowPopulationContract.Selection("SCOPED_UNION", listOf("payment"), listOf("revision")), unrestricted), emptyList(), mapOf("revision" to child), emptyList())
            val rows = warehouse.execute(compile(definitions.catalog(), input)).associateBy { it.first() as String? }; assertEquals(4, rows.size)
            assertEquals(100, (rows.getValue("原记录")[1] as Number).toInt()); assertNull(rows.getValue("原记录")[2]); assertNull(rows.getValue("更正记录")[1]); assertEquals(100, (rows.getValue("更正记录")[2] as Number).toInt())
            assertEquals(200, (rows.getValue("下一期间")[1] as Number).toInt()); assertEquals(200, (rows.getValue("下一期间")[2] as Number).toInt()); assertEquals(90, (rows.getValue(null)[1] as Number).toInt()); assertEquals(90, (rows.getValue(null)[2] as Number).toInt())
            val unproved = historyCatalog(warehouse, false)
            assertThrows(RuntimeException::class.java) { compile(unproved.catalog(), historyInput(unproved, "2026-02-15T00:00:00")) }
        }
    }
    @Test fun resolvesABackboneClockThroughAnIndependentSiblingBeforeTheHistoricalJoin(@TempDir directory: Path) {
        historyWarehouse(directory).use { warehouse ->
            warehouse.finishSetup(); val definitions = historyCatalog(warehouse, true)
            definitions.physical["clocks@1"] = warehouse.adopt("clocks", mapOf("clocks.entity" to "entity", "clocks.occurred" to "occurred"))
            definitions.joins["clock_owner@1"] = ExecutableJoinPath("clock_owner", "1", "fact_history", "1", "facts", "1", "clocks", "1", "LEFT", "MANY_TO_ONE", null,
                "SQL_EQUALS_NULL_NEVER_MATCHES", listOf(ExecutableJoinKey(1, "facts.entity", "1", "clocks.entity", "1")))
            definitions.topologies["fact_history@1"] = definitions.topologies.getValue("fact_history@1").copy(joinPathRefs = listOf(ExecutableJoinPathRef("history", "1"), ExecutableJoinPathRef("clock_owner", "1")))
            val historical = definitions.joins.getValue("history@1"); val knowledge = requireNotNull(historical.temporalConstraint).intervals[1]
            definitions.joins["history@1"] = historical.copy(temporalConstraint = TemporalJoinContract.Constraint(listOf(TemporalJoinContract.Interval(
                TemporalJoinContract.BackboneLogicUnitClock("clocks.occurred", 1, listOf(TemporalJoinContract.SourceTimeJoinPathRef("clock_owner", 1))), "history.valid_from", 1, "history.valid_to", 1, true, false, true), knowledge)))
            val rows = warehouse.execute(compile(definitions.catalog(), historyInput(definitions, "2026-02-15T00:00:00"))).associate { it.first() as String? to it[1] as Number }
            assertEquals(2, rows.size); assertEquals(300, rows.getValue("下一期间").toInt()); assertEquals(90, rows.getValue(null).toInt())
        }
    }
    private fun snapshotWarehouse(directory: Path, dialect: String) = NativeWarehouse(directory, dialect, listOf(
        "CREATE TABLE snapshots(id INTEGER PRIMARY KEY, customer INTEGER NOT NULL, book VARCHAR NOT NULL, region VARCHAR, occurred TIMESTAMP(6) NOT NULL, amount DECIMAL(18,2), eligible BOOLEAN)",
        "INSERT INTO snapshots VALUES (1,1,'cash','A','2026-01-01 00:00:00.000001',10,TRUE),(2,1,'cash','A','2026-01-03 00:00:00.000001',12,TRUE),(3,1,'cash','A','2026-01-03 00:00:00.000001',30,FALSE),(4,2,'cash','A','2026-01-02 00:00:00.000001',20,TRUE),(5,2,'cash','A','2026-01-03 00:00:00.000001',40,TRUE),(6,3,'margin','A','2026-01-01 00:00:00.000001',5,TRUE),(7,3,'margin','A','2026-01-03 00:00:00.000001',15,TRUE),(8,4,'cash','B','2026-01-01 00:00:00.000001',NULL,TRUE),(9,4,'cash','B','2026-01-03 00:00:00.000001',7,TRUE),(10,5,'cash','C','2026-01-01 00:00:00.000001',3,TRUE),(11,5,'cash','C','2026-01-03 00:00:00.000001',NULL,TRUE),(12,1,'cash','A','2026-02-01 00:00:00.000000',999,TRUE),(13,3,'cash','A','2026-01-02 00:00:00.000001',2,TRUE)"))
    private fun snapshotBinding(id: String, column: String, source: ExecutablePhysicalExecutionBinding) = ExecutableDimensionBinding(id, "1", id, "1", "snapshots", "1", "snapshots", "1", "snapshots.$column", "1",
        SemanticExpression.Reference(SemanticExpression.ReferenceKind.LOGIC_UNIT, "snapshots.$column", 1), local, emptyMap(), emptyList(), source.bindingId, "1")
    private fun snapshotCatalog(warehouse: NativeWarehouse): Definitions {
        val source = warehouse.adopt("snapshots", listOf("id", "customer", "book", "region", "occurred", "amount", "eligible").associate { "snapshots.$it" to it })
        val topology = ExecutableTopology("snapshots", "1", "snapshots", null, "snapshots", "1")
        val event = ExecutableEvent("snapshots", "1", "snapshots", "1", null, EventIdentity(listOf(EventIdentity.LogicUnitRef("snapshots.id", 1)),
            mapOf("account" to listOf(EventIdentity.LogicUnitRef("snapshots.customer", 1), EventIdentity.LogicUnitRef("snapshots.book", 1)))), emptyMap(), null, "snapshots.occurred", "1")
        val time = ExecutableAnalysisDimension("time", "1", "观察时间", "time", "time", "instant", eventMappings = listOf(ExecutableAnalysisDimensionEventMapping("snapshot_time_mapping", "time", "1", "instant", "snapshots", "1", "time", "1", "time", "1", "snapshots.occurred", "1", "same_time", "PROVEN_SAME_KEY")))
        return Definitions(ExecutableCatalog(ExecutableCatalogManifest("snapshots", ExecutableCatalogManifest.SCHEMA_VERSION), physicalExecutionBindings = mapOf("snapshots@1" to source),
            events = mapOf("snapshots@1" to event), topologies = mapOf("snapshots@1" to topology), analysisDimensions = mapOf("time@1" to time), dimensionBindings = mapOf("time@1" to snapshotBinding("time", "occurred", source))))
    }
    private fun snapshotInput(definitions: Definitions, dialect: String, mode: String): ExecutableInputScope {
        val reduction = when (mode) { "FIRST" -> MetricSnapshotRule.Reduction.FIRST; "LAST", "LAST_QUALIFIED" -> MetricSnapshotRule.Reduction.LAST; else -> MetricSnapshotRule.Reduction.MEAN }
        val isMean = reduction == MetricSnapshotRule.Reduction.MEAN; val meanContract = if (dialect == "h2") decimalMean else nativeFloatingMean
        val policy = if (!isMean) MetricSnapshotRule.NullPolicy.PRESERVE_SELECTED else if (mode == "MEAN_PROPAGATE") MetricSnapshotRule.NullPolicy.PROPAGATE else MetricSnapshotRule.NullPolicy.IGNORE
        val rule = MetricSnapshotRule(reduction, "account", listOf(MetricSnapshotRule.LogicUnitKey("snapshots.customer", 1), MetricSnapshotRule.LogicUnitKey("snapshots.book", 1)),
            MetricSnapshotRule.LogicUnitKey("snapshots.occurred", 1), listOf(MetricSnapshotRule.LogicUnitKey("snapshots.id", 1)), policy, if (isMean) meanContract else null,
            if (isMean) MeanTarget.SOURCE_NATIVE else null)
        val predicate = if (mode.endsWith("QUALIFIED")) SemanticExpression.Operation(SemanticExpression.Operator.EQ, listOf(SemanticExpression.Reference(SemanticExpression.ReferenceKind.LOGIC_UNIT, "snapshots.eligible", 1), SemanticExpression.Literal(true))) else null
        val metric = ExecutableMetric(MetricRef("balance", "1"), MetricDefinition.FactMeasure(EventRef("snapshots", 1), SemanticExpression.Reference(SemanticExpression.ReferenceKind.LOGIC_UNIT, "snapshots.amount", 1), MetricAggregation.SUM, false, predicate,
            emptyMap(), rule, if (isMean) meanContract else exact))
        val event = definitions.events.getValue("snapshots@1"); val source = definitions.physical.getValue("snapshots@1")
        fun bound(id: String, column: String, type: ScalarValueType, filter: Boolean) = ExecutableBoundAnalysisDimension(
            definitions.analyses["$id@1"] ?: ExecutableAnalysisDimension(id, "1", id, id, id, null), id, if (id == "time") "instant" else "value", event,
            ExecutableDimension(id, "1", null, "snapshot", null, null), snapshotBinding(id, column, source), ExecutableLogicUnit("snapshots.$column", "1", type, null, null, null, column, null, "snapshots", "1"),
            type, emptyList(), source, filter, if (id == "region") "same_region" else null, if (id == "region") "PROVEN_SAME_KEY" else "SINGLE_EVENT_ONLY")
        val range = ExecutableTimeRange("2026-01-01T00:00:00", "2026-02-01T00:00:00", TemporalWindow.ValueType.LOCAL_TIMESTAMP, TemporalWindow.BOUNDARY, "time", "1", "instant")
        val anchor = ExecutableTimeAnchor("snapshots", "1", "time", "1", "instant", "snapshots", "1", null, null, "snapshots.occurred", "1", null, "snapshots", "1", null)
        val selection = ExecutableMeasureSelection(mapOf("region" to ScopedInputContract.DimensionRef("region", "1", "value")), emptyMap(), null, range, emptyMap())
        val invocation = ExecutableMetricCall.Invocation(QueryStageAnalysis.ScopeId.root(), "balance", CalculationOwner.Metric(MetricInstanceRef("balance")), "value", "value", metric, emptyMap(), CalculationBindings.empty(), null, emptyMap(), emptyMap())
        val fact = ExecutableMetricInput(invocation, selection, event, definitions.topologies.getValue("snapshots@1"), anchor, bound("time", "occurred", ScalarValueType.LOCAL_TIMESTAMP, true),
            listOf(bound("region", "region", ScalarValueType.TEXT, false)), unrestricted, emptyMap(), emptyList())
        return ExecutableInputScope("ROOT", ExecutableInputRoot.Measures(listOf(ExecutableMetricCall.Fact(fact)), selection, RowPopulationContract.calls(listOf("balance")), unrestricted), emptyList(), emptyMap(), emptyList())
    }
    @Test fun reducesSnapshotsWithinCompositeEntitiesAfterRangeSelectionAndBeforeCrossEntityAggregation(@TempDir directory: Path) {
        for (dialect in listOf("h2", "duckdb")) snapshotWarehouse(directory, dialect).use { warehouse ->
            val definitions = snapshotCatalog(warehouse); warehouse.finishSetup()
            for (mode in listOf("FIRST", "LAST", "LAST_QUALIFIED", "MEAN_IGNORE", "MEAN_PROPAGATE", "MEAN_QUALIFIED")) {
                val values = warehouse.execute(compile(definitions.catalog(), snapshotInput(definitions, dialect, mode))).associate { it.first() as String to (it[1] as Number?)?.toDouble() }
                assertEquals(setOf("A", "B", "C"), values.keys)
                val expected = when (mode) { "FIRST" -> 37.0; "LAST" -> 87.0; "LAST_QUALIFIED" -> 57.0; "MEAN_QUALIFIED" -> 53.0; else -> 59.333333333333333 }
                assertEquals(expected, requireNotNull(values["A"]), 1E-9, "$dialect:$mode")
                if (mode == "FIRST" || mode == "MEAN_PROPAGATE") assertNull(values["B"]) else assertEquals(7.0, requireNotNull(values["B"]), 1E-9)
                if (mode.startsWith("LAST") || mode == "MEAN_PROPAGATE") assertNull(values["C"]) else assertEquals(3.0, requireNotNull(values["C"]), 1E-9)
            }
            warehouse.connection.createStatement().use { statement -> statement.executeQuery("WITH selected AS (SELECT *, ROW_NUMBER() OVER (PARTITION BY customer,book ORDER BY occurred DESC,id DESC) rn FROM snapshots WHERE eligible AND occurred >= TIMESTAMP '2026-01-01 00:00:00' AND occurred < TIMESTAMP '2026-02-01 00:00:00') SELECT SUM(amount) FROM selected WHERE rn=1 AND region='A'").use { wrong ->
                assertTrue(wrong.next()); assertEquals(69.0, wrong.getDouble(1))
            } }
        }
    }
    @Test fun quantizesMathematicalSnapshotMeansBeforeFurtherAggregationAndPreservesNullParticipation(@TempDir directory: Path) {
        snapshotWarehouse(directory, "h2").use { warehouse ->
            val definitions = snapshotCatalog(warehouse)
            warehouse.finishSetup()
            val intermediate = MetricValueContract(unit, MetricValueContract.Exact(), MetricValueContract.Decimal(38, 12, MetricValueContract.Rounding.HALF_DOWN),
                emptyMap(), MetricValueContract.Overflow.FAIL)
            val outerMean = MetricValueContract(unit, MetricValueContract.Exact(), MetricValueContract.Decimal(60, 22, MetricValueContract.Rounding.HALF_DOWN),
                emptyMap(), MetricValueContract.Overflow.FAIL)
            fun input(aggregation: MetricAggregation, policy: MetricSnapshotRule.NullPolicy, withSnapshot: Boolean = true): ExecutableInputScope {
                val base = snapshotInput(definitions, "h2", if (policy == MetricSnapshotRule.NullPolicy.PROPAGATE) "MEAN_PROPAGATE" else "MEAN_IGNORE")
                val original = base.metrics.single()
                val fact = original.metric.factMeasure()
                val oldRule = requireNotNull(fact.snapshotRule)
                val rule = if (withSnapshot) MetricSnapshotRule(oldRule.reduction, oldRule.entityRole, oldRule.entityKeys,
                    oldRule.timeKey, oldRule.tieBreakKeys, policy, intermediate, MeanTarget.MATHEMATICAL) else null
                val output = when (aggregation) {
                    MetricAggregation.COUNT -> ordinal
                    MetricAggregation.AVG -> if (withSnapshot) outerMean else intermediate
                    else -> intermediate
                }
                val definition = MetricDefinition.FactMeasure(fact.event, fact.input, aggregation, false, fact.privatePredicate, fact.roles,
                    rule, output, if (aggregation == MetricAggregation.AVG) MeanTarget.MATHEMATICAL else null)
                val invocation = original.invocation.copy(metric = original.metric.copy(definition = definition))
                val selected = ExecutableMetricInput(invocation, original.selection, original.event, original.topology, original.timeAnchor,
                    original.rangeDimension, original.analysisDimensions, original.filterExpression, original.relationConditions, original.objectRestrictions)
                return ExecutableInputScope(base.inputMode, base.measures().copy(calls = listOf(ExecutableMetricCall.Fact(selected))),
                    base.objectRestrictions, base.additionalInputs, base.postOps)
            }
            for (policy in listOf(MetricSnapshotRule.NullPolicy.IGNORE, MetricSnapshotRule.NullPolicy.PROPAGATE)) {
                for (aggregation in listOf(MetricAggregation.SUM, MetricAggregation.COUNT, MetricAggregation.AVG)) {
                    val values = warehouse.execute(compile(definitions.catalog(), input(aggregation, policy))).associate { it[0] as String to it[1] }
                    assertEquals(setOf("A", "B", "C"), values.keys)
                    val label = "$aggregation:$policy"
                    // A 有四个完整实体：52/3、30、10、2。先向12位量化，再对四个完成值聚合。
                    when (aggregation) {
                        MetricAggregation.SUM -> assertEquals(BigDecimal("59.333333333333"), values["A"], label)
                        MetricAggregation.COUNT -> assertEquals(4L, values["A"], label)
                        MetricAggregation.AVG -> assertEquals(BigDecimal("14.8333333333332500000000"), values["A"], label)
                        else -> error("this snapshot trial uses SUM, COUNT and AVG")
                    }
                    for ((region, amount) in listOf("B" to "7", "C" to "3")) {
                        if (aggregation == MetricAggregation.COUNT) {
                            assertEquals(if (policy == MetricSnapshotRule.NullPolicy.PROPAGATE) 0L else 1L, values[region], "$label:$region")
                        } else if (policy == MetricSnapshotRule.NullPolicy.PROPAGATE) {
                            assertNull(values[region], "$label:$region")
                        } else {
                            val scale = if (aggregation == MetricAggregation.AVG) 22 else 12
                            assertEquals(BigDecimal(amount).setScale(scale), values[region], "$label:$region")
                        }
                    }
                }
            }
            val raw = warehouse.execute(compile(definitions.catalog(), input(MetricAggregation.AVG, MetricSnapshotRule.NullPolicy.IGNORE, false)))
                .associate { it[0] as String to it[1] }
            assertEquals(mapOf("A" to BigDecimal("16.750000000000"), "B" to BigDecimal("7.000000000000"), "C" to BigDecimal("3.000000000000")), raw)
        }
    }
    private fun temporalTable(definitions: Definitions, warehouse: NativeWarehouse, table: String, types: Map<String, ScalarValueType>, dimensions: Map<String, String>, accountColumn: String? = null) {
        definitions.physical["$table@1"] = warehouse.adopt(table, types.keys.associate { "$table.$it" to it })
        definitions.topologies["$table@1"] = ExecutableTopology(table, "1", null, null, table, "1")
        definitions.events["$table@1"] = ExecutableEvent(table, "1", table, "1", null,
            EventIdentity(listOf(EventIdentity.LogicUnitRef("$table.id", 1)), if (accountColumn == null) emptyMap() else mapOf("account" to listOf(EventIdentity.LogicUnitRef("$table.$accountColumn", 1)))),
            if (accountColumn == null) emptyMap() else mapOf("account" to local), null, "$table.occurred", "1")
        completeTemporalCatalog(definitions, table, types, dimensions)
    }
    private fun refundCatalog(warehouse: NativeWarehouse): Definitions {
        val definitions = Definitions(ExecutableCatalog(ExecutableCatalogManifest("refunds", ExecutableCatalogManifest.SCHEMA_VERSION)))
        temporalTable(definitions, warehouse, "refunds", listOf("id", "customer", "amount", "cumulative", "reversal_of").associateWith { ScalarValueType.NUMBER } + ("occurred" to ScalarValueType.DATE), mapOf("customer" to "customer", "time" to "occurred"), "customer")
        projectedRecords(definitions, "refund-records", "refunds", listOf("id", "amount", "cumulative", "reversal_of").associateWith { ScalarValueType.NUMBER }, mapOf("amount" to exact, "cumulative" to exact), listOf("customer", "time"))
        return definitions
    }
    private fun associatedRefundRecords(definitions: Definitions): MemberRef {
        val source = definitions.members.getValue("refund-records@1")
        val stages = listOf(
            PostOperationContract.JoinInput("original", "original", "LEFT", operation(ResultOperator.AND, operation(ResultOperator.EQ, symbol("reversal_of"), symbol("original__id")), operation(ResultOperator.EQ, symbol("customer"), symbol("original__customer")))),
            PostOperationContract.PostFilter(operation(ResultOperator.OR, operation(ResultOperator.IS_NULL, symbol("reversal_of")), operation(ResultOperator.IS_NOT_NULL, symbol("original__id")))),
            PostOperationContract.Derive(ResultNumeric(ResultCaseWhen(listOf(ResultCaseBranch(operation(ResultOperator.IS_NOT_NULL, symbol("reversal_of")), operation(ResultOperator.NEGATE, symbol("amount")))), symbol("amount")), exact, null), "signed_amount", "已关联冲回或正向退回"),
            PostOperationContract.Project(listOf("id", "customer", "time", "signed_amount", "cumulative", "reversal_of")))
        val body = memberCalculation(source, null, emptyMap(), stages)
        val original = Calculation.Scope(body.computation.root, emptyMap(), listOf(PostOperationContract.PostFilter(operation(ResultOperator.IS_NULL, symbol("reversal_of")))))
        val calculation = Calculation(body.dimensionSlots, body.memberSlots, emptyList(), emptyList(), emptyMap(), Calculation.Scope(body.computation.root, mapOf("original" to Calculation.InputScope("RELATION", original)), stages))
        return computedRecords(definitions, "associated-refunds", source, calculation, listOf("id", "customer", "time", "signed_amount", "cumulative", "reversal_of"), listOf("id"))
    }
    private fun migrationProjection(definitions: Definitions, warehouse: NativeWarehouse, table: String, quantity: MetricValueContract): MemberSource.Projection {
        temporalTable(definitions, warehouse, table, listOf("id", "customer", "amount").associateWith { ScalarValueType.NUMBER } + ("occurred" to ScalarValueType.DATE), mapOf("customer" to "customer", "time" to "occurred"), "customer")
        return projectedRecords(definitions, "$table-records", table, mapOf("id" to ScalarValueType.NUMBER, "amount" to ScalarValueType.NUMBER), mapOf("amount" to quantity), listOf("customer", "time"))
    }
    private fun migratedRefundRecords(definitions: Definitions, warehouse: NativeWarehouse): MemberRef {
        val cents = MetricValueContract.UnitIdentity("trial", "USD_CENT", 1)
        val minor = MetricValueContract(MetricValueContract.Fixed(cents), MetricValueContract.Exact(), MetricValueContract.Integer(64), emptyMap(), MetricValueContract.Overflow.FAIL)
        val equation = MetricValueContract.Scale(MetricValueContract.BinaryOperator.MULTIPLY, cents, unit.identity, "0.01")
        val conversion = MetricValueContract(unit, exact.arithmetic, exact.result, mapOf("minor_to_major" to equation), MetricValueContract.Overflow.FAIL)
        val legacy = migrationProjection(definitions, warehouse, "legacy_refunds", minor)
        val current = migrationProjection(definitions, warehouse, "current_refunds", exact)
        val cutoff = ResultTypedLiteral(ScalarValueType.DATE, "2026-01-02")
        val old = computedRecords(definitions, "legacy-authoritative-refunds", legacy, listOf(
            PostOperationContract.PostFilter(operation(ResultOperator.LT, symbol("time"), cutoff)),
            PostOperationContract.Derive(ResultNumeric(operation(ResultOperator.MULTIPLY, symbol("amount"), ResultLiteral(BigDecimal("0.01"))), conversion, "minor_to_major"), "value", "主单位退回金额"),
            PostOperationContract.Project(listOf("customer", "time", "value"))), listOf("customer", "time", "value"), emptyList())
        val new = computedRecords(definitions, "current-authoritative-refunds", current, listOf(
            PostOperationContract.PostFilter(operation(ResultOperator.GTE, symbol("time"), cutoff)), PostOperationContract.Derive(symbol("amount"), "value", "主单位退回金额"),
            PostOperationContract.Project(listOf("customer", "time", "value"))), listOf("customer", "time", "value"), emptyList())
        val dimensions = listOf("customer", "time").map { Calculation.DimensionSlot(it, it, 1, it) }
        val outputs = mapOf<String, Calculation.MemberOutput>("customer" to Calculation.MemberDimension("customer", null), "time" to Calculation.MemberDimension("time", null), "value" to Calculation.MemberProperty("amount", ScalarValueType.NUMBER, exact))
        val sources = mapOf("legacy" to old, "current" to new)
        val selected = listOf("customer", "time", "value")
        val body = Calculation(dimensions, sources.map { (name, ref) -> Calculation.MemberSlot(name, "account", "account", MemberShape.RAW_BAG, outputs, ref) }, emptyList(), emptyList(), emptyMap(), Calculation.Scope(Calculation.SetCombination(Calculation.SetKind.UNION, true, sources.keys.map { Calculation.SetProjection(it, selected) }, listOf(
            SetOutputContract("customer", SetOutputContract.DimensionSlot("customer")), SetOutputContract("time", SetOutputContract.DimensionSlot("time")), SetOutputContract("value", SetOutputContract.Quantity(exact)))),
            sources.keys.associateWith { name -> Calculation.InputScope("ROOT", Calculation.Scope(Calculation.Members(name, Calculation.MemberParameter(name), selected.map { Calculation.MemberProjection(it, it, null) }, null, emptyMap(), MemberCondition.Unrestricted(), emptyMap(), emptyMap(), emptyMap()), emptyMap(), emptyList())) }, emptyList()))
        val bindings = CalculationBindings(temporalBindings(definitions, body, null).dimensions, sources.mapValues { (_, ref) -> CalculationBindings.SourceCall(ref, emptyMap(), selected.associateWith { it }, emptyMap(), emptyMap()) }, emptyMap(), emptyMap(), emptyMap())
        val result = MemberResult("account", "account", MemberShape.RAW_BAG, emptyList(), mapOf(
            "customer" to MemberSourceDefinition.ComputedDimensionOutput("customer", "customer", ScalarValueType.NUMBER, "customer", "customer", null),
            "time" to MemberSourceDefinition.ComputedDimensionOutput("time", "time", ScalarValueType.DATE, "time", "time", null),
            "value" to MemberSourceDefinition.ComputedPropertyOutput("value", "amount", ScalarValueType.NUMBER, "value", exact)), emptyMap(), "time", emptyMap(), "按切换日统一主单位的权威记录")
        definitions.members["migrated-refunds@1"] = MemberSource.Computed("migrated-refunds", 1, MemberSourceDefinition.Computed(body, result), bindings, emptyMap())
        return MemberRef("migrated-refunds", 1)
    }
    private fun refundMeasurement(definitions: Definitions, records: MemberRef, period: TemporalWindow): MetricInstanceRef {
        val source = definitions.members.getValue("${records.id}@${records.version}")
        val calculation = memberCalculation(source, Calculation.Argument("period"), mapOf("period" to Calculation.Parameter(Calculation.ParameterKind.RANGE, "DATE", emptyList(), null, null)), listOf(sumAt(listOf("customer"), "value", "total")))
        val definition = MetricRef("${records.id}-sum", "1")
        definitions.metrics[definition.key()] = ExecutableMetric(definition, MetricCalculation(calculation, "total", listOf("customer"), exact))
        val summed = MetricInstanceRef("${records.id}-sum-instance")
        definitions.instances[summed.id] = MetricInstance(definition, temporalBindings(definitions, calculation, source), mapOf("period" to Calculation.Range(period)), "本期已知差额")
        val customer = Calculation.DimensionSlot("customer", "customer", 1, "customer")
        val wrapper = MetricRef("refund-measurement", "1")
        val body = Calculation(dimensionSlots = listOf(customer), measurementSlots = listOf(Calculation.MeasurementSlot("net", emptyMap(), listOf(customer), emptyList(), exact)), computation = Calculation.Scope(Calculation.Measures(listOf(Calculation.MeasurementCall("net", "net", outputAlias = "value")), (listOf("customer")).map { Calculation.DimensionParameter(it) }, emptyList(), null, emptyMap(), Calculation.Condition(DimensionCondition.Unrestricted()), DimensionCondition.Unrestricted(), emptyList(), RowPopulationContract.calls(listOf("net")), null), emptyMap(), emptyList()))
        definitions.metrics[wrapper.key()] = ExecutableMetric(wrapper, MetricCalculation(body, "value", listOf("customer"), exact))
        val instance = MetricInstanceRef("${records.id}-measurement")
        definitions.instances[instance.id] = MetricInstance(wrapper, CalculationBindings(temporalBindings(definitions, body, null).dimensions, emptyMap(), emptyMap(), mapOf("net" to summed), emptyMap()), emptyMap(), "明确绑定已形成记录的测量")
        return instance
    }

    @Test fun formsRefundRecordsBeforeDisplaySelectionAndConsumesThemAsMeasurements(@TempDir directory: Path) {
        NativeWarehouse(directory, "duckdb", listOf(
            "CREATE TABLE refunds(id INTEGER PRIMARY KEY, customer INTEGER NOT NULL, occurred DATE NOT NULL, amount DECIMAL(18,2), cumulative DECIMAL(18,2), reversal_of INTEGER)",
            "INSERT INTO refunds VALUES (1,1,'2025-12-31',100,100,NULL),(2,1,'2026-01-01',30,130,NULL),(3,1,'2026-01-02',20,150,NULL),(4,1,'2026-01-03',10,140,2),(5,2,'2026-01-01',30,130,NULL),(6,2,'2026-01-02',20,150,NULL),(7,2,'2026-01-03',10,140,5),(8,1,'2026-01-02',900,NULL,999)",
            "CREATE TABLE legacy_refunds(id INTEGER PRIMARY KEY, customer INTEGER NOT NULL, occurred DATE NOT NULL, amount BIGINT)", "INSERT INTO legacy_refunds VALUES (1,3,'2026-01-01',1000),(2,3,'2026-01-02',2000)",
            "CREATE TABLE current_refunds(id INTEGER PRIMARY KEY, customer INTEGER NOT NULL, occurred DATE NOT NULL, amount DECIMAL(18,2))", "INSERT INTO current_refunds VALUES (1,3,'2026-01-02',20),(2,3,'2026-01-03',-5)")).use { warehouse ->
            val definitions = refundCatalog(warehouse); val associated = associatedRefundRecords(definitions); val records = definitions.members.getValue("${associated.id}@1")
            val increment = computedRecords(definitions, "increment-refunds", records, listOf(PostOperationContract.Derive(symbol("signed_amount"), "value", "本次退回金额"), PostOperationContract.Project(listOf("id", "customer", "time", "value"))), listOf("id", "customer", "time", "value"), listOf("id"))
            val cumulative = computedRecords(definitions, "cumulative-refunds", records, listOf(
                PostOperationContract.OffsetValue("cumulative", listOf("customer"), "previous", "前次累计", temporalOrder(), "LAG", 1),
                PostOperationContract.Derive(ResultNumeric(operation(ResultOperator.SUBTRACT, symbol("cumulative"), symbol("previous")), exact, null), "value", "本次累计差额"),
                PostOperationContract.Project(listOf("id", "customer", "time", "value", "previous", "cumulative"))), listOf("id", "customer", "time", "value", "previous", "cumulative"), listOf("id"))
            val january = TemporalWindow(TemporalWindow.ValueType.DATE, TemporalWindow.BOUNDARY, "2026-01-01", "2026-02-01")
            val increments = refundMeasurement(definitions, increment, january); val cumulativeMeasurement = refundMeasurement(definitions, cumulative, january)
            val migrated = refundMeasurement(definitions, migratedRefundRecords(definitions, warehouse), january); warehouse.finishSetup()
            val formed = executeTemporal(definitions, warehouse, "cumulative-refund-records", selectedMembers(cumulative, listOf("customer", "time", "value", "previous", "cumulative"), january,
                listOf(PostOperationContract.Sort(listOf(PostOperationContract.OrderKey("customer", "ASC", "LAST"), PostOperationContract.OrderKey("time", "ASC", "LAST")))))).businessResult.rows
            val complete = formed.filter { scalar(it.first()) == "1" }; val missing = formed.filter { scalar(it.first()) == "2" }
            assertEquals(listOf("30", "20", "-10").map(::decimal), complete.map { decimal(it[2]) }); assertEquals(decimal("100"), decimal(complete.first()[3]))
            assertEquals(3, missing.size); assertInstanceOf(ExecutableBusinessResult.NullValue::class.java, missing.first()[2]); assertInstanceOf(ExecutableBusinessResult.NullValue::class.java, missing.first()[3])
            val measured = executeTemporal(definitions, warehouse, "refund-measurements", measureScope(listOf(
                Calculation.InstanceCall("increments", increments), Calculation.InstanceCall("cumulative", cumulativeMeasurement)), listOf(dimension("customer")))).businessResult.rows.associateBy { scalar(it.first()) }
            assertEquals(setOf("1", "2"), measured.keys)
            assertEquals(decimal("40"), decimal(measured.getValue("1")[1])); assertEquals(decimal("40"), decimal(measured.getValue("1")[2]))
            assertEquals(decimal("40"), decimal(measured.getValue("2")[1])); assertEquals(decimal("10"), decimal(measured.getValue("2")[2]))
            val migratedRows = executeTemporal(definitions, warehouse, "migration-refund-measurement", measureScope(listOf(Calculation.InstanceCall("migrated", migrated, outputAlias = "value")), listOf(dimension("customer")))).businessResult.rows
            assertEquals(1, migratedRows.size); assertEquals("3", scalar(migratedRows.first().first())); assertEquals(decimal("25"), decimal(migratedRows.first()[1]))
        }
    }

    private fun snapshotRecordSource(definitions: Definitions, eligible: Boolean): MemberSource.Projection {
        completeTemporalCatalog(definitions, "snapshots", mapOf("id" to ScalarValueType.NUMBER, "customer" to ScalarValueType.NUMBER, "book" to ScalarValueType.TEXT, "region" to ScalarValueType.TEXT,
            "occurred" to ScalarValueType.LOCAL_TIMESTAMP, "amount" to ScalarValueType.NUMBER, "eligible" to ScalarValueType.BOOLEAN), mapOf("customer" to "customer", "book" to "book", "region" to "region", "time" to "occurred"))
        return projectedRecords(definitions, if (eligible) "snapshot-records" else "versioned-accounts", "snapshots", mapOf("id" to ScalarValueType.NUMBER, "amount" to ScalarValueType.NUMBER) +
            if (eligible) mapOf("eligible" to ScalarValueType.BOOLEAN) else emptyMap(), mapOf("amount" to exact), listOf("customer", "book", "region", "time"))
    }
    private fun selectedSnapshotRecords(definitions: Definitions, source: MemberSource, id: String, before: Boolean, after: Boolean): MemberRef {
        val stages = mutableListOf<ValidatedOperation>(PostOperationContract.PostFilter(operation(ResultOperator.AND,
            operation(ResultOperator.GTE, symbol("time"), ResultTypedLiteral(ScalarValueType.LOCAL_TIMESTAMP, "2026-01-01T00:00:00")),
            operation(ResultOperator.LT, symbol("time"), ResultTypedLiteral(ScalarValueType.LOCAL_TIMESTAMP, "2026-02-01T00:00:00")))))
        val eligible = PostOperationContract.PostFilter(operation(ResultOperator.EQ, symbol("eligible"), ResultLiteral(true)))
        if (before) stages += eligible
        stages += PostOperationContract.OrderedRank(PostOperationContract.ROW_NUMBER, listOf("customer", "book"), "selected", "实际最新版本", listOf(PostOperationContract.OrderKey("time", "DESC", "LAST"), PostOperationContract.OrderKey("id", "DESC", "LAST")))
        stages += PostOperationContract.PostFilter(operation(ResultOperator.EQ, symbol("selected"), ResultQuantityLiteral(BigDecimal.ONE, ordinal)))
        if (after) stages += eligible
        stages += sumAt(listOf("region"), "amount", "value")
        return computedRecords(definitions, id, source, stages, listOf("region", "value"), listOf("region"))
    }
    private fun snapshotObservations(definitions: Definitions): MemberRef {
        val metric = MetricRef("observed-balance", "1")
        val snapshot = MetricSnapshotRule(MetricSnapshotRule.Reduction.LAST, "account", listOf(MetricSnapshotRule.LogicUnitKey("snapshots.customer", 1), MetricSnapshotRule.LogicUnitKey("snapshots.book", 1)),
            MetricSnapshotRule.LogicUnitKey("snapshots.occurred", 1), listOf(MetricSnapshotRule.LogicUnitKey("snapshots.id", 1)), MetricSnapshotRule.NullPolicy.PRESERVE_SELECTED, null)
        definitions.metrics[metric.key()] = ExecutableMetric(metric, MetricDefinition.FactMeasure(EventRef("snapshots", 1), SemanticExpression.Reference(SemanticExpression.ReferenceKind.LOGIC_UNIT, "snapshots.amount", 1), MetricAggregation.SUM, false, null, emptyMap(), snapshot, exact))
        val names = listOf("customer", "book", "time")
        val calculation = Calculation(dimensionSlots = names.map { Calculation.DimensionSlot(it, it, 1, it) }, computation = Calculation.Scope(Calculation.Measures(listOf(Calculation.DefinitionCall("balance", metric, outputAlias = "value")), (names).map { Calculation.DimensionParameter(it) }, emptyList(), Calculation.TimeRange(Calculation.DimensionParameter("time"), Calculation.Range(TemporalWindow(TemporalWindow.ValueType.LOCAL_TIMESTAMP, TemporalWindow.BOUNDARY, "2026-01-01T00:00:00", "2026-02-01T00:00:00"))), emptyMap(), Calculation.Condition(DimensionCondition.Unrestricted()), DimensionCondition.Unrestricted(), emptyList(), RowPopulationContract.calls(listOf("balance")), null), emptyMap(), emptyList()))
        val outputs = linkedMapOf<String, MemberSourceDefinition.Output>()
        names.forEach { outputs[it] = MemberSourceDefinition.ComputedDimensionOutput(it, it, when (it) { "time" -> ScalarValueType.LOCAL_TIMESTAMP; "customer" -> ScalarValueType.NUMBER; else -> ScalarValueType.TEXT }, it, it, null) }
        outputs["value"] = MemberSourceDefinition.ComputedPropertyOutput("时点余额", "amount", ScalarValueType.NUMBER, "value", exact)
        definitions.members["snapshot-observations@1"] = MemberSource.Computed("snapshot-observations", 1, MemberSourceDefinition.Computed(calculation,
            MemberResult("account_observation", "account_observation", MemberShape.RECORDS, names, outputs, emptyMap(), "time", emptyMap(), "实体与时点的实际余额观察")), temporalBindings(definitions, calculation, null), emptyMap())
        return MemberRef("snapshot-observations", 1)
    }
    @Test fun formsVersionedComputedRecordsWithoutErasingSnapshotObservationAxes(@TempDir directory: Path) {
        snapshotWarehouse(directory, "duckdb").use { warehouse ->
            val definitions = snapshotCatalog(warehouse); val records = snapshotRecordSource(definitions, true)
            val policies = mapOf(selectedSnapshotRecords(definitions, records, "latest-balances", false, false) to "87", selectedSnapshotRecords(definitions, records, "latest-then-success", false, true) to "57", selectedSnapshotRecords(definitions, records, "success-then-latest", true, false) to "69")
            val observations = snapshotObservations(definitions); warehouse.finishSetup()
            policies.forEach { (ref, expected) ->
                val rows = executeTemporal(definitions, warehouse, ref.id, selectedMembers(ref, listOf("region", "value"), null, emptyList())).businessResult.rows
                assertEquals(decimal(expected), decimal(rows.first { scalar(it.first()) == "A" }[1]))
            }
            val rows = executeTemporal(definitions, warehouse, "same-time-balances", selectedMembers(observations, listOf("customer", "book", "time", "value"), null, listOf(sumAt(listOf("time"), "value", "total")))).businessResult.rows
            assertEquals(mapOf("2026-01-01" to decimal("18"), "2026-01-02" to decimal("22"), "2026-01-03" to decimal("92")), rows.associate { scalar(it.first()).take(10) to decimal(it[1]) })
            // 明确相加各时点的实际余额值，不将其解释为某个单独时点的余额。
            val acrossTimes = executeTemporal(definitions, warehouse, "cross-time-balances", selectedMembers(observations,
                listOf("customer", "book", "time", "value"), null, listOf(sumAt(listOf("customer", "book"), "value", "total")))).businessResult.rows
            assertEquals(mapOf(("1" to "cash") to decimal("40"), ("2" to "cash") to decimal("60"),
                ("3" to "cash") to decimal("2"), ("3" to "margin") to decimal("20"),
                ("4" to "cash") to decimal("7"), ("5" to "cash") to decimal("3")),
                acrossTimes.associate { (scalar(it[0]) to scalar(it[1])) to decimal(it[2]) })
        }
    }
    private fun versionedStock(definitions: Definitions, records: MemberSource, id: String, point: Calculation.Scalar, totalOrder: Boolean): MemberRef {
        val order = listOf(PostOperationContract.OrderKey("time", "DESC", "LAST")) + if (totalOrder) listOf(PostOperationContract.OrderKey("id", "DESC", "LAST")) else emptyList()
        return computedRecords(definitions, id, records, listOf(PostOperationContract.SelectVersions(listOf("customer", "book"), order, PostOperationContract.VersionTies.FAIL, PostOperationContract.SnapshotSelection("time", point))), listOf("customer", "book", "region", "time", "amount"), listOf("customer", "book"))
    }
    private fun stockCount(stock: MemberRef, grouping: List<String>) = selectedMembers(stock, listOf("customer", "book", "region"), null,
        listOf(PostOperationContract.Regroup(PostOperationContract.ResultGrouping(grouping, listOf(PostOperationContract.ResultAggregate("customer", ResultAggregation.Count(false, ResultAggregation.NullPolicy.IGNORE), "objects", "已知对象数")), null))))
    private fun countSum(input: String, output: String) = PostOperationContract.Regroup(PostOperationContract.ResultGrouping(emptyList(), listOf(PostOperationContract.ResultAggregate(input, ResultAggregation.Sum(ordinal, ResultAggregation.NullPolicy.IGNORE), output, output)), null))
    @Test fun selectsVersionsWithExplicitTiesAndPreservesAsOfStockCounts(@TempDir directory: Path) {
        snapshotWarehouse(directory, "duckdb").use { warehouse ->
            val definitions = snapshotCatalog(warehouse); val records = snapshotRecordSource(definitions, false)
            val firstPoint = Calculation.Scalar(ScalarValueType.LOCAL_TIMESTAMP, "2026-01-01T23:59:59"); val lastPoint = Calculation.Scalar(ScalarValueType.LOCAL_TIMESTAMP, "2026-01-03T23:59:59")
            val ambiguous = versionedStock(definitions, records, "ambiguous-stock", lastPoint, false)
            val first = versionedStock(definitions, records, "first-stock", firstPoint, true); val last = versionedStock(definitions, records, "last-stock", lastPoint, true); warehouse.finishSetup()
            val tie = assertThrows(EngineExecutionException::class.java) { executeTemporal(definitions, warehouse, "tied-latest-version", selectedMembers(ambiguous, listOf("customer", "book", "amount"), null, emptyList())) }
            assertEquals("VERSION_SELECTION_TIE", tie.code)
            val firstCount = stockCount(first, emptyList()); val lastCount = stockCount(last, emptyList())
            assertEquals(decimal("4"), decimal(executeTemporal(definitions, warehouse, "first-stock-count", firstCount).businessResult.rows.first().first()))
            assertEquals(decimal("6"), decimal(executeTemporal(definitions, warehouse, "last-stock-count", lastCount).businessResult.rows.first().first()))
            val region = stockCount(last, listOf("region"))
            assertEquals(decimal("6"), decimal(executeTemporal(definitions, warehouse, "same-point-region-count", Calculation.Scope(region.root, region.inputs, listOf(region.stages.first(), countSum("objects", "total")))).businessResult.rows.first().first()))
            val cross = Calculation.Scope(Calculation.SetCombination(Calculation.SetKind.UNION, false, listOf(Calculation.SetProjection("first", listOf("objects")), Calculation.SetProjection("last", listOf("objects"))), listOf(SetOutputContract("objects", SetOutputContract.Quantity(ordinal)))), mapOf("first" to Calculation.InputScope("ROOT", firstCount), "last" to Calculation.InputScope("ROOT", lastCount)), listOf(countSum("objects", "observed_total")))
            // 4与6是两个已选时点的完成计数；其和10并非任一时点或跨时点去重对象数。
            val different = executeTemporal(definitions, warehouse, "different-stock-points", cross).businessResult.rows
            assertEquals(decimal("10"), decimal(different.single().single()))
        }
    }

    private fun observationWindowCatalog(warehouse: NativeWarehouse): Definitions {
        val definitions = Definitions(ExecutableCatalog(ExecutableCatalogManifest("observation-windows", ExecutableCatalogManifest.SCHEMA_VERSION)))
        temporalTable(definitions, warehouse, "observations", listOf("id", "tenant", "item", "amount").associateWith { ScalarValueType.NUMBER } + ("occurred" to ScalarValueType.DATE), mapOf("time" to "occurred"))
        temporalTable(definitions, warehouse, "calendar", mapOf("id" to ScalarValueType.NUMBER, "occurred" to ScalarValueType.DATE), mapOf("time" to "occurred"))
        projectedRecords(definitions, "window-records", "observations", listOf("id", "tenant", "item", "amount").associateWith { ScalarValueType.NUMBER }, mapOf("amount" to exact), listOf("time"))
        val calendar = projectedRecords(definitions, "calendar-days", "calendar", mapOf("id" to ScalarValueType.NUMBER), emptyMap(), listOf("time"))
        definitions.members["calendar-days@1"] = MemberSource.Projection(calendar.id, calendar.version, MemberSourceDefinition.Projection(calendar.definition.event,
            MemberResult("calendar_day", "calendar_day", MemberShape.RECORDS, listOf("time"), calendar.definition.result.outputs, emptyMap(), "time", emptyMap(), "实际逐日来源，不声明业务活动存在")), calendar.outputExpressions)
        return definitions
    }
    private fun observationWindow(domain: PostOperationContract.ObservationDomain, inputs: List<String>, aggregation: ResultAggregation, frame: WindowFrameContract.Frame,
        observations: Calculation.Range, display: Calculation.Range, calendar: PostOperationContract.CalendarAxis?) = PostOperationContract.ObserveWindow(
        inputs, emptyList(), "rolling", "观察窗口结果", if (domain == PostOperationContract.ObservationDomain.ROWS) temporalOrder() else listOf(PostOperationContract.OrderKey("time", "ASC", "LAST")), aggregation,
        frame, domain, "time", observations, display, calendar)
    private fun calendarWindow(definitions: Definitions, records: MemberSource, calendar: MemberSource, observations: Calculation.Range, display: Calculation.Range, frame: WindowFrameContract.Frame): Calculation.Scope {
        val sources = linkedMapOf("observations" to records, "calendar" to calendar)
        val slots = mutableListOf<Calculation.MemberSlot>(); val calls = linkedMapOf<String, CalculationBindings.Member>(); val computations = linkedMapOf<String, Calculation.Scope>()
        sources.forEach { (name, source) ->
            val existing = memberCalculation(source, null, emptyMap(), emptyList()).memberSlots.first()
            slots += Calculation.MemberSlot(name, existing.entity, existing.role, existing.shape, existing.outputs, existing.fixedSource)
            calls[name] = CalculationBindings.SourceCall(MemberRef(source.id, source.version), emptyMap(), source.definition.result.outputs.keys.associateWith { it }, emptyMap(), emptyMap())
            computations[name] = Calculation.Scope(Calculation.Members(name, Calculation.MemberParameter(name), source.definition.result.outputs.keys.sorted().map { Calculation.MemberProjection(it, it, null) }, null, emptyMap(), MemberCondition.Unrestricted(), emptyMap(), emptyMap(), emptyMap()), emptyMap(),
                if (name == "observations") listOf(sumAt(listOf("time"), "amount", "daily")) else listOf(PostOperationContract.Project(listOf("time"))))
        }
        val body = Calculation(listOf(Calculation.DimensionSlot("time", "time", 1, "time")), slots, emptyList(), emptyList(), emptyMap(), Calculation.Scope(Calculation.AlignMeasurements(mapOf(
            "calendar" to Calculation.AlignedInput(computations.getValue("calendar"), mapOf("time" to "time"), emptyList()),
            "observations" to Calculation.AlignedInput(computations.getValue("observations"), mapOf("time" to "time"), listOf(Calculation.AlignedValue("daily", "value", Calculation.NoMatch.PRESERVE_NULL)))), listOf("time"), Calculation.AlignmentPopulation(Calculation.PopulationKind.BASE, listOf("calendar"))),
            emptyMap(), listOf(observationWindow(PostOperationContract.ObservationDomain.CALENDAR, listOf("value"), ResultAggregation.Sum(exact, ResultAggregation.NullPolicy.IGNORE), frame, observations, display, PostOperationContract.CalendarAxis("calendar", "time")))))
        return Calculation.Scope(Calculation.Compute(body, Calculation.ExplicitBindings(CalculationBindings(temporalBindings(definitions, body, null).dimensions, calls, emptyMap(), emptyMap(), emptyMap())), emptyMap()), emptyMap(), emptyList())
    }
    private fun windowForRecord(definitions: Definitions, warehouse: NativeWarehouse, request: String, scope: Calculation.Scope, id: String) =
        executeTemporal(definitions, warehouse, request, scope).businessResult.rows.first { scalar(it.first()) == id }.last().let(::decimal)

    @Test fun observesRowsElapsedAndCalendarWindowsBeforeDisplayAndCountsCompleteObjectTuples(@TempDir directory: Path) {
        NativeWarehouse(directory, "duckdb", listOf(
            "CREATE TABLE observations(id INTEGER PRIMARY KEY, tenant INTEGER, item INTEGER NOT NULL, occurred DATE NOT NULL, amount DECIMAL(18,2))",
            "INSERT INTO observations VALUES (1,1,7,'2026-01-01',10),(2,2,7,'2026-01-01',20),(3,1,7,'2026-01-03',30),(4,NULL,9,'2026-01-03',NULL),(5,2,7,'2026-01-04',40)",
            "CREATE TABLE calendar(id INTEGER PRIMARY KEY, occurred DATE UNIQUE NOT NULL)", "INSERT INTO calendar VALUES (1,'2026-01-01'),(2,'2026-01-02'),(3,'2026-01-03'),(4,'2026-01-04')")).use { warehouse ->
            val definitions = observationWindowCatalog(warehouse); val records = definitions.members.getValue("window-records@1"); val calendar = definitions.members.getValue("calendar-days@1")
            val gapped = computedRecords(definitions, "gapped-calendar", calendar, listOf(PostOperationContract.PostFilter(operation(ResultOperator.NE, symbol("time"), ResultTypedLiteral(ScalarValueType.DATE, "2026-01-02")))), listOf("time"), listOf("time"))
            fun range(start: String, end: String) = Calculation.Range(TemporalWindow(TemporalWindow.ValueType.DATE, TemporalWindow.BOUNDARY, start, end))
            val observations = range("2026-01-01", "2026-01-05"); val display = range("2026-01-03", "2026-01-04")
            val rowFrame = WindowFrameContract.Frame("ROWS", WindowFrameContract.Bound("PRECEDING", 1, "ROW"), WindowFrameContract.Bound("CURRENT_ROW", null, null))
            val dayFrame = WindowFrameContract.Frame("RANGE", WindowFrameContract.Bound("PRECEDING", 1, "DAY"), WindowFrameContract.Bound("CURRENT_ROW", null, null))
            val rowWindow = observationWindow(PostOperationContract.ObservationDomain.ROWS, listOf("amount"), ResultAggregation.Sum(exact, ResultAggregation.NullPolicy.IGNORE), rowFrame, observations, display, null)
            val elapsed = observationWindow(PostOperationContract.ObservationDomain.ELAPSED, listOf("amount"), ResultAggregation.Sum(exact, ResultAggregation.NullPolicy.IGNORE), dayFrame, observations, display, null)
            val clipped = observationWindow(PostOperationContract.ObservationDomain.ROWS, listOf("amount"), ResultAggregation.Sum(exact, ResultAggregation.NullPolicy.IGNORE), rowFrame, display, display, null)
            val calendarScope = calendarWindow(definitions, records, calendar, observations, display, dayFrame)
            val gappedScope = calendarWindow(definitions, records, definitions.members.getValue("${gapped.id}@1"), observations, display, dayFrame)
            warehouse.finishSetup(); val outputs = listOf("id", "time", "amount", "tenant", "item"); val ref = MemberRef(records.id, records.version)
            assertEquals(decimal("50"), windowForRecord(definitions, warehouse, "row-observations", selectedMembers(ref, outputs, null, listOf(rowWindow)), "3"))
            assertEquals(decimal("30"), windowForRecord(definitions, warehouse, "elapsed-observations", selectedMembers(ref, outputs, null, listOf(elapsed)), "3"))
            assertEquals(decimal("30"), windowForRecord(definitions, warehouse, "clipped-observations", selectedMembers(ref, outputs, null, listOf(clipped)), "3"))
            val distinct = observationWindow(PostOperationContract.ObservationDomain.ELAPSED, listOf("tenant", "item"), ResultAggregation.Count(true, ResultAggregation.NullPolicy.IGNORE),
                WindowFrameContract.Frame("RANGE", WindowFrameContract.Bound("PRECEDING", 3, "DAY"), WindowFrameContract.Bound("CURRENT_ROW", null, null)), observations, range("2026-01-04", "2026-01-05"), null)
            assertEquals(decimal("2"), windowForRecord(definitions, warehouse, "complete-object-distinct", selectedMembers(ref, outputs, null, listOf(distinct)), "5"))
            val calendarRows = executeTemporal(definitions, warehouse, "calendar-observations", calendarScope).businessResult.rows
            assertEquals(1, calendarRows.size); assertEquals("2026-01-03", scalar(calendarRows.first().first())); assertEquals(decimal("30"), decimal(calendarRows.first().last()))
            val days = executeTemporal(definitions, warehouse, "calendar-missing-business-day", calendarWindow(definitions, records, calendar, observations, observations, dayFrame)).businessResult.rows
            assertInstanceOf(ExecutableBusinessResult.NullValue::class.java, days.first { scalar(it.first()) == "2026-01-02" }[1])
            val missingCoverage = assertThrows(EngineExecutionException::class.java) { executeTemporal(definitions, warehouse, "calendar-date-gap", gappedScope) }
            assertEquals("CALENDAR_OBSERVATION_COVERAGE_MISSING", missingCoverage.code)
        }
    }

    private fun completeTemporalCatalog(definitions: Definitions, table: String, types: Map<String, ScalarValueType>, dimensions: Map<String, String>) {
        val physical = definitions.physical.getValue("$table@1")
        types.forEach { (column, type) -> definitions.units["$table.$column@1"] = ExecutableLogicUnit("$table.$column", "1", type, null, table, "1", column, null, table, "1") }
        dimensions.forEach { (name, column) ->
            definitions.dimensions["$name@1"] = ExecutableDimension(name, "1", null, "temporal", null, name)
            val binding = "$table-$name"
            definitions.bindings["$binding@1"] = ExecutableDimensionBinding(binding, "1", name, "1", table, "1", table, "1", "$table.$column", "1",
                SemanticExpression.Reference(SemanticExpression.ReferenceKind.LOGIC_UNIT, "$table.$column", 1), local, emptyMap(), emptyList(), physical.bindingId, "1")
            val mappings = definitions.analyses["$name@1"]?.eventMappings.orEmpty().filter { it.eventId != table } +
                ExecutableAnalysisDimensionEventMapping("${table}_${name}_mapping", name, "1", dimension(name).levelCode, table, "1", name, "1", binding, "1", "$table.$column", "1", "same_$name", "PROVEN_SAME_KEY")
            definitions.analyses["$name@1"] = ExecutableAnalysisDimension(name, "1", name, name, name, dimension(name).levelCode,
                listOf(ExecutableAnalysisDimensionLevel(dimension(name).levelCode, name, null)), mappings)
            if (name == "time") definitions.anchors[ExecutableTimeAnchor.key(table, "1", name, "1", dimension(name).levelCode)] =
                ExecutableTimeAnchor(table, "1", name, "1", dimension(name).levelCode, table, "1", null, null, "$table.$column", "1", null, physical.bindingId, "1", null)
        }
    }

    private fun projectedRecords(definitions: Definitions, id: String, table: String, properties: Map<String, ScalarValueType>, quantities: Map<String, MetricValueContract>, dimensions: List<String>): MemberSource.Projection {
        val outputs = linkedMapOf<String, MemberSourceDefinition.Output>(); val expressions = linkedMapOf<String, SemanticExpression>()
        dimensions.forEach { name ->
            val binding = definitions.bindings.getValue("$table-$name@1")
            outputs[name] = MemberSourceDefinition.DimensionOutput(name, name, requireNotNull(definitions.units.getValue("${binding.logicUnitId}@1").valueType), name, 1, binding.bindingId, 1, null)
            expressions[name] = binding.expression
        }
        properties.forEach { (name, type) ->
            outputs[name] = MemberSourceDefinition.PropertyOutput(name, name, type, "$table.$name", 1, local, quantities[name])
            expressions[name] = SemanticExpression.Reference(SemanticExpression.ReferenceKind.LOGIC_UNIT, "$table.$name", 1)
        }
        return MemberSource.Projection(id, 1, MemberSourceDefinition.Projection(EventRef(table, 1), MemberResult("account", "account", MemberShape.RECORDS, listOf("id"), outputs,
            emptyMap(), "time", emptyMap(), "按源记录保留观察与未知值")), expressions).also { definitions.members["$id@1"] = it }
    }

    private fun memberCalculation(source: MemberSource, range: Calculation.Binding?, parameters: Map<String, Calculation.Parameter>, stages: List<ValidatedOperation>): Calculation {
        val dimensions = mutableListOf<Calculation.DimensionSlot>(); val outputs = linkedMapOf<String, Calculation.MemberOutput>()
        source.definition.result.outputs.forEach { (name, output) ->
            if (output is MemberSourceDefinition.DimensionOutput || output is MemberSourceDefinition.ComputedDimensionOutput) {
                dimensions += Calculation.DimensionSlot(name, name, 1, output.role)
                outputs[name] = Calculation.MemberDimension(name, MemberSourceDefinition.rootValueContract(output))
            } else outputs[name] = Calculation.MemberProperty(output.role, MemberSourceDefinition.scalar(output).valueType, MemberSourceDefinition.rootValueContract(output))
        }
        val result = source.definition.result
        return Calculation(dimensions, listOf(Calculation.MemberSlot("records", result.entity, result.role, result.shape, outputs, MemberRef(source.id, source.version))), emptyList(), emptyList(), parameters, Calculation.Scope(Calculation.Members("records", Calculation.MemberParameter("records"), result.outputs.keys.sorted().map { Calculation.MemberProjection(it, it, null) }, range, emptyMap(), MemberCondition.Unrestricted(), emptyMap(), emptyMap(), emptyMap()), emptyMap(), stages))
    }

    private fun temporalBindings(definitions: Definitions, calculation: Calculation, source: MemberSource?): CalculationBindings {
        val dimensions = calculation.dimensionSlots.associate { it.name to CalculationBindings.Dimension(dimension(it.name), it.role,
            definitions.analyses.getValue("${it.name}@1").eventMappings.map { mapping -> mapping.mappingId }) }
        val members: Map<String, CalculationBindings.Member> = if (source == null) emptyMap() else mapOf("records" to CalculationBindings.SourceCall(MemberRef(source.id, source.version), emptyMap(), source.definition.result.outputs.keys.associateWith { it }, emptyMap(), emptyMap()))
        return CalculationBindings(dimensions, members, emptyMap(), emptyMap(), emptyMap())
    }

    private fun computedRecords(definitions: Definitions, id: String, source: MemberSource, stages: List<ValidatedOperation>, selected: List<String>, identity: List<String>): MemberRef =
        computedRecords(definitions, id, source, memberCalculation(source, null, emptyMap(), stages), selected, identity)

    private fun computedRecords(definitions: Definitions, id: String, source: MemberSource, calculation: Calculation, selected: List<String>, identity: List<String>): MemberRef {
        val outputs = selected.associateWith { name ->
            val original = source.definition.result.outputs[name]
            if (original is MemberSourceDefinition.DimensionOutput || original is MemberSourceDefinition.ComputedDimensionOutput)
                MemberSourceDefinition.ComputedDimensionOutput(name, requireNotNull(original).role, MemberSourceDefinition.scalar(original).valueType, name, name, MemberSourceDefinition.rootValueContract(original))
            else MemberSourceDefinition.ComputedPropertyOutput(name, original?.role ?: "amount", original?.let { MemberSourceDefinition.scalar(it).valueType } ?: ScalarValueType.NUMBER, name,
                if (original == null) exact else MemberSourceDefinition.rootValueContract(original))
        }
        definitions.members["$id@1"] = MemberSource.Computed(id, 1, MemberSourceDefinition.Computed(calculation, MemberResult(
            if (identity == listOf("region")) "region_balance" else source.definition.result.entity,
            if (identity == listOf("region")) "region_balance" else source.definition.result.role,
            if (identity.isEmpty()) MemberShape.RAW_BAG else MemberShape.RECORDS, identity, outputs, emptyMap(), if ("time" in selected) "time" else null, emptyMap(), "完成指定顺序后形成的记录")),
            temporalBindings(definitions, calculation, source), emptyMap())
        return MemberRef(id, 1)
    }

    private fun selectedMembers(source: MemberRef, outputs: List<String>, range: TemporalWindow?, stages: List<ValidatedOperation>) = Calculation.Scope(Calculation.Members("records", Calculation.MemberSource(source), outputs.map { Calculation.MemberProjection(it, it, null) }, (range)?.let { Calculation.Range(it) }, emptyMap(), MemberCondition.Unrestricted(), emptyMap(), emptyMap(), emptyMap()), emptyMap(), stages)
    private fun measureScope(calls: List<Calculation.Call>, grouping: List<ScopedInputContract.DimensionRef>) = Calculation.Scope(Calculation.Measures(calls, (grouping).map { Calculation.PublishedDimension(it) }, emptyList(), null, emptyMap(), Calculation.Condition(unrestricted), unrestricted, emptyList(), RowPopulationContract.calls(calls.map { it.callId }), null), emptyMap(), emptyList())
    private fun executeTemporal(definitions: Definitions, warehouse: NativeWarehouse, requestId: String, scope: Calculation.Scope): ExecutableExecutionResult {
        val catalog = definitions.catalog(); val provider = SourceProvider(warehouse.spec.source(), TestConnectors.registry)
        val engine = OlapEngine(OlapSemanticCompiler(CalciteRelCompiler(TestConnectors.registry)), SourceExecutableSqlExecutor({ if (it == provider.bindingRef) provider else null }, RuntimeDeploymentLimits.nativeMemoryBytes, null), RuntimeDeploymentLimits.result)
        return engine.execute(catalog, ExecutableQueryRequest(ExecutableQueryRequest.FORMAT_VERSION, requestId, catalog.manifest.catalogId, scope), object : ExecutableExecutionObserver {
            override fun bindingCompleted(input: ExecutableInputScope) {}
            override fun stageAnalysisCompleted(analyses: List<QueryStageAnalysis.Analysis>) {}
            override fun sourceResolutionCompleted(sources: List<CompiledQuery.SourceEvidence>) {}
            override fun loweringCompleted(result: ExecutableLoweringResult) {}
        }, RuntimeDeploymentLimits.CONTROL)
    }
    private fun dimension(name: String) = ScopedInputContract.DimensionRef(name, "1", if (name == "time") "instant" else "value")
    private fun temporalOrder() = listOf(PostOperationContract.OrderKey("time", "ASC", "LAST"), PostOperationContract.OrderKey("id", "ASC", "LAST"))
    private fun sumAt(keys: List<String>, input: String, output: String) = PostOperationContract.Regroup(PostOperationContract.ResultGrouping(keys,
        listOf(PostOperationContract.ResultAggregate(input, ResultAggregation.Sum(exact, ResultAggregation.NullPolicy.IGNORE), output, output)), null))
    private fun symbol(name: String) = ResultSymbol(name)
    private fun operation(operator: ResultOperator, vararg operands: ResultExpression) = ResultOperation(operator, operands.toList())
    private fun scalar(value: ExecutableBusinessResult.Value) = (value as ExecutableBusinessResult.ScalarValue).value
    private fun decimal(value: ExecutableBusinessResult.Value) = decimal(scalar(value))
    private fun decimal(value: String) = BigDecimal(value).stripTrailingZeros()
}
