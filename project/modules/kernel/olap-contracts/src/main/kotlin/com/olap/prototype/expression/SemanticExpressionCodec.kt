package com.olap.prototype.expression

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.olap.prototype.connector.ConnectorRef
import com.olap.prototype.event.RelationAccessPath
import com.olap.prototype.expression.SemanticExpression.*
import com.olap.prototype.metric.MetricValueContract.Rounding
import com.olap.prototype.util.OlapJsonUtils
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

/** 规范表达式JSON的唯一编解码入口。 */
object SemanticExpressionCodec {
    fun parse(json: String, label: String): SemanticExpression {
        require(json.isNotBlank()) { "$label 不能为空" }
        return try { parse(OlapJsonUtils.mapper().readTree(json), label) }
        catch (failure: IllegalArgumentException) { throw failure }
        catch (failure: Exception) { throw IllegalArgumentException("$label 不是合法的规范表达式 JSON", failure) }
    }

    fun write(expression: SemanticExpression): String = try { OlapJsonUtils.mapper().writeValueAsString(toJsonNode(expression)) }
        catch (failure: Exception) { throw IllegalArgumentException("规范表达式无法序列化", failure) }

    fun logicUnit(id: String, version: Long): Reference = Reference(ReferenceKind.LOGIC_UNIT, id, version)
    fun and(expressions: List<SemanticExpression>): SemanticExpression {
        require(expressions.isNotEmpty()) { "AND expressions 不能为空" }
        return expressions.singleOrNull() ?: Operation(Operator.AND, expressions)
    }

    fun parse(node: JsonNode?, label: String): SemanticExpression {
        require(node != null && node.isObject) { "$label 必须是 JSON object" }
        val kind = text(node, "kind", label)
        val required = when (kind) {
            "LOGIC_UNIT" -> setOf("kind", "logicUnitId", "logicUnitVersion")
            "ROLE_LOGIC_UNIT" -> setOf("kind", "role", "logicUnitId", "logicUnitVersion")
            "PHYSICAL_COLUMN" -> setOf("kind", "physicalColumnId", "physicalColumnVersion")
            "LITERAL", "EXACT_NUMBER" -> setOf("kind", "value")
            "CALL" -> setOf("kind", "function", "arguments")
            "NATIVE_CALL" -> setOf("kind", "connectorRef", "function", "arguments")
            "OPERATION" -> setOf("kind", "operator", "operands")
            "CASE" -> setOf("kind", "branches")
            "CAST" -> setOf("kind", "expression", "targetType", "rounding")
            "ROUND" -> setOf("kind", "expression", "scale", "rounding")
            "EXISTS" -> setOf("kind", "instanceId", "from", "accessPath", "predicate")
            else -> throw IllegalArgumentException("$label has an unsupported expression kind: $kind")
        }
        val optional = when (kind) { "CASE" -> setOf("otherwise"); "CAST" -> setOf("precision", "scale"); else -> emptySet() }
        fields(node, required, optional, label)
        return when (kind) {
            "EXISTS" -> Exists(text(node, "instanceId", label), parent(node.required("from"), "$label.from"),
                OlapJsonUtils.mapper().treeToValue(node.required("accessPath"), RelationAccessPath::class.java),
                parse(node.required("predicate"), "$label.predicate"))
            "LOGIC_UNIT" -> Reference(ReferenceKind.LOGIC_UNIT, text(node, "logicUnitId", label), positiveLong(node, "logicUnitVersion", label))
            "ROLE_LOGIC_UNIT" -> Reference(ReferenceKind.ROLE_LOGIC_UNIT, text(node, "logicUnitId", label), positiveLong(node, "logicUnitVersion", label), text(node, "role", label))
            "PHYSICAL_COLUMN" -> Reference(ReferenceKind.PHYSICAL_COLUMN, text(node, "physicalColumnId", label), positiveLong(node, "physicalColumnVersion", label))
            "LITERAL" -> Literal(literal(node["value"], label))
            "EXACT_NUMBER" -> Literal(BigDecimal(text(node, "value", label)))
            "CALL" -> Call(ScalarFunction.parse(text(node, "function", label)), expressions(node["arguments"], "$label.arguments"))
            "NATIVE_CALL" -> nativeCall(node, label)
            "OPERATION" -> Operation(enumValue<Operator>(text(node, "operator", label), "$label.operator"), expressions(node["operands"], "$label.operands"))
            "CASE" -> case(node, label)
            "ROUND" -> Round(parse(node["expression"], "$label.expression"), requireNotNull(integer(node, "scale", label)), enumValue<Rounding>(text(node, "rounding", label), "$label.rounding"))
            "CAST" -> Cast(parse(node["expression"], "$label.expression"), enumValue<ValueType>(text(node, "targetType", label), "$label.targetType"), integer(node, "precision", label), integer(node, "scale", label), enumValue<Rounding>(text(node, "rounding", label), "$label.rounding"))
            else -> error("unreachable expression kind")
        }
    }

