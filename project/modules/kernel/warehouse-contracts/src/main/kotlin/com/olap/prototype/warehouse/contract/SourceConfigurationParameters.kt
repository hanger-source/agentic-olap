package com.olap.prototype.warehouse.contract

/** 插件明确声明参数角色；必填值与值域仍由该插件校验。 */
class SourceConfigurationParameters(identityNames: Set<String>, runtimeNames: Set<String>) {
    val identityNames: Set<String> = java.util.Set.copyOf(identityNames)
    val runtimeNames: Set<String> = java.util.Set.copyOf(runtimeNames)
    init { require(this.identityNames.intersect(this.runtimeNames).isEmpty() && (this.identityNames + this.runtimeNames).all { it.isNotBlank() }) }
    fun requireConfiguration(source: SourceConfiguration) {
        require(identityNames.containsAll(source.identityParameters.keys)) { "source identity parameter is not declared by the exact plugin" }
        require(runtimeNames.containsAll(source.runtimeParameters.keys)) { "source runtime parameter is not declared by the exact plugin" }
    }
}
