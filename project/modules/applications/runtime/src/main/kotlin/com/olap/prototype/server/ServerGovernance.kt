package com.olap.prototype.server

import com.fasterxml.jackson.databind.JsonNode
import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.governance.application.GovernanceOperations
import com.olap.prototype.governance.application.GovernanceWriteReceipts
import com.olap.prototype.server.ServerInputs.fields
import com.olap.prototype.server.ServerInputs.text

internal class ServerGovernance(
    private val governance: GovernanceOperations,
    private val authorization: ServerAuthorization,
    private val workers: RemoteWorkers,
) {
    private fun inspection(request: ServerRequest) = SourceInvestigationClient(workers, request.work)

    fun language(operation: String, request: ServerRequest, payload: JsonNode): Any {
        fields(payload, if (operation == "read-query-language-contract")
            setOf("section", "root", "operation", "path", "variant") else emptySet())
        return governance.read(operation, payload, request.control)
    }

    fun commands(request: ServerRequest, payload: JsonNode): Any {
        fields(payload, emptySet())
        authorization.governance(request.principal, "discover-governance-commands")
        return governance.read("list-write-commands", payload, request.control)
    }

    fun reads(request: ServerRequest, payload: JsonNode): Any {
        fields(payload, emptySet())
        authorization.governance(request.principal, "discover-governance-reads")
        return GovernanceOperations.readOperations()
    }

    fun describeRead(request: ServerRequest, payload: JsonNode): Any {
        fields(payload, setOf("operation"))
        authorization.governance(request.principal, "describe-governance-read")
        return GovernanceOperations.describeReadOperation(text(payload, "operation"))
    }

    fun describeCommand(request: ServerRequest, payload: JsonNode): Any {
        fields(payload, setOf("operation", "path", "variant"))
        authorization.governance(request.principal, "describe-governance-command")
        return governance.read("describe-write-command", payload, request.control)
    }

    fun read(request: ServerRequest, payload: JsonNode): Any {
        fields(payload, setOf("operation", "payload"))
        authorization.governance(request.principal, "read-governance")
        val operation = text(payload, "operation")
        if (operation !in GovernanceOperations.readOperations()) throw ProducerContractException("read-governance accepts only registered read operations")
        return governance.read(operation, payload.required("payload"), request.control)
    }

    fun write(request: ServerRequest, payload: JsonNode): Any {
        fields(payload, setOf("operation", "payload"))
        authorization.governance(request.principal, "write-governance")
        val command = text(payload, "operation")
        val result = governance.dispatchAuthenticated(command, payload.required("payload"), request.control,
            request.principal.subject, inspection(request)) { source -> authorization.source(request.principal, "write-governance", source) }
        return GovernanceWriteReceipts.project(command, result)
    }
}