    private fun parent(node: JsonNode, label: String): RelationParent = when (text(node, "kind", label)) {
        "LOCAL" -> { fields(node, setOf("kind"), emptySet(), label); LocalParent() }
        "ROLE" -> { fields(node, setOf("kind", "role"), emptySet(), label); RoleParent(text(node, "role", label)) }
        else -> throw IllegalArgumentException("$label has invalid quantified relation scope")
    }

    private fun nativeCall(node: JsonNode, label: String): NativeCall {
        val connector = node.required("connectorRef")
        fields(connector, setOf("id", "version", "sha256"), emptySet(), "$label.connectorRef")
        val arguments = node.required("arguments")
        require(arguments.isArray) { "native function arguments must be an array" }
        val values = arguments.mapIndexed { index, argument ->
            val location = "$label.arguments[$index]"
            when (text(argument, "kind", location)) {
                "SCALAR" -> {
                    fields(argument, setOf("kind", "expression"), emptySet(), location)
                    ScalarArgument(parse(argument.required("expression"), "$location.expression"))
                }
                "PHYSICAL_TABLE" -> {
                    fields(argument, setOf("kind", "physicalTableId", "physicalTableVersion"), emptySet(), location)
                    TableArgument(text(argument, "physicalTableId", location), positiveLong(argument, "physicalTableVersion", location))
                }
                else -> throw IllegalArgumentException("$location has invalid native function argument kind")
            }
        }
        return NativeCall(ConnectorRef(text(connector, "id", label), text(connector, "version", label), text(connector, "sha256", label)), text(node, "function", label), values)
    }

    private fun case(node: JsonNode, label: String): CaseWhen {
        val branches = node["branches"]
        require(branches != null && branches.isArray && !branches.isEmpty) { "$label.branches 不能为空" }
        val values = branches.mapIndexed { index, branch ->
            Branch(parse(branch["when"], "$label.branches[$index].when"), parse(branch["then"], "$label.branches[$index].then"))
        }
        return CaseWhen(values, node["otherwise"]?.takeUnless { it.isNull }?.let { parse(it, "$label.otherwise") })
    }

    private fun expressions(node: JsonNode?, label: String): List<SemanticExpression> {
        require(node != null && node.isArray && !node.isEmpty) { "$label 不能为空" }
        return node.mapIndexed { index, child -> parse(child, "$label[$index]") }
    }

    private fun literal(node: JsonNode?, label: String): Any = when {
        node == null || node.isNull -> throw IllegalArgumentException("$label.value 不能为空")
        node.isTextual -> node.textValue()
        node.isBoolean -> node.booleanValue()
        else -> throw IllegalArgumentException("$label.value 只支持 string/boolean；数值必须使用 EXACT_NUMBER 十进制文本")
    }

