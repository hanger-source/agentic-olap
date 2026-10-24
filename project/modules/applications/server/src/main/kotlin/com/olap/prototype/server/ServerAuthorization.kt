package com.olap.prototype.server

import com.olap.prototype.query.contract.PublishedQueryEntry

class ServerAuthorization(private val grants: List<Grant>) {
    data class Principal(val subject: String, val scopes: List<String>) {
        init { if (subject.isBlank()) throw SecurityException("authenticated principal is required") }
    }
    data class Grant(val subjects: List<String>, val scopes: List<String>, val operations: List<String>,
        val dataSources: List<String>, val subjectAreas: List<String>, val releases: List<String>,
        val allGovernanceAssets: Boolean)

    fun matching(principal: Principal, operation: String): List<Grant> = grants.filter {
        principal.subject in it.subjects && principal.scopes.containsAll(it.scopes) && operation in it.operations
    }
    fun operation(principal: Principal, operation: String) {
        if (matching(principal, operation).isEmpty()) throw SecurityException("operation is not authorized")
    }
    fun governance(principal: Principal, operation: String) {
        if (matching(principal, operation).none { it.allGovernanceAssets })
            throw SecurityException("this governance operation requires an explicit grant covering shared assets")
    }
    fun source(principal: Principal, operation: String, dataSourceId: String) {
        if (matching(principal, operation).none { includes(it.dataSources, dataSourceId) })
            throw SecurityException("data source is not authorized")
    }
    fun publication(principal: Principal, operation: String, entry: PublishedQueryEntry) {
        val capability = entry.capability
        val subjectArea = capability.subjectAreaCapabilityContext.subjectAreaId
        publication(principal, operation, subjectArea, capability.releaseId)
    }
    /** 历史结果按保存时的权威归属授权，不重新装载旧执行目录或插件。 */
    fun publication(principal: Principal, operation: String, subjectAreaId: String, releaseId: String) {
        if (matching(principal, operation).none {
            includes(it.subjectAreas, subjectAreaId) && includes(it.releases, releaseId)
        }) throw SecurityException("exact publication is not authorized")
    }
    companion object { fun includes(allowed: List<String>, id: String): Boolean = "*" in allowed || id in allowed }
}
