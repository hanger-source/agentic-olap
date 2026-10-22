package com.olap.prototype.codec

/** 正式领域消息的显式字段转换。 */
internal fun GovernanceReadRequestsPageInputToProto(value: com.olap.prototype.governance.contract.GovernanceReadRequests.PageInput): com.olap.prototype.wire.governance.GovernanceReadRequestsPageInput = com.olap.prototype.wire.governance.GovernanceReadRequestsPageInput.newBuilder().apply {
    value.offset?.let { setOffset(it) }
    value.limit?.let { setLimit(it) }
}.build()
internal fun GovernanceReadRequestsPageInputFromProto(value: com.olap.prototype.wire.governance.GovernanceReadRequestsPageInput): com.olap.prototype.governance.contract.GovernanceReadRequests.PageInput = com.olap.prototype.governance.contract.GovernanceReadRequests.PageInput(
    offset = if (value.hasOffset()) value.getOffset() else null,
    limit = if (value.hasLimit()) value.getLimit() else null
)

internal fun GovernanceReadRequestsPageBusinessLineRecordToProto(value: com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.governance.contract.BusinessLineRecord>): com.olap.prototype.wire.governance.GovernanceReadRequestsPageBusinessLineRecord = com.olap.prototype.wire.governance.GovernanceReadRequestsPageBusinessLineRecord.newBuilder().apply {
    addAllItems(value.items.map { BusinessLineRecordToProto(it) })
    setTotal(value.total)
    value.nextOffset?.let { setNextOffset(it) }
}.build()
internal fun GovernanceReadRequestsPageBusinessLineRecordFromProto(value: com.olap.prototype.wire.governance.GovernanceReadRequestsPageBusinessLineRecord): com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.governance.contract.BusinessLineRecord> = com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.governance.contract.BusinessLineRecord>(
    items = value.getItemsList().map { BusinessLineRecordFromProto(it) },
    total = value.getTotal(),
    nextOffset = if (value.hasNextOffset()) value.getNextOffset() else null
)

internal fun GovernanceReadRequestsPagePhysicalTableRecordToProto(value: com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.governance.contract.PhysicalTableRecord>): com.olap.prototype.wire.governance.GovernanceReadRequestsPagePhysicalTableRecord = com.olap.prototype.wire.governance.GovernanceReadRequestsPagePhysicalTableRecord.newBuilder().apply {
    addAllItems(value.items.map { PhysicalTableRecordToProto(it) })
    setTotal(value.total)
    value.nextOffset?.let { setNextOffset(it) }
}.build()
internal fun GovernanceReadRequestsPagePhysicalTableRecordFromProto(value: com.olap.prototype.wire.governance.GovernanceReadRequestsPagePhysicalTableRecord): com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.governance.contract.PhysicalTableRecord> = com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.governance.contract.PhysicalTableRecord>(
    items = value.getItemsList().map { PhysicalTableRecordFromProto(it) },
    total = value.getTotal(),
    nextOffset = if (value.hasNextOffset()) value.getNextOffset() else null
)

internal fun GovernanceReadRequestsPageLogicalTableRecordToProto(value: com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.governance.contract.LogicalTableRecord>): com.olap.prototype.wire.governance.GovernanceReadRequestsPageLogicalTableRecord = com.olap.prototype.wire.governance.GovernanceReadRequestsPageLogicalTableRecord.newBuilder().apply {
    addAllItems(value.items.map { LogicalTableRecordToProto(it) })
    setTotal(value.total)
    value.nextOffset?.let { setNextOffset(it) }
}.build()
internal fun GovernanceReadRequestsPageLogicalTableRecordFromProto(value: com.olap.prototype.wire.governance.GovernanceReadRequestsPageLogicalTableRecord): com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.governance.contract.LogicalTableRecord> = com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.governance.contract.LogicalTableRecord>(
    items = value.getItemsList().map { LogicalTableRecordFromProto(it) },
    total = value.getTotal(),
    nextOffset = if (value.hasNextOffset()) value.getNextOffset() else null
)

internal fun GovernanceReadRequestsPageTopologyRecordToProto(value: com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.governance.contract.TopologyRecord>): com.olap.prototype.wire.governance.GovernanceReadRequestsPageTopologyRecord = com.olap.prototype.wire.governance.GovernanceReadRequestsPageTopologyRecord.newBuilder().apply {
    addAllItems(value.items.map { TopologyRecordToProto(it) })
    setTotal(value.total)
    value.nextOffset?.let { setNextOffset(it) }
}.build()
internal fun GovernanceReadRequestsPageTopologyRecordFromProto(value: com.olap.prototype.wire.governance.GovernanceReadRequestsPageTopologyRecord): com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.governance.contract.TopologyRecord> = com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.governance.contract.TopologyRecord>(
    items = value.getItemsList().map { TopologyRecordFromProto(it) },
    total = value.getTotal(),
    nextOffset = if (value.hasNextOffset()) value.getNextOffset() else null
)

internal fun GovernanceReadRequestsPageEventRecordToProto(value: com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.governance.contract.EventRecord>): com.olap.prototype.wire.governance.GovernanceReadRequestsPageEventRecord = com.olap.prototype.wire.governance.GovernanceReadRequestsPageEventRecord.newBuilder().apply {
    addAllItems(value.items.map { EventRecordToProto(it) })
    setTotal(value.total)
    value.nextOffset?.let { setNextOffset(it) }
}.build()
internal fun GovernanceReadRequestsPageEventRecordFromProto(value: com.olap.prototype.wire.governance.GovernanceReadRequestsPageEventRecord): com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.governance.contract.EventRecord> = com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.governance.contract.EventRecord>(
    items = value.getItemsList().map { EventRecordFromProto(it) },
    total = value.getTotal(),
    nextOffset = if (value.hasNextOffset()) value.getNextOffset() else null
)

