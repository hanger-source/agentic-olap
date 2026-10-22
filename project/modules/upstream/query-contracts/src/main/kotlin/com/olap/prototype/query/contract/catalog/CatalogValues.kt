package com.olap.prototype.query.contract.catalog

import com.olap.prototype.contract.ProducerContracts
import java.util.Collections

/** 公开目录的领域值约束；不承担字节编解码。 */
object CatalogValues {
    @JvmStatic
    fun text(value: String): String = ProducerContracts.requireText(value, "published catalog text")

    @JvmStatic
    fun version(value: Long): Long {
        require(value > 0) { "exact positive published version required" }
        return value
    }

    @JvmStatic
    fun <T> map(values: Map<String, T>): Map<String, T> = Collections.unmodifiableMap(LinkedHashMap(values))

    @JvmStatic
    fun <T> nonempty(values: List<T>): List<T> {
        require(values.isNotEmpty()) { "published list must be nonempty" }
        return Collections.unmodifiableList(ArrayList(values))
    }
}
