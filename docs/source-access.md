# 来源访问扩展

完整 MCP 服务通过来源插件访问数据。使用方提供与其网络、鉴权和查询协议相符的插件，服务负责语义管理、发布、规划、SQL 生成、执行要求检查及结果合同校验。HTTP 查询服务是来源访问实现，不需要数据库端口、JDBC URL、数据库凭据或 catalog 权限。业务公式、资格规则和指标组合仍通过语义定义和 Query 表达。

本文描述源码中的来源访问合同：连接器 API 为 `agentic-olap-connector/v13-kotlin-data-contract-source-adoption-calcite-1.42`，元数据存储格式为 `17`。产品运行构件按同一发行装配；独立插件核对目标宿主的连接器合同与实际运行要求，产品版或 SDK 坐标变化本身不要求重构原插件，具体采用规则见[安装与发布](installation-release.md)。旧存储格式明确拒绝，服务不会自动改写已有持久根。

## 配置来源

在服务配置中指定已安装插件的 manifest，以及真实来源绑定。以下 HTTP 参数和凭据槽名称由具体插件定义，核心不规定 HTTP 接口协议：

```json
{
  "connectors": ["/absolute/plugins/http-sql/connector.json"],
  "dataSources": [{
    "dataSourceId": "business",
    "connectorRef": {"id": "http-sql", "version": "1.0.0", "sha256": "完整插件组摘要"},
    "dialectId": "postgresql",
    "identityParameters": {"endpoint": "https://query.example.com/sql", "database": "analytics"},
    "runtimeParameters": {"timeoutMillis": 60000},
    "credentialEnvironmentVariables": {"authorization": "BUSINESS_QUERY_AUTH"}
  }]
}
```

启动服务前注入引用的环境变量。凭据值不进入配置、发布或查询请求；执行工作进程只取得本次授权来源声明的凭据环境。`identityParameters` 与 `runtimeParameters` 只能包含 JSON 值，敏感值通过凭据槽引用。插件通过参数角色声明分别列出允许的身份参数与运行参数；核心拒绝错放、未知或同时属于两类的参数，不按 timeout、endpoint 等名称猜测角色。

`dataSourceId` 是稳定业务来源身份；`SourceBindingRef={dataSourceId,sourceIdentityHash}` 标识一份不可变访问绑定。摘要由来源 ID、精确插件引用、方言、规范化身份参数和凭据槽名共同形成，不包括运行参数、凭据值与保存凭据的环境变量名。运行参数只能改变预算、等待或诊断行为，不能改变数据选择、类型、文本比较或计算口径；超限须明确失败，不能截断为成功。改变插件、端点或其他身份参数产生不同摘要。同一来源 ID 可以部署多个不同绑定；同一绑定不能同时配置两套运行策略。

Agent 从 `olap.sources.list(EmptyRequest)` 取得获授权的 `binding/connectorRef/dialectId/accessCapabilities`，按本次业务用途明确选择绑定，不按列表顺序、唯一性猜测或“最新”选择。调查与结构登记均提交返回的精确 `sourceIdentityHash`。该摘要不是数据库内容快照或结构版本。

JDBC 实现的 `identityParameters` 为 `jdbcUrl`、可选 `username` 和 `driverClassName`，`runtimeParameters` 为 `{}`，凭据槽为可选 `password`。这些字段只由 JDBC 插件解释。HTTP 插件不填写虚假的 JDBC 字段。

## 提供结构

结构获取与 SQL 执行分开。两种结构供应方式都进入相同的物理定义、语义建设和发布流程：

- 使用方显式提供结构：通过 `describe-script-api` 读取 `register-source-structure` 对应方法和 `SourceStructureRegistrationRequest` 的正式构造器，再在 kts 中调用 `olap.governance.registerSourceStructure`。输入包括已授权 `dataSourceId/sourceIdentityHash`、catalog/schema 范围、`authority`、`revision`、表、字段、SQL 类型及约束声明，以及 `requestedBy=olap.caller.subject`。不需要先调用 `discover-source`。
- 插件提供结构：实现 `SourceAccess.discover`，从数据库 catalog、使用方配置或外部目录取得结构。`discover-source` 同样提交精确 `dataSourceId/sourceIdentityHash`，仅对声明支持调查的来源调用该实现。通过 `olap.sources.inspect` 调查时，除读取能力变体外，其余正式请求均持有精确 `SourceBindingRef`。

`olap.governance.registerSourceStructure` 返回 `SourceDiscoveryReceipt`，给出实际保存的 `snapshotId`、状态和表、列计数。用该引用调用 `olap.semantics.sourceDiscovery`、`discoveredTables` 和 `discoveredColumns` 读取已保存结构，再以实际 `discoveredTableId` 构造物理定义，继续建设、采用并发布其他语义。读取已保存结构不触发数据库探测。

精确物理版本 P 拥有 `PhysicalDataContract`，保存稳定来源、表坐标、精确列版本及 SQL 类型／NULL、约束、快照唯一规则及 anchor，以及原精确封存声明。P 不钉连接器、方言或当前观察摘要。`originDiscoveredTableId` 追溯原结构材料，不代表另一绑定已经核验。

