package com.olap.prototype.connector

import com.olap.prototype.warehouse.ResultMaterializationBudget
import com.olap.prototype.warehouse.WarehouseExecutionControl
import com.olap.prototype.warehouse.WarehouseSourceInspection
import com.olap.prototype.warehouse.contract.PhysicalDataContract
import com.olap.prototype.warehouse.contract.PhysicalSourceAdoption
import com.olap.prototype.warehouse.contract.SealedSourceDataset
import com.olap.prototype.warehouse.contract.SourceConfiguration
import com.olap.prototype.warehouse.inspection.*

/** JDBC、HTTP等实现共同遵守的来源执行与调查边界。 */
interface SourceAccess : WarehouseSourceInspection, AutoCloseable {
    val configurationParameters: com.olap.prototype.warehouse.contract.SourceConfigurationParameters
    fun validateConfiguration(source: SourceConfiguration)
    fun capabilities(source: SourceConfiguration): SourceCapabilities
    /**
     * 纯能力声明：是否能兑现该冻结证据交给优化器的键等约束，不连接来源。
     * 返回 false 不会把真实非空列改成可空；本次 NOT_NULL 采用仍由 execute 兑现。
     * execute 可使用同读基础的数据见证，或在稳定 schema 下核验源端真实强制的约束；
     * 声明、历史 catalog 观察与能力标志本身都不是本次执行的约束证明。
     */
    fun supportsConstraintVerification(dataContract: PhysicalDataContract, adoption: PhysicalSourceAdoption): Boolean = false
    /**
     * 兑现 request 中全部精确来源保证和关系检查后才交付结果。
     * 原子 statement 内核验必须保证必要检查不因主结果为空、优化器裁剪或结果预算而跳过；
     * 一次 HTTP 或一个 SQL 外壳本身不证明检查已经执行，常量输入成功不证明真实物理路径可用。
     * 运输和物化保留主 SQL 的选行、NULL、重复度与行序，不以内部子查询排序推断外层聚合保序。
     * 不满足约束抛出 SourceGuaranteeException，不能兑现则准确拒绝对应能力。
     */
    fun execute(source: SourceConfiguration, request: SourceSqlRequest, control: WarehouseExecutionControl,
        budget: ResultMaterializationBudget, nativeMemoryBytes: Long, stageNanos: (String, Long) -> Unit): SourceExecutionResult

    override fun discover(request: WarehouseSourceInspection.DiscoveryRequest, control: WarehouseExecutionControl): WarehouseSourceInspection.Discovery =
        unsupported(request.inspection.connection, "discover-source", SourceCapabilities.Capability.STRUCTURE_INSPECTION)
    override fun preview(request: WarehousePreviewRequest, control: WarehouseExecutionControl): WarehousePreview =
        unsupported(request.connection, "preview-source", SourceCapabilities.Capability.DATA_PREVIEW)
    override fun verifySealedDataset(connection: SourceConfiguration, declaration: SealedSourceDataset, control: WarehouseExecutionControl): Unit =
        unsupported(connection, "register-source-dataset", SourceCapabilities.Capability.SEALED_DATASET_VERIFICATION)
    fun discover(request: WarehouseDiscoveryRequest, control: WarehouseExecutionControl): WarehouseDiscoveryResult =
        unsupported(request.connection, "discover", SourceCapabilities.Capability.STRUCTURE_INSPECTION)
    fun describe(request: WarehouseTableInspectionRequest, control: WarehouseExecutionControl): WarehouseTableMetadata =
        unsupported(request.connection, "describe", SourceCapabilities.Capability.STRUCTURE_INSPECTION)
    fun snapshot(request: WarehouseSchemaSnapshotRequest, control: WarehouseExecutionControl): WarehouseSchemaSnapshot =
        unsupported(request.connection, "snapshot", SourceCapabilities.Capability.STRUCTURE_INSPECTION)
    fun profile(request: WarehouseProfileRequest, control: WarehouseExecutionControl): WarehouseProfile =
        unsupported(request.connection, "profile", SourceCapabilities.Capability.DATA_PROFILING)
    fun profileRelationship(request: WarehouseRelationshipProfileRequest, control: WarehouseExecutionControl): WarehouseRelationshipProfile =
        unsupported(request.connection, "profileRelationship", SourceCapabilities.Capability.RELATIONSHIP_PROFILING)

    override fun close()

    private fun unsupported(source: SourceConfiguration, operation: String, capability: SourceCapabilities.Capability): Nothing =
        throw SourceCapabilityException(source.dataSourceId, operation, capability)
}

/** 可选 JDBC 专属技术能力；通用访问合同不要求 HTTP 来源实现它。 */
interface JdbcAccess : SourceAccess { val jdbc: JdbcSupport }
