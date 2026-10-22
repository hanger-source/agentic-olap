package com.olap.prototype.management.governance

import com.olap.prototype.connector.SourceProvider
import com.olap.prototype.contract.JdbcColumnType
import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.event.EventIdentity
import com.olap.prototype.expression.SemanticExpression
import com.olap.prototype.governance.application.GovernanceApplication
import com.olap.prototype.governance.application.GovernanceOperations
import com.olap.prototype.governance.contract.*
import com.olap.prototype.integration.RuntimeDeploymentLimits
import com.olap.prototype.integration.TestConnectors
import com.olap.prototype.management.warehouse.*
import com.olap.prototype.storage.PlatformDatabase
import com.olap.prototype.util.OlapJsonUtils
import com.olap.prototype.warehouse.contract.JdbcConnectionSpec
import com.olap.prototype.warehouse.contract.SourceConfiguration
import com.olap.prototype.warehouse.inspection.JdbcWarehouseInspector
import com.olap.prototype.warehouse.jdbc.JdbcSourceInspection
import com.olap.prototype.warehouse.onboarding.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.SQLException

class GovernanceHistoryAndRollbackTest {
    private val connectors = TestConnectors.registry

    @Test
    fun logicalTableAndLogicUnitPagesReadRequestedSnapshots() {
        PlatformDatabase.inMemory().use { database ->
            val stored = StoredGovernanceDefinitions(database)
            stored.physicalTable(1, "订单源表 v1", "orders_v1", "pc_amount", 1, "DEPRECATED")
            stored.physicalTable(2, "订单源表 v2", "orders_v2", "pc_amount", 2)
            stored.physicalColumn("pc_amount", "amount_v1", version = 1, status = "DEPRECATED", displayName = "金额列 v1", dataType = "decimal")
            stored.physicalColumn("pc_amount", "amount_v2", version = 2, displayName = "金额列 v2", dataType = "decimal")
            stored.logicalTable("order_fact", "FACT", 1, "DEPRECATED", "订单事实 v1")
            stored.logicalTable("order_fact", "FACT", 2, "ACTIVE", "订单事实 v2")
            stored.logicUnit("order_fact", "order_fact.amount", "amount", "NUMBER", aggregatable = true, version = 1, status = "DEPRECATED", displayName = "金额 v1", expression = SemanticExpression.Reference(SemanticExpression.ReferenceKind.PHYSICAL_COLUMN, "pc_amount", 1))
            stored.logicUnit("order_fact", "order_fact.amount", "amount", "NUMBER", aggregatable = true, version = 2, displayName = "金额 v2", expression = SemanticExpression.Reference(SemanticExpression.ReferenceKind.PHYSICAL_COLUMN, "pc_amount", 2))
            val refSql = "INSERT INTO logical_table_logic_unit_ref (id,logical_table_id,logical_table_version,logic_unit_id,logic_unit_version,created_at) VALUES (?,?,?,?,?,?)"
            stored.execute(refSql, "order_fact:v1:logic-unit:amount:v1", "order_fact", 1, "order_fact.amount", 1, stored.now)
            stored.execute(refSql, "order_fact:v2:logic-unit:amount:v2", "order_fact", 2, "order_fact.amount", 2, stored.now)
            assertThrows(SQLException::class.java) { stored.execute(refSql, "order_fact:v2:logic-unit:amount:v1", "order_fact", 2, "order_fact.amount", 1, stored.now) }
            stored.execute("INSERT INTO physical_logical_table_ref (id,physical_table_id,physical_table_version,logical_table_id,logical_table_version,ref_role,created_at) VALUES (?,?,?,?,?,?,?)",
                "order_fact:v1:source", "pt_orders", 1, "order_fact", 1, "SOURCE", stored.now)
            val sourceSql = "INSERT INTO logic_unit_source_ref (id,logic_unit_id,logic_unit_version,physical_column_id,physical_column_version,ref_role,created_at) VALUES (?,?,?,?,?,?,?)"
            stored.execute(sourceSql, "order_fact.amount:v1:source", "order_fact.amount", 1, "pc_amount", 1, "DIRECT_SOURCE", stored.now)
            assertThrows(SQLException::class.java) { stored.execute(sourceSql, "legacy-source-role", "order_fact.amount", 1, "pc_amount", 1, "SOURCE_COLUMN", stored.now) }
            val snapshots = GovernanceSnapshotReader(database)
            assertEquals("订单事实 v1", snapshots.logicalTable("order_fact", 1).displayName)
            assertEquals("金额 v1", snapshots.logicUnit("order_fact.amount", 1).displayName)
            assertEquals(SemanticExpression.Reference(SemanticExpression.ReferenceKind.PHYSICAL_COLUMN, "pc_amount", 1), snapshots.logicUnit("order_fact.amount", 1).expression)
        }
    }

