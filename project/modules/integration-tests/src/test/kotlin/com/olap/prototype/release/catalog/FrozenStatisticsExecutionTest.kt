package com.olap.prototype.release.catalog

import com.olap.prototype.calculation.*
import com.olap.prototype.expression.SemanticExpression
import com.olap.prototype.metric.MetricAggregation
import com.olap.prototype.metric.MetricValueContract
import com.olap.prototype.metric.MeanTarget
import com.olap.prototype.event.EventRef
import com.olap.prototype.query.*
import com.olap.prototype.query.PostOperationContract.Derive
import com.olap.prototype.query.PostOperationContract.Project
import com.olap.prototype.query.ResultExpression.*
import com.olap.prototype.query.contract.PostProcessCapability
import com.olap.prototype.query.contract.StageAuthorization
import com.olap.prototype.query.contract.ExpressionAuthorization
import com.olap.prototype.release.catalog.FrozenPublicationFixture.Companion.adoptedInstance
import com.olap.prototype.release.catalog.FrozenPublicationFixture.Companion.call
import com.olap.prototype.release.catalog.FrozenPublicationFixture.Companion.computation
import com.olap.prototype.release.catalog.FrozenPublicationFixture.Companion.execute
import com.olap.prototype.release.catalog.FrozenPublicationFixture.Companion.fact
import com.olap.prototype.release.catalog.FrozenPublicationFixture.Companion.frozen
import com.olap.prototype.release.catalog.FrozenPublicationFixture.Companion.mapper
import com.olap.prototype.release.catalog.FrozenPublicationFixture.Companion.money
import com.olap.prototype.release.catalog.FrozenPublicationFixture.Companion.ref
import com.olap.prototype.release.catalog.FrozenPublicationFixture.Companion.request
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.sqrt

class FrozenStatisticsExecutionTest {
    @Test
    fun savesAndExecutesBothMeanTargetsFromTheFrozenPublication(@TempDir directory: Path) {
        val mathematical = MetricValueContract(money().unit, MetricValueContract.Exact(),
            MetricValueContract.Decimal(28, 12, MetricValueContract.Rounding.HALF_DOWN), emptyMap(), MetricValueContract.Overflow.FAIL)
        val native = MetricValueContract(money().unit, MetricValueContract.Exact(),
            MetricValueContract.Decimal(12, 2, MetricValueContract.Rounding.HALF_UP), emptyMap(), MetricValueContract.Overflow.FAIL)
        FrozenPublicationFixture(directory, "h2", true, listOf("DELETE FROM orders",
            "INSERT INTO orders VALUES (1,'2026-06-01',0.14,7),(2,'2026-06-01',0.15,7),(3,'2026-06-02',0.15,8),(4,'2026-06-03',NULL,9)" )).use { fixture ->
            fun average(target: MeanTarget, contract: MetricValueContract) = MetricDefinition.FactMeasure(EventRef("payment", 1),
                ref("orders.amount"), MetricAggregation.AVG, false, null, emptyMap(), null, contract, target)
            val release = fixture.release(mapOf("mathematical" to average(MeanTarget.MATHEMATICAL, mathematical),
                "source_native" to average(MeanTarget.SOURCE_NATIVE, native)), false)
            // 正式发布序列化及持久查询读取后执行，不能沿用作者内存里的定义或目标。
            val catalog = frozen(release)
            PublishedQueryDefinitionFixture(directory, "mean-targets", release, catalog).use { definitions ->
                val input = request(listOf(call(catalog, "mathematical", "mathematical"),
                    call(catalog, "source_native", "source_native")), timed = false)
                val saved = definitions.service.save(definitions.command("mean_targets", input), "tester")
                val restored = definitions.service.requireByIdAndVersion(saved.definitionId, saved.definitionVersion)
                val rows = execute(fixture, catalog, restored.intent.computation)
                assertEquals(listOf(listOf(BigDecimal("0.44").divide(BigDecimal(3), 12, RoundingMode.HALF_DOWN),
                    BigDecimal("0.44").divide(BigDecimal(3), 2, RoundingMode.HALF_UP))), rows)
            }
        }
    }

    @Test
    fun freezesNativeSampleStatisticsAndReusesThemAtIndependentCallInstances(@TempDir directory: Path) {
        val deviation = MetricValueContract(money().unit, MetricValueContract.Approximate("0", "10000", "1E-9", "1E-12"),
            MetricValueContract.BinaryFloat(64), emptyMap(), MetricValueContract.Overflow.FAIL)
        listOf("h2", "duckdb").forEach { dialect -> FrozenPublicationFixture(directory, dialect, true).use { fixture ->
            val release = fixture.release(mapOf("dispersion" to fact(ref("orders.amount"), MetricAggregation.STDDEV_SAMP, contract = deviation)), false)
            val catalog = frozen(release)
            PublishedQueryDefinitionFixture(directory, "$dialect-statistics", release, catalog).use { definitions ->
                val input = request(listOf(call(catalog, "first", "dispersion"), call(catalog, "second", "dispersion")), timed = false)
                val saved = definitions.service.save(definitions.command("dispersion_query", computation(input)), "tester")
                val rows = execute(fixture, catalog, definitions.service.requireByIdAndVersion(saved.definitionId, saved.definitionVersion).intent.computation)
                assertEquals(1, rows.size)
                assertEquals(2, rows.first().size)
                // 五个观察10、20、30、40、999的精确中心平方差/(n-1)为189860.2。
                rows.first().forEach { assertEquals(sqrt(189860.2), (it as Number).toDouble(), 1E-9, dialect) }
            }
        } }
    }

