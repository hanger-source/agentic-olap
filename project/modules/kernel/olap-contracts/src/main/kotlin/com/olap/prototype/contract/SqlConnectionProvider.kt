package com.olap.prototype.contract

import java.sql.Connection

fun interface SqlConnectionProvider { fun getConnection(): Connection }
