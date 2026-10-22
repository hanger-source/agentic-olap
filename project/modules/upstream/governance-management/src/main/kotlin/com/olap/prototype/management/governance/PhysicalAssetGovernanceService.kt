package com.olap.prototype.management.governance

import com.olap.prototype.contract.JdbcColumnType
import com.olap.prototype.contract.NativeTableDependency
import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.expression.SemanticExpression.TableArgument
import com.olap.prototype.governance.contract.*
import com.olap.prototype.management.warehouse.*
import com.olap.prototype.storage.PlatformDatabase
import com.olap.prototype.warehouse.contract.JdbcTableConstraints
import com.olap.prototype.warehouse.contract.PhysicalSourceConstraints
import com.olap.prototype.warehouse.contract.SourceBindingRef
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64

/** Physical versions adopt exact discovery evidence; metadata edits retain only unchanged observed types. */
class PhysicalAssetGovernanceService(
    private val database: PlatformDatabase,
    private val warehouseDataSources: WarehouseDiscoveryReader
) : PhysicalAssetRegistrar, PhysicalAssetReferenceResolver {
    private val snapshots = GovernanceSnapshotReader(database)
    private val assets = GovernanceRepository(database)
    private val versions = GovernanceVersionRefRepository(database)

    override fun createPhysicalTable(request: PhysicalTableCreateRequest): PhysicalTableCreateResult =
        database.inTransaction { create(request, null) }

    fun revisePhysicalTable(request: GovernanceRevisionRequest<PhysicalTableCreateRequest>): PhysicalTableCreateResult =
        database.inTransaction { create(request.definition, request) }

    private fun create(request: PhysicalTableCreateRequest, revision: GovernanceRevisionRequest<PhysicalTableCreateRequest>?): PhysicalTableCreateResult {
        val change = prepareTable(request, revision)
        write(change)
        return PhysicalTableCreateResult(change.table, change.version, change.activeColumns, change.columnVersions)
    }

    fun updatePhysicalTableMetadata(request: PhysicalTableMetadataUpdateRequest): PhysicalTableMetadataUpdateResult = database.inTransaction {
        val id = text(request.physicalTableId, "physical_table.id")
        val expected = positive(request.expectedVersion, "physical_table.expected_version")
        val current = findPhysicalTable(id) ?: throw ProducerContractException("physical_table.id not found: $id")
        matching(current.currentVersion, expected, "physical_table.current_version", "physical_table.expected_version")
        val actor = text(request.updatedBy, "physical_table.updated_by")
        val draft = GovernanceDraftVersions(database).start(GovernanceDraftVersions.Asset.PHYSICAL_TABLE, id, GovernanceRevisionRequest(id, expected, request), actor)
        val now = Instant.now()
        val updated = current.copy(displayName = text(request.displayName, "physical_table.display_name"), description = request.description,
            owner = text(request.owner, "physical_table.owner"), status = "DRAFT", currentVersion = draft.version, updatedBy = actor, updatedAt = now)
        val columns = snapshots.physicalTableColumns(id, expected)
        val previous = snapshots.physicalTableContent(id, expected)
        val evidence = retainedEvidence(PhysicalAssetSnapshots.sourceConstraints(previous), columns, columns)
        val version = tableVersion(updated, PhysicalAssetSnapshots.revisedTableContent(updated, previous, columns, evidence), expected, actor, now)
        savePhysicalTable(updated)
        savePhysicalTableVersion(version)
        PhysicalTableMetadataUpdateResult(updated, version)
    }

    fun updatePhysicalTableColumns(request: PhysicalTableColumnsUpdateRequest): PhysicalTableColumnsUpdateResult = database.inTransaction {
        val change = prepareColumns(request)
        write(change)
        PhysicalTableColumnsUpdateResult(change.table, change.version, change.activeColumns, change.columnVersions)
    }

    private data class ColumnWrite(val column: PhysicalColumnRecord, val version: PhysicalColumnVersionRecord)
    private class TableChange(val table: PhysicalTableRecord, val version: PhysicalTableVersionRecord, columns: List<ColumnWrite>) {
        val columns: List<ColumnWrite> = java.util.List.copyOf(columns)
        val activeColumns: List<PhysicalColumnRecord> get() = columns.map { it.column }.filter { it.enabled }
        val columnVersions: List<PhysicalColumnVersionRecord> get() = columns.map { it.version }
    }

    private fun prepareColumns(request: PhysicalTableColumnsUpdateRequest): TableChange {
        val id = text(request.physicalTableId, "physical_table.id")
        val expected = positive(request.expectedVersion, "physical_table.expected_version")
        val table = findPhysicalTable(id) ?: throw ProducerContractException("physical_table.id not found: $id")
        matching(table.currentVersion, expected, "physical_table.current_version", "physical_table.expected_version")
        val actor = text(request.updatedBy, "physical_table.updated_by")
        val draft = GovernanceDraftVersions(database).start(GovernanceDraftVersions.Asset.PHYSICAL_TABLE, id, GovernanceRevisionRequest(id, expected, request), actor)
        if (request.columns.isEmpty()) throw ProducerContractException("physical_table.columns must not be empty")
        val prior = snapshots.physicalTableColumns(id, expected)
        val remaining = prior.associateByTo(linkedMapOf()) { it.columnName }
        val names = hashSetOf<String>()
        val now = Instant.now()
        val writes = request.columns.mapIndexedTo(mutableListOf()) { index, input ->
            val name = uniqueColumn(input.columnName, names)
            val previous = remaining.remove(name)
            val columnId = previous?.id ?: "$id:column:$name"
            val columnDraft = GovernanceDraftVersions(database).owned(GovernanceDraftVersions.Asset.PHYSICAL_COLUMN, columnId, actor)
            val column = PhysicalColumnRecord(columnId, id, name, text(input.displayName, "physical_column.display_name"),
                text(input.columnType, "physical_column.data_type"), input.description, input.nullable, index + 1, true, "DRAFT", columnDraft.version,
                previous?.createdBy ?: actor, previous?.createdAt ?: now, now, input.jdbcColumnType)
            ColumnWrite(column, columnVersion(column, columnDraft.sourceVersion, actor, now, null))
        }
        remaining.values.forEach { removed ->
            val removedDraft = GovernanceDraftVersions(database).owned(GovernanceDraftVersions.Asset.PHYSICAL_COLUMN, removed.id, actor)
            val archived = removed.copy(enabled = false, status = "ARCHIVED", currentVersion = removedDraft.version, updatedAt = now)
            writes += ColumnWrite(archived, columnVersion(archived, removedDraft.sourceVersion, actor, now, null))
        }
        val updated = table.copy(status = "DRAFT", currentVersion = draft.version, updatedBy = actor, updatedAt = now)
        val previous = snapshots.physicalTableContent(id, expected)
        val active = writes.map { it.column }.filter { it.enabled }
        val evidence = retainedEvidence(PhysicalAssetSnapshots.sourceConstraints(previous), prior, active)
        return TableChange(updated, tableVersion(updated, PhysicalAssetSnapshots.revisedTableContent(updated, previous, active, evidence), expected, actor, now), writes)
    }

    private fun prepareTable(request: PhysicalTableCreateRequest, revision: GovernanceRevisionRequest<PhysicalTableCreateRequest>?): TableChange {
        text(request.sourceConstraints.discoveredTableId, "physical_table.sourceConstraints.discoveredTableId")
        text(request.schemaHash, "physical_table.schemaHash from the same exact discovery table")
        val tableName = text(request.tableName, "physical_table.table_name")
        val schema = text(request.schemaName, "physical_table.schema_name")
        val sourceId = text(request.dataSource, "physical_table.data_source")
        val source = warehouseDataSources.findDataSource(sourceId) ?: throw ProducerContractException("physical_table.data_source not found: $sourceId")
        val dialect = text(request.dialectId, "physical_table.dialect_id")
        if (source.status != "ACTIVE") throw ProducerContractException("physical table requires an active source identity")
        val adopted = warehouseDataSources.findDiscoveredTable(request.sourceConstraints.discoveredTableId) ?: throw ProducerContractException("exact discovery table not found")
        val discovery = warehouseDataSources.findDiscoverySnapshot(adopted.discoverySnapshotId) ?: throw ProducerContractException("exact discovery snapshot not found")
        val binding = warehouseDataSources.findSourceBinding(SourceBindingRef(sourceId, discovery.sourceIdentityHash)) ?: throw ProducerContractException("exact registered source binding not found")
        if (binding.source.dialectId != dialect) throw ProducerContractException("physical table dialect must match its adopted source binding")
        val catalog = request.catalog?.trim().orEmpty()
        val id = if (revision == null) physicalTableId(sourceId, catalog, schema, tableName) else text(revision.objectId, "physical_table revision identity")
        val previous = revision?.let { physicalTableSnapshot(id, positive(it.sourceVersion, "physical_table sourceVersion")) }
        if (previous != null && previous.dataSource != sourceId) throw ProducerContractException("physical table source binding revision must retain its stable data source identity")
        val priorColumns = if (revision == null) emptyMap() else snapshots.physicalTableColumns(id, revision.sourceVersion).associateBy { it.columnName }
        val draft = GovernanceDraftVersions(database).start(GovernanceDraftVersions.Asset.PHYSICAL_TABLE, id, revision, request.createdBy)
        val actor = text(request.createdBy, "physical_table.created_by")
        if (request.columns.isEmpty()) throw ProducerContractException("physical_table.columns must not be empty")
        val now = Instant.now()
        val table = PhysicalTableRecord(id, text(request.code, "physical_table.code"), text(request.displayName, "physical_table.display_name"), sourceId, dialect,
            catalog, schema, tableName, request.description, text(request.owner, "physical_table.owner"), "NOT_LOADED", null, request.schemaHash, "DRAFT", draft.version,
            previous?.createdBy ?: actor, previous?.createdAt ?: now, actor, now)
        val names = hashSetOf<String>()
        val writes = request.columns.mapIndexed { index, input ->
            val name = uniqueColumn(input.columnName, names)
            val prior = priorColumns[name]
            val columnId = prior?.id ?: "$id:column:$name"
            val columnDraft = GovernanceDraftVersions(database).owned(GovernanceDraftVersions.Asset.PHYSICAL_COLUMN, columnId, actor)
            val column = PhysicalColumnRecord(columnId, id, name, text(input.displayName, "physical_column.display_name"), text(input.columnType, "physical_column.data_type"),
                input.description, input.nullable, index + 1, true, "DRAFT", columnDraft.version, prior?.createdBy ?: actor, prior?.createdAt ?: now, now, input.jdbcColumnType)
            ColumnWrite(column, columnVersion(column, columnDraft.sourceVersion, actor, now, input.sensitivityLevel))
        }
        val columns = writes.map { it.column }
        val evidence = adoptedEvidence(table, request.sourceConstraints, columns, adopted, discovery, binding)
        return TableChange(table, tableVersion(table, PhysicalAssetSnapshots.tableContent(table, request, columns, evidence), draft.sourceVersion, actor, now), writes)
    }

    private fun adoptedEvidence(table: PhysicalTableRecord, input: PhysicalTableCreateRequest.SourceConstraintsInput, columns: List<PhysicalColumnRecord>,
        adopted: WarehouseDiscoveredTableRecord, discovery: WarehouseDiscoverySnapshotRecord, binding: WarehouseSourceBindingRecord): PhysicalSourceConstraints {
        if (discovery.status != "SUCCEEDED" || table.dataSource != discovery.dataSource || table.dialectId != discovery.dialectId ||
            table.catalogName != adopted.catalogName || table.schemaName != adopted.schemaName || table.tableName != adopted.tableName || table.schemaHash != adopted.schemaHash)
            throw ProducerContractException("source constraints must match exact discovery source and table")
        val observed = warehouseDataSources.listDiscoveredColumns(adopted.id)
        if (observed.size != columns.size) throw ProducerContractException("source constraints require the complete discovered column set")
        val byName = columns.associateBy { it.columnName }
        observed.forEach { item ->
            val column = byName[item.columnName] ?: throw ProducerContractException("source constraint column missing from physical version")
            if (JdbcColumnType(item.jdbcType, item.typeName, item.precision, item.scale, item.nullable) != column.jdbcColumnType)
                throw ProducerContractException("source constraint column type differs from discovery")
        }
        val collections = input.sourceCollectionIds.map { id -> warehouseDataSources.findSourceCollection(id) ?: throw ProducerContractException("exact source collection is missing: $id") }
        collections.forEach { collection ->
            val sourceTable = collection.collection.table
            if (table.dataSource != collection.dataSourceId || discovery.sourceIdentityHash != collection.sourceIdentityHash ||
                table.catalogName?.takeUnless { it.isEmpty() } != sourceTable.catalog || table.schemaName != sourceTable.schema || table.tableName != sourceTable.table)
                throw ProducerContractException("source collection must identify the exact adopted physical source and table: ${collection.id}")
        }
        val schemaHash = text(adopted.schemaHash, "source constraints discovery.schema_hash")
        val provenance = discovery.structureProvenance ?: throw ProducerContractException("source constraints require exact discovery structure provenance")
        return PhysicalSourceConstraints(adopted.discoverySnapshotId, schemaHash, discovery.sourceIdentityHash, binding.source.connectorRef,
            discovery.databaseProductName, discovery.databaseProductVersion, discovery.driverName, discovery.driverVersion, adopted.constraints,
            columnRefs(columns), collections, provenance)
    }

    private fun retainedEvidence(previous: PhysicalSourceConstraints, priorColumns: List<PhysicalColumnRecord>, columns: List<PhysicalColumnRecord>): PhysicalSourceConstraints {
        if (priorColumns.associate { it.columnName to it.jdbcColumnType } != columns.associate { it.columnName to it.jdbcColumnType })
            throw ProducerContractException("changing observed source columns requires a new discovery and complete physical definition")
        return PhysicalSourceConstraints(previous.discoverySnapshotId, previous.schemaHash, previous.sourceIdentityHash, previous.connectorRef,
            previous.databaseProductName, previous.databaseProductVersion, previous.driverName, previous.driverVersion, previous.constraints,
            columnRefs(columns), previous.sourceCollections, previous.structureProvenance)
    }

    private fun write(change: TableChange) {
        savePhysicalTable(change.table)
        change.columns.forEach { savePhysicalColumn(it.column); savePhysicalColumnVersion(it.version) }
        savePhysicalTableVersion(change.version)
    }
    internal fun savePhysicalTable(record: PhysicalTableRecord) = assets.savePhysicalTable(record)
    internal fun savePhysicalTableVersion(record: PhysicalTableVersionRecord) {
        if (!assets.physicalTableIdentityExists(record.physicalTableId)) throw ProducerContractException("physical_table_version.physical_table_id not found: ${record.physicalTableId}")
        versions.savePhysicalTableVersion(record)
    }
    internal fun savePhysicalColumn(record: PhysicalColumnRecord) {
        if (!assets.physicalTableIdentityExists(record.physicalTableId)) throw ProducerContractException("physical_column.physical_table_id not found: ${record.physicalTableId}")
        assets.savePhysicalColumn(record)
    }
    internal fun savePhysicalColumnVersion(record: PhysicalColumnVersionRecord) {
        if (!assets.physicalColumnIdentityExists(record.physicalColumnId)) throw ProducerContractException("physical_column_version.physical_column_id not found: ${record.physicalColumnId}")
        versions.savePhysicalColumnVersion(record)
    }
    fun findPhysicalTable(id: String): PhysicalTableRecord? = assets.findPhysicalTable(id)
    fun physicalTableSnapshot(id: String, version: Long): PhysicalTableRecord = snapshots.physicalTable(id, version)
    fun physicalTableColumnSnapshots(id: String, version: Long): List<PhysicalColumnRecord> = snapshots.physicalTableColumns(id, version)
    fun listPhysicalTables(): List<PhysicalTableRecord> = assets.listPhysicalTables()
    fun findPhysicalColumn(id: String): PhysicalColumnRecord? = assets.findPhysicalColumn(id)
    fun physicalColumnSnapshot(id: String, version: Long): PhysicalColumnRecord = snapshots.physicalColumn(id, version)
    fun listPhysicalColumnsByTable(id: String): List<PhysicalColumnRecord> = assets.listPhysicalColumnsByTable(id)

    fun nativeTable(reference: TableArgument): NativeTableDependency {
        val table = physicalTableSnapshot(reference.physicalTableId, reference.physicalTableVersion)
        GovernanceContract.requireReferenceableVersion(table.status, "PHYSICAL_TABLE", table.id, reference.physicalTableVersion)
        return NativeTableDependency(table.id, reference.physicalTableVersion, table.dataSource, table.dialectId,
            JdbcTableConstraints.Table(table.catalogName, table.schemaName, table.tableName), physicalTableSourceConstraints(table.id, reference.physicalTableVersion))
    }
    fun physicalTableSourceConstraints(id: String, version: Long): PhysicalSourceConstraints {
        val content = snapshots.physicalTableContent(id, version)
        if (!content.has("sourceConstraints")) throw ProducerContractException("physical table version lacks source constraint declaration: $id@$version")
        if (content.get("sourceConstraints").isNull) throw ProducerContractException("physical table version has no frozen source evidence: $id@$version")
        val evidence = PhysicalAssetSnapshots.sourceConstraints(content)
        val table = snapshots.physicalTable(id, version)
        val binding = warehouseDataSources.findSourceBinding(SourceBindingRef(table.dataSource, evidence.sourceIdentityHash))
            ?: throw ProducerContractException("frozen physical version source binding is missing: $id@$version")
        if (binding.source.connectorRef != evidence.connectorRef || binding.source.dialectId != table.dialectId)
            throw ProducerContractException("frozen physical version differs from its exact source binding")
        if (columnRefs(snapshots.physicalTableColumns(id, version)) != evidence.columnVersionRefs)
            throw ProducerContractException("source constraints differ from exact physical column versions")
        return evidence
    }
    fun physicalTableUniqueKeys(id: String, version: Long): List<WarehouseDiscoveredUniqueKeyRecord> {
        val table = physicalTableSnapshot(id, version)
        val evidence = physicalTableSourceConstraints(id, version)
        val matches = warehouseDataSources.listDiscoveredTables(evidence.discoverySnapshotId).filter {
            it.catalogName == table.catalogName && it.schemaName == table.schemaName && it.tableName == table.tableName && it.schemaHash == evidence.schemaHash
        }
        if (matches.size != 1) throw ProducerContractException("exact physical version no longer identifies one adopted discovery table")
        return warehouseDataSources.listDiscoveredUniqueKeys(matches.single().id).also { proofs ->
            proofs.forEach { if (!evidence.columnVersionRefs.keys.containsAll(it.key.rule.columns)) throw ProducerContractException("source unique proof references columns outside the exact physical version") }
        }
    }
    override fun requirePhysicalTable(physicalTableId: String, field: String) {
        if (findPhysicalTable(physicalTableId) == null) throw ProducerContractException("$field physical_table not found: $physicalTableId")
    }
    override fun requirePhysicalTableVersion(physicalTableId: String, version: Long?, field: String) {
        requirePhysicalTable(physicalTableId, field)
        if (!versions.physicalTableVersionExists(physicalTableId, positive(version, field))) throw ProducerContractException("$field not found: $physicalTableId#$version")
    }
    override fun requirePhysicalColumn(physicalColumnId: String, field: String) {
        if (findPhysicalColumn(physicalColumnId) == null) throw ProducerContractException("$field physical_column not found: $physicalColumnId")
    }
    override fun requirePhysicalColumnVersion(physicalColumnId: String, version: Long?, field: String) {
        requirePhysicalColumn(physicalColumnId, field)
        if (!versions.physicalColumnVersionExists(physicalColumnId, positive(version, field))) throw ProducerContractException("$field not found: $physicalColumnId#$version")
    }

    private fun columnVersion(column: PhysicalColumnRecord, sourceVersion: Long?, actor: String, now: Instant, sensitivity: String?) =
        PhysicalColumnVersionRecord("${column.id}:v${column.currentVersion}", column.id, column.currentVersion, column.status,
            PhysicalAssetSnapshots.physicalColumnSnapshotJson(column, sensitivity), "{}", sourceVersion, actor, now, null, null, null, null, null, null)
    private fun tableVersion(table: PhysicalTableRecord, content: String, sourceVersion: Long?, actor: String, now: Instant) =
        PhysicalTableVersionRecord("${table.id}:v${table.currentVersion}", table.id, table.currentVersion, "DRAFT", content, "{}", sourceVersion, actor, now, null, null, null, null, null, null)
    private fun columnRefs(columns: List<PhysicalColumnRecord>): Map<String, String> = columns.associateTo(linkedMapOf()) { it.columnName to "${it.id}@${it.currentVersion}" }
    private fun uniqueColumn(value: String, names: MutableSet<String>): String = text(value, "physical_column.column_name").also {
        if (!names.add(it)) throw ProducerContractException("physical_table.columns contains duplicate column: $it")
    }
    private fun physicalTableId(source: String, catalog: String, schema: String, table: String): String = "physical-table/" +
        listOf(source, catalog, schema, table).joinToString("/") { Base64.getUrlEncoder().withoutPadding().encodeToString(it.toByteArray(StandardCharsets.UTF_8)) }
    private fun text(value: String?, field: String): String = value?.trim()?.takeUnless { it.isEmpty() } ?: throw ProducerContractException("$field is required")
    private fun positive(value: Long?, field: String): Long = value?.takeIf { it > 0 } ?: throw ProducerContractException("$field is required and must be a positive version")
    private fun matching(actual: Any?, expected: Any, actualField: String, expectedField: String) {
        if (actual == null || actual != expected) throw ProducerContractException("$actualField must match $expectedField")
    }
}
