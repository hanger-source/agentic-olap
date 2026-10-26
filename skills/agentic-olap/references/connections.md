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

`sdk` 返回本地安装版本、API、随包文档、JitPack 仓库地址和 `artifacts` 中的精确构件坐标，`sdk --repository` 只输出仓库地址。新建或确需重构的独立插件工程配置 `maven("https://jitpack.io")`，采用目标宿主披露的精确坐标；第三方依赖使用 `mavenCentral()`。SDK 公开下载，无需令牌或本地 SDK 仓库。`API.md`、`SOURCE-ACCESS.md`、`LOADER.md` 面向插件实现者。使用远程服务时核对该服务的实际声明；命令报告本地安装版本，不会把运行中的服务自动升级。

HTTP 或其他后端由使用方提供匹配其 SQL 后端及实际协议的插件；SQL 协议兼容不证明类型、数值、时间或一致性映射已经成立。业务 Agent 通过正式 MCP 使用已配置的来源。

本仓库源码的连接器 API 为 `agentic-olap-connector/v14-kotlin-mean-target-calcite-1.42`；安装发行物时以实际服务及 `agentic-olap sdk` 披露的 API 为准。`connectorRef` 是 manifest 中实际的 `{id,version,sha256}`，数据源逐项引用该完整对象。服务核验 API 和摘要，驱动只由 JDBC 实现核验。

产品版本或 SDK Maven 坐标变化本身不要求重新构建既有插件。原插件与目标宿主的连接器 API 相同、所用公共接口的二进制合同及 JVM、Kotlin、Calcite 要求满足时，可原样保留完整插件组。宿主仍逐项核验原 manifest、JAR 摘要、完整 `connectorRef` 和 provider 身份；不因产品升级改写它们。API 字符串相同和装载成功都不能单独证明尚未调用的分支成立，升级须用原精确插件完成实际使用能力的查询验证。原字节和来源身份配置均未改变时，来源绑定也不变，无需仅为产品版本重登记目录或修订资产。

集合展开的 `expandCollection(outputType,tableAlias)` 返回封闭的 `CollectionExpansion.Relation` 或 `InputProjection` 计划：前者映射原生 FROM 关系，后者映射完整输入上的单个 SELECT-list SRF；插件分别提供生成与完整元素／序号取值表达式。核心负责输入透传、INNER／OUTER 人口、重复度与缺行补 NULL，不开放完整输入的任意关系改写。投影式 OUTER 另需 `COLLECTION_PROJECTION_OUTER`，兑现核心的数组长度判断、typed singleton 哨兵、存在标记与整元素 NULL；未兑现时编译明确拒绝。具体合同与实现入口见随 npm 安装的 SDK `API.md`。使用真实表行或聚合结果回放原 Query，常量数组成功不能替代相关展开验收。

集合生成、展开和来源物理传递分别判断。一个 Query 可能只构造完整 ROW 集合，再经 JOIN 继续组合，并未使用集合展开；这类失败不能直接归到 LATERAL 或 SRF 放置。逻辑 ARRAY／ROW 的原生构造、CAST 与聚合由既有方言和聚合映射承担；能力发现不承诺所有来源计划均可执行。结果列合同仍须满足 ARRAY 或 STRUCT／JAVA_OBJECT 容器类别，并交付递归类型化值，不能仅更换运输 JSON 或类型标签宣称接通。若目标来源存在可用替代表示，先明确其完整 NULL、去重、字段与后续计算语义，再判断既有入口能否表达。

按主体分组收集完整 ROW，结果是该主体下这些 tuple 的集合；若问题要求保留每个对象的归属，须将真实对象身份继续留在分组与输出中。已保留的类别或选项 ID 不同，即使显示值相同也不会合并；未进入 ROW 的对象归属不会自动保留。没有关联、整体 NULL 与字段为 NULL 的真实记录分别处理。

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

需要来源原生 AVG 等聚合时，可用 `ConnectorCapabilitiesRequest.Aggregate(connectorRef, aggregation, inputType, grouped)` 读取该输入的正式映射。`aggregation` 是 `ResultAggregation.Kind`；`inputType` 是 `NativeValueType`，给出实际类型、容量和可空性，不能拿最终输出合同代替。DECIMAL 须明确 precision/scale。全局聚合用 `grouped=false`，普通非空分组键用 true，空分组集合按全局处理。`mapping` 区分插件已经兑现的结果类型与 AVG 的 `averageBehavior`，以及明确未实现的映射；此读取不证明整个查询可执行。

