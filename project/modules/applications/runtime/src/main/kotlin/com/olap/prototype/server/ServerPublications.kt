package com.olap.prototype.server

import com.fasterxml.jackson.databind.JsonNode
import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.query.contract.PublishedQueryProjection
import com.olap.prototype.query.contract.catalog.PublishedSemanticCatalogReader
import com.olap.prototype.query.contract.QueryCapabilityRequest
import com.olap.prototype.release.catalog.JdbcPublishedQueryEntryProvider
import com.olap.prototype.server.ServerInputs.fields
import com.olap.prototype.server.ServerInputs.text
import com.olap.prototype.server.ServerInputs.integer
import com.olap.prototype.server.ServerInputs.optionalText
import com.olap.prototype.util.OlapJsonUtils

internal class ServerPublications(private val publications: JdbcPublishedQueryEntryProvider,
    private val authorization: ServerAuthorization) {
    private val mapper = OlapJsonUtils.mapper()

    fun list(request: ServerRequest, payload: JsonNode): Any {
        fields(payload, setOf("offset", "limit", "subjectAreaId", "releaseId"))
        val scopes = authorization.matching(request.principal, "list-publications").map {
            JdbcPublishedQueryEntryProvider.Scope(if ("*" in it.subjectAreas) null else it.subjectAreas.toSet(),
                if ("*" in it.releases) null else it.releases.toSet())
        }
        return publications.list(integer(payload, "offset", 0, 0, Int.MAX_VALUE),
            integer(payload, "limit", 50, 1, 200), optionalText(payload, "subjectAreaId"),
            optionalText(payload, "releaseId"), scopes)
    }

    fun read(request: ServerRequest, payload: JsonNode): Any {
        val entry = publications.require(mapper.treeToValue(payload, QueryCapabilityRequest::class.java))
        authorization.publication(request.principal, "read-publication", entry)
        return PublishedQueryProjection.produce(entry)
    }

    fun semantics(operation: String, request: ServerRequest, payload: JsonNode): Any {
        val reference: JsonNode?
        val coordinate: JsonNode
        if (operation == "search-semantics") {
            fields(payload, setOf("coordinate", "query", "kind", "offset", "limit"))
            coordinate = payload.required("coordinate"); reference = null
        } else {
            fields(payload, when (operation) {
                "read-semantic-asset" -> setOf("assetReference")
                "read-semantic-definition" -> setOf("catalogRef", "kind", "reference")
                else -> setOf("catalogRef")
            })
            reference = if (operation == "read-semantic-asset") payload.required("assetReference").required("catalogRef") else payload.required("catalogRef")
            fields(reference, setOf("coordinate", "sha256")); coordinate = reference.required("coordinate")
        }
        val entry = publications.require(mapper.treeToValue(coordinate, QueryCapabilityRequest::class.java))
        authorization.publication(request.principal, operation, entry)
        if (reference != null && entry.semanticCatalogHash != text(reference, "sha256"))
            throw ProducerContractException("exact semantic catalog hash is unavailable")
        return PublishedSemanticCatalogReader(entry.semanticCatalog).read(operation, payload)
    }
}
