package com.olap.prototype.codec


/** 自然 JSON 参数或外部文档的公开拒绝，不携带原始字段值。 */
class JsonDocumentException(val fieldPath: List<Any>, val expected: String, val actual: String, cause: Throwable? = null) :
    IllegalArgumentException("JSON 文档与声明形态不匹配：${fieldPath.joinToString(".")}", cause) {
    val code = "INVALID_JSON_DOCUMENT"
    val stage = "DECODE_DOCUMENT"
}
