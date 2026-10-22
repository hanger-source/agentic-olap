# 安装与发布

源码、skill、JVM 构建与 npm 发行共用本仓库。服务使用一个 npm 包安装；自有 JVM 产品构件和 SDK 由 JitPack 交付，第三方 JVM 依赖来自 Maven Central。

## 安装与启动

```sh
npm install --global @hanger-source/agentic-olap
agentic-olap start
```

需要 Node.js 20 或更高，支持 macOS arm64。npm 包携带 Go 程序、JRE、精确下载清单、SDK 文档、插件声明和许可。命令直接指向 Go 程序，没有平台子包或 JS 启动壳。

安装脚本调用随包 Go 程序，从 JitPack 取得同标签 Kotlin 运行构件和内置连接器，从 Maven Central 取得第三方 JVM 库与 H2、DuckDB 驱动。产品及依赖 JAR 均不打进 npm 归档。下载按锁定 URL、大小和 SHA-256 校验，备齐 classpath 和插件组；相同内容可复用缓存。下载或校验失败使安装失败，不接受缺失依赖的安装结果。

安装完成即可启动。启动只核验和使用本地文件，不下载或解析依赖。无需安装 JDK、Gradle、Go，也不需执行第二条安装命令。缓存、代理、配置和升级操作见[产品安装说明](../project/cli/README.md)。升级不会自动重启服务、改写业务配置或转换持久资产格式。

## 独立连接器 SDK

`agentic-olap sdk` 只读取随包声明，返回 API、文档、JitPack 仓库和精确 Maven 坐标。插件工程配置：

```kotlin
repositories {
    mavenCentral()
    maven("https://jitpack.io") {
        content { includeGroup("com.github.hanger-source.agentic-olap") }
    }
}
dependencies {
    compileOnly("com.github.hanger-source.agentic-olap:connector-api:v0.11.3")
}
```

坐标以目标服务的实际声明为准。自有模块的 JAR 和 POM 来自 JitPack，第三方依赖来自 Maven Central。无需下载令牌、查找 GitHub 归档或手工解压。共同合同、Kotlin runtime 和 Calcite 不打入插件 JAR，具体部署边界见[连接器 API](../project/modules/kernel/connector-api/README.md)。业务后端的协议、鉴权、原生类型及 SQL 映射仍由独立插件负责。

## 维护者构建与发布

`project/cli/package.json` 拥有产品版本、命令入口及平台限制。自有 JVM 构件的 group 为 `com.github.hanger-source.agentic-olap`，版本为 `v` 加产品版本；npm 版本不带 `v`。连接器 API、存储格式、Query 格式和语义资产版本分别管理。

1. 完成代码、文档和相称验证后提交，推送精确发行标签 `vX.Y.Z`。源码、skill 和构建均采用该标签。
2. 手动运行 **构建 JitPack JVM 构件**，指定标签。JitPack 使用 JDK 21 执行 `publishProductToMavenLocal`，发布服务运行模块、内置连接器、SDK 及其模块依赖，不编译 Go 或打包 npm。测试和示例模块不发布。隔离连接器 JAR 使用 `plugin` classifier。
3. 手动运行 **构建 npm 产品包**，指定同一标签。装配读取 Gradle 的实际依赖关系，从 JitPack 取得自有运行 JAR，以远端实际字节生成 classpath、插件身份和下载清单。第三方依赖使用 Gradle 已解析的坐标与摘要。本地重编译字节不能代替远端构件。输出一份 npm tarball 和 `SHA256SUMS`，并通过实际安装、MCP 建设与发布、真实来源查询、结果保存和重启读取验收。
4. 为该标签建立 draft release，上传同轮 tarball 和 `SHA256SUMS`。skill 直接使用标签中的 `skills/agentic-olap`，不生成或同步另一份仓库。
5. 手动运行 **发布 Agentic OLAP**，指定 draft 标签。工作流核验实际归档、文件摘要和完整安装清单，通过 GitHub OIDC 发布一个 npm 产品包，成功后公开 GitHub release。npm 接受提交即记录结果，不轮询 registry。若 npm 已受理而公开 release 失败，直接修正 release 状态，不重复提交 npm。

JitPack 构件完成后才能装配 npm，不能预先猜测远端 JAR 摘要。源码 push、JVM 公开、安装验收和 npm 发布各有实际结果，不能相互替代。三个完整业务用法按需手动运行，不加入 push／PR 的重复业务执行。

```mermaid
flowchart TD
    S[精确源码和标签] --> J[JitPack 发布 JVM 产品和 SDK]
    J --> B[取得实际远端字节，装配 npm]
    B --> V[实际安装与完整服务使用]
    V --> D[同标签 draft release]
    D --> P[OIDC 发布一个 npm 包]
    P --> I[使用者 npm install]
    I --> C[取得并校验全部运行 JAR]
    C --> R[本地文件齐备后启动服务]
```

## 外部发布配置

JitPack 直接读取公开仓库标签，使用免费公开构建，不需要私有订阅或访问令牌。`@hanger-source/agentic-olap` 的可信发布者绑定 GitHub 用户 `hanger-source`、仓库 `agentic-olap` 和工作流文件 `release.yml`，使用短期 OIDC 权限，不保存长期 npm 令牌。

自有 JVM 构件不向 Maven Central 发布，不需要 Sonatype 账号、命名空间或 GPG 签名。第三方构件直接使用其 Maven Central 地址，不重新上传。JitPack 构建约定见[官方说明](https://docs.jitpack.io/building/)。

`./project/gradlew -p project stageSdk` 将 SDK 装配到 `project/build/sdk/maven`，供独立插件工程验证传递依赖；本地构建不能代替远端发布。研发服务发行目录直接使用 Gradle 构件，npm 产品装配读取 JitPack 构件；它们是研发构建与公开交付的不同产物，均不在启动时下载依赖。
