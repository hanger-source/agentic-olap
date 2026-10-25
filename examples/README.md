# Agentic OLAP 用法

这里使用自含业务数据与正式远程 MCP 服务，展示语义调查、定义建设与组合、发布、完整 Query、执行和结果消费。业务定义与查询由示例作者给出，这些运行不代表 Agent 自主完成业务理解与建模。接入自己的业务从[安装与配置](../skills/agentic-olap/references/server.md)开始，业务 Agent 的使用方式见[AI 使用指引](../docs/agent-usage.md)和[客户端示例](agent-client/README.md)。

| 用法 | 直接展示的能力 |
| --- | --- |
| [订单与退款](orders/README.md) | 多事实净额、资格集合、固定同群、Query 内联两期组合与真实权重 |
| [采购与收货](procurement/README.md) | 重复投递、取消与版本形成、不同筛选顺序和独立事实对齐 |
| [订阅与历史归属](subscriptions/README.md) | 来源切换、单位转换、历史归属、完整日历日均、对象身份窗口与账款目的地分摊 |
| [对象人口与完成关系](populations/README.md) | 对象交并差、独立测量、缺组与 NULL、保存算法多次调用、独立分母及变化数据下的历史结果 |

## 构建与运行

在仓库根准备 JDK 21：

```sh
./project/gradlew -p project :development:orders
./project/gradlew -p project :development:procurement
./project/gradlew -p project :development:subscriptions
./project/gradlew -p project :development:populations
```

任务明确消费同轮 `project/build/distribution`。每个产物目录必须尚不存在；默认 `.tmp/<example>`，可用 `-Partifacts=/absolute/path` 指定。各例独立持有业务数据、元数据与结果。`http/report.json` 保存各阶段正式脚本回执，包括保存查询和结果的精确引用；`mcp-calls.jsonl` 保存实际 MCP 请求与响应。完整定义、Query 和独立业务预期见各例的 `main.olap.kts`；原始记录见各自说明。

业务客户端只持有自己的作者输入、远程地址与令牌，通过正式 MCP 消费服务，不读取服务器元数据或私有执行文件。

## 用法中的调用关系

Kotlin 准备者建立原始业务数据；示例作者在 kts 中构造正式定义和 Query。`tools/src/main/kotlin` 的独立客户端通过 MCP 提交整段程序，脚本中的正式能力依次完成调查、定义与采用建设、发布、查询和结果读取，主服务负责实际保存与交付。业务数据、作者程序及独立结果预期留在示例和研发包，不进入产品运行依赖。

普通临时查询不创建保存定义或任务。订单内联查询由调用者组织`COMPUTE`，其他查询复用沉淀后的高层定义；客户端另行明确调用保存与精确复用。开发场景持有自己的真实签名者、HTTPS JWKS 和服务进程，观察外部认证、重启、无来源回读结果及只读源边界。订单用法观察外部新增数据后重执旧查询的变化；对象人口用法在来源删除对象后分别重执保存查询与回读原保存结果，区分当前数据与历史制品。这些研发职责不进入产品业务工具。

对象人口用法将真实采用中出现的组合问题转成公开的合成输入，通过完整服务分别执行人口选择、测量、定义复用和结果交付。其数据、单位及独立预期由本例明确提供；成功不代表私有业务、其他后端或任意规模已通过。

这些用法展示产品能力与计算语义，不是固定 Agent 工作流。外部业务压力和模型自主使用分别承担自己的输入、运行和解释责任。架构见[说明](../docs/architecture.md)，完整研发方法见[贡献文档](../CONTRIBUTING.md)。

## 内核工程试验

[内核工程试验](kernel/README.md)用于研发时直接构造中性目录、绑定和计算请求，观察实际计算、错误引用及来源结构变化的失败边界。其运行命令、构件身份和结果位置见该试验说明。
