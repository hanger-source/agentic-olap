package com.olap.prototype.expression

import com.olap.prototype.connector.CompilationSupport
import com.olap.prototype.connector.AggregateSql
import com.olap.prototype.connector.FiniteDecimalTypes
import com.olap.prototype.connector.NativeCompilationCapabilityException
import org.apache.calcite.rel.RelNode
import org.apache.calcite.rel.SingleRel
import org.apache.calcite.plan.RelTraitSet
import org.apache.calcite.rel.core.*
import org.apache.calcite.rel.logical.LogicalProject
import org.apache.calcite.rel.logical.LogicalWindow
import org.apache.calcite.rel.rel2sql.RelToSqlConverter
import org.apache.calcite.rel.rel2sql.SqlImplementor
import org.apache.calcite.rel.type.RelDataType
import org.apache.calcite.rex.*
import org.apache.calcite.sql.*
import org.apache.calcite.sql.`fun`.SqlStdOperatorTable as Ops
import org.apache.calcite.sql.`fun`.SqlCase
import org.apache.calcite.sql.type.SqlTypeName
import org.apache.calcite.sql.type.SqlTypeUtil
import org.apache.calcite.sql.util.SqlShuttle
import org.apache.calcite.sql.validate.SqlValidatorUtil
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.util.IdentityHashMap
import java.util.Locale
import java.util.TreeSet

/** 保留参数、窗口和完成关系边界；Calcite承载关系，精确连接器映射原生SQL节点。 */
class ParameterizedRelToSqlConverter(private val support: CompilationSupport, private val evaluationBoundaries: Set<RelNode> = emptySet()) : RelToSqlConverter(support.dialect) {
    private companion object {
        val log = LoggerFactory.getLogger(ParameterizedRelToSqlConverter::class.java)
    }
    private val shared = IdentityHashMap<RelNode, SqlIdentifier>()
    private val definitions = mutableListOf<SqlNode>()
    private val occupiedRelations = mutableSetOf<String>()
    private var defining: RelNode? = null

    override fun aliasContext(aliases: Map<String, RelDataType>, qualified: Boolean): Context =
        object : AliasContext(dialect, aliases, qualified) {
            override fun toSql(program: RexProgram?, expression: RexNode): SqlNode {
                fieldAccessEvidence(expression)
                val converted = subQuery(program, expression, this) ?: super.toSql(program, expression)
                return preserveDecimalLiteralType(expression, converted)
            }
        }

    override fun selectListContext(selectList: SqlNodeList, ignoreCast: Boolean): Context {
        val fields = super.selectListContext(selectList, ignoreCast)
        return object : Context(dialect, selectList.size, ignoreCast) {
            override fun field(ordinal: Int): SqlNode = fields.field(ordinal)
            override fun orderField(ordinal: Int): SqlNode = fields.orderField(ordinal)
            override fun implementor(): SqlImplementor = this@ParameterizedRelToSqlConverter
            override fun getAliasContext(variable: RexCorrelVariable): Context = this@ParameterizedRelToSqlConverter.getAliasContext(variable)
            override fun toSql(program: RexProgram?, expression: RexNode): SqlNode {
                fieldAccessEvidence(expression)
                val converted = subQuery(program, expression, this) ?: super.toSql(program, expression)
                return preserveDecimalLiteralType(expression, converted)
            }
        }
    }

    // Calcite的SQL数值字面量只保留值；DECIMAL(p,0)若发为裸整数会让后端选择整数运算。
    // 在插件仍可读取真实Rex字面量之后，按已经确定的原生类型保留每个嵌套字面量的容量。
    private fun preserveDecimalLiteralType(expression: RexNode, converted: SqlNode): SqlNode =
        if (requiresDecimalLiteralType(expression)) Ops.CAST.createCall(converted.parserPosition, converted,
            requireNotNull(dialect.getCastSpec(expression.type)) { "decimal literal requires its declared native SQL type" })
        else converted

    private fun requiresDecimalLiteralType(expression: RexNode): Boolean =
        expression is RexLiteral && expression.type.sqlTypeName == SqlTypeName.DECIMAL && !FiniteDecimalTypes.isFinite(expression.type)

