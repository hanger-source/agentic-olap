package com.olap.prototype.server

import com.fasterxml.jackson.databind.JsonNode
import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.dsl.transport.CapabilityRegistry
import com.olap.prototype.query.contract.*
import com.olap.prototype.query.contract.catalog.PublishedSemanticCatalogReader
import com.olap.prototype.query.contract.catalog.SemanticCatalogReads.SearchRequest
import com.olap.prototype.query.contract.catalog.SemanticCatalogReads.CatalogRequest
import com.olap.prototype.query.contract.catalog.SemanticCatalogReads.AssetRequest
import com.olap.prototype.query.contract.catalog.SemanticCatalogReads.DefinitionRequest
import com.olap.prototype.release.catalog.JdbcPublishedQueryEntryProvider
import com.olap.prototype.server.ServerInputs.fields
import com.olap.prototype.server.ServerInputs.integer
import com.olap.prototype.server.ServerInputs.optionalText

/** 发布读取按精确场景授权，不把查询使用者升级为共享治理作者。 */
internal class ServerPublications(private val publications: JdbcPublishedQueryEntryProvider,
    private val authorization: ServerAuthorization) {
    fun list(request: ServerRequest, payload: JsonNode): Any {
        fields(payload,setOf("offset","limit","subjectAreaId","releaseId"))
        val scopes=authorization.matching(request.principal,"list-publications").map {
            JdbcPublishedQueryEntryProvider.Scope(if ("*" in it.subjectAreas) null else it.subjectAreas.toSet(),
                if ("*" in it.releases) null else it.releases.toSet())
        }
        return publications.list(integer(payload,"offset",0,0,Int.MAX_VALUE),integer(payload,"limit",50,1,200),
            optionalText(payload,"subjectAreaId"),optionalText(payload,"releaseId"),scopes)
    }
    fun invoke(operation: String, request: ServerRequest, input: Any): Any {
        val coordinate=when(input) {
            is QueryCapabilityRequest -> input
            is SearchRequest -> input.publication
            is CatalogRequest -> input.reference.coordinate.request()
            is AssetRequest -> input.reference.catalog.coordinate.request()
            is DefinitionRequest -> input.catalog.coordinate.request()
            else -> throw ProducerContractException("published semantic read requires its exact typed request")
        }
        val entry=publications.require(coordinate)
        authorization.publication(request.principal,CapabilityRegistry.require(operation).permission,entry)
        val reader=PublishedSemanticCatalogReader(entry.semanticCatalog)
        return when(operation) {
            "load-query-construction-entry" -> entry
            "load-query-capability" -> entry.capability
            "load-published-semantic-catalog" -> entry.semanticCatalog
            "search-semantics" -> reader.search(input as SearchRequest)
            "read-semantic-catalog" -> reader.read(input as CatalogRequest)
            "read-semantic-asset" -> reader.asset(input as AssetRequest)
            "read-semantic-definition" -> reader.definition(input as DefinitionRequest)
            else -> throw ProducerContractException("published semantic operation is not registered")
        }
    }
}
