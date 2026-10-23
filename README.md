# Agentic OLAP

**面向 Agent 的可组合语义 OLAP：把业务数据库、可复用语义定义和复杂查询连接起来。**

Agentic OLAP 建立以 OLAP 为基础的高阶可组合语义计算模型：将原本散落在指标实现、固定过滤和查询条件中的业务含义，提炼为可执行、可解释、可引用的定义；让定义能够继续组合定义，逐层积累可复用的业务认知。事实、维度、关系与基础指标仍提供业务计算的来源和语义依据。

项目把对象集合、测量范围、计算粒度、总体参照、时间口径和有序计算阶段显式表达出来。Agent 的推理用于理解业务与组织计算；语义展开、类型检查、SQL 生成和执行由同一确定性引擎完成。

语义管理与查询是独立能力：Query 既可以直接调用沉淀后的高层定义，也可以组合已有定义与内联计算，自主组织本次完整语义查询。定义内部的计算与查询中的组合，由同一确定性引擎承载执行。

产品部署为服务，向外部业务 Agent 提供统一 MCP 入口。业务 Agent 根据实际业务背景，调查授权数据源，澄清口径，建设、组合和复用定义，组织查询并读取结果。定义、采用、版本、发布和使用记录由服务管理，供后续继续发现与使用。业务项目接入自己的数据库连接器、Agent 和业务界面；语义管理、查询和执行由本产品提供。