internal fun GovernanceReadRequestsPageMetricRecordToProto(value: com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.governance.contract.MetricRecord>): com.olap.prototype.wire.governance.GovernanceReadRequestsPageMetricRecord = com.olap.prototype.wire.governance.GovernanceReadRequestsPageMetricRecord.newBuilder().apply {
    addAllItems(value.items.map { MetricRecordToProto(it) })
    setTotal(value.total)
    value.nextOffset?.let { setNextOffset(it) }
}.build()
internal fun GovernanceReadRequestsPageMetricRecordFromProto(value: com.olap.prototype.wire.governance.GovernanceReadRequestsPageMetricRecord): com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.governance.contract.MetricRecord> = com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.governance.contract.MetricRecord>(
    items = value.getItemsList().map { MetricRecordFromProto(it) },
    total = value.getTotal(),
    nextOffset = if (value.hasNextOffset()) value.getNextOffset() else null
)

internal fun GovernanceReadRequestsPageMemberDefinitionRecordToProto(value: com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.governance.contract.MemberDefinitionRecord>): com.olap.prototype.wire.governance.GovernanceReadRequestsPageMemberDefinitionRecord = com.olap.prototype.wire.governance.GovernanceReadRequestsPageMemberDefinitionRecord.newBuilder().apply {
    addAllItems(value.items.map { MemberDefinitionRecordToProto(it) })
    setTotal(value.total)
    value.nextOffset?.let { setNextOffset(it) }
}.build()
internal fun GovernanceReadRequestsPageMemberDefinitionRecordFromProto(value: com.olap.prototype.wire.governance.GovernanceReadRequestsPageMemberDefinitionRecord): com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.governance.contract.MemberDefinitionRecord> = com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.governance.contract.MemberDefinitionRecord>(
    items = value.getItemsList().map { MemberDefinitionRecordFromProto(it) },
    total = value.getTotal(),
    nextOffset = if (value.hasNextOffset()) value.getNextOffset() else null
)

internal fun GovernanceReadRequestsPageDimensionRecordToProto(value: com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.governance.contract.DimensionRecord>): com.olap.prototype.wire.governance.GovernanceReadRequestsPageDimensionRecord = com.olap.prototype.wire.governance.GovernanceReadRequestsPageDimensionRecord.newBuilder().apply {
    addAllItems(value.items.map { DimensionRecordToProto(it) })
    setTotal(value.total)
    value.nextOffset?.let { setNextOffset(it) }
}.build()
internal fun GovernanceReadRequestsPageDimensionRecordFromProto(value: com.olap.prototype.wire.governance.GovernanceReadRequestsPageDimensionRecord): com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.governance.contract.DimensionRecord> = com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.governance.contract.DimensionRecord>(
    items = value.getItemsList().map { DimensionRecordFromProto(it) },
    total = value.getTotal(),
    nextOffset = if (value.hasNextOffset()) value.getNextOffset() else null
)

internal fun GovernanceReadRequestsPageSubjectAreaRecordToProto(value: com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.governance.contract.SubjectAreaRecord>): com.olap.prototype.wire.governance.GovernanceReadRequestsPageSubjectAreaRecord = com.olap.prototype.wire.governance.GovernanceReadRequestsPageSubjectAreaRecord.newBuilder().apply {
    addAllItems(value.items.map { SubjectAreaRecordToProto(it) })
    setTotal(value.total)
    value.nextOffset?.let { setNextOffset(it) }
}.build()
internal fun GovernanceReadRequestsPageSubjectAreaRecordFromProto(value: com.olap.prototype.wire.governance.GovernanceReadRequestsPageSubjectAreaRecord): com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.governance.contract.SubjectAreaRecord> = com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.governance.contract.SubjectAreaRecord>(
    items = value.getItemsList().map { SubjectAreaRecordFromProto(it) },
    total = value.getTotal(),
    nextOffset = if (value.hasNextOffset()) value.getNextOffset() else null
)

internal fun GovernanceReadRequestsSubjectPageInputToProto(value: com.olap.prototype.governance.contract.GovernanceReadRequests.SubjectPageInput): com.olap.prototype.wire.governance.GovernanceReadRequestsSubjectPageInput = com.olap.prototype.wire.governance.GovernanceReadRequestsSubjectPageInput.newBuilder().apply {
    value.subjectAreaId?.let { setSubjectAreaId(it) }
    value.offset?.let { setOffset(it) }
    value.limit?.let { setLimit(it) }
}.build()
internal fun GovernanceReadRequestsSubjectPageInputFromProto(value: com.olap.prototype.wire.governance.GovernanceReadRequestsSubjectPageInput): com.olap.prototype.governance.contract.GovernanceReadRequests.SubjectPageInput = com.olap.prototype.governance.contract.GovernanceReadRequests.SubjectPageInput(
    subjectAreaId = if (value.hasSubjectAreaId()) value.getSubjectAreaId() else null,
    offset = if (value.hasOffset()) value.getOffset() else null,
    limit = if (value.hasLimit()) value.getLimit() else null
)

internal fun GovernanceReadRequestsPageScenarioRecordToProto(value: com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.governance.contract.ScenarioRecord>): com.olap.prototype.wire.governance.GovernanceReadRequestsPageScenarioRecord = com.olap.prototype.wire.governance.GovernanceReadRequestsPageScenarioRecord.newBuilder().apply {
    addAllItems(value.items.map { ScenarioRecordToProto(it) })
    setTotal(value.total)
    value.nextOffset?.let { setNextOffset(it) }
}.build()
internal fun GovernanceReadRequestsPageScenarioRecordFromProto(value: com.olap.prototype.wire.governance.GovernanceReadRequestsPageScenarioRecord): com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.governance.contract.ScenarioRecord> = com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.governance.contract.ScenarioRecord>(
    items = value.getItemsList().map { ScenarioRecordFromProto(it) },
    total = value.getTotal(),
    nextOffset = if (value.hasNextOffset()) value.getNextOffset() else null
)

