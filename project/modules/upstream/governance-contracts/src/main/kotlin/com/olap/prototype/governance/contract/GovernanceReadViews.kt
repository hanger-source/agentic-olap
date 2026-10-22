package com.olap.prototype.governance.contract

import com.olap.prototype.warehouse.contract.JdbcTableConstraints
import java.time.Instant

/** 调查读取只披露来源结构事实，不混入本地登记状态。 */
data class SourceDiscoveryTable(val id:String,val discoverySnapshotId:String,val catalogName:String,val schemaName:String,val tableName:String,val tableComment:String?,val rowCount:Long?,val schemaHash:String?,val constraints:JdbcTableConstraints,val createdAt:Instant)

data class ReleaseIdentityView(val releaseId:String,val releaseVersion:String,val subjectAreaId:String,val subjectAreaVersion:Long,val governanceClosureHash:String,val catalogHash:String,val artifactHash:String)

class DefinitionDependencies(val target:DefinitionImpact.Reference,items:List<DefinitionImpact.Reference>,val nextOffset:Int?) { val items:List<DefinitionImpact.Reference> = java.util.List.copyOf(items) }
