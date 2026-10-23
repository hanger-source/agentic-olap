package com.olap.prototype.server

import com.olap.prototype.query.contract.PublishedQueryEntry
import com.olap.prototype.release.catalog.JdbcPublishedQueryEntryProvider
import com.olap.prototype.runtime.CandidateWorkerCommand
import com.olap.prototype.runtime.CandidateWorkerResult
import com.olap.prototype.runtime.EngineWorkerCommand
import com.olap.prototype.runtime.EngineWorkerResult
import com.olap.prototype.runtime.QueryWorkerCommand
import com.olap.prototype.runtime.QueryWorkerResult

/** 精确装载与回执核对；缓存和按字节驱逐属于实际持有目录的worker。 */
internal object WorkerPublications {
    fun query(worker: RemoteWorkers.Lease, entry: PublishedQueryEntry) {
        val present = worker.query(QueryWorkerCommand.Lookup(PublishedQueryEntry.key(entry.capability), entry.catalogHash,
            requireNotNull(entry.capability.capabilityHash), entry.semanticCatalogHash)) as? QueryWorkerResult.Present
            ?: error("query worker omitted its exact publication presence")
        if (present.value) return
        val ack = worker.query(QueryWorkerCommand.Load(entry)) as? QueryWorkerResult.Loaded ?: error("query worker returned a different command receipt")
        check(ack.coordinate == PublishedQueryEntry.key(entry.capability) && ack.catalogHash == entry.catalogHash && ack.capabilityHash == entry.capability.capabilityHash) {
            "query capability load receipt differs from exact publication"
        }
    }

    fun engine(worker: RemoteWorkers.Lease, publication: JdbcPublishedQueryEntryProvider.Publication) {
        val present = worker.engine(EngineWorkerCommand.Lookup(publication.executable.catalog.manifest.catalogId, publication.entry.catalogHash)) as? EngineWorkerResult.Present
            ?: error("engine worker omitted its exact publication presence")
        if (present.value) return
        val ack = worker.engine(EngineWorkerCommand.Load(publication.executable)) as? EngineWorkerResult.Loaded ?: error("engine worker returned a different command receipt")
        check(ack.catalogId == publication.executable.catalog.manifest.catalogId && ack.contentHash == publication.entry.catalogHash) {
            "engine load receipt differs from exact executable publication"
        }
    }

    fun candidate(worker: RemoteWorkers.Lease, publication: JdbcPublishedQueryEntryProvider.Publication) {
        val entry = publication.entry
        val identity = com.olap.prototype.runtime.CandidatePublicationIdentity(publication.executable.coordinate, entry.catalogHash,
            requireNotNull(entry.capability.capabilityHash), entry.semanticCatalogHash)
        val present = worker.candidate(CandidateWorkerCommand.Lookup(identity)) as? CandidateWorkerResult.Present
            ?: error("candidate worker omitted its exact publication presence")
        if (present.value) return
        val ack = (worker.candidate(CandidateWorkerCommand.Load(publication.entry, publication.executable)) as? CandidateWorkerResult.Loaded)?.identity
            ?: error("candidate worker returned a different publication receipt")
        check(ack.coordinate == publication.executable.coordinate && ack.catalogHash == entry.catalogHash && ack.capabilityHash == entry.capability.capabilityHash &&
            ack.semanticCatalogHash == entry.semanticCatalogHash) {
            "candidate publication receipt differs from exact content"
        }
    }
}
