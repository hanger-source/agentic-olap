package com.olap.prototype.server

import com.olap.prototype.query.contract.PublicationContentHash
import com.olap.prototype.query.contract.PublishedQueryEntry
import com.olap.prototype.release.catalog.JdbcPublishedQueryEntryProvider
import com.olap.prototype.util.OlapJsonUtils
import com.olap.prototype.server.ServerInputs.command
import com.olap.prototype.server.ServerInputs.text

/** 精确装载与回执核对；缓存和按字节驱逐属于实际持有目录的worker。 */
internal object WorkerPublications {
    private val mapper = OlapJsonUtils.mapper()

    fun query(worker: RemoteWorkers.Lease, entry: PublishedQueryEntry) {
        val ack = worker.exchange(command("load-capability", mapper.valueToTree(entry)))
        check(text(ack, "catalogHash") == entry.catalogHash && text(ack, "capabilityHash") == entry.capability.capabilityHash) {
            "query capability load receipt differs from exact publication"
        }
    }

    fun engine(worker: RemoteWorkers.Lease, publication: JdbcPublishedQueryEntryProvider.Publication) {
        val catalog = mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(publication.executable)
        val command = mapper.createObjectNode().put("operation", "load-catalog").set<com.fasterxml.jackson.databind.node.ObjectNode>("catalog", catalog)
        val ack = worker.exchange(command)
        check(text(ack, "catalogId") == publication.executable.catalog.manifest.catalogId &&
            text(ack, "contentHash") == PublicationContentHash.compute(catalog)) {
            "engine load receipt differs from exact executable publication"
        }
    }

    fun candidate(worker: RemoteWorkers.Lease, publication: JdbcPublishedQueryEntryProvider.Publication) {
        val load = mapper.createObjectNode().set<com.fasterxml.jackson.databind.node.ObjectNode>("entry", mapper.valueToTree(publication.entry))
            .set<com.fasterxml.jackson.databind.node.ObjectNode>("executable", mapper.valueToTree(publication.executable))
        val ack = worker.exchange(command("load-publication", load))
        val entry = publication.entry
        check(text(ack, "catalogHash") == entry.catalogHash && text(ack, "capabilityHash") == entry.capability.capabilityHash &&
            text(ack, "semanticCatalogHash") == entry.semanticCatalogHash) {
            "candidate publication receipt differs from exact content"
        }
    }
}