internal fun GovernanceReadRequestsVersionPageInputToProto(value: com.olap.prototype.governance.contract.GovernanceReadRequests.VersionPageInput): com.olap.prototype.wire.governance.GovernanceReadRequestsVersionPageInput = com.olap.prototype.wire.governance.GovernanceReadRequestsVersionPageInput.newBuilder().apply {
    value.id?.let { setId(it) }
    setVersion(value.version)
    value.offset?.let { setOffset(it) }
    value.limit?.let { setLimit(it) }
}.build()
internal fun GovernanceReadRequestsVersionPageInputFromProto(value: com.olap.prototype.wire.governance.GovernanceReadRequestsVersionPageInput): com.olap.prototype.governance.contract.GovernanceReadRequests.VersionPageInput = com.olap.prototype.governance.contract.GovernanceReadRequests.VersionPageInput(
    id = if (value.hasId()) value.getId() else null,
    version = value.getVersion(),
    offset = if (value.hasOffset()) value.getOffset() else null,
    limit = if (value.hasLimit()) value.getLimit() else null
)

internal fun GovernanceReadRequestsPageMemberSourceRecordToProto(value: com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.governance.contract.MemberSourceRecord>): com.olap.prototype.wire.governance.GovernanceReadRequestsPageMemberSourceRecord = com.olap.prototype.wire.governance.GovernanceReadRequestsPageMemberSourceRecord.newBuilder().apply {
    addAllItems(value.items.map { MemberSourceRecordToProto(it) })
    setTotal(value.total)
    value.nextOffset?.let { setNextOffset(it) }
}.build()
internal fun GovernanceReadRequestsPageMemberSourceRecordFromProto(value: com.olap.prototype.wire.governance.GovernanceReadRequestsPageMemberSourceRecord): com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.governance.contract.MemberSourceRecord> = com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.governance.contract.MemberSourceRecord>(
    items = value.getItemsList().map { MemberSourceRecordFromProto(it) },
    total = value.getTotal(),
    nextOffset = if (value.hasNextOffset()) value.getNextOffset() else null
)

internal fun GovernanceReadRequestsPageSubjectAreaMetricRefRecordToProto(value: com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.governance.contract.SubjectAreaMetricRefRecord>): com.olap.prototype.wire.governance.GovernanceReadRequestsPageSubjectAreaMetricRefRecord = com.olap.prototype.wire.governance.GovernanceReadRequestsPageSubjectAreaMetricRefRecord.newBuilder().apply {
    addAllItems(value.items.map { SubjectAreaMetricRefRecordToProto(it) })
    setTotal(value.total)
    value.nextOffset?.let { setNextOffset(it) }
}.build()
internal fun GovernanceReadRequestsPageSubjectAreaMetricRefRecordFromProto(value: com.olap.prototype.wire.governance.GovernanceReadRequestsPageSubjectAreaMetricRefRecord): com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.governance.contract.SubjectAreaMetricRefRecord> = com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.governance.contract.SubjectAreaMetricRefRecord>(
    items = value.getItemsList().map { SubjectAreaMetricRefRecordFromProto(it) },
    total = value.getTotal(),
    nextOffset = if (value.hasNextOffset()) value.getNextOffset() else null
)

internal fun GovernanceReadRequestsPageSubjectAreaAnalysisDimensionRefRecordToProto(value: com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.governance.contract.SubjectAreaAnalysisDimensionRefRecord>): com.olap.prototype.wire.governance.GovernanceReadRequestsPageSubjectAreaAnalysisDimensionRefRecord = com.olap.prototype.wire.governance.GovernanceReadRequestsPageSubjectAreaAnalysisDimensionRefRecord.newBuilder().apply {
    addAllItems(value.items.map { SubjectAreaAnalysisDimensionRefRecordToProto(it) })
    setTotal(value.total)
    value.nextOffset?.let { setNextOffset(it) }
}.build()
internal fun GovernanceReadRequestsPageSubjectAreaAnalysisDimensionRefRecordFromProto(value: com.olap.prototype.wire.governance.GovernanceReadRequestsPageSubjectAreaAnalysisDimensionRefRecord): com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.governance.contract.SubjectAreaAnalysisDimensionRefRecord> = com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.governance.contract.SubjectAreaAnalysisDimensionRefRecord>(
    items = value.getItemsList().map { SubjectAreaAnalysisDimensionRefRecordFromProto(it) },
    total = value.getTotal(),
    nextOffset = if (value.hasNextOffset()) value.getNextOffset() else null
)

internal fun GovernanceReadRequestsPageSubjectAreaScenarioRefRecordToProto(value: com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.governance.contract.SubjectAreaScenarioRefRecord>): com.olap.prototype.wire.governance.GovernanceReadRequestsPageSubjectAreaScenarioRefRecord = com.olap.prototype.wire.governance.GovernanceReadRequestsPageSubjectAreaScenarioRefRecord.newBuilder().apply {
    addAllItems(value.items.map { SubjectAreaScenarioRefRecordToProto(it) })
    setTotal(value.total)
    value.nextOffset?.let { setNextOffset(it) }
}.build()
internal fun GovernanceReadRequestsPageSubjectAreaScenarioRefRecordFromProto(value: com.olap.prototype.wire.governance.GovernanceReadRequestsPageSubjectAreaScenarioRefRecord): com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.governance.contract.SubjectAreaScenarioRefRecord> = com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.governance.contract.SubjectAreaScenarioRefRecord>(
    items = value.getItemsList().map { SubjectAreaScenarioRefRecordFromProto(it) },
    total = value.getTotal(),
    nextOffset = if (value.hasNextOffset()) value.getNextOffset() else null
)

internal fun GovernanceReadRequestsPageScenarioMetricRefRecordToProto(value: com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.governance.contract.ScenarioMetricRefRecord>): com.olap.prototype.wire.governance.GovernanceReadRequestsPageScenarioMetricRefRecord = com.olap.prototype.wire.governance.GovernanceReadRequestsPageScenarioMetricRefRecord.newBuilder().apply {
    addAllItems(value.items.map { ScenarioMetricRefRecordToProto(it) })
    setTotal(value.total)
    value.nextOffset?.let { setNextOffset(it) }
}.build()
internal fun GovernanceReadRequestsPageScenarioMetricRefRecordFromProto(value: com.olap.prototype.wire.governance.GovernanceReadRequestsPageScenarioMetricRefRecord): com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.governance.contract.ScenarioMetricRefRecord> = com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.governance.contract.ScenarioMetricRefRecord>(
    items = value.getItemsList().map { ScenarioMetricRefRecordFromProto(it) },
    total = value.getTotal(),
    nextOffset = if (value.hasNextOffset()) value.getNextOffset() else null
)

