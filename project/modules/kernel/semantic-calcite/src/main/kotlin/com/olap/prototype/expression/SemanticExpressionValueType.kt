package com.olap.prototype.expression

import com.olap.prototype.query.ScalarValueType
import org.apache.calcite.config.CalciteConnectionConfigImpl
import org.apache.calcite.jdbc.CalciteSchema
import org.apache.calcite.rel.type.RelDataType
import org.apache.calcite.rel.type.RelDataTypeFactory
import org.apache.calcite.sql.*
import org.apache.calcite.sql.`fun`.SqlCase
import org.apache.calcite.sql.`fun`.SqlStdOperatorTable as Ops
import org.apache.calcite.sql.parser.SqlParserPos
import org.apache.calcite.sql.type.SqlTypeFamily
import org.apache.calcite.sql.type.SqlTypeName
import org.apache.calcite.sql.type.SqlTypeUtil
import org.apache.calcite.sql.validate.SqlValidator
import org.apache.calcite.sql.validate.SqlValidatorUtil
import java.math.BigDecimal
import java.util.Properties

/** 以Calcite验证规范AST；只构造类型占位，不解析业务SQL或访问物理源。 */
object SemanticExpressionValueType {
    private val pos = SqlParserPos.ZERO

    /** 用实际原生类型验证共同函数；占位AST不读取来源，也不执行表达式。 */
    fun requireFunction(function: ScalarFunction, arguments: List<RelDataType>, factory: RelDataTypeFactory) {
        val operands: List<SqlNode> = arguments.map { type ->
            if (type.sqlTypeName == SqlTypeName.NULL) SqlLiteral.createNull(pos)
            else Ops.CAST.createCall(pos, SqlLiteral.createNull(pos), SqlTypeUtil.convertTypeToSpec(type))
        }
        val call = SemanticCalciteOperators.sqlScalarFunction(function, operands)
        val catalog = org.apache.calcite.prepare.CalciteCatalogReader(CalciteSchema.createRootSchema(false), emptyList(), factory, CalciteConnectionConfigImpl(Properties()))
        val validator = SqlValidatorUtil.newValidator(Ops.instance(), catalog, factory, SqlValidator.Config.DEFAULT.withTypeCoercionEnabled(false))
        validator.validate(SqlSelect(pos, SqlNodeList.EMPTY, SqlNodeList(listOf(call), pos),
            null, null, null, null, SqlNodeList.EMPTY, null, null, null, null, SqlNodeList.EMPTY))
    }

    fun resolve(expression: SemanticExpression, references: (SemanticExpression.Reference) -> ScalarValueType,
        nativeFunctions: (SemanticExpression.NativeCall) -> NativeScalarFunction): ScalarValueType {
        if (expression is SemanticExpression.Reference) return references(expression)
        val factory = SemanticCalciteTypes(SemanticCalciteTypeSystem())
        val catalog = org.apache.calcite.prepare.CalciteCatalogReader(CalciteSchema.createRootSchema(false), emptyList(), factory, CalciteConnectionConfigImpl(Properties()))
        val validator = SqlValidatorUtil.newValidator(Ops.instance(), catalog, factory, SqlValidator.Config.DEFAULT.withTypeCoercionEnabled(false))
        val select = SqlSelect(pos, SqlNodeList.EMPTY, SqlNodeList(listOf(sql(expression, references, nativeFunctions)), pos),
            null, null, null, null, SqlNodeList.EMPTY, null, null, null, null, SqlNodeList.EMPTY)
        val checked = validator.validate(select)
        return scalarType(validator.getValidatedNodeType(checked).fieldList.first().type.sqlTypeName)
    }

    fun scalarType(type: SqlTypeName): ScalarValueType = when (type.family) {
        SqlTypeFamily.NUMERIC -> ScalarValueType.NUMBER
        SqlTypeFamily.CHARACTER -> ScalarValueType.TEXT
        else -> when (type) {
            SqlTypeName.BOOLEAN -> ScalarValueType.BOOLEAN
            SqlTypeName.DATE -> ScalarValueType.DATE
            SqlTypeName.TIMESTAMP -> ScalarValueType.LOCAL_TIMESTAMP
            SqlTypeName.TIMESTAMP_TZ -> ScalarValueType.OFFSET_TIMESTAMP
            else -> throw IllegalArgumentException("Calcite result has no published scalar type: $type")
        }
    }

