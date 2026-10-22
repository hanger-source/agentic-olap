package com.olap.prototype.query.contract.catalog

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.cfg.CoercionAction
import com.fasterxml.jackson.databind.cfg.CoercionInputShape
import com.fasterxml.jackson.databind.type.LogicalType
import com.olap.prototype.contract.ProducerContracts
import com.olap.prototype.util.OlapJsonUtils
import java.io.IOException
import java.util.Collections

/** 公开目录 JSON 边界；字段来自 Jackson 正式绑定，不保存另一份字段协议。 */
object CatalogJson {
    private val MAPPER: ObjectMapper = OlapJsonUtils.mapper().copy().apply {
        enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
            DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
            DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
        disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT,
            DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT)
        disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
        for (shape in listOf(CoercionInputShape.Integer, CoercionInputShape.Float, CoercionInputShape.Boolean))
            coercionConfigFor(LogicalType.Textual).setCoercion(shape, CoercionAction.Fail)
    }

    @JvmStatic
    fun <T> read(value: JsonNode, type: Class<T>): T = try {
        MAPPER.readerFor(type).readValue(value.traverse(MAPPER))
    } catch (failure: IOException) {
        throw IllegalArgumentException("invalid published catalog ${type.simpleName}", failure)
    }

    @JvmStatic
    fun <T> list(value: JsonNode?, type: Class<T>): List<T> {
        require(value != null && value.isArray) { "published catalog requires an explicit list" }
        return Collections.unmodifiableList(value.map { read(it, type) })
    }

    @JvmStatic
    fun <T> required(value: JsonNode, name: String, type: Class<T>): T {
        require(value.has(name)) { "missing published catalog field: $name" }
        return read(value.get(name), type)
    }

    @JvmStatic
    fun fields(value: JsonNode?, type: Class<*>, excluded: Set<String>) {
        val required = linkedSetOf<String>()
        val allowed = linkedSetOf<String>()
        for (field in MAPPER.deserializationConfig.introspect(MAPPER.constructType(type)).findProperties()) {
            if (!field.couldDeserialize() || field.name in excluded || field.internalName in excluded) continue
            allowed.add(field.name)
            // 自定义目录解码器允许 CatalogOptional 缺失；只以正式显式 required 标记补充字段检查。
            if (field.primaryMember?.getAnnotation(JsonProperty::class.java)?.required == true) required.add(field.name)
        }
        fields(value, required, allowed)
    }

    @JvmStatic
    fun fields(value: JsonNode?, required: Set<String>, allowed: Set<String>) {
        require(value != null && value.isObject) { "published catalog object is required" }
        val actual = value.fieldNames().asSequence().toSet()
        require(actual.containsAll(required) && allowed.containsAll(actual)) {
            "published catalog fields differ from their type"
        }
    }

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
