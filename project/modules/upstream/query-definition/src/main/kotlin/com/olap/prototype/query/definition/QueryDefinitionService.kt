package com.olap.prototype.query.definition

import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.query.contract.*

/** 保存定义的业务规则；实际原生编译由注入的无来源访问校验端口完成。 */
class QueryDefinitionService(
    private val repository: QueryDefinitionRepository,
    private val queryCapabilityProvider: PublishedQueryEntryProvider,
    private val nativeValidator: NativeValidator,
) : QueryDefinitionReader {
    fun save(command: QueryDefinitionCommand, createdBy: String): QueryDefinitionView {
        if (command.sourceVersion != null) throw ProducerContractException("query_definition.sourceVersion must be absent when creating an identity")
        return repository.save(command, QueryDefinitionWriteContext(validateEntry(command), createdBy))
    }

    fun update(definitionId: String, command: QueryDefinitionCommand, createdBy: String): QueryDefinitionView {
        val id = requireId(definitionId)
        val sourceVersion = requireVersion(command.sourceVersion)
        if (command.definitionId != null && id != command.definitionId) throw ProducerContractException("query_definition.definitionId does not match the update target")
        return repository.update(id, sourceVersion, command, QueryDefinitionWriteContext(validateEntry(command), createdBy))
    }

    fun find(scenarioId: String?, scenarioVersion: Long?, releaseId: String?, releaseVersion: String?): List<QueryDefinitionView> {
        paired(scenarioId, scenarioVersion, "scenario")
        paired(releaseId, releaseVersion, "release")
        return repository.find(scenarioId, scenarioVersion, releaseId, releaseVersion)
    }

    fun findByIdAndVersion(definitionId: String, definitionVersion: Long): QueryDefinitionView? =
        repository.findByIdAndVersion(requireId(definitionId), requireVersion(definitionVersion))

    override fun requireByIdAndVersion(definitionId: String, definitionVersion: Long): QueryDefinitionView =
        findByIdAndVersion(definitionId, definitionVersion)
            ?: throw ProducerContractException("query_definition_version missing: $definitionId#$definitionVersion")

    fun validate(command: QueryDefinitionCommand): QueryCapabilityView = validateEntry(command).capability

    private fun validateEntry(command: QueryDefinitionCommand): PublishedQueryEntry {
        val entry = queryCapabilityProvider.require(QueryCapabilityRequest(command.scenarioId, command.scenarioVersion, command.releaseId, command.releaseVersion))
        val capability = entry.capability
        if (command.capabilityHash != QueryCapabilityHash.requireValid(capability)) throw ProducerContractException("query_definition.capabilityHash does not match exact QueryCapability")
        if (capability.releaseId != command.releaseId || capability.releaseVersion != command.releaseVersion ||
            capability.scenario.scenarioId != command.scenarioId || capability.scenario.scenarioVersion != command.scenarioVersion)
            throw ProducerContractException("QueryDefinition command does not match QueryCapabilityView boundary")
        nativeValidator.validate(command.intent, capability)
        return entry
    }

    private fun paired(id: String?, version: Any?, name: String) {
        if ((!id.isNullOrBlank()) != (version != null && version.toString().isNotBlank()))
            throw ProducerContractException("query_definition list filter requires $name id and version together")
    }

    private fun requireId(value: String): String = value.takeIf { it.isNotBlank() }
        ?: throw ProducerContractException("query_definition_id is required")
    private fun requireVersion(value: Long?): Long = value?.takeIf { it > 0 }
        ?: throw ProducerContractException("query_definition_version is required")
}
