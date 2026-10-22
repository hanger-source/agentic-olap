package com.olap.prototype.storage

import com.olap.prototype.storage.schema.PlatformSchemaInitializer
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.sql.SQLFeatureNotSupportedException
import java.util.UUID

/** 元数据 JVM 独占数据库与连接生命周期；不再为内部模块启动数据库网络服务。 */
class PlatformDatabase private constructor(private val jdbcUrl: String) : AutoCloseable {
    private val monitor = Any()
    private val keeper = DriverManager.getConnection(jdbcUrl, USER, PASSWORD)
    private val connections = linkedSetOf(keeper)
    private val transactionConnection = ThreadLocal<Connection>()
    private var closed = false

    fun connection(): Connection = transactionConnection.get()?.let { view(it) {} } ?: newConnection()

    fun <T> inTransaction(work: () -> T): T = transaction(work, null)

    /** 多表读取使用同一串行化快照，不能把并发修订拼成一份依赖闭包。 */
    fun <T> inReadSnapshot(work: () -> T): T = transaction(work, Connection.TRANSACTION_SERIALIZABLE)

    private fun <T> transaction(work: () -> T, isolation: Int?): T {
        val active = transactionConnection.get()
        if (active != null) {
            if (isolation != null && active.transactionIsolation != isolation)
                error("read snapshot requires an existing serializable transaction")
            return work()
        }
        try {
            newConnection().use { connection ->
                if (isolation != null) connection.transactionIsolation = isolation
                connection.autoCommit = false
                transactionConnection.set(connection)
                try {
                    val result = work()
                    connection.commit()
                    return result
                } catch (failure: Throwable) {
                    try { connection.rollback() } catch (rollback: SQLException) { failure.addSuppressed(rollback) }
                    throw failure
                } finally {
                    transactionConnection.remove()
                }
            }
        } catch (failure: SQLException) {
            throw IllegalStateException("Database transaction failed", failure)
        }
    }

    private fun newConnection(): Connection = synchronized(monitor) {
        if (closed) throw SQLException("Platform database is closed")
        val connection = DriverManager.getConnection(jdbcUrl, USER, PASSWORD)
        connections.add(connection)
        view(connection) { synchronized(monitor) { connection.close(); connections.remove(connection) } }
    }

    override fun close(): Unit = synchronized(monitor) {
        if (closed) return@synchronized
        closed = true
        var failure: SQLException? = null
        for (connection in connections) {
            try { connection.close() } catch (close: SQLException) {
                if (failure == null) failure = close else failure.addSuppressed(close)
            }
        }
        connections.clear()
        failure?.let { throw it }
    }

    private fun view(connection: Connection, release: () -> Unit): Connection = Proxy.newProxyInstance(
        PlatformDatabase::class.java.classLoader, arrayOf(Connection::class.java)
    ) { proxy, method, arguments ->
        when (method.name) {
            "equals" -> proxy === requireNotNull(arguments)[0]
            "hashCode" -> System.identityHashCode(proxy)
            "isWrapperFor" -> (requireNotNull(arguments)[0] as? Class<*>)?.isInstance(proxy) == true
            "unwrap" -> {
                val type = requireNotNull(arguments)[0] as? Class<*>
                    ?: throw SQLException("connection wrapper type is required")
                if (!type.isInstance(proxy)) throw SQLFeatureNotSupportedException(
                    "Platform connection lifecycle view does not expose native or vendor connections: ${type.name}")
                type.cast(proxy)
            }
            "close" -> { release(); null }
            else -> try {
                if (arguments == null) method.invoke(connection) else method.invoke(connection, *arguments)
            } catch (failure: InvocationTargetException) { throw failure.targetException }
        }
    } as Connection

    companion object {
        private const val USER = "sa"
        private const val PASSWORD = ""

        fun openSingleInstance(dataDirectory: Path): PlatformDatabase = try {
            Files.createDirectories(dataDirectory)
            initialized("jdbc:h2:file:${dataDirectory.toRealPath().resolve("olap-platform")};MODE=PostgreSQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=0")
        } catch (failure: Exception) {
            throw IllegalStateException("Failed to open single-instance platform database", failure)
        }

        fun inMemory(): PlatformDatabase = try {
            initialized("jdbc:h2:mem:platform_${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=0")
        } catch (failure: SQLException) {
            throw IllegalStateException("Failed to initialize H2 platform database", failure)
        }

        @Synchronized
        private fun initialized(url: String): PlatformDatabase {
            val database = PlatformDatabase(url)
            try {
                PlatformSchemaInitializer.initializeOrValidate(database.keeper)
                return database
            } catch (failure: Throwable) {
                try { database.close() } catch (close: SQLException) { failure.addSuppressed(close) }
                throw failure
            }
        }
    }
}
