# 服务与运行监督

Go 服务提供 CLI、官方 MCP Streamable HTTP、认证、配置、运行准入、JVM 监督和结果字节交付。`cmd/agentic-olap-runtime` 是单个 npm 产品包内的实际程序，使用者通过 `agentic-olap` 启动完整服务。

服务从 `distribution.json` 读取精确构件，逐项核对路径与摘要。它启动唯一 Kotlin 元数据应用、有上限脚本 JVM 池及来源工作进程，持有期限、取消和关闭责任。脚本在同一 Subject＋Scopes 归属内复用 worker，每次提交独立执行状态与受管临时子目录。`olap.temporaryDirectory` 是本次子目录，`File(".")` 仍为稳定 worker 私有根，不伪装 chdir。

Agent 用 `describe-script-api` 发现实际 Kotlin API，经 `execute-script` 提交完整 kts 程序。每次正式能力回调仍进入 Go 原应用层，由原认证主体授权，结果继续由 Go 保存和交付。领域授权、治理、语义规划及数量校验由 Kotlin 承担，Go 不复制计算模型或业务规则。

`project/protocol/` 的正式 schema 声明运行、语义、治理、查询和 worker 消息，由构建入口生成两端代码。具体消息在边界显式转换为正式领域模型；不反射任意模型、不传 JSON AST，不经通用浮点对象中转精确数值。JSON 仅用于 MCP 提交封套、配置和明确的普通资料。

研发构建、发行与正式 MCP 场景位于独立的 `tools` Go module。生产服务不调用源码构建工具、不启动模型或管理模型账号。配置与产品使用见[服务说明](../../skills/agentic-olap/references/server.md)，进程和信任边界见[架构](../../docs/architecture.md#服务与运行机制)。
