package com.olap.prototype.warehouse.jdbc

/** Safe connection facts exclude URL, credentials and the underlying driver's message. */
class WarehouseConnectionException(val code: String, val dataSourceId: String, val sqlState: String?, val failureType: String, val safeDetail: String? = null) :
    IllegalStateException("$code: dataSourceId=$dataSourceId${sqlState?.let { ", sqlState=$it" } ?: ""}, failureType=$failureType${safeDetail?.let { ", $it" } ?: ""}")
