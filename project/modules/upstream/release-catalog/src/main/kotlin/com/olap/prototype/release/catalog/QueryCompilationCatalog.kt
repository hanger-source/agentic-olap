package com.olap.prototype.release.catalog

import com.olap.prototype.calculation.MetricDefinition
import com.olap.prototype.codec.GovernanceProto
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
    private val constraintNames = linkedMapOf<Pair<TableLocator, String>, String>()

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
                val contract = dataContract(source, dependency.dataContract)
                val projectedTable = NativeTableDependency(contract, adoption(source, contract, dependency.adoption))
                projectedTable.key() to projectedTable
            }.toMap()
            val contract = dataContract(source, source.dataContract)
            identity to ExecutablePhysicalExecutionBinding(
                bindingId = identity.substringBeforeLast('@'), bindingVersion = source.bindingVersion,
                eventId = source.eventId, eventVersion = source.eventVersion, logicUnitId = source.logicUnitId,
                logicUnitVersion = source.logicUnitVersion, logicalTableId = source.logicalTableId, logicalTableVersion = source.logicalTableVersion,
                qualifiedTablePath = listOf("native", name), dataContract = contract, adoption = adoption(source, contract, source.adoption),
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

    private fun dataContract(source: ExecutablePhysicalExecutionBinding, contract: PhysicalDataContract): PhysicalDataContract = PhysicalDataContract(
        PhysicalTableRef(physicalTables.getValue(contract.reference.key()), contract.reference.version), sources.getValue(contract.dataSourceId),
        projectedTable(source, contract.table), contract.columns.map { (name, value) ->
            column(source, contract.table, name) to PhysicalDataContract.Column(adoptColumn(value.ref), value.type)
        }.toMap(), constraints(source, contract.constraints, contract.table),
        contract.snapshotUniqueKeys.map { snapshotKey(source, contract.table, it) },
        contract.sourceCollections.map { declaration(source, contract.table, it) }, contract.originDiscoveredTableId,
    )

    private fun adoption(source: ExecutablePhysicalExecutionBinding, contract: PhysicalDataContract, original: PhysicalSourceAdoption): PhysicalSourceAdoption {
        val binding = SourceBindingRef(sources.getValue(original.binding.dataSourceId), original.binding.sourceIdentityHash)
        val owner = original.table
        val observed = original.evidence
        val evidence = PhysicalSourceEvidence(observed.discoverySnapshotId, observed.discoveredTableId, observed.schemaHash,
            observed.structureProvenance, observed.databaseProductName, observed.databaseProductVersion, observed.driverName, observed.driverVersion,
            observed.columns.map { (name, type) -> column(source, owner, name) to type }.toMap(), constraints(source, observed.constraints, owner),
            observed.snapshotUniqueKeys.map { SnapshotUniqueKeyProof(it.id, snapshotKey(source, owner, it.key)) },
            observed.sourceCollections.map { sourceCollection(source, owner, it) })
        val table = projectedTable(source, owner)
        return PhysicalSourceAdoption(GovernanceProto.sourceAdoptionReference(contract.reference, binding, original.connectorRef,
            original.dialect, table, evidence), contract.reference, binding, original.connectorRef, original.dialect, table, evidence,
            original.adoptedBy, original.adoptedAt)
    }

    private fun constraints(source: ExecutablePhysicalExecutionBinding, observed: JdbcTableConstraints, owner: JdbcTableConstraints.Table): JdbcTableConstraints {
        val unique = observed.uniqueKeys.filter { it.filterCondition == null && it.expressions == null }.map { key ->
            JdbcTableConstraints.UniqueKey(constraintName(source, owner, "unique", key.name), key.columns.map { column(source, owner, it) }, null, null)
        }
        val foreign = observed.importedKeys.map { key -> JdbcTableConstraints.ForeignKeyColumn(
            key.name?.let { constraintName(source, owner, "foreign", it) }, column(source, owner, key.column),
            projectedTable(source, key.referencedTable), column(source, key.referencedTable, key.referencedColumn), key.updateRule, key.deleteRule, key.keySequence,
        ) }
        val temporal = observed.temporalKeys.map { key -> JdbcTableConstraints.TemporalKey(
            JdbcTableConstraints.TemporalRule(constraintName(source, owner, "temporal", key.rule.name), key.rule.equalityColumns.map { column(source, owner, it) },
                key.rule.intervals.map { interval -> JdbcTableConstraints.TemporalInterval(column(source, owner, interval.validFromColumn), column(source, owner, interval.validToColumn),
                    interval.validFromInclusive, interval.validToInclusive, interval.openEndedValidTo) }), key.anchor,
        ) }
        return JdbcTableConstraints(observed.primaryKeyColumns.map { column(source, owner, it) }, unique, foreign, temporal)
    }
    private fun constraintName(source: ExecutablePhysicalExecutionBinding, owner: JdbcTableConstraints.Table, kind: String, name: String): String =
        constraintNames.getOrPut(coordinate(source, owner) to "$kind:$name") { "$kind-${constraintNames.size}" }
    private fun snapshotKey(source: ExecutablePhysicalExecutionBinding, owner: JdbcTableConstraints.Table, key: SnapshotUniqueKey): SnapshotUniqueKey =
        SnapshotUniqueKey(SnapshotUniqueKey.Rule(constraintName(source, owner, "snapshot", key.rule.name), key.rule.columns.map { column(source, owner, it) }), key.anchor)
    private fun declaration(source: ExecutablePhysicalExecutionBinding, owner: JdbcTableConstraints.Table, value: SourceCollectionDeclaration): SourceCollectionDeclaration =
        value.copy(collection = SealedSourceDataset.Collection(collections.getOrPut(value.key()) { "collection_${collections.size}" },
            projectedTable(source, owner), value.collection.ruleIds))
    private fun sourceCollection(source: ExecutablePhysicalExecutionBinding, owner: JdbcTableConstraints.Table, record: SourceCollectionRecord): SourceCollectionRecord {
        require(source.dataSourceId == record.dataSourceId && coordinate(source, owner) == coordinate(source, record.collection.table)) { "finite collection differs from its exact physical source owner" }
        val declared = declaration(source, owner, record.declaration())
        return SourceCollectionRecord(record.id, sources.getValue(record.dataSourceId), record.sourceIdentityHash, record.declarationSha256, record.sourceAuthority,
            record.ruleDocumentSha256, record.anchor, record.scope, declared.collection)
    }
    private fun projectedTable(source: ExecutablePhysicalExecutionBinding, owner: JdbcTableConstraints.Table): JdbcTableConstraints.Table =
        JdbcTableConstraints.Table("", "native", table(source, owner))
    private fun ownerTable(binding: ExecutablePhysicalExecutionBinding): JdbcTableConstraints.Table = JdbcTableConstraints.Table(binding.catalogName, binding.schemaName, binding.tableName)
    private fun coordinate(source: ExecutablePhysicalExecutionBinding, table: JdbcTableConstraints.Table): TableLocator = TableLocator(
        source.adoption.binding, source.dialect,
        java.util.List.copyOf(connectors.require(source.connectorRef).compilation.tablePath(table.catalog, table.schema, table.table)),
    )
    private fun adoptPhysicalTable(reference: String): String = physicalTables.getOrPut(reference) { "physical-${physicalTables.size}" }
    private fun adoptColumn(reference: String): String = refs.getOrPut(reference) { "field-${refs.size}${reference.substring(reference.lastIndexOf('@'))}" }
    private fun table(source: ExecutablePhysicalExecutionBinding, table: JdbcTableConstraints.Table): String = tables.getOrPut(coordinate(source, table)) { "relation-${tables.size}" }
    private fun column(source: ExecutablePhysicalExecutionBinding, table: JdbcTableConstraints.Table, name: String): String = columns.getOrPut(ColumnLocator(coordinate(source, table), name)) { "column-${columns.size}" }
    companion object { fun project(catalog: ExecutableCatalog, connectors: ConnectorRegistry): ExecutableCatalog = QueryCompilationCatalog(connectors).catalog(catalog) }
}
