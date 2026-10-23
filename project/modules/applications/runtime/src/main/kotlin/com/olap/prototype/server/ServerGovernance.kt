package com.olap.prototype.server

import com.olap.prototype.codec.GovernanceCapabilityCodec
import com.olap.prototype.codec.GovernanceProto
import com.olap.prototype.dsl.transport.CapabilityRegistry
import com.olap.prototype.governance.application.GovernanceOperations
import com.olap.prototype.governance.application.GovernanceWriteEffect
import com.olap.prototype.governance.application.GovernanceWriteReceipts
import com.olap.prototype.protocol.CapabilityEffect
import com.olap.prototype.protocol.CapabilityOwnedMember

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
                .also { result -> request.completedEffects.add(effect(GovernanceWriteReceipts.project(result))) }
        } else {
            require(GovernanceCapabilityCodec.isRead(operation)) { "unregistered governance read" }
            governance.read(operation,input,request.control)
        }
    }

    private fun effect(value: GovernanceWriteEffect): CapabilityEffect = CapabilityEffect.newBuilder()
        .setReadOperation(value.readOperation).apply {
            value.status?.let(::setStatus)
            when (value) {
                is GovernanceWriteEffect.AssetVersion -> setAssetVersion(GovernanceProto.toProto(value.reference))
                is GovernanceWriteEffect.OwnedMember -> setOwnedMember(CapabilityOwnedMember.newBuilder()
                    .setOwner(GovernanceProto.toProto(value.owner)).setKind(value.kind).setId(value.id))
                is GovernanceWriteEffect.Release -> setRelease(GovernanceProto.toProto(value.reference))
                is GovernanceWriteEffect.SourceDiscovery -> setSourceDiscovery(value.snapshotId)
            }
        }.build()
}
