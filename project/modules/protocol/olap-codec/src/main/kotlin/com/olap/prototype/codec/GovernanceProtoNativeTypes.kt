package com.olap.prototype.codec

/** 正式领域消息的显式字段转换。 */
internal fun NativeValueTypeToProto(value: com.olap.prototype.contract.NativeValueType): com.olap.prototype.wire.governance.NativeValueType = com.olap.prototype.wire.governance.NativeValueType.newBuilder().apply {
    setName(value.name)
    value.precision?.let { setPrecision(it) }
    value.scale?.let { setScale(it) }
    setNullable(value.nullable)
    value.elementType?.let { setElementType(NativeValueTypeToProto(it)) }
    value.fields?.let { setFields(com.olap.prototype.wire.governance.NativeValueTypeFieldsValues.newBuilder().addAllValues(it.map { NativeValueTypeToProto(it) }).build()) }
}.build()
internal fun NativeValueTypeFromProto(value: com.olap.prototype.wire.governance.NativeValueType): com.olap.prototype.contract.NativeValueType = com.olap.prototype.contract.NativeValueType(
    name = value.getName(),
    precision = if (value.hasPrecision()) value.getPrecision() else null,
    scale = if (value.hasScale()) value.getScale() else null,
    nullable = value.getNullable(),
    elementType = if (value.hasElementType()) NativeValueTypeFromProto(value.getElementType()) else null,
    fields = if (value.hasFields()) value.getFields().getValuesList().map { NativeValueTypeFromProto(it) } else null
)

internal fun NativeTableDependencyToProto(value: com.olap.prototype.contract.NativeTableDependency): com.olap.prototype.wire.governance.NativeTableDependency = com.olap.prototype.wire.governance.NativeTableDependency.newBuilder()
    .setDataContract(PhysicalDataContractToProto(value.dataContract))
    .setAdoption(PhysicalSourceAdoptionToProto(value.adoption)).build()
internal fun NativeTableDependencyFromProto(value: com.olap.prototype.wire.governance.NativeTableDependency): com.olap.prototype.contract.NativeTableDependency = com.olap.prototype.contract.NativeTableDependency(
    dataContract = required(value.hasDataContract(), "NativeTableDependency.dataContract") { PhysicalDataContractFromProto(value.getDataContract()) },
    adoption = required(value.hasAdoption(), "NativeTableDependency.adoption") { PhysicalSourceAdoptionFromProto(value.getAdoption()) }
)

internal fun JdbcColumnTypeToProto(value: com.olap.prototype.contract.JdbcColumnType): com.olap.prototype.wire.governance.JdbcColumnType = com.olap.prototype.wire.governance.JdbcColumnType.newBuilder().apply {
    setJdbcType(value.jdbcType)
    setTypeName(value.typeName)
    value.precision?.let { setPrecision(it) }
    value.scale?.let { setScale(it) }
    value.nullable?.let { setNullable(it) }
}.build()
internal fun JdbcColumnTypeFromProto(value: com.olap.prototype.wire.governance.JdbcColumnType): com.olap.prototype.contract.JdbcColumnType = com.olap.prototype.contract.JdbcColumnType(
    jdbcType = value.getJdbcType(),
    typeName = value.getTypeName(),
    precision = if (value.hasPrecision()) value.getPrecision() else null,
    scale = if (value.hasScale()) value.getScale() else null,
    nullable = if (value.hasNullable()) value.getNullable() else null
)
