package com.olap.prototype.server

internal data class ServerRequest(val principal: ServerAuthorization.Principal, val work: RemoteWorkers.Request) {
    val control get() = work.control
}