    private fun fieldAccessEvidence(expression: RexNode) {
        if (expression is RexFieldAccess && log.isDebugEnabled) log.debug(
            "原生 ROW 字段转换：requestId={}, workerKind={}, referenceType={}, fieldIndex={}, fieldType={}, resultType={}",
            MDC.get("requestId"), MDC.get("workerKind"), expression.referenceExpr.type.fullTypeString, expression.field.index,
            expression.field.type.fullTypeString, expression.type.fullTypeString)
    }

    private fun subQuery(program: RexProgram?, expression: RexNode, context: Context): SqlNode? {
        if (expression !is RexSubQuery) return null
        val query = dialect.prepareUnparse(expression) as? RexSubQuery ?: return null
        return when (query.kind) {
            SqlKind.IN, SqlKind.SOME, SqlKind.ALL -> {
                val selected = convertRoot(query.rel).asQueryOrValues()
                val values = query.operands.map { context.toSql(program, it) }
                query.operator.createCall(POS, if (values.size == 1) values.single() else SqlNodeList(values, POS), selected)
            }
            SqlKind.EXISTS, SqlKind.UNIQUE, SqlKind.SCALAR_QUERY -> query.operator.createCall(POS, convertRoot(query.rel).asQueryOrValues())
            else -> null
        }
    }

    fun visit(spool: TableSpool): Result = visitInput(spool, 0)

    fun visit(expansion: CollectionExpansionRel): Result {
        val input = visitInput(expansion, 0).resetAlias()
        val context = input.qualifiedContext()
        val alias = SqlValidatorUtil.uniquify("__collection", aliasSet, SqlValidatorUtil.EXPR_SUGGESTER)
        val fields = expansion.input.rowType.fieldList.indices.map { context.field(it) }
        val selected = CollectionExpansionSql.select(support, expansion, input.asFrom(), fields, alias) { suggested ->
            SqlValidatorUtil.uniquify(suggested, aliasSet, SqlValidatorUtil.EXPR_SUGGESTER)
        }
        // 完成元素先成为有名列，后续 ROW_FIELD 不能把 CASE 或原生下标表达式当成字段标识符。
        val completed = result(selected, listOf(Clause.FROM, Clause.SELECT), expansion, null).resetAlias()
        return result(completed.asFrom(), listOf(Clause.FROM), expansion, null)
    }

    fun visit(repeat: RepeatUnion): Result {
        require(!repeat.all && repeat.iterationLimit == -1 && repeat.transientTable != null) {
            "recursive relation requires an unbounded DISTINCT fixed point with an exact transient table"
        }
        val transient = requireNotNull(repeat.transientTable)
        val identity = SqlIdentifier(transient.qualifiedName.last(), POS)
        shared[repeat] = identity
        val seed = visitInput(repeat, 0).asStatement()
        val iterative = visitInput(repeat, 1).asStatement()
        val columns = SqlNodeList(transient.rowType.fieldNames.map { SqlIdentifier(it, POS) }, POS)
        definitions.add(SqlWithItem(POS, identity, columns, Ops.UNION.createCall(POS, seed, iterative), SqlLiteral.createBoolean(true, POS)))
        return sharedReference(repeat, identity)
    }

    /** 固定点变量保持自身列合同；优化后RepeatUnion输出仍按序交给真正的消费者。 */
    private fun sharedReference(node: RelNode, identity: SqlIdentifier): Result {
        if (node !is RepeatUnion) return result(identity, listOf(Clause.FROM), node, null)
        val actual = requireNotNull(node.transientTable).rowType
        check(actual.fieldCount == node.rowType.fieldCount && actual.fieldList.indices.all { index ->
            SqlTypeUtil.equalSansNullability(node.cluster.typeFactory, actual.fieldList[index].type, node.rowType.fieldList[index].type)
        }) { "shared SQL relation differs from its compiled row width or types" }
        if (actual.fieldNames == node.rowType.fieldNames) return result(identity, listOf(Clause.FROM), node, null)
        val columns = mutableListOf<SqlNode>()
        actual.fieldNames.forEach { name -> addSelect(columns, SqlIdentifier(identity.names + name, POS), node.rowType) }
        val selected = SqlSelect(POS, null, SqlNodeList(columns, POS), identity,
            null, null, null, null, null, null, null, null, null)
        return result(selected, listOf(Clause.FROM, Clause.SELECT), node, null)
    }

