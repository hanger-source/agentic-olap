package com.olap.prototype.engine.catalog

data class ExecutableCatalogManifest(val catalogId: String, val schemaVersion: String) {
    companion object {
        const val SCHEMA_VERSION = "olap-executable-catalog/v23"
        fun requireSupportedSchema(version: String) { require(version == SCHEMA_VERSION) { "unsupported executable catalog schema: $version" } }
    }
}
