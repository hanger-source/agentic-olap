package com.olap.prototype.connector

import com.olap.prototype.contract.JdbcColumnType
import com.olap.prototype.contract.JdbcParameter
import com.olap.prototype.contract.NativeValueType
import com.olap.prototype.expression.NativeScalarFunction
import com.olap.prototype.expression.NativeAverageBehavior
import org.apache.calcite.rel.type.RelDataType
import org.apache.calcite.rel.type.RelDataTypeFactory
import org.apache.calcite.rel.type.RelDataTypeSystem
import org.apache.calcite.rel.core.Aggregate
import org.apache.calcite.rel.core.AggregateCall
import org.apache.calcite.rex.RexNode
import org.apache.calcite.rex.RexOver
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
    /**
     * DIVIDE 表达允许小数的算术商，不按整数输入选择截断商。
     * 两个整数输入由核心按各自完整整数域无损 CAST 为 DECIMAL(p,0)，保留 NULL；BIGINT 为 DECIMAL(19,0)。
     * SAFE_DIVIDE 使用同一入口，核心另外保留 NULLIF 的零分母政策。
     * 返回类型推导须描述实际中间结果，SQL AST 映射须兑现所接受输入的原生容量及算术；
     * 原生整型商会被拒绝；DECIMAL 的 scale 为零本身不证明截断，连接器仍须兑现实际算术，
     * 例如检查可整除性并明确拒绝非整除输入，而不能改贴 DECIMAL 类型来掩盖整数截断；
     * 也不能把有限 scale 的原生商仅标成更宽类型，或依赖外层 CAST 补回已舍入的位数。
     * 可返回自定义 SqlOperator，在类型推导阶段拒绝尚无保证的精确输入，再在 unparse 映射已证明的策略。
     * 此入口只有本次运算的有序实参与原生类型，不承诺收到外围数量合同的目标精度或舍入。
     */
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
    /** 描述该真实聚合调用的原生行为，包含实际分组、FILTER、DISTINCT 及输入／结果类型；未知时明确拒绝。 */
    fun averageBehavior(owner: Aggregate, call: AggregateCall): NativeAverageBehavior
    /** 描述该真实窗口调用的原生行为，不用普通全局聚合的推导代替实际窗口。 */
    fun averageBehavior(over: RexOver): NativeAverageBehavior
    fun numericFailure(value: SqlNode, type: RelDataType, position: SqlParserPos): SqlNode
    fun parameter(type: RelDataType, value: Any?): JdbcParameter
    fun requireNativeCast(target: RelDataType)
    fun tablePath(catalog: String?, schema: String?, table: String): List<String>
    fun matchesNativeResult(expected: NativeValueType, observed: JdbcColumnType): Boolean
}
