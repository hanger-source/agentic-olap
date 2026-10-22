package com.olap.prototype.query.contract

/** 正式构造读取精确发布模型；语言构造与函数发现由 DSL API 提供。 */
object PublishedQueryProjection {
    fun produce(entry: PublishedQueryEntry): PublishedQueryEntry = entry
}
