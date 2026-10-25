# Agentic OLAP

安装后直接启动的语义 OLAP 服务，提供调查、可组合定义管理、发布、完整 Query、确定性执行、结果与使用记录读取。npm 安装阶段备齐服务、脚本编译运行和插件所需文件，使用者无需下载或编译源码。

安装并启动完整服务：

```sh
npm install --global @hanger-source/agentic-olap
agentic-olap start
```

一个 npm 包交付 macOS arm64 产品，需要 Node.js 20 或更高。

JRE、自包含安装引导和命令入口随 npm 交付。`npm install` 用包内 JRE 运行引导，自动从 JitPack 取得本版 Kotlin 主服务、工作入口、REPL 运行构件和内置连接器，从 Maven Central 取得第三方 JVM 库与驱动。运行 JAR 按发行清单校验大小和摘要后写入安装目录；缓存中的相同字节可复用。需要允许本包的 postinstall 脚本，下载或校验失败使安装失败。安装完成后，命令入口直接启动包内 JRE，启动阶段不下载依赖。

安装后读取同版本 SDK 声明及 H2、DuckDB 插件组：

```sh
agentic-olap sdk
agentic-olap sdk --repository
agentic-olap connectors
```

`sdk` 返回本版连接器 API、随包文档、JitPack 仓库地址及正式构件坐标；`--repository` 只输出仓库地址。新建或确需重构的独立插件工程配置 `maven("https://jitpack.io")`，采用目标宿主披露的精确坐标；第三方依赖由 `mavenCentral()` 提供。SDK 构件公开下载，无需使用者配置令牌或从 npm 包提取本地 Maven 仓库。连接远程服务时须核对其实际声明，命令返回的是本地安装信息。

JVM 下载缓存默认位于 `~/.cache/agentic-olap/jvm`；设置 `AGENTIC_OLAP_CACHE=/absolute/cache` 时使用该目录。缓存按内容摘要保存，逐摘要文件锁协调并发安装；安装目录仍持有完整运行文件，启动不依赖缓存目录或 Maven 网络。下载使用 JRE 信任库及 `HTTP_PROXY/HTTPS_PROXY/NO_PROXY`，代理不携带凭据。

每个 Maven 请求具有明确超时。HTTP 错误、长度或摘要不符均使安装失败，不切换来源、版本或校验要求。重新执行 npm 安装可复用此前校验成功的缓存，不要求手工下载构件。

`connectors` 返回 npm 已安装的 H2、DuckDB 插件组的 `manifestPath`、`connectorRef` 和 API，可直接用于服务配置。上述命令只读取本地文件，不下载、复制或安装任何内容，也不创建数据源。具体后端的业务来源及凭据仍由使用者配置。

默认服务只监听本机，首次启动创建 `~/.agentic-olap/server.json`、独立访问令牌和持久数据目录。服务前台运行；退出终端进程将关闭其所属服务和工作进程，重新启动继续读取原资产。

```sh
agentic-olap info
agentic-olap tools
agentic-olap call list-data-sources --payload '{}'
agentic-olap call describe-script-api --payload '{}'
agentic-olap call execute-script --payload '{"executionId":"sources-1","waitMillis":1000,"source":"olap.output(olap.sources.list(EmptyRequest))","sourceName":"sources.olap.kts","dslApiVersion":"agentic-olap-kotlin-dsl/v8","parameters":{}}'
```

`execute-script` 首次可建立持续 Kotlin 会话，回执给出 `sessionId` 与 `executionId`。`waitMillis` 只控制本次等待：`RUNNING` 时用 `wait-script` 读取同一执行，不重复提交源码；后续片段明确传同一 `sessionId` 和新的 `executionId`。通过 `cancel-script` 取消执行，`read-script-session` 读取会话，`reset-script-session` 清空语言上下文，`close-script-session` 结束自己建立的会话。重置和关闭会停止当前执行，核对旧进程退出后才确认；服务重启不恢复语言对象，也不重放历史源码。

同一个执行身份和相同源码、参数及会话选择只执行一次；相同身份却改变输入会被拒绝。这个去重记录属于当前服务生命周期，不承诺跨服务重启去重。语言会话与持久定义、发布、查询结果分别管理。`info` 直接读取安装、服务及获准来源声明，不创建语言会话，也不执行来源查询。

显式 `olap.output` 与普通打印按发生顺序进入本次执行的 `outputs`；最后表达式不会被隐式输出。最终 JSON/MCP 消息过大时自动完整保存并返回 `read-response` 入口，原执行结局不变。