`PhysicalSourceAdoption` 明确接受目标绑定及具体快照 S 的证据兑现 P，冻结其精确引用、结构出处、技术观察、真实约束证据及该绑定的集合登记。B 可以有额外列，但必须兑现 P 声明的全部列；不比较完整观察列数或要求原 `schemaHash` 相同。同 P、同 B 的另一 S 可产生不同不可变采用，不选择最近记录。

初次 `createPhysicalTable` 通过 `initialAdoption.discoveredTableId/sourceCollectionIds` 指定材料，P 与首份采用同事务建立，返回 `sourceAdoptions`。更换兑现实现时，用 `registerSourceAdoptions` 明确登记已有 P 对目标 S 的采用，或按精确 S 读取已有采用；Kotlin 将正式返回的 `references` 直接传给 `buildRelease`。发布计算实际 P 闭包、核对精确采用并冻结，缺失或同 P 多选明确拒绝，未用余项不进依赖。短例见[数据合同与来源兑现采用](agent-usage.md#数据合同与来源兑现采用)。

数据合同与业务依赖未变时，更换实现不强迫修订语义图或主题／场景授权。旧发布继续冻结原采用，当前部署缺少旧绑定会返回 `SOURCE_BINDING_NOT_DEPLOYED`，不自动切换。读取已保存采用只要求对应元数据读取授权和精确结构观察存在，不要求旧绑定仍部署或来源仍为 ACTIVE；登记新采用和实际执行继续核对各自准入条件。原生函数的精确连接器依赖也不能被采用改写。绑定与证据冻结不保证后端环境一直满足原要求，也不固定动态数据读基础。

每份结构携带 `structureProvenance={kind,authority,revision}`：`DECLARED` 表示使用方显式提供，`EXTERNAL_CATALOG` 表示外部目录，`DATABASE_CATALOG` 表示实际数据库观察。采用冻结所选 S 的出处，执行者按该证据核验；不会拿 P 的原出处伪称 B 做过调查。显式结构不伪造数据库产品、驱动或只读连接观察。`schemaHash` 标识这次观察的结构内容，来源出处和版本另行明确保存。

内置 JDBC 实现的 `revision` 标识精确访问绑定；本次结构观察由保存的 snapshot ID 和表的 `schemaHash` 标识，不把绑定摘要称为数据库原生结构版本或数据快照。

约束声明不等于约束已核验。规划或执行实际依赖键、关系检查、封存集合等保证时，来源必须兑现对应要求。结构供应者负责及时登记变化并修订、发布受影响定义；显式结构不会被服务偷偷替换成一次数据库调查结果。

冻结列的非空声明进入原生类型系统时，核心记录对应精确来源列的 `NOT_NULL` 采用；声明本身不代替核验。非空类型可以参与过滤简化与表达式推导，因此单表 `RAW_BAG` 原样读取也可能要求 `CONSTRAINT_VERIFICATION` 和 `CONSISTENT_READ`，不以 JOIN、聚合或唯一键归约为前提。实际采用范围来自本次冻结来源列及编译读取的元数据，不能按 EventIdentity 或主键字段猜测。

执行者必须兑现每项采用的非空保证，最终输出没有保留该列、主查询为空或 NULL 已被聚合隐藏都不能免除要求。可以在与主查询一致的数据状态上检查真实数据，也可以在本次执行期间稳定的 schema 下，核验源端真实强制、覆盖所有被读取数据的非空约束；后一路径的条件见下文。原本可空的列由查询内过滤证明非空，不因此增加来源非空保证。仅作为原生函数参数的表不采用行字段的非空保证；同时实际读取其数据行时按真实列用途记录。内置 JDBC 实现采用数据非空见证，不要求读取 catalog 的非空约束；PK／UK 等命名约束仍按插件明确兑现的 catalog／数据一致性核验。

字段必须明确提供类型、顺序与可空性。`autoIncrement/generated` 可以缺省并保存为未知；它们不决定读取查询的保证。主键列必须明确声明为不可空，约束引用必须属于已声明字段。查询返回头可以提供类型信息，但不能替代基础表的键与非空声明；服务不会从样本中推导这些保证。

## 实现插件

`OlapConnector.compilation` 提供实际 SQL 后端的方言、类型、参数和原生运算映射；`access(verifiedIdentity)` 返回 `SourceAccess`。两者共同属于同一个精确插件组。HTTP 是传输方式，不是 SQL 方言，不能仅凭使用 HTTP 就选择 PostgreSQL 或其他映射。

只读属性 `CompilationSupport.parameterSubmission` 明确选择 `BIND_PARAMETERS` 或 `TYPED_LITERALS`。前者提交 SQL 与有序类型参数，要求访问实现支持参数绑定；后者在 Calcite SQL AST 中将参数节点转成类型化字面量，保留精确 CAST，再由实际方言生成完整 SQL，提交参数列表为空。重复参数按节点身份处理，不扫描或替换 SQL 文本中的问号。字符串、精确数值、布尔及时间值由已校验的参数编码产生；需要后端专属字面量映射时实现 `parameterLiteral`。不支持的映射明确拒绝，不在绑定失败后自动切换方式。

因此，只接收完整 SQL 文本的 HTTP 服务可以使用 `TYPED_LITERALS`，不必宣称具有 `PARAMETER_BINDING`。主查询、关系检查和候选值查询经过同一编译出口；业务方无需自行插值。该选择属于精确插件的编译合同，不改变业务公式。

内置 H2 插件也明确选择 `TYPED_LITERALS`：H2 2.2.224 在参数位于共享 CTE、且多个派生表消费该 CTE 的已复现形状中，会返回错误的空结果；同条件的类型化字面量返回正确人口。该映射在编译时确定，不在执行失败后切换。H2 访问协议本身仍支持参数绑定，其他连接器继续按各自声明提交参数。

`SourceAccess` 的职责如下：

| 合同 | 插件责任 |
| --- | --- |
| `validateConfiguration` | 校验自身参数与凭据槽；不把数据库探测作为通用前置 |
| `capabilities` | 按实际配置声明独立能力 |
| `supportsConstraintVerification(dataContract, adoption)` | 分别接收 `PhysicalDataContract` 的要求和 `PhysicalSourceAdoption` 的精确兑现证据，纯声明能否兑现交给优化器的冻结键等约束，不连接来源；返回 false 不会改变真实列的非空类型，也不免除本次 `NOT_NULL` 要求 |
| `execute` | 执行核心给出的 `SourceSqlRequest`，兑现 SQL、参数、关系检查、来源保证和本次资源要求 |
| `discover` / `snapshot` / `describe` | 可选结构供应或调查；没有实现时准确拒绝对应操作 |
| `preview` / `profile` / `profileRelationship` | 可选样本、列和关系调查；分别按能力检查 |
| `verifySealedDataset` | 只有支持封存来源核验时实现 |

执行请求包含 SQL、无损类型参数、结果别名和输出类型，以及实际依赖的来源证据、约束和关系检查。插件不能丢弃附带检查后只执行主 SQL。存在关系检查或采用约束时，`CONSISTENT_READ` 要求检查与本次计算针对一致的数据状态成立；事务快照、同一原子 statement 内检查或精确绑定的不可变数据版本，都可以兑现该要求。一次 HTTP 请求、一个 SQL 批次或“先检查再查询”本身不能证明一致性。插件不认识或无法兑现的要求应明确拒绝，不改写业务语义。

`sourceRequirements` 持有稳定 `dataContract`、明确选定的 `adoption`、本次实际使用的 `columns` 与 `constraintUses`。前者给出 P 的精确列和约束要求，后者给出实际绑定、连接器、方言、表坐标及该 S 的冻结证据；不能从 P 的原出处推导执行来源。`NOT_NULL.name` 直接标识列，其他约束项定位 P 的实际键或规则，插件无需解析 SQL 猜测来源列。`relationChecks` 另行给出 SQL、类型参数与违例码。两者都属于执行合同，参数顺序、比较语义与检查范围必须保留。

API v13 的插件按上述两个正式对象实现纯能力声明及执行核验，不自行登记或选择采用。`adoption.requireContract(dataContract)` 只核冻结证据能否兑现 P，`requireDeployment(source)` 只核当前精确绑定；实际保证仍由本次 `execute` 兑现。`JdbcSourceRequirement.snapshotUniqueKeys` 来自 P，`sourceCollections` 只选取兑现 P 声明的登记，不能把 B 的其他观察自动提升为查询要求。可选 `JdbcSupport.supportsColumnInspection` 和 `supportsConstraintSnapshot` 接收 `PhysicalSourceEvidence`，核验使用所选 S 的出处和数据库／驱动观察，不沿用 P 原出处的技术信息。完整签名与字段责任见[连接器 API](../project/modules/kernel/connector-api/README.md#数据合同与兑现采用)。

源端原生强制约束可以免去同一非空条件的数据扫描，但插件必须核验约束确实作用于本次精确来源和列、覆盖已有数据与本次可读数据，而且在主查询期间不会因 DDL、表替换或连接切换失效。仅约束名称相同、目录返回 `nullable=false`、只约束后续写入、一次历史 catalog 观察或 `DECLARED` 结构都不足以证明这些条件。核验与使用之间的 schema 稳定性应由来源实际机制保证，例如同一受保护事务或 statement 的稳定 schema；不能靠先查目录再独立提交查询推断。源端只兑现非空约束时，也不能据此声明可以为优化器兑现所有冻结 PK／UK 等约束。

插件选择同一原子 statement 进行数据见证或关系检查时，必须有目标来源的真实执行机制保证每项必要检查都会执行并将违例传播为失败，即使主结果为空、优化器裁剪了投影或分支、或者结果读取达到预算。把检查放进可能未被读取的 CTE、投影或交叉连接不构成这种保证；一次 HTTP、一个 SQL 外壳或 statement snapshot 声明也不证明检查实际执行。共同返回检查证据和主结果的运输实现，不能因主结果零行而丢失必要证据。没有通用 SQL 包装模板能替所有来源兑现这项责任；物理来源路径被后端拒绝时应保留真实失败，不能把常量输入的成功当作能力已兑现。插件保持核心主 SQL 的人口、NULL、重复度和求值语义，不用前置 COUNT、跳过空人口或失败后换方案替代核验。

发现采用列存在 NULL 时返回 `SourceGuaranteeException("SOURCE_NOT_NULL_VIOLATION")`；精确来源、类型或约束已变化时返回对应 `SOURCE_BINDING_DRIFT`、`SOURCE_COLUMN_TYPE_DRIFT` 或 `SOURCE_CONSTRAINT_DRIFT`。无法核验或无法维持所需一致性时准确拒绝能力或报告保证核验失败，不能报成已发现数据违例。HTTP 插件可用 `SourceExecutionException` 保留实际阶段、请求接受与执行状态；仅能确认查询已提交时，不把状态填成 `NOT_SUBMITTED`。原生异常与完整诊断保留为私有 cause，不把 SQL 或来源原文放入公开消息。

`CONSTRAINT_VERIFICATION` 与 `CONSISTENT_READ` 是插件对本次保证的责任声明，不是选择某种传输或数据库产品的开关。执行时可真实维持检查与计算的一致性，同时没有可交出的数据版本坐标，此时 `readBasis` 仍应如实为 `Unknown`；它不提供重放保证，也不自动否定已经兑现的单次执行保证。

`execute` 使用 `stageNanos: (String, Long) -> Unit` 回传阶段名称及耗时纳秒；插件直接调用该 Kotlin 函数。取消通过 `WarehouseExecutionControl` 传递，阶段计时不改变资源归属或异常处理责任。

候选值读取同样提交完整 `SourceSqlRequest` 给 `execute`，保留精确来源、参数、输出类型和实际采用的保证。候选值入口另行核对单列标量结果、类型和分页边界，不要求插件实现另一套候选执行通路。连接器的 `CompilationSupport.nativeAggregate` 提供实际来源的原生聚合运算符与结果类型推导；核心继续决定业务公式及数量保证，不能以某一数据库的返回类型代替所有来源的事实。

精确 AVG 的原生结果由连接器 `typeSystem.deriveAvgAggType`、`nativeAggregate` 和 `decimalAverageRounding` 如实声明，核心再处理最终业务结果的 DECIMAL 容量和舍入。作者指定最终精度不能补救原生聚合已丢失的信息。原生类型推导明确拒绝时返回 `NATIVE_AGGREGATE_MAPPING_UNAVAILABLE` 和实际输入类型；插件实现者应先研究真实后端映射，不能用浮点、客户端舍入或未经证明的 SUM/COUNT 改写替代。

这三项声明还须覆盖所接受的原生输入类型、实际参与行数及支持的分组／窗口执行形态。原生 AVG 静默算错时，错误值也可能落在合法结果容量内；结果范围、有限值及 `Overflow.FAIL` 检查不构成对后端算术实现的验证。已知错误或尚无保证的映射应继续拒绝，不把最终 DECIMAL、单独的舍入声明或小值样本当作修复。

如果同一输入类型在不同执行形态上返回不同 scale，须区分只增加尾零的表示差异与原生计算已经不同舍入的情况。宽类型只提供容纳空间，不能补回原生结果已经丢失的信息。插件只有在能兑现统一的实际容量及原生舍入时才能声明映射；已证明的原生输入转换或另一原生策略须通过确定的 SQL AST 映射和相同类型推导表达，保留人口、NULL、权重和错误，不在来源执行失败后切换。

`DIVIDE` 与 `SAFE_DIVIDE` 表达允许小数的算术商，不按输入的整数技术类型自动截断。显式 `SUM(x)/COUNT(x)` 使用 `CompilationSupport.division`，不使用 AVG 的 `decimalAverageRounding`；来源表达式、完成关系及分摊计算使用同一除法入口。两个输入都是整数 SQL 类型时，核心按各自完整整数域无损转换为 `DECIMAL(p,0)` 并保留 NULL，例如 BIGINT 转为 `DECIMAL(19,0)`。这是输入表示规范化，不会将最终输出的 precision／scale 提前施加给操作数，也不改变 SAFE_DIVIDE 的零分母 NULL 政策。

该入口可返回自定义 `SqlOperator`，用规范化后的实际输入类型推导返回类型并渲染已证明的非截断原生 AST；标准 DECIMAL 除法使用 `typeSystem.deriveDecimalDivideType`，自定义算子可有自己的类型推导。来源仍返回整型商时明确拒绝；返回 DOUBLE 的映射仍不能满足 Exact。DECIMAL 的 scale 为零本身不构成截断证据，连接器可以检查可整除性并明确拒绝不能精确表示的输入。不能仅修改返回类型来掩盖实际 SQL 的整数截断，也不能因为结果是 numeric 就宣称已经实现数学上的目标精度。

未知或已知不能兑现的精确除法，应在该算子的类型推导中明确拒绝。实际绑定／推导拒绝返回 `NATIVE_ARITHMETIC_MAPPING_UNAVAILABLE`，披露 `operation=DIVIDE`、实际 `inputTypes` 及受控 `reason`：映射主动拒绝为 `MAPPING_REJECTED`，输入整数域无法无损规范化为 `INTEGER_INPUT_CAPACITY_UNAVAILABLE`，只返回整数商为 `INTEGRAL_QUOTIENT`，返回非数值为 `NON_NUMERIC_QUOTIENT`。没有执行来源 SQL，也不公开插件 cause 原文。

`ResultNumeric` 的最终 DECIMAL 容量与舍入在表达式完成后应用，当前除法入口不接收这份外围输出政策。原生商若已舍入为六位，再 CAST 为 DECIMAL(38,18) 只会补零；结果类型检查和 BigDecimal 读取不能补回位数。`prepareUnparse` 能看到类型，但 CAST 的目标 p／s 不完整表达 HALF_UP 等业务政策。也不能把最终十八位量化提前到所有中间除法，或者将三十八整数位分子窄化为 DECIMAL(38,18) 来换取 scale。若后端策略确实必须按本次目标政策选择，并已证明现有统一运算及类型入口无法表达，应提交该策略及具体缺失信息，再确定通用扩展；不能仅凭存在 typed AST 就宣称问题已经解决。

无损整数规范化不等于完整的精确量化策略。原生商先舍入到有限位数、最终输出再舍入，仍可能产生双重舍入；原生 scale 高于目标 scale 也不能单独排除它。`ResultNumeric`、`ResultRound` 和最终 CAST 都不能恢复原生运算已经丢失的位数。连接器仍须为所接受的输入和执行形态兑现数值保证；无法兑现时保持明确拒绝，不以客户端计算或业务 NativeCall 补偿。

当前类型推导与聚合发现只接收技术类型，没有本次输入的业务值域及行数合同。最终输出容量、`Approximate.minimum/maximum` 或一次调查范围不能替代受限输入的证明，也不能据此臆定评分为某个区间。先明确后端实际保证及其适用前提，再判断现有表达能否承载；尚未找到可兑现策略，不等于核心缺少一个范围参数。

作者通过 `describe-script-api` 读取 `read-connector-capabilities` 的方法和正式参数，再在脚本中提交具体聚合查询。例如，以下程序使用已获准来源所指的精确连接器读取 BIGINT 全局 AVG 映射：

~~~kotlin
import com.olap.prototype.contract.NativeValueType

val selected = olap.sources.list(EmptyRequest).items.single {
    it.binding.dataSourceId == parameters.text("dataSourceId") &&
        it.binding.sourceIdentityHash == parameters.text("sourceIdentityHash")
}
olap.output(olap.sources.connectorCapabilities(
    ConnectorCapabilitiesRequest(
        aggregate = ConnectorCapabilitiesRequest.Aggregate(
            connectorRef = selected.connectorRef,
            aggregation = ResultAggregation.Kind.AVG,
            inputType = NativeValueType("BIGINT", null, null, nullable = true),
            grouped = false
        )
    )
))
~~~

`aggregation` 使用正式聚合枚举；`inputType` 是传入聚合的表达式原生类型，包含实际可空性和完整容量，不是最终业务结果合同。DECIMAL 必须指定 precision、scale，有长度的文本必须指定 precision；类型或容量不能被后端类型工厂静默改写。`grouped=false` 表示没有分组键的全局聚合，`true` 表示存在普通分组键；分组集合中的空分组按全局方式查询。该上下文用于原生结果可空性推导。

响应的 `aggregate.mapping` 为 `MAPPED` 时给出 `resultType` 和 `aggregationRounding`；AVG 使用实际类型系统、原生聚合函数及舍入声明，二者容量不一致时明确拒绝。浮点 AVG 的 aggregationRounding 为 NONE，非 AVG 也为 NONE。`UNAVAILABLE` 携带 `NATIVE_AGGREGATE_MAPPING_UNAVAILABLE`，表示插件没有为该类型兑现映射；不会返回推测的容量或舍入。精确连接器必须用于本主体获准的来源，不能借此读取任意已安装插件。

读取不连接数据源、不执行 SQL，披露的是精确插件的编译声明，不验证后端实际版本、连接状态或完整 Query。`MAPPED` 不代替单位、算术保证、NULL、溢出和最终结果容量检查。数值 AVG 的 `valueContract` 仍为必填；事实度量的 `aggregationRounding`、完成关系中 `ResultAggregation.Average.rounding` 都须明确接受相应原生策略，`valueContract.result.rounding` 仅负责额外的最终输出舍入。原 SQL 没有 ROUND，不能据此把原生策略填为 NONE；插件也不能用未经核实的统一舍入值宣称支持。

`expandCollection(outputType,tableAlias)` 返回原生展开的放置计划：`CollectionExpansion.Relation` 提供 FROM 生成器，`InputProjection` 提供完整输入上的单个 SELECT-list SRF；取值回调只接收当前作用域的数组和生成值引用，返回完整元素与序号表达式。核心构造输入透传、逐行人口、INNER／OUTER 及输出投影，不向插件开放任意关系改写。FROM 计划用核心 LEFT JOIN 兑现 OUTER；输入投影的 OUTER 要求明确的 `COLLECTION_PROJECTION_OUTER`，由核心绑定存在标记、用 typed singleton NULL 数组保留空／NULL 输入，再补整个元素和序号 NULL。该原生类型与方言组合未兑现时，编译以 `NATIVE_COMPILATION_CAPABILITY_MISSING` 和 `EXPAND_COLLECTION_OUTER` 明确拒绝。实际 NULL 元素仍有从 1 开始的位置，重复度、完整 ROW 和求值作用域不变。完整接口与原生映射范式见[连接器 API](../project/modules/kernel/connector-api/README.md)。常量数组或其他后端的成功不替代真实表行、聚合结果及目标后端正式 Query 的验收。

零条输入关系、已存在记录上的 NULL／空数组、NULL 元素及字段全 NULL 的非空 ROW 分别保留自身含义。固定零行或一行的完整集合外壳可能被常量折叠，不能据此推定物理扫描、原生引擎之间的类型传递或记录描述符同样可用；不同 Unknown 读基础下的空人口失败与非空成功也不能定位唯一根因。插件依据实际原生类型和执行路径调查，不删除元素、序号或输入透传列，不将中间 ARRAY／ROW 压成展示字符串来绕过类型边界。

`textIdentityEquals(builder,left,right)` 接收两个保留真实类型的操作数，为文本身份 EQ、NE、IN 提供完整的 BOOLEAN 比较表达式，遵循 SQL 三值逻辑，不能隐式忽略大小写、尾空格或做 Unicode 归一。原生文本等号是否等价取决于实际类型、编码与 collation；样本相等和估算成本下降都不足以证明普遍等价。插件直接映射完整比较，不必将文本冒充二进制，也不必在 SQL 字符串中寻找操作数。数据库、列或比较策略中影响该保证的条件属于身份与来源保证，不能放入运行参数。这个合同不重新定义所有 CHAR、GROUP BY 或 ORDER BY 行为。

插件的原生 Rex 函数还须诚实声明 NULL 传播，供 Calcite 分析真实过滤条件。返回类型可空不等于严格 NULL 传播；仅当函数严格传播 NULL 且非 NULL 输入不产生 NULL 时，才可用正式 `SqlOperator.getStrongPolicyInference()` 声明 `Strong.Policy.ANY`。内置 DuckDB `ENCODE` 和 H2 `RAWTOHEX/STRINGTOUTF8` 使用这一声明，原生 SQL 与身份比较语义不变。无法证明时，核心仍保留运行关系检查，不据插件函数名或可空类型免除 NULL、人口、重复度与数量边界。

连接器若能保证受支持操作数的原生相等与精确身份相等一致，可以在核验实际类型后返回 `builder.equals(left,right)`。该保证需要覆盖列、参数与表达式的比较行为，包括 NULL、大小写、尾空格和 Unicode；不能仅根据数据库默认 collation 推定。类型或条件超出保证范围时明确拒绝。CHAR 等可能改变空格含义的类型必须单独处理，不能通过类型标签或有损 CAST 冒充等价。

原生候选等号与精确残余同时出现，并不保证后端能够执行原生 HashJoin 或运行时过滤。核心不自动删除残余、移到 WHERE，或插入额外人口裁剪。来源原生运算由插件兑现；关系规划的新增规则需要独立证明 LEFT 未匹配人口、bag 重复度、NULL 和求值作用域均保持，并由实际计划确认收益。不能将某个后端的规划限制变成所有使用者必须配置的业务规则。

实际约束或关系检查失败时，插件通过通用 `SourceGuaranteeException` 返回机器可识别的失败码；该合同属于 `warehouse-contracts`，无需依赖 JDBC 实现。核心保留保证失败码。能力不足使用 `SourceCapabilityException`，避免把不能核验与核验发现数据不满足条件混为一谈。

## 调用原生标量函数

通过 `olap.sources.connectorCapabilities(ConnectorCapabilitiesRequest(aggregate=null))` 读取当前主体可访问来源所用的精确连接器；响应的 `connectors` 每项包含 `connectorRef`、`nativeScalarFunctions`、`compilationCapabilities`、`parameterSubmission` 和 `configurationParameters`。这些是精确插件的声明，不连接来源或证明完整查询可用。函数声明给出 `id`、有序 `parameters`、`resultType`、`arithmetic` 和 `stability`，不按 SQL 方言或产品名称猜函数。

`compilationCapabilities` 只列出插件明确支持的编译能力。`CORRELATED_DERIVED_PROJECTION` 表示派生关系投影可以引用外层查询值，普通 EXISTS 的条件相关不需要这项能力。若数值求值绑定实际需要这种投影而插件未声明，编译在提交 SQL 前返回 `NATIVE_COMPILATION_CAPABILITY_MISSING`，并指出 `capability=CORRELATED_DERIVED_PROJECTION`、`operation=NUMERIC_EVALUATION_BINDING`。不退回重复内联、移除人口守卫或提前计算未选中分支。

来源表达式使用 `SemanticExpression.NativeCall`，提交精确 `connectorRef`、`function` 和有序 `arguments`。参数使用正式 Scalar 与 PhysicalTable 变体，分别持有来源表达式或精确物理表身份和版本。作者阶段核对精确函数声明和 P 可引用，不从 P 暗选兑现实现；发布／编译核验实际采用与函数的精确连接器一致。`NativeCall(A)` 不会因发布选择 B 而被改写，冲突需明确修订定义。仅作为函数参数的 P 也作为真实来源依赖冻结和授权。来源表达式中的内联调用只允许本次精确发布的表闭包；它不是表名文本，也不接受任意 SQL。技术调查 `profile-relationship` 不提供已采用表解析，不支持此类调用。

完成关系表达式使用 `ResultExpression.ResultNativeCall(connectorRef,function,arguments)`，有序实参均为 `ResultExpression`。它在当前实际来源中变换已有标量值，不另选来源、不引入物理表；函数声明的参数必须均为 SCALAR。发布场景须明确授权 `NATIVE_CALL` 表达式种类，字段及实参继续经过现有表达式授权。已完成关系的 CASE 分支可以持有该节点，所有分支都静态核验，运行调用保留在实际分支位置。原生函数对格式文本、NULL、非法输入和求值的行为由插件及来源兑现，不能用无条件的来源预解析代替条件分支。完整作者构造见[条件原生调用](../examples/agent-client/conditional-native-call.olap.kts)。

原生数值结果保留声明的算术及容量，作为维数一数量继续参与组合；DECIMAL 需要明确原生 precision 和 scale。维数一的数量实参只用于 NUMBER→NUMBER，并须满足函数的算术保证；非数值返回只消费分类标量，带业务单位的测量也不能通过该入口擦除单位。函数声明不推导业务天数、货币或单位转换。实际 Kotlin 构造器、类型及局部规则从服务 API 发现读取。

调用保留在本次来源 SQL 中执行。来源要求通过 `uses` 明确区分 `ROWS`（读取数据行）与 `NATIVE_TABLE`（原生函数的表参数）；两者都核对精确来源身份与授权，行约束及有限集合核验按实际 `ROWS` 用途要求。仅作函数参数的表也进入依赖闭包，无需伪造字段引用。

原生函数交出标量，不能以该入口返回表、数组或记录。数值结果必须声明算术保证：`EXACT_DECIMAL` 为精确算术，`NATIVE_APPROXIMATION` 明确结果域及绝对、相对容差，`NATIVE_FLOATING` 沿用原生浮点算术且不声明这些界限。`NATIVE_FLOATING` 仍接受容量、有限值及溢出核验；它不能通过 CAST、输出为 DECIMAL 或定义组合取得精确性或误差界限。结果的技术表示与算术保证分别核验，不能相互替代。

`IMMUTABLE/STATEMENT/VOLATILE` 按函数实际依赖声明稳定性；精确物理版本不冻结表内数据，不能据此宣称读取该表的结果恒定。函数调用不能因显示名相同而被当作常量或共享。插件只映射已校验参数到 SQL AST，业务定义与 Query 继续拥有业务公式、对象总体和计算阶段；函数声明不扩大来源、表或公开输出权限。

### 原生映射的来源环境

依赖后端版本、locale、编码或 collation 的映射，由精确插件声明适用前提。影响解析、比较或数值含义的可配置前提，通过 `SourceConfigurationParameters.identityNames` 声明并放入 `identityParameters`，随精确插件和来源绑定冻结；等待预算等仍属于运行参数。摘要冻结的是期望配置，不读取或证明真实后端环境。

`validateConfiguration` 校验参数与插件支持组合；`SourceAccess.execute` 对本次实际访问环境负责。插件依据访问通道确实提供的同会话、不可变部署或其他有效机制，将核验与主查询关联。独立一次版本查询、历史样本或初始化观察，本身不保证后续请求命中相同环境；也不要求所有来源先取得 catalog 调查权限。实际环境不满足或不能兑现映射前提时，插件明确拒绝，使用已有来源失败合同交付受控原因及实际请求事实。原生探测、SQL 和内部异常保留在私有诊断；`SourceReadBasis` 继续表达数据读基础，不作为引擎环境证书。

`nativeScalarCall` 接收函数与 SQL AST 实参，不接收来源配置；声明的映射应由精确插件及显式实参确定，不能声称配置会自动传入编译函数。`IMMUTABLE/STATEMENT/VOLATILE` 约束求值与共享，不替代环境保证。TEXT 签名也不证明字符串满足某个十进制词法或容量域：解析映射需要对允许输入兑现语义，对域外输入明确失败；调用方过滤或最终 DECIMAL 不能替代这一责任。

## 交付结果

插件返回 `SourceExecutionResult(frame,readBasis,request)`，其中 `frame` 是 `ResultFrame`。列必须与核心给定别名、顺序和类型一致；技术类型可以来自来源响应，也可以来自使用方明确的类型合同。`JdbcColumnType` 在这里是 SQL 标准类型码、原生类型名、精度、标度和可空性的值对象，不要求 `ResultSetMetaData`、`Connection` 或任何 JDBC 驱动。

结果行序由完整主 SQL 的最终排序语义决定，访问实现按来源返回顺序物化，核心继续保留该行列表。窗口内的排序、TopN 的选行顺序和集合内部排序不自动构成最终结果的展示排序；没有最终 `ORDER BY` 时不承诺稳定行序，排序键未区分的并列行不额外确定顺序。`SourceSqlRequest` 不另给独立排序键，列别名映射与输出类型不能当作排序合同。运输封装、分页和预算处理必须保留主 SQL 已有的选行及行序，不把有序子查询包进聚合后就宣称保序，也不解析 SQL 自行重建排序或在客户端重算；不能兑现时准确失败，不交付被重排、少行或截断的成功结果。

请求中的 `ResultValueType.Scalar` 用可空的 `Integer precision/scale` 表达 SQL 类型容量；未指定时为 `null`，不使用负数哨兵，也不能擅自补成零。DECIMAL 必须给出正精度及 `0..precision` 内的小数位。插件明确处理这些可空字段，装载器按精确连接器 API 标识检查接口。

HTTP 服务的分页、截断或未完成状态由插件识别；必须按明确的一致性合同取完或准确失败，不能把一页或已截断的数据当作完整结果返回。

精确数值使用 `BigDecimal`、`BigInteger` 或有界整数，不经过 JSON 浮点转换。日期、时间和带偏移时间使用对应 Java 时间类型。集合和记录使用通用 `SqlResultValues.ArrayValue`、`RowValue`；保留 NULL 集合、空集合、NULL 元素、重复元素和记录字段顺序。不能把集合编码成字符串或把 NULL 当零。

插件在读取过程中遵守共享结果预算及取消控制；宿主对返回结果再次检查预算，并按编译合同递归核验形态、精度、容量、NULL 和数量含义。收到响应不等于结果已通过语义合同校验。

## 执行事实、读基础与重放

`SourceExecutionResult.request` 给出来源请求身份、接受状态、响应状态和已知执行结局；未知必须显式为 UNKNOWN。HTTP 状态可以通过受控的 `protocol/status` 交出，不能附加端点、headers、SQL 或请求正文。失败通过 `SourceExecutionException` 携带 `SourceFailureEvidence`，保留安全错误码、实际失败的 `stage`、SQLState/原生码、请求与取消事实。插件在准备、连接、保证核验、提交、读取和结果处理边界记录阶段，包装层保留它；原始异常仍留作服务私有诊断。

客户端停止等待、取消动作调用成功和来源确认取消是不同事实。`WarehouseExecutionControl.cancellationEvidence()` 交出实际取消证据；void cancel、终止工作进程或网络中断均不能证明来源已取消。插件只有收到来源确认时才能声明 Confirmed，并同时给出 CANCELLED 执行结局。

`readBasis` 为 `Unknown(reason)` 或 `Versioned(authority,version,partitions,pinning)`，描述本次实际读取的数据基础。结构 snapshot、schemaHash、访问绑定及定义版本均不替代它。动态函数仍在同一 SQL 内求值；插件不能事前另查一个分区值，再宣称它就是该 SQL 实际采用的值。不能取得完整读基础时如实返回 Unknown。

保存结果可以按精确引用回读，不要求来源再次执行。按保存查询重新执行会建立新运行，默认读取当时数据。只有来源能强制使用指定版本时才声明 `PINNED_READ`，并接受 `SourceSqlRequest.expectedReadBasis`；核心核验返回读基础与请求完全一致，不自动换成最新。`QueryExecutionRequest.readBasis` 使用已知的 Versioned 基础；不支持 pin、版本已不可用或实际基础不符时准确拒绝。可 pin 不等于历史版本永久保留，也不意味着任意动态“最新”查询已可重放。

一次完整计算要求同一精确 `SourceBindingRef`、方言和连接器。跨绑定的输入在编译时返回 `SOURCE_BINDING_CONFLICT` 及具体作用域和来源引用。保存结果是读取交付物，当前没有把结果引用重新导入为 Query 输入的入口；需要物化时由外部系统维护真实来源，并通过正常结构与语义登记采用。产品不隐式联邦查询或客户端重算。

## 能力缺失

来源分别声明参数绑定、取消、一致读取、事务快照、结构调查、数据预览、列画像、关系画像、约束核验和封存核验。`list-data-sources` 返回获准来源实际声明的 `accessCapabilities`。

没有结构调查能力不影响显式结构登记。没有参数绑定能力不影响明确选择字面量提交的查询。需要约束或关系检查时要求 `CONSISTENT_READ`，不强制要求 `TRANSACTION_SNAPSHOT`；后者仅声明来源具有事务快照这一具体能力，内置 JDBC 实现使用它兑现一致读取。没有一致读取能力的来源仍可执行不依赖这项保证的查询。没有数据库端取消能力也不会使整个来源不可用，但服务请求结束不表示远端 SQL 已被取消，不能宣称具备这个保证。

缺失能力以 `SOURCE_CAPABILITY_MISSING` 返回具体 `dataSourceId`、`operation`、`capability`；查询执行失败还保留在该次使用记录中。使用方据此补充协议实现或调整本次实际需求，无需复制服务主类或核心流程。

插件组打包与类加载合同见 [连接器 API](../project/modules/kernel/connector-api/README.md)。
