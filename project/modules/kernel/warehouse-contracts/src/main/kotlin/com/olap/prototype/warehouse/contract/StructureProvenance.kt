package com.olap.prototype.warehouse.contract

/** 技术结构的提供者与精确版本；声明不冒充数据库观察。 */
class StructureProvenance(val kind: Kind, authority: String, revision: String) {
    enum class Kind { DATABASE_CATALOG, DECLARED, EXTERNAL_CATALOG }
    val authority: String = authority.trim()
    val revision: String = revision.trim()
    init { require(this.authority.isNotEmpty() && this.revision.isNotEmpty()) { "structure authority and revision are required" } }
    override fun equals(other: Any?): Boolean = other is StructureProvenance && kind == other.kind && authority == other.authority && revision == other.revision
    override fun hashCode(): Int = listOf(kind, authority, revision).hashCode()
}
