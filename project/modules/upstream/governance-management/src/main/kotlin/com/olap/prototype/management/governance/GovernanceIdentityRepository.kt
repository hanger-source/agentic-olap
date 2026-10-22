package com.olap.prototype.management.governance

import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.contract.ProducerContracts.requireText
import com.olap.prototype.governance.contract.*
import com.olap.prototype.storage.PlatformDatabase
import java.time.Instant

/** 这里只保存稳定身份；所有业务定义由精确版本正文拥有。 */
class GovernanceIdentityRepository(private val database: PlatformDatabase) {
    fun savePhysicalTable(record: PhysicalTableRecord) = save("physical_table", record.id, record.createdBy, record.createdAt, "code" to record.code)
    fun savePhysicalColumn(record: PhysicalColumnRecord) = save("physical_column", record.id, record.createdBy, record.createdAt, "physical_table_id" to record.physicalTableId)
    fun saveLogicalTable(record: LogicalTableRecord) = save("logical_table", record.id, record.createdBy, record.createdAt, "code" to record.code)
    fun saveLogicUnit(record: LogicUnitRecord) = save("logic_unit", record.id, record.createdBy, record.createdAt, "logical_table_id" to record.logicalTableId, "code" to record.code)
    fun saveTopology(record: TopologyRecord) = save("topology", record.id, record.createdBy, record.createdAt, "code" to record.code, "topology_implementation_namespace_code" to record.topologyImplementationNamespaceCode)
    fun saveJoinPath(record: JoinPathRecord) = save("join_path", record.id, record.createdBy, record.createdAt, "topology_id" to record.topologyId, "code" to record.code)
    fun saveEvent(record: EventRecord) = save("event", record.id, record.createdBy, record.createdAt, "topology_id" to record.topologyId, "code" to record.code)
    fun saveMetric(record: MetricRecord) = save("metric", record.id, record.createdBy, record.createdAt, "code" to record.code)
    fun saveDimension(record: DimensionRecord) = save("dimension", record.id, record.createdBy, record.createdAt, "code" to record.code, "dimension_code_namespace" to record.dimensionCodeNamespace, "local_code" to record.localCode)
    fun saveDimensionEventBinding(record: DimensionEventBindingRecord) = save("dimension_event_binding", record.id, record.createdBy, record.createdAt, "dimension_id" to record.dimensionId)
    fun saveBusinessLine(record: BusinessLineRecord) = save("business_line", record.id, record.createdBy, record.createdAt, "code" to record.code)
    fun saveSubjectArea(record: SubjectAreaRecord) = save("subject_area", record.id, record.createdBy, record.createdAt, "business_line_id" to record.businessLineId, "code" to record.code)
    fun saveAnalysisDimension(record: AnalysisDimensionRecord) = save("analysis_dimension", record.id, record.createdBy, record.createdAt, "subject_area_id" to record.subjectAreaId, "code" to record.code)
    fun saveScenario(record: ScenarioRecord) = save("scenario", record.id, record.createdBy, record.createdAt, "subject_area_id" to record.subjectAreaId, "code" to record.code)

    fun exists(table: String, id: String): Boolean {
        requireTable(table); requireText(id, "$table.id")
        return try { GovernanceJdbcSupport.query(database, "SELECT 1 FROM $table WHERE id=?", listOf(id)) { true }.isNotEmpty() }
        catch (failure: Exception) { throw IllegalStateException("Failed to check $table identity: $id", failure) }
    }
    private fun save(table: String, id: String, actor: String, createdAt: Instant, vararg fields: Pair<String, String>) {
        requireTable(table); requireText(id, "$table.id"); requireText(actor, "identity.created_by")
        val stable = linkedMapOf<String, Any>()
        fields.forEach { (field, value) -> stable[requireText(field, "identity field")] = requireText(value, "identity field: $field") }
        stable["created_by"] = actor; stable["created_at"] = createdAt
        try {
            database.connection().use { connection ->
                val columns = stable.keys.joinToString(", ")
                connection.prepareStatement("SELECT $columns FROM $table WHERE id=?").use { statement ->
                    statement.setString(1, id)
                    statement.executeQuery().use { rows ->
                        if (rows.next()) {
                            fields.forEach { (field, value) -> if (rows.getObject(field) != value) throw ProducerContractException("$table immutable identity conflict: $id.$field") }
                            return
                        }
                    }
                }
                connection.prepareStatement("INSERT INTO $table (id, $columns) VALUES (${List(stable.size + 1) { "?" }.joinToString(", ")})").use { statement ->
                    statement.setString(1, id)
                    stable.values.forEachIndexed { index, value -> statement.setObject(index + 2, if (value is Instant) GovernanceJdbcSupport.timestamp(value) else value) }
                    statement.executeUpdate()
                }
            }
        } catch (failure: ProducerContractException) { throw failure }
        catch (failure: Exception) { throw IllegalStateException("Failed to save $table identity: $id", failure) }
    }
    private fun requireTable(table: String) { require(GovernanceDraftVersions.Asset.entries.any { it.table == table }) { "unsupported governance identity table: $table" } }
}