    override fun visitAntiOrSemiJoin(join: Join): Result {
        val left = visitInput(join, 0).resetAlias()
        val right = visitInput(join, 1).resetAlias()
        val leftContext = left.qualifiedContext()
        val predicate = convertConditionToSqlNode(join.condition, leftContext, right.qualifiedContext())
        val lookup = SqlSelect(POS, null, SqlNodeList(listOf(SqlLiteral.createExactNumeric("1", POS)), POS),
            right.asFrom(), predicate, null, null, null, null, null, null, null, null)
        var exists = Ops.EXISTS.createCall(POS, lookup)
        if (join.joinType == JoinRelType.ANTI) exists = Ops.NOT.createCall(POS, exists)
        val columns = mutableListOf<SqlNode>()
        repeat(join.rowType.fieldCount) { addSelect(columns, leftContext.field(it), join.rowType) }
        val selected = SqlSelect(POS, null, SqlNodeList(columns, POS), left.asFrom(), exists, null, null, null, null, null, null, null, null)
        return result(selected, listOf(Clause.FROM, Clause.WHERE, Clause.SELECT), join, null)
    }

    override fun dispatch(node: RelNode): Result {
        if (node in evaluationBoundaries && node is Project && !support.supports(CompilationSupport.Capability.CORRELATED_DERIVED_PROJECTION)) {
            var correlated = false
            node.accept(object : RexShuttle() {
                override fun visitCorrelVariable(variable: RexCorrelVariable): RexNode { correlated = true; return variable }
            })
            if (correlated) throw NativeCompilationCapabilityException(CompilationSupport.Capability.CORRELATED_DERIVED_PROJECTION, "SQL_EVALUATION_BINDING")
        }
        val converted = shared[node]?.let { sharedReference(node, it) } ?: super.dispatch(node)
        if (node !in evaluationBoundaries) return converted
        val bound = if (node is Join) {
            // Join的完成列须先显式命名，不能把多表字段表直接当成一个已有别名。
            val context = converted.qualifiedContext()
            val columns = mutableListOf<SqlNode>()
            repeat(node.rowType.fieldCount) { addSelect(columns, context.field(it), node.rowType) }
            val selected = SqlSelect(POS, null, SqlNodeList(columns, POS), converted.asFrom(), null, null, null, null, null, null, null, null, null)
            result(selected, listOf(Clause.FROM, Clause.SELECT), node, null)
        } else converted
        if (node === defining) return bound
        // dispatch仍在当前子访问frame内；Result的expectedRel必须继续属于真正的父节点。
        return result(bound.resetAlias().asFrom(), listOf(Clause.FROM), node, null)
    }

    internal fun statement(plan: RelNode): SqlNode {
        val graph = SqlStatementRelations.analyze(plan, evaluationBoundaries)
        occupiedRelations.addAll(graph.occupiedNames)
        var index = 0
        graph.namedRelations.forEach { node ->
            var name: String
            do { name = "__r${index++}" } while (!occupiedRelations.add(name.lowercase(Locale.ROOT)))
            emitNamedRelation(node, name)
        }
        return requireNotNull(withDefinitions(convertRoot(plan).asStatement()).accept(object : SqlShuttle() {
            override fun visit(parameter: SqlDynamicParam): SqlNode {
                val type = requireNotNull(graph.parameterTypes[parameter.index]) { "SQL parameter has no compiled type" }
                return Ops.CAST.createCall(parameter.parserPosition, parameter, dialect.getCastSpec(type))
            }
        })) { "SQL parameter typing must preserve the statement" }
    }

    private fun emitNamedRelation(node: RelNode, name: String) {
        val identity = SqlIdentifier(name, POS)
        val previous = defining
        val query = try {
            // WITH 本身承接求值边界；定义自身无需再包一层同列 Project。
            defining = node
            convertRoot(node).asStatement()
        } finally {
            defining = previous
        }
        val outputs = (query as? SqlSelect)?.selectList
        val namedOutputs = outputs?.map { output ->
            when {
                output is SqlIdentifier && !output.isStar -> output.names.last()
                output is SqlCall && output.kind == SqlKind.AS -> (output.operand<SqlNode>(1) as? SqlIdentifier)?.simple
                else -> null
            }
        }
        val explicitOutputs = outputs != null && outputs.size == node.rowType.fieldCount &&
            outputs.none { it is SqlIdentifier && it.isStar }
        // 定义直接交付正式列名，特别是 JOIN 两侧的同名列；不再包同列投影。
        val columns = if (explicitOutputs) {
            (query as SqlSelect).selectList = SqlNodeList(requireNotNull(outputs).mapIndexed { index, output ->
                val name = node.rowType.fieldNames[index]
                if (namedOutputs?.get(index) == name) output else {
                    val value = if (output is SqlCall && output.kind == SqlKind.AS) output.operand<SqlNode>(0) else output
                    Ops.AS.createCall(POS, value, SqlIdentifier(name, POS))
                }
            }, POS)
            null
        } else SqlNodeList(node.rowType.fieldNames.map { SqlIdentifier(it, POS) }, POS)
        definitions.add(SqlWithItem(POS, identity, columns, query, SqlLiteral.createBoolean(false, POS)))
        shared[node] = identity
    }