internal fun GovernanceReadRequestsPageScenarioAnalysisDimensionRefRecordToProto(value: com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.governance.contract.ScenarioAnalysisDimensionRefRecord>): com.olap.prototype.wire.governance.GovernanceReadRequestsPageScenarioAnalysisDimensionRefRecord = com.olap.prototype.wire.governance.GovernanceReadRequestsPageScenarioAnalysisDimensionRefRecord.newBuilder().apply {
    addAllItems(value.items.map { ScenarioAnalysisDimensionRefRecordToProto(it) })
    setTotal(value.total)
    value.nextOffset?.let { setNextOffset(it) }
}.build()
internal fun GovernanceReadRequestsPageScenarioAnalysisDimensionRefRecordFromProto(value: com.olap.prototype.wire.governance.GovernanceReadRequestsPageScenarioAnalysisDimensionRefRecord): com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.governance.contract.ScenarioAnalysisDimensionRefRecord> = com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.governance.contract.ScenarioAnalysisDimensionRefRecord>(
    items = value.getItemsList().map { ScenarioAnalysisDimensionRefRecordFromProto(it) },
    total = value.getTotal(),
    nextOffset = if (value.hasNextOffset()) value.getNextOffset() else null
)

internal fun GovernanceReadRequestsPageScenarioMemberSourceRefRecordToProto(value: com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.governance.contract.ScenarioMemberSourceRefRecord>): com.olap.prototype.wire.governance.GovernanceReadRequestsPageScenarioMemberSourceRefRecord = com.olap.prototype.wire.governance.GovernanceReadRequestsPageScenarioMemberSourceRefRecord.newBuilder().apply {
    addAllItems(value.items.map { ScenarioMemberSourceRefRecordToProto(it) })
    setTotal(value.total)
    value.nextOffset?.let { setNextOffset(it) }
}.build()
internal fun GovernanceReadRequestsPageScenarioMemberSourceRefRecordFromProto(value: com.olap.prototype.wire.governance.GovernanceReadRequestsPageScenarioMemberSourceRefRecord): com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.governance.contract.ScenarioMemberSourceRefRecord> = com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.governance.contract.ScenarioMemberSourceRefRecord>(
    items = value.getItemsList().map { ScenarioMemberSourceRefRecordFromProto(it) },
    total = value.getTotal(),
    nextOffset = if (value.hasNextOffset()) value.getNextOffset() else null
)

internal fun GovernanceReadRequestsPageScenarioFilterRefRecordToProto(value: com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.governance.contract.ScenarioFilterRefRecord>): com.olap.prototype.wire.governance.GovernanceReadRequestsPageScenarioFilterRefRecord = com.olap.prototype.wire.governance.GovernanceReadRequestsPageScenarioFilterRefRecord.newBuilder().apply {
    addAllItems(value.items.map { ScenarioFilterRefRecordToProto(it) })
    setTotal(value.total)
    value.nextOffset?.let { setNextOffset(it) }
}.build()
internal fun GovernanceReadRequestsPageScenarioFilterRefRecordFromProto(value: com.olap.prototype.wire.governance.GovernanceReadRequestsPageScenarioFilterRefRecord): com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.governance.contract.ScenarioFilterRefRecord> = com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.governance.contract.ScenarioFilterRefRecord>(
    items = value.getItemsList().map { ScenarioFilterRefRecordFromProto(it) },
    total = value.getTotal(),
    nextOffset = if (value.hasNextOffset()) value.getNextOffset() else null
)

internal fun GovernanceReadRequestsPageScenarioRelationFilterRecordToProto(value: com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.governance.contract.ScenarioRelationFilterRecord>): com.olap.prototype.wire.governance.GovernanceReadRequestsPageScenarioRelationFilterRecord = com.olap.prototype.wire.governance.GovernanceReadRequestsPageScenarioRelationFilterRecord.newBuilder().apply {
    addAllItems(value.items.map { ScenarioRelationFilterRecordToProto(it) })
    setTotal(value.total)
    value.nextOffset?.let { setNextOffset(it) }
}.build()
internal fun GovernanceReadRequestsPageScenarioRelationFilterRecordFromProto(value: com.olap.prototype.wire.governance.GovernanceReadRequestsPageScenarioRelationFilterRecord): com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.governance.contract.ScenarioRelationFilterRecord> = com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.governance.contract.ScenarioRelationFilterRecord>(
    items = value.getItemsList().map { ScenarioRelationFilterRecordFromProto(it) },
    total = value.getTotal(),
    nextOffset = if (value.hasNextOffset()) value.getNextOffset() else null
)

internal fun GovernanceReadRequestsPageScenarioPipelineRuleRecordToProto(value: com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.governance.contract.ScenarioPipelineRuleRecord>): com.olap.prototype.wire.governance.GovernanceReadRequestsPageScenarioPipelineRuleRecord = com.olap.prototype.wire.governance.GovernanceReadRequestsPageScenarioPipelineRuleRecord.newBuilder().apply {
    addAllItems(value.items.map { ScenarioPipelineRuleRecordToProto(it) })
    setTotal(value.total)
    value.nextOffset?.let { setNextOffset(it) }
}.build()
internal fun GovernanceReadRequestsPageScenarioPipelineRuleRecordFromProto(value: com.olap.prototype.wire.governance.GovernanceReadRequestsPageScenarioPipelineRuleRecord): com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.governance.contract.ScenarioPipelineRuleRecord> = com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.governance.contract.ScenarioPipelineRuleRecord>(
    items = value.getItemsList().map { ScenarioPipelineRuleRecordFromProto(it) },
    total = value.getTotal(),
    nextOffset = if (value.hasNextOffset()) value.getNextOffset() else null
)

