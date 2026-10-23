# Agentic OLAP

安装后直接启动的语义 OLAP 服务，提供调查、可组合定义管理、发布、完整 Query、确定性执行、结果与使用记录读取。npm 安装阶段备齐服务、脚本编译运行和插件所需文件，使用者无需下载或编译源码。

安装并启动完整服务：

```sh
npm install --global @hanger-source/agentic-olap
agentic-olap start
```

一个 npm 包交付 macOS arm64 产品，需要 Node.js 20 或更高。

Go 程序和 JRE 随 npm 交付，命令直接运行 Go 程序。`npm install` 自动从 JitPack 取得本版自有 Kotlin 运行构件和内置连接器，从 Maven Central 取得第三方 JVM 库与驱动。所有产品和依赖 JAR 都在安装阶段下载，按发行清单校验大小和摘要后写入安装目录；缓存中的相同字节可复用。需要允许本包的 postinstall 脚本，下载或校验失败使安装失败。安装完成后直接启动，启动阶段不下载依赖。

安装后读取同版本 SDK 声明及 H2、DuckDB 插件组：

```sh
agentic-olap sdk
agentic-olap sdk --repository
agentic-olap connectors
```

`sdk` 返回本版连接器 API、随包文档、JitPack 仓库地址及正式构件坐标；`--repository` 只输出仓库地址。独立插件工程配置 `maven("https://jitpack.io")`，引用返回的精确坐标；第三方依赖由 `mavenCentral()` 提供。SDK 构件公开下载，无需使用者配置令牌或从 npm 包提取本地 Maven 仓库。连接远程服务时须核对其实际 API。

JVM 下载缓存默认位于系统用户缓存目录的 `agentic-olap/jvm/sha256`；设置 `AGENTIC_OLAP_CACHE=/absolute/cache` 时使用 `/absolute/cache/jvm/sha256`。缓存按内容摘要保存；安装目录仍持有完整运行文件，启动不依赖缓存目录或 Maven 网络。下载使用系统证书及 `HTTP_PROXY/HTTPS_PROXY/NO_PROXY`。

仓库返回 HTTP 429 时，安装器按 `Retry-After` 有限重试同一精确构件；未提供该字段时使用递增间隔。整个安装下载预算为 30 分钟。其他 HTTP 错误、长度或摘要不符均使安装失败，不切换来源、版本或校验要求。

`connectors` 返回 npm 已安装的 H2、DuckDB 插件组的 `manifestPath`、`connectorRef` 和 API，可直接用于服务配置。上述命令只读取本地文件，不下载、复制或安装任何内容，也不创建数据源。具体后端的业务来源及凭据仍由使用者配置。

默认服务只监听本机，首次启动创建 `~/.agentic-olap/server.json`、独立访问令牌和持久数据目录。服务前台运行；退出终端进程将关闭其所属服务和工作进程，重新启动继续读取原资产。

```sh
agentic-olap info
agentic-olap tools
agentic-olap call list-data-sources --payload '{}'
agentic-olap call describe-script-api --payload '{}'
agentic-olap call execute-script --payload '{"executionId":"sources-1","waitMillis":1000,"source":"olap.output(olap.sources.list(EmptyRequest))","sourceName":"sources.olap.kts","dslApiVersion":"agentic-olap-kotlin-dsl/v2","parameters":{}}'
```

`execute-script` 首次可建立持续 Kotlin 会话，回执给出 `sessionId` 与 `executionId`。`waitMillis` 只控制本次等待：`RUNNING` 时用 `wait-script` 读取同一执行，不重复提交源码；后续片段明确传同一 `sessionId` 和新的 `executionId`。通过 `cancel-script` 取消执行，`read-script-session` 读取会话，`reset-script-session` 清空语言上下文，`close-script-session` 结束自己建立的会话。重置和关闭会停止当前执行，核对旧进程退出后才确认；服务重启不恢复语言对象，也不重放历史源码。

