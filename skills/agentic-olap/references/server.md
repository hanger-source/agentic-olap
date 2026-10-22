# Agentic OLAP 服务使用与部署

安装后启动完整 Agentic OLAP 服务。外部业务 Agent 通过统一 MCP 调查实际业务源、澄清业务、建设和组合语义定义、管理采用与发布、组织完整 Query 并读取结果。业务项目承载 Agent、实际接入、上下文与界面；服务持有可复用资产，由同一确定性引擎执行计算，不启动模型。

## 安装与启动

连接器 API 为 `agentic-olap-connector/v11-kotlin-native-collection-relations-calcite-1.42`，存储格式为 `15`。服务、连接器和 SDK 使用同轮交付构件；独立插件须使用本版正式 Maven SDK 重新构建，集合展开实现与精确绑定要求见[连接说明](connections.md)。

需要 Node.js 20 或更高版本。macOS arm64 平台运行包包含 Go 程序、JRE 与自有 Kotlin 构件；npm 安装脚本自动取得发行清单锁定的第三方 JVM 库和内置 H2／DuckDB 驱动，完成摘要校验及本地安装。需要允许该安装脚本，下载失败会使安装失败。安装完成后启动只使用本地文件，不再下载依赖。SDK 的正式 Maven 坐标和文档由 `agentic-olap sdk` 读取。使用者无需安装编译工具或自行编译产品源码：

~~~sh
npm install --global @hanger-source/agentic-olap
agentic-olap start
~~~

服务前台运行，默认监听 http://127.0.0.1:8000/mcp。首次启动自动生成本地持久配置和随机访问令牌，不需要外部 issuer、JWKS 或模型账号。在另一个终端操作同一服务：

~~~sh
agentic-olap info
agentic-olap tools
agentic-olap call list-data-sources --payload '{}'
agentic-olap call discover-governance-commands --payload '{}'
agentic-olap call describe-governance-command --payload '{"operation":"create-business-line"}'
~~~

`tools` 通过官方 MCP 客户端读取可见工具；`call` 将 JSON 对象作为工具 payload 提交，输出真实 MCP 响应，业务失败以非零退出。参数合同由 `describe-operation` 及治理描述工具提供。如何建设、复用和查询语义，见随同交付的 [Agent skill](../SKILL.md)。

已有适用定义或发布时直接复用；新业务含义通过服务中的治理工具沉淀，不要求修改产品源码、执行批量建模脚本或每次重新发布。

## 本地配置、令牌与持久数据

默认目录为 `~/.agentic-olap`：

| 内容 | 位置或行为 |
| --- | --- |
| 部署配置 | `server.json`，可直接编辑 JSON，重启后生效 |
| 本地令牌 | `access-token`，首次生成至少256位随机值，文件权限0600 |
| 持久数据 | `data/`，持有元数据、保存结果及使用记录 |
| 连接信息 | `info` 输出 `url/configPath/dataRoot/tokenPath/authMode`、安装目录与版本，并从 MCP 读取服务版本、获准连接器和来源；不打印令牌值 |
| 显式取得令牌 | `agentic-olap token`，供自己的 MCP 客户端注入 Authorization Bearer |
| 尚未初始化 | `info`、`token`、默认本地 `tools/call` 明确失败，先执行 `start` |

`start --home /absolute/path --port 8010` 使用指定持久根与端口；后续 `info/tools/call/token` 使用同一 `--home`。显式 `--port` 会保存到本地配置，重新启动保留原令牌与资产。用终端中断结束前台服务，再以同一目录启动；服务关闭会回收所属请求、通道和工作进程。

升级先停止该前台进程，执行 `npm install --global @hanger-source/agentic-olap@目标版本`，再以原 `--home` 或 `--config` 启动。所有随包文件由 npm 替换，没有第二个产品安装步骤。安装完成不会重启已运行的进程，也不会改写配置或转换持久根格式。升级前核对目标发行的存储格式与连接器 API；精确插件引用变化时，明确配置插件并修订受影响资产，不自动改变旧发布引用。

本地重启后用 `info` 核对安装版本与实际服务版本；不可达或版本不同会返回具体原因及非零退出，报告仍保留配置和安装位置。远程客户端用 `tools` 返回的 `serverInfo.version` 核对。连接器和来源来自已有的授权读取；未获准项明确显示，来源列表不证明真实查询成功，`sourceQuery=NOT_CHECKED` 表示没有执行来源 SQL。所选发布、保存查询与结果的可用前提可通过 `read-deployment-availability` 核对；实际业务确认使用明确选择的查询，不自动重跑全部业务用法。