    @Test
    fun failedReleaseBuildLeavesNoPartialReleaseArtifact() {
        PlatformDatabase.inMemory().use { database ->
            val stored = StoredGovernanceDefinitions(database)
            stored.businessAndSubject()
            val registry = WarehouseRegistryRepository(database)
            val assets = PhysicalAssetGovernanceService(database, registry)
            val onboarding = WarehouseOnboardingService(assets, WarehouseRegistryService(database, registry, assets)) { null }
            val operations = GovernanceOperations(GovernanceApplication(database, connectors), emptyMap(), onboarding)
            assertThrows(ProducerContractException::class.java) {
                operations.dispatchAuthenticated("build-release", SubjectAreaReleaseBuildRequest("rel_trade", "1", "交易发布", "trade", 1, "tester"),
                    RuntimeDeploymentLimits.CONTROL, "tester", JdbcSourceInspection(JdbcWarehouseInspector(connectors), connectors)) { }
            }
            assertEquals(0L, stored.count("subject_area_release", "release_id", "rel_trade"))
            assertEquals(0L, stored.count("release_object_ref", "release_id", "rel_trade"))
        }
    }

    @Test
    fun logicalTableAggregateRollsBackEveryRowWhenLaterWriteFails() {
        PlatformDatabase.inMemory().use { database ->
            val stored = StoredGovernanceDefinitions(database)
            stored.physicalTable(1, "订单表", "orders", "pc_order_id", 1)
            stored.physicalColumn("pc_order_id", "order_id", 1)
            stored.physicalColumn("pc_amount", "amount", 2)
            stored.execute("CREATE UNIQUE INDEX test_one_logic_unit_per_table ON logic_unit(logical_table_id)")
            val assets = PhysicalAssetGovernanceService(database, WarehouseRegistryRepository(database))
            val logical = LogicalModelGovernanceService(database, assets, connectors)
            val request = LogicalTableCreateRequest("pt_orders", 1, "order_fact", "订单事实", LogicalTableRole.FACT, "订单事实", "data", "tester", listOf(
                column("order_id", "pc_order_id", true), column("amount", "pc_amount", false)))
            assertThrows(RuntimeException::class.java) { logical.createLogicalTable(request) }
            listOf("logical_table" to "id", "logical_table_version" to "logical_table_id", "physical_logical_table_ref" to "logical_table_id",
                "logic_unit" to "logical_table_id", "logical_table_logic_unit_ref" to "logical_table_id").forEach { (table, field) -> assertEquals(0L, stored.count(table, field, "order_fact")) }
            listOf("logic_unit_version", "logic_unit_source_ref").forEach { assertEquals(0L, stored.count(it, "logic_unit_id", "order_fact.%", like = true)) }
        }
    }

    @Test
    fun physicalTableAggregateRollsBackTableVersionsAndColumnsOnLateFailure(@TempDir directory: Path) {
        PlatformDatabase.inMemory().use { database ->
            val source = JdbcConnectionSpec("duckdb", TestConnectors.ref("duckdb"), "duckdb", "jdbc:duckdb:${directory.resolve("orders.duckdb")}", null, null, "org.duckdb.DuckDBDriver").source()
            TestConnectors.open("duckdb", source.identityParameters.getValue("jdbcUrl") as String).use { connection -> connection.createStatement().use { sql ->
                sql.execute("CREATE SCHEMA source_test"); sql.execute("CREATE TABLE source_test.orders (order_id VARCHAR NOT NULL)")
            } }
            SourceRegistrationService(database, connectors).initializeAuthorizedSources(listOf(source))
            val registry = WarehouseRegistryRepository(database)
            val assets = PhysicalAssetGovernanceService(database, registry)
            val onboarding = WarehouseOnboardingService(assets, WarehouseRegistryService(database, registry, assets)) { reference -> source.takeIf { it.bindingRef() == reference } }
            val discovery = onboarding.discover(WarehouseDiscoveryCommand("duckdb", source.sourceIdentityHash(), null, "source_test", "orders", emptyList(), 10, emptyMap(), emptyMap(), "tester"),
                RuntimeDeploymentLimits.CONTROL, JdbcSourceInspection(JdbcWarehouseInspector(connectors), connectors))
            assertEquals("SUCCEEDED", discovery.snapshot.status, discovery.snapshot.errorMessage)
            val table = discovery.tables.first()
            val column = registry.listDiscoveredColumns(table.id).first()
            val input = PhysicalTableCreateRequest.ColumnInput(column.columnName, "订单 ID", column.typeName, "内部", requireNotNull(column.nullable), "订单主键",
                JdbcColumnType(column.jdbcType, column.typeName, column.precision, column.scale, column.nullable))
            fun definition(columns: List<PhysicalTableCreateRequest.ColumnInput>) = PhysicalTableCreateRequest("orders", "orders", "交易订单", "交易订单事实", "交易", "data-platform",
                "内部", "warehouse", "duckdb", "duckdb", table.catalogName, table.schemaName, requireNotNull(table.schemaHash),
                PhysicalTableCreateRequest.SourceConstraintsInput(table.id, emptyList()), "tester", columns)
            assertThrows(RuntimeException::class.java) { assets.createPhysicalTable(definition(listOf(input, input))) }
            val stored = StoredGovernanceDefinitions(database)
            listOf("physical_table", "physical_table_version", "physical_column", "physical_column_version").forEach { assertEquals(0L, stored.count(it)) }
            assertEquals(1L, assets.createPhysicalTable(definition(listOf(input))).physicalTable.currentVersion)
        }
    }

