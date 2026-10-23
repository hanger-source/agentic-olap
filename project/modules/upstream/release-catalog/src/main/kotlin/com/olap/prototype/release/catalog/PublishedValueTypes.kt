package com.olap.prototype.release.catalog

import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.engine.catalog.ExecutableCatalog
import com.olap.prototype.engine.catalog.ExecutableLogicUnit
import com.olap.prototype.expression.SemanticExpressionValueType
import com.olap.prototype.query.ScalarValueType
import com.olap.prototype.query.SetOutputContract
import com.olap.prototype.query.contract.QueryExecutionTypes
import com.olap.prototype.release.contract.ReleaseDimensionMapping
import org.apache.calcite.sql.type.SqlTypeName
import java.util.Collections

/** 单次发布的精确类型索引，公开语义与查询能力共用同一类型事实。 */
class PublishedValueTypes(private val assets: ExecutableCatalog) {
    private fun unit(id: String, version: Long): ExecutableLogicUnit = requireNotNull(assets.logicUnits["$id@$version"]) { "exact frozen LogicUnit type is missing" }
    fun logicType(id: String, version: Long): ScalarValueType = unit(id, version).valueType ?: throw ProducerContractException("operation requires a scalar LogicUnit: $id@$version")
    fun shape(id: String, version: Long): SetOutputContract.Container? = unit(id, version).valueShape
    fun dimensions(mappings: List<ReleaseDimensionMapping>, nativeTypes: QueryExecutionTypes): Map<String, String> {
        val result = linkedMapOf<String, String>()
        mappings.forEach { mapping ->
            val matches = nativeTypes.dimensions.filter { value ->
                value.dimension.id == mapping.analysisDimensionId && value.dimension.version == mapping.analysisDimensionVersion.toString() &&
                    value.dimension.levelCode == mapping.levelCode && value.eventId == mapping.eventId && value.eventVersion == mapping.eventVersion
            }
            if (matches.size != 1) throw ProducerContractException("published dimension requires its exact frozen native type")
            val type = SemanticExpressionValueType.scalarType(SqlTypeName.valueOf(matches.single().type.name)).name
            val previous = result.putIfAbsent(mapping.levelCode, type)
            if (previous != null && previous != type) throw ProducerContractException("published dimension level has incompatible event scalar types: ${mapping.analysisDimensionId}@${mapping.analysisDimensionVersion}")
        }
        if (result.isEmpty()) throw ProducerContractException("published dimension has no typed frozen mappings")
        return Collections.unmodifiableMap(result)
    }
}