`info` 给出实际地址、配置及令牌文件位置、本地安装版本与运行目录，并通过 MCP 读取运行中服务版本、获准连接器和来源，不打印令牌。服务不可达或版本不同以非零退出并说明原因；无权读取的项目明确标记。来源列表只证明该主体可见的部署配置，报告的 `sourceQuery=NOT_CHECKED` 表示没有执行业务查询。`token` 显式读取令牌供自己的 MCP 客户端注入。外部 Agent 可直接连接该服务的 `/mcp`，也可通过 `tools/call` 操作同一真实 MCP 服务。

业务连接器由使用者独立提供。在 `info` 指出的配置文件中登记插件 manifest、来源身份、插件参数和凭据引用。来源支持 HTTP 等访问实现，结构可由调查取得，也可通过 MCP 显式登记；catalog 权限不是必备条件。同一来源 ID 可以部署多个不同绑定；从 `list-data-sources` 选择并原样使用其 `sourceIdentityHash`，不按同名来源自动切换。业务 Agent 经 MCP 发现正式 Kotlin API，并以完整 kts 程序调用 `olap.sources/semantics/governance/queries/results`。定义与 Query 由正式构造器产生，写入、执行与结果交付仍走统一服务。服务不启动模型。

源码构建的连接器 API 为 `agentic-olap-connector/v14-kotlin-mean-target-calcite-1.42`，元数据存储格式为 `19`。安装发行物时遵循目标宿主声明的接口和运行要求。既有持久根不会被自动改写。

需要独立持久根时，相关命令使用同一 `--home /absolute/path`。已有远程服务的客户端使用 `--url https://your-service/mcp --token-env OLAP_TOKEN`。服务器远程访问使用显式 `start --config /absolute/server.json` 和外部 JWT 认证配置；默认本机令牌模式只允许回环监听。

升级使用同一正式入口：先核对目标存储格式，再停止当前前台服务，执行 `npm install --global @hanger-source/agentic-olap@目标版本`。持久格式匹配时使用原 `--home` 或 `--config`；格式不匹配时为目标版本选择独立持久根，保留原根供匹配版本读取，不把旧根交给新格式运行时。本地服务启动后用 `agentic-olap info` 核对实际运行版本；远程服务通过 `tools` 返回的 `serverInfo.version` 核对。npm 安装不会自动重启已有进程，也不会改写业务配置或转换持久资产格式。

SDK 声明与内置插件随 npm 一起替换。独立插件不因产品版本或 SDK Maven 坐标变化就必须重构：连接器 API 相同，插件所用公共接口的二进制合同及 JVM、Kotlin、Calcite 要求满足目标宿主时，可保留原 manifest、JAR 和完整 `connectorRef`。宿主核验精确字节和 provider 身份，不比较插件构建所用的产品版本。API 字符串相同或装载成功不能替代实际使用能力的查询验证。原插件与来源身份参数未变时，来源绑定不变，无需为产品升级重登记目录或修订资产。

确实更换插件字节或来源身份时，显式配置新精确引用并为目标绑定取得结构快照 S。原物理数据合同 P 未变时，使用 `register-source-adoptions` 为原精确 P 登记新兑现采用，或用 `read-source-adoptions` 按精确 S 取得已有采用；Kotlin 将正式返回的 `references` 直接交 `build-release`。发布计算实际 P 闭包，不要求作者重抄逐表引用；缺失或多选明确拒绝。数据合同、业务含义或原生函数精确依赖改变时，再用正式定义、影响与差异读取组织相应修订。旧发布不自动切换，旧绑定未部署仅影响执行，不妨碍获授权的不可变元数据读取。外部业务插件由使用方提供，具体调用见[连接说明](../../skills/agentic-olap/references/connections.md#更换插件与显式采用)。所选发布、保存查询和历史结果可通过 `read-deployment-availability` 核对读取或编译前提；读取可用性本身不连接业务数据库。

Agent 使用指引通过 GitHub 安装：

```sh
npx skills add hanger-source/agentic-olap --skill agentic-olap
```

skill 入口为 `skills/agentic-olap/SKILL.md`，连接说明为 `skills/agentic-olap/references/connections.md`，完整配置、认证授权说明与模板为 `skills/agentic-olap/references/server.md` 和同目录的 `server.example.json`。使用指引与源码在[同一 GitHub 仓库](https://github.com/hanger-source/agentic-olap)交付，安装 skill 不要求构建产品源码。
