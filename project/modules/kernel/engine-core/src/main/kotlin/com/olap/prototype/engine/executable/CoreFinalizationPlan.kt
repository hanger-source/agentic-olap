package com.olap.prototype.engine.executable

import com.olap.prototype.api.ResultFrame
import com.olap.prototype.connector.CompilationSupport
import com.olap.prototype.engine.EngineExecutionException
import com.olap.prototype.metric.MetricValueContract
import com.olap.prototype.query.PostOperationContract
import com.olap.prototype.query.QueryStageAnalysis
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
class CoreFinalizationPlan private constructor(private val sourceContract: ExecutableOutputContract,
    val outputContract: ExecutableOutputContract, private val operations: List<Operation>, private val displayNames: Map<String, String>, scopes: List<ScopeId>) {
    val scopes: List<ScopeId> = java.util.List.copyOf(scopes)
    private sealed interface Operation {
        data class Derive(val alias: String, val expression: CoreExpression) : Operation
        data class Project(val names: List<String>) : Operation
        data class CallProjection(val columns: Map<String, CoreExpression>) : Operation
    }

    fun execute(frame: ResultFrame, support: CompilationSupport, control: WarehouseExecutionControl,
        limits: ResultMaterializationLimits): ExecutableBusinessResult {
        control.checkCancellation()
        val source = try { ExecutableBusinessResults.produce(frame, sourceContract, support) }
        catch (failure: CancellationException) { throw failure }
        catch (failure: RuntimeResourceLimitException) { throw failure }
        catch (failure: RuntimeException) {
            throw EngineExecutionException("MATERIALIZE_SOURCE_RESULT", "CORE_FINALIZATION_SOURCE_CONTRACT_FAILED",
                "source cut result differs from its compiled input contract", failure)
        }
        val budget = ResultMaterializationBudget(limits)
        val rows = source.rows.mapIndexed { rowIndex, sourceRow ->
            control.checkCancellation()
            budget.row()
            var values = source.columns.mapIndexed { index, column -> column.symbol.name to input(sourceRow[index]) }.toMap(LinkedHashMap())
            try {
                operations.forEach { operation ->
                    control.checkCancellation()
                    when (operation) {
                        is Operation.Derive -> values[operation.alias] = operation.expression.evaluate(values, control)
                        is Operation.Project -> values = operation.names.associateTo(LinkedHashMap()) { it to requireNotNull(values[it]) }
                        is Operation.CallProjection -> values = operation.columns.mapValuesTo(LinkedHashMap()) { (_, expression) -> expression.evaluate(values, control) }
                    }
                }
                outputContract.columns.map { field ->
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
        val sourceDisplay = source.columns.mapNotNull { column -> column.displayName?.let { column.symbol.name to it } }.toMap()
        return try { ExecutableBusinessResults.materialized(outputContract, rows, sourceDisplay + displayNames, control) }
        catch (failure: CancellationException) { throw failure }
        catch (failure: RuntimeResourceLimitException) { throw failure }
        catch (failure: RuntimeException) {
            throw EngineExecutionException("CORE_FINALIZATION", "CORE_FINALIZATION_OUTPUT_CONTRACT_FAILED",
                "core result differs from its compiled output contract", failure)
        }
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
        fun requireSupportedStages(analyses: Map<ScopeId, QueryStageAnalysis.Analysis>, path: CoreFinalizationPath) {
            path.calls.forEach { (scope, call) ->
                if (call.valueContract.arithmetic !is MetricValueContract.Exact || call.valueContract.result is MetricValueContract.BinaryFloat)
                    CoreFinalizationExpressions.unsupported(listOf<Any>("input") + scope.path + listOf("root", "calls", call.callId, "valueContract"),
                        "a core root calculation call requires an Exact integer or decimal output contract")
            }
            path.scopes.forEach { scope -> requireNotNull(analyses[scope]).stages.forEach { stage ->
                val path = listOf<Any>("input") + scope.path + listOf("postOps", stage.index)
                when (val operation = stage.operation) {
                    is PostOperationContract.Project -> Unit
                    is PostOperationContract.Derive -> ResultExpressionTree.visit(operation.expression, path + "expression") { node, at ->
                        val supported = when (node) {
                            is ResultSymbol, is ResultLiteral, is ResultNumeric, is ResultQuantityLiteral, is ResultRound, is ResultCaseWhen -> true
                            is ResultTypedLiteral -> node.valueType == ScalarValueType.NUMBER
                            is ResultCast -> node.targetType in setOf(com.olap.prototype.expression.SemanticExpression.ValueType.INTEGER,
                                com.olap.prototype.expression.SemanticExpression.ValueType.BIGINT, com.olap.prototype.expression.SemanticExpression.ValueType.DECIMAL,
                                com.olap.prototype.expression.SemanticExpression.ValueType.BOOLEAN)
                            is ResultOperation -> node.operator !in setOf(ResultOperator.TIMESTAMPDIFF, ResultOperator.TIMESTAMPADD, ResultOperator.DATE_TRUNC)
                            else -> false
                        }
                        if (!supported) CoreFinalizationExpressions.unsupported(at, "expression is not supported at the core scalar finalization boundary")
                    }
                    else -> CoreFinalizationExpressions.unsupported(path, "core finalization supports DERIVE and PROJECT stages only")
                }
            } }
        }

        fun compile(sourceContract: ExecutableOutputContract, fullAnalyses: Map<ScopeId, QueryStageAnalysis.Analysis>,
            path: CoreFinalizationPath): CoreFinalizationPlan {
            val scopes = path.scopes
            require(scopes.isNotEmpty() && scopes.distinct().size == scopes.size && scopes.last() == ScopeId.root()) { "core execution scopes must end at the actual query root" }
            require(scopes.first() == path.sourceScope) { "core execution must begin at its actual source cut" }
            require(sourceContract.outputScope == path.sourceScope) { "core input contract differs from its actual source cut" }
            requireSupportedStages(fullAnalyses, path)
            val types = sourceContract.columns.associateTo(LinkedHashMap()) { it.symbol.name to CoreFinalizationExpressions.sourceType(it.nativeType) }
            val displays = linkedMapOf<String, String>()
            val inputs = sourceContract.scopeInputs.associateByTo(LinkedHashMap()) { it.scope }
            val operations = mutableListOf<Operation>()
            scopes.forEach { scope ->
                val analysis = requireNotNull(fullAnalyses[scope])
                path.calls[scope]?.let { call ->
                    val compiler = CoreFinalizationExpressions(types)
                    val columns = call.outputSlots.mapValuesTo(LinkedHashMap()) { (alias, source) ->
                        val at = listOf<Any>("input") + scope.path + listOf("root", "calls", call.callId, "outputs", alias)
                        val symbol = ResultSymbol(source)
                        compiler.compile(if (alias == call.measureAlias) ResultNumeric(symbol, call.valueContract, null) else symbol, at)
                    }
                    types.clear()
                    columns.forEach { (alias, expression) -> types[alias] = expression.type }
                    displays.clear(); call.outputSlots.keys.forEach { displays[it] = it }; displays[call.measureAlias] = call.displayName
                    operations += Operation.CallProjection(columns)
                }
                check(analysis.inputs.map { it.name } == types.keys.toList()) { "local input columns differ from the compiled source boundary" }
                inputs[scope] = ExecutableOutputContract.ScopeInput(scope, analysis.inputs.map { symbol ->
                    ExecutableOutputContract.Field(symbol, requireNotNull(types.getValue(symbol.name).storage))
                })
                analysis.stages.forEach { stage ->
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
                        else -> error("local stage was not admitted")
                    }
                }
                check(analysis.outputs.map { it.name } == types.keys.toList()) { "local output columns differ from semantic analysis" }
            }
            val root = fullAnalyses.getValue(ScopeId.root())
            val contract = ExecutableOutputContract(root.outputs.map { symbol ->
                ExecutableOutputContract.Field(symbol, requireNotNull(types.getValue(symbol.name).storage))
            }, inputs.values.toList(), QueryStageAnalysis.symbols(fullAnalyses.values).values.toList(), ScopeId.root())
            return CoreFinalizationPlan(sourceContract, contract, operations.toList(), displays.toMap(), scopes)
        }
    }
}
