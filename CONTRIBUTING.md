# Agentic OLAP · 参与开发

产品源码位于 `project/`，职责见[架构](docs/architecture.md)。业务 Agent 通过完整服务管理语义资产和查询；修改 Go、Kotlin、协议、构建或发行属于源码研发。研发示例和试验使用本仓库的自含数据，产品构建与运行不依赖外部私有资料。

## 环境与唯一入口

研发环境使用 JDK 21 与 Go 1.26，npm 平台包另需 Node.js。Kotlin 和 Gradle 的版本由仓库版本目录、构建声明与 Wrapper 固定，不要求安装系统 Gradle 或 Kotlin 编译器。统一研发 CLI 与外部 MCP 业务试验由 Go 编写；生产和必需研发链路均不要求 Python、uv 或虚拟环境。

在仓库根执行：

```sh
go -C tools run ./cmd/olap-dev bootstrap
go -C tools run ./cmd/olap-dev build
go -C tools run ./cmd/olap-dev server-distribution --artifacts .tmp/dev-distribution
go -C tools run ./cmd/olap-dev test --distribution .tmp/dev-distribution
```

`build` 依次完成 JVM 构建、内部协议生成、构件记录和 Go 服务构建。构建声明只使用当前源码及已声明的依赖，不发现或装回归档代码。

| 工作 | 仓库根命令 |
| --- | --- |
| 生成 Go/Kotlin 共用的内部通信代码 | `go -C tools run ./cmd/olap-dev protocol` |
| 编译 Kotlin 与构建 JVM 构件 | `go -C tools run ./cmd/olap-dev jvm-build` |
| 构建 Go 服务程序 | `go -C tools run ./cmd/olap-dev service-build` |
| 构建完整生产代码 | `go -C tools run ./cmd/olap-dev build` |
| JVM 与 Go 行为试验 | `go -C tools run ./cmd/olap-dev test --distribution .tmp/dev-distribution` |
| 聚焦一个 JVM 试验 | `go -C tools run ./cmd/olap-dev test-jvm --jvm-test '完整类名.方法名'` |
| 聚焦 Go 试验 | `go -C tools run ./cmd/olap-dev test-go --distribution .tmp/dev-distribution --go-test '函数表达式'` |
| 服务发行目录 | `go -C tools run ./cmd/olap-dev server-distribution --artifacts .tmp/server-distribution` |
| 按正式坐标装配本地 SDK 验收构件 | `./project/gradlew -p project stageSdk` |
| 当前平台运行包与 npm 包 | `go -C tools run ./cmd/olap-dev binary --distribution .tmp/server-distribution --artifacts .tmp/binary` |
| 正式 MCP 业务用法 | `go -C tools run ./cmd/olap-dev example --example orders --distribution .tmp/server-distribution --artifacts .tmp/orders` |

`example` 可以明确选择 `orders`、`procurement`、`subscriptions` 或研发内核用法 `kernel`。需要独立输出根的命令要求目标尚不存在，消费时传入该次确切目录，不从旧产物或本机安装版本猜测输入。

## 源码、依赖与通信

`project/service` 使用一个 Go module，依赖由 `go.mod` 和 `go.sum` 固定。服务、运行监督、认证与结果字节交付分别按包归属。领域语义、授权和治理不在 Go 中复制。`tools` 是独立的研发 Go module，拥有构建、发行及正式 MCP 客户端试验，生产服务不依赖它。

Kotlin 模块由 `project/settings.gradle.kts` 统一登记，公共版本由 `project/gradle/libs.versions.toml` 声明。`kernel/` 保存通用计算，`upstream/` 保存治理、采用、发布与存储，`applications/` 保存应用装配及插件装载，`connectors/` 保存具体后端实现。集成试验位于 `project/modules/integration-tests/`，示例在 `examples/`。

`project/protocol/runtime.proto` 是内部通信的唯一来源。Gradle 生成两端代码；领域对象仍由 Kotlin 正式模型拥有，Protobuf 只承载实际进程通信。生成的 JVM Java 消息属于构建输出，手写 JVM 生产与试验源码使用 Kotlin。公开 Query、MCP 和插件配置继续使用各自正式合同。

Go 是唯一进程监督者，拥有 JVM 启动、工作池、期限、取消及关闭。Kotlin 元数据应用唯一持有权威 H2 库，工作进程只取得本次已授权的执行输入和来源环境。不要增加第二套进程池、发布授权或运行状态。

公开连接器 SDK、内部进程协议、产品发行版本和语义资产版本分别管理。JVM 构件使用 `com.github.hanger-source.agentic-olap` group，版本为 `v` 加 `project/cli/package.json` 的产品版本。SDK 由 JitPack 按源码仓库的精确发行标签构建；独立插件配置 JitPack 仓库和精确坐标，第三方依赖由 Maven Central 提供。SDK 构件公开下载，无需使用者令牌。插件通过 JVM ServiceLoader 注册，精确身份和类加载由公开装载器核验。

