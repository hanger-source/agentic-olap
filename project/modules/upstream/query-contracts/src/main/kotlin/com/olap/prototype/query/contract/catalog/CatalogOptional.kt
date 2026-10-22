package com.olap.prototype.query.contract.catalog


/** 字段出现事实：缺失、显式 NULL 与显式空集合分别保留。 */
data class CatalogOptional<T>(val present: Boolean, val value: T?) {
    init {
        require(present || value == null) { "absent catalog field cannot have a value" }
    }

    companion object {
        @JvmStatic fun <T> absent(): CatalogOptional<T> = CatalogOptional(false, null)
        @JvmStatic fun <T> of(value: T?): CatalogOptional<T> = CatalogOptional(true, value)
    }

    class Missing {
        override fun equals(other: Any?): Boolean = other is CatalogOptional<*> && !other.present
        override fun hashCode(): Int = 0
    }

}