仓库：[hanger-source/agentic-olap](https://github.com/hanger-source/agentic-olap) · [AI 使用指引](docs/agent-usage.md) · [快速开始](#快速开始) · [文档导航](docs/README.md) · [参与开发](CONTRIBUTING.md)

## 能表达什么

| 业务计算 | 语义表达与执行方式 |
| --- | --- |
| 支付、退款等多个事实共同计算 | 各事实独立测量，再按明确对象与维度对齐，避免关联放大金额 |
| 固定同群比较与各期重新圈群 | 对象资格集合与测量期间分别表达，共用或独立形成对象集合 |
| 多级统计与真实权重 | 按观察单位形成中间关系，再进行分组、重聚合、加权或结果计算 |
| 重复投递、取消与历史版本 | 声明记录身份、版本排序和筛选顺序，保留不同业务形成规则 |
| 历史归属、存量与窗口 | 分别绑定测量时间、关系时钟、历史时点和实际日历，表达预热与展示范围 |
| 一次查询中的多阶段组合 | 用嵌套输入、集合、关系对齐、内联 `COMPUTE` 和有序阶段组织完整计算 |

例如，“对本月达标客户，按同一批客户比较两期净额并计算增长率”可以组织为：

```text
形成资格客户集合
    → 在同一对象集合上独立测量两期支付与退款
    → 按客户身份对齐并汇总
    → 计算两期差额与增长率
```

“净额”“资格客户”“固定同群两期增长”可以分别沉淀为可引用的定义，由高层定义继续组合已有含义。Query 可以直接调用已发布的高层定义，也可以在授权范围内组合已有定义与尚未沉淀的内联计算；粒度、数量、单位、NULL 和来源含义沿计算链保留。对应的实际用法见[订单与跨期退款](examples/orders/README.md)。

## 从数据库到结果

```mermaid
flowchart LR
    B[业务背景与问题] --> A[外部业务 Agent]
    A <-->|MCP 调查、定义管理、查询与结果| S[Agentic OLAP 服务]
    S <--> M[持久语义资产与使用记录]
    S --> E[确定性语义编译与执行]
    E --> C[独立连接器]
    C <--> D[授权业务数据库]
```

1. **调查与建模**：业务 Agent 读取真实元数据、样本与源规则，结合业务背景建立物理采用、逻辑字段、关系、事件、指标、维度和成员来源。
2. **管理与发布**：业务 Agent 使用服务管理定义、上下文采用和版本，将批准的语义及其执行依赖冻结为精确发布；高层定义可以继续引用已有定义。
3. **构造查询**：业务 Agent 发现公开语义，自主组织主计算、独立输入、总体、条件、时间及有序阶段。完整查询可直接执行，也可单独校验；需要复用时显式保存。
4. **编译与执行**：上游核对发布授权，内核完成语义绑定与计算规划，Calcite 生成 SQL，连接器通过 `SourceAccess` 执行并校验实际输出。
5. **消费结果**：读取具有类型、业务来源和明确 NULL 含义的结果。完整 JSON/MCP 消息装得下就直接返回，装不下自动完整保存并交出读取入口。长期保留业务查询结果仍由明确的保存选项管理。

不可变发布固定的是语义定义及其批准范围。它不预先规定每一个业务问题的查询结构；可读的解释依赖也不自动授予私有定义或输出的调用权限。

上述能力按问题需要使用。已有合适发布时可以直接查询；尚未沉淀的计算可以在本次 Query 中组织。是否值得沉淀，由 Agent 结合实际可读取的历史、业务信息和人工确认判断，服务不会凭空补出业务上下文。

## 快速开始

通过一个 npm 包安装并启动完整服务：

```sh
npm install --global @hanger-source/agentic-olap
agentic-olap start
```

首次启动生成本机访问令牌、配置与持久目录，服务监听回环地址。保持该进程运行，在其他终端或 Agent 的命令工具中使用同一服务：

```sh
agentic-olap info
agentic-olap tools
agentic-olap call describe-script-api --payload '{}'
agentic-olap call execute-script --payload '{"executionId":"sources-1","waitMillis":1000,"source":"olap.output(olap.sources.list(EmptyRequest))","sourceName":"sources.olap.kts","dslApiVersion":"agentic-olap-kotlin-dsl/v2","parameters":{}}'
```

回执包含 `sessionId`、`executionId` 和实际状态。若仍为 `RUNNING`，调用 `wait-script` 等待同一执行；后续代码传同一 `sessionId` 和新的 `executionId`，可继续使用已有对象与函数。同执行身份的重试只观察已有执行，不重复副作用。用完后调用 `close-script-session` 关闭自己建立的会话；语言会话关闭不会删除已保存的定义、发布或结果。

`olap.output` 是明确的输出动作，可以多次调用；普通打印也进入本次执行输出。末表达式可以是任意 Kotlin 对象，不自动转成输出。完整的会话、等待、取消与恢复规则见[AI 使用指引](docs/agent-usage.md)。

Agent 使用说明与完整配置模板位于本仓 [skill](skills/agentic-olap/SKILL.md)，通过 GitHub 安装：

```sh
npx skills add hanger-source/agentic-olap --skill agentic-olap
```

源码、skill 与发行记录都在本仓库。Node.js 需要 20 或更高；产品支持 macOS arm64。已有远程服务的使用者可以直接连接 MCP。

`npm install` 自动从 JitPack 取得本版自有 Kotlin 运行构件和内置连接器，从 Maven Central 取得第三方 JVM 库与驱动，按精确清单校验并备齐服务、脚本编译运行和插件所需的 JAR。npm 包携带 Go 程序、JRE 和必要资源，安装完成即可启动；启动时不下载依赖。`agentic-olap sdk` 返回 JitPack SDK 坐标和随包文档，独立插件用 Gradle 匿名取得；`agentic-olap connectors` 返回已安装插件的 manifest 路径与精确引用。使用者无需查找链接、解压或执行第二个安装命令，具体用法见[产品安装与配置](project/cli/README.md)，构建及发行顺序见[安装与发布](docs/installation-release.md)。

| 你现在要做什么 | 入口 |
| --- | --- |
| 使用已有服务建设定义或查询业务 | [AI 使用指引](docs/agent-usage.md)、[MCP 客户端](examples/agent-client/README.md) |
| 安装并启动自己的服务、连接业务库 | [产品安装与配置](project/cli/README.md)、[服务配置](skills/agentic-olap/references/server.md) |
| 研发时运行自含业务例子 | [远程业务示例](examples/README.md)；数据和业务定义由例子作者提供 |
| 修改引擎、语言或服务源码 | [参与开发](CONTRIBUTING.md)、[架构](docs/architecture.md) |

空库启动后，使用方在 `info` 返回的配置路径登记独立连接器与实际业务源，重启服务，再由 Agent 通过 MCP 调查与建设语义资产。已有适用资产时直接复用；连接器、远程认证和授权配置见[服务说明](skills/agentic-olap/references/server.md)。本机默认启动不依赖外部身份系统。

业务源保留稳定 ID，每份访问绑定具有精确 `sourceIdentityHash`。Agent 从授权源列表选择绑定后调查或登记结构，物理版本与发布保留该选择；更换端点或插件不会自动改写旧发布。精确插件还可声明只读原生标量函数，Agent 从服务读取声明后在共同表达式中调用，物理表参数继续受精确采用与来源权限约束。配置、结构登记及插件扩展见[来源访问说明](docs/source-access.md)。

[订单业务示例](examples/orders/README.md)用作者给出的数据和定义展示以下实际计算，完整研发运行命令见[示例导航](examples/README.md)：

| 查询含义 | 实际结果 |
| --- | --- |
| 原订单期间净额 | 175.00 |
| 固定一月合格客户集合，本期净额 | 115.00 |
| 同一集合，上期净额 | 80.00 |
| Query 内联组合得到的固定同群增长率 | 0.4375，即 43.75% |

示例结果证明对应输入的确定性计算，不代表 Agent 已自主完成实际业务建模或存量迁移。

## 接入 Agent 或应用

服务器提供统一的远程 MCP 入口，内部装配调查、语义管理、治理、发布、查询、执行与结果读取。业务项目将自己的业务 Agent 连接到该服务，由 Agent 使用这些能力建设并消费语义资产。服务不运行语言模型；Agent 对话上下文由业务端持有，Kotlin 声明及对象由服务中的隔离 REPL 会话承接。下表链接是同一产品各能力的合同说明。

| 能力 | 提供什么 | 说明 |
| --- | --- | --- |
| 服务器产品 | 远程 MCP、外部认证、资源授权、权威元数据、在线发布消费和实际使用记录 | [server](skills/agentic-olap/references/server.md) |
| 语义管理 | 调查授权源，管理定义、采用、版本与发布 | [语义建设](docs/agent-usage.md) |
| 查询与结果 | 发现公开语义，校验完整查询，显式保存复用版本，解码与分页读取结果 | [查询与结果](docs/agent-usage.md) |
| 执行 | 在授权发布与数据源范围内执行完整查询或精确保存版本 | [服务调用](skills/agentic-olap/references/server.md) |
| 候选值 | 通过服务按批准字段只读提供实际候选页 | [Agent 使用指引](docs/agent-usage.md) |
| 运行机制 | 有界进程通道、取消、资源回收与部署权限 | [运行机制](docs/architecture.md#服务与运行机制) |

Agent 用 `describe-script-api` 发现实际 Kotlin 方法、构造器、属性、封闭变体及枚举，再通过 `execute-script` 提交完整 kts 程序。脚本固定提供 `olap` 和 `parameters`；可以用普通 Kotlin 分支、循环和局部函数读取真实回执后继续构造输入。构造与执行分离，正式调用仍通过 Go 按原主体和实际资源授权。

Query 使用正式 `QueryIntent(publication, computation)`，计算由根、独立输入和有序阶段构成。DSL 的 `scope/query/input/stage` 构造已有语义模型；根可使用测量、成员、输入、集合或共同计算，输入自身也可以是完整计算。定义内部与本次查询共同保留粒度、数量、单位、NULL 和来源含义。

共同计算分别声明成员、可调用测量和完成关系输入。完成关系按真实列、数量合同和观察键传入，可继续交给其他定义使用；不把金额或计数伪装为成员属性。调用中的来源条件沿显式参数传给指定事实，仍受本次发布的过滤权限约束，不自动向所有子计算继承。

脚本通过 `olap.queries.execute` 选择完整查询或精确保存引用，无需先保存或先校验。保存查询的正式回执含正文与精确 `reference`，后续执行使用该引用；保存结果则显式选择 `saveResult=true`，按实际返回的 `resultReference` 分页读取。小结果也可直接返回。完整程序见[AI 使用指引](docs/agent-usage.md)，外部客户端见[接入示例](examples/agent-client/README.md)。

部署方提供实际连接器、来源参数、凭据、可访问目录和资源预算。业务工具只取得明确授权的能力；查询客户端不持有数据库口令。可选技术诊断由日志与部署配置负责，显式业务写入和结果保存保留真实失败语义。

## 架构与目录

```text
project/
  service/              Go 服务、MCP、HTTP、认证、运行管理与结果交付
  protocol/             Go 与 Kotlin 共用的 Protobuf 内部协议
  modules/
    kernel/             通用语义、计算与来源访问合同、Calcite 编译
    upstream/           业务组织、语义治理、采用、发布与授权
    applications/       Kotlin 元数据应用、工作操作与插件装载
    scripting/          类型化 DSL、整段 kts 编译与求值
    protocol/           纯 Protobuf 生成物与显式领域转换
    connectors/         独立版本的具体后端插件
    integration-tests/  跨模块与真实进程试验
  gradle/               JVM 版本目录与 Gradle Wrapper
  cli/                  npm 产品入口与实际安装试验
skills/                 Agent 产品使用 skill
examples/               远程业务示例、外部客户端与内核工程试验
docs/                   架构、使用说明与发行文档
tools/                  构建、分发与开发入口
```

通用内核不依赖主题域、场景或 Agent 工作流；产品上游把业务空间中的采用与授权映射为内核可消费的定义、实例和绑定。这是产品内部的依赖分层。业务接入使用完整服务；内核的直接调用示例用于研发时检查这一依赖边界，见[内核工程试验](examples/kernel/README.md)。

Kotlin 模块由 `project/settings.gradle.kts` 统一登记，Go 服务由 `project/service/go.mod` 管理依赖。`project/protocol/` 的正式 schema 分别拥有运行监督、语义、治理、查询与 worker 消息，生成物与领域模型在编码边界显式转换；纯合同不依赖通信实现。服务发行包含 Go 程序、JVM 应用及其精确依赖和运行时；示例、研发脚本与使用者业务资产各自独立。依赖方向和调用边界见[架构说明](docs/architecture.md)。

## 支持范围

| 项目 | 当前实现 |
| --- | --- |
| 业务执行 | 随源码提供 DuckDB、H2 插件，具体能力由插件声明；其他后端通过公开连接器 API 独立接入 |
| 治理存储 | H2，具有明确的存储格式、事务和版本规则 |
| 产品入口 | 统一远程 MCP 服务，提供调查、语义资产管理、查询、执行和结果读取 |
| Agent 接入 | 外部客户端通过官方 MCP Streamable HTTP 消费服务；客户端代码与依赖保持独立 |
| 使用形态 | 单实例服务器；外部业务 Agent 通过 MCP 建设并使用语义资产，业务项目承载 Agent 与交互 |
| 产品发行 | `@hanger-source/agentic-olap` 与同标签 GitHub skill；连接器 SDK、内部协议及语义资产各自声明版本 |

实际源条件、类型、单位、历史规则与权限决定一份查询是否成立。业务事实不足时需要补充业务信息；当前后端或语言未支持的能力明确报告给调用者。

其他数据库由独立连接器提供，需要在实际后端核验类型、算术、函数与来源保证。连接器取得与配置见[服务器部署](skills/agentic-olap/references/server.md#取得连接器并绑定数据源)。

## 开发与维护

研发环境按[贡献说明](CONTRIBUTING.md#环境与唯一入口)准备，随后运行同一开发入口：

```sh
go -C tools run ./cmd/olap-dev bootstrap
go -C tools run ./cmd/olap-dev build
go -C tools run ./cmd/olap-dev server-distribution --artifacts .tmp/dev-distribution
go -C tools run ./cmd/olap-dev test --distribution .tmp/dev-distribution
```

Go 研发 CLI 统一编排 Gradle、协议生成、服务构建、发行和正式 MCP 业务试验，行为试验消费同轮构件。生产和必需研发链路均不要求 Python。环境、聚焦方法、示例运行与发行命令见[贡献说明](CONTRIBUTING.md)。

日常产品操作管理语义资产与查询。修改语言、Go/Kotlin 源码、构建或发行软件属于独立研发工作。正式业务示例使用自含数据，由 Go 研发 CLI 启动，通过公开 MCP 接口调用同轮服务。

## 文档与许可证

- [文档导航](docs/README.md)：按使用、部署、开发和研究查找说明。
- [AI 使用指引](docs/agent-usage.md)：通过 MCP 发现实际合同，建设语义资产或组织完整查询。
- [架构](docs/architecture.md)：内核、上游、应用及计算语言之间的责任。
- [业务用法](examples/README.md)：订单、采购、订阅的远程服务示例，以及单独列出的研发试验。
- [版本与变更](CHANGELOG.md)：构件版本、合同格式与变化。

项目采用 [Apache License 2.0](LICENSE)。第三方许可证与 NOTICE 随实际构件分发，说明见[贡献文档](CONTRIBUTING.md)。
