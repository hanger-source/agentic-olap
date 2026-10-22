package com.olap.prototype.connector

/** 调用者持有的不可变精确实现表；不扫描目录、不选默认后端。 */
class ConnectorRegistry(implementations: Collection<ResolvedConnector>) {
    private val connectors: Map<ConnectorRef, ResolvedConnector> = java.util.Collections.unmodifiableMap(
        linkedMapOf<ConnectorRef, ResolvedConnector>().apply {
            implementations.forEach { connector ->
                val functions = connector.compilation.nativeScalarFunctions
                require(functions.map { it.id }.distinct().size == functions.size) { "duplicate native scalar function in exact connector: ${connector.ref}" }
                require(putIfAbsent(connector.ref, connector) == null) { "duplicate exact connector: ${connector.ref}" }
            }
        },
    )
    fun require(reference: ConnectorRef): ResolvedConnector = connectors[reference] ?: throw ConnectorUnavailableException(reference)
    fun references(): Set<ConnectorRef> = connectors.keys
}
