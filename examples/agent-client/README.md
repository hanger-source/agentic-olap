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
```

在自己的 Agent 客户端中登记 `info` 返回的 MCP URL，并配置 `Authorization: Bearer <访问令牌>`。本地令牌由 `agentic-olap token` 显式取得；令牌应由客户端的凭据或环境配置注入。远程服务使用部署方提供的真实 URL 与授权令牌。模型账号、模型对话上下文、业务界面和客户端生命周期由使用方管理。

连接后按[AI 使用指引](../../docs/agent-usage.md)发现实际 Kotlin 方法、构造器、属性与精确版本，再提交 kts 程序调查业务、检索和复用定义、组织查询。CLI 的 `--payload` 是工具参数对象；直接 MCP 调用使用 `arguments={"payload":{...}}`。脚本中固定提供 `olap` 与 `parameters`，模型账号和对话仍留在外部客户端。已有适用定义时直接使用，需要沉淀新的业务含义时再明确建设与发布。接入、来源与授权配置见[服务使用说明](../../skills/agentic-olap/references/server.md)，可安装的完整使用指引从[本仓库 skill](https://github.com/hanger-source/agentic-olap/tree/main/skills/agentic-olap)取得。

从仓库根提交[第一段程序](read-sources.olap.kts)，建立会话并保留真实来源对象、参数快照和一个帮助函数。`dslApiVersion` 使用发现返回的精确值，本例为 `agentic-olap-kotlin-dsl/v10`：

```sh
agentic-olap call execute-script --payload "$(jq -n --rawfile source examples/agent-client/read-sources.olap.kts '{executionId:"sources-first-001",waitMillis:10000,source:$source,sourceName:"read-sources.olap.kts",dslApiVersion:"agentic-olap-kotlin-dsl/v10",parameters:{label:"first"}}')"
```

`executionId` 标识一次明确执行；每次新执行分配新标识。首次可省略 `sessionId`，返回的 `result.sessionId` 是后续片段使用的会话。`waitMillis=0` 读取当前状态，正值只决定本次等待时长。若 `result.state` 仍为 `RUNNING`，继续等待同一个执行，直到取得最终状态：

```sh
agentic-olap call wait-script --payload '{"executionId":"sources-first-001","waitMillis":10000}'
```

等待不重新提交源码、不刷新执行期限，也不重复正式写操作。执行标识重复且输入不同会被拒绝。要停止执行，调用 `cancel-script` 并传同一 `executionId`。

第一段结束后，将返回的会话标识填入 `SESSION_ID`，提交[第二段程序](continue-sources.olap.kts)：

```sh
SESSION_ID='<result.sessionId>'
agentic-olap call execute-script --payload "$(jq -n --arg session "$SESSION_ID" --rawfile source examples/agent-client/continue-sources.olap.kts '{sessionId:$session,executionId:"sources-second-001",waitMillis:10000,source:$source,sourceName:"continue-sources.olap.kts",dslApiVersion:"agentic-olap-kotlin-dsl/v10",parameters:{label:"second"}}')"
```

同样按 `sources-second-001` 等待最终状态。第二段直接使用第一段的正式对象与函数；`originalParameters` 保持第一段的不可变快照，环境中的 `parameters` 表示第二段参数。函数重新调用正式客户端时使用当前执行归属。普通末表达式只参与 Kotlin 求值，不自动成为业务输出。

作者通过 `olap.output(...)` 明确输出正式回执、精确引用或 `ScriptValue`。这个方法返回 `Unit`；组合引用对象时直接使用 `ScriptValue.QueryReferenceValue(reference)`、`ScriptValue.ResultReferenceValue(reference)` 等正式值类型。客户端按执行回执的 `outputs` 顺序消费：`kind=VALUE` 的 `value` 是正式 `ScriptValue` Any JSON，`STDOUT`／`STDERR` 是本次打印事件。一次执行可以输出多个值，异常前已经产生的输出和正式调用效果仍保留。

完整 JSON／MCP 消息装不下时，服务完整保存并返回 `location` 与 `read-response` 的精确 `read.payload`。将该 payload 原样传给 `read-response`，按 `nextOffset` 继续读取并拼接；不重新执行业务。保存响应与保存查询结果是两个独立合同。

会话由调用者显式管理。使用结束时关闭会话，释放其固定 JVM：

```sh
agentic-olap call read-script-session --payload "$(jq -n --arg session "$SESSION_ID" '{sessionId:$session}')"
agentic-olap call close-script-session --payload "$(jq -n --arg session "$SESSION_ID" '{sessionId:$session}')"
```

`reset-script-session` 使用同一 payload，确认旧进程退出后清空语言上下文。普通编译错误可在原会话修正继续；运行失败后先读真实执行状态与 `sessionUsable`，不假定失败片段已初始化，也不重放此前成功的正式调用。客户端自行建立的短会话应在完成或失败后关闭；显式传入的会话由其调用者管理。

产品 CLI 的 `tools` 和 `call` 通过正式 MCP 连接远程服务。客户端负责认证、会话生命周期、提交源码及普通参数和消费输出，业务构造使用服务提供的正式类型，不在客户端复制语义编译或结果规则。需要定制客户端的研发者，可参考 [Kotlin 远程调用](../../tools/src/main/kotlin/com/olap/development/McpClient.kt)：它等待已有执行并关闭自行建立的会话，完整保存的响应由正式读取入口回读和校验。正式业务用法见[示例说明](../README.md)。示例作者预先给出的定义与预期只证明实际产品行为，不代表 Agent 自主完成业务理解。

[条件原生调用程序](conditional-native-call.olap.kts)展示完成关系中的 `ResultExpression.ResultNativeCall`：按显式记录字段 `record_id` LEFT 连接 RAW_BAG 成员，保留右侧跨日期的全部匹配贡献，不宣称单键唯一；再在资格 CASE 的 THEN 内调用精确插件声明的格式日期函数。NULL 或空字符串资格不选 THEN；未匹配明细的原字段保持 NULL，解析结果遵循原生函数的 NULL 行为，不补零或去重。所有分支仍须通过静态类型和发布授权检查，不能把恒假 CASE 当作绕过非法调用的方式。

运行前从正式能力发现取得当前插件的函数 ID 和签名。本例只接受两个 TEXT 实参、DATE 返回的既有声明，内置插件没有该函数时不具备本例前提；插件仍须用真实后端兑现格式、NULL、解析错误、CASE 求值和稳定性。`validate` 不请求来源执行，不证明脏日期行为。原生结果没有自动取得日历天差或均值的业务含义，本例不计算这些后续指标。

普通参数需完整给出 `operation`（`validate` 或 `execute`）、`connectorId/connectorVersion/connectorSha256`、`parseFunction/format`、`factMemberId/factMemberVersion/detailMemberId/detailMemberVersion`、`factDaySlot/factIdSlot/eligibilitySlot/detailDaySlot/detailIdSlot/rawTextSlot`、`scenarioId/scenarioVersion/releaseId/releaseVersion`。成员须已采用并允许所选槽，场景须授权这些成员、JOIN／DERIVE／PROJECT 和 `NATIVE_CALL` 及其参数表达式。按上面的 execute／wait／close 会话方式提交程序；成功执行仅用实际返回的 `resultReference` 读取所保存结果，不重放 Query。
