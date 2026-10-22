package com.olap.prototype.source.contract

import com.olap.prototype.connector.ConnectorRef
import com.olap.prototype.contract.NativeValueType
import com.olap.prototype.expression.NativeAggregateMapping
import com.olap.prototype.expression.NativeScalarFunction
import com.olap.prototype.query.ResultAggregation
import com.olap.prototype.warehouse.contract.*
import com.olap.prototype.warehouse.inspection.*

data object EmptyRequest
data class AvailableSource(val binding: SourceBindingRef, val connectorRef: ConnectorRef, val dialectId: String, val accessCapabilities: Set<String>)
data class AvailableSources(val items: List<AvailableSource>)
data class ConnectorCapabilitiesRequest(val aggregate: Aggregate?) {
    data class Aggregate(val connectorRef: ConnectorRef, val aggregation: ResultAggregation.Kind, val inputType: NativeValueType, val grouped: Boolean)
}
data class ConnectorCapabilities(val apiVersion: String, val connectors: List<Connector>, val aggregate: Aggregate?) {
    data class Connector(val connectorRef: ConnectorRef, val nativeScalarFunctions: List<NativeScalarFunction>, val compilationCapabilities: Set<String>,
        val parameterSubmission: String, val configurationParameters: SourceConfigurationParameters)
    data class Aggregate(val connectorRef: ConnectorRef, val aggregation: ResultAggregation.Kind, val inputType: NativeValueType, val grouped: Boolean, val mapping: NativeAggregateMapping)
}

/** 只传获准绑定，不接受来源连接、凭据或 SQL。 */
sealed interface SourceInspection {
    data object Capabilities : SourceInspection
    data class Discover(val binding: SourceBindingRef, val catalog: String?, val schemaPattern: String?, val tablePattern: String?, val tableTypes: List<String>, val limit: Int?) : SourceInspection
    data class Describe(val binding: SourceBindingRef, val table: WarehouseTableRef) : SourceInspection
    data class Snapshot(val binding: SourceBindingRef, val catalog: String?, val schemaName: String, val tablePattern: String?, val tableTypes: List<String>, val limit: Int?) : SourceInspection
    data class Preview(val binding: SourceBindingRef, val table: WarehouseTableRef, val limit: Int?) : SourceInspection
    data class Profile(val binding: SourceBindingRef, val table: WarehouseTableRef, val columns: List<String>, val timeoutSeconds: Int?) : SourceInspection
    data class Relationship(val binding: SourceBindingRef, val sourceInput: WarehouseRelationshipProfileRequest.Input, val targetInput: WarehouseRelationshipProfileRequest.Input, val timeoutSeconds: Int?) : SourceInspection
}

sealed interface SourceInspectionResult {
    data class Capabilities(val operations: Set<String>, val relationshipExpressionType: String) : SourceInspectionResult
    data class Discovery(val value: WarehouseDiscoveryResult) : SourceInspectionResult
    data class Description(val value: WarehouseTableMetadata) : SourceInspectionResult
    data class Snapshot(val value: WarehouseSchemaSnapshot) : SourceInspectionResult
    data class Preview(val value: WarehousePreview) : SourceInspectionResult
    data class Profile(val value: WarehouseProfile) : SourceInspectionResult
    data class Relationship(val value: WarehouseRelationshipProfile) : SourceInspectionResult
}
