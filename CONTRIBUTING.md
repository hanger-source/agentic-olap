# Agentic OLAP · 参与开发

产品源码位于 `project/`，职责见[架构](docs/architecture.md)。业务 Agent 通过完整服务管理语义资产和查询；修改 Kotlin、协议、构建或发行属于源码研发。研发示例和试验使用本仓库的自含数据，产品构建与运行不依赖外部私有资料。

## 环境与唯一入口

研发环境使用 JDK 21，npm 产品装配另需 Node.js。Kotlin 和 Gradle 的版本由仓库版本目录、构建声明与 Wrapper 固定，不要求安装系统 Gradle 或 Kotlin 编译器。构建与装配统一使用 Gradle；独立 Kotlin 研发客户端通过真实 MCP 执行已有业务用法。生产和必需研发链路不依赖 Go 或 Python。

在仓库根执行：

```sh
./project/gradlew -p project assembleJvm
./project/gradlew -p project serverDistribution
./project/gradlew -p project :integration-tests:test --tests '完整类名.方法名'
```

`assembleJvm` 完成生产编译、必要协议生成和真实构件清单。构建声明只使用当前源码及已声明的依赖，不发现或装回归档代码。`serverDistribution` 使用本轮 Gradle 构件装配 `project/build/distribution`，不运行测试。

| 工作 | 仓库根命令 |
| --- | --- |
| 生成内部通信代码 | `./project/gradlew -p project :olap-protocol:generateProto` |
| 构建完整生产代码 | `./project/gradlew -p project assembleJvm` |
| 聚焦一个 JVM 试验 | `./project/gradlew -p project :integration-tests:test --tests '完整类名.方法名'` |
| 本地服务发行目录 | `./project/gradlew -p project serverDistribution` |
| 持续 REPL、权限与取消的核心真实试验 | `./project/gradlew -p project :development:runtime` |
| HTTP 来源与显式结构的完整服务用法 | `./project/gradlew -p project :development:httpSource` |
| 按正式坐标装配本地 SDK 验收构件 | `./project/gradlew -p project stageSdk` |
| 精确标签的 npm 产品包 | `./project/gradlew -p project npmPackage` |
| npm 产品真实安装用法 | `./project/gradlew -p project :development:installedProduct` |
| 正式 MCP 业务用法 | `./project/gradlew -p project :development:orders` |
| 对象人口、保存算法与交付组合 | `./project/gradlew -p project :development:populations` |

`:development` 下的 `orders`、`procurement`、`subscriptions`、`populations` 与 `kernel` 任务明确选择独立用法。默认输出到 `.tmp/<用法>`，可用 `-Partifacts=/absolute/path` 指定；目标必须尚不存在。安装用法默认消费 `project/build/npm/tarballs`，可用 `-Ptarballs=/absolute/path` 指定同轮包；不从本机已安装版本猜测输入。

`populations` 以公开合成数据执行对象集合、独立袋测量、完成关系参数、保存算法和独立分母的完整组合，并穿过来源采用、精确发布、保存查询、完整结果读取及重启。外部改变来源后，分别核对新执行与旧保存结果；脚本部分成功时从正式回执恢复，不重放已经成功的写入。业务问题、输入及独立预期见[对象人口用法](examples/populations/README.md)。

`:development:runtime` 使用真实服务、HTTPS JWKS、REPL 和 macOS 沙箱，观察正式能力回调、持续语言历史、参数快照、会话与主体隔离、文件／网络／外部程序权限、失控取消后的进程及目录回收，以及 chunked 请求预算。默认输出根 `.tmp/runtime` 必须尚不存在，可用 `-Partifacts=/absolute/path` 指定。运行试验与完整业务用法均显式调用，不作为构建的隐式步骤。

