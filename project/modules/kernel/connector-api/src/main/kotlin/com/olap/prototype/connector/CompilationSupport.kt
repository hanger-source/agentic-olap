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
    /**
     * 映射已定义的聚合，不改变参与人口、NULL、重复和权重。
     * 返回类型与原生舍入必须覆盖所接受的输入类型及支持的执行形态；不能用最终输出容量替代原生算术保证。
     * 后端会静默产生错误结果或保证仍未知时明确拒绝，不以采样范围、结果 CAST 或执行失败后的替换策略宣称支持。
     */
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
        /** 输入投影 SRF 支持核心的 CARDINALITY、typed singleton ARRAY 和整元素 NULL 补空。 */
        COLLECTION_PROJECTION_OUTER,
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
     * outputType 依次声明完整元素与从 1 开始的位置，保留有序 ROW 和递归 NULL 类型。
     * 原生放置计划只映射生成与取值；完整输入、透传列、INNER/OUTER 和缺行标记由核心构造。
     * 回调只取得当前作用域的 ARRAY/生成值表达式，不取得输入关系或 SQL 改写权限。
     */
    fun expandCollection(outputType: RelDataType, tableAlias: String): CollectionExpansion
    sealed interface CollectionExpansion {
        /** 生成使用给定 tableAlias 的 FROM 关系；核心用 CROSS/LEFT JOIN 保留声明的人口。 */
        class Relation(val generate: (SqlNode) -> CollectionRows) : CollectionExpansion
        /**
         * generate 返回恰好一个 SELECT-list SRF；核心绑定其生成值后再调用 values。
         * 两个回调的 ARRAY 均为各自作用域中同一已绑定的数组，不能捕获另一层的字段引用。
         * OUTER 另需 COLLECTION_PROJECTION_OUTER；生成器仍只展开核心提供的真实/哨兵数组。
         */
        class InputProjection(val generate: (SqlNode) -> SqlNode,
            val values: (collection: SqlNode, generated: SqlNode) -> CollectionValues) : CollectionExpansion
    }
    data class CollectionRows(val relation: SqlNode, val element: SqlNode, val ordinal: SqlNode)
    data class CollectionValues(val element: SqlNode, val ordinal: SqlNode)
    fun sortCollection(builder: RelBuilder, value: RexNode, descending: Boolean, nullsFirst: Boolean): RexNode
    fun collectionText(builder: RelBuilder, value: RexNode, delimiter: String, nullReplacement: String?): RexNode
    /** 原生精确 AVG 已兑现的舍入策略；与其类型系统声明的容量共同成立，不代表最终业务输出的舍入。 */
    val decimalAverageRounding: MetricValueContract.Rounding
    fun numericFailure(value: SqlNode, type: RelDataType, position: SqlParserPos): SqlNode
    fun parameter(type: RelDataType, value: Any?): JdbcParameter
    fun requireNativeCast(target: RelDataType)
    fun tablePath(catalog: String?, schema: String?, table: String): List<String>
    fun matchesNativeResult(expected: NativeValueType, observed: JdbcColumnType): Boolean
}
