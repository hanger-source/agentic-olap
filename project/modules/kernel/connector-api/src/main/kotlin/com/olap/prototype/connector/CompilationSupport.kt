package com.olap.prototype.connector

import com.olap.prototype.contract.JdbcColumnType
import com.olap.prototype.contract.JdbcParameter
import com.olap.prototype.contract.NativeValueType
import com.olap.prototype.expression.NativeScalarFunction
import com.olap.prototype.metric.MetricValueContract
import org.apache.calcite.rel.type.RelDataType
import org.apache.calcite.rel.type.RelDataTypeFactory
import org.apache.calcite.rel.type.RelDataTypeSystem
import org.apache.calcite.rex.RexNode
import org.apache.calcite.sql.SqlAggFunction
import org.apache.calcite.sql.SqlDialect
import org.apache.calcite.sql.SqlKind
import org.apache.calcite.sql.SqlLiteral
import org.apache.calcite.sql.SqlNode
import org.apache.calcite.sql.SqlOperator
import org.apache.calcite.sql.parser.SqlParserPos
import org.apache.calcite.tools.RelBuilder

/** 已有语义的原生映射；不替换业务关系规划或访问连接。 */
interface CompilationSupport {
    val nativeScalarFunctions: List<NativeScalarFunction>
    fun nativeAggregate(kind: SqlKind): SqlAggFunction

    sealed interface NativeArgument
    data class ScalarArgument(val value: SqlNode) : NativeArgument
    class TableArgument(qualifiedPath: List<String>) : NativeArgument {
        val qualifiedPath: List<String> = java.util.List.copyOf(qualifiedPath)
        init { require(this.qualifiedPath.isNotEmpty() && this.qualifiedPath.all { it.isNotBlank() }) { "native table argument requires an exact qualified path" } }
        override fun equals(other: Any?): Boolean = other is TableArgument && qualifiedPath == other.qualifiedPath
        override fun hashCode(): Int = qualifiedPath.hashCode()
    }

    /** 参数已由内核核验精确物理引用；插件只渲染原生 AST。 */
    fun nativeScalarCall(function: String, arguments: List<NativeArgument>): SqlNode
    fun requireNativeScalarFunction(id: String): NativeScalarFunction = nativeScalarFunctions.firstOrNull { it.id == id }
        ?: throw IllegalArgumentException("native scalar function is not declared by the exact connector: $id")

    enum class ParameterSubmission { BIND_PARAMETERS, TYPED_LITERALS }
    val parameterSubmission: ParameterSubmission

    /** 参数已定型；近似数值以精确文本进入外层 CAST，保留负零。 */
    fun parameterLiteral(parameter: JdbcParameter): SqlNode = when (parameter.encoding) {
        JdbcParameter.Encoding.INTEGER, JdbcParameter.Encoding.DECIMAL -> SqlLiteral.createExactNumeric(parameter.value, SqlParserPos.ZERO)
        JdbcParameter.Encoding.BOOLEAN -> SqlLiteral.createBoolean(parameter.jdbcValue() as Boolean, SqlParserPos.ZERO)
        JdbcParameter.Encoding.FLOAT, JdbcParameter.Encoding.DOUBLE, JdbcParameter.Encoding.TEXT,
        JdbcParameter.Encoding.DATE, JdbcParameter.Encoding.LOCAL_TIME, JdbcParameter.Encoding.OFFSET_TIME,
        JdbcParameter.Encoding.LOCAL_TIMESTAMP, JdbcParameter.Encoding.OFFSET_TIMESTAMP -> SqlLiteral.createCharString(parameter.value, SqlParserPos.ZERO)
    }

    enum class Capability {
        COLLECTION_RESULTS, COLLECTION_EXPANSION, COLLECTION_SORT, COLLECTION_TEXT, RECURSIVE_ANCESTORS,
        NUMERIC_FAILURE, NUMERIC_ROUNDING, SAMPLE_STDDEV, ELAPSED_TIMESTAMPDIFF, WIDE_INTEGER,
        TIMESTAMP_PARAMETER_NATIVE_CAST, ROUND_KEEPS_INPUT_PRECISION,
        /** 派生关系中的投影可以引用外层查询值；不等同于普通 EXISTS 条件相关。 */
        CORRELATED_DERIVED_PROJECTION,
    }

    val typeSystem: RelDataTypeSystem
    val dialect: SqlDialect
    fun jdbcType(factory: RelDataTypeFactory, column: JdbcColumnType): RelDataType
    val maximumDeclaredDecimalPrecision: Int
    fun supports(capability: Capability): Boolean
    val division: SqlOperator
    /** SQL 三值逻辑的精确文本身份相等；NULL 不匹配，不能折叠大小写、空白或 Unicode 差异。 */
    fun textIdentityEquals(builder: RelBuilder, left: RexNode, right: RexNode): RexNode
    /**
     * collection 是当前输入行中已限定来源的 ARRAY 表达式，不是读取该值的子查询。
     * outputType 依次声明完整元素与从 1 开始的位置，保留有序 ROW 和递归 NULL 类型。
     * 返回原生 FROM 关系及两列的取值表达式；关系使用给定别名，必要的表函数 LATERAL 语法由插件映射。
     * 内核拥有关联、输出投影和 INNER/OUTER 补空；ordinal 在真实元素上非 NULL，缺行时保持 NULL。
     */
    fun expandCollection(collection: SqlNode, outputType: RelDataType, tableAlias: String): CollectionExpansion
    data class CollectionExpansion(val relation: SqlNode, val element: SqlNode, val ordinal: SqlNode)
    fun sortCollection(builder: RelBuilder, value: RexNode, descending: Boolean, nullsFirst: Boolean): RexNode
    fun collectionText(builder: RelBuilder, value: RexNode, delimiter: String, nullReplacement: String?): RexNode
    val decimalAverageRounding: MetricValueContract.Rounding
    fun numericFailure(value: SqlNode, type: RelDataType, position: SqlParserPos): SqlNode
    fun parameter(type: RelDataType, value: Any?): JdbcParameter
    fun requireNativeCast(target: RelDataType)
    fun tablePath(catalog: String?, schema: String?, table: String): List<String>
    fun matchesNativeResult(expected: NativeValueType, observed: JdbcColumnType): Boolean
}
