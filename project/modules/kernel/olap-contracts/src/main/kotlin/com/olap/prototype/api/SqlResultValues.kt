package com.olap.prototype.api

/** SQL集合与记录的无损值形态；列表保留合法NULL，不依赖驱动或传输方式。 */
object SqlResultValues {
    class ArrayValue(elements: List<Any?>) {
        val elements: List<Any?> = java.util.Collections.unmodifiableList(ArrayList(elements))
        override fun equals(other: Any?): Boolean = other is ArrayValue && elements == other.elements
        override fun hashCode(): Int = elements.hashCode()
    }
    class RowValue(fields: List<Any?>) {
        val fields: List<Any?> = java.util.Collections.unmodifiableList(ArrayList(fields))
        override fun equals(other: Any?): Boolean = other is RowValue && fields == other.fields
        override fun hashCode(): Int = fields.hashCode()
    }
}