    private fun declareAggregateRelation(suggested: String, columns: List<String>, query: SqlNode): SqlIdentifier {
        var name: String
        do {
            name = SqlValidatorUtil.uniquify(suggested, aliasSet, SqlValidatorUtil.EXPR_SUGGESTER)
        } while (!occupiedRelations.add(name.lowercase(Locale.ROOT)))
        val identity = SqlIdentifier(name, POS)
        var body = query
        while (body is SqlWith || body is SqlOrderBy) body = when (body) {
            is SqlWith -> body.body
            is SqlOrderBy -> body.query
            else -> error("unreachable aggregate query wrapper")
        }
        val selected = requireNotNull(body as? SqlSelect) { "native aggregate work query requires an explicit final SELECT" }
        val outputs = requireNotNull(selected.selectList)
        require(outputs.size == columns.size && outputs.none { it is SqlIdentifier && it.isStar }) {
            "native aggregate work query must return all technical group columns and one value"
        }
        // 定义自身交付实际列名，不依赖消费者重新解释 WITH 的列名覆盖。
        selected.selectList = SqlNodeList(outputs.mapIndexed { index, output ->
            val value = if (output is SqlCall && output.kind == SqlKind.AS) output.operand<SqlNode>(0) else output
            Ops.AS.createCall(POS, value, SqlIdentifier(columns[index], POS))
        }, POS)
        definitions.add(SqlWithItem(POS, identity, null, query, SqlLiteral.createBoolean(false, POS)))
        return identity
    }

    private fun convertRoot(plan: RelNode): Result {
        // Calcite visitRoot 会隐式再次规划并重建节点；这里只建立转换根的访问 frame。
        val holder = object : SingleRel(plan.cluster, plan.traitSet, plan) {
            override fun copy(traitSet: RelTraitSet, inputs: List<RelNode>): RelNode =
                error("SQL conversion root is not a planning relation")
        }
        return visitInput(holder, 0)
    }

    private fun withDefinitions(body: SqlNode): SqlNode {
        if (definitions.filterIsInstance<SqlWithItem>().any { it.recursive.booleanValue() }) {
            val first = definitions.first() as SqlWithItem
            definitions[0] = SqlWithItem(POS, first.name, first.columnList, first.query, SqlLiteral.createBoolean(true, POS))
        }
        return if (definitions.isEmpty()) body else SqlWith(POS, SqlNodeList(definitions, POS), body)
    }

    override fun result(node: SqlNode, clauses: Collection<Clause>, relation: RelNode, aliases: Map<String, RelDataType>?): Result =
        super.result(node, if (relation is Window) TreeSet(clauses).apply { add(Clause.SELECT) } else clauses, relation, aliases)

    override fun visit(filter: Filter): Result {
        if (filter.input !is Aggregate && !shared.containsKey(filter.input) &&
            filter.rowType.fieldNames == filter.input.rowType.fieldNames) return super.visit(filter)
        // Filter 消费完成关系。聚合可能包含独立原生归约，不能将完成值条件塞回其内层 HAVING。
        // 让原 Filter 继续走 Calcite 的普通 WHERE 路径，并恢复本来已有的列合同。
        val input = filter.input
        val references = (0 until input.rowType.fieldCount).map { input.cluster.rexBuilder.makeInputRef(input, it) }
        val named = LogicalProject.create(input, emptyList(), references, filter.rowType.fieldNames, emptySet())
        return super.visit(filter.copy(filter.traitSet, named, filter.condition))
    }

