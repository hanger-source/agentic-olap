package com.olap.prototype.server

import com.olap.prototype.protocol.CapabilityEffect

internal data class ServerRequest(val principal: ServerAuthorization.Principal, val work: RemoteWorkers.Request,
    val completedEffects: MutableList<CapabilityEffect> = ArrayList(1)) {
    val control get() = work.control
}
