# 连接真实业务源

本说明用于已安装产品后的连接配置。服务初始化的配置位置由 `agentic-olap info` 返回；在服务停止时编辑该文件，重启后加载。业务定义与查询通过 MCP 管理，连接器与连接配置由使用者在业务环境中提供。

## 取得插件组

一个插件组包含 `connector.json`、连接器实现 JAR、对应 JDBC 驱动及必要依赖。整个目录一起保存；manifest 中的文件名与字节摘要不能手写或修改。单独 JDBC 驱动不能替代实现语义映射的连接器。

同版本 [GitHub Release](https://github.com/hanger-source/agentic-olap-skill/releases/tag/v0.3.0) 提供独立 H2、DuckDB 插件组。其他后端使用其发行方提供的完整插件；PostgreSQL 协议兼容不证明 Holo 的类型、数值、时间或一致性映射已经成立。

插件应声明 API `agentic-olap-connector/v2-calcite-1.42`。`connectorRef` 是 manifest 中实际的 `{id,version,sha256}`，数据源逐项引用该完整对象。服务启动时核验插件、驱动、API 和摘要。

## 登记数据源

保留已初始化配置中的其他字段，设置：

```json
{
  "connectors": ["/absolute/plugins/duckdb/connector.json"],
  "dataSources": [{
    "dataSourceId": "business",
    "connectorRef": {
      "id": "从实际manifest复制",
      "version": "从实际manifest复制",
      "sha256": "从实际manifest复制"
    },
    "dialectId": "duckdb",
    "jdbcUrl": "jdbc:duckdb:/absolute/warehouse/business.duckdb",
    "username": null,
    "passwordEnvironmentVariable": null,
    "driverClassName": "org.duckdb.DuckDBDriver"
  }]
}
```

以上是要修改的两个配置项，不是完整服务器配置。使用实际存在的业务库；不要拿示例路径创建空库后当作已接入业务。H2 对应 `dialectId=h2`、`driverClassName=org.h2.Driver`，JDBC 地址由实际数据库提供。网络后端的用户名、JDBC 地址和驱动类由其发行方和数据库管理员提供；有密码时，`passwordEnvironmentVariable` 填环境变量名，启动服务前注入实际值。

本机初始化主体 `local` 获准管理该服务的全部语义资产及已登记源。远程服务通过显式授权控制主体、工具和来源范围；业务 Agent 不自行改写权限。

## 观察实际来源

启动服务后：

```sh
agentic-olap call list-data-sources --payload '{}'
agentic-olap call read-connector-capabilities --payload '{}'
agentic-olap call inspect-warehouse --payload '{"operation":"discover","payload":{"dataSourceId":"business","limit":20}}'
```

来源列表说明本次授权源，连接器读取返回实际装载身份；真实表发现才会进入数据库。继续按 `describe-operation` 和 `read-inspection-capability` 的合同读取表结构与必要样本。表列表截断、样本缺少记录或当前唯一不能被推断为完整总体、历史保证或业务口径。

有适用语义资产时直接复用；需要新增含义时，Agent 结合业务资料和来源调查，通过治理合同明确创建、组合、采用和发布定义。配置来源不要求研发指标代码。