internal fun GovernanceReadRequestsPagePhysicalColumnRecordToProto(value: com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.governance.contract.PhysicalColumnRecord>): com.olap.prototype.wire.governance.GovernanceReadRequestsPagePhysicalColumnRecord = com.olap.prototype.wire.governance.GovernanceReadRequestsPagePhysicalColumnRecord.newBuilder().apply {
    addAllItems(value.items.map { PhysicalColumnRecordToProto(it) })
    setTotal(value.total)
    value.nextOffset?.let { setNextOffset(it) }
}.build()
internal fun GovernanceReadRequestsPagePhysicalColumnRecordFromProto(value: com.olap.prototype.wire.governance.GovernanceReadRequestsPagePhysicalColumnRecord): com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.governance.contract.PhysicalColumnRecord> = com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.governance.contract.PhysicalColumnRecord>(
    items = value.getItemsList().map { PhysicalColumnRecordFromProto(it) },
    total = value.getTotal(),
    nextOffset = if (value.hasNextOffset()) value.getNextOffset() else null
)

internal fun GovernanceReadRequestsVersionInputToProto(value: com.olap.prototype.governance.contract.GovernanceReadRequests.VersionInput): com.olap.prototype.wire.governance.GovernanceReadRequestsVersionInput = com.olap.prototype.wire.governance.GovernanceReadRequestsVersionInput.newBuilder().apply {
    value.id?.let { setId(it) }
    setVersion(value.version)
}.build()
internal fun GovernanceReadRequestsVersionInputFromProto(value: com.olap.prototype.wire.governance.GovernanceReadRequestsVersionInput): com.olap.prototype.governance.contract.GovernanceReadRequests.VersionInput = com.olap.prototype.governance.contract.GovernanceReadRequests.VersionInput(
    id = if (value.hasId()) value.getId() else null,
    version = value.getVersion()
)

internal fun GovernanceReadRequestsPageAnalysisDimensionEventMappingRecordToProto(value: com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.governance.contract.AnalysisDimensionEventMappingRecord>): com.olap.prototype.wire.governance.GovernanceReadRequestsPageAnalysisDimensionEventMappingRecord = com.olap.prototype.wire.governance.GovernanceReadRequestsPageAnalysisDimensionEventMappingRecord.newBuilder().apply {
    addAllItems(value.items.map { AnalysisDimensionEventMappingRecordToProto(it) })
    setTotal(value.total)
    value.nextOffset?.let { setNextOffset(it) }
}.build()
internal fun GovernanceReadRequestsPageAnalysisDimensionEventMappingRecordFromProto(value: com.olap.prototype.wire.governance.GovernanceReadRequestsPageAnalysisDimensionEventMappingRecord): com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.governance.contract.AnalysisDimensionEventMappingRecord> = com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.governance.contract.AnalysisDimensionEventMappingRecord>(
    items = value.getItemsList().map { AnalysisDimensionEventMappingRecordFromProto(it) },
    total = value.getTotal(),
    nextOffset = if (value.hasNextOffset()) value.getNextOffset() else null
)

internal fun GovernanceReadRequestsAssetVersionRequestToProto(value: com.olap.prototype.governance.contract.GovernanceReadRequests.AssetVersionRequest): com.olap.prototype.wire.governance.GovernanceReadRequestsAssetVersionRequest = com.olap.prototype.wire.governance.GovernanceReadRequestsAssetVersionRequest.newBuilder().apply {
    value.assetType?.let { setAssetType(it) }
    value.id?.let { setId(it) }
    setVersion(value.version)
}.build()
internal fun GovernanceReadRequestsAssetVersionRequestFromProto(value: com.olap.prototype.wire.governance.GovernanceReadRequestsAssetVersionRequest): com.olap.prototype.governance.contract.GovernanceReadRequests.AssetVersionRequest = com.olap.prototype.governance.contract.GovernanceReadRequests.AssetVersionRequest(
    assetType = if (value.hasAssetType()) value.getAssetType() else null,
    id = if (value.hasId()) value.getId() else null,
    version = value.getVersion()
)

internal fun GovernanceReadRequestsSourceDiscoveriesInputToProto(value: com.olap.prototype.governance.contract.GovernanceReadRequests.SourceDiscoveriesInput): com.olap.prototype.wire.governance.GovernanceReadRequestsSourceDiscoveriesInput = com.olap.prototype.wire.governance.GovernanceReadRequestsSourceDiscoveriesInput.newBuilder().apply {
    value.dataSourceId?.let { setDataSourceId(it) }
    value.catalog?.let { setCatalog(it) }
    value.schemaName?.let { setSchemaName(it) }
    value.offset?.let { setOffset(it) }
    value.limit?.let { setLimit(it) }
}.build()
internal fun GovernanceReadRequestsSourceDiscoveriesInputFromProto(value: com.olap.prototype.wire.governance.GovernanceReadRequestsSourceDiscoveriesInput): com.olap.prototype.governance.contract.GovernanceReadRequests.SourceDiscoveriesInput = com.olap.prototype.governance.contract.GovernanceReadRequests.SourceDiscoveriesInput(
    dataSourceId = if (value.hasDataSourceId()) value.getDataSourceId() else null,
    catalog = if (value.hasCatalog()) value.getCatalog() else null,
    schemaName = if (value.hasSchemaName()) value.getSchemaName() else null,
    offset = if (value.hasOffset()) value.getOffset() else null,
    limit = if (value.hasLimit()) value.getLimit() else null
)