`:development:httpSource` 使用现有独立 HTTP SQL fixture、HTTP 插件与订单作者程序，完整穿过正式 MCP、可选 catalog 拒绝、显式结构登记、建设与发布、真实查询、结果保存和重启读取。数据库仅由 fixture 持有；其测试 classpath 不进入产品发行。默认输出根 `.tmp/http-source` 必须尚不存在，同样支持 `-Partifacts=/absolute/path`。调用方预算由研发用法显式声明，产品默认配置保持独立。

## 源码、依赖与通信

主服务 `applications/server` 直接拥有 HTTP、认证、治理装配、权威元数据、会话、进程和结果交付。`script-worker` 持有持续 REPL；`worker-runtime` 持有来源调查、规划和执行入口；`bootstrap` 只负责 npm 安装自举。`tools` 是独立 Kotlin 研发客户端模块，生产服务不依赖它。

Kotlin 模块由 `project/settings.gradle.kts` 统一登记，公共版本由 `project/gradle/libs.versions.toml` 声明。`kernel/` 保存通用计算，`upstream/` 保存治理、采用、发布与存储，`applications/` 保存应用装配及插件装载，`scripting/` 保存纯 DSL API 与脚本编译求值，`protocol/` 保存纯生成协议和显式字段转换，`connectors/` 保存具体后端实现。集成试验位于 `project/modules/integration-tests/`，示例在 `examples/`。

`project/protocol/` 的 schema 声明实际跨进程与持久化消息。Gradle 生成 JVM 通信代码，`olap-protocol` 只依赖官方 Protobuf 运行库；`olap-codec` 显式转换具体消息与正式领域类型。纯合同不依赖编码实现。同进程内直接传正式 Kotlin 对象，不把协议封套作为应用返回模型。禁止反射序列化任意模型、Struct 语义树或按类名恢复对象。生成的 JVM Java 消息属于构建输出，手写生产与试验源码使用 Kotlin。MCP 保留提交封套、发现与结果读取；作者通过整段 kts 构造正式模型，不维护 JSON AST。

主服务拥有 JVM 启动、工作池、期限、取消及关闭，工作进程只取得本次已授权的执行输入和来源环境。语言会话固定一个 REPL JVM 并保留编译与求值历史；单次执行的参数、回调和输出分别管理。会话关闭、重置或失效时回收所属进程，不重放源码。主服务 classpath 不含 REPL 编译器，脚本 classpath 不含治理装配；工作入口不含 HTTP 服务。macOS 平台实现通过 JNA 装配进程组与 OS 沙箱，不把 ClassLoader、GC 或 JVM 堆预算当作权限隔离。

公开连接器 SDK、内部进程协议、产品发行版本和语义资产版本分别管理。JVM 构件使用 `com.github.hanger-source.agentic-olap` group，版本为 `v` 加 `project/cli/package.json` 的产品版本。SDK 由 JitPack 按源码仓库的精确发行标签构建；独立插件配置 JitPack 仓库和精确坐标，第三方依赖由 Maven Central 提供。SDK 构件公开下载，无需使用者令牌。插件通过 JVM ServiceLoader 注册，精确身份和类加载由公开装载器核验。

## 修改与验证

先明确本轮涉及的规则 owner、生产者、消费者和退出的旧路径，再把同轮生产与直接上下游一起写齐。完成后集中编译和类型诊断，修齐整族，再运行与真实变化相称的行为试验。不按微小编辑反复全量检查，不以覆盖率或绿色数量决定是否增加测试。

真实并发、失败、持久化、进程和权限边界优先复用已有试验；常量、字段映射、普通文档措辞及当前文件形状不单独冻结。测试的期望来自业务数据或独立可观察结果，不能由被测实现再生成同形答案。没有新的变化、失败或疑点时，不重复已取得的证据。

指标修改需要区分精确定义、所属主题域版本中的采用、发布执行实例和本次调用。定义依赖按精确 `MetricRef` 闭合，公开根按 `MetricInstanceRef` 选择，内部子调用沿实际位置派生绑定。共同模型由 `Calculation`、成员与数量合同各自拥有，不能为传输、测试方便再复制一套算法。

