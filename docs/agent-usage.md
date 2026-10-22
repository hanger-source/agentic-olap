# 外部 AI 使用指引

外部业务 Agent 通过 Agentic OLAP 的统一 MCP 服务调查真实来源、与业务方澄清含义、建设和组合语义资产，再复用这些资产组织完整 Query、执行并读取结果。定义可以引用定义，Query 可以调用沉淀后的高层定义，也可以组合已有定义与内联计算；语义展开、类型与数量检查、SQL 生成和执行由同一确定性引擎负责。

使用者的业务项目承载 Agent 的运行、实际服务接入、会话上下文和业务界面。业务理解在真实业务问题、源观察和已有语义之间逐步形成，可复用的含义沉淀到服务中的定义；核心服务不启动模型。

## 从哪里接入

按[安装与启动](../skills/agentic-olap/references/server.md#安装与启动)安装产品，再启动服务。使用时以所连接服务实际返回的工具和合同为准：

```sh
agentic-olap start
```

默认仅监听本机，自动创建持久配置和随机访问令牌，不需要外部认证设施、Python 或 Java 安装。在另一个终端通过同一产品发现和调用真实工具：

```sh
agentic-olap info
agentic-olap tools
agentic-olap call describe-operation --payload '{"operation":"execute-query"}'
```

`info` 给出实际地址、配置和令牌文件路径，不打印令牌值。外部 MCP 客户端使用该地址及 `agentic-olap token` 显式取得的令牌；也可直接通过产品 `tools/call` 操作同一服务。[Skill](../skills/agentic-olap/SKILL.md) 与完整部署说明随同轮构件交付。

| 当前情况 | 入口 |
| --- | --- |
| 已有服务地址和访问令牌 | 用支持 Streamable HTTP 的 MCP 客户端连接服务的 `/mcp`，直接使用获授权工具；无需项目源码或服务器文件 |
| 需要启动本地服务、接入连接器与业务源 | 从[服务使用与部署](../skills/agentic-olap/references/server.md)进入；本地认证自动初始化，实际来源与业务含义由使用者提供 |
| 需要面向远程客户端提供服务 | 明确配置外部 JWT、网络与授权，见[远程部署](../skills/agentic-olap/references/server.md#外部认证的远程服务) |
| 需要修改产品源码、计算语言或连接器 | 看[贡献说明](../CONTRIBUTING.md)，进入独立研发过程 |

普通使用无需 pip 或源码客户端装配。[客户端研发示例](../examples/agent-client/README.md)展示独立 SDK 和 Codex 接入。模型账号由客户端管理。业务 Agent 日常通过 MCP 操作语义资产和查询；研发 Agent 修改产品源码、连接器、构建与部署属于独立工程工作。客户端只引用已配置来源，不提交数据库口令、JAR 路径或服务器命令。

## 以实际服务合同为准

连接后通过 MCP 工具发现（协议方法 `tools/list`，客户端 SDK 提供对应方法）读取当前可见工具，再用 `describe-operation` 的 `{operation}` 读取所需操作的合同。工具是否可用、参数形状和授权范围由实际部署决定，不从本地 SDK、历史查询或名称猜测。

下文列出的参数对象都是工具的 `payload` 内容。直接使用 MCP SDK 时，调用参数还须包一层，例如调用 `describe-operation` 的 arguments 为 `{"payload":{"operation":"execute-query"}}`；读取语言合同为 `{"payload":{}}`。产品 CLI 的 `--payload` 选项会完成这一层封装。

治理有自己的按需发现入口：`discover-governance-reads` 与 `describe-governance-read` 发现并描述读取操作；`discover-governance-commands` 与 `describe-governance-command` 发现并描述写命令。描述工具的 `{operation}` 读取完整输入。写命令描述还支持 `path` 字段名数组和终点的 `variant` 判别标签，例如：

```json
{"operation":"create-member-source","path":["definition"],"variant":"PROJECTION"}
```

这只展开 `definition` 中 `PROJECTION` 的完整局部类型和适用规则，不必同时读取共同计算体的全部类型图。路径相对于该命令的输入；返回的 `contentHash` 标识本次选中合同的内容。未知路径或标签会被拒绝。实际调用使用 `read-governance` 或 `write-governance`，外层为 `{operation,payload}`，内层必须符合刚读取的合同。命令中的主体字段必须与认证主体一致。

写成功返回 `governance-write-receipt/v1`：`operation` 为本次命令，`result` 为精确身份、版本、状态及必要摘要，`reads` 给出可直接用于 `read-governance` 的读取请求。回执不重复返回完整版本正文和全部列。需要完整定义时用 `read-asset-definition` 的 `{assetType,id,version}`；需要资产记录时用 `read-asset-version`。物理列与其他集合沿回执给出的分页入口读取，不从显示名重新猜身份。

这些是合同发现能力，不是强制业务流程。空库没有发布或指标时，可以从获授权数据源调查和定义建设开始；先读取相关命令的真实形状，不预设某个业务线、指标或版本已经存在。

## 发现与沉淀业务含义

有公开定义时，用 `list-publications` 发现获准的发布；受限授权需要明确查询范围。选择返回的精确坐标后，`search-semantics` 接收 `{coordinate,query?,kind?,offset?,limit?}`，分页查业务摘要。`read-semantic-asset` 使用搜索返回的 `{assetReference}` 读取口径、参数与来源解释。

需要理解内部含义时，`read-semantic-definition` 接收 `{catalogRef,kind,reference}`，其中 `kind` 为 `CALCULATION`、`FACT`、`INSTANCE` 或 `MEMBER`，精确引用来自公开资产及其正文。它读取一层冻结定义，可以沿引用继续读取；无需先下载完整依赖闭包。`read-semantic-catalog` 是显式读取整个精确目录的选择。

需要建设或修订语义时，业务 Agent 通过 MCP 调查实际来源、读取已有资产，与业务方澄清口径，把散落在固定过滤、指标实现与查询条件中的业务含义提炼为定义。逻辑字段、成员形成、共同计算及其引用可以逐层复用；上下文采用绑定真实来源、测量和参数，发布交出可供查询消费的精确语义。已提交的发布可从同一在线服务发现，不要求客户端搬运内部发布文件或重启服务。

来源结构也可以由使用方的配置或数据目录明确提供。支持该合同的服务通过 `register-source-structure` 接受表、字段、SQL 类型、约束声明与出处版本；先读取实际服务返回的命令合同。没有 catalog 调查能力，不代表不能登记和使用该来源。HTTP 来源接入与发行版本要求见[来源访问扩展](source-access.md)。

定义修订、上下文采用和发布是分别明确的操作。已有适用定义与发布时可直接使用，不要求每次重新建模或发布。已有引用保持原版本，不自动切到“最新”。公开语义读取也不授予内部来源或治理写权限。

已有共享成员采用需要改用另一精确定义版本时，使用 `revise-member-adoption`。`source={id,version}` 选择草稿主题中的原成员采用；`expectedDefinition={id,version}` 给出当前共享引用，`definition={id,version}` 明确指定目标。`displayName`、`bindings`、`fixedArguments` 为完整替换值，`actor` 为当前主体；不从旧绑定猜测遗漏项。服务保留采用身份，事务内重验计算消费者及已有场景权限，失败回滚。`MEMBER_ADOPTION_CONFLICT` 返回预期与当前共享引用，调用者重新读取后决定如何修订。激活后的上下文先显式修订主题；`carry` 可以延续原采用，再在新草稿中使用本命令。旧发布保持原坐标。

修订前可通过 `read-governance` 调用 `read-definition-impact`，按精确 `{assetType,id,version}` 读取消费者与引用路径；`read-definition-dependencies` 分页读取直接依赖。`read-definition-revision-diff` 接收 `{assetType,id,fromVersion,toVersion}`，列出正文、拥有成员、依赖和场景授权的结构变化。差异分类不证明业务口径等价，也不自动升级任何引用。独立可解析的事件精确版本可以在同一发布共存；真实闭包冲突会交出对象与引入路径。

成员创建和首次采用命令不会覆盖既有成员采用。分析维度的修订提交完整根定义，并创建请求指定的默认层级；旧版本的其他层级、映射、值提供者和对齐声明不会隐式复制。按新版本的精确坐标显式建设所需子项，再修订采用它的场景。映射槽位已占用时，`RESOURCE_CONFLICT` 给出已有映射、请求引用、读取入口和所属版本的修订方向，不覆盖原记录。

研发示例的作者模板、Kotlin 准备代码和批量建模脚本，可以展示预定口径的产品行为。业务 Agent 的实际使用则需要观察其在业务项目中如何调查、澄清、建设或选择资产、组织查询并解释结果；不能把预先固化的业务理解当作 Agent 完成这些工作的证据。

## 组织本次完整查询

`read-query-language-contract` 的空 payload 返回本部署的语言合同。其 `intentContract` 给出完整 intent 的版本与精确字段；外层语言合同自身的 `formatVersion` 不是 intent 的版本。`read-semantic-expression-contract` 的空 payload 返回表达式、函数与引用合同。

需要局部输入时，语言合同可选 `{"root":"MEMBERS"}`、`{"operation":"REGROUP"}` 或 `{"section":"intent"}` / `{"section":"scope"}`，三类选择互斥。根和 scope 支持相同的字段路径与终点变体选择；阶段参数由各自算子合同给出。例 `{"root":"MEMBERS","path":["condition"],"variant":"LEAF"}` 说明该条件的完整形状。这里的 `values` 使用带 `kind` 的绑定，公开查询中的布尔常量是 `{"kind":"SCALAR","scalarType":"BOOLEAN","value":"true"}`，不是裸 JSON 布尔值。`REGROUP` 的计数由引擎推导数量合同，`valueContract` 显式为 `null`，`aggregationRounding` 显式为 `"NONE"`；数值聚合按其局部合同声明数量保证。

组织 AVG 等数值聚合前，先读取 `describe-operation` 的 `read-connector-capabilities` 合同，再以 `aggregate` 提交获准的精确 `connectorRef`、`aggregation`、完整 `inputType` 和 `grouped`，查询插件对该输入的原生映射。`aggregate.mapping` 返回原生结果类型与 `aggregationRounding`，或明确的 `UNAVAILABLE`。这不执行来源 SQL，也不证明完整查询可用；输入类型取实际聚合表达式，不能用最终输出 DECIMAL 代替。AVG 必须另外提供数量合同，明确接受原生舍入，再声明最终输出容量及额外舍入。详见[来源合同](source-access.md)。

当前完整 intent 恰好有六个字段：

| 字段 | 含义 |
| --- | --- |
| `formatVersion` | 取 `intentContract` 声明的值，当前为 `olap-query-intent/v6` |
| `scenarioId` | 精确发布中的场景身份 |
| `scenarioVersion` | 精确场景版本，正整数 |
| `releaseId` | 精确发布身份 |
| `releaseVersion` | 精确发布版本，文本 |
| `computation` | 完整计算对象，其结构由 `intentContract` 引用的 scoped input、根计算与阶段合同说明 |

四个发布坐标字段直接放在 intent 上，不包成 `coordinate`；intent 不增加 `capabilityHash`。其他工具可能有自己的 `coordinate` 或哈希参数，应分别遵循该工具合同。调用实例、成员、参数、维度和单位取自所选精确发布，不按显示名猜引用。

Query 可以直接调用已沉淀的高层定义，也可以组织尚未沉淀的完整计算；计算与输入结构由语言合同决定，不把 SQL 片段塞进 DSL，也不在客户端重算后冒充引擎结果。

内联 `COMPUTE` 中，`Call.target.kind=MEASUREMENT` 选择测量槽已绑定的精确采用实例。它的 `dimensionSlots` 和 `measurementSlots` 必须显式为 `{}`；这些绑定由实例拥有，即使父层映射看似相同也不能重传。成员、关系和参数继续按各自声明显式传入。完整语言合同及局部 Call 类型的 `rules.targetRules.MEASUREMENT` 公开这项限制；违反时，`MEASUREMENT_BINDING_OVERRIDE` 给出具体字段路径和 `EMPTY_MAP`／`NONEMPTY_MAP` 形态，不会忽略映射。

成员槽用于对象与属性语义；测量槽采用可调用的精确测量实例；关系槽接收本次已经完成的关系。共同计算的 `relationSlots` 声明列形态与 `keys`：`null` 不要求唯一性，`[]` 要求真实空唯一键（至多一行，不保证有行），非空列表要求完整的实际唯一键。关系槽通过 `RELATION` 根读取；采用中的 `PARAMETER` 由本次 `relations` 明确填入 `LOCAL`，其中 `inputId` 指向调用者的 `ROOT` 输入，`outputSlots` 映射实际输出列。定义内部也可绑定自身的完成输入，再通过 `Call.relationSlots` 传给其他定义。输入按已绑定的作用域转发，同名输入不会被重新捕获；真实数量、单位、NULL、重复度和观察证据由引擎保留，不隐式重算、归约或补零。公开候选的 `relationParameters` 给出需要填入的形参。

完成的非数量标量，例如 `MAX(TEXT)`、文本函数或布尔计算，使用 `{"kind":"SCALAR","valueType":"TEXT"}` 声明共同列形态；类型还可为 `ENUM`、`BOOLEAN`、`DATE`、`LOCAL_TIMESTAMP`、`OFFSET_TIMESTAMP`。它不声明成员属性角色，也不把文本变为业务维度；实际 SQL 类型、NULL 和计算依赖继续保留。直接的维度或成员属性仍须使用已有身份／属性形态，不能通过 SCALAR 擦除其含义；数量仍须 QUANTITY。此处 SCALAR 是关系／集合输出形态，与参数值的 SCALAR 绑定属于不同合同。可将同一 ROOT 完成关系的不同列视图显式传给基础结果与保存算法，再用 JOIN_INPUT 或其他正式阶段组合，无需复制来源计算。

当可复用计算需要本次来源人口时，声明 `kind=CONDITION`、`valueType=SOURCE_CONDITION` 的参数，调用值为 `{"kind":"CONDITION","condition":<正式 DimensionCondition>}`。计算体将该参数显式绑定到某个 `Call.sourceCondition`；普通 Query 调用的 `sourceCondition` 则直接属于该事实调用。只有实际事实消费者接受非 `TRUE` 条件，调用计算定义时通过其声明参数继续转发，不自动过滤整个子树。TEXT 日期可保持 TEXT 条件，不需要伪造时间角色。条件中的维度、操作符、关系筛选和精确事件仍按本次发布授权核验。具体条件不能成为采用的固定参数；长期业务人口使用成员或计算定义沉淀，不保存依赖某次发布过滤授权的片段。

`execute-query` 选择 `intent` 或 `queryReference` 其中一个执行。可以直接提交完整查询；`validate-query` 是可选的校验调用，`save-query`、`update-query` 是显式保存或修订复用版本的操作。查询不要求先保存、建立 Analysis 任务或提交分析结构。

来源表达式和完成关系表达式共用受支持的标量函数声明；完成关系可使用 `CALL`，例如通过 `CONCAT` 保留来源原有的文本身份形成规则。实际函数、参数与使用位置从两类表达式合同读取；来源原生 `NATIVE_CALL` 的物理依赖和授权不能借普通 CALL 绕过。

一次完整计算限于同一精确来源绑定、方言和连接器。多个定义、事实或子计算不等于跨来源联邦执行；冲突会列出具体输入。保存结果不能直接作为另一次 Query 的关系输入。

`explain-query` 接收完整 `intent` 或 `queryReference`，可按 `section=summary|scopes|sources|plan|compilationScale` 和分页参数读取已编译的人口、资格、时间、粒度、阶段及关系计划，不执行来源 SQL。数量检查保留在原求值与聚合边界，SQL 规模统计区分实际检查节点与错误文本次数。计划中的共享关系不保证后端物理扫描一次；未取得的实际基数、扫描量及逐行 NULL 原因明确保持未知。解释可按需读取，不是查询前置。

## 读取结果与实际使用记录

执行和结果交付分别读取真实状态。小结果可直接返回；大结果需要在执行请求中显式选择 `saveResult: true`，成功交付后使用返回的精确 `resultReference={runId,sha256}` 分页读取。交付失败不代表执行未发生，不因没有直接结果而盲目重跑。

执行返回分别保留 `clientOutcome`、`sourceOutcome`、已脱敏 `error`、`timingNanos` 和 `sourceReadEvidence`。停止等待时若来源结局未确认，状态为 `OUTCOME_UNKNOWN`，不会伪报来源取消成功。保存的失败资产也保留诊断内容，可沿结果元数据路径读取。

定义冻结不冻结动态数据。`sourceReadEvidence.basis` 说明本次已知数据版本或未知原因；保存查询的再次执行默认读取当时数据。仅来源声明且兑现 PINNED_READ 时，执行请求才可显式传入精确 VERSIONED `readBasis`，不支持时拒绝。历史结果按保存字节读取，不重跑来源，也不要求重新装载当年的执行插件。

`read-query-result` 读取行和所选列；`read-query-result-metadata` 读取结果元数据；`read-query-result-value` 读取递归值；`profile-query-result` 提供同一保存结果的概要。各自路径、偏移与页限通过 `describe-operation` 取得。值与元数据路径是结果内部路径，不是服务器文件路径；保留原生数值、单位、NULL、ARRAY 和 ROW 的含义。

结果页的列摘要独立给出 `columnId`、`displayName`、`name`、`symbolId` 和 `typeSummary`。普通名称不必为了展开整份符号血缘而另发请求；复杂类型、数量解释和来源依赖按各自 `metadataReference` 读取，极长的名称本身也可分页读取。标量类型未指定的精度或小数位为 `null`，DECIMAL 保留明确容量；这不改变 INTEGER、FLOAT、DECIMAL、NULL 与递归集合值的无损表示。

`search-query-usage` 以 `{coordinate,text?,offset?,limit?}` 检索当前主体获准读取的实际用法；`read-query-usage` 按 `{runId,offset?,limit?}` 读取执行、交付、引用和说明。执行时可显式提交 `question`，事后可用 `append-query-usage-note` 的 `{runId,text}` 追加业务说明。这些记录只反映实际保存的事实与明确说明，不是模型记忆，不自动推断用户偏好或不存在的历史。

部署维护者可用 `read-deployment-availability` 选择精确 `publications/queries/results`（合计至多 100 项），分别核对定义读取、发布与执行前提、保存查询编译及结果文件读取。该操作要求治理资产授权，执行前提核验还要求相应来源授权；结果仍限本人并核对保存时的发布归属。它不执行来源 SQL、不迁移旧格式；可读结果也不证明旧查询仍可执行。软件、插件 API、存储与结果格式分别核验，相同存储格式号不构成完整升级验收。

## 含义不足与失败

单位、时间解释、比较人口、日均分母、身份或分摊依据不明确时，先读取相关定义和来源依据，仍不足则向业务方说明缺少的含义。不要自动补零、按同名拼接、猜业务时区或以已有几行数据推断完整总体。需要长期复用的口径可以显式沉淀为定义；仅本次需要的组合可以留在 Query。

授权、类型、数量或资源预算拒绝应按真实原因处理。不要自动换发布、放宽口径、扩大服务器预算或重试可能已经执行的查询。部署容量由部署方决定，业务说明由业务方确认；源码修复进入独立研发过程。
