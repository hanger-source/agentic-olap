package com.olap.prototype.engine.calcite

import com.olap.prototype.connector.ConnectorRegistry
import com.olap.prototype.connector.ResolvedConnector
import com.olap.prototype.contract.JdbcSourceRequirement
import com.olap.prototype.contract.NativeTableDependency
import com.olap.prototype.engine.catalog.ExecutablePhysicalExecutionBinding
import com.olap.prototype.expression.SemanticExpression
import com.olap.prototype.warehouse.contract.JdbcTableConstraints
import org.apache.calcite.schema.SchemaPlus
import org.apache.calcite.schema.impl.AbstractSchema
import org.apache.calcite.tools.Frameworks
import org.apache.calcite.util.ImmutableBitSet

/** 只消费冻结列的schema，不打开数据库连接。 */
object FrozenCatalogSchema {
    data class TemporalKeys(val keys: List<JdbcTableConstraints.TemporalKey>, val adopt: (String) -> Unit)
    data class UnavailableKeys(val dataSourceId: String, val keys: List<ImmutableBitSet>)

    class SchemaBinding(val schema: SchemaPlus, val dialect: String, val connector: ResolvedConnector,
        val nativeTables: Map<String, NativeTableDependency>, private val adoptedSources: () -> List<JdbcSourceRequirement>) {
        fun sourceRequirements(): List<JdbcSourceRequirement> = adoptedSources()
    }

    fun bind(bindings: Collection<ExecutablePhysicalExecutionBinding>, requiredPhysicalRefs: Set<String>,
        requiredNativeTables: Set<SemanticExpression.TableArgument>, connectors: ConnectorRegistry): SchemaBinding {
        val sources = FrozenSourceTables.resolve(bindings, requiredPhysicalRefs, requiredNativeTables, connectors)
        val proofs = SourceProofs(sources)
        val root = Frameworks.createRootSchema(true)
        sources.tables.values.forEach { table ->
            var owner = root
            table.path.dropLast(1).forEach { component -> owner = owner.getSubSchema(component) ?: owner.add(component, AbstractSchema()) }
            owner.add(table.path.last(), FrozenCalciteTable(table, sources, proofs))
        }
        return SchemaBinding(root, sources.dialect, sources.connector, sources.nativeTables, proofs::requirements)
    }

    fun path(binding: ExecutablePhysicalExecutionBinding): List<String> = binding.qualifiedTablePath.toList()
}
