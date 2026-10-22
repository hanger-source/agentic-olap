package com.olap.prototype.codec

/** 正式领域消息的显式字段转换。 */
internal fun MemberSourceCreateRequestToProto(value: com.olap.prototype.governance.contract.MemberSourceCreateRequest): com.olap.prototype.wire.governance.MemberSourceCreateRequest = com.olap.prototype.wire.governance.MemberSourceCreateRequest.newBuilder().apply {
    setSubjectAreaId(value.subjectAreaId)
    setSubjectAreaVersion(value.subjectAreaVersion)
    setCode(value.code)
    setDisplayName(value.displayName)
    setDefinition(SemanticProto.toProto(value.definition))
    setBindings(SemanticProto.toProto(value.bindings))
    addAllFixedArguments(value.fixedArguments.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.MemberSourceCreateRequestFixedArgumentsEntry.newBuilder().setKey(entry.key).apply { setValue(SemanticProto.toProto(entry.value)) }.build() })
    setActor(value.actor)
}.build()
internal fun MemberSourceCreateRequestFromProto(value: com.olap.prototype.wire.governance.MemberSourceCreateRequest): com.olap.prototype.governance.contract.MemberSourceCreateRequest = com.olap.prototype.governance.contract.MemberSourceCreateRequest(
    subjectAreaId = value.getSubjectAreaId(),
    subjectAreaVersion = value.getSubjectAreaVersion(),
    code = value.getCode(),
    displayName = value.getDisplayName(),
    definition = required(value.hasDefinition(), "MemberSourceCreateRequest.definition") { SemanticProto.fromProto(value.getDefinition()) as com.olap.prototype.member.MemberSourceDefinition },
    bindings = required(value.hasBindings(), "MemberSourceCreateRequest.bindings") { SemanticProto.fromProto(value.getBindings()) as com.olap.prototype.calculation.CalculationBindings },
    fixedArguments = strictMap(value.getFixedArgumentsList().map { it.getKey() to SemanticProto.fromProto(it.getValue()) as com.olap.prototype.calculation.Calculation.Value }),
    actor = value.getActor()
)

internal fun MemberSourceRecordToProto(value: com.olap.prototype.governance.contract.MemberSourceRecord): com.olap.prototype.wire.governance.MemberSourceRecord = com.olap.prototype.wire.governance.MemberSourceRecord.newBuilder().apply {
    setId(value.id)
    setSubjectAreaId(value.subjectAreaId)
    setSubjectAreaVersion(value.subjectAreaVersion)
    setCode(value.code)
    setDisplayName(value.displayName)
    setOrigin(MemberDefinitionOriginToProto(value.origin))
    setDefinition(SemanticProto.toProto(value.definition))
    setBindings(SemanticProto.toProto(value.bindings))
    addAllFixedArguments(value.fixedArguments.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.MemberSourceRecordFixedArgumentsEntry.newBuilder().setKey(entry.key).apply { setValue(SemanticProto.toProto(entry.value)) }.build() })
}.build()
internal fun MemberSourceRecordFromProto(value: com.olap.prototype.wire.governance.MemberSourceRecord): com.olap.prototype.governance.contract.MemberSourceRecord = com.olap.prototype.governance.contract.MemberSourceRecord(
    id = value.getId(),
    subjectAreaId = value.getSubjectAreaId(),
    subjectAreaVersion = value.getSubjectAreaVersion(),
    code = value.getCode(),
    displayName = value.getDisplayName(),
    origin = required(value.hasOrigin(), "MemberSourceRecord.origin") { MemberDefinitionOriginFromProto(value.getOrigin()) },
    definition = required(value.hasDefinition(), "MemberSourceRecord.definition") { SemanticProto.fromProto(value.getDefinition()) as com.olap.prototype.member.MemberSourceDefinition },
    bindings = required(value.hasBindings(), "MemberSourceRecord.bindings") { SemanticProto.fromProto(value.getBindings()) as com.olap.prototype.calculation.CalculationBindings },
    fixedArguments = strictMap(value.getFixedArgumentsList().map { it.getKey() to SemanticProto.fromProto(it.getValue()) as com.olap.prototype.calculation.Calculation.Value })
)

internal fun MemberDefinitionOriginToProto(value: com.olap.prototype.governance.contract.MemberDefinitionOrigin): com.olap.prototype.wire.governance.MemberDefinitionOrigin = com.olap.prototype.wire.governance.MemberDefinitionOrigin.newBuilder().apply { when (value) {
    is com.olap.prototype.governance.contract.MemberDefinitionOrigin.Local -> setLocal(MemberDefinitionOriginLocalToProto(value))
    is com.olap.prototype.governance.contract.MemberDefinitionOrigin.Shared -> setShared(MemberDefinitionOriginSharedToProto(value))
} }.build()
internal fun MemberDefinitionOriginFromProto(value: com.olap.prototype.wire.governance.MemberDefinitionOrigin): com.olap.prototype.governance.contract.MemberDefinitionOrigin = when (value.kindCase) {
    com.olap.prototype.wire.governance.MemberDefinitionOrigin.KindCase.LOCAL -> MemberDefinitionOriginLocalFromProto(value.getLocal())
    com.olap.prototype.wire.governance.MemberDefinitionOrigin.KindCase.SHARED -> MemberDefinitionOriginSharedFromProto(value.getShared())
    com.olap.prototype.wire.governance.MemberDefinitionOrigin.KindCase.KIND_NOT_SET -> error("MemberDefinitionOrigin variant is required")
}

