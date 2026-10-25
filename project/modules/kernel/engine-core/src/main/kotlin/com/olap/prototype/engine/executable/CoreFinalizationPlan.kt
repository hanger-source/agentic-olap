package com.olap.prototype.engine.executable

import com.olap.prototype.api.ResultFrame
import com.olap.prototype.connector.CompilationSupport
import com.olap.prototype.calculation.Calculation
import com.olap.prototype.engine.EngineExecutionException
import com.olap.prototype.expression.SemanticNativeTypes
import com.olap.prototype.metric.MetricValueContract
import com.olap.prototype.query.PostOperationContract
import com.olap.prototype.query.QueryStageAnalysis
import com.olap.prototype.query.QueryExecutionMode
import com.olap.prototype.query.QueryStageAnalysis.ScopeId
import com.olap.prototype.query.ResultExpression.*
import com.olap.prototype.query.ResultExpressionTree
import com.olap.prototype.query.ScalarValueType
import com.olap.prototype.warehouse.ResultMaterializationBudget
import com.olap.prototype.warehouse.ResultMaterializationLimits
import com.olap.prototype.warehouse.WarehouseExecutionControl
import com.olap.prototype.runtime.RuntimeResourceLimitException
import org.apache.calcite.rel.type.RelDataType
import org.apache.calcite.sql.type.SqlTypeName
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.CancellationException

