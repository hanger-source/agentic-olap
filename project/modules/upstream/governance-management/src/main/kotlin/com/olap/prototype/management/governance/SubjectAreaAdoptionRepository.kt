package com.olap.prototype.management.governance

import com.fasterxml.jackson.core.type.TypeReference
import com.olap.prototype.calculation.*
import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.contract.ProducerContracts.requireText
import com.olap.prototype.contract.ProducerContracts.requireVersion
import com.olap.prototype.governance.contract.*
import com.olap.prototype.management.governance.GovernanceJdbcSupport.execute
import com.olap.prototype.management.governance.GovernanceJdbcSupport.insert
import com.olap.prototype.management.governance.GovernanceJdbcSupport.instant
import com.olap.prototype.management.governance.GovernanceJdbcSupport.nullableLong
import com.olap.prototype.management.governance.GovernanceJdbcSupport.query
import com.olap.prototype.member.MemberSourceDefinition
import com.olap.prototype.metric.MetricBusinessMeaning
import com.olap.prototype.query.ScopedInputContract
import com.olap.prototype.query.contract.MetricAdoption
import com.olap.prototype.storage.PlatformDatabase
import com.olap.prototype.util.OlapJsonUtils
import java.sql.ResultSet

class SubjectAreaAdoptionRepository(private val database: PlatformDatabase) {
    fun saveSubjectAreaMetricRef(record: SubjectAreaMetricRefRecord) {
        requireText(record.id, "subject_area_metric_ref.id"); requireText(record.subjectAreaId, "subject_area_metric_ref.subject_area_id")
        requireVersion(record.subjectAreaVersion, "subject_area_metric_ref.subject_area_version"); requireText(record.code, "subject_area_metric_ref.code")
        requireText(record.displayName, "subject_area_metric_ref.display_name"); requireText(record.metricId, "subject_area_metric_ref.metric_id")
        requireVersion(record.metricVersion, "subject_area_metric_ref.metric_version"); requireText(record.status, "subject_area_metric_ref.status")
        requireText(record.selectedBy, "subject_area_metric_ref.selected_by"); GovernanceContract.requireInstant(record.selectedAt, "subject_area_metric_ref.selected_at")
        val changed = storage("cannot replace exact DRAFT metric adoption") {
            execute(database, "UPDATE subject_area_metric_ref SET status=?,selected_by=?,selected_at=?,display_name=?,business_meaning_json=?,metric_id=?,metric_version=? WHERE id=? AND subject_area_id=? AND subject_area_version=? AND code=?",
                listOf(record.status, record.selectedBy, record.selectedAt, record.displayName, json(record.businessMeaning), record.metricId, record.metricVersion, record.id, record.subjectAreaId, record.subjectAreaVersion, record.code))
        }
        if (changed == 1) return
        storage("Failed to save subject_area_metric_ref: ${record.id}") { insert(database, "subject_area_metric_ref", linkedMapOf("id" to record.id, "subject_area_id" to record.subjectAreaId,
            "subject_area_version" to record.subjectAreaVersion, "code" to record.code, "display_name" to record.displayName, "business_meaning_json" to json(record.businessMeaning),
            "metric_id" to record.metricId, "metric_version" to record.metricVersion, "status" to record.status, "selected_by" to record.selectedBy, "selected_at" to record.selectedAt,
            "instance_id" to MetricAdoption.instance(record.id, record.subjectAreaVersion).id)) }
    }
    fun listSubjectAreaMetricRefs(id: String, version: Long): List<SubjectAreaMetricRefRecord> {
        requireText(id, "subject_area_metric_ref.subject_area_id"); requireVersion(version, "subject_area_metric_ref.subject_area_version")
        return storage("Failed to list subject_area_metric_ref: $id#$version") { query(database, "SELECT * FROM subject_area_metric_ref WHERE subject_area_id=? AND subject_area_version=? ORDER BY code", listOf(id, version), ::metricRef) }
    }
    private fun metricRef(rs: ResultSet) = SubjectAreaMetricRefRecord(rs.getString("id"), rs.getString("subject_area_id"), requireNotNull(nullableLong(rs, "subject_area_version")),
        rs.getString("code"), rs.getString("display_name"), OlapJsonUtils.mapper().readValue(rs.getString("business_meaning_json"), MetricBusinessMeaning::class.java),
        rs.getString("metric_id"), requireNotNull(nullableLong(rs, "metric_version")), rs.getString("status"), rs.getString("selected_by"), requireNotNull(instant(rs, "selected_at")))

