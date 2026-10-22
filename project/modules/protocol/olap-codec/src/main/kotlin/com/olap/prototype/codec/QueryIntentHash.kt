package com.olap.prototype.query.contract

import com.olap.prototype.codec.QueryProto

/** 完整查询使用正式 Protobuf 确定性字节取得内容身份。 */
object QueryIntentHash { fun compute(intent: QueryIntent): String = QueryProto.digest(intent) }
