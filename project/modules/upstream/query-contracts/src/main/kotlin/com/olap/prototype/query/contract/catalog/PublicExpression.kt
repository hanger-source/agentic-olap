package com.olap.prototype.query.contract.catalog

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import com.olap.prototype.connector.ConnectorRef
import com.olap.prototype.expression.SemanticExpression
import com.olap.prototype.metric.MetricValueContract.Rounding
import com.olap.prototype.query.ScopedInputContract
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/** 去除物理列与执行绑定；表参数仅保留精确采用坐标，量词保留实际公开关联。 */
@JsonSerialize(using = PublicExpression.Serializer::class)
@JsonDeserialize(using = PublicExpression.Deserializer::class)
sealed interface PublicExpression {
    data class Reference(val id: String, val version: Long, val role: String?) : PublicExpression {
        init { CatalogJson.text(id); CatalogJson.version(version); role?.let { ScopedInputContract.requireId(it) } }
    }
    data class TextLiteral(val value: String) : PublicExpression
    data class BooleanLiteral(val value: Boolean) : PublicExpression
    data class ExactNumber(val value: String) : PublicExpression { init { BigDecimal(CatalogJson.text(value)) } }
    class Call(val function: String, arguments: List<PublicExpression>) : PublicExpression {
        val arguments: List<PublicExpression> = CatalogJson.nonempty(arguments)
        init { CatalogJson.text(function) }
        override fun equals(other: Any?): Boolean = other is Call && function == other.function && arguments == other.arguments
        override fun hashCode(): Int = listOf(function, arguments).hashCode()
    }
    sealed interface NativeArgument
    data class ScalarArgument(val expression: PublicExpression) : NativeArgument
    data class TableArgument(val physicalTableId: String, val physicalTableVersion: Long) : NativeArgument {
        init { CatalogJson.text(physicalTableId); CatalogJson.version(physicalTableVersion) }
    }
    class NativeCall(val connectorRef: ConnectorRef, val function: String, arguments: List<NativeArgument>) : PublicExpression {
        val arguments: List<NativeArgument> = java.util.List.copyOf(arguments)
        init { ScopedInputContract.requireId(function) }
        override fun equals(other: Any?): Boolean = other is NativeCall && connectorRef == other.connectorRef && function == other.function && arguments == other.arguments
        override fun hashCode(): Int = listOf(connectorRef, function, arguments).hashCode()
    }
    class Operation(val operator: SemanticExpression.Operator, operands: List<PublicExpression>) : PublicExpression {
        val operands: List<PublicExpression> = CatalogJson.nonempty(operands)
        override fun equals(other: Any?): Boolean = other is Operation && operator == other.operator && operands == other.operands
        override fun hashCode(): Int = listOf(operator, operands).hashCode()
    }
    data class Branch(@param:JsonProperty(required = true) val `when`: PublicExpression, @param:JsonProperty(required = true) val then: PublicExpression)
    class CaseWhen(branches: List<Branch>, val otherwise: CatalogOptional<PublicExpression>) : PublicExpression {
        val branches: List<Branch> = CatalogJson.nonempty(branches)
        override fun equals(other: Any?): Boolean = other is CaseWhen && branches == other.branches && otherwise == other.otherwise
        override fun hashCode(): Int = listOf(branches, otherwise).hashCode()
    }
    data class Round(val expression: PublicExpression, val scale: Int, val rounding: Rounding) : PublicExpression {
        init { require(scale in -38..38 && rounding != Rounding.NONE) { "invalid published rounding" } }
    }
    data class Cast(val expression: PublicExpression, val targetType: SemanticExpression.ValueType, val precision: CatalogOptional<Int>,
        val scale: CatalogOptional<Int>, val rounding: Rounding) : PublicExpression {
        init { SemanticExpression.Cast.validateTarget(targetType, precision.value, scale.value) }
    }
    class Exists(val instanceId: String, val from: SemanticExpression.RelationParent, access: List<SemanticJoin>, val predicate: PublicExpression) : PublicExpression {
        val access: List<SemanticJoin> = CatalogJson.nonempty(access)
        init { ScopedInputContract.requireId(instanceId) }
        override fun equals(other: Any?): Boolean = other is Exists && instanceId == other.instanceId && from == other.from && access == other.access && predicate == other.predicate
        override fun hashCode(): Int = listOf(instanceId, from, access, predicate).hashCode()
    }

