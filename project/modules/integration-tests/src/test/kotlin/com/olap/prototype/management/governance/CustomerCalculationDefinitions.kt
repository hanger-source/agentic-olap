package com.olap.prototype.management.governance

import com.olap.prototype.calculation.*
import com.olap.prototype.member.*
import com.olap.prototype.metric.MetricValueContract
import com.olap.prototype.query.*

/** 纯作者定义：不访问仓储、不构造引擎，也不由产品结果推导预期答案。 */
internal object CustomerCalculationDefinitions {
    fun approximateMoney() = MetricValueContract(GovernanceOrderFixture.money().unit,
        MetricValueContract.Approximate("-1000000000000", "1000000000000", "0.000000001", "0.000000000001"),
        MetricValueContract.BinaryFloat(64), emptyMap(), MetricValueContract.Overflow.FAIL)

    fun average(paymentMetric: String, strict: Boolean = false): MetricCalculation {
        val slots = listOf(Calculation.DimensionSlot("customer", "global.customer", 1, "customer"), Calculation.DimensionSlot("time", "common.business_date", 1, "business_time"))
        val parameters = mapOf("window" to Calculation.Parameter(Calculation.ParameterKind.RANGE, "DATE", emptyList(), null, null),
            "minimum" to Calculation.Parameter(Calculation.ParameterKind.SCALAR, "NUMBER", emptyList(), null, GovernanceOrderFixture.money()),
            "groups" to Calculation.Parameter(Calculation.ParameterKind.GROUPING, "SEMANTIC_DIMENSIONS", listOf(Calculation.DimensionSlot("destination", "global.destination", 1, "destination")), null, null))
        val customers = Calculation.Scope(Calculation.Members("customers", "customers", listOf(Calculation.MemberProjection("customer", "customer")), null, emptyMap(), MemberCondition.Unrestricted()), emptyMap(), emptyList())
        val stages = listOf(PostOperationContract.JoinInput("eligible", "cohort", "SEMI",
            ResultExpression.ResultOperation(ResultExpression.ResultOperator.EQ, listOf(ResultExpression.ResultSymbol("customer"), ResultExpression.ResultSymbol("cohort__customer")))),
            PostOperationContract.PostFilter(ResultExpression.ResultOperation(if (strict) ResultExpression.ResultOperator.GT else ResultExpression.ResultOperator.GTE,
                listOf(ResultExpression.ResultSymbol("spent"), ResultExpression.ResultArgument("minimum", ScalarValueType.NUMBER)))),
            regroup("spent", "AVG", listOf("\$group:groups"), approximateMoney()))
        return MetricCalculation(Calculation(slots, listOf(Calculation.MemberSlot("customers", "客户", "customer", MemberShape.UNIQUE_MEMBERS,
            mapOf("customer" to Calculation.MemberDimension("customer", null)), null)), emptyList(), emptyList(), parameters, Calculation.Scope(Calculation.Measures(listOf(Calculation.Call("orders", Calculation.Fixed(MetricRef(paymentMetric, "1")), "spent", emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), Calculation.Condition(DimensionCondition.Unrestricted()))),
            listOf("customer"), listOf("groups"), "time", Calculation.Argument("window"), emptyMap(), Calculation.Population(listOf("orders")), null),
            mapOf("eligible" to Calculation.InputScope("RELATION", customers)), stages)), "value", listOf("\$group:groups"), approximateMoney())
    }

    fun parent(child: String, definition: MetricCalculation): MetricCalculation {
        val call = Calculation.Call("qualified", Calculation.Fixed(MetricRef(child, "1")), "value", mapOf("customer" to "customer", "time" to "time"), mapOf("customers" to "customers"), emptyMap(), emptyMap(), mapOf("window" to Calculation.Argument("window"), "minimum" to Calculation.Argument("minimum"), "groups" to Calculation.Argument("groups")), Calculation.Condition(DimensionCondition.Unrestricted()))
        return MetricCalculation(Calculation(definition.calculation.dimensionSlots, definition.calculation.memberSlots, emptyList(), emptyList(), definition.calculation.parameters, Calculation.Scope(Calculation.Measures(listOf(call), emptyList(), listOf("groups"), null, null, emptyMap(), Calculation.Population(listOf("qualified")), null), emptyMap(), emptyList())),
            "value", listOf("\$group:groups"), approximateMoney())
    }

    fun measurement(parameters: Map<String, Calculation.Parameter>, contract: MetricValueContract = approximateMoney()): MetricCalculation = MetricCalculation(
        Calculation(emptyList(), emptyList(), listOf(Calculation.MeasurementSlot("qualified", parameters, emptyList(), listOf("groups"), contract)), emptyList(), parameters, Calculation.Scope(Calculation.Measures(listOf(Calculation.Call("qualified", Calculation.Measurement("qualified"), "value", emptyMap(), emptyMap(), emptyMap(), emptyMap(), parameters.keys.associateWith { Calculation.Argument(it) }, Calculation.Condition(DimensionCondition.Unrestricted()))), emptyList(), listOf("groups"), null, null, emptyMap(), Calculation.Population(listOf("qualified")), null), emptyMap(), emptyList())),
        "value", listOf("\$group:groups"), contract)

    fun amount(aggregation: String, shape: MemberShape): MetricCalculation {
        val outputs = linkedMapOf<String, Calculation.MemberOutput>()
        if (shape != MemberShape.RAW_BAG) outputs["order"] = Calculation.MemberProperty("order", ScalarValueType.TEXT, null)
        outputs["amount"] = Calculation.MemberProperty("amount", ScalarValueType.NUMBER, GovernanceOrderFixture.money())
        val contract = if (aggregation == "AVG") approximateMoney() else GovernanceOrderFixture.money()
        return MetricCalculation(Calculation(emptyList(), listOf(Calculation.MemberSlot("rows", "订单", "order", shape, outputs, null)), emptyList(), emptyList(), emptyMap(), Calculation.Scope(Calculation.Members("rows", "rows", outputs.keys.map { Calculation.MemberProjection(it, it) }, null, emptyMap(), MemberCondition.Unrestricted()), emptyMap(),
            listOf(regroup("amount", aggregation, emptyList(), contract)))), "value", emptyList(), contract)
    }

    fun regroup(input: String, aggregation: String, groups: List<String>, contract: MetricValueContract) = PostOperationContract.Regroup(PostOperationContract.ResultGrouping(groups,
        listOf(PostOperationContract.ResultAggregate(input, when (aggregation) {
            "SUM" -> ResultAggregation.Sum(contract, ResultAggregation.NullPolicy.IGNORE)
            "AVG" -> ResultAggregation.Average(contract, ResultAggregation.NullPolicy.IGNORE, MetricValueContract.Rounding.NONE)
            else -> error("customer amount fixture requires SUM or AVG")
        }, "value", "形成金额")), null))
}
