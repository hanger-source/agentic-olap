package com.olap.prototype.release.catalog

import com.olap.prototype.codec.GovernanceProto
import com.olap.prototype.codec.ProtoBytes
import com.olap.prototype.calculation.MetricInstanceRef
import com.olap.prototype.calculation.MetricRef
import com.olap.prototype.calculation.MetricSource
import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.contract.ProducerContracts
import com.olap.prototype.contract.NativeTableDependency
import com.olap.prototype.member.MemberRef
import com.olap.prototype.query.contract.MetricAdoption
import com.olap.prototype.query.contract.PublishedMemberSource
import com.olap.prototype.release.contract.*
import com.olap.prototype.event.PublishedDimensionBinding
import com.olap.prototype.query.contract.PublishedRelationFilter
import java.util.Collections

/** 一次冻结读取的精确引用与解码缓存；原引用顺序仍由发布目录拥有。 */
class ReleaseObjectIndex(objects: List<ReleaseObjectRef>) {
    private data class Key(val type: String, val id: String, val version: Long)
    private data class Decoded(val reference: Key, val type: Class<*>)
    val objects: List<ReleaseObjectRef> = java.util.List.copyOf(objects)
    private val references: Map<Key, ReleaseObjectRef>
    private val decoded = hashMapOf<Decoded, Any>()
    private val instances: Map<MetricInstanceRef, MetricAdoption> by lazy(LazyThreadSafetyMode.NONE) {
        Collections.unmodifiableMap(linkedMapOf<MetricInstanceRef, MetricAdoption>().apply {
            this@ReleaseObjectIndex.objects.asSequence().filter { it.objectType == "METRIC_ADOPTION" }.forEach { ref ->
                val adoption = exact("METRIC_ADOPTION", ref.objectId, ref.objectVersion, MetricAdoption::class.java)
                requireAdoptionIdentity(adoption, ref.objectId, ref.objectVersion)
                if (putIfAbsent(adoption.instance, adoption) != null) throw ProducerContractException("duplicate exact frozen metric instance")
            }
        })
    }
    init {
        references = Collections.unmodifiableMap(linkedMapOf<Key, ReleaseObjectRef>().apply {
            this@ReleaseObjectIndex.objects.forEach { reference ->
                val key = Key(reference.objectType, reference.objectId, reference.objectVersion)
                if (putIfAbsent(key, reference) != null) throw ProducerContractException("duplicate exact frozen source object: $key")
            }
        })
    }
    fun require(type: String, id: String, version: Long): ReleaseObjectRef = references[Key(type, id, version)]
        ?: throw ProducerContractException("exact frozen source object is missing: $type:$id@$version")
    fun <T : Any> read(type: String, id: String, version: Long, representation: Class<T>): T {
        val key = Decoded(Key(type, id, version), representation)
        val value = decoded.getOrPut(key) { decode(require(type, id, version).snapshotProto, representation) }
        return representation.cast(value)
    }
    fun <T : Any> read(reference: ReleaseObjectRef, representation: Class<T>): T = read(reference.objectType, reference.objectId, reference.objectVersion, representation)
    fun metric(reference: MetricRef): MetricExecutionSnapshot = exact("METRIC", reference.id, reference.version.toLong(), MetricExecutionSnapshot::class.java)
    fun member(reference: MemberRef): PublishedMemberSource = exact("MEMBER_SOURCE", reference.id, reference.version, PublishedMemberSource::class.java).also {
        if (it.source.id != reference.id || it.source.version != reference.version) throw ProducerContractException("frozen member differs from its exact reference")
    }
    fun scenarioAdoption(reference: ScenarioMetricReference): MetricAdoption = exact("METRIC_ADOPTION", reference.adoptionId, reference.adoptionVersion, MetricAdoption::class.java).also {
        requireAdoptionIdentity(it, reference.adoptionId, reference.adoptionVersion)
    }
    fun instance(reference: MetricInstanceRef): MetricAdoption {
        val adoption = instances[reference] ?: throw ProducerContractException("exact adopted measurement instance is not frozen: $reference")
        metric(adoption.definition)
        return adoption
    }
    fun adoptions(roots: Collection<MetricInstanceRef>): Map<String, MetricAdoption> {
        val contexts = roots.map { instance(it).let { adoption -> "${adoption.subjectAreaId}@${adoption.subjectAreaVersion}" } }.toSet()
        if (contexts.size > 1) throw ProducerContractException("root metrics belong to different authorization contexts")
        val memberRoots = objects.filter { it.objectType == "MEMBER_SOURCE" }.sortedBy { "${it.objectType}:${it.objectId}@${it.objectVersion}" }.map { MemberRef(it.objectId, it.objectVersion) }
        val closure = MetricSource.closure(roots, memberRoots, { metric(it).definition }, { instance(it).computation }, { member(it).source })
        val result = linkedMapOf<String, MetricAdoption>()
        closure.dependencies.filterIsInstance<MetricSource.InstanceDependency>().forEach { dependency ->
            val adoption = instance(dependency.reference)
            if (contexts.isNotEmpty() && "${adoption.subjectAreaId}@${adoption.subjectAreaVersion}" !in contexts) throw ProducerContractException("frozen measurement belongs to another authorization context")
            if (result.putIfAbsent(adoption.adoptionId, adoption) != null) throw ProducerContractException("frozen adoption identity is ambiguous")
        }
        return Collections.unmodifiableMap(result)
    }
    private fun requireAdoptionIdentity(adoption: MetricAdoption, id: String, version: Long) {
        if (adoption.adoptionId != id || adoption.subjectAreaVersion != version) throw ProducerContractException("frozen adoption differs from its exact reference")
    }
    private fun <T : Any> exact(kind: String, id: String, version: Long, representation: Class<T>): T = try { read(kind, id, version, representation) }
    catch (failure: com.google.protobuf.InvalidProtocolBufferException) { throw ProducerContractException("invalid exact frozen $kind", failure) }

