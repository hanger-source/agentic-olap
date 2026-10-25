# 研发命令

`tools` 是独立 Kotlin 研发客户端模块，消费真实发行与公开 MCP，不进入产品服务的 classpath。Gradle 负责构建与分发装配；环境与命令见[贡献说明](../CONTRIBUTING.md)。

业务作者程序通过公共 `Computations` 构造同一个 `Calculation.Scope`，研发客户端只提交程序并读取正式回执。对象人口用法的两个计数实例选择由独立消费会话核对；真实数量不匹配诊断来自已有日均算法的关系形参调用，单列保存在 `dailyInputContractMismatch`，不为具体计数调用伪造形式签名。

在仓库根运行：

```sh
./project/gradlew -p project assembleJvm
./project/gradlew -p project serverDistribution
./project/gradlew -p project :development:runtime
./project/gradlew -p project :development:bootstrap
./project/gradlew -p project :development:httpSource
./project/gradlew -p project :development:orders
./project/gradlew -p project :development:installedProduct
```

`project/buildSrc` 的分发任务消费 Gradle 实际构件与解析依赖，装配独立 classpath、连接器和 npm 产品；公开产品装配读取 JitPack 的实际字节，skill 直接由同仓源码交付。`src/main/kotlin` 中的开发程序持有已有业务作者程序、MCP 客户端、真实签名者和临时服务进程，通过公开边界观察行为。安装程序从实际 npm 包运行完整来源、定义、发布、查询、重启与大消息读取链。研发客户端不复制计算引擎或授权规则。

`bootstrap` 独立运行随包安装引导，从正式 Maven Central 下载 H2，执行下载后的 JAR 和实际生成的启动参数，并观察离线缓存、并发安装与错误摘要不落位。该组件试验不替代同标签 JitPack 构件和完整 npm 产品的安装验收。

`:development:runtime` 显式运行一组核心真实进程试验：HTTPS JWKS 与主体授权、单在途请求中的正式回调、REPL 历史及参数快照、多会话隔离、Seatbelt 文件与网络及外部程序访问拒绝、失控执行取消及进程和目录回收，以及 chunked 请求的实际字节预算。它消费同轮发行，默认将日志、MCP 调用和 `report.json` 写入 `.tmp/runtime`。这些试验属于研发入口，不进入产品业务流程。

`:development:httpSource` 复用现有 `HttpSourceAccessTest.main` 的真实 HTTP SQL fixture 和订单作者程序。准备者建立原始 DuckDB 数据后，fixture 独立持有数据库；正式服务只登记 HTTP 插件和来源。程序先观察 catalog 读取的明确能力拒绝，再显式登记结构，完成建设、发布、HTTP 查询、保存结果、重启及无来源回读。fixture 的 test classpath 由 `:integration-tests:prepareHttpSourceFixture` 生成，不进入产品运行依赖。默认输出根 `.tmp/http-source` 必须尚不存在，MCP 回执在 `http/report.json`。

上述用法均由维护者显式选择；构建不会自动执行运行试验或三个完整业务用法。运行与业务用法消费 `project/build/distribution`，安装用法消费 `project/build/npm/tarballs`；输出目录必须尚不存在，失败现场保留。`-Partifacts=/absolute/path` 可以指定输出目录，`-Ptarballs=/absolute/path` 可以指定安装包目录。已有 JVM 行为按 `:integration-tests:test --tests '完整类名.方法名'` 聚焦。
