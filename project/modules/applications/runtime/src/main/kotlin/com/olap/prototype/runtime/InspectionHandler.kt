package com.olap.prototype.runtime

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.olap.prototype.connector.ConnectorRegistry
import com.olap.prototype.connector.loader.DeployedSources
import com.olap.prototype.expression.SemanticExpression
import com.olap.prototype.expression.SemanticExpressionCodec
import com.olap.prototype.server.ServerInputs.fields
import com.olap.prototype.server.ServerInputs.text
import com.olap.prototype.util.OlapJsonUtils
import com.olap.prototype.warehouse.WarehouseExecutionControl
import com.olap.prototype.warehouse.contract.SourceBindingRef
import com.olap.prototype.warehouse.contract.SourceConfiguration
import com.olap.prototype.warehouse.inspection.*

/** 技术调查按同一typed注册解码一次；connection只从监督者授予的精确绑定取得。 */
internal class InspectionHandler(connectors: ConnectorRegistry, configured: List<SourceConfiguration>) {
    private val mapper = OlapJsonUtils.mapper()
    private val sources = DeployedSources(configured, connectors)
    private val inspector = PluginWarehouseInspector(connectors)
    private class Operation(val fields: Set<String>, val execute: (JsonNode, WarehouseExecutionControl) -> Any)

    private fun <T> typed(type: Class<T>, fields: Set<String>, action: (T, WarehouseExecutionControl) -> Any): Operation =
        Operation(fields) { node, control -> action(mapper.treeToValue(node, type), control) }

    private val operations = mapOf(
        "discover" to typed(WarehouseDiscoveryRequest::class.java,
            setOf("dataSourceId", "sourceIdentityHash", "catalog", "schemaPattern", "tablePattern", "tableTypes", "limit"), inspector::discover),
        "describe" to typed(WarehouseTableInspectionRequest::class.java,
            setOf("dataSourceId", "sourceIdentityHash", "table"), inspector::describe),
        "snapshot" to typed(WarehouseSchemaSnapshotRequest::class.java,
            setOf("dataSourceId", "sourceIdentityHash", "catalog", "schemaName", "tablePattern", "tableTypes", "limit"), inspector::snapshot),
        "preview" to typed(WarehousePreviewRequest::class.java,
            setOf("dataSourceId", "sourceIdentityHash", "table", "limit"), inspector::preview),
        "profile" to typed(WarehouseProfileRequest::class.java,
            setOf("dataSourceId", "sourceIdentityHash", "table", "columns", "timeoutSeconds"), inspector::profile),
        "profile-relationship" to typed(WarehouseRelationshipProfileRequest::class.java,
            setOf("dataSourceId", "sourceIdentityHash", "sourceInput", "targetInput", "timeoutSeconds"), inspector::profileRelationship),
    )

    fun execute(operation: String, payload: JsonNode, control: WarehouseExecutionControl): Any {
        control.checkCancellation()
        if (operation == "read-inspection-capability") {
            fields(payload, emptySet())
            return mapOf("formatVersion" to "olap-inspection-capability/v2",
                "relationshipExpressionContract" to SemanticExpressionCodec.wireContract(SemanticExpression.ReferenceKind.PHYSICAL_COLUMN, false),
                "operations" to (operations.keys + setOf("read-inspection-capability", "list-data-sources")).sorted())
        }
        if (operation == "list-data-sources") {
            fields(payload, emptySet())
            return sources.configurations().map { mapOf("dataSourceId" to it.dataSourceId,
                "sourceIdentityHash" to it.sourceIdentityHash(), "dialectId" to it.dialectId) }
        }
        val selected = operations[operation] ?: throw IllegalArgumentException("unsupported inspection operation")
        fields(payload, selected.fields)
        val source = sources.requireConfiguration(SourceBindingRef(text(payload, "dataSourceId"), text(payload, "sourceIdentityHash")))
        val request = (payload as ObjectNode).deepCopy()
        request.remove(listOf("dataSourceId", "sourceIdentityHash"))
        request.set<JsonNode>("connection", mapper.valueToTree(source))
        val result = selected.execute(request, control)
        control.checkCancellation()
        return result
    }
}
