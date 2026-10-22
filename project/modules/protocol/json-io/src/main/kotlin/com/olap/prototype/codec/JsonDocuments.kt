package com.olap.prototype.codec

import kotlinx.serialization.json.JsonObject

/** 外部证明文档的形态检查；文档不被解释为语义模型。 */
object JsonDocuments {
    fun requireNonEmptyObject(document: String, limits: JsonStructure.Limits = JsonStructure.Limits()) {
        val objectValue = JsonStructure.parse(document.toByteArray(Charsets.UTF_8), limits) as? JsonObject
        require(!objectValue.isNullOrEmpty()) { "证明文档必须是非空 JSON 对象" }
    }
}