internal fun MemberDefinitionOriginLocalToProto(value: com.olap.prototype.governance.contract.MemberDefinitionOrigin.Local): com.olap.prototype.wire.governance.MemberDefinitionOriginLocal = com.olap.prototype.wire.governance.MemberDefinitionOriginLocal.getDefaultInstance()
internal fun MemberDefinitionOriginLocalFromProto(value: com.olap.prototype.wire.governance.MemberDefinitionOriginLocal): com.olap.prototype.governance.contract.MemberDefinitionOrigin.Local = com.olap.prototype.governance.contract.MemberDefinitionOrigin.Local()

internal fun MemberDefinitionOriginSharedToProto(value: com.olap.prototype.governance.contract.MemberDefinitionOrigin.Shared): com.olap.prototype.wire.governance.MemberDefinitionOriginShared = com.olap.prototype.wire.governance.MemberDefinitionOriginShared.newBuilder().apply {
    setReference(MemberDefinitionRefToProto(value.reference))
}.build()
internal fun MemberDefinitionOriginSharedFromProto(value: com.olap.prototype.wire.governance.MemberDefinitionOriginShared): com.olap.prototype.governance.contract.MemberDefinitionOrigin.Shared = com.olap.prototype.governance.contract.MemberDefinitionOrigin.Shared(
    reference = required(value.hasReference(), "MemberDefinitionOriginShared.reference") { MemberDefinitionRefFromProto(value.getReference()) }
)

internal fun MemberDefinitionRefToProto(value: com.olap.prototype.governance.contract.MemberDefinitionRef): com.olap.prototype.wire.governance.MemberDefinitionRef = com.olap.prototype.wire.governance.MemberDefinitionRef.newBuilder().apply {
    setId(value.id)
    setVersion(value.version)
}.build()
internal fun MemberDefinitionRefFromProto(value: com.olap.prototype.wire.governance.MemberDefinitionRef): com.olap.prototype.governance.contract.MemberDefinitionRef = com.olap.prototype.governance.contract.MemberDefinitionRef(
    id = value.getId(),
    version = value.getVersion()
)

internal fun MemberDefinitionCreateRequestToProto(value: com.olap.prototype.governance.contract.MemberDefinitionCreateRequest): com.olap.prototype.wire.governance.MemberDefinitionCreateRequest = com.olap.prototype.wire.governance.MemberDefinitionCreateRequest.newBuilder().apply {
    setCode(value.code)
    setDisplayName(value.displayName)
    setDefinition(SemanticProto.toProto(value.definition))
    value.description?.let { setDescription(it) }
    setOwner(value.owner)
    setCreatedBy(value.createdBy)
}.build()
internal fun MemberDefinitionCreateRequestFromProto(value: com.olap.prototype.wire.governance.MemberDefinitionCreateRequest): com.olap.prototype.governance.contract.MemberDefinitionCreateRequest = com.olap.prototype.governance.contract.MemberDefinitionCreateRequest(
    code = value.getCode(),
    displayName = value.getDisplayName(),
    definition = required(value.hasDefinition(), "MemberDefinitionCreateRequest.definition") { SemanticProto.fromProto(value.getDefinition()) as com.olap.prototype.member.MemberSourceDefinition },
    description = if (value.hasDescription()) value.getDescription() else null,
    owner = value.getOwner(),
    createdBy = value.getCreatedBy()
)

internal fun MemberDefinitionRecordToProto(value: com.olap.prototype.governance.contract.MemberDefinitionRecord): com.olap.prototype.wire.governance.MemberDefinitionRecord = com.olap.prototype.wire.governance.MemberDefinitionRecord.newBuilder().apply {
    setId(value.id)
    setCode(value.code)
    setDisplayName(value.displayName)
    setDefinition(SemanticProto.toProto(value.definition))
    value.description?.let { setDescription(it) }
    setOwner(value.owner)
    setStatus(value.status)
    setCurrentVersion(value.currentVersion)
    setCreatedBy(value.createdBy)
    setCreatedAt(value.createdAt.toString())
}.build()
internal fun MemberDefinitionRecordFromProto(value: com.olap.prototype.wire.governance.MemberDefinitionRecord): com.olap.prototype.governance.contract.MemberDefinitionRecord = com.olap.prototype.governance.contract.MemberDefinitionRecord(
    id = value.getId(),
    code = value.getCode(),
    displayName = value.getDisplayName(),
    definition = required(value.hasDefinition(), "MemberDefinitionRecord.definition") { SemanticProto.fromProto(value.getDefinition()) as com.olap.prototype.member.MemberSourceDefinition },
    description = if (value.hasDescription()) value.getDescription() else null,
    owner = value.getOwner(),
    status = value.getStatus(),
    currentVersion = value.getCurrentVersion(),
    createdBy = value.getCreatedBy(),
    createdAt = java.time.Instant.parse(value.getCreatedAt())
)

