package com.olap.prototype.query

import com.olap.prototype.calculation.Calculation
import com.olap.prototype.contract.ProducerContracts.requireExactFields
import com.olap.prototype.expression.SemanticExpression
import com.olap.prototype.expression.ScalarFunction
import com.olap.prototype.metric.MetricValueContract
import com.olap.prototype.query.ResultExpression.*
import com.olap.prototype.util.OlapJsonUtils
import java.math.BigDecimal
import java.math.BigInteger

/** 递归表达式的唯一 wire 边界；数值文字不经过二进制浮点。 */
object ResultExpressionCodec {
    fun write(expression: ResultExpression): Map<String, Any?> = when (expression) {
        is ResultCall -> wire("CALL", "function" to expression.function, "arguments" to expression.arguments.map(::write))
        is ResultArgument -> wire("ARGUMENT", "name" to expression.name, "valueType" to expression.valueType)
        is ResultSymbol -> wire("SYMBOL", "symbol" to expression.symbol)
        is ResultLiteral -> (expression.value as? BigDecimal)?.let { wire("EXACT_NUMBER", "value" to it.toPlainString()) }
            ?: wire("LITERAL", "value" to expression.value)
        is ResultTypedLiteral -> wire("TYPED_LITERAL", "valueType" to expression.valueType, "value" to decimalText(expression.value))
        is ResultQuantityLiteral -> wire("QUANTITY_LITERAL", "value" to expression.value.toPlainString(), "valueContract" to expression.valueContract)
        is ResultOperation -> wire("OPERATION", "operator" to expression.operator, "operands" to expression.operands.map(::write))
        is ResultRow -> wire("ROW", "operands" to expression.operands.map(::write))
        is ResultCaseWhen -> wire("CASE", "branches" to expression.branches.map { mapOf("when" to write(it.`when`), "then" to write(it.then)) }, "otherwise" to write(expression.otherwise))
        is ResultCast -> wire("CAST", "expression" to write(expression.expression), "targetType" to expression.targetType, "precision" to expression.precision,
            "scale" to expression.scale, "rounding" to expression.rounding)
        is ResultNumeric -> wire("NUMERIC", "expression" to write(expression.expression), "valueContract" to expression.valueContract, "unitEquation" to expression.unitEquation)
        is ResultRound -> wire("ROUND", "expression" to write(expression.expression), "scale" to expression.scale, "rounding" to expression.rounding)
        is ResultCollectionSort -> wire("COLLECTION_SORT", "expression" to write(expression.expression), "direction" to expression.direction, "nulls" to expression.nulls)
        is ResultCollectionText -> wire("COLLECTION_TEXT", "expression" to write(expression.expression), "delimiter" to expression.delimiter, "nulls" to expression.nulls, "nullText" to expression.nullText)
        is ResultRowField -> wire("ROW_FIELD", "expression" to write(expression.expression), "index" to expression.index)
        is ResultWeightedContribution -> wire("WEIGHTED_CONTRIBUTION", "value" to write(expression.value), "weight" to write(expression.weight),
            "observationUnit" to expression.observationUnit, "component" to expression.component, "valueContract" to expression.valueContract, "unitEquation" to expression.unitEquation)
        is ResultUniformAllocation -> wire("UNIFORM_ALLOCATION", "value" to write(expression.value), "sourceKeys" to expression.sourceKeys,
            "memberKeys" to expression.memberKeys, "valueContract" to expression.valueContract, "unitEquation" to expression.unitEquation)
    }
    private fun wire(kind: String, vararg fields: Pair<String, Any?>): Map<String, Any?> = linkedMapOf("kind" to kind, *fields)
    private fun decimalText(value: Any?): Any? = if (value is BigDecimal) value.toPlainString() else value
    private fun text(value: Any?, field: String): String = requireNotNull((value as? String)?.takeUnless(String::isBlank)) { "$field is required" }
    private fun objectFields(raw: Any?, field: String): Map<String, Any?> {
        require(raw is Map<*, *>) { "$field must be an object" }
        return raw.entries.associateTo(linkedMapOf()) { (key, value) ->
            require(key is String) { "$field keys must be text" }; key to value
        }
    }
    fun read(raw: Any?, field: String): ResultExpression {
        val fields = objectFields(raw, field)
        val kind = text(fields["kind"], "$field.kind")
        return when (kind) {
            "ARGUMENT", "SYMBOL", "LITERAL", "EXACT_NUMBER", "TYPED_LITERAL" -> literal(kind, fields, field)
            "QUANTITY_LITERAL", "NUMERIC", "WEIGHTED_CONTRIBUTION", "UNIFORM_ALLOCATION" -> quantity(kind, fields, field)
            "ROW_FIELD", "COLLECTION_SORT", "COLLECTION_TEXT" -> collection(kind, fields, field)
            "CALL", "ROW", "CASE", "OPERATION" -> compound(kind, fields, field)
            "ROUND", "CAST" -> conversion(kind, fields, field)
            else -> throw IllegalArgumentException("$field.kind is unsupported: $kind")
        }
    }
    private fun literal(kind: String, fields: Map<String, Any?>, field: String): ResultExpression = when (kind) {
        "ARGUMENT" -> {
            requireExactFields(fields, setOf("kind", "name", "valueType"), field)
            ResultArgument(text(fields["name"], "$field.name"), ScalarValueType.valueOf(text(fields["valueType"], "$field.valueType")))
        }
        "SYMBOL" -> {
            requireExactFields(fields, setOf("kind", "symbol"), field)
            ResultSymbol(ScopedInputContract.requireInputSymbol(fields["symbol"], "$field.symbol"))
        }
        "LITERAL" -> {
            requireExactFields(fields, setOf("kind", "value"), field)
            val value = fields["value"]
            require(value == null || value is Boolean) { "$field.value must be boolean or explicit null; numbers require EXACT_NUMBER decimal text" }
            ResultLiteral(value)
        }
        "EXACT_NUMBER" -> {
            requireExactFields(fields, setOf("kind", "value"), field)
            ResultLiteral(decimal(fields["value"], "$field.value"))
        }
        "TYPED_LITERAL" -> {
            requireExactFields(fields, setOf("kind", "valueType", "value"), field)
            val type = ScalarValueType.valueOf(text(fields["valueType"], "$field.valueType"))
            require(type in setOf(ScalarValueType.TEXT, ScalarValueType.NUMBER, ScalarValueType.DATE, ScalarValueType.LOCAL_TIMESTAMP, ScalarValueType.OFFSET_TIMESTAMP)) {
                "$field.valueType must be TEXT, categorical NUMBER, DATE, LOCAL_TIMESTAMP or OFFSET_TIMESTAMP"
            }
            val value = if (type == ScalarValueType.NUMBER) decimal(fields["value"], "$field.value").stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it } else fields["value"]
            ResultTypedLiteral(type, value)
        }
        else -> error("unsupported literal kind: $kind")
    }
    private fun quantity(kind: String, fields: Map<String, Any?>, field: String): ResultExpression = when (kind) {
        "QUANTITY_LITERAL" -> {
            requireExactFields(fields, setOf("kind", "value", "valueContract"), field)
            ResultQuantityLiteral(decimal(fields["value"], "$field.value"), requireValueContract(fields["valueContract"]))
        }
        "NUMERIC" -> {
            requireExactFields(fields, setOf("kind", "expression", "valueContract", "unitEquation"), field)
            ResultNumeric(read(fields["expression"], "$field.expression"), requireValueContract(fields["valueContract"]), equation(fields["unitEquation"], field))
        }
        "WEIGHTED_CONTRIBUTION" -> {
            requireExactFields(fields, setOf("kind", "value", "weight", "observationUnit", "component", "valueContract", "unitEquation"), field)
            ResultWeightedContribution(read(fields["value"], "$field.value"), read(fields["weight"], "$field.weight"),
                OlapJsonUtils.mapper().convertValue(fields["observationUnit"], Calculation.ObservationUnit::class.java),
                WeightedComponent.valueOf(text(fields["component"], "$field.component")), requireValueContract(fields["valueContract"]), equation(fields["unitEquation"], field))
        }
        "UNIFORM_ALLOCATION" -> {
            requireExactFields(fields, setOf("kind", "value", "sourceKeys", "memberKeys", "valueContract", "unitEquation"), field)
            ResultUniformAllocation(read(fields["value"], "$field.value"), ScopedInputContract.requireInputSymbols(fields["sourceKeys"], "$field.sourceKeys"),
                ScopedInputContract.requireInputSymbols(fields["memberKeys"], "$field.memberKeys"), requireValueContract(fields["valueContract"]), equation(fields["unitEquation"], field))
        }
        else -> error("unsupported quantity kind: $kind")
    }
    private fun equation(raw: Any?, field: String): String? = raw?.let { ScopedInputContract.requireId(text(it, "$field.unitEquation")) }
    private fun collection(kind: String, fields: Map<String, Any?>, field: String): ResultExpression = when (kind) {
        "ROW_FIELD" -> {
            requireExactFields(fields, setOf("kind", "expression", "index"), field)
            ResultRowField(read(fields["expression"], "$field.expression"), requireNotNull(integer(fields["index"], "$field.index")) { "$field requires a zero-based index" })
        }
        "COLLECTION_SORT" -> {
            requireExactFields(fields, setOf("kind", "expression", "direction", "nulls"), field)
            ResultCollectionSort(read(fields["expression"], "$field.expression"), text(fields["direction"], "$field.direction"), text(fields["nulls"], "$field.nulls"))
        }
        "COLLECTION_TEXT" -> {
            requireExactFields(fields, setOf("kind", "expression", "delimiter", "nulls", "nullText"), field)
            val delimiter = fields["delimiter"]
            val nullText = fields["nullText"]
            require(delimiter is String && (nullText == null || nullText is String)) { "$field text separators must be strings" }
            ResultCollectionText(read(fields["expression"], "$field.expression"), delimiter, text(fields["nulls"], "$field.nulls"), nullText as String?)
        }
        else -> error("unsupported collection kind: $kind")
    }
    private fun compound(kind: String, fields: Map<String, Any?>, field: String): ResultExpression = when (kind) {
        "CALL" -> {
            requireExactFields(fields, setOf("kind", "function", "arguments"), field)
            ResultCall(ScalarFunction.parse(text(fields["function"], "$field.function")),
                array(fields["arguments"], "$field.arguments", true).mapIndexed { index, value -> read(value, "$field.arguments[$index]") })
        }
        "ROW" -> {
            requireExactFields(fields, setOf("kind", "operands"), field)
            ResultRow(array(fields["operands"], "$field.operands", true).mapIndexed { index, value -> read(value, "$field.operands[$index]") })
        }
        "CASE" -> {
            requireExactFields(fields, setOf("kind", "branches", "otherwise"), field)
            val branches = array(fields["branches"], "$field.branches", true).mapIndexed { index, value ->
                val location = "$field.branches[$index]"
                val branch = objectFields(value, location)
                requireExactFields(branch, setOf("when", "then"), location)
                ResultCaseBranch(read(branch["when"], "$location.when"), read(branch["then"], "$location.then"))
            }
            ResultCaseWhen(branches, read(fields["otherwise"], "$field.otherwise"))
        }
        "OPERATION" -> {
            requireExactFields(fields, setOf("kind", "operator", "operands"), field)
            val operator = try { ResultOperator.valueOf(text(fields["operator"], "$field.operator")) }
                catch (failure: IllegalArgumentException) { throw IllegalArgumentException("$field.operator is unsupported", failure) }
            val values = array(fields["operands"], "$field.operands", false)
            require(values.size == operator.arity) { "$field.operands must contain ${operator.arity} values for $operator" }
            ResultOperation(operator, values.mapIndexed { index, value -> read(value, "$field.operands[$index]") })
        }
        else -> error("unsupported compound kind: $kind")
    }
    private fun array(raw: Any?, field: String, nonEmpty: Boolean): List<*> {
        require(raw is List<*> && (!nonEmpty || raw.isNotEmpty())) { "$field must be ${if (nonEmpty) "a non-empty" else "an"} array" }
        return raw
    }
    private fun conversion(kind: String, fields: Map<String, Any?>, field: String): ResultExpression = when (kind) {
        "ROUND" -> {
            requireExactFields(fields, setOf("kind", "expression", "scale", "rounding"), field)
            ResultRound(read(fields["expression"], "$field.expression"), requireNotNull(integer(fields["scale"], "$field.scale")) { "$field.scale is required" },
                MetricValueContract.Rounding.valueOf(text(fields["rounding"], "$field.rounding")))
        }
        "CAST" -> {
            requireExactFields(fields, setOf("kind", "expression", "targetType", "precision", "scale", "rounding"), field)
            ResultCast(read(fields["expression"], "$field.expression"), SemanticExpression.ValueType.valueOf(text(fields["targetType"], "$field.targetType")),
                integer(fields["precision"], "$field.precision"), integer(fields["scale"], "$field.scale"), MetricValueContract.Rounding.valueOf(text(fields["rounding"], "$field.rounding")))
        }
        else -> error("unsupported conversion kind: $kind")
    }
    private fun integer(raw: Any?, field: String): Int? = when (raw) {
        null -> null
        is Int -> raw
        is Long -> Math.toIntExact(raw)
        is BigInteger -> raw.intValueExact()
        else -> throw IllegalArgumentException("$field must be an integer or explicit null")
    }
    private fun decimal(raw: Any?, field: String): BigDecimal = try { BigDecimal(text(raw, field)) }
        catch (failure: NumberFormatException) { throw IllegalArgumentException("$field must be exact decimal text", failure) }
    fun requireValueContract(raw: Any?): MetricValueContract {
        if (raw is MetricValueContract) return raw
        require(raw is Map<*, *>) { "numeric value contract must be an explicit object" }
        return OlapJsonUtils.mapper().convertValue(raw, MetricValueContract::class.java)
    }
}
