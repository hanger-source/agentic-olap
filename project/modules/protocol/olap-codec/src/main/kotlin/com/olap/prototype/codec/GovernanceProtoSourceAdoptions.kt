package com.olap.prototype.codec

import com.olap.prototype.connector.ConnectorRef
import com.olap.prototype.warehouse.contract.*
import com.olap.prototype.wire.governance.PhysicalTableRef as WTableRef
import com.olap.prototype.wire.governance.PhysicalDataContract as WContract
import com.olap.prototype.wire.governance.PhysicalDataContractColumn as WColumn
import com.olap.prototype.wire.governance.PhysicalDataContractColumnsEntry as WColumnEntry
import com.olap.prototype.wire.governance.SourceCollectionDeclaration as WDeclaration
import com.olap.prototype.wire.governance.SourceAdoptionRef as WAdoptionRef
import com.olap.prototype.wire.governance.SnapshotUniqueKeyProof as WKeyProof
import com.olap.prototype.wire.governance.PhysicalSourceEvidence as WEvidence
import com.olap.prototype.wire.governance.PhysicalSourceEvidenceColumnsEntry as WEvidenceColumn
import com.olap.prototype.wire.governance.PhysicalSourceAdoption as WAdoption
import com.olap.prototype.wire.governance.SourceAdoptions as WAdoptions
import com.olap.prototype.wire.governance.SourceAdoptionRegistrationRequest as WRegistration
import com.olap.prototype.wire.governance.SourceAdoptionReadRequest as WRead

internal fun PhysicalTableRefToProto(value: PhysicalTableRef): WTableRef =
    WTableRef.newBuilder().setId(value.id).setVersion(value.version).build()
internal fun PhysicalTableRefFromProto(value: WTableRef): PhysicalTableRef = PhysicalTableRef(value.id, value.version)

internal fun PhysicalDataContractColumnToProto(value: PhysicalDataContract.Column): WColumn =
    WColumn.newBuilder().setRef(value.ref).setType(JdbcColumnTypeToProto(value.type)).build()
internal fun PhysicalDataContractColumnFromProto(value: WColumn): PhysicalDataContract.Column =
    PhysicalDataContract.Column(value.ref, required(value.hasType(), "PhysicalDataContract.Column.type") { JdbcColumnTypeFromProto(value.type) })

internal fun SourceCollectionDeclarationToProto(value: SourceCollectionDeclaration): WDeclaration = WDeclaration.newBuilder()
    .setDeclarationSha256(value.declarationSha256).setSourceAuthority(value.sourceAuthority).setRuleDocumentSha256(value.ruleDocumentSha256)
    .setAnchor(JdbcTableConstraintsDataAnchorToProto(value.anchor)).setScope(SealedSourceDatasetScopeToProto(value.scope))
    .setCollection(SealedSourceDatasetCollectionToProto(value.collection)).build()
internal fun SourceCollectionDeclarationFromProto(value: WDeclaration): SourceCollectionDeclaration = SourceCollectionDeclaration(
    value.declarationSha256, value.sourceAuthority, value.ruleDocumentSha256,
    required(value.hasAnchor(), "SourceCollectionDeclaration.anchor") { JdbcTableConstraintsDataAnchorFromProto(value.anchor) },
    required(value.hasScope(), "SourceCollectionDeclaration.scope") { SealedSourceDatasetScopeFromProto(value.scope) },
    required(value.hasCollection(), "SourceCollectionDeclaration.collection") { SealedSourceDatasetCollectionFromProto(value.collection) })

internal fun PhysicalDataContractToProto(value: PhysicalDataContract): WContract = WContract.newBuilder()
    .setReference(PhysicalTableRefToProto(value.reference)).setDataSourceId(value.dataSourceId).setTable(JdbcTableConstraintsTableToProto(value.table))
    .addAllColumns(value.columns.entries.sortedBy { it.key }.map { WColumnEntry.newBuilder().setKey(it.key).setValue(PhysicalDataContractColumnToProto(it.value)).build() })
    .setConstraints(JdbcTableConstraintsToProto(value.constraints)).addAllSnapshotUniqueKeys(value.snapshotUniqueKeys.map(::SnapshotUniqueKeyToProto))
    .addAllSourceCollections(value.sourceCollections.map(::SourceCollectionDeclarationToProto)).setOriginDiscoveredTableId(value.originDiscoveredTableId).build()
