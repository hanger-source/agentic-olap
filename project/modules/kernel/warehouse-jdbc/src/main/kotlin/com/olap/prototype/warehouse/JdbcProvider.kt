package com.olap.prototype.warehouse

import com.olap.prototype.api.*
import com.olap.prototype.connector.ConnectorRegistry
import com.olap.prototype.connector.SourceCapabilityException
import com.olap.prototype.connector.SourceResultMaterializationException
import com.olap.prototype.connector.SourceSqlRequest
import com.olap.prototype.connector.SourceExecutionResult
import com.olap.prototype.connector.SourceExecutionException
import com.olap.prototype.connector.SourceExecutionFailures
import com.olap.prototype.warehouse.contract.*
import com.olap.prototype.contract.JdbcColumnType
import com.olap.prototype.runtime.RuntimeResourceLimitException
import com.olap.prototype.warehouse.contract.JdbcConnectionSpec
import com.olap.prototype.warehouse.jdbc.*
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.SQLException
import java.util.concurrent.CancellationException

/** One JDBC execution owns connection, cancellation registrations, verification, rows and rollback. */
class JdbcProvider(private val connectionSpec: JdbcConnectionSpec, private val connectionFactory: JdbcConnectionFactory) {
    constructor(connectionSpec: JdbcConnectionSpec, connectors: ConnectorRegistry) : this(connectionSpec, JdbcConnectionFactory(connectors))
    private val log = LoggerFactory.getLogger(JdbcProvider::class.java)
    val dataSourceId: String get() = connectionSpec.dataSourceId
    val bindingRef get() = connectionSpec.source().bindingRef()
    val connectorRef get() = connectionSpec.connectorRef
    val dialectId: String get() = connectionSpec.dialectId
    fun executeFrame(request: SourceSqlRequest, control: WarehouseExecutionControl, budget: ResultMaterializationBudget, nativeMemoryBytes: Long, stageNanos: (String, Long) -> Unit): SourceExecutionResult {
        log.info("JdbcProvider [Action]: 正在执行物理 SQL 并校验结果列契约...")
        val sql = request.sql
        var receipt = SourceRequestEvidence(null, SourceRequestAcceptance.NOT_SUBMITTED, SourceResponseState.NOT_RECEIVED, SourceExecutionState.NOT_SUBMITTED, "JDBC", null)
        var completedReadEvidence: SourceReadEvidence? = null
        var stage = "PREPARE_SOURCE"
        try {
            control.checkCancellation()
            val jdbc = connectionFactory.jdbc(connectionSpec)
            request.requireCapabilities(connectionSpec.dataSourceId, jdbc.capabilities(connectionSpec.source()))
            stage = "VERIFY_SOURCE"
            JdbcSourceVerifier.requireDeployment(connectionSpec, request.sourceRequirements)
            stage = "CONNECT_SOURCE"
            observed("JDBC_CONNECT", stageNanos, ::connection).use { connection ->
                control.registerCancellationAction { connection.abort { task -> task.run() } }.use {
                    control.checkCancellation()
                    var executionFailure: Throwable? = null
                    try {
                        stage = "VERIFY_SOURCE"
                        val readBasis = observed("JDBC_SOURCE_VERIFY", stageNanos) {
                            require(nativeMemoryBytes > 0) { "explicit positive native memory budget is required" }
                            control.checkCancellation(); jdbc.configureMemory(connection, nativeMemoryBytes, control)
                            receipt = SourceRequestEvidence(null, SourceRequestAcceptance.UNKNOWN, SourceResponseState.UNKNOWN, SourceExecutionState.UNKNOWN, "JDBC", null)
                            val anchor = JdbcSourceVerifier.beginAndVerify(connection, connectionSpec, request.sourceRequirements, control, jdbc)
                            val basis = if (anchor == null) jdbc.readBasis(connection, connectionSpec, request.expectedReadBasis, control)
                                else SourceReadBasis.Versioned("sha256", anchor.sha256, emptyMap(),
                                    if (com.olap.prototype.connector.SourceCapabilities.Capability.PINNED_READ in jdbc.capabilities(connectionSpec.source()).supported)
                                        SourcePinning.EXACT_VERSION else SourcePinning.UNSUPPORTED)
                            request.expectedReadBasis?.let { if (basis != it) throw SourceGuaranteeException("SOURCE_READ_BASIS_MISMATCH") }
                            basis
                        }
                        val preparationStart = System.nanoTime()
                        stage = "EXECUTE_SQL"
                        connection.prepareStatement(sql).use { statement -> control.registerCancellationAction(statement::cancel).use {
                            stage = "VERIFY_SOURCE"
                            observed("JDBC_RELATION_CHECK", stageNanos) {
                                request.relationChecks.forEach { check ->
                                    control.checkCancellation()
                                    connection.prepareStatement(check.sql).use { validation -> control.registerCancellationAction(validation::cancel).use {
                                        validation.maxRows = 1
                                        check.parameters.forEachIndexed { index, parameter -> jdbc.bind(validation, index + 1, parameter) }
                                        validation.executeQuery().use { rows -> control.checkCancellation(); if (rows.next()) {
                                            log.error("原生关系检查拒绝查询：dataSourceId={}, code={}", connectionSpec.dataSourceId, check.code)
                                            throw SourceGuaranteeException(check.code)
                                        } }
                                    } }
                                }
                            }
                            stage = "EXECUTE_SQL"
                            request.parameters.forEachIndexed { index, parameter -> control.checkCancellation(); jdbc.bind(statement, index + 1, parameter) }
                            control.checkCancellation()
                            stageNanos("JDBC_PREPARE_BIND", System.nanoTime() - preparationStart)
                            receipt = SourceRequestEvidence(null, SourceRequestAcceptance.SUBMITTED, SourceResponseState.NOT_RECEIVED, SourceExecutionState.UNKNOWN, "JDBC", null)
                            observed("JDBC_EXECUTE_QUERY", stageNanos, statement::executeQuery).use { rows ->
                                stage = "READ_RESULT"
                                receipt = SourceRequestEvidence(null, SourceRequestAcceptance.ACCEPTED, SourceResponseState.RECEIVED, SourceExecutionState.UNKNOWN, "JDBC", null)
                                return observed("JDBC_MATERIALIZE", stageNanos) {
                                    try {
                                        val frame = materialize(rows, request, control, budget)
                                        stage = "MATERIALIZE_RESULT"
                                        receipt = receipt.copy(execution = SourceExecutionState.SUCCEEDED)
                                        completedReadEvidence = SourceReadEvidence(bindingRef, readBasis, receipt)
                                        val result = SourceExecutionResult(frame, readBasis, receipt)
                                        stage = "CLOSE_SOURCE"
                                        result
                                    }
                                    catch (failure: RuntimeResourceLimitException) { throw failure }
                                    catch (failure: SourceCapabilityException) { throw failure }
                                    catch (failure: SourceGuaranteeException) { throw failure }
                                    catch (failure: SourceExecutionException) { throw failure }
                                    catch (failure: CancellationException) { throw failure }
                                    catch (failure: Exception) {
                                        control.checkCancellation()
                                        stage = "MATERIALIZE_RESULT"
                                        log.error("JdbcProvider [Error]: 结果读取失败 -> {}", sql, failure)
                                        throw SourceResultMaterializationException(failure)
                                    }
                                }
                            }
                        } }
                    } catch (failure: Throwable) { executionFailure = failure; throw failure }
                    finally {
                        if (executionFailure == null) stage = "CLOSE_SOURCE"
                        try { observed("JDBC_ROLLBACK", stageNanos) { if (!connection.autoCommit) connection.rollback() } }
                        catch (cleanup: Throwable) { val original = executionFailure; if (original == null) throw cleanup else original.addSuppressed(cleanup) }
                    }
                }
            }
        } catch (failure: Exception) {
            if (failure is SQLException) log.error("JdbcProvider [Error]: SQL 执行失败 -> {}", sql, failure)
            if (failure is WarehouseConnectionException) throw SourceExecutionException(SourceFailureEvidence(
                SourceFailureCategory.CONNECTION, stage, failure.code, "source connection failed", bindingRef, request.operation,
                null, failure.sqlState, null, receipt, control.cancellationEvidence()), failure, completedReadEvidence)
            throw SourceExecutionFailures.exception(bindingRef, request.operation, stage, failure, control.cancellationEvidence(), receipt, completedReadEvidence)
        }
    }
    private fun materialize(rows: ResultSet, request: SourceSqlRequest, control: WarehouseExecutionControl, budget: ResultMaterializationBudget): ResultFrame {
        val metadata = rows.metaData
        val columns = (1..metadata.columnCount).map { index ->
            val label = metadata.getColumnLabel(index)
            ResultColumn(alias(label, request.aliasMapping), display(label, request.aliasDisplayNames), label, metadata.getColumnTypeName(index), JdbcColumnType(metadata.getColumnType(index), metadata.getColumnTypeName(index),
                metadata.getPrecision(index).takeIf { it >= 0 }, metadata.getScale(index).takeIf { it >= 0 }, when (metadata.isNullable(index)) {
                    ResultSetMetaData.columnNoNulls -> false; ResultSetMetaData.columnNullable -> true; else -> null
                }))
        }
        val result = mutableListOf<ResultRow>()
        val jdbc = connectionFactory.jdbc(connectionSpec)
        while (rows.next()) {
            control.checkCancellation(); budget.row()
            result += ResultRow(result.size, (1..metadata.columnCount).map { index -> ResultCell(alias(metadata.getColumnLabel(index), request.aliasMapping), JdbcResultValues.read(rows, index, control, budget, jdbc)) })
        }
        return ResultFrame(columns, result)
    }
    private fun display(label: String, names: Map<String, String>): String {
        check(names.isNotEmpty()) { "结果列展示名契约为空，无法映射物理列: $label" }
        return names[label]?.takeUnless { it.isBlank() } ?: throw IllegalStateException("结果列缺少精确展示名映射: $label, available=${names.keys}")
    }
    private fun alias(label: String, aliases: Map<String, String>): String {
        check(aliases.isNotEmpty()) { "结果列契约为空，无法映射物理列: $label" }
        return aliases[label]?.takeUnless { it.isBlank() } ?: throw IllegalStateException("结果列缺少精确语义映射: $label, available=${aliases.keys}")
    }
    fun connection(): Connection = connectionFactory.openReadOnly(connectionSpec)
    private inline fun <T> observed(stage: String, sink: (String, Long) -> Unit, action: () -> T): T {
        val start = System.nanoTime()
        return try { action() } finally { sink(stage, System.nanoTime() - start) }
    }
}