internal fun GovernanceReadRequestsPageWarehouseDiscoverySnapshotRecordToProto(value: com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.management.warehouse.WarehouseDiscoverySnapshotRecord>): com.olap.prototype.wire.governance.GovernanceReadRequestsPageWarehouseDiscoverySnapshotRecord = com.olap.prototype.wire.governance.GovernanceReadRequestsPageWarehouseDiscoverySnapshotRecord.newBuilder().apply {
    addAllItems(value.items.map { WarehouseDiscoverySnapshotRecordToProto(it) })
    setTotal(value.total)
    value.nextOffset?.let { setNextOffset(it) }
}.build()
internal fun GovernanceReadRequestsPageWarehouseDiscoverySnapshotRecordFromProto(value: com.olap.prototype.wire.governance.GovernanceReadRequestsPageWarehouseDiscoverySnapshotRecord): com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.management.warehouse.WarehouseDiscoverySnapshotRecord> = com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.management.warehouse.WarehouseDiscoverySnapshotRecord>(
    items = value.getItemsList().map { WarehouseDiscoverySnapshotRecordFromProto(it) },
    total = value.getTotal(),
    nextOffset = if (value.hasNextOffset()) value.getNextOffset() else null
)

internal fun GovernanceReadRequestsSnapshotInputToProto(value: com.olap.prototype.governance.contract.GovernanceReadRequests.SnapshotInput): com.olap.prototype.wire.governance.GovernanceReadRequestsSnapshotInput = com.olap.prototype.wire.governance.GovernanceReadRequestsSnapshotInput.newBuilder().apply {
    value.snapshotId?.let { setSnapshotId(it) }
}.build()
internal fun GovernanceReadRequestsSnapshotInputFromProto(value: com.olap.prototype.wire.governance.GovernanceReadRequestsSnapshotInput): com.olap.prototype.governance.contract.GovernanceReadRequests.SnapshotInput = com.olap.prototype.governance.contract.GovernanceReadRequests.SnapshotInput(
    snapshotId = if (value.hasSnapshotId()) value.getSnapshotId() else null
)

internal fun GovernanceReadRequestsSnapshotPageInputToProto(value: com.olap.prototype.governance.contract.GovernanceReadRequests.SnapshotPageInput): com.olap.prototype.wire.governance.GovernanceReadRequestsSnapshotPageInput = com.olap.prototype.wire.governance.GovernanceReadRequestsSnapshotPageInput.newBuilder().apply {
    value.snapshotId?.let { setSnapshotId(it) }
    value.offset?.let { setOffset(it) }
    value.limit?.let { setLimit(it) }
}.build()
internal fun GovernanceReadRequestsSnapshotPageInputFromProto(value: com.olap.prototype.wire.governance.GovernanceReadRequestsSnapshotPageInput): com.olap.prototype.governance.contract.GovernanceReadRequests.SnapshotPageInput = com.olap.prototype.governance.contract.GovernanceReadRequests.SnapshotPageInput(
    snapshotId = if (value.hasSnapshotId()) value.getSnapshotId() else null,
    offset = if (value.hasOffset()) value.getOffset() else null,
    limit = if (value.hasLimit()) value.getLimit() else null
)

internal fun GovernanceReadRequestsPageSourceDiscoveryTableToProto(value: com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.governance.contract.SourceDiscoveryTable>): com.olap.prototype.wire.governance.GovernanceReadRequestsPageSourceDiscoveryTable = com.olap.prototype.wire.governance.GovernanceReadRequestsPageSourceDiscoveryTable.newBuilder().apply {
    addAllItems(value.items.map { SourceDiscoveryTableToProto(it) })
    setTotal(value.total)
    value.nextOffset?.let { setNextOffset(it) }
}.build()
internal fun GovernanceReadRequestsPageSourceDiscoveryTableFromProto(value: com.olap.prototype.wire.governance.GovernanceReadRequestsPageSourceDiscoveryTable): com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.governance.contract.SourceDiscoveryTable> = com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.governance.contract.SourceDiscoveryTable>(
    items = value.getItemsList().map { SourceDiscoveryTableFromProto(it) },
    total = value.getTotal(),
    nextOffset = if (value.hasNextOffset()) value.getNextOffset() else null
)

internal fun GovernanceReadRequestsDiscoveredTablePageInputToProto(value: com.olap.prototype.governance.contract.GovernanceReadRequests.DiscoveredTablePageInput): com.olap.prototype.wire.governance.GovernanceReadRequestsDiscoveredTablePageInput = com.olap.prototype.wire.governance.GovernanceReadRequestsDiscoveredTablePageInput.newBuilder().apply {
    value.discoveredTableId?.let { setDiscoveredTableId(it) }
    value.offset?.let { setOffset(it) }
    value.limit?.let { setLimit(it) }
}.build()
internal fun GovernanceReadRequestsDiscoveredTablePageInputFromProto(value: com.olap.prototype.wire.governance.GovernanceReadRequestsDiscoveredTablePageInput): com.olap.prototype.governance.contract.GovernanceReadRequests.DiscoveredTablePageInput = com.olap.prototype.governance.contract.GovernanceReadRequests.DiscoveredTablePageInput(
    discoveredTableId = if (value.hasDiscoveredTableId()) value.getDiscoveredTableId() else null,
    offset = if (value.hasOffset()) value.getOffset() else null,
    limit = if (value.hasLimit()) value.getLimit() else null
)

internal fun GovernanceReadRequestsPageWarehouseDiscoveredColumnRecordToProto(value: com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.management.warehouse.WarehouseDiscoveredColumnRecord>): com.olap.prototype.wire.governance.GovernanceReadRequestsPageWarehouseDiscoveredColumnRecord = com.olap.prototype.wire.governance.GovernanceReadRequestsPageWarehouseDiscoveredColumnRecord.newBuilder().apply {
    addAllItems(value.items.map { WarehouseDiscoveredColumnRecordToProto(it) })
    setTotal(value.total)
    value.nextOffset?.let { setNextOffset(it) }
}.build()
internal fun GovernanceReadRequestsPageWarehouseDiscoveredColumnRecordFromProto(value: com.olap.prototype.wire.governance.GovernanceReadRequestsPageWarehouseDiscoveredColumnRecord): com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.management.warehouse.WarehouseDiscoveredColumnRecord> = com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.management.warehouse.WarehouseDiscoveredColumnRecord>(
    items = value.getItemsList().map { WarehouseDiscoveredColumnRecordFromProto(it) },
    total = value.getTotal(),
    nextOffset = if (value.hasNextOffset()) value.getNextOffset() else null
)

