package com.olap.prototype.management.warehouse

import com.olap.prototype.warehouse.contract.PhysicalSourceAdoption
import com.olap.prototype.warehouse.contract.SourceCollectionRecord

/** 精确来源观察与不可变采用的存储端口，不读取或修订治理定义。 */
interface SourceAdoptionRegistry : WarehouseDiscoveryReader {
    fun <T> inTransaction(work: () -> T): T
    fun insertSourceAdoption(record: PhysicalSourceAdoption): PhysicalSourceAdoption
    fun listSourceCollections(dataSourceId: String): List<SourceCollectionRecord>
}
