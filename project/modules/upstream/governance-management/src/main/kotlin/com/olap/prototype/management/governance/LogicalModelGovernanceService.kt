package com.olap.prototype.management.governance

import com.olap.prototype.connector.ConnectorRegistry
import com.olap.prototype.contract.NativeTableDependency
import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.contract.ProducerContracts.requireText
import com.olap.prototype.contract.ProducerContracts.requireVersion
import com.olap.prototype.expression.*
import com.olap.prototype.governance.contract.*
import com.olap.prototype.query.ScalarValueType
import com.olap.prototype.storage.PlatformDatabase
import org.apache.calcite.rex.RexNode
import org.apache.calcite.sql.type.SqlTypeUtil
import java.time.Instant
import java.util.Collections

/** Owns the complete logical aggregate, exact physical adoption and derived expression closure. */
class LogicalModelGovernanceService(
    private val database: PlatformDatabase,
    private val physicalAssets: PhysicalAssetGovernanceService,
    private val connectors: ConnectorRegistry
) {
    private val assets = GovernanceRepository(database)
    private val versions = GovernanceVersionRefRepository(database)
    private val snapshots = GovernanceSnapshotReader(database)

    fun createLogicalTable(request: LogicalTableCreateRequest): LogicalTableCreateResult = database.inTransaction { create(request, null) }
    fun reviseLogicalTable(request: GovernanceRevisionRequest<LogicalTableCreateRequest>): LogicalTableCreateResult = database.inTransaction { create(request.definition, request) }

    private fun create(request: LogicalTableCreateRequest, revision: GovernanceRevisionRequest<LogicalTableCreateRequest>?): LogicalTableCreateResult {
        val physicalId = requireText(request.physicalTableId, "logical_table_create.physical_table_id").trim()
        val physicalVersion = requireVersion(request.physicalTableVersion, "logical_table_create.physical_table_version")
        physicalAssets.requirePhysicalTableVersion(physicalId, physicalVersion, "logical_table_create.physical_table_version")
        if (physicalAssets.findPhysicalTable(physicalId) == null) throw ProducerContractException("logical_table_create.physical_table_id not found: $physicalId")
        val code = requireText(request.code, "logical_table.code").trim()
        val id = revision?.objectId ?: code
        val draft = GovernanceDraftVersions(database).start(GovernanceDraftVersions.Asset.LOGICAL_TABLE, id, revision, request.createdBy)
        val actor = requireText(request.createdBy, "logical_table.created_by").trim()
        if (request.logicUnits.isEmpty()) throw ProducerContractException("logical_table_create.logic_units must not be empty")
        val codes = hashSetOf<String>()
        request.logicUnits.forEach {
            val normalized = requireText(it.code, "logical_table_create.logic_units.code").trim()
            if (!codes.add(normalized)) throw ProducerContractException("logical_table_create.logic_units.code contains duplicate value: $normalized")
        }
        val now = Instant.now()
        val table = LogicalTableRecord(id, code, requireText(request.displayName, "logical_table.display_name").trim(),
            GovernanceContract.requireEnumValue(request.tableRole, "logical_table.table_role"), request.description, requireText(request.owner, "logical_table.owner").trim(),
            "DRAFT", draft.version, actor, now, actor, now)
        val version = LogicalTableVersionRecord("$id:v${draft.version}", id, draft.version, "DRAFT",
            encodeDefinition(LogicalModelSnapshots.logicalTableVersionContent(table, physicalId, physicalVersion, request.logicUnits.size)), "{}", draft.sourceVersion, actor, now, null, null, null, null, null, null)
        val physical = PhysicalLogicalTableRefRecord("$id:v${draft.version}:physical-table:$physicalId:v$physicalVersion:SOURCE", physicalId, physicalVersion, id, draft.version, "SOURCE", now)
        // Children need the stable parent identity, while all version contents remain in this transaction.
        if (revision == null) saveLogicalTable(table)
        val prepared = prepareUnits(request.logicUnits, id, draft.version, physical, actor, now)
        if (revision != null) saveLogicalTable(table)
        saveLogicalTableVersion(version)
        savePhysicalLogicalTableRef(physical)
        return persistUnits(table, version, physical, prepared, actor, now)
    }

    private data class Candidate(val unit: LogicUnitRecord, val draft: GovernanceDraftVersions.Draft, val sources: List<LogicalTableCreateRequest.SourceRefInput>)
    private data class Prepared(val draft: GovernanceDraftVersions.Draft, val expansion: LogicUnitExpansion)
    private fun prepareUnits(inputs: List<LogicalTableCreateRequest.LogicUnitInput>, tableId: String, version: Long,
        physical: PhysicalLogicalTableRefRecord, actor: String, now: Instant): List<Prepared> {
        val candidates = linkedMapOf<String, Candidate>()
        inputs.forEach { input ->
            val code = requireText(input.code, "logic_unit.code").trim()
            val id = "$tableId.$code"
            val draft = GovernanceDraftVersions(database).owned(GovernanceDraftVersions.Asset.LOGIC_UNIT, id, actor)
            val unit = LogicUnitRecord(id, tableId, code, requireText(input.displayName, "logic_unit.display_name").trim(),
                GovernanceContract.requireEnumValue(input.sourceKind, "logic_unit.source_kind"), input.expression,
                input.logicType?.let { GovernanceContract.requireEnumValue(it, "logic_unit.logic_type") }, input.valueShape,
                flag(input.isTime, "logic_unit.is_time"), flag(input.isPrimaryKey, "logic_unit.is_primary_key"), flag(input.isForeignKey, "logic_unit.is_foreign_key"),
                flag(input.filterable, "logic_unit.filterable"), flag(input.aggregatable, "logic_unit.aggregatable"), "DRAFT", draft.version, actor, now, actor, now)
            val sources = if (input.sourceKind == LogicUnitSourceKind.COLUMN) {
                val refs = input.sourceRefs
                if (refs.isNullOrEmpty()) throw ProducerContractException("COLUMN sourceRefs must not be empty")
                if (refs.size != 1 || refs.single().refRole != LogicUnitSourceRefRole.DIRECT_SOURCE) throw ProducerContractException("COLUMN requires exactly one DIRECT_SOURCE")
                refs
            } else {
                if (!input.sourceRefs.isNullOrEmpty()) throw ProducerContractException("EXPRESSION sourceRefs are derived from its exact expression graph and must not be supplied")
                emptyList()
            }
            candidates["$id@${draft.version}"] = Candidate(unit, draft, java.util.List.copyOf(sources))
        }
        val units = candidates.mapValues { it.value.unit }
        val sources = candidates.filterValues { it.unit.sourceKind == "COLUMN" }.mapValues { it.value.sources }
        return candidates.values.map { candidate ->
            val expansion = expand(candidate.unit, version, physical, units, sources)
            expansion.units.filterKeys { it !in units }.values.forEach {
                GovernanceContract.requireReferenceableVersion(it.status, "LOGIC_UNIT", it.id, it.currentVersion)
            }
            Prepared(candidate.draft, expansion)
        }
    }

    private fun persistUnits(table: LogicalTableRecord, version: LogicalTableVersionRecord, physical: PhysicalLogicalTableRefRecord,
        prepared: List<Prepared>, actor: String, now: Instant): LogicalTableCreateResult {
        val units = mutableListOf<LogicUnitRecord>()
        val unitVersions = mutableListOf<LogicUnitVersionRecord>()
        val unitRefs = mutableListOf<LogicalTableLogicUnitRefRecord>()
        val sourceRefs = mutableListOf<LogicUnitSourceRefRecord>()
        prepared.forEach { candidate ->
            val unit = candidate.expansion.root
            val draft = candidate.draft
            val sources = candidate.expansion.sourceRefs(unit.sourceKind)
            val unitVersion = LogicUnitVersionRecord("${unit.id}:v${draft.version}", unit.id, draft.version, "DRAFT", encodeDefinition(LogicalModelSnapshots.logicUnitVersionContent(unit, sources)),
                "{}", draft.sourceVersion, actor, now, null, null, null, null, null, null)
            saveLogicUnit(unit); saveLogicUnitVersion(unitVersion)
            val ref = LogicalTableLogicUnitRefRecord("${table.id}:v${table.currentVersion}:logic-unit:${unit.id}:v${draft.version}", table.id, table.currentVersion, unit.id, draft.version, now)
            saveLogicalTableLogicUnitRef(ref)
            units += unit; unitVersions += unitVersion; unitRefs += ref
            sources.forEach { input ->
                val column = requireText(input.physicalColumnId, "logic_unit_source_ref.physical_column_id").trim()
                val columnVersion = requireVersion(input.physicalColumnVersion, "logic_unit_source_ref.physical_column_version")
                val source = LogicUnitSourceRefRecord("${unit.id}:v${draft.version}:physical-column:$column:v$columnVersion", unit.id, draft.version, column, columnVersion,
                    GovernanceContract.requireEnumValue(input.refRole, "logic_unit_source_ref.ref_role"), now)
                saveLogicUnitSourceRef(source); sourceRefs += source
            }
        }
        return LogicalTableCreateResult(table, version, physical, units, unitVersions, unitRefs, sourceRefs)
    }

    /** A single expansion session retains authored expressions and derives exact physical dependencies. */
    class LogicUnitExpansion(val root: LogicUnitRecord, val logicalTableVersion: Long, val physical: PhysicalLogicalTableRefRecord,
        units: Map<String, LogicUnitRecord>, expressions: Map<String, SemanticExpression>, columns: Map<String, PhysicalColumnRecord>, nativeTables: Map<String, NativeTableDependency>) {
        val units: Map<String, LogicUnitRecord> = Collections.unmodifiableMap(LinkedHashMap(units))
        val expressions: Map<String, SemanticExpression> = Collections.unmodifiableMap(LinkedHashMap(expressions))
        val columns: Map<String, PhysicalColumnRecord> = Collections.unmodifiableMap(LinkedHashMap(columns))
        val nativeTables: Map<String, NativeTableDependency> = java.util.Map.copyOf(nativeTables)
        fun sourceRefs(sourceKind: String): List<LogicalTableCreateRequest.SourceRefInput> {
            val role = if (sourceKind == "COLUMN") LogicUnitSourceRefRole.DIRECT_SOURCE else LogicUnitSourceRefRole.EXPRESSION_DEPENDENCY
            return columns.map { (key, value) -> LogicalTableCreateRequest.SourceRefInput(value.id, key.substringAfterLast('@').toLong(), role) }
        }
        override fun equals(other: Any?): Boolean = other is LogicUnitExpansion && root == other.root && logicalTableVersion == other.logicalTableVersion && physical == other.physical && units == other.units && expressions == other.expressions && columns == other.columns && nativeTables == other.nativeTables
        override fun hashCode(): Int = listOf(root, logicalTableVersion, physical, units, expressions, columns, nativeTables).hashCode()
    }
    fun expandLogicUnit(id: String, version: Long): LogicUnitExpansion {
        val unit = logicUnitSnapshot(id, version)
        val owner = logicUnitOwnerRef(id, version)
        return expand(unit, owner.logicalTableVersion, physicalOwner(owner.logicalTableId, owner.logicalTableVersion), emptyMap(), emptyMap())
    }
    private fun physicalOwner(id: String, version: Long): PhysicalLogicalTableRefRecord {
        val refs = listPhysicalLogicalTableRefsByLogicalTable(id).filter { it.logicalTableVersion == version }
        if (refs.size != 1) throw ProducerContractException("logical source requires one exact physical adoption: $id@$version")
        return refs.single()
    }
    private fun expand(root: LogicUnitRecord, logicalVersion: Long, physical: PhysicalLogicalTableRefRecord,
        candidates: Map<String, LogicUnitRecord>, candidateSources: Map<String, List<LogicalTableCreateRequest.SourceRefInput>>): LogicUnitExpansion {
        val units = linkedMapOf<String, LogicUnitRecord>()
        val expressions = linkedMapOf<String, SemanticExpression>()
        val columns = linkedMapOf<String, PhysicalColumnRecord>()
        val nativeTables = linkedMapOf<String, NativeTableDependency>()
        val owner = physicalAssets.nativeTable(SemanticExpression.TableArgument(physical.physicalTableId, physical.physicalTableVersion))
        val visiting = linkedSetOf<String>()
        val allowed = physicalAssets.physicalTableColumnSnapshots(physical.physicalTableId, physical.physicalTableVersion).associateBy { "${it.id}@${it.currentVersion}" }
        fun visit(unit: LogicUnitRecord) {
            val key = "${unit.id}@${unit.currentVersion}"
            if (key in units) return
            if (!visiting.add(key)) throw ProducerContractException("cyclic exact LogicUnit definitions: $visiting -> $key")
            if (visiting.size > 64) throw ProducerContractException("LogicUnit expression graph exceeds its scope budget")
            if (unit.logicalTableId != root.logicalTableId) throw ProducerContractException("LogicUnit reference must stay in its explicit logical source: $key")
            if (key !in candidates) {
                val unitOwner = logicUnitOwnerRef(unit.id, unit.currentVersion)
                val adopted = physicalOwner(unitOwner.logicalTableId, unitOwner.logicalTableVersion)
                if (adopted.physicalTableId != physical.physicalTableId || adopted.physicalTableVersion != physical.physicalTableVersion)
                    throw ProducerContractException("LogicUnit reference uses a different exact physical adoption: $key")
            }
            val expression = when (unit.sourceKind) {
                "COLUMN" -> {
                    val direct = candidateSources[key] ?: listLogicUnitSourceRefs(unit.id, unit.currentVersion).map {
                        LogicalTableCreateRequest.SourceRefInput(it.physicalColumnId, it.physicalColumnVersion, LogicUnitSourceRefRole.DIRECT_SOURCE)
                    }
                    if (direct.size != 1) throw ProducerContractException("COLUMN requires one exact physical source: $key")
                    SemanticExpression.Reference(SemanticExpression.ReferenceKind.PHYSICAL_COLUMN, direct.single().physicalColumnId, direct.single().physicalColumnVersion)
                }
                "EXPRESSION" -> requireNotNull(unit.expression) { "expression LogicUnit requires its authored expression" }
                else -> throw ProducerContractException("unsupported LogicUnit sourceKind: ${unit.sourceKind}")
            }
            if (SemanticExpressionInspector.quantifiers(expression).isNotEmpty()) throw ProducerContractException("relationship quantifiers belong to using inputs, not LogicUnit definitions")
            SemanticExpressionInspector.references(expression).forEach { reference ->
                val dependency = "${reference.id}@${reference.version}"
                when (reference.referenceKind) {
                    SemanticExpression.ReferenceKind.PHYSICAL_COLUMN -> columns.putIfAbsent(dependency, allowed[dependency] ?: throw ProducerContractException("physical column is outside the exact adopted source: $dependency"))
                    SemanticExpression.ReferenceKind.LOGIC_UNIT -> visit(candidates[dependency] ?: logicUnitSnapshot(reference.id, reference.version))
                    else -> throw ProducerContractException("LogicUnit cannot own relationship roles: $dependency")
                }
            }
            SemanticExpressionInspector.physicalTables(expression).forEach { reference ->
                val table = physicalAssets.nativeTable(reference)
                if (!table.matchesSource(owner.bindingRef(), owner.sourceConstraints.connectorRef, owner.dialect))
                    throw ProducerContractException("LogicUnit native table differs from its exact source binding, connector or dialect")
                nativeTables.putIfAbsent(table.key(), table)
            }
            expressions[key] = expression; units[key] = unit; visiting.remove(key)
        }
        visit(root)
        if (columns.isEmpty() && expressions.values.all { SemanticExpressionInspector.nativeCalls(it).isEmpty() }) throw ProducerContractException("LogicUnit requires an actual adopted source dependency")
        val expansion = LogicUnitExpansion(root, logicalVersion, physical, units, expressions, columns, nativeTables)
        try { validateTypes(expansion) }
        catch (invalid: IllegalArgumentException) { throw ProducerContractException("LogicUnit expression type is invalid: ${root.id}@${root.currentVersion}", invalid) }
        return expansion
    }
    private fun validateTypes(expansion: LogicUnitExpansion) {
        val evidence = physicalAssets.physicalTableSourceConstraints(expansion.physical.physicalTableId, expansion.physical.physicalTableVersion)
        val support = connectors.require(evidence.connectorRef).compilation
        val builder = CalciteCompilation.builder(support)
        val native = NativeExpressionScope(evidence.connectorRef, support) { expansion.nativeTables[it.key()] }
        val types = linkedMapOf<String, RexNode>()
        expansion.expressions.forEach { (key, expression) ->
            val value = SemanticCalciteRexCompiler(builder, mutableListOf()) { call, operands -> native.compile(builder, call, operands) }.compile(expression, { reference ->
                val dependency = "${reference.id}@${reference.version}"
                if (reference.referenceKind == SemanticExpression.ReferenceKind.LOGIC_UNIT) requireNotNull(types[dependency]) { "exact dependency type is missing" }
                else {
                    val column = requireNotNull(expansion.columns[dependency]) { "exact physical type is missing" }
                    builder.rexBuilder.makeInputRef(support.jdbcType(builder.typeFactory, column.jdbcColumnType), 0)
                }
            })
            val unit = requireNotNull(expansion.units[key])
            try {
                val shape = unit.valueShape
                if (shape != null) SemanticNativeTypes.requireSetType(value.type, shape)
                else if (unit.logicType != "ID" || !SqlTypeUtil.isNumeric(value.type)) SemanticNativeTypes.requireScalarType(value.type, ScalarValueType.fromLogicType(requireNotNull(unit.logicType)))
            } catch (invalid: IllegalArgumentException) { throw ProducerContractException("LogicUnit declared type differs from its exact expression: $key", invalid) }
            types[key] = value
        }
    }

    internal fun saveLogicalTable(record: LogicalTableRecord) = assets.saveLogicalTable(record)
    internal fun saveLogicalTableVersion(record: LogicalTableVersionRecord) {
        if (!assets.logicalTableIdentityExists(record.logicalTableId)) throw ProducerContractException("logical_table_version.logical_table_id not found: ${record.logicalTableId}")
        versions.saveLogicalTableVersion(record)
    }
    internal fun savePhysicalLogicalTableRef(record: PhysicalLogicalTableRefRecord) {
        physicalAssets.requirePhysicalTableVersion(record.physicalTableId, record.physicalTableVersion, "physical_logical_table_ref.physical_table_version")
        requireLogicalTableVersion(record.logicalTableId, record.logicalTableVersion, "physical_logical_table_ref.logical_table_version")
        assets.savePhysicalLogicalTableRef(record)
    }
    internal fun saveLogicUnit(record: LogicUnitRecord) {
        if (findLogicalTable(record.logicalTableId) == null) throw ProducerContractException("logic_unit.logical_table_id not found: ${record.logicalTableId}")
        assets.saveLogicUnit(record)
    }
    internal fun saveLogicUnitVersion(record: LogicUnitVersionRecord) {
        if (!assets.logicUnitIdentityExists(record.logicUnitId)) throw ProducerContractException("logic_unit_version.logic_unit_id not found: ${record.logicUnitId}")
        versions.saveLogicUnitVersion(record)
    }
    internal fun saveLogicalTableLogicUnitRef(record: LogicalTableLogicUnitRefRecord) {
        requireLogicalTableVersion(record.logicalTableId, record.logicalTableVersion, "logical_table_logic_unit_ref.logical_table_version")
        requireLogicUnitVersion(record.logicUnitId, record.logicUnitVersion, "logical_table_logic_unit_ref.logic_unit_version")
        val unit = findLogicUnit(record.logicUnitId) ?: throw ProducerContractException("logical_table_logic_unit_ref.logic_unit_id not found: ${record.logicUnitId}")
        if (unit.logicalTableId != record.logicalTableId) throw ProducerContractException("logic_unit.logical_table_id must match logical_table_logic_unit_ref.logical_table_id")
        versions.saveLogicalTableLogicUnitRef(record)
    }
    internal fun saveLogicUnitSourceRef(record: LogicUnitSourceRefRecord) {
        requireLogicUnitVersion(record.logicUnitId, record.logicUnitVersion, "logic_unit_source_ref.logic_unit_version")
        physicalAssets.requirePhysicalColumnVersion(record.physicalColumnId, record.physicalColumnVersion, "logic_unit_source_ref.physical_column_version")
        val unit = findLogicUnit(record.logicUnitId) ?: throw ProducerContractException("logic_unit_source_ref.logic_unit_id not found: ${record.logicUnitId}")
        val column = physicalAssets.findPhysicalColumn(record.physicalColumnId) ?: throw ProducerContractException("logic_unit_source_ref.physical_column_id not found: ${record.physicalColumnId}")
        if (listPhysicalLogicalTableRefsByLogicalTable(unit.logicalTableId).none { it.physicalTableId == column.physicalTableId }) throw ProducerContractException("logic_unit_source_ref.physical_column_id is not linked to logic_unit.logical_table through physical_logical_table_ref: ${record.physicalColumnId}")
        versions.saveLogicUnitSourceRef(record)
    }
    fun findLogicalTable(id: String): LogicalTableRecord? = assets.findLogicalTable(id)
    fun logicalTableSnapshot(id: String, version: Long): LogicalTableRecord = snapshots.logicalTable(id, version)
    fun listLogicalTables(): List<LogicalTableRecord> = assets.listLogicalTables()
    fun listLogicalTablesByPhysicalTable(id: String): List<LogicalTableRecord> = assets.listLogicalTablesByPhysicalTable(id)
    fun listPhysicalLogicalTableRefsByPhysicalTable(id: String): List<PhysicalLogicalTableRefRecord> = assets.listPhysicalLogicalTableRefsByPhysicalTable(id)
    fun listPhysicalLogicalTableRefsByLogicalTable(id: String): List<PhysicalLogicalTableRefRecord> = assets.listPhysicalLogicalTableRefsByLogicalTable(id)
    fun listLogicalTableLogicUnitRefs(id: String, version: Long): List<LogicalTableLogicUnitRefRecord> = snapshots.logicalTableLogicUnits(id, version)
    fun logicUnitOwnerRef(id: String, version: Long): LogicalTableLogicUnitRefRecord = snapshots.logicUnitOwner(id, version)
    fun findLogicUnit(id: String): LogicUnitRecord? = assets.findLogicUnit(id)
    fun logicUnitSnapshot(id: String, version: Long): LogicUnitRecord = snapshots.logicUnit(id, version)
    fun listLogicUnitsByLogicalTable(id: String): List<LogicUnitRecord> = assets.listLogicUnitsByLogicalTable(id)
    fun listLogicUnitSourceRefs(id: String, version: Long): List<LogicUnitSourceRefRecord> {
        requireLogicUnitVersion(id, version, "logic_unit_source_ref.logic_unit_version")
        return versions.listLogicUnitSourceRefs(id, version)
    }
    fun requireLogicalTableVersion(id: String, version: Long?, field: String) {
        if (findLogicalTable(id) == null) throw ProducerContractException("$field logical_table not found: $id")
        if (!versions.logicalTableVersionExists(id, requireVersion(version, field))) throw ProducerContractException("$field not found: $id#$version")
    }
    fun requireLogicUnitVersion(id: String, version: Long?, field: String) {
        if (findLogicUnit(id) == null) throw ProducerContractException("$field logic_unit not found: $id")
        if (!versions.logicUnitVersionExists(id, requireVersion(version, field))) throw ProducerContractException("$field not found: $id#$version")
    }
    private fun flag(value: Boolean?, field: String): Boolean = value ?: throw ProducerContractException("$field is required")
    private fun encodeDefinition(value: GovernanceDefinition): String = com.olap.prototype.codec.ProtoBytes.storage(com.olap.prototype.codec.GovernanceProto.definition(value))
}