internal fun GovernanceReadRequestsPageWarehouseDiscoveredUniqueKeyRecordToProto(value: com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.management.warehouse.WarehouseDiscoveredUniqueKeyRecord>): com.olap.prototype.wire.governance.GovernanceReadRequestsPageWarehouseDiscoveredUniqueKeyRecord = com.olap.prototype.wire.governance.GovernanceReadRequestsPageWarehouseDiscoveredUniqueKeyRecord.newBuilder().apply {
    addAllItems(value.items.map { WarehouseDiscoveredUniqueKeyRecordToProto(it) })
    setTotal(value.total)
    value.nextOffset?.let { setNextOffset(it) }
}.build()
internal fun GovernanceReadRequestsPageWarehouseDiscoveredUniqueKeyRecordFromProto(value: com.olap.prototype.wire.governance.GovernanceReadRequestsPageWarehouseDiscoveredUniqueKeyRecord): com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.management.warehouse.WarehouseDiscoveredUniqueKeyRecord> = com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.management.warehouse.WarehouseDiscoveredUniqueKeyRecord>(
    items = value.getItemsList().map { WarehouseDiscoveredUniqueKeyRecordFromProto(it) },
    total = value.getTotal(),
    nextOffset = if (value.hasNextOffset()) value.getNextOffset() else null
)

internal fun GovernanceReadRequestsSourcePageInputToProto(value: com.olap.prototype.governance.contract.GovernanceReadRequests.SourcePageInput): com.olap.prototype.wire.governance.GovernanceReadRequestsSourcePageInput = com.olap.prototype.wire.governance.GovernanceReadRequestsSourcePageInput.newBuilder().apply {
    value.dataSourceId?.let { setDataSourceId(it) }
    value.offset?.let { setOffset(it) }
    value.limit?.let { setLimit(it) }
}.build()
internal fun GovernanceReadRequestsSourcePageInputFromProto(value: com.olap.prototype.wire.governance.GovernanceReadRequestsSourcePageInput): com.olap.prototype.governance.contract.GovernanceReadRequests.SourcePageInput = com.olap.prototype.governance.contract.GovernanceReadRequests.SourcePageInput(
    dataSourceId = if (value.hasDataSourceId()) value.getDataSourceId() else null,
    offset = if (value.hasOffset()) value.getOffset() else null,
    limit = if (value.hasLimit()) value.getLimit() else null
)

internal fun GovernanceReadRequestsPageSourceCollectionRecordToProto(value: com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.warehouse.contract.SourceCollectionRecord>): com.olap.prototype.wire.governance.GovernanceReadRequestsPageSourceCollectionRecord = com.olap.prototype.wire.governance.GovernanceReadRequestsPageSourceCollectionRecord.newBuilder().apply {
    addAllItems(value.items.map { SourceCollectionRecordToProto(it) })
    setTotal(value.total)
    value.nextOffset?.let { setNextOffset(it) }
}.build()
internal fun GovernanceReadRequestsPageSourceCollectionRecordFromProto(value: com.olap.prototype.wire.governance.GovernanceReadRequestsPageSourceCollectionRecord): com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.warehouse.contract.SourceCollectionRecord> = com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.warehouse.contract.SourceCollectionRecord>(
    items = value.getItemsList().map { SourceCollectionRecordFromProto(it) },
    total = value.getTotal(),
    nextOffset = if (value.hasNextOffset()) value.getNextOffset() else null
)

internal fun GovernanceReadRequestsIdentityInputToProto(value: com.olap.prototype.governance.contract.GovernanceReadRequests.IdentityInput): com.olap.prototype.wire.governance.GovernanceReadRequestsIdentityInput = com.olap.prototype.wire.governance.GovernanceReadRequestsIdentityInput.newBuilder().apply {
    value.id?.let { setId(it) }
}.build()
internal fun GovernanceReadRequestsIdentityInputFromProto(value: com.olap.prototype.wire.governance.GovernanceReadRequestsIdentityInput): com.olap.prototype.governance.contract.GovernanceReadRequests.IdentityInput = com.olap.prototype.governance.contract.GovernanceReadRequests.IdentityInput(
    id = if (value.hasId()) value.getId() else null
)

internal fun GovernanceReadRequestsDefinitionDependenciesRequestToProto(value: com.olap.prototype.governance.contract.GovernanceReadRequests.DefinitionDependenciesRequest): com.olap.prototype.wire.governance.GovernanceReadRequestsDefinitionDependenciesRequest = com.olap.prototype.wire.governance.GovernanceReadRequestsDefinitionDependenciesRequest.newBuilder().apply {
    setAssetType(value.assetType)
    setId(value.id)
    setVersion(value.version)
    setOffset(value.offset)
    setLimit(value.limit)
}.build()
internal fun GovernanceReadRequestsDefinitionDependenciesRequestFromProto(value: com.olap.prototype.wire.governance.GovernanceReadRequestsDefinitionDependenciesRequest): com.olap.prototype.governance.contract.GovernanceReadRequests.DefinitionDependenciesRequest = com.olap.prototype.governance.contract.GovernanceReadRequests.DefinitionDependenciesRequest(
    assetType = value.getAssetType(),
    id = value.getId(),
    version = value.getVersion(),
    offset = value.getOffset(),
    limit = value.getLimit()
)

internal fun GovernanceReadRequestsPageReleasedScenarioEntrypointToProto(value: com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.release.catalog.capability.ReleasedScenarioEntrypoint>): com.olap.prototype.wire.governance.GovernanceReadRequestsPageReleasedScenarioEntrypoint = com.olap.prototype.wire.governance.GovernanceReadRequestsPageReleasedScenarioEntrypoint.newBuilder().apply {
    addAllItems(value.items.map { ReleasedScenarioEntrypointToProto(it) })
    setTotal(value.total)
    value.nextOffset?.let { setNextOffset(it) }
}.build()
internal fun GovernanceReadRequestsPageReleasedScenarioEntrypointFromProto(value: com.olap.prototype.wire.governance.GovernanceReadRequestsPageReleasedScenarioEntrypoint): com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.release.catalog.capability.ReleasedScenarioEntrypoint> = com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.release.catalog.capability.ReleasedScenarioEntrypoint>(
    items = value.getItemsList().map { ReleasedScenarioEntrypointFromProto(it) },
    total = value.getTotal(),
    nextOffset = if (value.hasNextOffset()) value.getNextOffset() else null
)

