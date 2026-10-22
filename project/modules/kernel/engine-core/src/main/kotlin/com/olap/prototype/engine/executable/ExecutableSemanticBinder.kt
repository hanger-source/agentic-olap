package com.olap.prototype.engine.executable

import com.olap.prototype.connector.ConnectorRegistry
import com.olap.prototype.engine.catalog.ExecutableCatalog

/** 每次业务请求创建独立绑定生命周期。 */
class ExecutableSemanticBinder(private val connectors: ConnectorRegistry) {
    fun bind(catalog: ExecutableCatalog, request: ExecutableQueryRequest): ExecutableInputScope = ExecutableBindingSession(catalog, connectors).bind(request)
}
