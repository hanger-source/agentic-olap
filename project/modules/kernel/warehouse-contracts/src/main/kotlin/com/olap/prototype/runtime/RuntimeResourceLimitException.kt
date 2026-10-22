package com.olap.prototype.runtime

/** 预算失败不改变业务人口，也不截断为成功结果。 */
class RuntimeResourceLimitException(val code: String) : RuntimeException(code)
