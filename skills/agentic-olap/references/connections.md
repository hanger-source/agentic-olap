# 连接真实业务源

本说明用于已安装产品后的连接配置。安装与发行边界、远程认证授权及完整配置模板见[服务使用与部署](server.md)。服务初始化的配置位置由 `agentic-olap info` 返回；在服务停止时编辑该文件，重启后加载。业务定义与查询通过 MCP 管理，连接器与连接配置由使用者在业务环境中提供。

## 取得插件组

一个插件组包含 `connector.json`、连接器实现 JAR 和实现所需的依赖。JDBC 实现附带实际驱动，HTTP 实现拥有自己的访问协议和结果映射。整个目录一起保存；manifest 中的文件名与字节摘要不能手写或修改。单独数据库驱动不能替代完整连接器。

插件组和连接器 SDK 须与实际服务来自同一发行或同轮构建。从 [0.6.0 GitHub Release](https://github.com/hanger-source/agentic-olap-skill/releases/tag/v0.6.0) 取得 `agentic-olap-connectors-0.6.0.tgz`（H2、DuckDB 插件组）和 `agentic-olap-connector-sdk-0.6.0.tgz`（扩展开发 SDK）。解压插件归档后使用其中实际的组目录，不根据连接器 ID 推断目录名。HTTP 或其他后端由使用方提供匹配其 SQL 后端及实际协议的插件；SQL 协议兼容不证明类型、数值、时间或一致性映射已经成立。SDK 中的 `API.md`、`SOURCE-ACCESS.md`、`LOADER.md` 面向插件实现者；业务 Agent 通过正式 MCP 使用已配置的来源。

连接器 API 为 `agentic-olap-connector/v7-kotlin-source-sql-calcite-1.42`。`connectorRef` 是 manifest 中实际的 `{id,version,sha256}`，数据源逐项引用该完整对象。服务核验 API 和摘要，驱动只由 JDBC 实现核验。

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

`dataSourceId` 是稳定业务身份，`sourceIdentityHash` 标识精确访问绑定。同 ID 可以登记多个不同绑定，改变插件、端点或其他身份参数会产生不同摘要；轮换凭据或改变凭据存放位置不改变摘要。调查和显式结构登记须选择实际返回的绑定，不能按列表顺序或同名来源自动切换。

## 观察实际来源

启动服务后：

```sh
agentic-olap call list-data-sources --payload '{}'
agentic-olap call read-connector-capabilities --payload '{}'
```

来源列表每项返回 `dataSourceId/sourceIdentityHash/connectorRef/dialectId/accessCapabilities`。明确选定本次绑定后，把返回的实际摘要放入调查请求：

```sh
agentic-olap call inspect-warehouse --payload '{"operation":"discover","payload":{"dataSourceId":"business","sourceIdentityHash":"替换为所选绑定的实际64位摘要","limit":20}}'
```

`read-connector-capabilities` 的 `connectors` 返回当前主体可访问来源所用的 `{connectorRef,nativeScalarFunctions}`。来源支持调查时，按 `describe-operation` 和 `read-inspection-capability` 读取结构与必要样本；除读取能力合同外，调查请求均须带精确 `dataSourceId/sourceIdentityHash`。表列表截断、样本缺少记录或当前唯一不能被推断为完整总体、历史保证或业务口径。

## 显式提供结构

没有 catalog 权限或由配置、已有平台、数据目录提供结构时，先读取准确命令合同：

```sh
agentic-olap call describe-governance-command --payload '{"operation":"register-source-structure"}'
```

通过 `write-governance` 调用该命令，提供已授权的精确 `dataSourceId/sourceIdentityHash`、表与字段的 SQL 类型、约束声明、`authority/revision` 及本次主体的 `requestedBy`。返回的 snapshot、tables、columns 可直接用于现有物理定义、语义建设与发布，不要求先调用 `discover-source`。主键需明确声明不可空，不能从查询返回头或样本猜测。

字段应明确提供类型、顺序与可空性；`autoIncrement/generated` 未知时可以不提供，服务保留未知。`structureProvenance.kind=DECLARED` 表示明确提供的结构，外部目录与数据库观察分别标记来源。声明不等于已经核验的保证；查询实际依赖关系或约束检查时，插件必须兑现检查与计算的一致性。事务快照是实现方式之一；来源也可通过原子 SQL 或不可变数据版本兑现。插件可明确选择安全字面量 SQL 提交，无需假称参数绑定。缺少能力会以 `SOURCE_CAPABILITY_MISSING` 指出来源、操作和能力，只影响实际依赖它的操作。

同一物理表身份的新版本可以显式采用同一稳定业务来源的另一绑定；已有版本和发布仍保留原绑定。旧发布的绑定必须继续部署，缺失时执行明确失败，不自动选用同 ID 的其他绑定。服务使用存储格式 `13`，不自动改写旧格式持久根，恢复时使用匹配发行构件及精确插件。

## 使用已声明的原生函数

需要后端原生标量能力时，先读取 `read-connector-capabilities` 的实际 `nativeScalarFunctions`，再从表达式合同取得 `NATIVE_CALL` 的准确形状。调用提交精确 `connectorRef`、函数 ID 和有序参数；`SCALAR` 输入完整表达式，`PHYSICAL_TABLE` 输入已采用的 `physicalTableId/physicalTableVersion`，不能传自由表名或 SQL。结果类型、算术保证和 `IMMUTABLE/STATEMENT/VOLATILE` 稳定性以该函数声明为准，不凭函数名或方言推测，也不把原生浮点结果升级为精确数量。

有适用语义资产时直接复用；需要新增含义时，Agent 结合业务资料和来源调查，通过治理合同明确创建、组合、采用和发布定义。配置来源不要求研发指标代码。
