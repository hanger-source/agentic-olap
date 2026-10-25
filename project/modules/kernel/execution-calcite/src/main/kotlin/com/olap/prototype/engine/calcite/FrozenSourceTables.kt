package com.olap.prototype.engine.calcite

import com.olap.prototype.connector.ConnectorRegistry
import com.olap.prototype.connector.ResolvedConnector
import com.olap.prototype.contract.JdbcColumnType
import com.olap.prototype.contract.JdbcSourceRequirement
import com.olap.prototype.contract.NativeTableDependency
import com.olap.prototype.engine.catalog.ExecutablePhysicalExecutionBinding
import com.olap.prototype.expression.SemanticExpression.TableArgument
import com.olap.prototype.warehouse.contract.JdbcTableConstraints
import java.util.Collections

/** 本次实际读取的冻结表，列次序、用途和约束在进入Calcite前形成完整事实。 */
internal class FrozenSourceTables private constructor(val connector: ResolvedConnector, val dialect: String,
    val tables: Map<List<String>, Table>, val sources: List<JdbcSourceRequirement>, val nativeTables: Map<String, NativeTableDependency>) {
    class Table(val path: List<String>, columns: Map<String, JdbcColumnType>, val verifiedConstraints: JdbcTableConstraints?,
        val unavailableConstraints: JdbcSourceRequirement?, val sources: List<JdbcSourceRequirement>) {
        val columns: Map<String, JdbcColumnType> = Collections.unmodifiableMap(LinkedHashMap(columns))
        val names: List<String> = this.columns.keys.toList()
    }

    companion object {
        fun resolve(bindings: Collection<ExecutablePhysicalExecutionBinding>, physicalRefs: Set<String>, nativeRefs: Set<TableArgument>, connectors: ConnectorRegistry): FrozenSourceTables {
            val sources = rowSources(bindings, physicalRefs)
            val selectedNative = adoptNativeTables(bindings, nativeRefs, sources)
            val exactBinding = JdbcSourceRequirement.requireSingleBinding(sources)
            val first = sources.first()
            check(sources.all { it.bindingRef() == exactBinding && it.connectorRef == first.connectorRef && it.dialect == first.dialect }) {
                "one Calcite SQL plan requires one exact source binding"
            }
            val connector = connectors.require(first.connectorRef)
            val columns = orderedColumns(bindings, physicalRefs)
            val grouped = sources.filter { it.readsRows() }.groupByTo(linkedMapOf()) {
                connector.compilation.tablePath(it.table.catalog, it.table.schema, it.table.table)
            }
            val tables = grouped.mapValues { (path, rows) ->
                describe(path, requireNotNull(columns[path]) { "frozen table path differs from its exact connector path" }, rows, connector)
            }
            return FrozenSourceTables(connector, first.dialect, Collections.unmodifiableMap(tables), sources.toList(), selectedNative.toMap())
        }

        private fun describe(path: List<String>, columns: Map<String, JdbcColumnType>, sources: List<JdbcSourceRequirement>, connector: ResolvedConnector): Table {
            var verified: JdbcTableConstraints? = null
            var unavailable: JdbcSourceRequirement? = null
            sources.forEach { source ->
                if (!connector.access.supportsConstraintVerification(source.dataContract, source.adoption)) unavailable = source
                else {
                    val constraints = source.dataContract.constraints
                    check(verified == null || verified == constraints) { "conflicting adopted source constraints for one physical table" }
                    verified = constraints
                }
            }
            return Table(path.toList(), columns, verified, unavailable, sources.toList())
        }

        private fun orderedColumns(bindings: Collection<ExecutablePhysicalExecutionBinding>, refs: Set<String>): Map<List<String>, Map<String, JdbcColumnType>> {
            val tables = linkedMapOf<List<String>, MutableMap<String, JdbcColumnType>>()
            bindings.forEach { binding ->
                val columns = tables.getOrPut(FrozenCatalogSchema.path(binding)) { linkedMapOf() }
                binding.physicalColumnBindings.forEach { (ref, name) ->
                    if (ref in refs) {
                        val type = requireNotNull(binding.physicalColumnTypes[ref])
                        val previous = columns.putIfAbsent(name, type)
                        check(previous == null || previous == type) { "conflicting frozen physical column type: $name" }
                    }
                }
            }
            return tables
        }

        private fun rowSources(bindings: Collection<ExecutablePhysicalExecutionBinding>, refs: Set<String>): MutableList<JdbcSourceRequirement> {
            val merged = linkedMapOf<String, JdbcSourceRequirement>()
            bindings.forEach { binding ->
                binding.adoption.requireContract(binding.dataContract)
                check(binding.physicalColumnBindings.keys == binding.physicalColumnTypes.keys) { "frozen physical column names and types must have identical exact refs" }
                val columns = linkedMapOf<String, JdbcColumnType>()
                binding.physicalColumnBindings.forEach { (ref, name) ->
                    if (ref in refs) {
                        check(ref == binding.dataContract.columns[name]?.ref) { "binding column differs from exact data contract version: $ref" }
                        columns[name] = requireNotNull(binding.physicalColumnTypes[ref])
                    }
                }
                val source = JdbcSourceRequirement(binding.dataContract, binding.adoption, columns, setOf(JdbcSourceRequirement.Use.ROWS), emptySet())
                val key = "${source.physicalTableId}@${source.physicalTableVersion}"
                merged[key] = merged[key]?.let { mergeRows(it, source) } ?: source
            }
            return merged.values.toMutableList()
        }

        private fun mergeRows(left: JdbcSourceRequirement, right: JdbcSourceRequirement): JdbcSourceRequirement {
            check(left.dataContract == right.dataContract && left.adoption == right.adoption) {
                "conflicting frozen source for one exact physical table"
            }
            val columns = LinkedHashMap(left.columns)
            right.columns.forEach { (name, type) -> val previous = columns.putIfAbsent(name, type); check(previous == null || previous == type) { "conflicting frozen column type: $name" } }
            return JdbcSourceRequirement(left.dataContract, left.adoption, columns, left.uses, emptySet())
        }

        private fun adoptNativeTables(bindings: Collection<ExecutablePhysicalExecutionBinding>, refs: Set<TableArgument>, sources: MutableList<JdbcSourceRequirement>): Map<String, NativeTableDependency> {
            val available = linkedMapOf<String, NativeTableDependency>()
            bindings.forEach { binding -> binding.nativeTableDependencies.forEach { (key, table) ->
                val previous = available.putIfAbsent(key, table)
                require(previous == null || previous == table) { "conflicting frozen native table dependency: $key" }
            } }
            val exactBinding = JdbcSourceRequirement.requireSingleBinding(sources)
            val owner = sources.first()
            return refs.associate { ref ->
                val table = requireNotNull(available[ref.key()]) { "native table is outside the exact frozen dependency closure: ${ref.key()}" }
                require(table.key() == ref.key()) { "native table differs from its exact frozen dependency reference" }
                table.requireSource(exactBinding, owner.connectorRef, owner.dialect)
                val row = sources.indexOfFirst { "${it.physicalTableId}@${it.physicalTableVersion}" == table.key() }
                if (row >= 0) {
                    val source = sources[row]
                    check(source.dataContract == table.dataContract && source.adoption == table.adoption) { "native table differs from the exact row source adoption" }
                    sources[row] = source.withUse(JdbcSourceRequirement.Use.NATIVE_TABLE)
                } else sources.add(JdbcSourceRequirement(table.dataContract, table.adoption, emptyMap(), setOf(JdbcSourceRequirement.Use.NATIVE_TABLE), emptySet()))
                ref.key() to table
            }
        }
    }
}
