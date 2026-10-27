package com.olap.prototype.connector

import org.apache.calcite.sql.SqlIdentifier
import org.apache.calcite.sql.SqlNode

/** 一个已定型普通聚合的原生实现；不取得业务输入关系的改写权。 */
sealed interface AggregateSql {
    class Direct(val expression: SqlNode) : AggregateSql

    /**
     * aggregate 在原分组内与其他聚合一起求值；其工作载体不成为最终结果类型。
     * reduce 消费核心提供的独立工作关系，不引用外层列或捕获原聚合实参。
     * groups/value 是该关系中简单列名；每个输入组必须返回恰好一行，列序为 groups + value。
     * 返回查询的最终 SELECT 显式列出这些列（可带 WITH/ORDER BY），核心统一命名，不能返回 SELECT *。
     * 组列保持原值、类型和 NULL；空/全 NULL 工作值的结果仍须兑现实际聚合的 NULL 规则。
     * 空工作关系不产生新组。归约不能访问其他来源、重建业务 FROM/JOIN 或改变人口及重复度。
     * 当前只承载 SIMPLE 分组；核心持有全部技术组键，负责 NULL 一致回接和最终列合同。
     * 此 SQL 计划不代替 aggregateBehavior 对累加、合并和失败保证的声明。
     */
    class Reduction(
        val aggregate: SqlNode,
        val reduce: (work: SqlIdentifier, groups: List<SqlIdentifier>, value: SqlIdentifier) -> SqlNode,
    ) : AggregateSql
}