    /** 读取点选择固定正式消息；持久化内容不能指定类或选择其他解码器。 */
    private fun decode(stored: String?, representation: Class<*>): Any {
        val bytes = ProtoBytes.stored(ProducerContracts.requireText(stored, "frozen snapshot"))
        return when (representation) {
            MetricAdoption::class.java -> GovernanceProto.fromProto(ProtoBytes.decode(bytes, com.olap.prototype.wire.governance.MetricAdoption.getDefaultInstance()))
            PublishedMemberSource::class.java -> GovernanceProto.fromProto(ProtoBytes.decode(bytes, com.olap.prototype.wire.governance.PublishedMemberSource.getDefaultInstance()))
            MetricExecutionSnapshot::class.java -> GovernanceProto.fromProto(ProtoBytes.decode(bytes, com.olap.prototype.wire.governance.MetricExecutionSnapshot.getDefaultInstance()))
            EventExecutionSnapshot::class.java -> GovernanceProto.fromProto(ProtoBytes.decode(bytes, com.olap.prototype.wire.governance.EventExecutionSnapshot.getDefaultInstance()))
            LogicUnitExecutionSnapshot::class.java -> GovernanceProto.fromProto(ProtoBytes.decode(bytes, com.olap.prototype.wire.governance.LogicUnitExecutionSnapshot.getDefaultInstance()))
            TopologyExecutionSnapshot::class.java -> GovernanceProto.fromProto(ProtoBytes.decode(bytes, com.olap.prototype.wire.governance.TopologyExecutionSnapshot.getDefaultInstance()))
            PublishedDimensionBinding::class.java -> GovernanceProto.fromProto(ProtoBytes.decode(bytes, com.olap.prototype.wire.governance.PublishedDimensionBinding.getDefaultInstance()))
            ReleaseJoinPathSnapshot::class.java -> GovernanceProto.fromProto(ProtoBytes.decode(bytes, com.olap.prototype.wire.governance.ReleaseJoinPathSnapshot.getDefaultInstance()))
            FrozenAnalysisDimensionMeaning::class.java -> GovernanceProto.fromProto(ProtoBytes.decode(bytes, com.olap.prototype.wire.governance.FrozenAnalysisDimensionMeaning.getDefaultInstance()))
            PublishedRelationFilter::class.java -> GovernanceProto.fromProto(ProtoBytes.decode(bytes, com.olap.prototype.wire.governance.PublishedRelationFilter.getDefaultInstance()))
            NativeTableDependency::class.java -> GovernanceProto.fromProto(ProtoBytes.decode(bytes, com.olap.prototype.wire.governance.NativeTableDependency.getDefaultInstance()))
            else -> throw ProducerContractException("unsupported frozen representation: ${representation.simpleName}")
        }
    }
}