internal fun MemberDefinitionAdoptRequestToProto(value: com.olap.prototype.governance.contract.MemberDefinitionAdoptRequest): com.olap.prototype.wire.governance.MemberDefinitionAdoptRequest = com.olap.prototype.wire.governance.MemberDefinitionAdoptRequest.newBuilder().apply {
    setSubjectAreaId(value.subjectAreaId)
    setSubjectAreaVersion(value.subjectAreaVersion)
    setCode(value.code)
    setDisplayName(value.displayName)
    setDefinition(MemberDefinitionRefToProto(value.definition))
    setBindings(SemanticProto.toProto(value.bindings))
    addAllFixedArguments(value.fixedArguments.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.MemberDefinitionAdoptRequestFixedArgumentsEntry.newBuilder().setKey(entry.key).apply { setValue(SemanticProto.toProto(entry.value)) }.build() })
    setActor(value.actor)
}.build()
internal fun MemberDefinitionAdoptRequestFromProto(value: com.olap.prototype.wire.governance.MemberDefinitionAdoptRequest): com.olap.prototype.governance.contract.MemberDefinitionAdoptRequest = com.olap.prototype.governance.contract.MemberDefinitionAdoptRequest(
    subjectAreaId = value.getSubjectAreaId(),
    subjectAreaVersion = value.getSubjectAreaVersion(),
    code = value.getCode(),
    displayName = value.getDisplayName(),
    definition = required(value.hasDefinition(), "MemberDefinitionAdoptRequest.definition") { MemberDefinitionRefFromProto(value.getDefinition()) },
    bindings = required(value.hasBindings(), "MemberDefinitionAdoptRequest.bindings") { SemanticProto.fromProto(value.getBindings()) as com.olap.prototype.calculation.CalculationBindings },
    fixedArguments = strictMap(value.getFixedArgumentsList().map { it.getKey() to SemanticProto.fromProto(it.getValue()) as com.olap.prototype.calculation.Calculation.Value }),
    actor = value.getActor()
)

internal fun MemberAdoptionRevisionRequestToProto(value: com.olap.prototype.governance.contract.MemberAdoptionRevisionRequest): com.olap.prototype.wire.governance.MemberAdoptionRevisionRequest = com.olap.prototype.wire.governance.MemberAdoptionRevisionRequest.newBuilder().apply {
    setSource(SemanticProto.toProto(value.source))
    setExpectedDefinition(MemberDefinitionRefToProto(value.expectedDefinition))
    setDefinition(MemberDefinitionRefToProto(value.definition))
    setDisplayName(value.displayName)
    setBindings(SemanticProto.toProto(value.bindings))
    addAllFixedArguments(value.fixedArguments.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.MemberAdoptionRevisionRequestFixedArgumentsEntry.newBuilder().setKey(entry.key).apply { setValue(SemanticProto.toProto(entry.value)) }.build() })
    setActor(value.actor)
}.build()
internal fun MemberAdoptionRevisionRequestFromProto(value: com.olap.prototype.wire.governance.MemberAdoptionRevisionRequest): com.olap.prototype.governance.contract.MemberAdoptionRevisionRequest = com.olap.prototype.governance.contract.MemberAdoptionRevisionRequest(
    source = required(value.hasSource(), "MemberAdoptionRevisionRequest.source") { SemanticProto.fromProto(value.getSource()) as com.olap.prototype.member.MemberRef },
    expectedDefinition = required(value.hasExpectedDefinition(), "MemberAdoptionRevisionRequest.expectedDefinition") { MemberDefinitionRefFromProto(value.getExpectedDefinition()) },
    definition = required(value.hasDefinition(), "MemberAdoptionRevisionRequest.definition") { MemberDefinitionRefFromProto(value.getDefinition()) },
    displayName = value.getDisplayName(),
    bindings = required(value.hasBindings(), "MemberAdoptionRevisionRequest.bindings") { SemanticProto.fromProto(value.getBindings()) as com.olap.prototype.calculation.CalculationBindings },
    fixedArguments = strictMap(value.getFixedArgumentsList().map { it.getKey() to SemanticProto.fromProto(it.getValue()) as com.olap.prototype.calculation.Calculation.Value }),
    actor = value.getActor()
)
