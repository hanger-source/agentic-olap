# Agentic OLAP 服务使用与部署

安装后启动完整 Agentic OLAP 服务。外部业务 Agent 通过统一 MCP 调查实际业务源、澄清业务、建设和组合语义定义、管理采用与发布、组织完整 Query 并读取结果。业务项目承载 Agent、实际接入、上下文与界面；服务持有可复用资产，由同一确定性引擎执行计算，不启动模型。

## 安装与启动

本仓库源码的连接器 API 为 `agentic-olap-connector/v14-kotlin-mean-target-calcite-1.42`，存储格式为 `19`。服务、连接器和 SDK 使用同轮交付构件；安装发行物时以实际服务及 `agentic-olap sdk` 披露的 API 为准。独立插件须使用对应正式 Maven SDK 重新构建，集合展开实现与精确绑定要求见[连接说明](connections.md)。

代码会话使用 `agentic-olap-kotlin-dsl/v8`，实际跨进程通信为 `agentic-olap-runtime/v11`。Kotlin 主服务直接持有正式应用、权威元数据和运行管理；语言会话在固定 REPL JVM 中保留真实 Kotlin 历史，来源工作进程承担调查、规划与执行。模型对话与账号由外部客户端持有。

需要 Node.js 20 或更高版本。macOS arm64 产品通过一个 npm 包交付 JRE、自包含安装引导与命令入口；npm 安装脚本用包内 JRE 从 JitPack 取得自有 Kotlin 运行构件和内置连接器，从 Maven Central 取得第三方 JVM 库和 H2／DuckDB 驱动，全部按发行清单锁定，完成摘要校验及本地安装。需要允许该安装脚本，下载失败会使安装失败。安装完成后启动只使用本地文件，不再下载依赖。SDK 的正式 Maven 坐标和文档由 `agentic-olap sdk` 读取。使用者无需安装编译工具或自行编译产品源码：

~~~sh
npm install --global @hanger-source/agentic-olap
agentic-olap start
~~~

服务前台运行，默认监听 http://127.0.0.1:8000/mcp。首次启动自动生成本地持久配置和随机访问令牌，不需要外部 issuer、JWKS 或模型账号。在另一个终端操作同一服务：

~~~sh
agentic-olap info
agentic-olap tools
agentic-olap call list-data-sources --payload '{}'
agentic-olap call describe-script-api --payload '{}'
agentic-olap call execute-script --payload '{"executionId":"sources-001","waitMillis":0,"source":"val sources = olap.sources.list(EmptyRequest)\nolap.output(sources)","sourceName":"sources.olap.kts","dslApiVersion":"agentic-olap-kotlin-dsl/v8","parameters":{}}'
agentic-olap call wait-script --payload '{"executionId":"sources-001","waitMillis":1000}'
~~~

`tools` 通过官方 MCP 客户端读取可见工具；`call` 将 JSON 对象作为工具 payload 提交，输出真实 MCP 响应，业务失败以非零退出。`describe-operation` 提供 MCP 提交封套，`describe-script-api` 提供脚本实际使用的 Kotlin 方法、构造器和回执。脚本版本取服务发现结果。如何建设、复用和查询语义，见随同交付的 [Agent skill](../SKILL.md)。

已有适用定义或发布时直接复用；新业务含义通过服务中的治理工具沉淀，不要求修改产品源码、执行批量建模脚本或每次重新发布。

## 代码会话的执行与控制

`execute-script` 必填 executionId 和完整 `source/sourceName/dslApiVersion/parameters`；sessionId 可空以创建会话，继续提交时使用实际返回的 sessionId。waitMillis 为非负整数，0 立即观察，正值最多等待这些毫秒。RUNNING 表示执行尚未结束，使用 `wait-script {executionId,waitMillis}` 继续读取，不再次提交代码。工具受理成功与内层执行 success 分开；最终 COMPLETED 且 success=true 才是代码成功。

同一主体和规范化 scopes 下，相同 executionId 及执行输入读取原记录；waitMillis 不参与冲突比较。首次创建时省略 sessionId，重试同一执行也保持该输入。不同执行输入使用新 executionId。HTTP 超时或客户端停止等待不会自动取消代码，执行有独立部署期限。