    fun replaceCalculationBindings(id: String, version: Long, bindings: CalculationBindings, fixedArguments: Map<String, Calculation.Value>) {
        // All rows are owned by the same exact adoption and replaced in its caller's transaction.
        update("DELETE FROM subject_area_metric_measurement_binding WHERE adoption_id=? AND adoption_version=?", id, version)
        update("DELETE FROM subject_area_metric_fixed_argument WHERE adoption_id=? AND adoption_version=?", id, version)
        bindings.measurements.forEach { (slot, target) -> update("INSERT INTO subject_area_metric_measurement_binding(adoption_id,adoption_version,slot,target_instance_id) VALUES (?,?,?,?)", id, version, slot, target.id) }
        fixedArguments.forEach { (name, value) -> update("INSERT INTO subject_area_metric_fixed_argument(adoption_id,adoption_version,parameter_name,value_json) VALUES (?,?,?,?)", id, version, name, json(value)) }
        update("DELETE FROM subject_area_metric_dimension_mapping WHERE adoption_id=? AND adoption_version=?", id, version)
        update("DELETE FROM subject_area_metric_dimension_binding WHERE adoption_id=? AND adoption_version=?", id, version)
        update("DELETE FROM subject_area_metric_grouping_parameter WHERE adoption_id=? AND adoption_version=?", id, version)
        update("DELETE FROM subject_area_metric_member_binding WHERE adoption_id=? AND adoption_version=?", id, version)
        update("DELETE FROM subject_area_metric_relation_binding WHERE adoption_id=? AND adoption_version=?", id, version)
        bindings.dimensions.forEach { (slot, value) -> saveDimension(id, version, slot, "INTERNAL", 0, value) }
        bindings.groupingCandidates.forEach { (parameter, values) ->
            update("INSERT INTO subject_area_metric_grouping_parameter(adoption_id,adoption_version,parameter_name) VALUES (?,?,?)", id, version, parameter)
            values.forEachIndexed { index, value -> saveDimension(id, version, parameter, "GROUPING", index, value) }
        }
        bindings.members.forEach { (slot, binding) ->
            val call = binding as? CalculationBindings.SourceCall
            update("INSERT INTO subject_area_metric_member_binding(adoption_id,adoption_version,slot,binding_kind,source_id,source_version,binding_json) VALUES (?,?,?,?,?,?,?)",
                id, version, slot, memberKind(binding), call?.source?.id, call?.source?.version, json(binding))
        }
        bindings.relations.forEach { (slot, binding) ->
            update("INSERT INTO subject_area_metric_relation_binding(adoption_id,adoption_version,slot,binding_json) VALUES (?,?,?,?)", id, version, slot, json(binding))
        }
    }
    private fun saveDimension(id: String, version: Long, slot: String, kind: String, index: Int, value: CalculationBindings.Dimension) {
        val ref = value.reference
        update("INSERT INTO subject_area_metric_dimension_binding(adoption_id,adoption_version,slot,binding_kind,candidate_index,analysis_dimension_id,analysis_dimension_version,level_code,role) VALUES (?,?,?,?,?,?,?,?,?)",
            id, version, slot, kind, index, ref.id, ref.version.toLong(), ref.levelCode, value.role)
        value.mappingIds.forEach { update("INSERT INTO subject_area_metric_dimension_mapping(adoption_id,adoption_version,slot,binding_kind,candidate_index,mapping_id) VALUES (?,?,?,?,?,?)", id, version, slot, kind, index, it) }
    }
    fun metricAdoptionBindings(id: String, version: Long): CalculationBindings = storage("cannot read exact metric adoption: $id") {
        val args = listOf(id, version)
        val dimensions = linkedMapOf<String, CalculationBindings.Dimension>()
        val groups = linkedMapOf<String, MutableList<CalculationBindings.Dimension>>()
        query(database, "SELECT parameter_name FROM subject_area_metric_grouping_parameter WHERE adoption_id=? AND adoption_version=? ORDER BY parameter_name", args) { it.getString(1) }.forEach { groups[it] = mutableListOf() }
        query(database, "SELECT * FROM subject_area_metric_dimension_binding WHERE adoption_id=? AND adoption_version=? ORDER BY binding_kind,slot,candidate_index", args) { row ->
            val slot = row.getString("slot")
            val kind = row.getString("binding_kind")
            val index = row.getInt("candidate_index")
            val mappings = query(database, "SELECT mapping_id FROM subject_area_metric_dimension_mapping WHERE adoption_id=? AND adoption_version=? AND slot=? AND binding_kind=? AND candidate_index=? ORDER BY mapping_id", listOf(id, version, slot, kind, index)) { it.getString(1) }
            val value = CalculationBindings.Dimension(ScopedInputContract.DimensionRef(row.getString("analysis_dimension_id"), row.getString("analysis_dimension_version"), row.getString("level_code")), row.getString("role"), mappings)
            if (kind == "INTERNAL") dimensions[slot] = value else requireNotNull(groups[slot]) { "grouping declaration is missing" }.add(value)
        }
        val members = query(database, "SELECT * FROM subject_area_metric_member_binding WHERE adoption_id=? AND adoption_version=? ORDER BY slot", args) { row ->
            val binding = OlapJsonUtils.mapper().readValue(row.getString("binding_json"), CalculationBindings.Member::class.java)
            if (memberKind(binding) != row.getString("binding_kind")) throw ProducerContractException("stored member binding kind differs from its exact content")
            if (binding is CalculationBindings.SourceCall && (binding.source.id != row.getString("source_id") || binding.source.version != row.getLong("source_version"))) throw ProducerContractException("stored member source differs from its exact binding")
            row.getString("slot") to binding
        }.toMap(linkedMapOf())
        val measurements = query(database, "SELECT slot,target_instance_id FROM subject_area_metric_measurement_binding WHERE adoption_id=? AND adoption_version=? ORDER BY slot", args) { it.getString(1) to MetricInstanceRef(it.getString(2)) }.toMap(linkedMapOf())
        val relations = query(database, "SELECT slot,binding_json FROM subject_area_metric_relation_binding WHERE adoption_id=? AND adoption_version=? ORDER BY slot", args) {
            it.getString(1) to OlapJsonUtils.mapper().readValue(it.getString(2), CalculationBindings.Relation::class.java)
        }.toMap(linkedMapOf())
        CalculationBindings(dimensions, members, groups, measurements, relations)
    }
    private fun memberKind(binding: CalculationBindings.Member): String = when (binding) {
        is CalculationBindings.SourceCall -> "SOURCE_CALL"
        is CalculationBindings.LocalRef -> "LOCAL"
        is CalculationBindings.InputParameter -> "PARAMETER"
    }
    fun metricInstanceReference(reference: MetricInstanceRef): SubjectAreaMetricRefRecord = checkedRead("cannot read exact adopted metric instance") {
        val rows = query(database, "SELECT * FROM subject_area_metric_ref WHERE instance_id=?", listOf(reference.id), ::metricRef)
        if (rows.isEmpty()) throw ProducerContractException("exact adopted metric instance does not exist: $reference")
        if (rows.size != 1 || rows.single().instance != reference) throw ProducerContractException("adopted metric instance identity is inconsistent: $reference")
        rows.single()
    }
    fun metricInstance(reference: MetricInstanceRef): MetricAdoption = metricInstanceReference(reference).let { metricAdoption(it.id, it.subjectAreaVersion) }
    private fun fixedArguments(id: String, version: Long): Map<String, Calculation.Value> = storage("cannot read adopted fixed arguments") {
        query(database, "SELECT parameter_name,value_json FROM subject_area_metric_fixed_argument WHERE adoption_id=? AND adoption_version=? ORDER BY parameter_name", listOf(id, version)) {
            it.getString(1) to OlapJsonUtils.mapper().readValue(it.getString(2), Calculation.Value::class.java)
        }.toMap().let { java.util.Map.copyOf(it) }
    }
    fun metricAdoption(id: String, version: Long): MetricAdoption = checkedRead("cannot read exact metric adoption") {
        val rows = query(database, "SELECT * FROM subject_area_metric_ref WHERE id=? AND subject_area_version=?", listOf(id, version)) { row ->
            val adoption = MetricAdoption(id, row.getString("subject_area_id"), version, row.getString("code"), row.getString("display_name"), OlapJsonUtils.mapper().readValue(row.getString("business_meaning_json"), MetricBusinessMeaning::class.java),
                MetricRef(row.getString("metric_id"), row.getString("metric_version")), metricAdoptionBindings(id, version), fixedArguments(id, version))
            if (adoption.instance.id != row.getString("instance_id")) throw ProducerContractException("stored adopted instance differs from its exact adoption identity")
            adoption
        }
        rows.firstOrNull() ?: throw ProducerContractException("exact metric adoption does not exist: $id")
    }