    fun toJsonNode(expression: SemanticExpression): ObjectNode = OlapJsonUtils.mapper().createObjectNode().apply {
        when (expression) {
            is Reference -> {
                put("kind", expression.referenceKind.name)
                val prefix = if (expression.referenceKind == ReferenceKind.PHYSICAL_COLUMN) "physicalColumn" else "logicUnit"
                put("${prefix}Id", expression.id)
                put("${prefix}Version", expression.version)
                expression.role?.let { put("role", it) }
            }
            is Literal -> when (val value = expression.value) {
                is Number -> { put("kind", "EXACT_NUMBER"); put("value", BigDecimal(value.toString()).toPlainString()) }
                is String -> { put("kind", "LITERAL"); put("value", value) }
                is Boolean -> { put("kind", "LITERAL"); put("value", value) }
                is LocalDate, is LocalDateTime -> { put("kind", "LITERAL"); put("value", value.toString()) }
                else -> throw IllegalArgumentException("literal value type 不支持: ${value.javaClass.name}")
            }
            is Call -> { put("kind", "CALL"); put("function", expression.function.name); set<ArrayNode>("arguments", array(expression.arguments)) }
            is NativeCall -> {
                put("kind", "NATIVE_CALL")
                set<JsonNode>("connectorRef", OlapJsonUtils.mapper().valueToTree(expression.connectorRef))
                put("function", expression.function)
                val values = putArray("arguments")
                expression.arguments.forEach { argument ->
                    values.addObject().apply {
                        when (argument) {
                            is ScalarArgument -> { put("kind", "SCALAR"); set<JsonNode>("expression", toJsonNode(argument.expression)) }
                            is TableArgument -> { put("kind", "PHYSICAL_TABLE"); put("physicalTableId", argument.physicalTableId); put("physicalTableVersion", argument.physicalTableVersion) }
                        }
                    }
                }
            }
            is Operation -> { put("kind", "OPERATION"); put("operator", expression.operator.name); set<ArrayNode>("operands", array(expression.operands)) }
            is CaseWhen -> {
                put("kind", "CASE")
                val branches = putArray("branches")
                expression.branches.forEach { branch -> branches.addObject().apply { set<JsonNode>("when", toJsonNode(branch.`when`)); set<JsonNode>("then", toJsonNode(branch.then)) } }
                expression.otherwise?.let { set<JsonNode>("otherwise", toJsonNode(it)) }
            }
            is Exists -> {
                put("kind", "EXISTS")
                put("instanceId", expression.instanceId)
                set<ObjectNode>("from", OlapJsonUtils.mapper().createObjectNode().apply {
                    when (val from = expression.from) { is LocalParent -> put("kind", "LOCAL"); is RoleParent -> { put("kind", "ROLE"); put("role", from.role) } }
                })
                set<JsonNode>("accessPath", OlapJsonUtils.mapper().valueToTree(expression.accessPath))
                set<JsonNode>("predicate", toJsonNode(expression.predicate))
            }
            is Round -> { put("kind", "ROUND"); set<JsonNode>("expression", toJsonNode(expression.expression)); put("scale", expression.scale); put("rounding", expression.rounding.name) }
            is Cast -> {
                put("kind", "CAST"); set<JsonNode>("expression", toJsonNode(expression.expression))
                put("targetType", expression.targetType.name); put("rounding", expression.rounding.name)
                expression.precision?.let { put("precision", it) }; expression.scale?.let { put("scale", it) }
            }
        }
    }

    private fun array(expressions: List<SemanticExpression>): ArrayNode = OlapJsonUtils.mapper().createArrayNode().apply { expressions.forEach { add(toJsonNode(it)) } }
    private fun fields(node: JsonNode, required: Set<String>, optional: Set<String>, label: String) {
        require(node.isObject) { "$label 必须是 JSON object" }
        val actual = node.fieldNames().asSequence().toSet()
        require(actual.containsAll(required) && (required + optional).containsAll(actual)) { "$label fields differ: missing=${required - actual}, unknown=${actual - required - optional}" }
    }
    private fun text(node: JsonNode, field: String, label: String): String {
        val value = node[field]
        require(value != null && value.isTextual && value.textValue().isNotBlank()) { "$label.$field 不能为空且必须为文本" }
        return value.textValue()
    }
    private fun integer(node: JsonNode, field: String, label: String): Int? {
        val value = node[field] ?: return null
        require(value.isIntegralNumber && value.canConvertToInt()) { "$label.$field 必须为整数" }
        return value.intValue()
    }
    private fun positiveLong(node: JsonNode, field: String, label: String): Long {
        val value = node[field]
        require(value != null && value.isIntegralNumber && value.canConvertToLong() && value.longValue() > 0) { "$label.$field 必须为正整数" }
        return value.longValue()
    }
    private inline fun <reified T : Enum<T>> enumValue(raw: String, label: String): T =
        try { enumValueOf<T>(raw.trim().uppercase(Locale.ROOT)) } catch (failure: IllegalArgumentException) { throw IllegalArgumentException("$label 不支持: $raw", failure) }

