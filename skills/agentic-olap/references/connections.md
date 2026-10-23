# 连接真实业务源

本说明用于已安装产品后的连接配置。安装与发行边界、远程认证授权及完整配置模板见[服务使用与部署](server.md)。服务初始化的配置位置由 `agentic-olap info` 返回；在服务停止时编辑该文件，重启后加载。业务定义与查询通过 MCP 管理，连接器与连接配置由使用者在业务环境中提供。

## 取得插件组

一个插件组包含 `connector.json`、连接器实现 JAR 和实现所需的依赖。JDBC 实现附带实际驱动，HTTP 实现拥有自己的访问协议和结果映射。整个目录一起保存；manifest 中的文件名与字节摘要不能手写或修改。单独数据库驱动不能替代完整连接器。

H2、DuckDB 插件组在 npm 安装时就绪，所需驱动由安装脚本从 Maven Central 按精确清单下载、校验并缓存。SDK 作为正式 Maven 构件发布，产品命令提供同版坐标与随包文档，不需要查询 Git 标签、下载归档或手工解压：

```sh
agentic-olap connectors
agentic-olap sdk
agentic-olap sdk --repository
```

`connectors` 返回 npm 已安装插件的实际 `manifestPath`、`connectorRef` 和 API。把返回值用于服务配置，不按连接器 ID 猜目录。文件已随 npm 就位，命令只读取路径与身份，不下载、解压或二次安装，不修改配置、不创建数据源，也不连接业务数据库。

`sdk` 返回本地安装版本、API、随包文档、JitPack 仓库地址和 `artifacts` 中的精确构件坐标，`sdk --repository` 只输出仓库地址。独立插件工程配置 `maven("https://jitpack.io")` 并引用该坐标，第三方依赖使用 `mavenCentral()`。SDK 公开下载，无需令牌或本地 SDK 仓库。`API.md`、`SOURCE-ACCESS.md`、`LOADER.md` 面向插件实现者。使用远程服务时核对 SDK、插件与该服务的实际 API；命令报告本地安装版本，不会把运行中的服务自动升级。

HTTP 或其他后端由使用方提供匹配其 SQL 后端及实际协议的插件；SQL 协议兼容不证明类型、数值、时间或一致性映射已经成立。业务 Agent 通过正式 MCP 使用已配置的来源。

本仓库源码的连接器 API 为 `agentic-olap-connector/v12-kotlin-native-collection-placement-calcite-1.42`；安装发行物时以实际服务及 `agentic-olap sdk` 披露的 API 为准。`connectorRef` 是 manifest 中实际的 `{id,version,sha256}`，数据源逐项引用该完整对象。服务核验 API 和摘要，驱动只由 JDBC 实现核验。

