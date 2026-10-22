package com.olap.prototype.release.catalog

import com.olap.prototype.calculation.MetricDefinition
import com.olap.prototype.connector.ConnectorRegistry
import com.olap.prototype.contract.NativeTableDependency
import com.olap.prototype.engine.catalog.*
import com.olap.prototype.expression.SemanticExpression
import com.olap.prototype.expression.SemanticExpressionTree
import com.olap.prototype.member.MemberSource
import com.olap.prototype.warehouse.contract.*

/** 无物理定位的正式编译投影；只重命名坐标，不创造键、类型或来源保证。 */
class QueryCompilationCatalog private constructor(private val connectors: ConnectorRegistry) {
    private data class TableLocator(val binding: SourceBindingRef, val dialect: String, val path: List<String>)
    private data class ColumnLocator(val table: TableLocator, val name: String)
    private val sources = linkedMapOf<String, String>()
    private val tables = linkedMapOf<TableLocator, String>()
    private val columns = linkedMapOf<ColumnLocator, String>()
    private val bindings = linkedMapOf<String, String>()
    private val refs = linkedMapOf<String, String>()
    private val collections = linkedMapOf<String, String>()
    private val physicalTables = linkedMapOf<String, String>()

    private fun catalog(original: ExecutableCatalog): ExecutableCatalog {
        val ordered = original.physicalExecutionBindings.toSortedMap()
        ordered.forEach { (key, source) ->
            bindings[key] = "binding-${bindings.size}@${source.bindingVersion}"
            sources.getOrPut(source.dataSourceId) { "source-${sources.size}" }
            table(source, ownerTable(source))
            adoptPhysicalTable("${source.physicalTableId}@${source.physicalTableVersion}")
            source.nativeTableDependencies.forEach { (reference, dependency) ->
                require(reference == dependency.key()) { "native table differs from its exact compilation reference" }
                adoptPhysicalTable(reference)
                table(source, dependency.table)
            }
            source.physicalColumnBindings.toSortedMap().forEach { (reference, name) ->
                adoptColumn(reference)
                column(source, ownerTable(source), name)
            }
        }
        fun bindingId(id: String, version: String): String = bindings.getValue("$id@$version").substringBeforeLast('@')
        val projected = ordered.map { (key, source) ->
            val identity = bindings.getValue(key)
            val name = table(source, ownerTable(source))
            val nativeTables = source.nativeTableDependencies.map { (reference, dependency) ->
                val projectedTable = NativeTableDependency(physicalTables.getValue(reference), dependency.physicalTableVersion,
                    sources.getValue(dependency.dataSourceId), dependency.dialect, JdbcTableConstraints.Table("", "native", table(source, dependency.table)),
                    evidence(source, dependency.sourceConstraints, dependency.table))
                projectedTable.key() to projectedTable
            }.toMap()
            identity to ExecutablePhysicalExecutionBinding(
                bindingId = identity.substringBeforeLast('@'), bindingVersion = source.bindingVersion,
                eventId = source.eventId, eventVersion = source.eventVersion, logicUnitId = source.logicUnitId,
                logicUnitVersion = source.logicUnitVersion, logicalTableId = source.logicalTableId, logicalTableVersion = source.logicalTableVersion,
                physicalTableId = physicalTables.getValue("${source.physicalTableId}@${source.physicalTableVersion}"), physicalTableVersion = source.physicalTableVersion,
                dataSourceId = sources.getValue(source.dataSourceId), connectorRef = source.connectorRef, qualifiedTablePath = listOf("native", name), dialect = source.dialect,
                catalogName = "", schemaName = "native", tableName = name, sourceConstraints = evidence(source, source.sourceConstraints, ownerTable(source)),
                snapshotUniqueKeys = source.snapshotUniqueKeys.map { snapshot -> SnapshotUniqueKey(SnapshotUniqueKey.Rule(snapshot.rule.name,
                    snapshot.rule.columns.map { column(source, ownerTable(source), it) }), snapshot.anchor) },
                nativeTableDependencies = nativeTables, logicUnitBindings = source.logicUnitBindings.mapValues { expression(it.value) },
                physicalColumnBindings = source.physicalColumnBindings.map { (reference, fieldName) -> refs.getValue(reference) to column(source, ownerTable(source), fieldName) }.toMap(),
                physicalColumnTypes = source.physicalColumnTypes.mapKeys { refs.getValue(it.key) },
            )
        }.toMap()
        return ExecutableCatalog(
            manifest = original.manifest, metrics = original.metrics.mapValues { (_, value) ->
                val definition = value.definition
                if (definition is MetricDefinition.FactMeasure) value.copy(definition = MetricDefinition.FactMeasure(definition.event,
                    expression(definition.input), definition.aggregation, definition.distinct, definition.privatePredicate?.let(::expression),
                    definition.roles, definition.snapshotRule, definition.valueContract, definition.aggregationRounding)) else value
            }, metricInstances = original.metricInstances, memberSources = original.memberSources.mapValues { (_, source) -> when (source) {
                is MemberSource.Projection -> MemberSource.Projection(source.id, source.version, source.definition, source.outputExpressions.mapValues { expression(it.value) })
                is MemberSource.Computed -> source
            } },
            relationFilters = original.relationFilters, analysisDimensions = original.analysisDimensions,
            events = original.events.mapValues { (_, value) -> value.copy(baseFilter = value.baseFilter?.let(::expression)) },
            topologies = original.topologies, dimensions = original.dimensions,
            dimensionBindings = original.dimensionBindings.mapValues { (_, value) -> ExecutableDimensionBinding(value.bindingId, value.bindingVersion,
                value.dimensionId, value.dimensionVersion, value.eventId, value.eventVersion, value.topologyId, value.topologyVersion,
                value.logicUnitId, value.logicUnitVersion, expression(value.expression), value.accessPath, value.roles, value.joinPathRefs,
                bindingId(value.physicalExecutionBindingId, value.physicalExecutionBindingVersion), value.physicalExecutionBindingVersion) },
            logicUnits = original.logicUnits.mapValues { (_, value) -> ExecutableLogicUnit(value.logicUnitId, value.logicUnitVersion, value.valueType,
                value.valueShape, value.topologyId, value.topologyVersion, value.logicUnitCode, value.displayName,
                bindingId(value.physicalExecutionBindingId, value.physicalExecutionBindingVersion), value.physicalExecutionBindingVersion, value.outputSymbols) },
            timeAnchors = original.timeAnchors.mapValues { (_, value) -> ExecutableTimeAnchor(value.eventId, value.eventVersion, value.analysisDimensionId,
                value.analysisDimensionVersion, value.levelCode, value.topologyId, value.topologyVersion, value.topologyImplementationNamespaceCode,
                value.topologyImplementationNamespaceDisplayName, value.effectiveTimeLogicUnitId, value.effectiveTimeLogicUnitVersion,
                value.effectiveTimeLogicUnitDisplayName, bindingId(value.physicalExecutionBindingId, value.physicalExecutionBindingVersion), value.physicalExecutionBindingVersion, value.timeRole) },
            joinPaths = original.joinPaths, physicalExecutionBindings = projected,
        )
    }
    private fun expression(value: SemanticExpression): SemanticExpression = SemanticExpressionTree.rewrite(value) { node ->
        when {
            node is SemanticExpression.NativeCall -> SemanticExpression.NativeCall(node.connectorRef, node.function, node.arguments.map { argument -> when (argument) {
                is SemanticExpression.ScalarArgument -> argument
                is SemanticExpression.TableArgument -> argument.copy(physicalTableId = requireNotNull(physicalTables[argument.key()]) { "native table is outside the exact compilation closure" })
            } })
            node is SemanticExpression.Reference && node.referenceKind == SemanticExpression.ReferenceKind.PHYSICAL_COLUMN -> {
                val key = requireNotNull(refs["${node.id}@${node.version}"]) { "source expression column is missing" }
                node.copy(id = key.substringBeforeLast('@'))
            }
            else -> node
        }
    }

