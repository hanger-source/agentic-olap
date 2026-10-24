# Agentic OLAP · 内核工程试验

本工程用于研发时观察确定性内核的计算与失败边界。业务 Agent 通过[统一服务](../../skills/agentic-olap/references/server.md)调查来源、管理语义资产和查询；本页描述直接构造内核输入的工程试验。

Gradle 的 `:examples:kernel` 任务编译并运行 [Kernel.kt](Kernel.kt)。它通过公开连接器装载器取得精确 DuckDB 插件及驱动，构造中性事件、逻辑字段、物理绑定、成员来源、计算定义、实例和内核请求。定义通过 `MetricRef` 精确引用；`MetricInstance` 持有定义、绑定、固定实参和显示名。相同每记录金额计算通过测量槽位绑定金额 SUM（EUR）与记录 COUNT（ONE），两个实例固定不同 `factor`，查询只填写剩余 `offset`。

在仓库根执行：

```sh
./project/gradlew -p project :development:kernel
```

输出根 `.tmp/kernel` 必须不存在，可用 `-Partifacts=/absolute/path` 指定。Gradle 装配同轮 JVM 发行与真实插件，独立 Kotlin 研发客户端启动本工程。连接器配置使用绝对 manifest 路径与真实 JAR 摘要，内核不自行扫描部署目录。

Kotlin 入口明确接受连接器配置、尚不存在的 `run/` 目录、请求期限毫秒数与原生内存字节数。它持有本次 DuckDB 数据库、连接及执行资源。结果物化预算为 1000 行、20000 个值、2097152 字节和 16 层深度；这些是工程试验的部署条件。资源在调用内关闭，结果和失败现场保留，不自动覆盖或重试。

示例在自己的 `run/orders.duckdb` 创建四行订单，明确登记定义及其执行实例，以 `callId` 和 `instance:{id}` 发起调用，执行事实 SUM/COUNT 与独立 RECORDS 成员关系的多阶段组合，保留 NULL 和重复记录的含义。订单明细使用真实事件的 `MemberSource.Projection`，结果声明由 `MemberResult` 持有；成员调用明确给出 `MemberRef`、实参和成员参数，本例两个参数集合均为空。共享 wrapper 以客户为观察键将这两个测量实例对齐；各客户原金额 10.00／记录数 2 得到每记录金额 5.00，固定 factor=1／2 与查询 offset=1.00 分别得到 6.00／11.00。测量数量合同不混用，固定实参不由查询覆盖，也没有主题域或治理服务参与。技术证据用 `CalculationOwner.Metric` 标识实际根实例。实际 Engine 入口观察错误实例引用的 `BIND_INPUT` 失败，以及真实物理列类型变化的 `VERIFY_SOURCE` 失败。只有真实业务结果和两个边界行为都成立，进程才成功退出。

事实金额 SUM 保持精确十进制合同。每记录均值、factor 缩放与 offset 调整使用现有 `MetricValueContract.Approximate` 和 64 位浮点输出，评价域明确为 [-10^12,10^12]、绝对容差 10^-9、相对容差 10^-12，溢出失败；EUR／ONE 除法及 EUR×ONE 乘法分别声明保留 EUR 的单位方程。factor 和 offset 参数继续采用各自原生类型及数量合同，查询不能覆盖固定 factor。6.00／11.00 是这四行输入的独立结果预期，不是对通用均值的精确算术承诺。

`run/business-result.json` 保留原 SUM／COUNT 与 NULL 明细的完整类型化业务结果；`measurement-business-result.json` 保存共享 wrapper 的 6.00／11.00 结果，`measurement-request.json` 与 `measurement-compiled-query.json` 保存其明确请求和技术证据。`run/answer.md` 给出简短解释；同目录的 `catalog.json`、`request.json`、`compiled-query.json`、`source-description.json` 与 `boundary-failures.json` 保存此次输入、计划、真实源描述和失败证据。`source.sql`、`source-drift.sql` 保留实际建表与数据输入，`bad-reference-request.json` 保留坏引用请求；两项具体失败分别位于 `bad-reference-failure.json`、`source-drift-failure.json`，实际完成的绑定、阶段、源解析和降低阶段各自保存证据。

该工程直接观察确定性内核；完整业务用法通过正式 MCP 服务观察。运行需要 JDK 21、可写本地目录，以及 Gradle 依赖解析条件。构建成功与实际业务结果分别提供自己的证据。