internal fun GovernanceReadRequestsReleaseIdentityRequestToProto(value: com.olap.prototype.governance.contract.GovernanceReadRequests.ReleaseIdentityRequest): com.olap.prototype.wire.governance.GovernanceReadRequestsReleaseIdentityRequest = com.olap.prototype.wire.governance.GovernanceReadRequestsReleaseIdentityRequest.newBuilder().apply {
    setReleaseId(value.releaseId)
    setReleaseVersion(value.releaseVersion)
}.build()
internal fun GovernanceReadRequestsReleaseIdentityRequestFromProto(value: com.olap.prototype.wire.governance.GovernanceReadRequestsReleaseIdentityRequest): com.olap.prototype.governance.contract.GovernanceReadRequests.ReleaseIdentityRequest = com.olap.prototype.governance.contract.GovernanceReadRequests.ReleaseIdentityRequest(
    releaseId = value.getReleaseId(),
    releaseVersion = value.getReleaseVersion()
)

internal fun GovernanceReadRequestsPageAnalysisDimensionRecordToProto(value: com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.governance.contract.AnalysisDimensionRecord>): com.olap.prototype.wire.governance.GovernanceReadRequestsPageAnalysisDimensionRecord = com.olap.prototype.wire.governance.GovernanceReadRequestsPageAnalysisDimensionRecord.newBuilder().apply {
    addAllItems(value.items.map { AnalysisDimensionRecordToProto(it) })
    setTotal(value.total)
    value.nextOffset?.let { setNextOffset(it) }
}.build()
internal fun GovernanceReadRequestsPageAnalysisDimensionRecordFromProto(value: com.olap.prototype.wire.governance.GovernanceReadRequestsPageAnalysisDimensionRecord): com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.governance.contract.AnalysisDimensionRecord> = com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.governance.contract.AnalysisDimensionRecord>(
    items = value.getItemsList().map { AnalysisDimensionRecordFromProto(it) },
    total = value.getTotal(),
    nextOffset = if (value.hasNextOffset()) value.getNextOffset() else null
)

internal fun GovernanceReadRequestsPageAnalysisDimensionLevelRecordToProto(value: com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.governance.contract.AnalysisDimensionLevelRecord>): com.olap.prototype.wire.governance.GovernanceReadRequestsPageAnalysisDimensionLevelRecord = com.olap.prototype.wire.governance.GovernanceReadRequestsPageAnalysisDimensionLevelRecord.newBuilder().apply {
    addAllItems(value.items.map { AnalysisDimensionLevelRecordToProto(it) })
    setTotal(value.total)
    value.nextOffset?.let { setNextOffset(it) }
}.build()
internal fun GovernanceReadRequestsPageAnalysisDimensionLevelRecordFromProto(value: com.olap.prototype.wire.governance.GovernanceReadRequestsPageAnalysisDimensionLevelRecord): com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.governance.contract.AnalysisDimensionLevelRecord> = com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.governance.contract.AnalysisDimensionLevelRecord>(
    items = value.getItemsList().map { AnalysisDimensionLevelRecordFromProto(it) },
    total = value.getTotal(),
    nextOffset = if (value.hasNextOffset()) value.getNextOffset() else null
)

internal fun GovernanceReadRequestsPageAnalysisDimensionValueProviderRecordToProto(value: com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.governance.contract.AnalysisDimensionValueProviderRecord>): com.olap.prototype.wire.governance.GovernanceReadRequestsPageAnalysisDimensionValueProviderRecord = com.olap.prototype.wire.governance.GovernanceReadRequestsPageAnalysisDimensionValueProviderRecord.newBuilder().apply {
    addAllItems(value.items.map { AnalysisDimensionValueProviderRecordToProto(it) })
    setTotal(value.total)
    value.nextOffset?.let { setNextOffset(it) }
}.build()
internal fun GovernanceReadRequestsPageAnalysisDimensionValueProviderRecordFromProto(value: com.olap.prototype.wire.governance.GovernanceReadRequestsPageAnalysisDimensionValueProviderRecord): com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.governance.contract.AnalysisDimensionValueProviderRecord> = com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.governance.contract.AnalysisDimensionValueProviderRecord>(
    items = value.getItemsList().map { AnalysisDimensionValueProviderRecordFromProto(it) },
    total = value.getTotal(),
    nextOffset = if (value.hasNextOffset()) value.getNextOffset() else null
)

internal fun GovernanceReadRequestsPageAnalysisDimensionValueAlignmentRecordToProto(value: com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.governance.contract.AnalysisDimensionValueAlignmentRecord>): com.olap.prototype.wire.governance.GovernanceReadRequestsPageAnalysisDimensionValueAlignmentRecord = com.olap.prototype.wire.governance.GovernanceReadRequestsPageAnalysisDimensionValueAlignmentRecord.newBuilder().apply {
    addAllItems(value.items.map { AnalysisDimensionValueAlignmentRecordToProto(it) })
    setTotal(value.total)
    value.nextOffset?.let { setNextOffset(it) }
}.build()
internal fun GovernanceReadRequestsPageAnalysisDimensionValueAlignmentRecordFromProto(value: com.olap.prototype.wire.governance.GovernanceReadRequestsPageAnalysisDimensionValueAlignmentRecord): com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.governance.contract.AnalysisDimensionValueAlignmentRecord> = com.olap.prototype.governance.contract.GovernanceReadRequests.Page<com.olap.prototype.governance.contract.AnalysisDimensionValueAlignmentRecord>(
    items = value.getItemsList().map { AnalysisDimensionValueAlignmentRecordFromProto(it) },
    total = value.getTotal(),
    nextOffset = if (value.hasNextOffset()) value.getNextOffset() else null
)