`MeanTarget.MATHEMATICAL` 对实际参与值求数学均值，再完成该节点声明的最终量化；`SOURCE_NATIVE` 有意采用本发布所选实现的原生均值，再应用输出合同。目标与 `Exact / Approximate / NativeFloating` 独立，结果表示为 DECIMAL 不会恢复输入已失去的保证。作者声明人口、NULL、单位、结果容量和最终舍入，不填写后端原生舍入。所选执行位置无法兑现目标时明确拒绝，不改变业务目标或自动换算法。 AVG 的 `valueContract` 与 `meanTarget` 必填，快照 MEAN 的 `meanValueContract` 是该实体时间归约自己的量化，外层聚合继续独立。发现的原生行为供理解实现范围，真实 Query 才检查实际形态和目标。

显式 CORE_FINALIZATION 支持完成关系 REGROUP 对来源无损交付的 Exact 整数或固定 Decimal 原袋求 MATHEMATICAL AVG；它按各列独立 NULL 人口、重复权重及一次节点最终量化执行，不要求插件映射该核心 AVG，也不要求作者改成 SUM/COUNT。形成观察的来源 NativeCall、LEFT、CASE 仍由来源兑现。事实、窗口、快照及 SOURCE_NATIVE 的均值不自动移入核心，来源原生 AVG 仍须有实际行为和容量依据；已有精度损失或越界证据不能因最终改 Decimal 或选择 SOURCE_NATIVE 而变 Exact。

