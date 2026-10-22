package com.olap.prototype.query.contract

import com.olap.prototype.contract.NativeValueType
import com.olap.prototype.contract.ProducerContracts
import com.olap.prototype.query.ScopedInputContract
import java.util.Collections
import java.util.TreeMap
import java.util.TreeSet

/** 发布的精确技术类型事实，不保存另一套支持政策。 */
class QueryExecutionTypes(val formatVersion: String, metricInstances: Map<String, ContextBinding>, members: Map<String, Member>, dimensions: List<Dimension>) {
    val metricInstances: Map<String, ContextBinding> = Collections.unmodifiableMap(TreeMap(metricInstances))
    val members: Map<String, Member> = Collections.unmodifiableMap(TreeMap(members))
    val dimensions: List<Dimension> = java.util.List.copyOf(dimensions)
    init { require(formatVersion == FORMAT_VERSION) { "unknown query execution types format" } }
    data class Context(val executionGroup: String, val dialect: String,  val constraintSnapshotAvailable: Boolean) {
        init { ProducerContracts.requireText(executionGroup, "published execution group"); ProducerContracts.requireText(dialect, "published execution dialect") }
    }


    sealed interface ContextBinding
    data class FixedContext(val context: Context) : ContextBinding
    class Inputs(memberParameters: Set<String>, relationParameters: Set<String>) : ContextBinding {
        val memberParameters: Set<String> = Collections.unmodifiableSet(TreeSet(memberParameters))
        val relationParameters: Set<String> = Collections.unmodifiableSet(TreeSet(relationParameters))
        init {
            require(this.memberParameters.size + this.relationParameters.size in 1..ScopedInputContract.MAX_INPUTS) { "context selection requires explicit bounded member or relation parameters" }
            this.memberParameters.forEach { ScopedInputContract.requireId(it) }
            this.relationParameters.forEach { ScopedInputContract.requireId(it) }
        }
        override fun equals(other: Any?): Boolean = other is Inputs && memberParameters == other.memberParameters && relationParameters == other.relationParameters
        override fun hashCode(): Int = listOf(memberParameters, relationParameters).hashCode()
    }


    sealed interface Member
    class ProjectionMember(val context: Context, outputs: Map<String, NativeValueType>) : Member {
        val outputs: Map<String, NativeValueType> = Collections.unmodifiableMap(TreeMap(outputs))
        override fun equals(other: Any?): Boolean = other is ProjectionMember && context == other.context && outputs == other.outputs
        override fun hashCode(): Int = listOf(context, outputs).hashCode()
    }
    data class ComputedMember(val context: ContextBinding) : Member
    data class Dimension(val dimension: ScopedInputContract.DimensionRef, val eventId: String, val eventVersion: Long, val context: Context, val type: NativeValueType) {
        init { ProducerContracts.requireText(eventId, "dimension event"); ProducerContracts.requireVersion(eventVersion, "dimension event version") }
    }
    override fun equals(other: Any?): Boolean = other is QueryExecutionTypes && formatVersion == other.formatVersion && metricInstances == other.metricInstances && members == other.members && dimensions == other.dimensions
    override fun hashCode(): Int = listOf(formatVersion, metricInstances, members, dimensions).hashCode()
    companion object { const val FORMAT_VERSION = "olap-query-execution-types/v5" }
}