    /** 只描述语法；闭包、量词位置、类型与授权仍由每个使用入口决定。 */
    fun wireContract(referenceKind: ReferenceKind, allowNativeCalls: Boolean): Map<String, Any> {
        val text = mapOf("kind" to "string", "nonBlank" to true)
        val version = mapOf("kind" to "integer", "minimum" to 1)
        val expression = mapOf("kind" to "reference", "definition" to "expression")
        val expressions = mapOf("kind" to "array", "minimumItems" to 1, "element" to expression)
        val semantic = referenceKind == ReferenceKind.LOGIC_UNIT
        val prefix = if (semantic) "logicUnit" else "physicalColumn"
        val variants = mutableListOf<Map<String, Any>>(variant(referenceKind.name, mapOf("${prefix}Id" to text, "${prefix}Version" to version)))
        if (semantic) {
            variants += variant("ROLE_LOGIC_UNIT", mapOf("role" to text, "logicUnitId" to text, "logicUnitVersion" to version))
            variants += variant("EXISTS", mapOf("instanceId" to text,
                "from" to union(listOf(variant("LOCAL", emptyMap()), variant("ROLE", mapOf("role" to text)))),
                "accessPath" to mapOf("kind" to "object", "fields" to mapOf("joinPathRefs" to mapOf("kind" to "array", "minimumItems" to 1, "maximumItems" to 16,
                    "element" to mapOf("kind" to "object", "fields" to mapOf("joinPathId" to text, "joinPathVersion" to version))))),
                "predicate" to expression)) + mapOf(
                "scope" to "LOCAL_FIELDS_BELONG_TO_ONE_MATCHED_RELATED_ROW_OUTER_FIELDS_REQUIRE_DECLARED_ROLES",
                "population" to "ONLY_REAL_MATCHES_SQL_ON_TRUE_NO_GENERATED_MISSING_ROW",
                "result" to "NON_NULL_BOOLEAN_NOT_EXISTS_IS_EXPLICIT_NOT",
                "nesting" to "LOCAL_PARENT_IS_CURRENT_QUANTIFIED_POSITION_PATH_IS_RELATIVE_TO_EXPLICIT_PARENT")
        }
        variants += variant("LITERAL", mapOf("value" to mapOf("kind" to "scalar", "types" to listOf("string", "boolean"), "nullable" to false)))
        variants += variant("EXACT_NUMBER", mapOf("value" to text))
        variants += variant("CALL", mapOf("function" to mapOf("kind" to "enum", "values" to ScalarFunction.entries.map { it.name }), "arguments" to expressions)) +
            mapOf("functionContracts" to ScalarFunction.entries.map { it.contract() })
        if (allowNativeCalls) variants += variant("NATIVE_CALL", mapOf(
            "connectorRef" to mapOf("kind" to "object", "fields" to mapOf("id" to text, "version" to text, "sha256" to text)),
            "function" to text, "arguments" to mapOf("kind" to "array", "element" to union(listOf(
                variant("SCALAR", mapOf("expression" to expression)), variant("PHYSICAL_TABLE", mapOf("physicalTableId" to text, "physicalTableVersion" to version))))))) +
            mapOf("declarations" to "read-connector-capabilities.connectors[].nativeScalarFunctions")
        variants += variant("ROUND", mapOf("expression" to expression, "scale" to mapOf("kind" to "integer", "minimum" to -38, "maximum" to 38),
            "rounding" to mapOf("kind" to "enum", "values" to Rounding.entries.filter { it != Rounding.NONE }.map { it.name })))
        variants += variant("OPERATION", mapOf("operator" to mapOf("kind" to "enum", "values" to Operator.entries.map { it.name }), "operands" to expressions))
        variants += variant("CASE", mapOf("branches" to mapOf("kind" to "array", "minimumItems" to 1, "element" to mapOf("kind" to "object", "fields" to mapOf("when" to expression, "then" to expression))),
            "otherwise" to (expression + mapOf("optional" to true, "nullable" to true))))
        val capacity = mapOf("kind" to "integer", "minimum" to 0, "optional" to true, "requiredForTargetTypes" to listOf("DECIMAL"))
        variants += variant("CAST", mapOf("expression" to expression, "rounding" to mapOf("kind" to "enum", "values" to Rounding.entries.map { it.name }),
            "targetType" to mapOf("kind" to "enum", "values" to ValueType.entries.map { it.name }), "precision" to capacity, "scale" to capacity))
        return mapOf("kind" to "string", "encoding" to "json", "rootDefinition" to "expression", "allowedReferenceKind" to referenceKind.name,
            "definitions" to mapOf("expression" to union(variants)),
            "validationBoundary" to "Syntax does not authorize references, paths, functions or types; each using input owns its relationship scopes.")
    }
    private fun variant(kind: String, fields: Map<String, Any>): Map<String, Any> = mapOf("kindValue" to kind, "fields" to fields)
    private fun union(variants: List<Map<String, Any>>): Map<String, Any> = mapOf("kind" to "discriminatedUnion", "discriminator" to "kind", "variants" to variants)
}