    fun references(roles: Set<String>, references: MutableSet<String>) {
        when (this) {
            is Reference -> { require(role == null || role in roles) { "published expression uses an undeclared role" }; references.add("$id@$version") }
            is Call -> arguments.forEach { it.references(roles, references) }
            is NativeCall -> arguments.filterIsInstance<ScalarArgument>().forEach { it.expression.references(roles, references) }
            is Operation -> operands.forEach { it.references(roles, references) }
            is CaseWhen -> { branches.forEach { it.`when`.references(roles, references); it.then.references(roles, references) }; otherwise.value?.references(roles, references) }
            is Round -> expression.references(roles, references)
            is Cast -> expression.references(roles, references)
            is Exists -> {
                val parent = from as? SemanticExpression.RoleParent
                require(parent == null || parent.role in roles) { "published quantifier parent role is undeclared" }
                access.forEach { it.references(references) }; predicate.references(roles, references)
            }
            is TextLiteral, is BooleanLiteral, is ExactNumber -> kotlin.Unit
        }
    }

    companion object {
        fun project(value: SemanticExpression, quantified: (SemanticExpression.Exists) -> Exists): PublicExpression = when (value) {
            is SemanticExpression.Reference -> {
                require(value.referenceKind != SemanticExpression.ReferenceKind.PHYSICAL_COLUMN) { "published expression cannot expose physical references" }
                Reference(value.id, value.version, value.role)
            }
            is SemanticExpression.Literal -> when (val literal = value.value) {
                is String -> TextLiteral(literal)
                is Boolean -> BooleanLiteral(literal)
                is Number -> ExactNumber(BigDecimal(literal.toString()).toPlainString())
                is LocalDate -> TextLiteral(literal.toString())
                is LocalDateTime -> TextLiteral(literal.toString())
                else -> throw IllegalArgumentException("unsupported published literal")
            }
            is SemanticExpression.Call -> Call(value.function.name, value.arguments.map { project(it, quantified) })
            is SemanticExpression.NativeCall -> NativeCall(value.connectorRef, value.function, value.arguments.map { argument -> when (argument) {
                is SemanticExpression.ScalarArgument -> ScalarArgument(project(argument.expression, quantified))
                is SemanticExpression.TableArgument -> TableArgument(argument.physicalTableId, argument.physicalTableVersion)
            } })
            is SemanticExpression.Operation -> Operation(value.operator, value.operands.map { project(it, quantified) })
            is SemanticExpression.CaseWhen -> CaseWhen(value.branches.map { Branch(project(it.`when`, quantified), project(it.then, quantified)) },
                value.otherwise?.let { CatalogOptional.of(project(it, quantified)) } ?: CatalogOptional.absent())
            is SemanticExpression.Round -> Round(project(value.expression, quantified), value.scale, value.rounding)
            is SemanticExpression.Cast -> Cast(project(value.expression, quantified), value.targetType,
                value.precision?.let { CatalogOptional.of(it) } ?: CatalogOptional.absent(), value.scale?.let { CatalogOptional.of(it) } ?: CatalogOptional.absent(), value.rounding)
            is SemanticExpression.Exists -> quantified(value)
        }

        fun read(node: JsonNode?, depth: Int): PublicExpression {
            require(depth <= 64 && node != null && node.isObject && node.path("kind").isTextual) { "bounded published expression object required" }
            val kind = node["kind"].textValue()
            val required = when (kind) {
                "LOGIC_UNIT" -> setOf("kind", "logicUnitId", "logicUnitVersion")
                "ROLE_LOGIC_UNIT" -> setOf("kind", "logicUnitId", "logicUnitVersion", "role")
                "LITERAL", "EXACT_NUMBER" -> setOf("kind", "value")
                "CALL" -> setOf("kind", "function", "arguments")
                "NATIVE_CALL" -> setOf("kind", "connectorRef", "function", "arguments")
                "OPERATION" -> setOf("kind", "operator", "operands")
                "CASE" -> setOf("kind", "branches")
                "ROUND" -> setOf("kind", "expression", "scale", "rounding")
                "CAST" -> setOf("kind", "expression", "targetType", "rounding")
                "EXISTS" -> setOf("kind", "instanceId", "from", "access", "predicate")
                else -> throw IllegalArgumentException("unsupported public expression kind: $kind")
            }
            val allowed = required + when (kind) { "CASE" -> setOf("otherwise"); "CAST" -> setOf("precision", "scale"); else -> emptySet() }
            CatalogJson.fields(node, required, allowed)
            return when (kind) {
                "LOGIC_UNIT", "ROLE_LOGIC_UNIT" -> Reference(CatalogJson.required(node, "logicUnitId", String::class.java),
                    CatalogJson.required(node, "logicUnitVersion", Long::class.javaObjectType), if (kind == "ROLE_LOGIC_UNIT") CatalogJson.required(node, "role", String::class.java) else null)
                "LITERAL" -> when { node["value"].isTextual -> TextLiteral(node["value"].textValue()); node["value"].isBoolean -> BooleanLiteral(node["value"].booleanValue()); else -> throw IllegalArgumentException("published literal must be text or boolean") }
                "EXACT_NUMBER" -> ExactNumber(CatalogJson.required(node, "value", String::class.java))
                "CALL" -> Call(CatalogJson.required(node, "function", String::class.java), expressions(node["arguments"], depth))
                "NATIVE_CALL" -> NativeCall(CatalogJson.required(node, "connectorRef", ConnectorRef::class.java), CatalogJson.required(node, "function", String::class.java), nativeArguments(node["arguments"], depth))
                "OPERATION" -> Operation(CatalogJson.required(node, "operator", SemanticExpression.Operator::class.java), expressions(node["operands"], depth))
                "CASE" -> {
                    val branches = node["branches"]
                    require(branches.isArray) { "published branches required" }
                    CaseWhen(branches.map { branch ->
                        CatalogJson.fields(branch, setOf("when", "then"), setOf("when", "then"))
                        Branch(read(branch["when"], depth + 1), read(branch["then"], depth + 1))
                    }, if (!node.has("otherwise")) CatalogOptional.absent() else CatalogOptional.of(if (node["otherwise"].isNull) null else read(node["otherwise"], depth + 1)))
                }
                "ROUND" -> Round(read(node["expression"], depth + 1), CatalogJson.required(node, "scale", Int::class.javaObjectType), CatalogJson.required(node, "rounding", Rounding::class.java))
                "CAST" -> Cast(read(node["expression"], depth + 1), CatalogJson.required(node, "targetType", SemanticExpression.ValueType::class.java), integer(node, "precision"), integer(node, "scale"), CatalogJson.required(node, "rounding", Rounding::class.java))
                "EXISTS" -> Exists(CatalogJson.required(node, "instanceId", String::class.java), CatalogJson.required(node, "from", SemanticExpression.RelationParent::class.java), CatalogJson.list(node["access"], SemanticJoin::class.java), read(node["predicate"], depth + 1))
                else -> error("validated public expression kind is missing")
            }
        }
        private fun integer(node: JsonNode, name: String): CatalogOptional<Int> = if (node.has(name)) CatalogOptional.of(CatalogJson.read(node[name], Int::class.javaObjectType)) else CatalogOptional.absent()
        private fun expressions(node: JsonNode?, depth: Int): List<PublicExpression> {
            require(node != null && node.isArray) { "published expression list required" }
            return node.map { read(it, depth + 1) }
        }
        private fun nativeArguments(node: JsonNode?, depth: Int): List<NativeArgument> {
            require(node != null && node.isArray) { "published native argument list required" }
            return node.map { argument -> when (val kind = CatalogJson.required(argument, "kind", String::class.java)) {
                "SCALAR" -> { CatalogJson.fields(argument, setOf("kind", "expression"), setOf("kind", "expression")); ScalarArgument(read(argument["expression"], depth + 1)) }
                "PHYSICAL_TABLE" -> {
                    val fields = setOf("kind", "physicalTableId", "physicalTableVersion")
                    CatalogJson.fields(argument, fields, fields)
                    TableArgument(CatalogJson.required(argument, "physicalTableId", String::class.java), CatalogJson.required(argument, "physicalTableVersion", Long::class.javaObjectType))
                }
                else -> throw IllegalArgumentException("unsupported public native argument kind: $kind")
            } }
        }
    }

