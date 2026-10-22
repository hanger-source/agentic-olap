package com.olap.prototype.connector

/** 后端实现的精确部署身份；版本不隐式选择 latest。 */
data class ConnectorRef(val id: String, val version: String, val sha256: String) {
    init {
        require(id.matches(Regex("[a-z][a-z0-9_-]*"))) { "connector id must be an explicit stable identifier" }
        require(version.isNotBlank() && version == version.trim()) { "connector version must be explicit" }
        require(sha256.matches(Regex("[0-9a-f]{64}"))) { "connector sha256 must identify its exact artifact group" }
    }
}
