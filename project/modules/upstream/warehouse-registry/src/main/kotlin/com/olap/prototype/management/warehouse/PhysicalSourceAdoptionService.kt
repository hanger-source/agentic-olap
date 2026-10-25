package com.olap.prototype.management.warehouse

import com.olap.prototype.codec.GovernanceProto
import com.olap.prototype.contract.JdbcColumnType
import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.warehouse.contract.*
import java.time.Instant

/** 显式接受已记录的来源观察兑现P；不执行调查，也不修订P或选择最新观察。 */
class PhysicalSourceAdoptionService(
    private val registry: SourceAdoptionRegistry,
    private val contracts: PhysicalDataContractReader,
) {
    fun register(request: SourceAdoptionRegistrationRequest): SourceAdoptions = registry.inTransaction {
        val snapshot = snapshot(request.discoverySnapshotId)
        val tables = registry.listDiscoveredTables(snapshot.id)
        val items = request.physicalTables.map { reference ->
            val contract = contracts.readPhysicalDataContract(reference)
            val matches = tables.filter { table(it) == contract.table }
            if (matches.size != 1) throw ProducerContractException("source adoption requires one exact observed table for ${reference.key()} in ${snapshot.id}")
            adopt(contract, matches.single(), snapshot, collections(contract, snapshot), request.adoptedBy)
        }
        SourceAdoptions(items)
    }

    /** 调用方的createPhysicalTable事务同时提交P、列及首份采用。 */
    fun registerInitial(contract: PhysicalDataContract, discoveredTableId: String, sourceCollectionIds: List<String>, actor: String): PhysicalSourceAdoption = registry.inTransaction {
        val observed = registry.findDiscoveredTable(discoveredTableId)
            ?: throw ProducerContractException("source adoption discovered table is missing: $discoveredTableId")
        val snapshot = snapshot(observed.discoverySnapshotId)
        val collections = sourceCollectionIds.map { id -> registry.findSourceCollection(id)
            ?: throw ProducerContractException("source adoption collection evidence is missing: $id") }
        adopt(contract, observed, snapshot, collections, actor)
    }

    fun read(request: SourceAdoptionReadRequest): SourceAdoptions {
        if (registry.findDiscoverySnapshot(request.discoverySnapshotId) == null)
            throw ProducerContractException("source adoption snapshot is missing: ${request.discoverySnapshotId}")
        return SourceAdoptions(registry.listSourceAdoptions(request.discoverySnapshotId))
    }

    private fun snapshot(id: String): WarehouseDiscoverySnapshotRecord {
        val observed = registry.findDiscoverySnapshot(id) ?: throw ProducerContractException("source adoption snapshot is missing: $id")
        if (observed.status != "SUCCEEDED") throw ProducerContractException("source adoption requires a complete successful structure snapshot: $id")
        val source = registry.findDataSource(observed.dataSource) ?: throw ProducerContractException("source adoption source is missing: ${observed.dataSource}")
        if (source.status != "ACTIVE") throw ProducerContractException("source adoption requires an active stable source: ${observed.dataSource}")
        val binding = registry.findSourceBinding(SourceBindingRef(observed.dataSource, observed.sourceIdentityHash))
            ?: throw ProducerContractException("source adoption binding is missing for snapshot: $id")
        if (binding.source.dialectId != observed.dialectId) throw ProducerContractException("source adoption snapshot differs from its registered binding dialect")
        return observed
    }

    private fun collections(contract: PhysicalDataContract, snapshot: WarehouseDiscoverySnapshotRecord): List<SourceCollectionRecord> {
        val available = registry.listSourceCollections(snapshot.dataSource).filter { it.sourceIdentityHash == snapshot.sourceIdentityHash }
        return contract.sourceCollections.map { expected ->
            val matches = available.filter { it.declaration() == expected }
            if (matches.size != 1) throw ProducerContractException("source adoption requires one exact collection verification for ${contract.reference.key()}: ${expected.key()}")
            matches.single()
        }
    }

    private fun adopt(contract: PhysicalDataContract, observed: WarehouseDiscoveredTableRecord, snapshot: WarehouseDiscoverySnapshotRecord,
        collections: List<SourceCollectionRecord>, actor: String): PhysicalSourceAdoption {
        if (contract.dataSourceId != snapshot.dataSource || contract.table != table(observed) || observed.discoverySnapshotId != snapshot.id)
            throw ProducerContractException("source adoption observation differs from the stable physical source: ${contract.reference.key()}")
        val bindingRef = SourceBindingRef(snapshot.dataSource, snapshot.sourceIdentityHash)
        val binding = registry.findSourceBinding(bindingRef) ?: throw ProducerContractException("source adoption binding is missing")
        val columns = registry.listDiscoveredColumns(observed.id).filter { it.columnName in contract.columns }.associateTo(sortedMapOf()) { column ->
            column.columnName to JdbcColumnType(column.jdbcType, column.typeName, column.precision, column.scale, column.nullable)
        }
        val proofs = registry.listDiscoveredUniqueKeys(observed.id).map { SnapshotUniqueKeyProof(it.id, it.key) }.sortedBy { it.id }
        val evidence = PhysicalSourceEvidence(snapshot.id, observed.id, observed.schemaHash
            ?: throw ProducerContractException("source adoption requires the observed schema hash"), snapshot.structureProvenance
            ?: throw ProducerContractException("source adoption requires the observed structure provenance"),
            snapshot.databaseProductName, snapshot.databaseProductVersion, snapshot.driverName, snapshot.driverVersion, columns,
            observed.constraints, proofs, collections.sortedBy { it.id })
        val reference = GovernanceProto.sourceAdoptionReference(contract.reference, bindingRef, binding.source.connectorRef, snapshot.dialectId, contract.table, evidence)
        val adoption = PhysicalSourceAdoption(reference, contract.reference, bindingRef, binding.source.connectorRef, snapshot.dialectId, contract.table, evidence, actor, Instant.now())
        try { adoption.requireContract(contract) }
        catch (failure: IllegalArgumentException) { throw ProducerContractException("source adoption does not fulfill ${contract.reference.key()}: ${failure.message}", failure) }
        return registry.insertSourceAdoption(adoption)
    }

    private fun table(record: WarehouseDiscoveredTableRecord): JdbcTableConstraints.Table =
        JdbcTableConstraints.Table(record.catalogName.takeUnless(String::isEmpty), record.schemaName, record.tableName)
}