    class Deserializer : JsonDeserializer<PublicExpression>() {
        override fun deserialize(input: JsonParser, context: DeserializationContext): PublicExpression = read(input.codec.readTree(input), 0)
    }
    class Serializer : JsonSerializer<PublicExpression>() {
        override fun serialize(value: PublicExpression, output: JsonGenerator, provider: SerializerProvider) {
            output.writeStartObject()
            when (value) {
                is Reference -> { output.writeStringField("kind", if (value.role == null) "LOGIC_UNIT" else "ROLE_LOGIC_UNIT"); output.writeStringField("logicUnitId", value.id); output.writeNumberField("logicUnitVersion", value.version); value.role?.let { output.writeStringField("role", it) } }
                is TextLiteral -> { output.writeStringField("kind", "LITERAL"); output.writeStringField("value", value.value) }
                is BooleanLiteral -> { output.writeStringField("kind", "LITERAL"); output.writeBooleanField("value", value.value) }
                is ExactNumber -> { output.writeStringField("kind", "EXACT_NUMBER"); output.writeStringField("value", value.value) }
                is Call -> { output.writeStringField("kind", "CALL"); output.writeStringField("function", value.function); output.writeObjectField("arguments", value.arguments) }
                is NativeCall -> {
                    output.writeStringField("kind", "NATIVE_CALL"); output.writeObjectField("connectorRef", value.connectorRef); output.writeStringField("function", value.function)
                    output.writeArrayFieldStart("arguments")
                    value.arguments.forEach { argument ->
                        output.writeStartObject()
                        when (argument) {
                            is ScalarArgument -> { output.writeStringField("kind", "SCALAR"); output.writeObjectField("expression", argument.expression) }
                            is TableArgument -> { output.writeStringField("kind", "PHYSICAL_TABLE"); output.writeStringField("physicalTableId", argument.physicalTableId); output.writeNumberField("physicalTableVersion", argument.physicalTableVersion) }
                        }
                        output.writeEndObject()
                    }
                    output.writeEndArray()
                }
                is Operation -> { output.writeStringField("kind", "OPERATION"); output.writeStringField("operator", value.operator.name); output.writeObjectField("operands", value.operands) }
                is CaseWhen -> { output.writeStringField("kind", "CASE"); output.writeObjectField("branches", value.branches); if (value.otherwise.present) output.writeObjectField("otherwise", value.otherwise.value) }
                is Round -> { output.writeStringField("kind", "ROUND"); output.writeObjectField("expression", value.expression); output.writeNumberField("scale", value.scale); output.writeStringField("rounding", value.rounding.name) }
                is Cast -> {
                    output.writeStringField("kind", "CAST"); output.writeObjectField("expression", value.expression); output.writeStringField("targetType", value.targetType.name); output.writeStringField("rounding", value.rounding.name)
                    if (value.precision.present) output.writeObjectField("precision", value.precision.value)
                    if (value.scale.present) output.writeObjectField("scale", value.scale.value)
                }
                is Exists -> {
                    output.writeStringField("kind", "EXISTS"); output.writeStringField("instanceId", value.instanceId)
                    output.writeObjectFieldStart("from")
                    when (val from = value.from) {
                        is SemanticExpression.LocalParent -> output.writeStringField("kind", "LOCAL")
                        is SemanticExpression.RoleParent -> { output.writeStringField("kind", "ROLE"); output.writeStringField("role", from.role) }
                    }
                    output.writeEndObject()
                    output.writeObjectField("access", value.access); output.writeObjectField("predicate", value.predicate)
                }
            }
            output.writeEndObject()
        }
    }
}
