package com.olap.prototype.engine.executable

import com.olap.prototype.engine.calcite.CalciteQueryCompiler
import com.olap.prototype.engine.calcite.FrozenCatalogSchema
import com.olap.prototype.expression.CalciteSqlRendering
import com.olap.prototype.integration.TestConnectors
import org.apache.calcite.avatica.util.TimeUnitRange
import org.apache.calcite.rel.core.Values
import org.apache.calcite.sql.`fun`.SqlStdOperatorTable
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path
import java.sql.Types
import java.time.LocalDateTime

class CalciteEmptyRelationExecutionTest {
    @TempDir lateinit var directory: Path
    @Test fun executesDuckDbDateBucketsAndExactAggregatesAfterImpossibleNotNullFilter() {
        NativeWarehouse(directory, "duckdb", listOf("CREATE SCHEMA analytics",
            "CREATE TABLE analytics.receipts (received_at TIMESTAMP(6) NOT NULL, amount DECIMAL(18,2), buyer VARCHAR(64))",
            "INSERT INTO analytics.receipts VALUES (TIMESTAMP '2028-01-01 12:30:00.123456', 12.34, '客户甲')")).use { warehouse ->
            warehouse.connection.schema = "analytics"
            val binding = warehouse.adopt("receipts", emptyMap())
            warehouse.connection.createStatement().use { statement ->
                for (empty in listOf(true, false)) for (global in listOf(false, true)) {
                    val builder = CalciteQueryCompiler.inputs(listOf(binding), binding.physicalColumnBindings.keys, emptySet(), TestConnectors.registry).builder
                    builder.scan(FrozenCatalogSchema.path(binding))
                    builder.filter(if (empty) builder.isNull(builder.field("received_at")) else builder.isNotNull(builder.field("received_at")))
                    if (empty) assertInstanceOf(Values::class.java, builder.peek(), "保留Calcite对不可能条件的空关系化简")
                    builder.project(listOf(builder.call(SqlStdOperatorTable.FLOOR, builder.field("received_at"), builder.rexBuilder.makeFlag(TimeUnitRange.DAY)),
                        builder.field("amount"), builder.field("buyer")), listOf("day", "amount", "buyer"))
                    builder.aggregate(if (global) builder.groupKey() else builder.groupKey(builder.field("day"), builder.field("buyer")),
                        builder.sum(false, "total", builder.field("amount")), builder.countStar("receipts"))
                    val sql = CalciteSqlRendering.render(CalciteQueryCompiler.optimize(builder.build(), emptyList()),
                        TestConnectors.registry.require(TestConnectors.ref("duckdb")).compilation).sql
                    statement.executeQuery(sql).use { rows ->
                        assertEquals(!empty || global, rows.next())
                        if (!empty || global) {
                            assertEquals(if (empty) 0L else 1L, rows.getLong("receipts"))
                            if (empty) assertNull(rows.getObject("total"), "空集合SUM不是零")
                            else assertEquals(0, BigDecimal("12.34").compareTo(rows.getBigDecimal("total")))
                            if (!global) {
                                assertEquals(LocalDateTime.parse("2028-01-01T00:00:00"), rows.getObject("day", LocalDateTime::class.java))
                                assertEquals("客户甲", rows.getString("buyer"))
                            }
                            assertEquals(Types.DECIMAL, rows.metaData.getColumnType(rows.findColumn("total")))
                            assertEquals(2, rows.metaData.getScale(rows.findColumn("total")))
                            assertFalse(rows.next())
                        }
                    }
                }
            }
        }
    }
}