| 工具 | 输入 | 生命周期 |
| --- | --- | --- |
| `wait-script` | executionId、waitMillis | 读取同一执行及实际输出，不重新求值 |
| `cancel-script` | executionId | 请求协作停止并等待实际结束；必要时停止 JVM，来源取消仍按真实证据表达 |
| `read-script-session` | sessionId | 读取 STARTING／READY／BUSY／RESETTING／CLOSING／LOST／CLOSED 及当前执行 |
| `reset-script-session` | sessionId | 停止当前代码，确认旧 JVM 退出，清理目录并建立空语言环境 |
| `close-script-session` | sessionId | 停止当前代码，确认退出并清理目录，释放会话资源 |

一个会话固定一个 JVM，一次执行一个片段，只有 READY 能接收新片段；RESETTING／CLOSING 不接受代码。普通编译失败及官方求值历史仍可继续的运行错误保留会话；不可恢复语言状态、求值上下文恢复失败、进程／协议故障或强制终止需要明确 reset。新增线程、未知归属和线程观察失败只提供诊断，不因此使会话不可用。控制操作不撤销已完成的治理或来源执行。无需继续时显式 close；服务重启不恢复语言历史、执行记录或去重，不自动重放源码。正式定义、发布和保存结果各自保持持久合同。

`olap` 和 `parameters` 是正式 DSL 的顶层属性，由应用在真正求值期间绑定到当前线程；环境不向后台线程继承。片段共用稳定 OlapClient，parameters getter 读取当前不可变快照，`val original = parameters` 保留当时的对象；旧函数读取 getter 或调用稳定客户端时使用当前执行绑定。正式调用和显式输出继续核对精确活跃求值线程。变量和函数的重新声明遵循 Kotlin 语义，不保证旧函数内部自动改绑。`olap.temporaryDirectory` 为跨片段的会话私有根；内部请求子目录不向作者暴露，交接结束后清理。

