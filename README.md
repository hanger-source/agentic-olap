# Agentic OLAP

安装后直接启动的语义 OLAP 服务，提供调查、可组合定义管理、发布、完整 Query、确定性执行、结果与使用记录读取。所需运行时随平台包交付，使用者无需下载或编译源码。

安装并启动完整服务：

```sh
npm install --global @hanger-source/agentic-olap
agentic-olap start
```

安装自动选择匹配的平台运行包。支持 macOS arm64、Linux x64（glibc 2.35 或更高），需要 Node.js 20 或更高。

默认服务只监听本机，首次启动创建 `~/.agentic-olap/server.json`、独立访问令牌和持久数据目录。服务前台运行；退出终端进程将关闭其所属服务和工作进程，重新启动继续读取原资产。

```sh
agentic-olap info
agentic-olap tools
agentic-olap call list-data-sources --payload '{}'
agentic-olap call discover-governance-commands --payload '{}'
```

`info` 给出实际地址、配置及令牌文件位置，不打印令牌。`token` 显式读取令牌供自己的 MCP 客户端注入。外部 Agent 可直接连接该服务的 `/mcp`，也可通过 `tools/call` 操作同一真实 MCP 服务。

业务连接器由使用者独立提供。在 `info` 指出的配置文件中登记插件 manifest、来源身份、插件参数和凭据引用。来源支持 HTTP 等访问实现，结构可由调查取得，也可通过 MCP 显式登记；catalog 权限不是必备条件。同一来源 ID 可以部署多个不同绑定；从 `list-data-sources` 选择并原样使用其 `sourceIdentityHash`，不按同名来源自动切换。业务定义由 Agent 使用 MCP 建设和复用，服务不启动模型。

源码构建的连接器 API 为 `agentic-olap-connector/v9-kotlin-source-sql-calcite-1.42`，元数据存储格式为 `14`。使用同轮插件及 SDK；安装发行物时遵循该发行声明的接口版本。既有持久根不会被自动改写。

需要自己的工作目录时，相关命令使用同一 `--home /absolute/path`。已有远程服务的客户端使用 `--url https://your-service/mcp --token-env OLAP_TOKEN`。服务器远程访问使用显式 `start --config /absolute/server.json` 和外部 JWT 认证配置；默认本机令牌模式只允许回环监听。

Agent 使用指引通过 GitHub 安装：

```sh
npx skills add hanger-source/agentic-olap-skill --skill agentic-olap
```

skill 入口为 `skills/agentic-olap/SKILL.md`，连接说明为 `skills/agentic-olap/references/connections.md`，完整配置、认证授权说明与模板为 `skills/agentic-olap/references/server.md` 和同目录的 `server.example.json`。这些材料不依赖源码仓库或内部服务装配。使用说明、匹配的插件组和 SDK 见 [GitHub](https://github.com/hanger-source/agentic-olap-skill)。