    @Test
    fun freezesNativeStageConditionsBeforeSavingAndExecutesTheSupportedPublication(@TempDir directory: Path) {
        val floating = MetricValueContract(MetricValueContract.Fixed(MetricValueContract.ONE), MetricValueContract.Approximate("0", "10000", "1E-9", "1E-9"),
            MetricValueContract.BinaryFloat(64), emptyMap(), MetricValueContract.Overflow.FAIL)
        val maximum = fact(SemanticExpression.Cast(ref("orders.amount"), SemanticExpression.ValueType.DOUBLE, null, null, MetricValueContract.Rounding.NONE),
            MetricAggregation.MAX, contract = floating)
        val addition = Derive(ResultNumeric(ResultOperation(ResultOperator.ADD, listOf(ResultSymbol("first"), ResultSymbol("second"))), floating, null), "total", "两个完成标量相加")
        val projectTotal = Project(listOf("total"))
        val derivePermission = PostProcessCapability("derive", "DERIVE", "两个完成标量相加",
            StageAuthorization("DERIVE", emptySet(), emptySet(), ExpressionAuthorization(setOf(ExpressionAuthorization.Kind.NUMERIC,
                ExpressionAuthorization.Kind.OPERATION, ExpressionAuthorization.Kind.SYMBOL), setOf(ResultOperator.ADD), emptySet(), emptySet(), emptySet())), 1,
            null, null, null, null, null, true, false, false, false)
        val projectPermission = PostProcessCapability("project", "PROJECT", "输出完成合计", StageAuthorization.current("PROJECT"), 2, null, null, null, null, null, true, false, false, false)
        listOf("h2", "duckdb").forEach { dialect -> listOf(false, true).forEach { internal -> FrozenPublicationFixture(directory, dialect, true).use { fixture ->
            val completed = Calculation.Measures(listOf(
                Calculation.DefinitionCall("first", MetricRef("maximum", "1")),
                Calculation.DefinitionCall("second", MetricRef("maximum", "1"))), emptyList(), emptyList(), null, emptyMap(), Calculation.Condition(DimensionCondition.Unrestricted()), DimensionCondition.Unrestricted(), emptyList(), RowPopulationContract.calls(listOf("first", "second")), null)
            val factor = MetricValueContract(MetricValueContract.Fixed(MetricValueContract.ONE), MetricValueContract.Exact(),
                MetricValueContract.Decimal(8, 0, MetricValueContract.Rounding.NONE), emptyMap(), MetricValueContract.Overflow.FAIL)
            val scaled = Derive(ResultNumeric(ResultOperation(ResultOperator.MULTIPLY, listOf(ResultSymbol("total"), ResultArgument("factor", ScalarValueType.NUMBER))),
                floating, null), "scaled", "实参缩放后的合计")
            val calculation = MetricCalculation(Calculation(emptyList(), emptyList(), emptyList(), emptyList(), mapOf("factor" to Calculation.Parameter(
                Calculation.ParameterKind.SCALAR, "NUMBER", emptyList(), null, factor)), Calculation.Scope(completed, emptyMap(), listOf(addition, scaled, Project(listOf("scaled"))))),
                "scaled", emptyList(), floating)
            val release = fixture.release(if (internal) mapOf("maximum" to maximum, "complete_total" to calculation) else mapOf("maximum" to maximum), false)
            val catalog = frozen(release, if (internal) emptyList() else listOf(derivePermission, projectPermission))
            val computation = if (internal) computation(request(listOf(Calculation.InstanceCall("completed", adoptedInstance(catalog, "complete_total"), outputAlias = "total", arguments = mapOf("factor" to Calculation.Scalar(ScalarValueType.NUMBER, "2")))), timed = false)) else {
                val root = computation(request(listOf(call(catalog, "first", "maximum"), call(catalog, "second", "maximum")), timed = false)).root
                Calculation.Scope(root, emptyMap(), listOf(addition, projectTotal))
            }
            PublishedQueryDefinitionFixture(directory, "$dialect-$internal-stages", release, catalog).use { definitions ->
                val saved = definitions.service.save(definitions.command("native_total", computation), "tester")
                val restored = definitions.service.requireByIdAndVersion(saved.definitionId, saved.definitionVersion)
                val rows = execute(fixture, catalog, restored.intent.computation)
                assertEquals(1, rows.size)
                // 两个完成MAX均为999；外层加法为1998，内部再明确消费factor=2为3996。
                assertEquals(if (internal) 3996.0 else 1998.0, (rows.first().first() as Number).toDouble(), 1E-9, dialect)
            }
        } } }
    }
}
