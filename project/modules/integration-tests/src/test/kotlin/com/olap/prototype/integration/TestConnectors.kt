package com.olap.prototype.integration

import com.olap.prototype.api.ResultValueType
import com.olap.prototype.connector.*
import com.olap.prototype.connector.loader.ConnectorPlugins
import com.olap.prototype.contract.JdbcColumnType
import com.olap.prototype.engine.catalog.ExecutablePhysicalExecutionBinding
import com.olap.prototype.expression.SemanticNativeTypes
import com.olap.prototype.util.OlapJsonUtils
import com.olap.prototype.warehouse.contract.*
import com.olap.prototype.codec.GovernanceProto
import com.olap.prototype.warehouse.jdbc.JdbcConnectionFactory
import org.apache.calcite.jdbc.JavaTypeFactoryImpl
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection
import java.sql.Driver
import java.sql.ResultSetMetaData
import java.util.HexFormat
import java.util.Properties

/** 测试JVM显式部署插件及驱动，不将provider类加入宿主classpath。 */
object TestConnectors {
    val configuration: Path
    private val plugins: ConnectorPlugins
    val registry: ConnectorRegistry get() = plugins.registry
    init {
        val root = Files.createTempDirectory("olap-test-connectors-")
        val manifests = listOf("h2", "duckdb").map { backend ->
            val property = "olap.connector.$backend.jar"
            val plugin = Path.of(requireNotNull(System.getProperty(property)) { "required test artifact: $property" }).toRealPath()
            val driver = Class.forName(driverName(backend), false, TestConnectors::class.java.classLoader)
            val driverJar = Path.of(driver.protectionDomain.codeSource.location.toURI()).toRealPath()
            val group = Files.createDirectory(root.resolve(backend))
            val artifacts = linkedMapOf<String, String>()
            listOf(plugin, driverJar).forEach { jar ->
                require(Files.isRegularFile(jar)) { "test plugin group requires actual JAR files: $jar" }
                val captured = Files.copy(jar, group.resolve(jar.fileName))
                val digest = MessageDigest.getInstance("SHA-256")
                Files.newInputStream(captured).use { input ->
                    val buffer = ByteArray(65536)
                    while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
                }
                artifacts[captured.fileName.toString()] = HexFormat.of().formatHex(digest.digest())
            }
            val reference = ConnectorRef(backend, "1.0.0", ConnectorPlugins.groupHash(artifacts))
            group.resolve("connector.json").also { file ->
                OlapJsonUtils.mapper().writeValue(file.toFile(), ConnectorPlugins.Manifest(reference, OlapConnector.API_VERSION,
                    artifacts.map { (name, hash) -> ConnectorPlugins.Artifact(name, hash) }))
            }
        }
        configuration = root.resolve("connectors.json")
        OlapJsonUtils.mapper().writeValue(configuration.toFile(), manifests.map(Path::toString))
        plugins = ConnectorPlugins.load(manifests)
        Runtime.getRuntime().addShutdownHook(Thread({ plugins.close() }, "test-connector-loader-owner"))
    }
    fun ref(backend: String): ConnectorRef = registry.references().filter { it.id == backend }.also {
        require(it.size == 1) { "test backend requires one exact deployed connector: $backend" }
    }.single()

    /** SQL作者声明NULL语义，原生容量只取真实prepared SQL元数据。 */
    fun sqlOutputTypes(source: JdbcConnectionSpec, sql: String, aliases: Map<String, String>, authorNullability: Map<String, Boolean>): Map<String, ResultValueType> {
        require(authorNullability.keys == aliases.values.toSet()) { "raw SQL author nullability must cover every output" }
        val support = registry.require(source.connectorRef).compilation
        val factory = JavaTypeFactoryImpl(support.typeSystem)
        val result = linkedMapOf<String, ResultValueType>()
        JdbcConnectionFactory(registry).openReadOnly(source).use { connection -> connection.prepareStatement(sql).use { statement ->
            val metadata = requireNotNull(statement.metaData) { "raw SQL source must expose prepared output metadata" }
            for (index in 1..metadata.columnCount) {
                val label = metadata.getColumnLabel(index)
                val matches = aliases.filterKeys { it.equals(label, ignoreCase = true) }
                require(matches.size == 1) { "raw SQL metadata requires one declared alias: $label" }
                val alias = matches.values.single()
                val nullable = authorNullability.getValue(alias)
                require(nullable || metadata.isNullable(index) != ResultSetMetaData.columnNullable) { "raw SQL author denies source-declared nullable output: $alias" }
                val type = support.jdbcType(factory, JdbcColumnType(metadata.getColumnType(index), metadata.getColumnTypeName(index),
                    metadata.getPrecision(index).takeUnless { it < 0 }, metadata.getScale(index).takeUnless { it == Int.MIN_VALUE }, nullable))
                require(result.put(alias, SemanticNativeTypes.resultType(type)) == null) { "raw SQL produced a duplicate semantic alias: $alias" }
            }
        } }
        require(result.keys == authorNullability.keys) { "raw SQL metadata does not cover its declared outputs" }
        return result.toMap()
    }
    private fun driverName(backend: String): String = when (backend) {
        "h2" -> "org.h2.Driver"
        "duckdb" -> "org.duckdb.DuckDBDriver"
        else -> throw IllegalArgumentException("test driver not declared: $backend")
    }
    fun driver(backend: String): Driver {
        val access = registry.require(ref(backend)).access as JdbcAccess
        return Class.forName(driverName(backend), true, access.jdbc.javaClass.classLoader).getDeclaredConstructor().newInstance() as Driver
    }
    fun open(backend: String, url: String, user: String? = null, password: String? = null): Connection =
        requireNotNull(driver(backend).connect(url, Properties().apply {
            user?.let { setProperty("user", it) }; password?.let { setProperty("password", it) }
        })) { "test driver did not accept its declared URL" }

    fun physical(binding: ExecutablePhysicalExecutionBinding): ExecutablePhysicalExecutionBinding {
        return binding.copy(qualifiedTablePath = registry.require(binding.adoption.connectorRef).compilation.tablePath(binding.catalogName, binding.schemaName, binding.tableName))
    }

    fun adoption(contract: PhysicalDataContract, source: SourceConfiguration, evidence: PhysicalSourceEvidence): PhysicalSourceAdoption =
        PhysicalSourceAdoption(GovernanceProto.sourceAdoptionReference(contract.reference, source.bindingRef(), source.connectorRef,
            source.dialectId, contract.table, evidence), contract.reference, source.bindingRef(), source.connectorRef, source.dialectId,
            contract.table, evidence, "integration-test", java.time.Instant.parse("2026-07-21T00:00:00Z"))
}