    @Test
    fun eventAggregateRollsBackIdentityVersionAndRefsOnLateFailure() {
        PlatformDatabase.inMemory().use { database ->
            val stored = StoredGovernanceDefinitions(database)
            stored.logicalTable("order_fact", "FACT")
            stored.logicUnit("order_fact", "order_fact.pay_time", "pay_time", "DATE", time = true)
            stored.logicUnit("order_fact", "order_fact.order_id", "order_id", "ID", primaryKey = true)
            stored.logicUnit("order_fact", "order_fact.destination", "destination", "STRING")
            stored.logicUnit("order_fact", "order_fact.pay_amount", "pay_amount", "NUMBER", aggregatable = true)
            val assets = PhysicalAssetGovernanceService(database, WarehouseRegistryRepository(database))
            val logical = LogicalModelGovernanceService(database, assets, connectors)
            val topologies = TopologyGovernanceService(database, logical)
            val events = EventGovernanceService(database, topologies, NativeExpressionBindings(assets, connectors))
            topologies.createTopology(topology())
            assertThrows(RuntimeException::class.java) {
                events.createEvent(EventCreateRequest("trade.payment.payment", 1, "paid", "支付成功", "data", "order_fact.pay_time", 1,
                    EventIdentity(listOf(EventIdentity.LogicUnitRef("order_fact.order_id", 1)), emptyMap()), emptyMap(),
                    listOf(EventCreateRequest.LogicUnitRefInput("order_fact.destination", 1)), listOf(EventCreateRequest.LogicUnitRefInput("order_fact.missing_amount", 1)), "支付成功事件", "tester"))
            }
            assertEquals(0L, stored.count("event", "id", "trade.payment.payment.paid"))
            assertEquals(0L, stored.count("event_version", "event_id", "trade.payment.payment.paid"))
            assertEquals(0L, stored.count("event_logic_unit_ref", "event_id", "trade.payment.payment.paid"))
        }
    }

    @Test
    fun joinPathAggregateRollsBackIdentityVersionTopologyRefAndKeysOnLateFailure() {
        PlatformDatabase.inMemory().use { database ->
            val stored = StoredGovernanceDefinitions(database)
            stored.logicalTable("order_fact", "FACT")
            stored.logicalTable("customer_dim", "DIM")
            stored.logicUnit("order_fact", "order_fact.customer_id", "customer_id", "ID")
            stored.logicUnit("customer_dim", "customer_dim.customer_id", "customer_id", "ID", primaryKey = true)
            val assets = PhysicalAssetGovernanceService(database, WarehouseRegistryRepository(database))
            val topologies = TopologyGovernanceService(database, LogicalModelGovernanceService(database, assets, connectors))
            topologies.createTopology(topology())
            topologies.createTopologyLogicalTableRef(TopologyLogicalTableRefCreateRequest("trade.payment.payment", 1, "customer_dim", 1, TopologyNodeRole.DIM_NODE))
            assertThrows(RuntimeException::class.java) {
                topologies.createJoinPath(JoinPathCreateRequest("trade.payment.payment", 1, "order_customer", "order_fact", 1, "customer_dim", 1, "LEFT", "MANY_TO_ONE", null,
                    "SQL_EQUALS_NULL_NEVER_MATCHES", "tester", listOf(JoinPathCreateRequest.KeyRefInput("order_fact.customer_id", 1, "customer_dim.missing_customer_id", 1))))
            }
            val id = "trade.payment.payment:join:order_customer"
            listOf("join_path" to "id", "join_path_version" to "join_path_id", "topology_join_path_ref" to "join_path_id", "join_path_key_ref" to "join_path_id").forEach {
                assertEquals(0L, stored.count(it.first, it.second, id))
            }
        }
    }

    private fun column(code: String, physicalId: String, primaryKey: Boolean) = LogicalTableCreateRequest.LogicUnitInput(code, code, LogicUnitSourceKind.COLUMN, null,
        if (primaryKey) LogicType.ID else LogicType.NUMBER, null, false, primaryKey, false, true, !primaryKey,
        listOf(LogicalTableCreateRequest.SourceRefInput(physicalId, 1, LogicUnitSourceRefRole.DIRECT_SOURCE)))
    private fun topology() = TopologyCreateRequest("payment", "支付拓扑", "trade.payment", "交易支付", "order_fact", 1, "data", "支付事实拓扑", "tester")
}
