# 服务与运行监督

Go 服务提供 CLI、官方 MCP Streamable HTTP、认证、配置、运行准入、JVM 监督和结果字节交付。`cmd/agentic-olap-runtime` 是平台包调用的实际程序，普通使用者从 npm 安装后的 `agentic-olap` 命令启动服务。

服务从 `distribution.json` 读取精确构件，逐项核对路径与摘要。它启动一个 Kotlin 元数据进程及有界工作进程，持有取消、期限和关闭责任。操作描述、领域授权、语义规划及业务结果合同由 Kotlin 提供，Go 不解释第二套 DSL。

内部协议的唯一来源是 `project/protocol/runtime.proto`，由 Gradle 同时生成 JVM 与 Go 通信代码。JSON 合同通过原字节传递，结果读取保留数值文字、NULL 和集合结构，不经通用浮点对象中转。

研发构建、发行与正式 MCP 场景位于独立的 `tools` Go module。生产服务不调用源码构建工具、不启动模型或管理模型账号。配置与产品使用见[服务说明](../../skills/agentic-olap/references/server.md)。
