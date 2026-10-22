package com.olap.prototype.governance.application

import com.olap.prototype.contract.ContractSelectionException
import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.contract.InputContractException
import com.olap.prototype.contract.ProducerContracts
import com.olap.prototype.governance.contract.*
import com.olap.prototype.governance.contract.GovernanceReadRequests.AssetVersionRequest
import com.olap.prototype.governance.contract.GovernanceReadRequests.DefinitionDependenciesRequest
import com.olap.prototype.governance.contract.GovernanceReadRequests.ReleaseIdentityRequest
import com.olap.prototype.governance.contract.GovernanceReadRequests.Page
import com.olap.prototype.governance.contract.GovernanceReadRequests.EmptyInput
import com.olap.prototype.governance.contract.GovernanceReadRequests.OperationInput
import com.olap.prototype.governance.contract.GovernanceReadRequests.DescribeCommandInput
import com.olap.prototype.governance.contract.GovernanceReadRequests.PageInput
import com.olap.prototype.governance.contract.GovernanceReadRequests.SourceDiscoveriesInput
import com.olap.prototype.governance.contract.GovernanceReadRequests.SnapshotInput
import com.olap.prototype.governance.contract.GovernanceReadRequests.SnapshotPageInput
import com.olap.prototype.governance.contract.GovernanceReadRequests.DiscoveredTablePageInput
import com.olap.prototype.governance.contract.GovernanceReadRequests.SourcePageInput
import com.olap.prototype.governance.contract.GovernanceReadRequests.IdentityInput
import com.olap.prototype.governance.contract.GovernanceReadRequests.VersionInput
import com.olap.prototype.governance.contract.GovernanceReadRequests.VersionPageInput
import com.olap.prototype.governance.contract.GovernanceReadRequests.SubjectPageInput
import com.olap.prototype.management.governance.*
import com.olap.prototype.warehouse.WarehouseExecutionControl
import com.olap.prototype.warehouse.WarehouseSourceInspection
import com.olap.prototype.warehouse.contract.SourceBindingRef
import com.olap.prototype.warehouse.contract.SourceConfiguration
import com.olap.prototype.warehouse.onboarding.SourceStructureRegistrationRequest
import com.olap.prototype.warehouse.onboarding.WarehouseDiscoveryCommand
import com.olap.prototype.warehouse.onboarding.WarehouseOnboardingService
import java.util.Collections
import java.lang.reflect.Type
import com.olap.prototype.dsl.ApiDiscovery
import com.olap.prototype.dsl.transport.CapabilityRegistry
import com.olap.prototype.warehouse.onboarding.SourceDiscoveryReceipt

