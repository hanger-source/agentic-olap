package com.olap.prototype.util

import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.databind.JavaType
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition
import com.olap.prototype.contract.ContractSelectionException
import com.olap.prototype.query.PostOperationContract
import com.olap.prototype.query.PostOperationCodec
import com.olap.prototype.query.PostOperationSchema
import java.lang.reflect.Modifier
import java.security.MessageDigest
import java.util.HexFormat

/** 从 Jackson 实际字段与具名子类型生成有限类型图；领域字段规则仍由所属入口提供。 */
open class WireTypeShape(protected val MAPPER: ObjectMapper) {
    private val names = linkedMapOf<String, String>()
    private val definitions = linkedMapOf<String, Any>()

    fun describe(type: JavaType, formatVersion: String): Map<String, Any> =
        body(type).toMutableMap().apply {
            put("formatVersion", formatVersion)
            putIfAbsent("definitions", definitions)
        }

    fun describe(type: JavaType, formatVersion: String, path: List<String>?, variant: String?): Map<String, Any> {
        val selected = locate(type, path, variant)
        val field = selected.field ?: return describe(selected.type, formatVersion)
        return fieldShape(requireNotNull(selected.owner).rawClass, field, selected.owner).toMutableMap().apply {
            putIfAbsent("formatVersion", formatVersion)
            put("selectedField", mapOf(
                "name" to field.name,
                "requiredByJavaType" to field.primaryType.rawClass.isPrimitive,
                "requiredByJson" to field.isRequired,
            ))
            putIfAbsent("definitions", definitions)
        }
    }

    private data class Selection(val type: JavaType, val owner: JavaType? = null, val field: BeanPropertyDefinition? = null)

    /** 路径仅由正式 JSON 字段名构成；集合和字典自动指向元素或值类型。 */
    fun select(type: JavaType, path: List<String>?, variant: String?): JavaType = locate(type, path, variant).type

    private fun locate(initial: JavaType, path: List<String>?, variant: String?): Selection {
        var type = initial
        var owner: JavaType? = null
        var selectedField: BeanPropertyDefinition? = null
        for (name in path.orEmpty()) {
            type = elementType(type)
            if (type.rawClass == PostOperationContract.ValidatedOperation::class.java)
                throw ContractSelectionException("post-operation arguments use the operation selector, not object-field navigation")
            if (name.isBlank() || !isObject(type))
                throw ContractSelectionException("contract path requires an exact object field: $name")
            val field = properties(type).singleOrNull { it.name == name }
                ?: throw ContractSelectionException("unknown contract field: $name")
            owner = type
            selectedField = field
            type = field.primaryType
        }
        if (variant == null) return Selection(type, owner, selectedField)
        type = elementType(type)
        if (variant.isBlank() || typeInfo(type) == null || !Modifier.isAbstract(type.rawClass.modifiers))
            throw ContractSelectionException("variant requires a named wire union: $variant")
        val subtype = subtypes(type)[variant]
            ?: throw ContractSelectionException("unknown contract variant: $variant")
        return Selection(subtype)
    }

    private fun elementType(initial: JavaType): JavaType {
        var type = initial
        while (type.isCollectionLikeType || type.isMapLikeType) type = type.contentType
        return type
    }

    protected fun shape(type: JavaType): Map<String, Any> {
        if (type.rawClass == PostOperationContract.ValidatedOperation::class.java)
            return mapOf("kind" to "discriminated-operation", "discriminatorField" to "opType",
                "fields" to listOf("opType", "arguments"), "contract" to PostOperationSchema.contract())
        if (!isObject(type) && typeInfo(type) == null) return body(type)
        val key = type.toCanonical()
        val name = names[key] ?: "${type.rawClass.simpleName}_${names.size}".also {
            names[key] = it
            definitions[it] = emptyMap<String, Any>()
            definitions[it] = body(type)
        }
        return mapOf("kind" to "reference", "definition" to name)
    }

    private fun properties(type: JavaType): List<BeanPropertyDefinition> =
        MAPPER.deserializationConfig.introspect(wireType(type)).findProperties().filter { it.couldDeserialize() }

    private fun wireType(type: JavaType): JavaType =
        if (type.rawClass == PostOperationContract.ResultAggregate::class.java) MAPPER.constructType(PostOperationCodec.AggregateArguments::class.java) else type

    private fun typeInfo(type: JavaType): JsonTypeInfo? =
        MAPPER.deserializationConfig.introspectClassAnnotations(type).classInfo.getAnnotation(JsonTypeInfo::class.java)

    private fun isObject(type: JavaType): Boolean = !type.isContainerType && !type.rawClass.isEnum &&
        !type.isPrimitive && !type.isJavaLangObject && type.rawClass != String::class.java &&
        !Number::class.java.isAssignableFrom(type.rawClass) && type.rawClass != java.lang.Boolean::class.java &&
        !Modifier.isAbstract(type.rawClass.modifiers) && !type.rawClass.name.startsWith("java.")

