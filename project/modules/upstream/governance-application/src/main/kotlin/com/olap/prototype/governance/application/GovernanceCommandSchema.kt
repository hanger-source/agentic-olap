package com.olap.prototype.governance.application

import com.olap.prototype.dsl.ApiDiscovery
import java.lang.reflect.Type

/** 描述实际 Kotlin API 构造类型，不再维护 JSON 建模字段反射。 */
object GovernanceCommandSchema {
    fun describeInput(type: Class<*>): Any = ApiDiscovery.type(type)
    fun describe(type: Type): Any = ApiDiscovery.type(type)
}
