package com.olap.prototype.contract

open class ProducerContractException(override val message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

/** 仅表示发现请求的无效选择，不混淆定义或存储完整性失败。 */
class ContractSelectionException(message: String) : ProducerContractException(message)

object ProducerContracts {
    fun requireText(value: String?, fieldName: String): String = value?.takeIf { it.isNotBlank() }
        ?: throw ProducerContractException("$fieldName must be produced explicitly")

    fun requireVersion(value: Long?, fieldName: String): Long = value?.takeIf { it > 0 }
        ?: throw ProducerContractException("$fieldName must be a positive produced version")

    fun requireDiagnosticSeverity(value: String?, fieldName: String): String = requireText(value, fieldName).trim().also {
        if (it !in setOf("ERROR", "WARNING", "INFO")) throw ProducerContractException("$fieldName must be ERROR / WARNING / INFO: $it")
    }

    fun requireVersionedRefPair(objectId: String?, objectVersion: Long?, refName: String) {
        if (!objectId.isNullOrBlank() != (objectVersion != null)) throw ProducerContractException("$refName must provide object id and version together")
        if (objectVersion != null && objectVersion <= 0) throw ProducerContractException("$refName version must be positive")
    }

    fun <T> requireList(value: List<T>?, fieldName: String): List<T> = java.util.List.copyOf(value
        ?: throw ProducerContractException("$fieldName must be produced explicitly"))
    fun <K, V> requireMap(value: Map<K, V>?, fieldName: String): Map<K, V> = java.util.Map.copyOf(value
        ?: throw ProducerContractException("$fieldName must be produced explicitly"))

    fun requireExactFields(arguments: Map<String, Any?>, expected: Set<String>, field: String) {
        val actual = arguments.keys
        require(actual == expected) { "$field.arguments must contain exactly $expected, missing=${expected - actual}, unknown=${actual - expected}" }
    }
}
