# Agentic OLAP 文档

[项目首页](../README.md)介绍用途、表达能力、快速开始和支持范围。本页按实际工作连接说明；[架构](architecture.md)描述责任与依赖，各构件 README 拥有精确接口、格式、部署参数和失败方式。

## 开始使用

按[安装与启动](../skills/agentic-olap/references/server.md#安装与启动)取得与本仓合同匹配的构件。安装后直接启动，无需下载或编译源码：

```sh
agentic-olap start
```

首次启动自动创建本地配置、随机访问令牌与持久数据目录。另开终端使用 `agentic-olap info`、`agentic-olap tools` 和 `agentic-olap call TOOL --payload JSON` 操作同一真实 MCP 服务。外部 Agent 使用随同轮构件交付的 [skill](../skills/agentic-olap/SKILL.md)。

| 要做什么 | 从这里进入 |
| --- | --- |
| 安装、启动并接入自己的业务源 | [服务使用与部署](../skills/agentic-olap/references/server.md) |
| 让 AI 使用服务中的定义与查询 | [AI 使用指引](agent-usage.md)，从服务发现工具、口径和完整请求合同 |
| 扩展 HTTP 来源或显式提供结构 | [来源访问扩展](source-access.md)，说明源码 API 与发行版本边界 |
| 配置外部 JWT 远程服务 | [远程部署](../skills/agentic-olap/references/server.md#外部认证的远程服务) |
| 研发构建并查看自含业务结果 | [贡献说明](../CONTRIBUTING.md)、[自含业务用法](../examples/README.md) |
| 调查来源并管理语义、指标与发布 | [Agent 使用指引](agent-usage.md)、[来源接入](source-access.md) |
| 读取语义并组织完整 Query DSL | [查询与定义使用](agent-usage.md) |
| 执行完整查询，直接或按引用消费结果 | [查询与结果读取](agent-usage.md#读取结果与实际使用记录) |
| 查看实际候选值 | [Agent 使用指引](agent-usage.md)，从服务读取准确候选合同 |

业务项目接入完整服务，由自己的业务 Agent 通过 MCP 调查来源、理解和澄清业务、建设与复用定义、组织查询及读取结果。已有发布可以直接用于查询；需要新增或沉淀业务含义时，再通过语义管理建设、组合和发布定义。Query 可以内联计算、组合输入和计算阶段；保存版本用于明确复用。以下能力文档描述同一产品的合同与实现分工。

## 部署与调用

| 服务器部署与日常使用 | 正式说明 |
| --- | --- |
| 安装并启动统一服务 | [服务使用与部署](../skills/agentic-olap/references/server.md) |
| 取得插件组、绑定业务数据库与授权 | [连接器及数据源配置](../skills/agentic-olap/references/server.md#取得连接器并绑定数据源) |
| 从本地或远程 MCP 服务建设和查询 | [AI 使用指引](agent-usage.md)；产品 CLI 已提供工具发现与调用 |
| 实现并发行独立后端插件 | [连接器 API](../project/modules/kernel/connector-api/README.md) |

平台运行包交付服务及内部运行时；使用者配置实际连接、凭据、权限和预算。业务 Agent 从统一服务发现能力与已有资产，结合业务背景选择定义和组织完整查询；新增业务口径通过服务中的语义管理完成。

## 理解与开发

- [架构](architecture.md)：产品内部的通用内核、上游治理、运行应用、计算语言和调用链。
- [贡献说明](../CONTRIBUTING.md)：开发环境、固定依赖、构建、检查与提交。
- [安装与发布](installation-release.md)：npm 安装、JitPack SDK、精确源码与公开发行的完整链路。
- [工程与文档约定](../STYLE_GUIDE.md)：规则归属与文档表达。
- [版本与变更](../CHANGELOG.md)：源码、构件、协议和运行中的资产身份。
- [内核工程试验](../examples/kernel/README.md)：直接构造内核输入，检查真实计算与失败边界。
- [外部 Agent 接入](../examples/agent-client/README.md)：MCP 客户端连接、令牌配置与官方 Go SDK 调用参照。

内部实现见[运行机制](architecture.md#服务与运行机制)：Go 服务拥有外部入口与进程监督，Kotlin 应用拥有语义、治理、授权与权威元数据；两端使用 Protobuf 通信。服务部署和业务接入始终使用上面的统一产品入口。

Kotlin 模块由 `project/settings.gradle.kts` 组织，Go 服务使用 `project/service/go.mod`，内部协议由 `project/protocol/runtime.proto` 生成。独立 `tools` Go module 的 `olap-dev` 统一编排研发构建与试验；产品运行不调用源码构建或修改工具。


正式业务示例及研发试验使用仓库自含数据，由[研发命令](../tools/README.md)统一启动，消费同轮服务与公开 MCP 接口。
