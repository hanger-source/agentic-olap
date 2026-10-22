# Agentic OLAP

安装后直接启动的语义 OLAP 服务，提供调查、可组合定义管理、发布、完整 Query、确定性执行、结果与使用记录读取。所需运行时随平台包交付，使用者无需下载或编译源码。

安装并启动完整服务：

```sh
npm install --global @hanger-source/agentic-olap
agentic-olap start
```

安装包含 macOS arm64 平台运行包，需要 Node.js 20 或更高。

同版本的连接器 SDK、H2 与 DuckDB 插件组也随 npm 安装，无需另外下载、找链接或手工解压：

```sh
agentic-olap sdk
agentic-olap sdk --path
agentic-olap connectors
```

`sdk` 返回已安装 SDK 的版本、连接器 API、文档和本地 Maven 仓库路径；`--path` 只输出该仓库路径，供 Gradle 构建直接消费。SDK 对应本地安装版本，连接远程服务时须核对其实际 API。

`connectors` 返回 npm 已安装的 H2、DuckDB 插件组的 `manifestPath`、`connectorRef` 和 API，可直接用于服务配置。上述命令只读取本地文件，不下载、复制或安装任何内容，也不创建数据源。具体后端的业务来源及凭据仍由使用者配置。

默认服务只监听本机，首次启动创建 `~/.agentic-olap/server.json`、独立访问令牌和持久数据目录。服务前台运行；退出终端进程将关闭其所属服务和工作进程，重新启动继续读取原资产。

```sh
agentic-olap info
agentic-olap tools
agentic-olap call list-data-sources --payload '{}'
agentic-olap call discover-governance-commands --payload '{}'
```

`info` 给出实际地址、配置及令牌文件位置、本地安装版本与运行目录，并通过 MCP 读取运行中服务版本、获准连接器和来源，不打印令牌。服务不可达或版本不同以非零退出并说明原因；无权读取的项目明确标记。来源列表只证明该主体可见的部署配置，报告的 `sourceQuery=NOT_CHECKED` 表示没有执行业务查询。`token` 显式读取令牌供自己的 MCP 客户端注入。外部 Agent 可直接连接该服务的 `/mcp`，也可通过 `tools/call` 操作同一真实 MCP 服务。

业务连接器由使用者独立提供。在 `info` 指出的配置文件中登记插件 manifest、来源身份、插件参数和凭据引用。来源支持 HTTP 等访问实现，结构可由调查取得，也可通过 MCP 显式登记；catalog 权限不是必备条件。同一来源 ID 可以部署多个不同绑定；从 `list-data-sources` 选择并原样使用其 `sourceIdentityHash`，不按同名来源自动切换。业务定义由 Agent 使用 MCP 建设和复用，服务不启动模型。

源码构建的连接器 API 为 `agentic-olap-connector/v11-kotlin-native-collection-relations-calcite-1.42`，元数据存储格式为 `15`。使用同轮插件及 SDK；安装发行物时遵循该发行声明的接口版本。既有持久根不会被自动改写。

需要自己的工作目录时，相关命令使用同一 `--home /absolute/path`。已有远程服务的客户端使用 `--url https://your-service/mcp --token-env OLAP_TOKEN`。服务器远程访问使用显式 `start --config /absolute/server.json` 和外部 JWT 认证配置；默认本机令牌模式只允许回环监听。

升级使用同一正式入口：停止当前前台服务，执行 `npm install --global @hanger-source/agentic-olap@目标版本`，再用原 `--home` 或 `--config` 启动。本地服务启动后用 `agentic-olap info` 核对实际运行版本；远程服务通过 `tools` 返回的 `serverInfo.version` 核对。npm 安装不会自动重启已有进程，也不会改写业务配置或转换持久资产格式。

SDK 和内置插件随 npm 一起替换。升级前核对目标发行的存储格式与连接器 API；精确插件引用发生变化时，需要明确配置对应插件并修订受影响资产，服务不会自动切换旧发布的引用。外部业务插件由使用方提供。所选发布、保存查询和历史结果可以通过 `read-deployment-availability` 核对读取或编译前提，再执行明确选择的业务查询确认；读取可用性本身不连接业务数据库。

Agent 使用指引通过 GitHub 安装：

```sh
npx skills add hanger-source/agentic-olap-skill --skill agentic-olap
```

skill 入口为 `skills/agentic-olap/SKILL.md`，连接说明为 `skills/agentic-olap/references/connections.md`，完整配置、认证授权说明与模板为 `skills/agentic-olap/references/server.md` 和同目录的 `server.example.json`。这些材料不依赖源码仓库或内部服务装配，使用指引通过 [GitHub](https://github.com/hanger-source/agentic-olap-skill) 分发。