internal fun PhysicalDataContractFromProto(value: WContract): PhysicalDataContract = PhysicalDataContract(
    required(value.hasReference(), "PhysicalDataContract.reference") { PhysicalTableRefFromProto(value.reference) }, value.dataSourceId,
    required(value.hasTable(), "PhysicalDataContract.table") { JdbcTableConstraintsTableFromProto(value.table) },
    strictMap(value.columnsList.map { it.key to required(it.hasValue(), "PhysicalDataContract.columns.value") { PhysicalDataContractColumnFromProto(it.value) } }),
    required(value.hasConstraints(), "PhysicalDataContract.constraints") { JdbcTableConstraintsFromProto(value.constraints) },
    value.snapshotUniqueKeysList.map(::SnapshotUniqueKeyFromProto), value.sourceCollectionsList.map(::SourceCollectionDeclarationFromProto), value.originDiscoveredTableId)

internal fun SourceAdoptionRefToProto(value: SourceAdoptionRef): WAdoptionRef = WAdoptionRef.newBuilder().setId(value.id).build()
internal fun SourceAdoptionRefFromProto(value: WAdoptionRef): SourceAdoptionRef = SourceAdoptionRef(value.id)
internal fun SnapshotUniqueKeyProofToProto(value: SnapshotUniqueKeyProof): WKeyProof =
    WKeyProof.newBuilder().setId(value.id).setKey(SnapshotUniqueKeyToProto(value.key)).build()
internal fun SnapshotUniqueKeyProofFromProto(value: WKeyProof): SnapshotUniqueKeyProof =
    SnapshotUniqueKeyProof(value.id, required(value.hasKey(), "SnapshotUniqueKeyProof.key") { SnapshotUniqueKeyFromProto(value.key) })

internal fun PhysicalSourceEvidenceToProto(value: PhysicalSourceEvidence): WEvidence = WEvidence.newBuilder().apply {
    discoverySnapshotId = value.discoverySnapshotId
    discoveredTableId = value.discoveredTableId
    schemaHash = value.schemaHash
    structureProvenance = StructureProvenanceToProto(value.structureProvenance)
    value.databaseProductName?.let(::setDatabaseProductName)
    value.databaseProductVersion?.let(::setDatabaseProductVersion)
    value.driverName?.let(::setDriverName)
    value.driverVersion?.let(::setDriverVersion)
    addAllColumns(value.columns.entries.sortedBy { it.key }.map { WEvidenceColumn.newBuilder().setKey(it.key).setValue(JdbcColumnTypeToProto(it.value)).build() })
    constraints = JdbcTableConstraintsToProto(value.constraints)
    addAllSnapshotUniqueKeys(value.snapshotUniqueKeys.map(::SnapshotUniqueKeyProofToProto))
    addAllSourceCollections(value.sourceCollections.map(::SourceCollectionRecordToProto))
}.build()
internal fun PhysicalSourceEvidenceFromProto(value: WEvidence): PhysicalSourceEvidence = PhysicalSourceEvidence(
    value.discoverySnapshotId, value.discoveredTableId, value.schemaHash,
    required(value.hasStructureProvenance(), "PhysicalSourceEvidence.structureProvenance") { StructureProvenanceFromProto(value.structureProvenance) },
    if (value.hasDatabaseProductName()) value.databaseProductName else null,
    if (value.hasDatabaseProductVersion()) value.databaseProductVersion else null,
    if (value.hasDriverName()) value.driverName else null,
    if (value.hasDriverVersion()) value.driverVersion else null,
    strictMap(value.columnsList.map { it.key to required(it.hasValue(), "PhysicalSourceEvidence.columns.value") { JdbcColumnTypeFromProto(it.value) } }),
    required(value.hasConstraints(), "PhysicalSourceEvidence.constraints") { JdbcTableConstraintsFromProto(value.constraints) },
    value.snapshotUniqueKeysList.map(::SnapshotUniqueKeyProofFromProto), value.sourceCollectionsList.map(::SourceCollectionRecordFromProto))

