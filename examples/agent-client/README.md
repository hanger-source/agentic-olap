# 外部 Agent 接入

Agentic OLAP 不启动或绑定某个模型。外部 Agent 通过统一 Streamable HTTP MCP 服务调查来源、建设和复用语义定义、组织完整 Query 并读取结果。

安装产品并启动服务：

```sh
npm install --global @hanger-source/agentic-olap
agentic-olap start
```

在另一个终端取得连接信息与可见工具：

```sh
agentic-olap info
agentic-olap tools
agentic-olap call describe-script-api --payload '{}'
agentic-olap call execute-script --payload '{"source":"olap.output(olap.sources.list(EmptyRequest))","sourceName":"sources.olap.kts","dslApiVersion":"agentic-olap-kotlin-dsl/v1","parameters":{}}'
```

在自己的 Agent 客户端中登记 `info` 返回的 MCP URL，并配置 `Authorization: Bearer <访问令牌>`。本地令牌由 `agentic-olap token` 显式取得；令牌应由客户端的凭据或环境配置注入。远程服务使用部署方提供的真实 URL 与授权令牌。模型账号、会话上下文、业务界面和客户端生命周期由使用方管理。

连接后按[AI 使用指引](../../docs/agent-usage.md)发现实际 Kotlin 方法、构造器、属性与精确版本，再提交整段 kts 程序调查业务、检索和复用定义、组织查询。CLI 的 `--payload` 是工具参数对象；直接 MCP 调用使用 `arguments={"payload":{...}}`。脚本中固定提供 `olap` 与 `parameters`，模型账号和对话仍留在外部客户端。已有适用定义时直接使用，需要沉淀新的业务含义时再明确建设与发布。接入、来源与授权配置见[服务使用说明](../../skills/agentic-olap/references/server.md)，可安装的完整使用指引从[本仓库 skill](https://github.com/hanger-source/agentic-olap/tree/main/skills/agentic-olap)取得。

产品 CLI 的 `tools` 和 `call` 已使用官方 Go MCP SDK，可直接连接远程服务。客户端只负责 MCP 连接、认证和提交源码及普通参数，业务构造使用服务提供的正式类型，不在客户端复制语义编译或结果规则。需要定制客户端的研发者，可参考 [Go 远程调用](../../tools/internal/scenario/remote.go)和[正式业务用法](../README.md)。示例作者预先给出的定义与预期只证明实际产品行为，不代表 Agent 自主完成业务理解。