    fun saveMemberSource(source: MemberSourceRecord) {
        update("INSERT INTO subject_area_member_source(id,subject_area_id,subject_area_version,code,display_name,definition_origin_json,definition_json,bindings_json,fixed_arguments_json) VALUES (?,?,?,?,?,?,?,?,?)",
            source.id, source.subjectAreaId, source.subjectAreaVersion, source.code, source.displayName, json(source.origin),
            if (source.origin is MemberDefinitionOrigin.Local) json(source.definition) else null, json(source.bindings), fixedMemberJson(source.fixedArguments))
        saveMemberOutputs(source)
    }
    fun replaceMemberAdoption(source: MemberSourceRecord) {
        require(source.origin is MemberDefinitionOrigin.Shared) { "member adoption revision requires a shared definition" }
        storage("cannot replace exact member adoption") {
            if (execute(database, "UPDATE subject_area_member_source SET display_name=?,definition_origin_json=?,bindings_json=?,fixed_arguments_json=? WHERE id=? AND subject_area_id=? AND subject_area_version=? AND code=?",
                listOf(source.displayName, json(source.origin), json(source.bindings), fixedMemberJson(source.fixedArguments),
                    source.id, source.subjectAreaId, source.subjectAreaVersion, source.code)) != 1)
                throw IllegalStateException("member adoption revision requires one exact existing source")
            execute(database, "DELETE FROM subject_area_member_output WHERE source_id=? AND source_version=?", listOf(source.id, source.subjectAreaVersion))
        }
        saveMemberOutputs(source)
    }
    private fun saveMemberOutputs(source: MemberSourceRecord) {
        source.definition.result.outputs.forEach { (slot, output) ->
            val columns = linkedMapOf<String, Any?>("source_id" to source.id, "source_version" to source.subjectAreaVersion, "slot" to slot)
            when (output) {
                is MemberSourceDefinition.DimensionOutput -> { columns["output_kind"] = "DIMENSION"; columns["binding_id"] = output.bindingId; columns["binding_version"] = output.bindingVersion }
                is MemberSourceDefinition.PropertyOutput -> { columns["output_kind"] = "PROPERTY"; columns["logic_unit_id"] = output.logicUnitId; columns["logic_unit_version"] = output.logicUnitVersion }
                is MemberSourceDefinition.ComputedDimensionOutput -> { columns["output_kind"] = "COMPUTED_DIMENSION"; columns["dimension_slot"] = output.dimensionSlot; columns["symbol"] = output.symbol }
                is MemberSourceDefinition.ComputedPropertyOutput -> { columns["output_kind"] = "COMPUTED_PROPERTY"; columns["symbol"] = output.symbol }
                is MemberSourceDefinition.ContainerPropertyOutput -> { columns["output_kind"] = "CONTAINER_PROPERTY"; columns["logic_unit_id"] = output.logicUnitId; columns["logic_unit_version"] = output.logicUnitVersion }
                is MemberSourceDefinition.ComputedContainerPropertyOutput -> { columns["output_kind"] = "COMPUTED_CONTAINER_PROPERTY"; columns["symbol"] = output.symbol }
            }
            storage("cannot persist exact subject-area input or binding") { insert(database, "subject_area_member_output", columns) }
        }
    }
    fun memberSources(id: String, version: Long): List<MemberSourceRecord> = storage("cannot read exact member sources") {
        query(database, "SELECT * FROM subject_area_member_source WHERE subject_area_id=? AND subject_area_version=? ORDER BY code", listOf(id, version), ::memberSource)
    }
    fun memberSource(id: String, version: Long): MemberSourceRecord = checkedRead("cannot read exact member source") {
        query(database, "SELECT * FROM subject_area_member_source WHERE id=? AND subject_area_version=?", listOf(id, version), ::memberSource).firstOrNull() ?: throw ProducerContractException("exact member source is missing: $id@$version")
    }
    private fun memberSource(row: ResultSet): MemberSourceRecord {
        val origin = OlapJsonUtils.mapper().readValue(row.getString("definition_origin_json"), MemberDefinitionOrigin::class.java)
        val definition = when (origin) {
            is MemberDefinitionOrigin.Local -> OlapJsonUtils.mapper().readValue(requireNotNull(row.getString("definition_json")) { "local member definition is required" }, MemberSourceDefinition::class.java)
            is MemberDefinitionOrigin.Shared -> {
                if (row.getString("definition_json") != null) throw ProducerContractException("shared member adoption cannot own a second definition body")
                MemberDefinitionRepository(database).read(origin.reference.id, origin.reference.version).definition
            }
        }
        return MemberSourceRecord(row.getString("id"), row.getString("subject_area_id"), row.getLong("subject_area_version"), row.getString("code"), row.getString("display_name"), origin, definition,
            OlapJsonUtils.mapper().readValue(row.getString("bindings_json"), CalculationBindings::class.java), OlapJsonUtils.mapper().readValue(row.getString("fixed_arguments_json"), argumentType))
    }
    private val argumentType = object : TypeReference<Map<String, Calculation.Value>>() {}
    private fun fixedMemberJson(arguments: Map<String, Calculation.Value>): String = try { OlapJsonUtils.mapper().writerFor(argumentType).writeValueAsString(arguments) }
        catch (failure: Exception) { throw ProducerContractException("invalid fixed member arguments", failure) }
    private fun json(value: Any): String = try { OlapJsonUtils.mapper().writeValueAsString(value) } catch (failure: Exception) { throw ProducerContractException("invalid semantic adoption value", failure) }
    private fun update(sql: String, vararg values: Any?) { storage("cannot persist exact subject-area input or binding") { execute(database, sql, values.toList()) } }
    private fun <T> checkedRead(message: String, action: () -> T): T = try { action() } catch (failure: ProducerContractException) { throw failure } catch (failure: Exception) { throw IllegalStateException(message, failure) }
    private fun <T> storage(message: String, action: () -> T): T = try { action() } catch (failure: Exception) { throw IllegalStateException(message, failure) }
}
