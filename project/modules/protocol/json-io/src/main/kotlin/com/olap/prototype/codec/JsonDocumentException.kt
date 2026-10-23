package com.olap.prototype.codec


/** 自然 JSON 参数或外部文档的形态或真实资源预算拒绝，不携带原始字段值。 */
class JsonDocumentException(fieldPath: List<Any>, val expected: String, val actual: String, cause: Throwable? = null,
    val resource: Resource? = null, val limit: Long? = null, val observed: Long? = null) :
    IllegalArgumentException("JSON 文档与声明形态不匹配：${fieldPath.joinToString(".")}", cause) {
    enum class Resource { JSON_INPUT_BYTES, JSON_INPUT_DEPTH, JSON_INPUT_NODES }
    val fieldPath: List<Any> = java.util.List.copyOf(fieldPath)
    init {
        require(if (resource == null) limit == null && observed == null else limit != null && observed != null && limit > 0 && observed > limit) {
            "JSON resource diagnostic requires its actual resource, positive limit and first excess observation"
        }
    }
    val code: String get() = if (resource == null) "INVALID_JSON_DOCUMENT" else "JSON_INPUT_RESOURCE_LIMIT"
    val stage = "DECODE_DOCUMENT"
    val details: Map<String, Any> = buildMap {
        put("fieldPath", this@JsonDocumentException.fieldPath)
        put("expected", expected); put("actual", actual)
        if (resource != null) {
            put("resource", resource.name); put("limit", requireNotNull(limit)); put("observed", requireNotNull(observed))
        }
    }
    companion object {
        fun resourceLimit(resource: Resource, limit: Long, observed: Long, fieldPath: List<Any>): JsonDocumentException =
            JsonDocumentException(fieldPath, "${resource.name}<=$limit", observed.toString(),
                resource = resource, limit = limit, observed = observed)
    }
}