精确 Decimal 当前为固定 precision／scale；NUMERIC 标签或缺失 p/s 不代表支持可变尺度输入，未知工作容量不代表无限容量。输入解析、聚合工作和节点最终容量是不同保证：单值可容纳不证明 SUM 可容纳，38,20 比38,18多两位小数、少两位整数，并非全面无损扩大。完整边界见[来源接入的数值职责](https://github.com/hanger-source/agentic-olap/blob/main/docs/source-access.md)。

## 显式提供结构

没有 catalog 权限或由配置、已有平台、数据目录提供结构时，用 `describe-script-api` 读取 `register-source-structure` 和 `SourceStructureRegistrationRequest` 的真实构造器，再通过 `olap.governance.registerSourceStructure` 提供已授权的精确 `dataSourceId/sourceIdentityHash`、表与字段的 SQL 类型、约束声明、`authority/revision` 及 `requestedBy=olap.caller.subject`。回执给出 `snapshotId`、状态及表、列计数；用 `olap.semantics.sourceDiscovery/discoveredTables/discoveredColumns` 按实际引用继续读取持久结构，供物理定义、语义建设与发布使用，不要求先调查数据库。主键需明确声明不可空，不能从查询返回头或样本猜测。

字段应明确提供类型、顺序与可空性；`autoIncrement/generated` 未知时可以不提供，服务保留未知。`structureProvenance.kind=DECLARED` 表示明确提供的结构，外部目录与数据库观察分别标记来源。声明不等于已经核验的保证；查询实际依赖关系或约束检查时，插件必须兑现检查与计算的一致性。事务快照是实现方式之一；来源也可通过原子 SQL 或不可变数据版本兑现。插件可明确选择安全字面量 SQL 提交，无需假称参数绑定。缺少能力会以 `SOURCE_CAPABILITY_MISSING` 指出来源、操作和能力，只影响实际依赖它的操作。

运行参数调整不改变绑定，重启后使用实际部署策略。服务不自动改写旧格式持久根，恢复时使用匹配发行构件及精确插件；所选资产可通过 `read-deployment-availability` 分别核验读取和执行前提。

## 更换插件与显式采用

真正更换插件字节、provider 版本或来源身份参数时，使用实际产物生成的新 manifest 和完整 `connectorRef`；保留原插件组供仍引用它的资产使用。精确插件引用进入 `sourceIdentityHash`，相同连接器 API 不使新旧绑定相等。`list-data-sources` 返回部署后的实际绑定，不能手写旧摘要覆盖新字节。

物理版本 P 的 `PhysicalDataContract` 保存稳定来源、表坐标、精确列版本与类型／NULL、约束、快照唯一规则及 anchor，以及封存集合的完整原声明。P 不钉连接器、方言或某次结构观察。S 是目标绑定下已记录的精确结构快照；兑现采用接受该绑定及 S 的实际证据兑现 P。原结构出处不能冒充 B 的核验，结构登记本身也不产生采用。

初次 `olap.governance.createPhysicalTable` 使用 `initialAdoption.discoveredTableId/sourceCollectionIds`，连接器、方言及结构摘要从实际材料取得。P、列与首份采用同事务提交，返回 `sourceAdoptions`；首个发布直接传其 `references`，没有额外登记步骤。

更换实现、数据合同不变时：

1. 用 `olap.governance.discoverSource` 或 `registerSourceStructure` 为目标精确绑定取得 S。
2. 用 `olap.governance.registerSourceAdoptions(SourceAdoptionRegistrationRequest(physicalTables, S.snapshotId, olap.caller.subject))` 一次明确接受已有精确 P 的兑现。服务按 P 声明列核对类型／NULL及原约束、声明，不要求 B 没有额外列，也不要求完整观察摘要与原出处相同。
3. 将返回 `SourceAdoptions.references` 直接交 `buildRelease`。已有采用也可用 `olap.semantics.sourceAdoptions(SourceAdoptionReadRequest(S.snapshotId))` 读取后直接传入。

产品计算实际依赖闭包并取用精确采用，作者不手抄逐表 ID、不查最近记录，也不自行装配闭包。缺少实际 P 的采用或同 P 选择多个不同采用时明确拒绝；未用余项不成为依赖。可直接传递正式返回对象的短例见[数据合同与来源兑现采用](../../../docs/agent-usage.md#数据合同与来源兑现采用)。

新发布冻结所选采用，旧发布仍保留原绑定和证据。按精确 S 读取历史采用只核对元数据权限，不访问来源，不要求该绑定仍部署或来源仍 ACTIVE；执行旧发布时缺少旧绑定则返回 `SOURCE_BINDING_NOT_DEPLOYED`，不会按同 ID 自动切换。同 P、同绑定因技术观察改变可以登记不同采用；旧发布可能因当前技术环境不满足其冻结要求而明确失败。数据合同、业务含义或 `NativeCall(A)` 的真实精确依赖改变时，仍通过正式修订及影响读取处理；实现替换不自动改写它们，也不强迫修订未变主题／场景授权。采用冻结不固定动态分区、数据版本或 `SourceReadBasis`。

## 使用已声明的原生函数

需要后端原生标量能力时，先用 `olap.sources.connectorCapabilities` 读取实际 `nativeScalarFunctions`，再从 Kotlin 类型发现取得所需作者节点。来源定义使用 `SemanticExpression.NativeCall`：Scalar 参数输入完整来源表达式，PhysicalTable 参数输入已采用的精确物理表身份和版本，不能传自由表名或 SQL。完成关系使用 `ResultExpression.ResultNativeCall(connectorRef,function,arguments)`，实参为有序结果表达式，只允许精确来源插件声明的纯 SCALAR 签名，不新增物理表依赖。两者均核对精确 `connectorRef`、函数 ID、参数及原生返回类型，不凭方言猜函数支持。

关联后的条件解析应将 `ResultNativeCall` 放在该 JOIN 后的 `ResultCaseWhen` 分支内，不能无证明地移到源定义预解析。所有 CASE 分支都做静态校验，发布授权还须允许 `NATIVE_CALL` 及实参树的节点。插件和真实后端负责格式、NULL、解析错误、CASE 求值及 `IMMUTABLE/STATEMENT/VOLATILE` 稳定性；普通 TEXT 非 NULL 不证明格式有效。见[条件原生调用作者示例](../../../examples/agent-client/conditional-native-call.olap.kts)，示例要求部署方已有真实解析声明，不代表内置插件或目标后端已验收。

原生数值结果只有维数一、声明的原生容量与算术保证，不自动成为天、金额或其他业务单位。Measure 实参仅允许 NUMBER→NUMBER，且单位为维数一、原生结果保证可承接输入；非数值返回的原生调用只消费分类标量，不能用 NUMBER→TEXT→CAST 擦除数量合同。原始逐行输入可形成逐行贡献，完成量的观察及重复度来源继续保留。CAST 或 DECIMAL 结果不能把近似、原生浮点改为 Exact。后续贡献人口、日期差、SUM/有效 COUNT、单位和舍入仍由业务模型明确表达。

有适用语义资产时直接复用；需要新增含义时，Agent 结合业务资料和来源调查，通过治理合同明确创建、组合、采用和发布定义。配置来源不要求研发指标代码。
