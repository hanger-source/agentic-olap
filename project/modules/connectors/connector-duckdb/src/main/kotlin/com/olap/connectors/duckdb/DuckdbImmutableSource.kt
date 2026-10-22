package com.olap.connectors.duckdb

import com.olap.prototype.runtime.RuntimeResourceLimitException
import com.olap.prototype.warehouse.SourceGuaranteeException
import com.olap.prototype.warehouse.contract.JdbcConnectionSpec
import com.olap.prototype.warehouse.contract.JdbcTableConstraints.DataAnchor
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection
import java.util.HexFormat
import java.util.concurrent.CancellationException

/** Hash the sealed file while its exact read-only connection owns the native database lock. */
object DuckdbImmutableSource {
    fun anchor(connection: Connection, spec: JdbcConnectionSpec, checkCancellation: () -> Unit): DataAnchor {
        checkCancellation()
        try {
            if (!spec.jdbcUrl.startsWith("jdbc:duckdb:") || !connection.isReadOnly || connection.metaData.databaseProductName != "DuckDB" || connection.metaData.databaseProductVersion != "v1.5.6")
                throw SourceGuaranteeException("IMMUTABLE_SOURCE_UNSUPPORTED")
            val name = spec.jdbcUrl.substring("jdbc:duckdb:".length)
            val file = Path.of(name)
            if (!file.isAbsolute || ';' in name || '?' in name || !Files.isRegularFile(file) || Files.exists(Path.of("$name.wal"))) throw SourceGuaranteeException("IMMUTABLE_SOURCE_NOT_SEALED")
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(file).use { input ->
                val bytes = ByteArray(65536)
                while (true) { val count = input.read(bytes); if (count == -1) break; checkCancellation(); digest.update(bytes, 0, count) }
            }
            return DataAnchor(HexFormat.of().formatHex(digest.digest()))
        } catch (failure: SourceGuaranteeException) { throw failure }
          catch (failure: RuntimeResourceLimitException) { throw failure }
          catch (failure: CancellationException) { throw failure }
          catch (failure: Exception) { throw SourceGuaranteeException("IMMUTABLE_SOURCE_ANCHOR_UNAVAILABLE") }
    }
}