/** 内容映射与持久化使用同一组字段；引用和审计不参与内容地址。 */
internal fun PhysicalSourceAdoptionContentToProto(physicalTable: PhysicalTableRef, binding: SourceBindingRef, connectorRef: ConnectorRef,
    dialect: String, table: JdbcTableConstraints.Table, evidence: PhysicalSourceEvidence): WAdoption.Builder = WAdoption.newBuilder()
    .setPhysicalTable(PhysicalTableRefToProto(physicalTable)).setBinding(QuerySourceProto.toProto(binding)).setConnectorRef(SemanticProto.toProto(connectorRef))
    .setDialect(dialect).setTable(JdbcTableConstraintsTableToProto(table)).setEvidence(PhysicalSourceEvidenceToProto(evidence))

internal fun PhysicalSourceAdoptionToProto(value: PhysicalSourceAdoption): WAdoption = PhysicalSourceAdoptionContentToProto(
    value.physicalTable, value.binding, value.connectorRef, value.dialect, value.table, value.evidence)
    .setReference(SourceAdoptionRefToProto(value.reference)).setAdoptedBy(value.adoptedBy).setAdoptedAt(value.adoptedAt.toString()).build()
internal fun PhysicalSourceAdoptionFromProto(value: WAdoption): PhysicalSourceAdoption = PhysicalSourceAdoption(
    required(value.hasReference(), "PhysicalSourceAdoption.reference") { SourceAdoptionRefFromProto(value.reference) },
    required(value.hasPhysicalTable(), "PhysicalSourceAdoption.physicalTable") { PhysicalTableRefFromProto(value.physicalTable) },
    required(value.hasBinding(), "PhysicalSourceAdoption.binding") { QuerySourceProto.fromProto(value.binding) },
    required(value.hasConnectorRef(), "PhysicalSourceAdoption.connectorRef") { SemanticProto.fromProto(value.connectorRef) as ConnectorRef },
    value.dialect, required(value.hasTable(), "PhysicalSourceAdoption.table") { JdbcTableConstraintsTableFromProto(value.table) },
    required(value.hasEvidence(), "PhysicalSourceAdoption.evidence") { PhysicalSourceEvidenceFromProto(value.evidence) },
    value.adoptedBy, java.time.Instant.parse(value.adoptedAt))

internal fun SourceAdoptionsToProto(value: SourceAdoptions): WAdoptions = WAdoptions.newBuilder().addAllItems(value.items.map(::PhysicalSourceAdoptionToProto)).build()
internal fun SourceAdoptionsFromProto(value: WAdoptions): SourceAdoptions = SourceAdoptions(value.itemsList.map(::PhysicalSourceAdoptionFromProto))
internal fun SourceAdoptionRegistrationRequestToProto(value: SourceAdoptionRegistrationRequest): WRegistration = WRegistration.newBuilder()
    .addAllPhysicalTables(value.physicalTables.map(::PhysicalTableRefToProto)).setDiscoverySnapshotId(value.discoverySnapshotId).setAdoptedBy(value.adoptedBy).build()
internal fun SourceAdoptionRegistrationRequestFromProto(value: WRegistration): SourceAdoptionRegistrationRequest =
    SourceAdoptionRegistrationRequest(value.physicalTablesList.map(::PhysicalTableRefFromProto), value.discoverySnapshotId, value.adoptedBy)
internal fun SourceAdoptionReadRequestToProto(value: SourceAdoptionReadRequest): WRead = WRead.newBuilder().setDiscoverySnapshotId(value.discoverySnapshotId).build()
internal fun SourceAdoptionReadRequestFromProto(value: WRead): SourceAdoptionReadRequest = SourceAdoptionReadRequest(value.discoverySnapshotId)