    override fun visit(window: Window): Result {
        val inputFields = window.input.rowType.fieldCount
        val constants = object : RexShuttle() {
            override fun visitInputRef(reference: RexInputRef): RexNode = if (reference.index < inputFields) reference else window.constants[reference.index - inputFields]
        }
        val groups = window.groups.map { Window.Group(it.keys, it.isRows, it.lowerBound.accept(constants), it.upperBound.accept(constants), it.exclude, it.orderKeys, it.aggCalls) }
        return super.visit(LogicalWindow.create(window.traitSet, window.hints, window.input, window.constants, window.rowType, groups))
    }

    override fun buildAggregate(aggregate: Aggregate, builder: Builder, selectList: MutableList<SqlNode>,
        groupByList: MutableList<SqlNode>): Builder {
        val firstAggregate = selectList.size
        val completed = super.buildAggregate(aggregate, builder, selectList, groupByList)
        check(selectList.size == firstAggregate + aggregate.aggCallList.size) {
            "aggregate SQL outputs differ from the actual aggregate calls"
        }
        val mappings = aggregate.aggCallList.mapIndexed { index, call ->
            val output = selectList[firstAggregate + index]
            val alias = (output as? SqlCall)?.takeIf { it.kind == SqlKind.AS }
            val expression = alias?.operand<SqlNode>(0) ?: output
            val arguments = call.argList.map(builder.context::field)
            val mapped = support.aggregateSql(aggregate, call, arguments, expression)
            val value = when (mapped) {
                is AggregateSql.Direct -> mapped.expression
                is AggregateSql.Reduction -> mapped.aggregate
            }
            selectList[firstAggregate + index] = if (alias == null) value else
                alias.operator.createCall(alias.functionQuantifier, alias.parserPosition,
                    *(listOf(value) + alias.operandList.drop(1)).toTypedArray())
            mapped
        }
        completed.setSelect(SqlNodeList(selectList, POS))
        if (mappings.none { it is AggregateSql.Reduction }) return completed
        check(firstAggregate == aggregate.groupCount) { "native aggregate reduction requires the complete technical group prefix" }
        val reduced = AggregateSqlReduction.complete(aggregate, completed.result().asSelect(), mappings, ::declareAggregateRelation)
        // 这里仍在 Aggregate 自身的访问 frame 内，不能把预期属于父消费者的 Result
        // 再当作本 Aggregate 的输入 builder；直接完成同一 owner 的新 SQL Builder。
        return Builder(aggregate, listOf(Clause.FROM, Clause.SELECT), reduced,
            selectListContext(requireNotNull(reduced.selectList), false), isAnon, null)
    }

    override fun visit(project: Project): Result {
        if (project.projects.any(::requiresProjectionType)) return typedProjection(project)
        if (!RexUtil.isIdentity(project.projects, project.input.rowType) || project.variablesSet.isNotEmpty()) return super.visit(project)
        val input = if (project.input is Sort) visitInput(project, 0) else visitInput(project, 0, Clause.SELECT)
        val projection = input.builder(project)
        val columns = mutableListOf<SqlNode>()
        project.projects.forEach { addSelect(columns, projection.context.toSql(null, it), project.rowType) }
        projection.setSelect(SqlNodeList(columns, POS))
        return projection.result()
    }

    private fun typedProjection(project: Project): Result {
        val converted = super.visit(project)
        val select = converted.asSelect()
        val columns = requireNotNull(select.selectList)
        check(columns.size == project.projects.size) { "literal projection SQL differs from its compiled row width" }
        val typed = columns.mapIndexed { index, column ->
            val value = project.projects[index]
            if (!requiresProjectionType(value)) column else {
                val alias = (column as? SqlCall)?.takeIf { it.kind == SqlKind.AS }
                val literal = alias?.operand<SqlNode>(0) ?: column
                val cast = Ops.CAST.createCall(literal.parserPosition, literal, dialect.getCastSpec(value.type))
                if (alias == null) cast else Ops.AS.createCall(alias.parserPosition, cast, alias.operand<SqlNode>(1))
            }
        }
        select.selectList = SqlNodeList(typed, columns.parserPosition)
        return converted
    }

