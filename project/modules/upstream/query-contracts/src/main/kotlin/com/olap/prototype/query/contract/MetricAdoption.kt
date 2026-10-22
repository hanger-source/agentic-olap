package com.olap.prototype.query.contract

import com.fasterxml.jackson.annotation.JsonIgnore
import com.olap.prototype.calculation.Calculation
import com.olap.prototype.calculation.CalculationBindings
import com.olap.prototype.calculation.MetricDefinition
import com.olap.prototype.calculation.MetricInstance
import com.olap.prototype.calculation.MetricInstanceRef
import com.olap.prototype.calculation.MetricRef
import com.olap.prototype.metric.MetricBusinessMeaning
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat

/** 业务名称限定在采用上下文，内核实例身份仅由精确采用坐标派生。 */
class MetricAdoption(val adoptionId: String, val subjectAreaId: String, val subjectAreaVersion: Long,
    val code: String, val displayName: String, val businessMeaning: MetricBusinessMeaning, val definition: MetricRef,
    val bindings: CalculationBindings, fixedArguments: Map<String, Calculation.Value>) {
    val fixedArguments: Map<String, Calculation.Value> = java.util.Map.copyOf(fixedArguments)
    init {
        MetricDefinition.text(adoptionId, "adoptionId"); MetricDefinition.text(subjectAreaId, "subjectAreaId"); MetricDefinition.version(subjectAreaVersion)
        MetricDefinition.text(code, "adoption code"); MetricDefinition.text(displayName, "adoption displayName")
        require(adoptionId == identity(subjectAreaId, code)) { "adoption identity differs from its owning context and code" }
    }
    @get:JsonIgnore val instance: MetricInstanceRef get() = instance(adoptionId, subjectAreaVersion)
    @get:JsonIgnore val computation: MetricInstance get() = MetricInstance(definition, bindings, fixedArguments, displayName)
    override fun equals(other: Any?): Boolean = other is MetricAdoption && adoptionId == other.adoptionId && subjectAreaId == other.subjectAreaId && subjectAreaVersion == other.subjectAreaVersion && code == other.code && displayName == other.displayName && businessMeaning == other.businessMeaning && definition == other.definition && bindings == other.bindings && fixedArguments == other.fixedArguments
    override fun hashCode(): Int = listOf(adoptionId, subjectAreaId, subjectAreaVersion, code, displayName, businessMeaning, definition, bindings, fixedArguments).hashCode()
    companion object {
        fun identity(subjectAreaId: String, code: String): String = "metric-adoption-${hash(subjectAreaId, code)}"
        fun instance(adoptionId: String, adoptionVersion: Long): MetricInstanceRef {
            MetricDefinition.version(adoptionVersion)
            return MetricInstanceRef("metric-instance-${hash(adoptionId, adoptionVersion.toString())}")
        }
        private fun hash(first: String, second: String): String {
            MetricDefinition.text(first, "identity component"); MetricDefinition.text(second, "identity component")
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { output -> listOf(first, second).forEach { value ->
                val encoded = value.toByteArray(StandardCharsets.UTF_8)
                output.writeInt(encoded.size); output.write(encoded)
            } }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()))
        }
    }
}
