package com.olap.prototype.connector.loader

import com.olap.prototype.connector.ConnectorRegistry
import com.olap.prototype.connector.SourceBindingUnavailableException
import com.olap.prototype.connector.SourceProvider
import com.olap.prototype.warehouse.contract.SourceBindingRef
import com.olap.prototype.warehouse.contract.SourceConfiguration

/** 精确部署绑定事实；不从来源ID或登记顺序选择新旧版本。 */
class DeployedSources(sources: Collection<SourceConfiguration>, connectors: ConnectorRegistry) {
    private val configurations: Map<SourceBindingRef, SourceConfiguration>
    private val providers: Map<SourceBindingRef, SourceProvider>

    init {
        val configured = linkedMapOf<SourceBindingRef, SourceConfiguration>()
        sources.forEach { source ->
            require(configured.putIfAbsent(source.bindingRef(), source) == null) { "duplicate deployed source binding: ${source.bindingRef()}" }
        }
        configurations = configured.toMap()
        providers = configured.mapValues { (_, source) -> SourceProvider(source, connectors) }
    }

    fun configurations(): List<SourceConfiguration> = configurations.values.toList()
    fun bindings(): Map<SourceBindingRef, SourceConfiguration> = configurations
    fun requireConfiguration(binding: SourceBindingRef): SourceConfiguration =
        configurations[binding] ?: throw SourceBindingUnavailableException(binding)
    fun requireProvider(binding: SourceBindingRef): SourceProvider =
        providers[binding] ?: throw SourceBindingUnavailableException(binding)
}