    private fun subtypes(type: JavaType): Map<String, JavaType> {
        val config = MAPPER.deserializationConfig
        val result = sortedMapOf<String, JavaType>()
        for (subtype in MAPPER.subtypeResolver.collectAndResolveSubtypesByTypeId(config, config.introspectClassAnnotations(type).classInfo)) {
            if (!type.rawClass.isAssignableFrom(subtype.type) || Modifier.isAbstract(subtype.type.modifiers)) continue
            require(subtype.hasName()) { "wire subtype requires an explicit wire name: ${subtype.type}" }
            require(result.put(subtype.name, MAPPER.constructType(subtype.type)) == null) {
                "duplicate wire subtype wire name: ${subtype.name}"
            }
        }
        require(result.isNotEmpty()) { "wire union has no named concrete variants: ${type.toCanonical()}" }
        return result
    }

    private fun body(type: JavaType): Map<String, Any> {
        val wire = wireType(type)
        if (wire != type) return body(wire)
        if (type.isCollectionLikeType) return mapOf("kind" to "array", "element" to shape(type.contentType))
        if (type.isMapLikeType) {
            require(type.keyType.rawClass == String::class.java) { "wire dictionaries require JSON string keys: ${type.toCanonical()}" }
            return mapOf("kind" to "dictionary", "key" to mapOf("kind" to "string"), "value" to shape(type.contentType))
        }
        val cls = type.rawClass
        val discriminator = typeInfo(type)
        require(discriminator == null || discriminator.use == JsonTypeInfo.Id.NAME && discriminator.include == JsonTypeInfo.As.PROPERTY) {
            "wire contract requires named property-discriminated types: ${type.toCanonical()}"
        }
        if (isObject(type)) {
            val fields = mutableListOf<Map<String, Any>>()
            if (discriminator != null) {
                val serializer = MAPPER.serializerFactory.createTypeSerializer(MAPPER.serializationConfig, type)
                val tag = serializer.typeIdResolver.idFromValueAndType(null, cls)
                fields.add(mapOf("name" to discriminator.property, "shape" to mapOf("kind" to "enum", "values" to listOf(tag)), "requiredByJson" to true))
            }
            for (field in properties(type)) {
                if (field.name == discriminator?.property) continue
                fields.add(mapOf("name" to field.name, "shape" to fieldShape(cls, field, type),
                    "requiredByJavaType" to field.primaryType.rawClass.isPrimitive, "requiredByJson" to field.isRequired))
            }
            return linkedMapOf<String, Any>("kind" to "object", "fields" to fields).apply {
                val rules = typeRules(type)
                if (rules.isNotEmpty()) put("rules", rules)
            }
        }
        if (discriminator != null) return mapOf("kind" to "union", "discriminator" to discriminator.property,
            "variants" to subtypes(type).mapValues { shape(it.value) })
        return when (cls) {
            String::class.java -> mapOf("kind" to "string")
            java.lang.Boolean::class.java, Boolean::class.javaPrimitiveType -> mapOf("kind" to "boolean")
            java.lang.Long::class.java, Long::class.javaPrimitiveType,
            java.lang.Integer::class.java, Int::class.javaPrimitiveType,
            java.lang.Short::class.java, Short::class.javaPrimitiveType,
            java.lang.Byte::class.java, Byte::class.javaPrimitiveType -> mapOf("kind" to "integer")
            java.lang.Double::class.java, Double::class.javaPrimitiveType,
            java.lang.Float::class.java, Float::class.javaPrimitiveType -> mapOf("kind" to "number")
            else -> if (cls.isEnum) mapOf("kind" to "enum", "values" to cls.enumConstants.map { (it as Enum<*>).name })
                else throw IllegalArgumentException("wire contract has an unsupported wire type: ${type.toCanonical()}")
        }
    }

    protected open fun fieldShape(owner: Class<*>, field: BeanPropertyDefinition, type: JavaType): Map<String, Any> = shape(field.primaryType)
    protected open fun typeRules(type: JavaType): Map<String, Any> = emptyMap()

    companion object {
        /** 摘要不依赖 Map 实现或 JSON 属性顺序；不包含摘要字段自身。 */
        @JvmStatic
        fun withContentHash(content: Map<String, *>): Map<String, Any?> {
            val mapper = OlapJsonUtils.mapper()
            val bytes = mapper.writeValueAsBytes(canonical(mapper.valueToTree(content)))
            return LinkedHashMap<String, Any?>(content).apply {
                put("contentHash", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
            }
        }

        private fun canonical(node: JsonNode): JsonNode = when {
            node.isObject -> OlapJsonUtils.mapper().createObjectNode().apply {
                node.fields().asSequence().toList().sortedBy { it.key }.forEach { set<JsonNode>(it.key, canonical(it.value)) }
            }
            node.isArray -> OlapJsonUtils.mapper().createArrayNode().apply { node.forEach { add(canonical(it)) } }
            else -> node
        }
    }
}