`olap.output` 是 Unit 输出动作，接受真实注册回执、精确引用及普通 ScriptValue，可以多次使用；末表达式任意对象不自动输出。outputs 顺序保存 VALUE 与 STDOUT／STDERR：VALUE 使用正式 Protobuf JSON，打印合法 UTF-8 时 encoding=UTF8，否则 encoding=BASE64。已输出内容及 calls.effects 不因后续异常消失；是否输出与正式写入效果独立。完整构造及状态说明见 [Agent 使用指引](../../../docs/agent-usage.md#会话中调用正式能力)。

## 本地配置、令牌与持久数据

默认目录为 `~/.agentic-olap`：

| 内容 | 位置或行为 |
| --- | --- |
| 部署配置 | `server.json`，可直接编辑 JSON，重启后生效 |
| 本地令牌 | `access-token`，首次生成至少256位随机值，文件权限0600 |
| 持久数据 | `data/`，持有元数据、保存结果、使用记录及 `responses/` 中自动完整保存的响应 |
| 连接信息 | `info` 输出 `url/configPath/dataRoot/tokenPath/authMode`、安装目录与版本，并从 MCP 读取服务版本、获准连接器和来源；不打印令牌值 |
| 显式取得令牌 | `agentic-olap token`，供自己的 MCP 客户端注入 Authorization Bearer |
| 尚未初始化 | `info`、`token`、默认本地 `tools/call` 明确失败，先执行 `start` |

`start --home /absolute/path --port 8010` 使用指定持久根与端口；后续 `info/tools/call/token` 使用同一 `--home`。显式 `--port` 会保存到本地配置，重新启动保留原令牌与资产。用终端中断结束前台服务，再以同一目录启动；服务关闭会回收所属请求、通道和工作进程。

升级先停止该前台进程，执行 `npm install --global @hanger-source/agentic-olap@目标版本`，再以原 `--home` 或 `--config` 启动。所有随包文件由 npm 替换，没有第二个产品安装步骤。安装完成不会重启已运行的进程，也不会改写配置或转换持久根格式。升级前核对目标发行的存储格式与连接器 API；精确插件引用变化时明确配置插件并登记其兑现采用，数据合同或原生调用依赖改变时再修订相应定义，不自动改变旧发布引用。

元数据库与保存结果分别核对格式：storage 19 描述元数据库；结果文件封套为 `olap-query-result-artifact/v6`、记录为 `olap-query-result-record/v7`。就绪报告披露实际支持格式，并区分旧结果格式不支持与文件损坏。同一元数据库格式不保证新发行能解码所有旧结果；旧文件保留原字节，使用匹配发行读取，不自动转换，也无需重新执行原业务操作来取得历史结果。

本地重启后用 `info` 核对安装版本与实际服务版本；不可达或版本不同会返回具体原因及非零退出，报告仍保留配置和安装位置。远程客户端用 `tools` 返回的 `serverInfo.version` 核对。连接器和来源来自已有的授权读取；未获准项明确显示，来源列表不证明真实查询成功，`sourceQuery=NOT_CHECKED` 表示没有执行来源 SQL。所选发布、保存查询与结果的可用前提可通过 `read-deployment-availability` 核对；实际业务确认使用明确选择的查询，不自动重跑全部业务用法。

本地认证明确使用 `auth.mode="local-token"`，其字段为 `tokenFile/subject/scopes/grants`。只允许监听127.0.0.1或::1，令牌必须来自0600普通文件。默认主体为 `local`，scope为 `olap.local`；生成的 grant 操作列表来自同发行模板，`dataSources/subjectAreas/releases` 显式为 `["*"]`，`allGovernanceAssets=true`。这些权限仅授予该本地令牌主体，仍经过相同的操作、来源、治理和发布授权。

核心不会生成业务指标或连接配置。安装后的空库可以立即发现治理合同；实际业务查询还需要接入真实来源，并建设或选择相应语义资产。

## 取得连接器并绑定数据源

连接器发行方交付 manifest、插件 JAR 和具体实现需要的依赖；JDBC 实现包含实际驱动，HTTP 实现拥有自己的协议、鉴权和结果映射。H2、DuckDB 插件已随 npm 安装，通过 `agentic-olap connectors` 读取可直接用于配置的精确 manifest 路径和引用；扩展开发 SDK 通过 `agentic-olap sdk` 读取 JitPack 仓库、精确 Maven 坐标和随包文档。其他业务源插件由使用方独立提供。具体用法见[插件取得说明](connections.md#取得插件组)，保持 manifest 中的实际版本与字节摘要，不能只放一个数据库驱动或猜测哈希。

插件组取得、显式结构登记和原生函数的用法见[连接说明](connections.md)。

编辑 `info` 返回的配置文件。顶层 `connectors` 直接填写各 manifest 路径，不填写独立 JVM 使用的 connectors.json 列表文件。相对路径以配置文件所在目录解析。文件型 DuckDB 的配置关系如下：

| 配置项 | 含义 |
| --- | --- |
| `connectors` | 例如 `["/absolute/plugins/duckdb/connector.json"]` |
| `dataSources[].dataSourceId` | 明确的源身份，例如 `business` |
| `dataSources[].connectorRef` | 从同组 manifest 原样复制实际 `{id,version,sha256}` |
| `dataSources[].dialectId` | `duckdb` |
| `dataSources[].identityParameters.jdbcUrl` | 例如 `jdbc:duckdb:/absolute/warehouse/business.duckdb`，数据库须实际存在 |
| `dataSources[].identityParameters.username` | 无用户名时省略 |
| `dataSources[].credentialEnvironmentVariables` | 无凭据时为 `{}`；JDBC 密码为 `{"password":"BUSINESS_DB_PASSWORD"}` |
| `dataSources[].identityParameters.driverClassName` | `org.duckdb.DuckDBDriver` |
| `dataSources[].runtimeParameters` | 插件声明的超时、预算或诊断策略；没有时为 `{}`，不进入来源身份 |

密码值通过启动服务的环境提供，不写入业务工具输入。网络型数据库使用自己的插件、真实 JDBC 地址和数据库访问条件。连接器、凭据与路径由服务配置管理，业务 Agent 只引用授权源身份。

修改配置后重启同一服务，再核对实际来源：

~~~sh
agentic-olap call describe-script-api --payload '{"operation":"inspect-warehouse"}'
agentic-olap call execute-script --payload '{"executionId":"sources-after-config","waitMillis":0,"source":"olap.output(olap.sources.list(EmptyRequest))","sourceName":"sources.olap.kts","dslApiVersion":"agentic-olap-kotlin-dsl/v8","parameters":{}}'
agentic-olap call wait-script --payload '{"executionId":"sources-after-config","waitMillis":1000}'
~~~

同一 `dataSourceId` 可以有多个访问绑定。明确选定 `olap.sources.list(EmptyRequest)` 返回的 `binding.sourceIdentityHash`，调查与显式结构登记原样传入该摘要，不猜最新绑定。结构快照不是兑现采用；初次建物理表同事务返回采用，后续通过 `registerSourceAdoptions` 明确接受目标结构兑现原精确数据合同，再把返回 `references` 交发布；已有采用按精确快照读取，见[连接说明](connections.md#更换插件与显式采用)。来源列表只证明登记可见；实际调查结果才说明本次连接与调查成立。空表结果可能来自真实空库或调查范围。连接、授权或业务口径不足时取得实际信息，不自动换来源、补零或扩大权限。

## 外部认证的远程服务

远程部署使用明确配置：

~~~sh
agentic-olap start --config /absolute/deployment/server.json
~~~

`--config` 不与 `--home/--port` 混用，也不生成或改写该配置。顶层配置包含 `listen/auth/dataRoot/connectors/dataSources/limits/history/scripting`；[server.example.json](server.example.json) 是完整的外部认证空库模板。实际地址、主体、来源、权限与预算由部署方填写。

外部认证明确使用 `auth.mode="external-jwt"`，与本地令牌模式分别选择，不互相兜底。认证部分形状如下，所有占位值必须替换：

~~~json
{
  "mode": "external-jwt",
  "issuer": "https://issuer.example.com/",
  "audience": "https://olap.example.com/mcp",
  "jwksUri": "https://issuer.example.com/.well-known/jwks.json",
  "algorithms": ["RS256"],
  "grants": [{
    "subjects": ["external-analyst"],
    "scopes": ["olap.query"],
    "operations": ["describe-operation", "describe-script-api", "list-publications", "read-publication"],
    "dataSources": ["business"],
    "subjectAreas": ["replace-with-actual-subject-area-id"],
    "releases": ["business-release"],
    "allGovernanceAssets": false
  }]
}
~~~

该示例 grant 只授予列出的发现和读取权限。代码会话的 execute-script、wait-script、cancel-script、read-script-session、reset-script-session、close-script-session 分别需要对应操作权限；片段调用的治理、来源、查询与结果权限仍须逐项授予。允许会话执行或控制不会扩大其中的业务能力。服务从 JWKS 验签，校验 issuer、audience、算法和有效期；JWT 必须有 `iss/aud/exp/sub`，sub 为非空文本。文本 scope claim 按空白拆分，其他 claim 不自动转为权限。

每条 grant 要求主体精确匹配，并包含它要求的全部 scope；`operations` 为正式权限标识。部分与工具名相同，类型化治理方法仍对应 `read-governance/write-governance` 等共同权限，执行脚本还分别核对实际能力与资源；不能把方法名直接当权限名。资源数组 `dataSources/subjectAreas/releases` 可使用精确身份或显式 `"*"`，空数组不授予该类资源，不支持前缀模式。共享定义治理还需要 `allGovernanceAssets=true`；发布访问需要同一 grant 同时覆盖其主题域和发布，执行另核对实际来源。创建命令中的主体须与认证主体一致。

完整模板保留了空资源数组。配置业务源时，将其稳定 `dataSourceId` 明确加入获准主体的 `dataSources`。空库可以先授予共享定义治理权限，取得实际创建返回的主题域 ID 和自行指定的发布 ID 后配置发布范围；也可以由部署方显式授权 `"*"` 覆盖未来资源。修改授权配置后重启同一服务，再用该主体的真实令牌发现工具并调用获准操作。

远程 HTTP 监听置于 TLS 反向代理之后，配置真实的 HTTPS audience、issuer和可访问的 JWKS，以及代理的 Host/Origin 规则。客户端可以直接使用产品 CLI：

~~~sh
export OLAP_SERVICE_TOKEN='由实际认证设施签发的访问令牌'
agentic-olap tools --url https://olap.example.com/mcp --token-env OLAP_SERVICE_TOKEN
agentic-olap call list-publications --payload '{"subjectAreaId":"实际主题域ID","releaseId":"business-release"}' --url https://olap.example.com/mcp --token-env OLAP_SERVICE_TOKEN
~~~

`--url` 和 `--token-env` 必须同时给出，不能与 `--home` 混用。普通本地调用无需这些远程参数，也无需 pip 安装客户端。

## 持续使用、结果与备份

定义、采用、发布、保存查询和使用记录各有精确身份。修改定义不会把旧引用切成最新版本；业务数据可以持续变化，同一语义查询的新运行不覆盖旧结果。查询不要求先保存。

官方 SDK 生成的完整最终 JSON/MCP 消息不超过 `limits.responseBytes` 时直接返回，超过时自动完整保存在 `dataRoot/responses`。当前消息给出简短提示、`location` 和 `read.operation/read.payload`；无需预先选择保存，不截断、删减或重执行业务，不新增存储配额或审批。成功保持成功，失败消息完整保存后仍保留原失败。

将短提示的 `read.payload` 原样作为 `read-response` 的 payload，其中包含精确 `reference={responseId,sha256}` 和起始 `offset`。可选 length 与 offset 按 UTF-8 字节计算；offset 必须位于字符起点，段尾不截破字符。返回页包含 `content/offset/nextOffset/totalBytes/reference`，按 nextOffset 继续读取完整存档，不重新执行业务。读取要求同一主体、规范化 scopes 和完整授权策略。存档若是 RUNNING 观察，执行的后续状态仍通过 wait-script 读取。

执行请求的 `saveResult: true` 显式保存历史 SQL 结果，成功交付后按实际 `resultReference` 分页读取。结果与执行使用同一 runId；保存或 manifest 提交失败会如实报告，不伪造引用或重新执行查询。单位、NULL和递归值保持原含义。SQL 结果物化限额、显式历史结果保存与完整响应自动存档是独立合同。

`scripting` 声明会话 JVM 容量、堆预算、每次执行的正式能力调用上限，以及 nativeLogBytes 原生诊断预算，准确字段与值使用完整模板。会话语言状态与根目录持续存在，内部请求资源按执行释放；普通 System.out/err 进入当前执行的有序输出，原生 FD2 留在进程生命周期的私有诊断，不混作用户打印。服务不承诺任意 JVM 全局副作用自动恢复为新进程。

workerOutcome／workerDisposition／recycleReasons 描述实际进程处理，执行和会话状态分别读取。正常语言历史类加载器不逐片段关闭。线程差集不证明创建者或泄漏；新增、未取样、归属未知或读取失败的线程观察不影响执行状态及会话可继续性，身份、状态、daemon、上下文类加载器及必要堆栈仅进入关联 request/worker 的有界私有诊断。后台线程不能使用当前求值线程的正式权限；越界调用、迟到输出及关闭产品标准流的动作被拦截并记诊断，不因此终止会话。同步能力等待结束后，迟到回复按原精确调用身份处理；宿主收齐本次能力任务，同一管道 writer 保证已提交回复先于下一片段请求完整交付，旧回调不借用新请求绑定。真实写帧失败会结束该通信进程，来源结局未知与语言历史不可继续分别表达。

`limits` 分别声明输入预算、直接响应阈值、并发、背压、来源工作进程、原生内存和 SQL 结果预算：

| 字段 | 检查边界 |
| --- | --- |
| `requestBytes/inputNodes/inputDepth` | 实际 JSON 输入。根为第 1 层且计节点，容器、标量和 NULL 计节点，键不计；节点和深度在首次越界拒绝 |
| `responseBytes` | 官方 SDK 生成的完整最终 JSON/MCP 消息的直接返回阈值。超过时自动完整存档并给出位置和 `read-response`，不据此把原执行改为失败 |
| `resultRows/resultValues/resultBytes/resultDepth` | SQL 业务结果物化。与 JSON 输入及完整消息交付独立，不以 `resultDepth` 限制普通 `ScriptValue` |

普通值模型不另设固定的节点、深度、文本、键或十进制容量阈值，结构目录通过 `parameters` 是正常输入路径。普通值与正式能力回执使用正式 Protobuf 编码和实际消息读取，不设置独立响应深度门槛。完整内部 Protobuf 帧超过管道帧阈值时通过进程私有文件交接同一消息；`pendingOutputBytes` 约束在途管道帧和背压，不作为完整业务返回的容量上限。工作进程按需使用，预算不是每个进程可以同时无限占用资源的承诺；预算调整由部署方决定，业务 Agent 不会自行扩大。诊断与计数细节见[普通参数与返回值容量](../../../docs/agent-usage.md#普通参数与返回值容量)。

`describe-script-api` 读取 `ScriptArguments/ScriptValue` 及其子类型时，`rules.deploymentBudgets` 返回实际部署限额与计数语义；`describe-operation` 选择 `execute-script` 时，`resourceBudgets` 返回同一组部署事实。业务客户端据此判断当前服务的边界。

治理写入及保存／修订查询的 `calls[].effects` 仅支持在当次响应中按精确引用继续读取，权限保持原有边界。程序后续失败不撤销已提交效果，也不自动重试或回滚；空 `effects` 不代表没有副作用。断线或进程退出后的持久恢复不由这些响应内引用提供。

持久根在所有写入进程停止后整体备份，恢复时使用匹配版本的运行构件和精确连接器。业务数据库按自己的生命周期备份；不要复制运行中元数据库的单个文件或编辑内部存储来绕过版本合同。
