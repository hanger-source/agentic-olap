# Agentic OLAP 用法

这里使用自含业务数据与正式远程 MCP 服务，展示语义调查、定义建设与组合、发布、完整 Query、执行和结果消费。业务定义与查询由示例作者给出，这些运行不代表 Agent 自主完成业务理解与建模。接入自己的业务从[安装与配置](../skills/agentic-olap/references/server.md)开始，业务 Agent 的使用方式见[AI 使用指引](../docs/agent-usage.md)和[客户端示例](agent-client/README.md)。

| 用法 | 直接展示的能力 |
| --- | --- |
| [订单与退款](orders/README.md) | 多事实净额、资格集合、固定同群、Query 内联两期组合与真实权重 |
| [采购与收货](procurement/README.md) | 重复投递、取消与版本形成、不同筛选顺序和独立事实对齐 |
| [订阅与历史归属](subscriptions/README.md) | 来源切换、单位转换、历史归属、完整日历日均、对象身份窗口与账款目的地分摊 |

## 构建与运行

在仓库根准备 JDK 21 与 Go 1.26：

```sh
go -C tools run ./cmd/olap-dev bootstrap
go -C tools run ./cmd/olap-dev build
go -C tools run ./cmd/olap-dev server-distribution --artifacts .tmp/server-distribution
go -C tools run ./cmd/olap-dev example --example orders --artifacts .tmp/orders --distribution .tmp/server-distribution
go -C tools run ./cmd/olap-dev example --example procurement --artifacts .tmp/procurement --distribution .tmp/server-distribution
go -C tools run ./cmd/olap-dev example --example subscriptions --artifacts .tmp/subscriptions --distribution .tmp/server-distribution
```

每个产物目录必须尚不存在。三例按顺序运行，共用精确发行构件，各自持有业务数据、元数据与结果。业务值见`.tmp/<example>/http/business/observations.json`，同目录`queries.json`保留完整查询，`mcp-calls.jsonl`保留实际公开调用；原始记录与独立预期见各自说明。

三个业务客户端只持有自己的作者输入、远程地址与令牌，通过正式 MCP 消费服务，不读取服务器元数据或私有执行文件。

## 用法中的调用关系

Kotlin 准备者只建立原始业务数据和类型化定义模板，没有治理或发布副作用。`tools/internal/scenario` 中的 Go 客户端通过官方 MCP SDK 完成源调查、定义与采用建设、精确发布、完整 Query 校验和执行，按返回引用分页读取结果与历史。查询作者与独立业务预期也在该研发包中，不进入产品运行依赖。

普通临时查询不创建保存定义或任务。订单内联查询由调用者组织`COMPUTE`，其他查询复用沉淀后的高层定义；客户端另行明确调用保存与精确复用。开发场景持有自己的真实签名者、服务进程和故障现场，观察外部认证、重启、结果存储失败、行/字节预算和只读源边界。这些研发职责不进入产品业务工具。

这些用法展示产品能力与计算语义，不是固定 Agent 工作流。外部业务压力和模型自主使用分别承担自己的输入、运行和解释责任。架构见[说明](../docs/architecture.md)，完整研发方法见[贡献文档](../CONTRIBUTING.md)。

## 内核工程试验

[内核工程试验](kernel/README.md)用于研发时直接构造中性目录、绑定和计算请求，观察实际计算、错误引用及来源结构变化的失败边界。其运行命令、构件身份和结果位置见该试验说明。
