package com.olap.fixtures.http

import com.olap.prototype.connector.JdbcSupport
import com.olap.prototype.warehouse.*
import com.olap.prototype.warehouse.contract.JdbcConnectionSpec
import com.olap.prototype.warehouse.jdbc.JdbcResultValues
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Properties
import java.util.concurrent.atomic.AtomicInteger

/** 真实 SQL 文本 HTTP 服务；不提供参数绑定、事务、catalog 或结果类型旁路。 */
class HttpSqlService(jdbc: JdbcSupport, source: JdbcConnectionSpec) : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val queryCount = AtomicInteger()
    val endpoint: String get() = "http://127.0.0.1:${server.address.port}/sql"
    val queries: Int get() = queryCount.get()
    init {
        server.createContext("/sql") { exchange -> exchange.use {
            if (exchange.requestHeaders.getFirst("Authorization") != "Bearer fixture-query-access") { exchange.sendResponseHeaders(403, -1); return@createContext }
            val response = ByteArrayOutputStream()
            try {
                DataOutputStream(response).use { output -> jdbc.openReadOnly(source, Properties()).use { connection -> connection.createStatement().use { statement -> DeadlineExecutionControl(30000).use { control ->
                    val bytes = exchange.requestBody.readNBytes(32 * 1024 * 1024 + 1)
                    if (bytes.size > 32 * 1024 * 1024) throw IOException("SQL request exceeds limit")
                    val sql = UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
                    statement.executeQuery(sql).use { rows ->
                        val metadata = rows.metaData
                        output.writeInt(metadata.columnCount)
                        (1..metadata.columnCount).forEach { HttpSqlConnector.writeText(output, metadata.getColumnLabel(it)) }
                        val budget = ResultMaterializationBudget(ResultMaterializationLimits(10000, 100000, 16000000, 32))
                        while (rows.next()) { budget.row(); output.writeBoolean(true); (1..metadata.columnCount).forEach { HttpSqlConnector.writeValue(output, JdbcResultValues.read(rows, it, control, budget, jdbc)) } }
                        output.writeBoolean(false)
                    }
                    queryCount.incrementAndGet()
                } } } }
            } catch (failure: Exception) {
                System.getLogger(HttpSqlService::class.java.name).log(System.Logger.Level.WARNING, "SQL-text HTTP fixture rejected request", failure)
                exchange.sendResponseHeaders(500, -1); return@createContext
            }
            exchange.sendResponseHeaders(200, response.size().toLong()); exchange.responseBody.write(response.toByteArray())
        } }
        server.start()
    }
    override fun close() = server.stop(0)
}