三个业务示例各自拥有原始数据、纯作者定义、查询和独立结果预期。Kotlin 准备者不写治理资产，外部客户端通过正式 MCP 完成调查、建设、发布、查询及结果读取。HTTP 来源试验只在 HTTP 服务端持有数据库，插件通过正式访问合同执行，并通过 MCP 显式登记结构。持续变化的数据、旧发布和旧结果分别保留各自含义。

评价 Agentic 使用时，观察业务 Agent 如何根据实际背景调查来源、澄清口径、建设或选择定义、组织完整查询和解释结果。确定性用法、普通编译与源码预写定义只能证明各自实际行为，不能替代自主业务使用的证据。

## 构件与发行

Gradle 的实际解析结果生成 `project/build/jvm-artifacts.json`，记录应用 classpath、模块、依赖、构件字节和摘要。根构建脚本声明任务关系，`project/buildSrc` 中的 Kotlin 装配任务消费这些真实输入，不维护第二份模块表。npm 归档另外保存精确发行标签对应的源码 revision 与实际文件摘要。

服务发行清单为 `agentic-olap-distribution/v4`，分别包含主服务、工作入口、脚本入口及作者 DSL 的精确类路径与主类。装配只消费同轮记录，运行时逐项核验路径与摘要，不扫描目录选取旧包。npm 产品包包含 JDK 21 工具生成的 JRE 与自包含安装引导；用户通过 npm 安装后直接启动，无需源码或构建工具。

`project/cli/package.json` 拥有单一 npm 包的版本、平台限制和原生命令入口。产品包通过实际安装试验后才能作为发行证据；skill 直接从本仓库的 `skills/agentic-olap` 交付。源码、文档、skill、SDK 和发布工作流采用同一精确标签。构建、安装、源码 push 和公开发布是不同动作，不自动互相授予权限。

Gradle 构件清单的 `externalArtifacts` 记录已解析的第三方 JAR 坐标、Maven Central 地址、长度和摘要；npm 装配同时从 JitPack 取得自有运行构件的实际字节，生成覆盖全部运行库和插件的 `jvm-dependencies.json`。独立安装引导 JAR 随 npm 带齐自身依赖，`postinstall` 用包内 JRE 运行它，下载、校验并原子落位运行文件；共享缓存通过摘要文件锁协调并发安装。完整源码研发发行目录直接使用 Gradle 构件，产品启动没有下载或依赖解析路径。

根 `jitpack.yml` 固定 JDK 21，并调用 `publishProductToMavenLocal`，发布服务运行模块、内置连接器和 SDK，不生成 npm 包。SDK 模块及传递依赖由 Gradle 项目关系决定。`./project/gradlew -p project stageSdk` 将同版 SDK 按正式坐标装配到 `project/build/sdk/maven`，供隔离的独立插件构建验收；本地验收不能替代 JitPack 远程构建。

本仓库的手动 `构建 JitPack JVM 构件` 工作流按已推送的发行标签请求公开源码构建，再确认 SDK 入口可以匿名下载。构建无需私有仓库访问令牌、JitPack 私有订阅、Sonatype 命名空间或 GPG 签名。先完成同标签 JVM 构件，再装配和验收单个 macOS arm64 npm 包，由 `发布 Agentic OLAP` 工作流通过 OIDC 发布。完整顺序见[安装与发布](docs/installation-release.md)。

根 Apache 2.0 许可证适用于本项目。JVM 依赖保留实际 LICENSE/NOTICE；分发资源保存在 `project/distribution-resources/`，构件清单与许可均随相应发行物交付。新增依赖或驱动需要核对自身分发内容。

阶段性提交对应写完整、编译通过并有相称证据的一批变化。精确暂存本批文件，保留并发或历史未提交内容；PR 说明问题、最终行为、验证和真实限制，不提交凭据、公司业务资料或私有运行轨迹。维护者显式决定 push 和公开发布。
