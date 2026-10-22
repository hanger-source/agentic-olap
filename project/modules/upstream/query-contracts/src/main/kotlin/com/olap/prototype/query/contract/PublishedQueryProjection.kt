package com.olap.prototype.query.contract

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.olap.prototype.query.*
import com.olap.prototype.time.TemporalWindow
import com.olap.prototype.util.OlapJsonUtils

/** 查询公开投影交出完整冻结语义，物理定位仍只在私有编译交接中。 */
object PublishedQueryProjection {
    fun produce(entry: PublishedQueryEntry): ObjectNode {
        val mapper = OlapJsonUtils.mapper()
        val source = mapper.valueToTree<JsonNode>(entry.capability)
        val capability = pick(source, "releaseId", "releaseVersion", "releaseDisplayName", "capabilityHash")
        capability.set<JsonNode>("scenario", pick(source.required("scenario"), "scenarioId", "scenarioVersion", "scenarioDisplayName"))
        mapOf("timeRangeContract" to TemporalWindow.contract(), "filterValueContract" to ScalarValueType.contract(),
            "sourceEventConditionContract" to QueryComputation.sourceEventConditionContract(), "sourceJoinContract" to SourceJoinContract.contract(),
            "rowPopulationContract" to RowPopulationContract.contract(), "scopedInputContract" to ScopedInputContract.contract(),
            "calculationInputContract" to QueryLanguageContract.calculationInputContract(),
            "temporalClockContract" to TemporalClockBindings.contract(), "postOperationContract" to PostOperationSchema.contract()).forEach { (name, contract) ->
            capability.set<JsonNode>(name, mapper.valueToTree(contract))
        }
        capability.set<JsonNode>("temporalClockCandidates", source.required("temporalClockCandidates"))
        capability.set<JsonNode>("executionTypes", source.required("executionTypes"))
        capability.set<JsonNode>("semanticDependencies", mapper.valueToTree<JsonNode>(entry.semanticCatalog).required("catalog").required("semanticDependencies"))
        capability.putArray("metricEventClosures").also { closures ->
            entry.capability.metricCandidates.forEach { metric ->
                closures.addObject().apply {
                    set<JsonNode>("instance", mapper.valueToTree(metric.instance))
                    val events = putArray("events")
                    QueryCapabilityExecutionGraph.requireMetricEventKeys(entry.capability, setOf(metric.instance)).sorted().forEach { key ->
                        val separator = key.lastIndexOf('@')
                        events.addObject().put("eventId", key.substring(0, separator)).put("eventVersion", key.substring(separator + 1).toLong())
                    }
                }
            }
        }
        capability.putArray("metricCandidates").also { metrics ->
            entry.capability.metricCandidates.forEach { candidate ->
                metrics.add(pick(mapper.valueToTree(candidate), "adoptionId", "adoptionVersion", "subjectAreaId", "subjectAreaVersion", "code", "displayName",
                    "definition", "instance", "businessMeaning", "valueContract", "snapshotMeaning", "sources", "definitionKind", "parameters", "fixedArguments", "groupingCandidates", "memberParameters", "relationParameters").apply {
                    set<JsonNode>("sourceScopes", mapper.valueToTree(entry.metricDefinition(candidate.instance).sourceScopes))
                })
            }
        }
        capability.set<JsonNode>("memberCandidates", source.required("memberCandidates"))
        capability.putArray("relationFilterCandidates").also { relations -> entry.capability.relationFilterCandidates.forEach { candidate ->
            relations.add(mapper.valueToTree<JsonNode>(entry.relationFilterDefinition(candidate.definition.relation.id, candidate.definition.relation.version)))
        } }
        capability.putArray("analysisDimensionCandidates").also { dimensions -> source.required("analysisDimensionCandidates").forEach { candidate ->
            dimensions.add(pick(candidate, "analysisDimensionId", "analysisDimensionVersion", "analysisDimensionDisplayName", "outputAlias", "availableLevelCodes").apply {
                set<JsonNode>("eventMappings", rows(candidate.required("eventMappings"), "mappingId", "levelCode", "levelDisplayName", "eventId", "eventVersion", "eventDisplayName", "valueAlignmentKey", "valueAlignmentStatus"))
                set<JsonNode>("sourceDefinition", mapper.valueToTree(entry.dimensionSourceDefinition(candidate.required("analysisDimensionId").textValue(), candidate.required("analysisDimensionVersion").longValue())))
            })
        } }
        capability.putArray("timeAnalysisDimensionCandidates").also { dimensions -> source.required("timeAnalysisDimensionCandidates").forEach { candidate ->
            dimensions.add(pick(candidate, "analysisDimensionId", "analysisDimensionVersion", "analysisDimensionDisplayName", "outputAlias", "availableLevelCodes").apply {
                set<JsonNode>("eventAnchors", rows(candidate.required("eventAnchors"), "mappingId", "levelCode", "levelDisplayName", "eventId", "eventVersion", "eventDisplayName", "timeRole"))
                set<JsonNode>("metricEventCapabilities", combinations(candidate.required("metricEventCapabilities")))
                set<JsonNode>("sourceDefinition", mapper.valueToTree(entry.timeDimensionSourceDefinition(candidate.required("analysisDimensionId").textValue(), candidate.required("analysisDimensionVersion").longValue())))
            })
        } }
        capability.putArray("filterCandidates").also { filters -> source.required("filterCandidates").forEach { candidate ->
            filters.add(pick(candidate, "filterRefId", "filterTargetType", "queryFieldKind", "filterTargetObjectId", "filterTargetObjectVersion", "filterTargetDisplayName", "operators", "filterValueTypes", "filterValueCardinality").apply {
                set<JsonNode>("eventMappings", rows(candidate.required("eventMappings"), "analysisDimensionId", "analysisDimensionVersion", "levelCode", "eventId", "eventVersion", "valueAlignmentKey", "valueAlignmentStatus"))
                val levels = putArray("candidateValueLevelCodes")
                val seen = linkedSetOf<String>()
                candidate.required("eventMappings").forEach { mapping ->
                    if (!candidate.required("valueProviderId").isNull && candidate.required("valueProviderLogicUnitId") == mapping.required("logicUnitId") &&
                        candidate.required("valueProviderLogicUnitVersion") == mapping.required("logicUnitVersion") && seen.add(mapping.required("levelCode").textValue())) levels.add(mapping.required("levelCode"))
                }
                set<JsonNode>("sourceDefinition", mapper.valueToTree(entry.dimensionSourceDefinition(candidate.required("filterTargetObjectId").textValue(), candidate.required("filterTargetObjectVersion").longValue())))
            })
        } }
        capability.set<JsonNode>("postProcessCapabilities", rows(source.required("postProcessCapabilities"), "ruleType", "ruleDisplayName", "ruleConfigJson", "available", "reasonCode", "reasonMessage"))
        capability.set<JsonNode>("metricAnalysisDimensionMatrix", combinations(source.required("metricAnalysisDimensionMatrix")))
        return mapper.createObjectNode().apply {
            put("formatVersion", "olap-query-construction-capability/v24")
            put("catalogHash", entry.catalogHash); put("semanticCatalogHash", entry.semanticCatalogHash)
            set<JsonNode>("capability", capability)
            put("sha256", QueryIntentHash.compute(this))
        }
    }
    private fun combinations(source: JsonNode): ArrayNode = rows(source, "instance", "eventId", "eventVersion", "analysisDimensionId", "analysisDimensionVersion", "levelCode", "available", "reasonCode", "reasonMessage")
    private fun rows(source: JsonNode, vararg fields: String): ArrayNode = OlapJsonUtils.mapper().createArrayNode().also { result -> source.forEach { result.add(pick(it, *fields)) } }
    private fun pick(source: JsonNode, vararg fields: String): ObjectNode = OlapJsonUtils.mapper().createObjectNode().apply {
        fields.forEach { field -> require(source.has(field)) { "published capability field is missing: $field" }; set<JsonNode>(field, source[field]) }
    }
}