/** 一次Query的明确来源切点及正式核心标量后缀；不读取历史结果或重新访问来源。 */
class CoreFinalizationPlan private constructor(private val sourceLayout: ExecutableSourceLayout,
    val outputContract: ExecutableOutputContract, private val programs: List<ScopeProgram>, private val displayNames: Map<String, String>,
    private val sourceCuts: Map<ScopeId, Int>, scopes: List<ScopeId>) {
    val scopes: List<ScopeId> = java.util.List.copyOf(scopes)
    val sourceScopes: Set<ScopeId> = java.util.Set.copyOf(sourceCuts.keys)
    fun isCoreStage(scope: ScopeId, index: Int): Boolean = scope in scopes && index >= (sourceCuts[scope] ?: 0)
    private data class ScopeProgram(val scope: ScopeId, val root: Root, val operations: List<Operation>)
    private data class Binding(val scope: ScopeId, val expression: CoreExpression)
    private data class Column(val bindings: List<Binding>, val type: CoreScalarType, val zeroWhenAbsent: Boolean)
    private sealed interface Root {
        data object Source : Root
        data class Forward(val scope: ScopeId) : Root
        data class Projection(val columns: Map<String, Column>, val population: CoreFinalizationGraph.Population,
            val observationIdentity: List<String>?) : Root
    }
    private sealed interface Operation {
        data class Derive(val alias: String, val expression: CoreExpression) : Operation
        data class Project(val names: List<String>) : Operation
        data class Filter(val expression: CoreExpression) : Operation
    }

    fun execute(frame: ResultFrame, support: CompilationSupport, control: WarehouseExecutionControl,
        limits: ResultMaterializationLimits): ExecutableBusinessResult {
        control.checkCancellation()
        val source = try { sourceLayout.materialize(frame, support, control) }
        catch (failure: CancellationException) { throw failure }
        catch (failure: RuntimeResourceLimitException) { throw failure }
        catch (failure: RuntimeException) {
            throw EngineExecutionException("MATERIALIZE_SOURCE_RESULT", "CORE_FINALIZATION_SOURCE_CONTRACT_FAILED",
                "source cut result differs from its compiled input contract", failure)
        }
        val budget = ResultMaterializationBudget(limits)
        val rows = mutableListOf<List<ExecutableBusinessResult.Value>>()
        frame.rows.indices.forEach { rowIndex ->
            control.checkCancellation()
            val sourceValues = sourceLayout.scopes.mapValues { (scope, layout) ->
                source.getValue(scope)[rowIndex]?.let { row -> layout.contract.columns.mapIndexed { index, field ->
                    field.symbol.name to input(row[index])
                }.toMap(LinkedHashMap()) }
            }
            // null 表示该作用域缺组；Missing 仅表示一个实际存在组中的 NULL 值。
            val completed = linkedMapOf<ScopeId, Map<String, CoreValue>?>()
            try {
                programs.forEach { program ->
                    control.checkCancellation()
                    completed[program.scope] = evaluate(program, sourceValues[program.scope], completed, control)
                }
                val values = completed.getValue(ScopeId.root()) ?: return@forEach
                budget.row()
                rows += outputContract.columns.map { field ->
                    val value = try { materialize(requireNotNull(values[field.symbol.name]), field.nativeType) }
                    catch (failure: CoreArithmeticFailure) {
                        val origin = field.symbol.origin as? QueryStageAnalysis.OperationOrigin
                        val path = origin?.let { listOf<Any>("input") + it.scope.path + listOf("postOps", it.operationIndex, "expression") }.orEmpty()
                        throw CoreFinalizationProblem(failure.code, path, requireNotNull(failure.message), failure)
                    }
                    account(budget, value, 1)
                    value
                }
            } catch (failure: CoreFinalizationProblem) {
                throw EngineExecutionException("CORE_FINALIZATION", failure.code, requireNotNull(failure.message), failure,
                    mapOf("fieldPath" to failure.fieldPath, "rowIndex" to rowIndex))
            } catch (failure: CoreArithmeticFailure) {
                throw EngineExecutionException("CORE_FINALIZATION", failure.code, requireNotNull(failure.message), failure,
                    mapOf("rowIndex" to rowIndex))
            }
        }
        control.checkCancellation()
        val physicalDisplay = frame.columns.associate { it.name to it.displayName }
        val sourceDisplay = sourceLayout.scopes.values.flatMap { scope -> scope.columns.mapNotNull { (name, physical) ->
            physicalDisplay[physical]?.let { name to it }
        } }.toMap()
        return try { ExecutableBusinessResults.materialized(outputContract, rows, sourceDisplay + displayNames, control) }
        catch (failure: CancellationException) { throw failure }
        catch (failure: RuntimeResourceLimitException) { throw failure }
        catch (failure: RuntimeException) {
            throw EngineExecutionException("CORE_FINALIZATION", "CORE_FINALIZATION_OUTPUT_CONTRACT_FAILED",
                "core result differs from its compiled output contract", failure)
        }
    }

    private fun evaluate(program: ScopeProgram, source: Map<String, CoreValue>?,
        completed: Map<ScopeId, Map<String, CoreValue>?>, control: WarehouseExecutionControl): Map<String, CoreValue>? {
        var values = when (val root = program.root) {
            Root.Source -> LinkedHashMap(source ?: return null)
            is Root.Forward -> LinkedHashMap(completed.getValue(root.scope) ?: return null)
            is Root.Projection -> {
                root.observationIdentity?.forEach { key -> root.columns.getValue(key).bindings.forEach { binding ->
                    completed.getValue(binding.scope)?.let { branch ->
                        if (binding.expression.evaluate(branch, control) == CoreValue.Missing)
                            throw CoreFinalizationProblem("OBSERVATION_OBJECT_IDENTITY_NULL",
                                listOf<Any>("input") + program.scope.path + listOf("root", "observationKeys", key),
                                "completed observation identity contains NULL")
                    }
                } }
                val present = root.population.scopes.map { completed.getValue(it) != null }
                val selected = when (root.population.kind) {
                    Calculation.PopulationKind.BASE -> present.single()
                    Calculation.PopulationKind.UNION -> present.any { it }
                    Calculation.PopulationKind.INTERSECTION -> present.all { it }
                }
                if (!selected) return null
                root.columns.mapValuesTo(LinkedHashMap()) { (_, column) ->
                    val binding = column.bindings.firstOrNull { completed.getValue(it.scope) != null }
                    if (binding != null) binding.expression.evaluate(requireNotNull(completed.getValue(binding.scope)), control)
                    else if (column.zeroWhenAbsent) zero(column.type) else CoreValue.Missing
                }
            }
        }
        for (operation in program.operations) {
            control.checkCancellation()
            when (operation) {
                is Operation.Derive -> values[operation.alias] = operation.expression.evaluate(values, control)
                is Operation.Project -> values = operation.names.associateTo(LinkedHashMap()) { it to values.getValue(it) }
                is Operation.Filter -> if ((operation.expression.evaluate(values, control) as? CoreValue.Logical)?.value != true) return null
            }
        }
        return values
    }

    private fun zero(type: CoreScalarType): CoreValue = when (type.kind) {
        CoreScalarType.Kind.NUMBER -> CoreValue.Numeric(CoreExactNumber.decimal(BigDecimal.ZERO))
        CoreScalarType.Kind.PRESERVED -> {
            check(type.storage?.sqlTypeName in setOf(SqlTypeName.REAL, SqlTypeName.FLOAT, SqlTypeName.DOUBLE)) { "absent value zero requires a numeric representation" }
            CoreValue.Preserved(ExecutableBusinessResult.ScalarValue(ExecutableBusinessResult.ValueKind.FLOAT, "0.0"))
        }
        else -> error("absent value zero requires a numeric representation")
    }

    private fun input(value: ExecutableBusinessResult.Value): CoreValue = when (value) {
        is ExecutableBusinessResult.NullValue -> CoreValue.Missing
        is ExecutableBusinessResult.ScalarValue -> when (value.type) {
            ExecutableBusinessResult.ValueKind.INTEGER, ExecutableBusinessResult.ValueKind.DECIMAL -> CoreValue.Numeric(CoreExactNumber.decimal(BigDecimal(value.value)))
            ExecutableBusinessResult.ValueKind.BOOLEAN -> CoreValue.Logical(value.value.toBooleanStrict())
            else -> CoreValue.Preserved(value)
        }
        else -> CoreValue.Preserved(value)
    }
    private fun materialize(value: CoreValue, type: RelDataType): ExecutableBusinessResult.Value = when (value) {
        CoreValue.Missing -> {
            check(type.isNullable || type.sqlTypeName == SqlTypeName.NULL) { "local NULL violates its compiled output type" }
            ExecutableBusinessResult.NullValue()
        }
        is CoreValue.Logical -> ExecutableBusinessResult.ScalarValue(ExecutableBusinessResult.ValueKind.BOOLEAN, value.value.toString())
        is CoreValue.Numeric -> when (type.sqlTypeName) {
            SqlTypeName.TINYINT, SqlTypeName.SMALLINT, SqlTypeName.INTEGER, SqlTypeName.BIGINT -> {
                val integer = value.value.integer()
                ExecutableResultTypes.requireValue(type, integer)
                ExecutableBusinessResult.ScalarValue(ExecutableBusinessResult.ValueKind.INTEGER, integer.toString())
            }
            SqlTypeName.DECIMAL -> {
                val decimal = try { value.value.decimal().setScale(type.scale, RoundingMode.UNNECESSARY) }
                catch (failure: CoreArithmeticFailure) { throw failure }
                catch (failure: ArithmeticException) {
                    throw CoreArithmeticFailure("CORE_FINALIZATION_ROUNDING_REQUIRED", "final decimal representation would discard digits without a quantization node", failure)
                }
                ExecutableResultTypes.requireValue(type, decimal)
                ExecutableBusinessResult.ScalarValue(ExecutableBusinessResult.ValueKind.DECIMAL, decimal.toPlainString())
            }
            else -> error("local numeric output lacks a compiled exact representation")
        }
        is CoreValue.Preserved -> value.value
    }
    private fun account(budget: ResultMaterializationBudget, value: ExecutableBusinessResult.Value, depth: Int) {
        budget.value(depth)
        when (value) {
            is ExecutableBusinessResult.ScalarValue -> budget.scalar(value.value)
            is ExecutableBusinessResult.ArrayValue -> value.value.forEach { account(budget, it, depth + 1) }
            is ExecutableBusinessResult.RowValue -> value.value.forEach { account(budget, it, depth + 1) }
            is ExecutableBusinessResult.NullValue -> Unit
        }
    }

    companion object {
        /** 在来源前缀编译之前拒绝不能留在此执行边界的阶段及表达节点。 */
        fun requireSupportedStages(analyses: Map<ScopeId, QueryStageAnalysis.Analysis>, graph: CoreFinalizationGraph) {
            graph.nodes.forEach { node -> (node.root as? CoreFinalizationGraph.Root.Projection)?.inputs?.forEach { input ->
                input.valueContracts.forEach { (alias, contract) ->
                    if (contract.arithmetic !is MetricValueContract.Exact || contract.result is MetricValueContract.BinaryFloat)
                        CoreFinalizationExpressions.unsupported(listOf<Any>("input") + node.scope.path + listOf("root") + input.position + listOf("outputs", alias, "valueContract"),
                            "a core root calculation call requires an Exact integer or decimal output contract")
                }
            } }
            graph.nodes.forEach { selected ->
                val scope = selected.scope
                requireNotNull(analyses[scope]).stages.drop(selected.stageStart).forEach { stage ->
                val path = listOf<Any>("input") + scope.path + listOf("postOps", stage.index)
                when (val operation = stage.operation) {
                    is PostOperationContract.Project -> Unit
                    is PostOperationContract.Derive, is PostOperationContract.PostFilter -> ResultExpressionTree.visit(
                        if (operation is PostOperationContract.Derive) operation.expression else (operation as PostOperationContract.PostFilter).expression,
                        path + "expression") { node, at ->
                        val supported = when (node) {
                            is ResultSymbol, is ResultLiteral, is ResultNumeric, is ResultQuantityLiteral, is ResultRound, is ResultCaseWhen -> true
                            is ResultTypedLiteral -> node.valueType == ScalarValueType.NUMBER
                            is ResultCall -> QueryExecutionMode.supportsCoreFunction(node.function)
                            is ResultCast -> node.targetType in setOf(com.olap.prototype.expression.SemanticExpression.ValueType.INTEGER,
                                com.olap.prototype.expression.SemanticExpression.ValueType.BIGINT, com.olap.prototype.expression.SemanticExpression.ValueType.DECIMAL,
                                com.olap.prototype.expression.SemanticExpression.ValueType.BOOLEAN)
                            is ResultOperation -> node.operator !in setOf(ResultOperator.TIMESTAMPDIFF, ResultOperator.TIMESTAMPADD, ResultOperator.DATE_TRUNC)
                            else -> false
                        }
                        if (!supported) CoreFinalizationExpressions.unsupported(at, "expression is not supported at the core scalar finalization boundary")
                    }
                    else -> CoreFinalizationExpressions.unsupported(path, "core finalization supports scalar DERIVE, POST_FILTER and PROJECT stages only")
                }
            } }
        }

        fun compile(sourceLayout: ExecutableSourceLayout, fullAnalyses: Map<ScopeId, QueryStageAnalysis.Analysis>,
            graph: CoreFinalizationGraph): CoreFinalizationPlan {
            require(graph.scopes.isNotEmpty() && graph.scopes.last() == ScopeId.root()) { "core execution scopes must end at the actual query root" }
            require(sourceLayout.scopes.keys == graph.sources.map { it.scope }.toSet()) { "core inputs differ from their actual source cuts" }
            requireSupportedStages(fullAnalyses, graph)
            val sourceTypes = sourceLayout.scopes.mapValues { (_, layout) -> layout.contract.columns.associateTo(LinkedHashMap()) {
                it.symbol.name to CoreFinalizationExpressions.sourceType(it.nativeType)
            } }
            val completedTypes = linkedMapOf<ScopeId, Map<String, CoreScalarType>>()
            val completedDisplays = linkedMapOf<ScopeId, Map<String, String>>()
            val inputs = linkedMapOf<ScopeId, ExecutableOutputContract.ScopeInput>()
            sourceLayout.scopes.values.forEach { layout -> layout.contract.scopeInputs.forEach { input ->
                val previous = inputs.putIfAbsent(input.scope, input)
                check(previous == null || previous == input) { "source cuts disagree on the same scope's actual input contract" }
            } }
            val programs = mutableListOf<ScopeProgram>()
            graph.nodes.forEach { node ->
                val scope = node.scope
                val analysis = requireNotNull(fullAnalyses[scope])
                val stages = analysis.stages.drop(node.stageStart)
                val columns = linkedMapOf<String, MutableList<Binding>>()
                val absentZero = linkedSetOf<String>()
                val displays = linkedMapOf<String, String>()
                fun project(dependency: ScopeId, outputSlots: Map<String, String>, projection: CoreFinalizationGraph.InputProjection? = null) {
                    val compiler = CoreFinalizationExpressions(completedTypes.getValue(dependency))
                    outputSlots.forEach { (alias, source) ->
                        val at = listOf<Any>("input") + scope.path + listOf("root") +
                            projection?.position.orEmpty() + listOf("outputs", alias)
                        val symbol = ResultSymbol(source)
                        val contract = projection?.valueContracts?.get(alias)
                        val expression = compiler.compile(if (contract != null) ResultNumeric(symbol, contract, null) else symbol, at)
                        columns.getOrPut(alias) { mutableListOf() } += Binding(dependency, expression)
                        if (projection != null && alias in projection.zeroWhenAbsent) absentZero += alias
                        displays[alias] = projection?.displayNames?.get(alias) ?: completedDisplays[dependency]?.get(source) ?: alias
                    }
                }
                fun projection(population: CoreFinalizationGraph.Population, observationIdentity: List<String>? = null): Root.Projection = Root.Projection(columns.mapValues { (alias, bindings) ->
                    val boundScopes = bindings.map { it.scope }.toSet()
                    val guaranteed = when (population.kind) {
                        Calculation.PopulationKind.BASE -> population.scopes.single() in boundScopes
                        Calculation.PopulationKind.UNION -> population.scopes.all { it in boundScopes }
                        Calculation.PopulationKind.INTERSECTION -> population.scopes.any { it in boundScopes }
                    }
                    Column(bindings.toList(), CoreFinalizationExpressions.projectionType(bindings.map { it.expression.type },
                        bindings.any { it.expression.type.nullable } || !guaranteed && alias !in absentZero,
                        listOf<Any>("input") + scope.path + listOf("root", "outputs", alias)), alias in absentZero)
                }, population, observationIdentity)
                val root = when (val root = node.root) {
                    CoreFinalizationGraph.Root.Source -> Root.Source
                    is CoreFinalizationGraph.Root.Forward -> Root.Forward(root.scope).also { displays.putAll(completedDisplays[root.scope].orEmpty()) }
                    is CoreFinalizationGraph.Root.Relation -> {
                        val relation = root.input.projection
                        relation.outputs.forEach { output ->
                            val storage = completedTypes.getValue(root.input.inputScope).getValue(relation.outputSlots.getValue(output.name)).storage
                            SemanticNativeTypes.requireCompletedRepresentation(requireNotNull(storage) { "completed relation needs an actual source representation" }, output.shape)
                        }
                        project(root.input.inputScope, relation.outputs.associate { it.name to relation.outputSlots.getValue(it.name) })
                        projection(CoreFinalizationGraph.Population(Calculation.PopulationKind.BASE, listOf(root.input.inputScope)))
                    }
                    is CoreFinalizationGraph.Root.Projection -> {
                        root.inputs.forEach { project(it.inputScope, it.outputSlots, it) }
                        projection(root.population, root.observationIdentity)
                    }
                }
                val types = when (root) {
                    Root.Source -> LinkedHashMap(sourceTypes.getValue(scope))
                    is Root.Forward -> LinkedHashMap(completedTypes.getValue(root.scope))
                    is Root.Projection -> root.columns.mapValuesTo(LinkedHashMap()) { it.value.type }
                }
                val startingColumns = if (root == Root.Source) stages.firstOrNull()?.inputs ?: analysis.outputs else analysis.inputs
                check(startingColumns.map { it.name } == types.keys.toList()) { "local input columns differ from the compiled source boundary" }
                if (root == Root.Source) {
                    check(sourceLayout.scopes.getValue(scope).contract.columns.map { it.symbol } == startingColumns) { "source prefix symbols differ from the actual local starting position" }
                    check(inputs.containsKey(scope)) { "source scope must retain its native root input metadata" }
                } else inputs[scope] = ExecutableOutputContract.ScopeInput(scope, analysis.inputs.map { symbol ->
                    ExecutableOutputContract.Field(symbol, requireNotNull(types.getValue(symbol.name).storage))
                })
                val operations = mutableListOf<Operation>()
                stages.forEach { stage ->
                    val path = listOf<Any>("input") + scope.path + listOf("postOps", stage.index)
                    when (val operation = stage.operation) {
                        is PostOperationContract.Derive -> {
                            val expression = CoreFinalizationExpressions(types).compile(operation.expression, path + "expression")
                            val symbol = stage.outputs.first { it.name == operation.outputAlias }
                            val storage = expression.type.storage ?: symbol.quantity?.declaration?.let { declaration ->
                                if (declaration.arithmetic !is MetricValueContract.Exact || declaration.result is MetricValueContract.BinaryFloat)
                                    CoreFinalizationExpressions.unsupported(path + "expression", "core numeric finalization requires an Exact result representation")
                                CoreFinalizationExpressions.resultType(declaration.result, expression.type.nullable)
                            } ?: CoreFinalizationExpressions.unsupported(path + "expression", "local output needs a determined exact numeric or boolean representation")
                            types[operation.outputAlias] = expression.type.copy(storage = storage)
                            displays[operation.outputAlias] = operation.outputDisplayName
                            operations += Operation.Derive(operation.outputAlias, expression)
                        }
                        is PostOperationContract.Project -> {
                            val selected = operation.symbols.associateTo(LinkedHashMap()) { it to types.getValue(it) }
                            types.clear(); types.putAll(selected)
                            displays.keys.retainAll(selected.keys)
                            operations += Operation.Project(operation.symbols)
                        }
                        is PostOperationContract.PostFilter -> {
                            val expression = CoreFinalizationExpressions(types).compile(operation.expression, path + "expression")
                            check(expression.type.kind in setOf(CoreScalarType.Kind.BOOLEAN, CoreScalarType.Kind.NULL)) { "completed filter requires BOOLEAN" }
                            operations += Operation.Filter(expression)
                        }
                        else -> error("local stage was not admitted")
                    }
                }
                check(analysis.outputs.map { it.name } == types.keys.toList()) { "local output columns differ from semantic analysis" }
                completedTypes[scope] = types.toMap()
                completedDisplays[scope] = displays.toMap()
                programs += ScopeProgram(scope, root, operations.toList())
            }
            val root = fullAnalyses.getValue(ScopeId.root())
            val types = completedTypes.getValue(ScopeId.root())
            val contract = ExecutableOutputContract(root.outputs.map { symbol ->
                ExecutableOutputContract.Field(symbol, requireNotNull(types.getValue(symbol.name).storage))
            }, inputs.values.toList(), QueryStageAnalysis.symbols(fullAnalyses.values).values.toList(), ScopeId.root())
            return CoreFinalizationPlan(sourceLayout, contract, programs.toList(), completedDisplays.getValue(ScopeId.root()),
                graph.sources.associate { it.scope to it.stageCount }, graph.scopes)
        }
    }
}
