package com.olap.prototype.query.contract

import com.olap.prototype.dsl.ApiDiscovery

/** 可发现的 Kotlin 计算语言入口。 */
object QueryLanguageContract {
    data class Selection(val type: String?, val operation: String?)
    fun describe(selection: Selection): Any = when {
        selection.type != null && selection.operation != null -> error("select one exact Kotlin type or capability")
        selection.type != null -> ApiDiscovery.type(selection.type)
        selection.operation != null -> ApiDiscovery.operation(selection.operation)
        else -> ApiDiscovery.overview()
    }
}
