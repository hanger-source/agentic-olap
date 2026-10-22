package com.olap.prototype.util

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.cfg.CoercionAction
import com.fasterxml.jackson.databind.cfg.CoercionInputShape
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.type.LogicalType
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinFeature
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.slf4j.LoggerFactory

/** 共同 JSON 边界；保留精确数值和缺失、NULL、空集合的不同含义。 */
object OlapJsonUtils {
    private val log = LoggerFactory.getLogger(OlapJsonUtils::class.java)
    private val MAPPER = ObjectMapper()
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
        .setNodeFactory(JsonNodeFactory.withExactBigDecimals(true))
        .enable(SerializationFeature.WRITE_BIGDECIMAL_AS_PLAIN)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .registerModule(JavaTimeModule())
        .registerModule(KotlinModule.Builder()
            .enable(KotlinFeature.StrictNullChecks)
            .disable(KotlinFeature.NullIsSameAsDefault)
            .disable(KotlinFeature.NullToEmptyCollection)
            .disable(KotlinFeature.NullToEmptyMap)
            .build())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .apply {
            for (shape in listOf(CoercionInputShape.Integer, CoercionInputShape.Float, CoercionInputShape.Boolean))
                coercionConfigFor(LogicalType.Textual).setCoercion(shape, CoercionAction.Fail)
            coercionConfigFor(LogicalType.Integer)
                .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
            coercionConfigFor(LogicalType.Boolean)
                .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
        }

    @JvmStatic
    fun mapper(): ObjectMapper = MAPPER

    @JvmStatic
    fun <T> loadFromResource(path: String, clazz: Class<T>): T = try {
        val stream = OlapJsonUtils::class.java.classLoader.getResourceAsStream(path)
            ?: throw IllegalStateException("Resource not found: $path")
        stream.use { MAPPER.readValue(it, clazz) }
    } catch (failure: Exception) {
        log.error("JSON 加载失败: {}", path, failure)
        throw RuntimeException(failure)
    }

    @JvmStatic
    fun <T> loadListFromResource(path: String, clazz: Class<T>): List<T> = try {
        val stream = OlapJsonUtils::class.java.classLoader.getResourceAsStream(path)
            ?: throw IllegalStateException("Resource not found: $path")
        stream.use { MAPPER.readValue(it, MAPPER.typeFactory.constructCollectionType(List::class.java, clazz)) }
    } catch (failure: Exception) {
        log.error("JSON 列表加载失败: {}", path, failure)
        throw RuntimeException(failure)
    }
}