## 修改与验证

先明确本轮涉及的规则 owner、生产者、消费者和退出的旧路径，再把同轮生产与直接上下游一起写齐。完成后集中编译和类型诊断，修齐整族，再运行与真实变化相称的行为试验。不按微小编辑反复全量检查，不以覆盖率或绿色数量决定是否增加测试。

真实并发、失败、持久化、进程和权限边界优先复用已有试验；常量、字段映射、普通文档措辞及当前文件形状不单独冻结。测试的期望来自业务数据或独立可观察结果，不能由被测实现再生成同形答案。没有新的变化、失败或疑点时，不重复已取得的证据。

指标修改需要区分精确定义、所属主题域版本中的采用、发布执行实例和本次调用。定义依赖按精确 `MetricRef` 闭合，公开根按 `MetricInstanceRef` 选择，内部子调用沿实际位置派生绑定。共同模型由 `Calculation`、成员与数量合同各自拥有，不能为传输、测试方便再复制一套算法。

三个业务示例各自拥有原始数据、纯作者定义、查询和独立结果预期。Kotlin 准备者不写治理资产，外部客户端通过正式 MCP 完成调查、建设、发布、查询及结果读取。HTTP 来源试验只在 HTTP 服务端持有数据库，插件通过正式访问合同执行，并通过 MCP 显式登记结构。持续变化的数据、旧发布和旧结果分别保留各自含义。

评价 Agentic 使用时，观察业务 Agent 如何根据实际背景调查来源、澄清口径、建设或选择定义、组织完整查询和解释结果。确定性用法、普通编译与源码预写定义只能证明各自实际行为，不能替代自主业务使用的证据。

## 构件与发行

Gradle 的实际解析结果生成 `project/build/jvm-artifacts.json`；研发入口记录源码输入、精确构件、依赖与摘要。Go 构建记录保存自身源码、模块依赖和二进制摘要。记录中的 HEAD 是源码起点，未提交内容仍由输入摘要识别，不能冒称已提交版本。

服务发行清单为 `agentic-olap-distribution/v2`，包含一个 Go 服务程序和 JVM 主类的精确 JAR 列表。装配只消费同轮记录，运行时逐项核验路径与摘要，不扫描目录选取旧包。平台运行包另包含 JDK 21 工具生成的 JRE；用户通过 npm 安装后直接启动，无需源码或构建工具。

`project/cli/package.json` 拥有 npm 版本和精确平台依赖。平台包通过实际安装试验后才能作为发行证据；skill 直接从本仓库的 `skills/agentic-olap` 交付。源码、文档、skill、SDK 和发布工作流采用同一精确标签。构建、安装、源码 push 和公开发布是不同动作，不自动互相授予权限。

Gradle 构件清单的 `externalArtifacts` 记录已解析的第三方 JAR 坐标、Maven Central 地址、长度和摘要；npm 装配据此生成 `jvm-dependencies.json`，不在包内重复携带这些 JAR。`postinstall` 调用 Go 安装器并行下载、验证并复用内容缓存，填充运行 classpath 与内置插件组。完整源码研发发行目录仍直接使用 Gradle 构件，产品启动没有下载或依赖解析路径。

根 `jitpack.yml` 固定 JDK 21，并调用 `publishSdkToMavenLocal`，只构建和发布内核 SDK 及 `connector-loader`，不构建 Go 服务。SDK 模块及传递依赖由 Gradle 项目关系决定。`./project/gradlew -p project stageSdk` 将同版 SDK 按正式坐标装配到 `project/build/sdk/maven`，供隔离的独立插件构建验收；本地验收不能替代 JitPack 远程构建。

本仓库的手动 `构建 JitPack SDK` 工作流按已推送的发行标签请求公开源码构建，再确认 SDK 入口可以匿名下载。构建无需私有仓库访问令牌、JitPack 私有订阅、Sonatype 命名空间或 GPG 签名。先完成同版 SDK，再由本仓库的 `发布 Agentic OLAP` 工作流并行发布 npm 主包和 macOS arm64 运行包。完整顺序见[安装与发布](docs/installation-release.md)。

根 Apache 2.0 许可证适用于本项目。JVM 依赖保留实际 LICENSE/NOTICE，Go 依赖许可按实际编译依赖收集；分发资源保存在 `project/distribution-resources/`，构件清单与许可均随相应发行物交付。新增依赖或驱动需要核对自身分发内容。

阶段性提交对应写完整、编译通过并有相称证据的一批变化。精确暂存本批文件，保留并发或历史未提交内容；PR 说明问题、最终行为、验证和真实限制，不提交凭据、公司业务资料或私有运行轨迹。维护者显式决定 push 和公开发布。