独立插件必须使用 `agentic-olap sdk` 返回的同版正式 Maven 坐标重新构建，并更新插件 manifest 和精确来源绑定。集合展开的 `expandCollection(outputType,tableAlias)` 返回封闭的 `CollectionExpansion.Relation` 或 `InputProjection` 计划：前者映射原生 FROM 关系，后者映射完整输入上的单个 SELECT-list SRF；插件分别提供生成与完整元素／序号取值表达式。核心负责输入透传、INNER／OUTER 人口、重复度与缺行补 NULL，不开放完整输入的任意关系改写。投影式 OUTER 另需 `COLLECTION_PROJECTION_OUTER`，兑现核心的数组长度判断、typed singleton 哨兵、存在标记与整元素 NULL；未兑现时编译明确拒绝。具体合同与实现入口见随 npm 安装的 SDK `API.md`。使用真实表行或聚合结果回放原 Query，常量数组成功不能替代相关展开验收。

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
    "identityParameters": {
      "jdbcUrl": "jdbc:duckdb:/absolute/warehouse/business.duckdb",
      "driverClassName": "org.duckdb.DuckDBDriver"
    },
    "runtimeParameters": {},
    "credentialEnvironmentVariables": {}
  }]
}
```

以上是要修改的两个配置项，不是完整服务器配置。使用实际存在的业务库；不要拿示例路径创建空库后当作已接入业务。H2 对应 `dialectId=h2`、`identityParameters.driverClassName=org.h2.Driver`。JDBC 用户名使用 `identityParameters.username`；密码引用使用 `credentialEnvironmentVariables.password`。启动服务前注入引用的环境变量，配置中不保存凭据值。

HTTP 来源同样使用两类参数和具名凭据引用，具体允许项由精确插件的 `configurationParameters` 声明。例如真实入口可放入 `identityParameters.endpoint`，等待预算可放入插件声明的 `runtimeParameters.timeoutMillis`；参数不能任意换类。`credentialEnvironmentVariables.authorization` 引用鉴权值，不填写虚假的 JDBC 字段。`dialectId` 表示实际 SQL 后端，HTTP 本身不是 SQL 方言。

本机初始化主体 `local` 获准管理该服务的全部语义资产及已登记源。远程服务通过显式授权控制主体、工具和来源范围；业务 Agent 不自行改写权限。

`dataSourceId` 是稳定业务身份，`sourceIdentityHash` 标识精确访问绑定。同 ID 可以登记多个不同绑定，改变插件、端点或其他身份参数会产生不同摘要；轮换凭据或改变凭据存放位置不改变摘要。调查和显式结构登记须选择实际返回的绑定，不能按列表顺序或同名来源自动切换。

## 观察实际来源

用 `describe-script-api` 读取 `list-data-sources/read-connector-capabilities/inspect-warehouse` 的正式 Kotlin 方法和类型，然后在脚本中调查所选绑定。例如：

~~~kotlin
val sources = olap.sources.list(EmptyRequest)
val selected = sources.items.single {
    it.binding.dataSourceId == parameters.text("dataSourceId") &&
        it.binding.sourceIdentityHash == parameters.text("sourceIdentityHash")
}
val observation = olap.sources.inspect(
    SourceInspection.Discover(
        binding = selected.binding,
        catalog = null,
        schemaPattern = null,
        tablePattern = null,
        tableTypes = emptyList(),
        limit = 20
    )
)
olap.output(observation)
~~~

来源项给出 `binding/connectorRef/dialectId/accessCapabilities`。调查支持范围先通过 `SourceInspection.Capabilities` 读取；其他调查变体都要求精确绑定。表列表截断、几行样本或当前唯一不证明完整总体、历史保证或业务口径。读取插件声明无需连接数据库：

~~~kotlin
olap.output(olap.sources.connectorCapabilities(ConnectorCapabilitiesRequest(aggregate = null)))
~~~

`connectors` 返回精确 `connectorRef`、原生标量函数、编译能力、参数提交策略和参数角色声明。声明不代替真实后端验证。若编译返回 `NATIVE_COMPILATION_CAPABILITY_MISSING`，按其 capability/operation 检查精确插件；例如数值绑定要求的相关派生投影与普通 EXISTS 条件相关是不同能力。

组织 AVG 等聚合前，用 `ConnectorCapabilitiesRequest.Aggregate(connectorRef, aggregation, inputType, grouped)` 读取该输入的正式映射。`aggregation` 是 `ResultAggregation.Kind`；`inputType` 是 `NativeValueType`，给出实际类型、容量和可空性，不能拿最终输出合同代替。DECIMAL 须明确 precision/scale。全局聚合用 `grouped=false`，普通非空分组键用 true，空分组集合按全局处理。`mapping` 区分插件已经兑现的结果类型与原生舍入，以及明确未实现的映射；此读取不证明整个查询可执行。

AVG 仍须提供非空数量合同。事实度量的 `aggregationRounding`、完成关系中 `ResultAggregation.Average.rounding` 明确接受原生 AVG 自身的舍入，须匹配该输入的插件声明；`valueContract.result.rounding` 是额外的最终输出舍入，两者不能互换。原生浮点 AVG 不能据此声明 Exact；原 SQL 没写 ROUND 也不能推出原生舍入为 NONE。遇到映射未实现，由插件查明真实后端类型和舍入，不能猜 DECIMAL 容量、转 DOUBLE 或在客户端重算。

## 显式提供结构

没有 catalog 权限或由配置、已有平台、数据目录提供结构时，用 `describe-script-api` 读取 `register-source-structure` 和 `SourceStructureRegistrationRequest` 的真实构造器，再通过 `olap.governance.registerSourceStructure` 提供已授权的精确 `dataSourceId/sourceIdentityHash`、表与字段的 SQL 类型、约束声明、`authority/revision` 及 `requestedBy=olap.caller.subject`。回执给出 `snapshotId`、状态及表、列计数；用 `olap.semantics.sourceDiscovery/discoveredTables/discoveredColumns` 按实际引用继续读取持久结构，供物理定义、语义建设与发布使用，不要求先调查数据库。主键需明确声明不可空，不能从查询返回头或样本猜测。

字段应明确提供类型、顺序与可空性；`autoIncrement/generated` 未知时可以不提供，服务保留未知。`structureProvenance.kind=DECLARED` 表示明确提供的结构，外部目录与数据库观察分别标记来源。声明不等于已经核验的保证；查询实际依赖关系或约束检查时，插件必须兑现检查与计算的一致性。事务快照是实现方式之一；来源也可通过原子 SQL 或不可变数据版本兑现。插件可明确选择安全字面量 SQL 提交，无需假称参数绑定。缺少能力会以 `SOURCE_CAPABILITY_MISSING` 指出来源、操作和能力，只影响实际依赖它的操作。

同一物理表身份的新版本可以显式采用同一稳定业务来源的另一绑定；已有版本和发布仍保留原绑定。旧发布的绑定必须继续部署，缺失时执行明确失败，不自动选用同 ID 的其他绑定。运行参数调整不改变绑定，重启后使用实际部署策略。服务使用存储格式 `16`，不自动改写旧格式持久根，恢复时使用匹配发行构件及精确插件；所选资产可通过 `read-deployment-availability` 分别核验读取和执行前提。

## 使用已声明的原生函数

需要后端原生标量能力时，先用 `olap.sources.connectorCapabilities` 读取实际 `nativeScalarFunctions`，再从 Kotlin 类型发现取得 `SemanticExpression.NativeCall` 及其参数变体。调用提交精确 `connectorRef`、函数 ID 和有序参数；Scalar 参数输入完整表达式，PhysicalTable 参数输入已采用的精确物理表身份和版本，不能传自由表名或 SQL。结果类型、算术保证和 `IMMUTABLE/STATEMENT/VOLATILE` 稳定性以该函数声明为准，不凭函数名或方言推测，也不把原生浮点结果升级为精确数量。

有适用语义资产时直接复用；需要新增含义时，Agent 结合业务资料和来源调查，通过治理合同明确创建、组合、采用和发布定义。配置来源不要求研发指标代码。