/** 写入只接受已认证主体；同一次类型解码依次执行作者核对、来源准入与领域操作。 */
class GovernanceOperations(
    private val application: GovernanceApplication,
    authorizedSources: Map<SourceBindingRef, SourceConfiguration>,
    private val onboarding: WarehouseOnboardingService,
) {
    private val discoverySources = Collections.unmodifiableMap(LinkedHashMap(authorizedSources))
    private val commands = registeredCommands()
    private val reads = GovernanceReads(application, { commands.keys.sorted() }, ::describeWriteCommand)

    private fun registeredCommands(): Map<String, Command<*>> = with(application) {
        mapOf(
            command("create-physical-table", physicalAssets::createPhysicalTable, actor { it.createdBy }),
            command("create-business-line", businessLines::createBusinessLine, actor { it.createdBy }),
            command("create-logical-table", logicalModel::createLogicalTable, actor { it.createdBy }),
            command("create-topology", topologies::createTopology, actor { it.createdBy }),
            command("create-topology-table-ref", topologies::createTopologyLogicalTableRef, noActor),
            command("create-join-path", topologies::createJoinPath, actor { it.createdBy }),
            command("create-event", events::createEvent, actor { it.createdBy }),
            command("create-metric", metrics::createMetric, actor { it.createdBy }),
            command("create-dimension", dimensions::createDimension, actor { it.createdBy }),
            command("create-dimension-event-binding", dimensions::createDimensionEventBinding, actor { it.createdBy }),
            command("create-subject-area", subjectAreas::createSubjectArea, actor { it.createdBy }),
            command("create-subject-area-metric-ref", adoptions::createSubjectAreaMetricRef, actor { it.selectedBy }),
            command("create-analysis-dimension", analysisDimensions::createAnalysisDimension, actor { it.createdBy }),
            command("create-member-source", adoptions::createMemberSource, actor { it.actor }),
            command("create-member-definition", memberDefinitions::create, actor { it.createdBy }),
            command("adopt-member-definition", adoptions::adoptMemberDefinition, actor { it.actor }),
            command("revise-member-adoption", application::reviseMemberAdoption, actor { it.actor }),
            command("create-analysis-dimension-event-mapping", analysisDimensions::createAnalysisDimensionEventMapping, noActor),
            command("create-analysis-dimension-value-provider", analysisDimensions::createAnalysisDimensionValueProvider, actor { it.createdBy }),
            command("create-analysis-dimension-value-alignment", analysisDimensions::createAnalysisDimensionValueAlignment, actor { it.confirmedBy }),
            command("create-scenario", scenarios::createScenario, actor { it.createdBy }),
            command("create-scenario-metric-ref", scenarios::createScenarioMetricRef, noActor),
            command("create-scenario-member-source-ref", scenarios::createScenarioMemberSourceRef, noActor),
            command("create-scenario-relation-filter", scenarios::createScenarioRelationFilter, noActor),
            command("create-scenario-analysis-dimension-ref", scenarios::createScenarioAnalysisDimensionRef, noActor),
            command("create-scenario-filter-ref", scenarios::createScenarioFilterRef, noActor),
            command("create-scenario-pipeline-rule", scenarios::createScenarioPipelineRule, noActor),
            command("refresh-scenario-pipeline-rule-contract", scenarios::refreshScenarioPipelineRuleContract, noActor),
            command("revise-physical-table", physicalAssets::revisePhysicalTable, actor { it.definition.createdBy }),
            command("update-physical-table-metadata", physicalAssets::updatePhysicalTableMetadata, actor { it.updatedBy }),
            command("update-physical-table-columns", physicalAssets::updatePhysicalTableColumns, actor { it.updatedBy }),
            command("revise-business-line", businessLines::reviseBusinessLine, actor { it.definition.createdBy }),
            command("revise-logical-table", logicalModel::reviseLogicalTable, actor { it.definition.createdBy }),
            command("revise-topology", topologies::reviseTopology, actor { it.definition.createdBy }),
            command("revise-join-path", topologies::reviseJoinPath, actor { it.definition.createdBy }),
            command("revise-event", events::reviseEvent, actor { it.definition.createdBy }),
            command("revise-metric", metrics::reviseMetric, actor { it.definition.createdBy }),
            command("create-metric-calculation", metrics::createMetricCalculation, actor { it.createdBy }),
            command("revise-metric-calculation", metrics::reviseMetricCalculation, actor { it.definition.createdBy }),
            command("revise-member-definition", memberDefinitions::revise, actor { it.definition.createdBy }),
            command("revise-dimension", dimensions::reviseDimension, actor { it.definition.createdBy }),
            command("revise-dimension-event-binding", dimensions::reviseDimensionEventBinding, actor { it.definition.createdBy }),
            command("revise-subject-area", application.contextRevisions::revise, actor { it.definition.createdBy }),
            command("revise-analysis-dimension", analysisDimensions::reviseAnalysisDimension, actor { it.definition.createdBy }),
            command("revise-scenario", scenarios::reviseScenario, actor { it.definition.createdBy }),
            command("activate", lifecycle::activate, actor { it.activatedBy }),
            command("deprecate", lifecycle::deprecate, actor { it.deprecatedBy }),
            command("build-release", releases::build, actor { it.createdBy }),
            "register-source-structure" to Command<SourceStructureRegistrationRequest>(
                SourceStructureRegistrationRequest::class.java,
                { request, control, _ ->
                    requireAuthorizedBinding(SourceBindingRef(request.dataSourceId, request.sourceIdentityHash))
                    control.checkCancellation()
                    onboarding.registerStructure(request).let { recorded -> SourceDiscoveryReceipt(recorded.snapshot.id, recorded.snapshot.dataSource, recorded.snapshot.sourceIdentityHash, recorded.snapshot.status, recorded.truncated, recorded.snapshot.tableCount, recorded.columns.size) }
                }, { request, authorize -> authorize(request.dataSourceId) }, actor { it.requestedBy }),
            "discover-source" to Command<WarehouseDiscoveryCommand>(
                WarehouseDiscoveryCommand::class.java,
                { request, control, inspection ->
                    requireAuthorizedBinding(SourceBindingRef(request.dataSourceId, request.sourceIdentityHash))
                    onboarding.discover(request, control, inspection).let { recorded -> SourceDiscoveryReceipt(recorded.snapshot.id, recorded.snapshot.dataSource, recorded.snapshot.sourceIdentityHash, recorded.snapshot.status, recorded.truncated, recorded.snapshot.tableCount, recorded.columns.size) }
                }, { request, authorize -> authorize(request.dataSourceId) }, actor { it.requestedBy }),
        )
    }

    fun dispatchAuthenticated(
        operation: String,
        request: Any,
        control: WarehouseExecutionControl,
        subject: String,
        inspection: WarehouseSourceInspection,
        authorizeSource: (String) -> Unit,
    ): Any {
        ProducerContracts.requireText(subject, "authenticated subject")
        val command = commands[operation] ?: throw ProducerContractException("authenticated governance dispatch requires a registered write command")
        return command.execute(request, control, subject, inspection, authorizeSource)
    }

    fun read(operation: String, payload: Any, control: WarehouseExecutionControl): Any {
        control.checkCancellation()
        val result = reads.read(operation, payload)
        control.checkCancellation()
        return result
    }

    fun readAssetVersion(request: AssetVersionRequest): Any = reads.readAssetVersion(request)

    private fun describeWriteCommand(input: DescribeCommandInput): Any {
        val operation = requireNotNull(input.operation?.takeIf { it.isNotBlank() }) { "operation must be explicit" }
        require(input.path.isNullOrEmpty() && input.variant == null) { "Kotlin API discovery selects a formal type or operation; JSON field selectors are not supported" }
        return ApiDiscovery.operation(operation)
    }

    fun writeInputType(operation: String): Type = commands[operation]?.type
        ?: throw ProducerContractException("unsupported authenticated governance command: $operation")
    fun writeOutputType(operation: String): Type = CapabilityRegistry.require(operation).outputType
    fun readInputType(operation: String): Type = GovernanceReads.inputType(operation)

    private fun requireAuthorizedBinding(reference: SourceBindingRef) {
        val source = discoverySources[reference]
        if (source == null || reference != source.bindingRef()) throw ProducerContractException("exact source binding is not authorized for discovery: $reference")
        application.warehouseRepository.findSourceBinding(reference)
            ?: throw ProducerContractException("exact authorized source binding has not been registered: $reference")
    }

    private class Command<T>(
        val type: Type,
        private val handler: (T, WarehouseExecutionControl, WarehouseSourceInspection) -> Any,
        private val authorizeAccess: (T, (String) -> Unit) -> Unit,
        private val verifyActor: (T, String) -> Unit,
    ) {
        fun execute(value: Any, control: WarehouseExecutionControl, subject: String,
            inspection: WarehouseSourceInspection, authorizeSource: (String) -> Unit): Any {
            val raw = when (type) { is Class<*> -> type; is java.lang.reflect.ParameterizedType -> type.rawType as Class<*>; else -> error("unsupported command type") }
            require(raw.isInstance(value)) { "command input differs from its registered type: $type" }
            @Suppress("UNCHECKED_CAST") val request = value as T
            verifyActor(request, subject)
            authorizeAccess(request, authorizeSource)
            control.checkCancellation()
            return handler(request, control, inspection)
        }
    }

    companion object {
        private val noActor: (Any?, String) -> Unit = { _, _ -> }
        private fun <T> actor(author: (T) -> String?): (T, String) -> Unit = { request, subject ->
            if (subject != author(request)) throw SecurityException("command actor conflicts with authenticated principal")
        }
        private fun <T> command(name: String, handler: (T) -> Any, actor: (T, String) -> Unit): Pair<String, Command<*>> =
            name to Command(CapabilityRegistry.require(name).inputType,
                { request, _, _ -> handler(request) }, { _, _ -> }, actor)

        fun readOperations(): List<String> = GovernanceReads.operations()
        fun describeReadOperation(operation: String): Any = GovernanceReads.describe(operation)
    }
}
