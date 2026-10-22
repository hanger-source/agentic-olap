# 连接器部署装载

`com.github.hanger-source.agentic-olap:connector-loader` 通过 JitPack 发布，属于应用装配边界，精确版本由本版 `agentic-olap sdk` 披露。它消费 [connector-api](../../kernel/connector-api/README.md)，拥有部署 manifest、JAR 字节核验、独立 classloader 与关闭责任。内核只取得显式解析的实现，不扫描部署目录。

部署列表是一个 JSON 数组，每项为插件 manifest 的绝对路径：

```json
["/deployment/connectors/backend/manifest.json"]
```

Kotlin 应用显式装载并持有资源：

```kotlin
ConnectorPlugins.load(listOf(Path.of("/deployment/connectors/backend/manifest.json"))).use { plugins ->
    val connector = plugins.registry.require(exactConnectorRef)
    val compilation = connector.compilation
    val access = connector.access
    // 同一精确实现提供 SQL 映射和来源访问，具体传输由插件拥有。
}
```

`ConnectorPlugins` 位于 `com.olap.prototype.connector.loader`。`load(List<Path>)` 接收明确的 manifest 列表。完整服务从部署配置读取这些路径，Go 按本次工作所需的精确来源启动工作进程，Kotlin 装载对应插件；业务工具不接受 manifest 路径或插件文件。

每个 manifest 的 JAR 位于其所在目录，文件名不得包含目录分隔符；实际路径不能越出该组。装载器核每个 JAR 的 SHA-256、完整组摘要、API 版本与 provider 身份，每组只允许一个 provider，注册表拒绝重复精确引用。装载器取得 CompilationSupport 和 SourceAccess；JDBC 实现自行核验驱动，HTTP 实现不要求驱动。发现与编译阶段不连接业务源。

JDK、Kotlin runtime、`com.olap.prototype` 共同合同、Calcite、Guava 和 SLF4J 由宿主提供；插件实现、驱动类及其资源从自己的已核验 JAR 读取。通用宿主不负责提供 JDBC 实现。仓库的 H2/DuckDB 插件在构建时将 `warehouse-jdbc` 实现重定位到插件私有的 `com.olap.connectors.jdbc`，随插件 JAR 交付；共同合同保持宿主类型身份。宿主已有同名驱动不能替代缺失依赖。插件 JAR 不能携带另一份共同合同、Kotlin runtime 或 Calcite 类。

装载成功后，调用方将不可变 `ConnectorRegistry` 显式传入需要的 library 或进程装配；相同 id 的不同版本或不同字节只能按各自完整 `ConnectorRef` 使用。来源绑定、发布与执行不因另一个插件组装载而改变。

实际连接、语句和取消注册由创建它们的调用方收尾，停止这些调用后再关闭插件装载器。`close()` 先关闭各插件来源访问实现，再关闭其 classloader。仓库的 H2/DuckDB 实现注销本组注册的 JDBC 驱动，并拒绝关闭后的新连接；装载失败也关闭已经建立的 classloader。