    override fun visit(values: Values): Result {
        val converted = super.visit(values)
        if (values.rowType.fieldCount == 0) return converted
        if (values.tuples.isNotEmpty()) {
            // RelBuilder 可将全局空列聚合及常量投影折成 Values。其 Rex 字面量类型
            // 不能约束后端对裸 SQL 字面量的推导，仍须在真正交付列上保持原生类型。
            val input = converted.resetAlias()
            val context = input.qualifiedContext()
            val columns = values.rowType.fieldList.mapIndexed { ordinal, field ->
                val value = context.field(ordinal)
                val typed = if (field.type.sqlTypeName == SqlTypeName.NULL) value else
                    Ops.CAST.createCall(POS, value, dialect.getCastSpec(field.type))
                Ops.AS.createCall(POS, typed, SqlIdentifier(field.name, POS))
            }
            val selected = SqlSelect(POS, null, SqlNodeList(columns, POS), input.asFrom(),
                null, null, null, null, null, null, null, null, null)
            return result(selected, listOf(Clause.SELECT), values, null)
        }
        val typing = object : SqlShuttle() {
            var ordinal = 0
            override fun visit(literal: SqlLiteral): SqlNode {
                if (literal.typeName != SqlTypeName.NULL) return literal
                check(ordinal < values.rowType.fieldCount) { "Calcite empty Values SQL has more null fields than its row type" }
                val type = values.rowType.fieldList[ordinal++].type
                return if (type.sqlTypeName == SqlTypeName.NULL) literal else Ops.CAST.createCall(POS, literal, dialect.getCastSpec(type))
            }
        }
        val typed = requireNotNull(converted.asStatement().accept(typing)) { "empty Values typing must preserve the statement" }
        check(typing.ordinal == values.rowType.fieldCount) { "Calcite empty Values SQL does not preserve its row type fields" }
        return result(typed, listOf(Clause.SELECT), values, null)
    }

    override fun visitInput(parent: RelNode, ordinal: Int, anon: Boolean, ignoreClauses: Boolean, expectedClauses: Set<Clause>): Result {
        var input = parent.getInput(ordinal)
        // Aggregate(Project) 的默认 ignoreClauses 会再次把列程序展开到每个聚合参数。
        // dispatch 已把它变为有名 FROM；仍须保留消费者实际要追加的 WHERE/SELECT/GROUP BY 等子句。
        if (input in evaluationBoundaries) return super.visitInput(parent, ordinal, anon, false, expectedClauses)
        if (parent is Project && input is Window) return super.visitInput(parent, ordinal, anon, false, setOf(Clause.FROM, Clause.SELECT))
        if (parent is Window) {
            while (input is Project || input is Filter) input = input.getInput(0)
            return super.visitInput(parent, ordinal, anon, false, if (input is Aggregate) setOf(Clause.FROM) else expectedClauses)
        }
        if (parent is Aggregate && parent.input is Project) {
            val project = parent.input as Project
            if (parent.groupSet.asList().any { hasDynamicParameter(project.projects[it]) }) return super.visitInput(parent, ordinal, anon, false, expectedClauses)
        }
        return super.visitInput(parent, ordinal, anon, ignoreClauses, expectedClauses)
    }

    private fun requiresProjectionType(value: RexNode): Boolean {
        if (value is RexLiteral) return value.type.sqlTypeName != SqlTypeName.NULL && !requiresDecimalLiteralType(value)
        if (value.type.sqlTypeName !in setOf(SqlTypeName.ARRAY, SqlTypeName.ROW) || !determinedContainerType(value.type)) return false
        val constructors = object : RexVisitorImpl<Void?>(true) {
            var found = false
            override fun visitCall(call: RexCall): Void? {
                found = found || call.kind in setOf(SqlKind.ROW, SqlKind.ARRAY_VALUE_CONSTRUCTOR)
                return super.visitCall(call)
            }
        }
        value.accept(constructors)
        return constructors.found
    }
    private fun determinedContainerType(type: RelDataType): Boolean = when (type.sqlTypeName) {
        SqlTypeName.NULL, SqlTypeName.ANY, SqlTypeName.UNKNOWN -> false
        SqlTypeName.ARRAY, SqlTypeName.MULTISET -> type.componentType?.let(::determinedContainerType) == true
        SqlTypeName.ROW -> type.fieldList.all { determinedContainerType(it.type) }
        else -> true
    }
    private fun hasDynamicParameter(expression: RexNode): Boolean {
        val visitor = object : RexVisitorImpl<Void?>(true) {
            var found = false
            override fun visitDynamicParam(parameter: RexDynamicParam): Void? { found = true; return null }
        }
        expression.accept(visitor)
        return visitor.found
    }
}
