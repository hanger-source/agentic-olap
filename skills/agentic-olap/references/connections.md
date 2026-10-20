# 连接真实业务源

本说明用于已安装产品后的连接配置。服务初始化的配置位置由 `agentic-olap info` 返回；在服务停止时编辑该文件，重启后加载。业务定义与查询通过 MCP 管理，连接器与连接配置由使用者在业务环境中提供。

## 取得插件组

一个插件组包含 `connector.json`、连接器实现 JAR 和实现所需的依赖。JDBC 实现附带实际驱动，HTTP 实现拥有自己的访问协议和结果映射。整个目录一起保存；manifest 中的文件名与字节摘要不能手写或修改。单独数据库驱动不能替代完整连接器。

同版本 [GitHub Release](https://github.com/hanger-source/agentic-olap-skill/releases/tag/v0.4.0) 提供独立 H2、DuckDB 插件组和连接器 SDK。HTTP 或其他后端由使用方提供匹配其 SQL 后端及实际协议的插件；SQL 协议兼容不证明类型、数值、时间或一致性映射已经成立。SDK 中的 `API.md`、`SOURCE-ACCESS.md`、`LOADER.md` 面向插件实现者；业务 Agent 通过正式 MCP 使用已配置的来源。

插件应声明 API `agentic-olap-connector/v3-source-access-calcite-1.42`。`connectorRef` 是 manifest 中实际的 `{id,version,sha256}`，数据源逐项引用该完整对象。服务核验 API 和摘要，驱动只由 JDBC 实现核验。

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
    "parameters": {
      "jdbcUrl": "jdbc:duckdb:/absolute/warehouse/business.duckdb",
      "driverClassName": "org.duckdb.DuckDBDriver"
    },
    "credentialEnvironmentVariables": {}
  }]
}
```

以上是要修改的两个配置项，不是完整服务器配置。使用实际存在的业务库；不要拿示例路径创建空库后当作已接入业务。H2 对应 `dialectId=h2`、`parameters.driverClassName=org.h2.Driver`。JDBC 用户名使用 `parameters.username`；密码引用使用 `credentialEnvironmentVariables.password`。启动服务前注入引用的环境变量，配置中不保存凭据值。

HTTP 来源同样使用 `parameters` 和具名凭据引用，具体字段由实际插件定义。例如插件可以接受 `parameters.endpoint` 和 `credentialEnvironmentVariables.authorization`；不填写虚假的 JDBC URL 或驱动。`dialectId` 表示实际 SQL 后端，HTTP 本身不是 SQL 方言。

本机初始化主体 `local` 获准管理该服务的全部语义资产及已登记源。远程服务通过显式授权控制主体、工具和来源范围；业务 Agent 不自行改写权限。

## 观察实际来源

启动服务后：

```sh
agentic-olap call list-data-sources --payload '{}'
agentic-olap call read-connector-capabilities --payload '{}'
agentic-olap call inspect-warehouse --payload '{"operation":"discover","payload":{"dataSourceId":"business","limit":20}}'
```

来源列表返回获准来源及 `accessCapabilities`，连接器读取返回实际装载身份。来源支持调查时，按 `describe-operation` 和 `read-inspection-capability` 读取结构与必要样本。表列表截断、样本缺少记录或当前唯一不能被推断为完整总体、历史保证或业务口径。

## 显式提供结构

没有 catalog 权限或由配置、已有平台、数据目录提供结构时，先读取准确命令合同：

```sh
agentic-olap call describe-governance-command --payload '{"operation":"register-source-structure"}'
```

通过 `write-governance` 调用该命令，提供已授权 `dataSourceId`、表与字段的 SQL 类型、约束声明、`authority/revision` 及本次主体的 `requestedBy`。返回的 snapshot、tables、columns 可直接用于现有物理定义、语义建设与发布，不要求先调用 `discover-source`。主键需明确声明不可空，不能从查询返回头或样本猜测。

`structureProvenance.kind=DECLARED` 表示明确提供的结构，外部目录与数据库观察分别标记来源。声明不等于已经核验的保证；查询实际依赖参数绑定、关系检查或约束快照时，插件必须兑现对应要求。缺少能力会以 `SOURCE_CAPABILITY_MISSING` 指出来源、操作和能力，只影响实际依赖它的操作。

`0.4.0` 使用元数据存储格式 `10`，不自动改写旧格式持久根；升级既有服务时保留旧持久根及其匹配发行构件。

有适用语义资产时直接复用；需要新增含义时，Agent 结合业务资料和来源调查，通过治理合同明确创建、组合、采用和发布定义。配置来源不要求研发指标代码。