    private fun sql(expression: SemanticExpression, references: (SemanticExpression.Reference) -> ScalarValueType,
        nativeFunctions: (SemanticExpression.NativeCall) -> NativeScalarFunction): SqlNode {
        fun recurse(value: SemanticExpression): SqlNode = sql(value, references, nativeFunctions)
        return when (expression) {
            is SemanticExpression.Exists -> {
                val query = SqlSelect(pos, null, SqlNodeList(listOf(SqlLiteral.createExactNumeric("1", pos)), pos),
                    null, recurse(expression.predicate), null, null, null, null, null, null, null, null)
                Ops.EXISTS.createCall(pos, query)
            }
            is SemanticExpression.Reference -> {
                val type = when (references(expression)) {
                    ScalarValueType.TEXT, ScalarValueType.ENUM -> SqlTypeName.VARCHAR
                    ScalarValueType.NUMBER -> SqlTypeName.DECIMAL
                    ScalarValueType.BOOLEAN -> SqlTypeName.BOOLEAN
                    ScalarValueType.DATE -> SqlTypeName.DATE
                    ScalarValueType.LOCAL_TIMESTAMP -> SqlTypeName.TIMESTAMP
                    ScalarValueType.OFFSET_TIMESTAMP -> SqlTypeName.TIMESTAMP_TZ
                }
                placeholder(type, null, null)
            }
            is SemanticExpression.Literal -> when (val value = expression.value) {
                is String -> SqlLiteral.createCharString(value, pos)
                is Number -> SqlLiteral.createExactNumeric(BigDecimal(value.toString()).toPlainString(), pos)
                is Boolean -> SqlLiteral.createBoolean(value, pos)
                else -> throw IllegalArgumentException("unsupported scalar literal type")
            }
            is SemanticExpression.NativeCall -> {
                val declaration = nativeFunctions(expression)
                declaration.requireCall(expression)
                expression.arguments.forEachIndexed { index, argument ->
                    if (argument is SemanticExpression.ScalarArgument) {
                        val actual = resolve(argument.expression, references, nativeFunctions)
                        val expected = declaration.parameters[index].scalarType
                        require(actual == expected || actual == ScalarValueType.ENUM && expected == ScalarValueType.TEXT) {
                            "native function scalar parameter type differs from its declaration"
                        }
                    }
                }
                val type = declaration.resultType
                placeholder(SqlTypeName.valueOf(type.name), type.precision, type.scale)
            }
            is SemanticExpression.Call -> SemanticCalciteOperators.sqlCall(expression, ::recurse)
            is SemanticExpression.Operation -> {
                val args = expression.operands.map(::recurse)
                when (expression.operator) {
                    SemanticExpression.Operator.IN -> {
                        require(args.size >= 2) { "IN requires values" }
                        Ops.IN.createCall(pos, args.first(), SqlNodeList(args.drop(1), pos))
                    }
                    SemanticExpression.Operator.AND, SemanticExpression.Operator.OR -> {
                        require(args.size >= 2) { "boolean combination requires at least two operands" }
                        args.drop(1).fold(args.first()) { tree, arg -> SemanticCalciteOperators.operation(expression.operator).createCall(pos, tree, arg) }
                    }
                    else -> SemanticCalciteOperators.operation(expression.operator).createCall(pos, args)
                }
            }
            is SemanticExpression.CaseWhen -> SqlCase(pos, null,
                SqlNodeList(expression.branches.map { recurse(it.`when`) }, pos),
                SqlNodeList(expression.branches.map { recurse(it.then) }, pos),
                expression.otherwise?.let(::recurse) ?: SqlLiteral.createNull(pos))
            is SemanticExpression.Round -> Ops.ROUND.createCall(pos, recurse(expression.expression), SqlLiteral.createExactNumeric(expression.scale.toString(), pos))
            is SemanticExpression.Cast -> {
                val type = when (expression.targetType) {
                    SemanticExpression.ValueType.STRING -> SqlTypeName.VARCHAR
                    SemanticExpression.ValueType.INTEGER -> SqlTypeName.INTEGER
                    SemanticExpression.ValueType.BIGINT -> SqlTypeName.BIGINT
                    SemanticExpression.ValueType.DECIMAL -> SqlTypeName.DECIMAL
                    SemanticExpression.ValueType.DOUBLE -> SqlTypeName.DOUBLE
                    SemanticExpression.ValueType.BOOLEAN -> SqlTypeName.BOOLEAN
                    SemanticExpression.ValueType.DATE -> SqlTypeName.DATE
                    SemanticExpression.ValueType.TIMESTAMP -> SqlTypeName.TIMESTAMP
                }
                Ops.CAST.createCall(pos, recurse(expression.expression), specification(type, expression.precision, expression.scale))
            }
        }
    }

    private fun placeholder(type: SqlTypeName, precision: Int?, scale: Int?): SqlNode =
        Ops.CAST.createCall(pos, SqlLiteral.createNull(pos), specification(type, precision, scale))

    private fun specification(type: SqlTypeName, precision: Int?, scale: Int?): SqlDataTypeSpec = SqlDataTypeSpec(when {
        scale != null -> SqlBasicTypeNameSpec(type, requireNotNull(precision), scale, pos)
        precision != null -> SqlBasicTypeNameSpec(type, precision, pos)
        else -> SqlBasicTypeNameSpec(type, pos)
    }, pos)
}
