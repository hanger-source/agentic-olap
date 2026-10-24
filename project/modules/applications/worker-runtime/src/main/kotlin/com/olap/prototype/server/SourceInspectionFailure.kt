package com.olap.prototype.server

import com.olap.prototype.connector.SourceCapabilities
import com.olap.prototype.connector.SourceCapabilityException
import com.olap.prototype.runtime.RuntimeResourceLimitException
import com.olap.prototype.runtime.SourceWorkerCommand
import com.olap.prototype.runtime.SourceWorkerResult
import com.olap.prototype.source.contract.SourceInspection
import com.olap.prototype.warehouse.SourceGuaranteeException
import com.olap.prototype.warehouse.WarehouseExecutionControl
import com.olap.prototype.warehouse.WarehouseSourceInspection
import com.olap.prototype.warehouse.contract.SealedSourceDataset
import com.olap.prototype.warehouse.contract.SourceConfiguration
import com.olap.prototype.warehouse.inspection.WarehouseInspectionException
import com.olap.prototype.warehouse.inspection.WarehousePreview
import com.olap.prototype.warehouse.inspection.WarehousePreviewRequest
import com.olap.prototype.wire.query.SourceCapabilityUnavailable
import com.olap.prototype.wire.query.SourceGuaranteeFailed
import com.olap.prototype.wire.query.SourceInspectionFailed
import com.olap.prototype.wire.query.SourceInspectionFailure as WireInspectionFailure

object SourceInspectionFailure {
    fun facts(failure: Exception): WireInspectionFailure? = when (failure) {
        is SourceCapabilityException -> WireInspectionFailure.newBuilder().setCapability(
            SourceCapabilityUnavailable.newBuilder().setDataSourceId(failure.dataSourceId)
                .setOperation(failure.operation).setCapability(failure.capability.name)).build()
        is WarehouseInspectionException -> WireInspectionFailure.newBuilder().setInspection(
            SourceInspectionFailed.newBuilder().setCode(failure.code).setDataSourceId(failure.dataSourceId).apply {
                failure.sqlState?.let(::setSqlState); failure.failureType?.let(::setFailureType)
            }).build()
        is SourceGuaranteeException -> WireInspectionFailure.newBuilder().setGuarantee(
            SourceGuaranteeFailed.newBuilder().setCode(failure.code).apply {
                failure.sqlState?.let(::setSqlState); failure.failureType?.let(::setFailureType)
            }).build()
        is RuntimeResourceLimitException -> WireInspectionFailure.newBuilder().setResourceLimit(failure.code).build()
        is java.util.concurrent.CancellationException -> WireInspectionFailure.newBuilder()
            .setCancelled(com.olap.prototype.wire.semantic.Empty.getDefaultInstance()).build()
        else -> null
    }

    fun project(failure: Exception): Map<String, Any?> = when (failure) {
        is SourceCapabilityException -> mapOf("type" to failure.javaClass.name, "code" to failure.code,
            "dataSourceId" to failure.dataSourceId, "operation" to failure.operation, "capability" to failure.capability.name)
        is WarehouseInspectionException -> mapOf("type" to failure.javaClass.name, "code" to failure.code,
            "dataSourceId" to failure.dataSourceId, "sqlState" to failure.sqlState, "failureType" to failure.failureType)
        is SourceGuaranteeException -> mapOf("type" to failure.javaClass.name, "code" to failure.code,
            "sqlState" to failure.sqlState, "failureType" to failure.failureType)
        is RuntimeResourceLimitException -> mapOf("type" to failure.javaClass.name, "code" to failure.code)
        is java.util.concurrent.CancellationException -> mapOf("type" to failure.javaClass.name, "code" to "CANCELLED")
        else -> mapOf("type" to failure.javaClass.name, "code" to "SOURCE_INSPECTION_FAILED")
    }

    fun rethrow(failure: WireInspectionFailure): Nothing = when (failure.kindCase) {
        WireInspectionFailure.KindCase.CAPABILITY -> failure.capability.let {
            require(it.dataSourceId.isNotBlank() && it.operation.isNotBlank()) { "source capability failure requires its source and operation" }
            throw SourceCapabilityException(it.dataSourceId, it.operation, SourceCapabilities.Capability.valueOf(it.capability))
        }
        WireInspectionFailure.KindCase.INSPECTION -> failure.inspection.let {
            require(it.code.isNotBlank() && it.dataSourceId.isNotBlank()) { "inspection failure requires its code and source" }
            throw WarehouseInspectionException(it.code, it.dataSourceId, it.sqlState.takeIf { _ -> it.hasSqlState() },
                it.failureType.takeIf { _ -> it.hasFailureType() })
        }
        WireInspectionFailure.KindCase.GUARANTEE -> failure.guarantee.let {
            throw SourceGuaranteeException(it.code, it.sqlState.takeIf { _ -> it.hasSqlState() },
                it.failureType.takeIf { _ -> it.hasFailureType() })
        }
        WireInspectionFailure.KindCase.RESOURCE_LIMIT -> {
            require(failure.resourceLimit.isNotBlank()) { "source resource failure requires its code" }
            throw RuntimeResourceLimitException(failure.resourceLimit)
        }
        WireInspectionFailure.KindCase.CANCELLED -> throw java.util.concurrent.CancellationException("source inspection was cancelled")
        else -> error("source inspection failure kind is required")
    }
}