同一个执行身份和相同源码、参数及会话选择只执行一次；相同身份却改变输入会被拒绝。这个去重记录属于当前服务生命周期，不承诺跨服务重启去重。语言会话与持久定义、发布、查询结果分别管理。`info` 的连接器读取也使用这条链，并关闭自己创建的会话。

显式 `olap.output` 与普通打印按发生顺序进入本次执行的 `outputs`；最后表达式不会被隐式输出。最终 JSON/MCP 消息过大时自动完整保存并返回 `read-response` 入口，原执行结局不变。

`info` 给出实际地址、配置及令牌文件位置、本地安装版本与运行目录，并通过 MCP 读取运行中服务版本、获准连接器和来源，不打印令牌。服务不可达或版本不同以非零退出并说明原因；无权读取的项目明确标记。来源列表只证明该主体可见的部署配置，报告的 `sourceQuery=NOT_CHECKED` 表示没有执行业务查询。`token` 显式读取令牌供自己的 MCP 客户端注入。外部 Agent 可直接连接该服务的 `/mcp`，也可通过 `tools/call` 操作同一真实 MCP 服务。

业务连接器由使用者独立提供。在 `info` 指出的配置文件中登记插件 manifest、来源身份、插件参数和凭据引用。来源支持 HTTP 等访问实现，结构可由调查取得，也可通过 MCP 显式登记；catalog 权限不是必备条件。同一来源 ID 可以部署多个不同绑定；从 `list-data-sources` 选择并原样使用其 `sourceIdentityHash`，不按同名来源自动切换。业务 Agent 经 MCP 发现正式 Kotlin API，并以完整 kts 程序调用 `olap.sources/semantics/governance/queries/results`。定义与 Query 由正式构造器产生，写入、执行与结果交付仍走统一服务。服务不启动模型。

源码构建的连接器 API 为 `agentic-olap-connector/v12-kotlin-native-collection-placement-calcite-1.42`，元数据存储格式为 `16`。使用同轮插件及 SDK；安装发行物时遵循该发行声明的接口版本。既有持久根不会被自动改写。

需要独立持久根时，相关命令使用同一 `--home /absolute/path`。已有远程服务的客户端使用 `--url https://your-service/mcp --token-env OLAP_TOKEN`。服务器远程访问使用显式 `start --config /absolute/server.json` 和外部 JWT 认证配置；默认本机令牌模式只允许回环监听。

升级使用同一正式入口：停止当前前台服务，执行 `npm install --global @hanger-source/agentic-olap@目标版本`，再用原 `--home` 或 `--config` 启动。本地服务启动后用 `agentic-olap info` 核对实际运行版本；远程服务通过 `tools` 返回的 `serverInfo.version` 核对。npm 安装不会自动重启已有进程，也不会改写业务配置或转换持久资产格式。

SDK 声明与内置插件随 npm 一起替换，独立插件工程明确采用新的 SDK Maven 版本。升级前核对目标发行的存储格式与连接器 API；精确插件引用发生变化时，需要明确配置对应插件并修订受影响资产，服务不会自动切换旧发布的引用。外部业务插件由使用方提供。所选发布、保存查询和历史结果可以通过 `read-deployment-availability` 核对读取或编译前提，再执行明确选择的业务查询确认；读取可用性本身不连接业务数据库。

Agent 使用指引通过 GitHub 安装：

```sh
npx skills add hanger-source/agentic-olap --skill agentic-olap
```

skill 入口为 `skills/agentic-olap/SKILL.md`，连接说明为 `skills/agentic-olap/references/connections.md`，完整配置、认证授权说明与模板为 `skills/agentic-olap/references/server.md` 和同目录的 `server.example.json`。使用指引与源码在[同一 GitHub 仓库](https://github.com/hanger-source/agentic-olap)交付，安装 skill 不要求构建产品源码。
