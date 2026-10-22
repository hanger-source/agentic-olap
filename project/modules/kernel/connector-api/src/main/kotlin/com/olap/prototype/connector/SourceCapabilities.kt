package com.olap.prototype.connector

/** 来源明确兑现的独立能力；是否必需由本次操作决定。 */
class SourceCapabilities(supported: Set<Capability>) {
    val supported: Set<Capability> = java.util.Set.copyOf(supported)
    enum class Capability {
        PARAMETER_BINDING, CANCELLATION, CONSISTENT_READ, TRANSACTION_SNAPSHOT,
        STRUCTURE_INSPECTION, DATA_PROFILING, RELATIONSHIP_PROFILING, DATA_PREVIEW,
        CONSTRAINT_VERIFICATION, SEALED_DATASET_VERIFICATION, PINNED_READ,
    }
    fun require(dataSourceId: String, operation: String, capability: Capability) {
        if (capability !in supported) throw SourceCapabilityException(dataSourceId, operation, capability)
    }
    override fun equals(other: Any?): Boolean = other is SourceCapabilities && supported == other.supported
    override fun hashCode(): Int = supported.hashCode()
}
