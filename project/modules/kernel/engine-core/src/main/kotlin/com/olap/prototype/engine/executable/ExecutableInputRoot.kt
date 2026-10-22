package com.olap.prototype.engine.executable

import com.olap.prototype.calculation.Calculation
import com.olap.prototype.query.DimensionCondition
import com.olap.prototype.query.RowPopulationContract
import com.olap.prototype.query.ScopedInputContract
import com.olap.prototype.query.SetOutputContract

/** 绑定完成的业务入口，不携带Rel或物理装配选择。 */
sealed interface ExecutableInputRoot {
    data class Measures(val calls: List<ExecutableMetricCall>, val selection: ExecutableMeasureSelection,
        val population: RowPopulationContract.Selection, val outputCondition: DimensionCondition) : ExecutableInputRoot {
        val grouping get() = selection.grouping
        val groupingBindings get() = selection.groupingBindings
        val groupingLevels get() = selection.groupingLevels
        val timeRange get() = selection.timeRange
        val clocks get() = selection.clocks
    }
    data class Members(val input: ExecutableMemberInput) : ExecutableInputRoot
    data class Input(val inputId: String) : ExecutableInputRoot { init { ScopedInputContract.requireId(inputId) } }
    data class Relation(val input: ExecutableRelationInput) : ExecutableInputRoot
    data class ObjectMeasure(val roles: List<Calculation.ObjectRole>) : ExecutableInputRoot
    data class AlignMeasurements(val observationKeys: List<String>, val keys: Map<String, Map<String, String>>,
        val values: Map<String, List<Calculation.AlignedValue>>, val population: Calculation.AlignmentPopulation) : ExecutableInputRoot
    data class ObservationStatistics(val unit: Calculation.ObservationUnit, val outerKeys: List<String>,
        val statistics: List<Calculation.ObservationStatistic>) : ExecutableInputRoot
    data class SetInputs(val operation: Calculation.SetKind, val all: Boolean, val inputs: List<Calculation.SetProjection>,
        val outputSlots: List<SetOutputContract>) : ExecutableInputRoot
}