    private fun evidence(source: ExecutablePhysicalExecutionBinding, evidence: PhysicalSourceConstraints, owner: JdbcTableConstraints.Table): PhysicalSourceConstraints {
        val observed = evidence.constraints
        val unique = observed.uniqueKeys.filter { it.filterCondition == null && it.expressions == null }.mapIndexed { index, key ->
            JdbcTableConstraints.UniqueKey("unique-$index", key.columns.map { column(source, owner, it) }, null, null)
        }
        val foreignNames = linkedMapOf<String, String>()
        val foreign = observed.importedKeys.map { key -> JdbcTableConstraints.ForeignKeyColumn(
            key.name?.let { foreignNames.getOrPut(it) { "foreign-${foreignNames.size}" } }, column(source, owner, key.column),
            JdbcTableConstraints.Table("", "native", table(source, key.referencedTable)), column(source, key.referencedTable, key.referencedColumn), key.updateRule, key.deleteRule, key.keySequence,
        ) }
        val temporal = observed.temporalKeys.mapIndexed { index, key -> JdbcTableConstraints.TemporalKey(
            JdbcTableConstraints.TemporalRule("temporal-$index", key.rule.equalityColumns.map { column(source, owner, it) },
                key.rule.intervals.map { interval -> JdbcTableConstraints.TemporalInterval(column(source, owner, interval.validFromColumn), column(source, owner, interval.validToColumn),
                    interval.validFromInclusive, interval.validToInclusive, interval.openEndedValidTo) }), key.anchor,
        ) }
        return PhysicalSourceConstraints(evidence.discoverySnapshotId, evidence.schemaHash, evidence.sourceIdentityHash, evidence.connectorRef,
            evidence.databaseProductName, evidence.databaseProductVersion, evidence.driverName, evidence.driverVersion,
            JdbcTableConstraints(observed.primaryKeyColumns.map { column(source, owner, it) }, unique, foreign, temporal),
            evidence.columnVersionRefs.map { (name, reference) -> column(source, owner, name) to adoptColumn(reference) }.toMap(),
            evidence.sourceCollections.map { sourceCollection(source, owner, it) }, evidence.structureProvenance)
    }
    private fun sourceCollection(source: ExecutablePhysicalExecutionBinding, owner: JdbcTableConstraints.Table, record: SourceCollectionRecord): SourceCollectionRecord {
        require(source.dataSourceId == record.dataSourceId && coordinate(source, owner) == coordinate(source, record.collection.table)) { "finite collection differs from its exact physical source owner" }
        return SourceCollectionRecord(record.id, sources.getValue(record.dataSourceId), record.sourceIdentityHash, record.declarationSha256, record.sourceAuthority,
            record.ruleDocumentSha256, record.anchor, record.scope, SealedSourceDataset.Collection(collections.getOrPut(record.id) { "collection_${collections.size}" },
                JdbcTableConstraints.Table(null, "native", table(source, record.collection.table)), record.collection.ruleIds))
    }
    private fun ownerTable(binding: ExecutablePhysicalExecutionBinding): JdbcTableConstraints.Table = JdbcTableConstraints.Table(binding.catalogName, binding.schemaName, binding.tableName)
    private fun coordinate(source: ExecutablePhysicalExecutionBinding, table: JdbcTableConstraints.Table): TableLocator = TableLocator(
        SourceBindingRef(source.dataSourceId, source.sourceConstraints.sourceIdentityHash), source.dialect,
        java.util.List.copyOf(connectors.require(source.connectorRef).compilation.tablePath(table.catalog, table.schema, table.table)),
    )
    private fun adoptPhysicalTable(reference: String): String = physicalTables.getOrPut(reference) { "physical-${physicalTables.size}" }
    private fun adoptColumn(reference: String): String = refs.getOrPut(reference) { "field-${refs.size}${reference.substring(reference.lastIndexOf('@'))}" }
    private fun table(source: ExecutablePhysicalExecutionBinding, table: JdbcTableConstraints.Table): String = tables.getOrPut(coordinate(source, table)) { "relation-${tables.size}" }
    private fun column(source: ExecutablePhysicalExecutionBinding, table: JdbcTableConstraints.Table, name: String): String = columns.getOrPut(ColumnLocator(coordinate(source, table), name)) { "column-${columns.size}" }
    companion object { fun project(catalog: ExecutableCatalog, connectors: ConnectorRegistry): ExecutableCatalog = QueryCompilationCatalog(connectors).catalog(catalog) }
}