本地认证明确使用 `auth.mode="local-token"`，其字段为 `tokenFile/subject/scopes/grants`。只允许监听127.0.0.1或::1，令牌必须来自0600普通文件。默认主体为 `local`，scope为 `olap.local`；生成的 grant 操作列表来自同发行模板，`dataSources/subjectAreas/releases` 显式为 `["*"]`，`allGovernanceAssets=true`。这些权限仅授予该本地令牌主体，仍经过相同的操作、来源、治理和发布授权。

核心不会生成业务指标或连接配置。安装后的空库可以立即发现治理合同；实际业务查询还需要接入真实来源，并建设或选择相应语义资产。

## 取得连接器并绑定数据源

连接器发行方交付 manifest、插件 JAR 和具体实现需要的依赖；JDBC 实现包含实际驱动，HTTP 实现拥有自己的协议、鉴权和结果映射。H2、DuckDB 插件已随 npm 安装，通过 `agentic-olap connectors` 读取可直接用于配置的精确 manifest 路径和引用；扩展开发 SDK 通过 `agentic-olap sdk` 读取本地路径。其他业务源插件由使用方独立提供。具体用法见[插件取得说明](connections.md#取得插件组)，保持 manifest 中的实际版本与字节摘要，不能只放一个数据库驱动或猜测哈希。

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
agentic-olap call read-connector-capabilities --payload '{}'
agentic-olap call list-data-sources --payload '{}'
agentic-olap call inspect-warehouse --payload '{"operation":"discover","payload":{"dataSourceId":"business","sourceIdentityHash":"替换为list-data-sources所选绑定的实际64位摘要","limit":20}}'
~~~

同一 `dataSourceId` 可以有多个访问绑定。明确选定 `list-data-sources` 返回的 `sourceIdentityHash`，调查与显式结构登记原样传入该摘要，不猜最新绑定。来源列表只证明登记可见；实际调查结果才说明本次连接与调查成立。空表结果可能来自真实空库或调查范围。连接、授权或业务口径不足时取得实际信息，不自动换来源、补零或扩大权限。

## 外部认证的远程服务

远程部署使用明确配置：

~~~sh
agentic-olap start --config /absolute/deployment/server.json
~~~

`--config` 不与 `--home/--port` 混用，也不生成或改写该配置。顶层配置包含 `listen/auth/dataRoot/connectors/dataSources/limits/history`；[server.example.json](server.example.json) 是完整的外部认证空库模板。实际地址、主体、来源、权限与预算由部署方填写。

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
    "operations": ["describe-operation", "list-publications", "read-publication"],
    "dataSources": ["business"],
    "subjectAreas": ["replace-with-actual-subject-area-id"],
    "releases": ["business-release"],
    "allGovernanceAssets": false
  }]
}
~~~

该示例 grant 仅允许列出的三项读取；实际治理或查询所需操作按服务合同逐项授权。服务从 JWKS 验签，校验 issuer、audience、算法和有效期；JWT 必须有 `iss/aud/exp/sub`，`sub` 为非空文本。文本 `scope` claim 按空白拆分，其他 claim 不自动转为权限。

每条 grant 要求主体精确匹配，并包含它要求的全部 scope；`operations` 为精确工具名。资源数组 `dataSources/subjectAreas/releases` 可使用精确身份或显式 `"*"`，空数组不授予该类资源，不支持前缀模式。共享定义治理还需要 `allGovernanceAssets=true`；发布访问需要同一 grant 同时覆盖其主题域和发布，执行另核对实际来源。创建命令中的主体须与认证主体一致。

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

大结果在执行时显式选择 `saveResult: true`，成功交付后按返回引用分页读取。结果与执行使用同一 runId；保存或 manifest 提交失败会如实报告，不伪造引用或重新执行查询。单位、NULL和递归值保持原含义。

`limits` 声明请求、并发、背压、工作进程、原生内存及结果预算。结果采用共同 `resultRows/resultValues/resultBytes/resultDepth`，`pendingOutputBytes` 须容纳一个完整输出帧。工作进程按需使用，预算不是每个进程可以同时无限占用资源的承诺。预算调整由服务配置决定，业务 Agent 不会自行扩大。

持久根在所有写入进程停止后整体备份，恢复时使用匹配版本的运行构件和精确连接器。业务数据库按自己的生命周期备份；不要复制运行中元数据库的单个文件或编辑内部存储来绕过版本合同。
