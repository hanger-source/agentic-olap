package com.olap.prototype.connector

/** ServiceLoader 入口；发现与编译不连接业务源，也不读取凭据。 */
interface OlapConnector {
    val id: String
    val version: String
    val apiVersion: String
    val compilation: CompilationSupport
    fun access(verifiedIdentity: ConnectorRef): SourceAccess

    companion object { const val API_VERSION = "agentic-olap-connector/v14-kotlin-mean-target-calcite-1.42" }
}

/** 已核验字节的单个实现；生命周期由部署装载者持有。 */
data class ResolvedConnector(val ref: ConnectorRef, val compilation: CompilationSupport, val access: SourceAccess)
