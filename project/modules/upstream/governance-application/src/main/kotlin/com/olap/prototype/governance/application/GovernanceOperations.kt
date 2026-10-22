package com.olap.prototype.governance.application

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JavaType
import com.fasterxml.jackson.databind.JsonNode
import com.olap.prototype.contract.ContractSelectionException
import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.contract.InputContractException
import com.olap.prototype.contract.ProducerContracts
import com.olap.prototype.governance.contract.*
import com.olap.prototype.management.governance.*
import com.olap.prototype.util.OlapJsonUtils
import com.olap.prototype.util.WireTypeShape
import com.olap.prototype.warehouse.WarehouseExecutionControl
import com.olap.prototype.warehouse.WarehouseSourceInspection
import com.olap.prototype.warehouse.contract.SourceBindingRef
import com.olap.prototype.warehouse.contract.SourceConfiguration
import com.olap.prototype.warehouse.onboarding.SourceStructureRegistrationRequest
import com.olap.prototype.warehouse.onboarding.WarehouseDiscoveryCommand
import com.olap.prototype.warehouse.onboarding.WarehouseOnboardingService
import java.util.Collections

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
                mapper.constructType(SourceStructureRegistrationRequest::class.java),
                { request, control, _ ->
                    requireAuthorizedBinding(SourceBindingRef(request.dataSourceId, request.sourceIdentityHash))
                    control.checkCancellation()
                    onboarding.registerStructure(request)
                }, { request, authorize -> authorize(request.dataSourceId) }, actor { it.requestedBy }),
            "discover-source" to Command<WarehouseDiscoveryCommand>(
                mapper.constructType(WarehouseDiscoveryCommand::class.java),
                { request, control, inspection ->
                    requireAuthorizedBinding(SourceBindingRef(request.dataSourceId, request.sourceIdentityHash))
                    onboarding.discover(request, control, inspection)
                }, { request, authorize -> authorize(request.dataSourceId) }, actor { it.requestedBy }),
        )
    }

    fun dispatchAuthenticated(
        operation: String,
        payload: JsonNode,
        control: WarehouseExecutionControl,
        subject: String,
        inspection: WarehouseSourceInspection,
        authorizeSource: (String) -> Unit,
    ): Any {
        ProducerContracts.requireText(subject, "authenticated subject")
        val command = commands[operation] ?: throw ProducerContractException("authenticated governance dispatch requires a registered write command")
        return command.execute(payload, control, subject, inspection, authorizeSource)
    }

    fun read(operation: String, payload: JsonNode, control: WarehouseExecutionControl): Any {
        control.checkCancellation()
        val result = reads.read(operation, payload)
        control.checkCancellation()
        return result
    }

    fun readAssetVersion(request: AssetVersionRequest): Any = reads.readAssetVersion(request)

    private fun describeWriteCommand(input: DescribeCommandInput): Any {
        val operation = input.operation?.takeIf { it.isNotBlank() } ?: throw ContractSelectionException("operation must be explicit")
        val selected = commands[operation] ?: throw ContractSelectionException("unsupported write command")
        val shape = GovernanceCommandSchema.describe(selected.type, input.path, input.variant)
        val described = linkedMapOf<String, Any>()
        mapper.valueToTree<JsonNode>(shape).fields().forEachRemaining { described[it.key] = it.value }
        described["operation"] = operation
        described["path"] = input.path ?: emptyList<String>()
        input.variant?.let { described["variant"] = it }
        described["selectionContract"] = mapOf(
            "path" to "EXACT_JSON_FIELD_NAMES_FROM_COMMAND_ROOT_COLLECTIONS_FOLLOW_ELEMENT_OR_VALUE_TYPE_NO_INDEXES",
            "variant" to "EXACT_WIRE_UNION_TAG_AT_PATH_ENDPOINT",
            "placement" to "INPUT_SHAPE_REPLACES_ONLY_THE_SELECTED_COMMAND_FIELD_OR_VARIANT", "unknownSelectors" to "REJECT")
        when (operation) {
            "revise-subject-area" -> described["revisionContract"] = mapOf(
                "sourceVersion" to "EXACT_LATEST_SUBJECT_VERSION_NEW_DRAFT_CREATED_OLD_ACTIVE_VERSION_PRESERVED",
                "carry" to "EXPLICIT_SELECTED_IDENTITIES_FROM_READ_SUBJECT_CONTEXT_SELECTION_EMPTY_MEANS_NONE_DEPENDENCY_OMISSIONS_REJECTED",
                "definitions" to "SHARED_DEFINITIONS_AND_EXACT_SOURCE_BINDINGS_REUSED_NO_BUSINESS_FORMULA_REWRITE",
                "children" to "NEW_EXACT_DIMENSION_AND_SCENARIO_VERSIONS_MEMBER_AND_METRIC_ADOPTIONS_REBOUND_AND_REVALIDATED_ATOMICALLY",
                "authorization" to "ONLY_SELECTED_EXISTING_SCENARIO_RULE_CONTENT_CARRIED_NEW_VERSIONS_REQUIRE_EXPLICIT_ACTIVATION",
                "frozenQueries" to "OLD_RELEASES_AND_SAVED_QUERIES_KEEP_THEIR_EXACT_COORDINATES",
                "failure" to "STALE_CHILD_VERSION_MISSING_DEPENDENCY_OR_INVALID_BINDING_ROLLS_BACK_THE_ENTIRE_REVISION")
            "activate" -> described["lifecycleContract"] = GovernanceActivationObjectType.contract()
            "deprecate" -> described["lifecycleContract"] = GovernanceActivationObjectType.deprecationContract()
            "revise-member-adoption" -> described["revisionContract"] = mapOf(
                "source" to "EXACT_EXISTING_SHARED_ADOPTION_IN_DRAFT_SUBJECT_AREA_IDENTITY_AND_CODE_PRESERVED",
                "expectedDefinition" to "EXACT_CURRENT_SHARED_REFERENCE_REQUIRED_STALE_REFERENCE_RETURNS_MEMBER_ADOPTION_CONFLICT",
                "definition" to "EXPLICIT_REFERENCEABLE_SHARED_DEFINITION_NO_LATEST_SELECTION",
                "bindings" to "COMPLETE_REPLACEMENT_BINDINGS_AND_FIXED_ARGUMENTS_NOT_A_PATCH",
                "dependencies" to "ALL_CONTEXT_CALCULATIONS_AND_EXISTING_SCENARIO_PERMISSIONS_REVALIDATED_IN_ONE_TRANSACTION_FAILURE_ROLLS_BACK",
                "references" to "NO_AUTOMATIC_DOWNSTREAM_REWRITE_OR_PERMISSION_CHANGE_OLD_CONTEXTS_AND_RELEASES_UNCHANGED",
                "impact" to "READ_DEFINITION_IMPACT_FOR_MEMBER_SOURCE_AND_READ_THE_EXACT_SOURCE_BEFORE_REVISION")
            "revise-analysis-dimension" -> described["revisionContract"] = mapOf(
                "sourceVersion" to "LATEST_VERSION_OF_THIS_IDENTITY_REQUIRED_DRAFT_SOURCE_ARCHIVED_ACTIVE_SOURCE_PRESERVED_NEW_DRAFT_CREATED",
                "definition" to "ROOT_REPLACEMENT_WITH_REQUESTED_DEFAULT_LEVEL_AND_SUBJECT_AREA_REFERENCE_ONLY",
                "children" to "NO_COPY_OF_OLD_LEVELS_EVENT_MAPPINGS_VALUE_PROVIDERS_OR_VALUE_ALIGNMENTS_EXPLICIT_RECONSTRUCTION",
                "subjectArea" to "IDENTITY_PRESERVED_SAME_VERSION_CAS_REPLACES_OLD_REF_NEW_DRAFT_SUBJECT_VERSION_ADDS_REF",
                "readChildren" to listOf("read-analysis-dimension-levels", "read-analysis-dimension-mappings", "read-analysis-dimension-providers", "read-analysis-dimension-alignments"),
                "createChildren" to listOf("create-analysis-dimension-event-mapping", "create-analysis-dimension-value-provider", "create-analysis-dimension-value-alignment"))
        }
        return WireTypeShape.withContentHash(described)
    }

    private fun requireAuthorizedBinding(reference: SourceBindingRef) {
        val source = discoverySources[reference]
        if (source == null || reference != source.bindingRef()) throw ProducerContractException("exact source binding is not authorized for discovery: $reference")
        application.warehouseRepository.findSourceBinding(reference)
            ?: throw ProducerContractException("exact authorized source binding has not been registered: $reference")
    }

    private class Command<T>(
        val type: JavaType,
        private val handler: (T, WarehouseExecutionControl, WarehouseSourceInspection) -> Any,
        private val authorizeAccess: (T, (String) -> Unit) -> Unit,
        private val verifyActor: (T, String) -> Unit,
    ) {
        fun execute(payload: JsonNode, control: WarehouseExecutionControl, subject: String,
            inspection: WarehouseSourceInspection, authorizeSource: (String) -> Unit): Any {
            if (!payload.isObject) throw ProducerContractException("governance command payload must be a JSON object")
            val request: T = try { mapper.readerFor(type).readValue(payload.traverse(mapper)) }
                catch (invalid: JsonProcessingException) { throw InputContractException.decoding(invalid, payload) }
            verifyActor(request, subject)
            authorizeAccess(request, authorizeSource)
            control.checkCancellation()
            return handler(request, control, inspection)
        }
    }

    data class AssetVersionRequest(val assetType: String?, val id: String?, val version: Long)
    data class DefinitionDependenciesRequest(val assetType: String, val id: String, val version: Long, val offset: Int, val limit: Int)
    data class ReleaseIdentityRequest(val releaseId: String, val releaseVersion: String)
    class Page(items: List<*>, val total: Int, val nextOffset: Int?) { val items: List<*> = Collections.unmodifiableList(ArrayList(items)) }
    class EmptyInput
    data class OperationInput(val operation: String?)
    class DescribeCommandInput(val operation: String?, path: List<String>?, val variant: String?) {
        val path: List<String>? = path?.let { Collections.unmodifiableList(ArrayList(it)) }
    }
    data class PageInput(val offset: Int?, val limit: Int?)
    data class SourceDiscoveriesInput(val dataSourceId: String?, val catalog: String?, val schemaName: String?, val offset: Int?, val limit: Int?)
    data class SnapshotInput(val snapshotId: String?)
    data class SnapshotPageInput(val snapshotId: String?, val offset: Int?, val limit: Int?)
    data class DiscoveredTablePageInput(val discoveredTableId: String?, val offset: Int?, val limit: Int?)
    data class SourcePageInput(val dataSourceId: String?, val offset: Int?, val limit: Int?)
    data class IdentityInput(val id: String?)
    data class VersionInput(val id: String?, val version: Long)
    data class VersionPageInput(val id: String?, val version: Long, val offset: Int?, val limit: Int?)
    data class SubjectPageInput(val subjectAreaId: String?, val offset: Int?, val limit: Int?)

    companion object {
        private val mapper = OlapJsonUtils.mapper().copy().enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS,
            DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        private val noActor: (Any?, String) -> Unit = { _, _ -> }
        private fun <T> actor(author: (T) -> String?): (T, String) -> Unit = { request, subject ->
            if (subject != author(request)) throw SecurityException("command actor conflicts with authenticated principal")
        }
        private inline fun <reified T> command(name: String, noinline handler: (T) -> Any, noinline actor: (T, String) -> Unit): Pair<String, Command<*>> =
            name to Command(mapper.constructType(object : TypeReference<T>() {}),
                { request, _, _ -> handler(request) }, { _, _ -> }, actor)

        fun readOperations(): List<String> = GovernanceReads.operations()
        fun describeReadOperation(operation: String): Any = GovernanceReads.describe(operation)
    }
}
