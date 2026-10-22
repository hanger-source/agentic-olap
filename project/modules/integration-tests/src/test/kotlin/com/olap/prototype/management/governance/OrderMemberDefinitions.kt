package com.olap.prototype.management.governance

import com.olap.prototype.calculation.*
import com.olap.prototype.event.RelationAccessPath
import com.olap.prototype.event.EventRef
import com.olap.prototype.member.*
import com.olap.prototype.query.ScalarValueType

internal object OrderMemberDefinitions {
    val slots = mapOf("order" to "order", "amount" to "amount")
    fun projection(shape: MemberShape): MemberSourceDefinition.Projection {
        val local = RelationAccessPath(emptyList())
        return MemberSourceDefinition.Projection(EventRef(GovernanceOrderFixture.EVENT, 1), MemberResult("订单", "order", shape,
            if (shape == MemberShape.RAW_BAG) emptyList() else listOf("order"), mapOf(
                "order" to MemberSourceDefinition.PropertyOutput("订单", "order", ScalarValueType.TEXT, "order_fact.order_id", 1, local, null),
                "amount" to MemberSourceDefinition.PropertyOutput("金额", "amount", ScalarValueType.NUMBER, "order_fact.pay_amount", 1, local, GovernanceOrderFixture.money()),
                "paid" to MemberSourceDefinition.PropertyOutput("支付日期", "business_time", ScalarValueType.DATE, "order_fact.pay_time", 1, local, null)),
            mapOf("amount" to listOf(MemberCondition.Operator.GTE)), "paid", emptyMap(), "真实订单字段投影"))
    }

    fun computed(threshold: String, projectionRange: Boolean): MemberSourceDefinition.Computed {
        val outputs = mapOf("order" to Calculation.MemberProperty("order", ScalarValueType.TEXT, null), "amount" to Calculation.MemberProperty("amount", ScalarValueType.NUMBER, GovernanceOrderFixture.money()))
        val calculation = Calculation(emptyList(), listOf(Calculation.MemberSlot("rows", "订单", "order", MemberShape.RECORDS, outputs, null)), emptyList(), emptyList(), mapOf("window" to Calculation.Parameter(Calculation.ParameterKind.RANGE, "DATE", emptyList(), null, null), threshold to Calculation.Parameter(Calculation.ParameterKind.SCALAR, "NUMBER", emptyList(), null, GovernanceOrderFixture.money())), Calculation.Scope(Calculation.Members("rows", "rows", listOf(Calculation.MemberProjection("order", "order"), Calculation.MemberProjection("amount", "amount")),
            if (projectionRange) Calculation.Argument("window") else null, emptyMap(), MemberCondition.Predicate("amount", MemberCondition.Operator.GTE, listOf(Calculation.Argument(threshold)))), emptyMap(), emptyList()))
        return MemberSourceDefinition.Computed(calculation, MemberResult("订单", "order", MemberShape.RECORDS, listOf("order"), mapOf(
            "order" to MemberSourceDefinition.ComputedPropertyOutput("订单", "order", ScalarValueType.TEXT, "order", null),
            "amount" to MemberSourceDefinition.ComputedPropertyOutput("金额", "amount", ScalarValueType.NUMBER, "amount", GovernanceOrderFixture.money()),
            "candidate_amount" to MemberSourceDefinition.ComputedPropertyOutput("候选金额", "amount", ScalarValueType.NUMBER, "amount", GovernanceOrderFixture.money())),
            mapOf("amount" to listOf(MemberCondition.Operator.GTE)), null, emptyMap(), "完成关系上的订单和金额"))
    }
}
