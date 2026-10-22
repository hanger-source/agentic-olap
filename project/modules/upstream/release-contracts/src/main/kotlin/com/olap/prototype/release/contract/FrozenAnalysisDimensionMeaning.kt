package com.olap.prototype.release.contract

import com.olap.prototype.governance.contract.AnalysisDimensionValueAlignmentRecord

/** 发布引用冻结的维度解释及精确证据，不从当前工作版本重新读取。 */
class FrozenAnalysisDimensionMeaning(val description: String?, valueAlignmentEvidence: List<AnalysisDimensionValueAlignmentRecord>) {
    val valueAlignmentEvidence: List<AnalysisDimensionValueAlignmentRecord> = java.util.List.copyOf(valueAlignmentEvidence)
    override fun equals(other: Any?): Boolean = other is FrozenAnalysisDimensionMeaning && description == other.description && valueAlignmentEvidence == other.valueAlignmentEvidence
    override fun hashCode(): Int = listOf(description, valueAlignmentEvidence).hashCode()
}
