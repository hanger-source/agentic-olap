package com.olap.prototype.server

import com.olap.prototype.codec.GovernanceCapabilityCodec
import com.olap.prototype.dsl.transport.CapabilityRegistry
import com.olap.prototype.governance.application.GovernanceOperations

/** 接收已经按正式消息解码的请求，作者和来源授权仍由现有领域入口核对。 */
internal class ServerGovernance(private val governance: GovernanceOperations,
    private val authorization: ServerAuthorization, private val workers: RemoteWorkers) {
    fun invoke(operation: String, request: ServerRequest, input: Any): Any {
        val permission=CapabilityRegistry.require(operation).permission
        authorization.governance(request.principal,permission)
        request.control.checkCancellation()
        return if (GovernanceCapabilityCodec.isWrite(operation)) {
            governance.dispatchAuthenticated(operation,input,request.control,request.principal.subject,
                SourceInvestigationClient(workers,request.work)) { source -> authorization.source(request.principal,permission,source) }
        } else {
            require(GovernanceCapabilityCodec.isRead(operation)) { "unregistered governance read" }
            governance.read(operation,input,request.control)
        }
    }
}
