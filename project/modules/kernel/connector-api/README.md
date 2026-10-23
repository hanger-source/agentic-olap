# 连接器公开接口

`connector-api` 为独立后端实现提供共同合同和 Calcite 1.42.0 的公开类型。它不依赖治理、服务应用或内部编译实现。连接器实现已定义语义的原生映射与来源访问，业务指标、对象资格、数量、NULL 与查询组织仍由语义定义和共同内核拥有。

SDK 按精确发行标签发布到 JitPack。`agentic-olap sdk` 返回本版 API、随包文档、Maven 仓库及 `artifacts` 中的完整坐标；`sdk --repository` 只输出仓库地址。构件使用 `com.github.hanger-source.agentic-olap` group，版本为 `v` 加产品版本，公开入口为 `connector-api`，可复用 JDBC 支持为 `warehouse-jdbc`，应用装载器为 `connector-loader`。使用命令返回的精确版本，不使用 SNAPSHOT。API 标识为 `agentic-olap-connector/v12-kotlin-native-collection-placement-calcite-1.42`。

SDK 的 JAR、POM 由插件工程的 Gradle 从 JitPack 匿名取得，第三方传递依赖从 Maven Central 取得。插件开发仍需 Kotlin/Gradle 工具链；运行产品无需这些构建工具。部署到其他服务前核对该服务的实际 API，不能把本地安装版本当作远程运行版本。

`OlapConnector` 使用 Kotlin 接口，通过 JVM ServiceLoader 注册；只读属性 `id`、`version`、`apiVersion`、`compilation` 声明实现与编译能力，`access(verifiedIdentity)` 接收装载器核验后的精确身份并返回来源访问实现。发现和纯编译不读取源凭据、不打开业务连接。原生类型、参数、转换、数值失败与标量解码按实际输入类型实现，不支持的映射明确拒绝。

完整服务的配置、精确绑定、显式结构登记、可选调查、结果交付与能力检查见[来源访问扩展](../../../../docs/source-access.md)。SDK 和插件必须匹配服务实际 API。`JdbcAccess : SourceAccess` 是可选 JDBC 扩展，通过只读属性 `jdbc` 提供 `JdbcSupport`；`JdbcSourceAccess` 实现该合同。通用服务依赖 `SourceAccess`，HTTP 等插件无需实现 `JdbcAccess`。

## 精确身份与部署

`ConnectorRef={id,version,sha256}` 标识完整插件组。`sha256` 由组内 JAR 的文件名与内容摘要按 manifest 顺序计算；每个条目依次输入 UTF-8 文件名、一个零字节和 SHA-256 摘要的 32 字节，再对整个序列取 SHA-256。插件自身不能在源码中嵌入自身 JAR 的哈希；部署装载器核验 JAR 后，构造 `ResolvedConnector` 和不可变 `ConnectorRegistry`。调用方按完整引用解析，不选择默认或最新版本。

部署每个插件为同一目录中的 manifest、插件 JAR 和该访问实现需要的依赖；HTTP 实现无需数据库驱动：

```json
{
  "connectorRef": {"id": "backend", "version": "1.0.0", "sha256": "完整插件组摘要"},
  "apiVersion": "agentic-olap-connector/v12-kotlin-native-collection-placement-calcite-1.42",
  "jars": [
    {"file": "connector-backend.jar", "sha256": "该JAR摘要"},
    {"file": "backend-jdbc.jar", "sha256": "该JAR摘要"}
  ]
}
```

摘要使用 64 位小写十六进制。宿主提供共同合同、连接器 API 和 Calcite，插件不打包这些宿主类。JDBC 插件自行携带 JDBC 实现；仓库参考插件将共同实现重定位到 `com.olap.connectors.jdbc` 后打入插件 JAR。插件实现使用自己的包名，例如 `com.olap.connectors.backend`。独立插件组的类与原生资源从已核验 JAR 读取，宿主同名驱动不能遮蔽它们。

应用侧 `connector-loader` 的唯一装载入口是 `ConnectorPlugins.load(List<Path>)`，接收明确的绝对 manifest 路径列表。完整服务从部署配置的 `connectors` 数组取得路径，Go 按本次工作所需的精确来源将对应列表交给 Kotlin 工作进程，由后者装载并持有资源；应用关闭时关闭装载器。业务请求不接受插件路径。重复精确身份、API 不匹配、字节摘要不符或缺驱动在装载时失败。

同一连接器 ID 的不同版本或不同构件摘要可以并存，各插件组使用独立目录。源码装配入口按输入序号隔离目录并返回准确 manifest 列表，发行归档沿该列表收集插件组；目录名不代替精确 `ConnectorRef`，不能只按 ID 推导路径或选择某个版本。

## 消费与责任

`ConnectorRegistry.require(ref)` 返回精确 `ResolvedConnector`。编译消费者使用其 `compilation`，来源消费者使用其 `access`；不能按方言名称重新选择实现，也没有全局当前后端。数据源连接身份、物理采用证据、冻结执行绑定和实际编译结果保留相同连接器引用，执行时核对部署与冻结内容一致。

`CompilationSupport` 提供 `SqlDialect`、`RelDataTypeSystem`、JDBC 类型映射、表坐标、参数、精确文本身份比较、类型化集合展开及原生数值操作。`matchesNativeResult(NativeValueType, JdbcColumnType)` 核验来源响应或显式合同提供的 SQL 标量类型能否承载编译类型；原始观察元数据保持原值，DuckDB 的 HUGEINT 等专属匹配由对应插件拥有。共同编译器继续组织关系与阶段、进行类型和数量分析并生成 SQL，内核保留结果容器形态、NULL 与逐值数值约束。

`nativeAggregate(SqlKind)` 返回该精确来源的 `SqlAggFunction`，包括真实返回类型推导。核心将已定义的聚合映射为原生 kind，并继续组织 DISTINCT、分组、窗口和数量保证；插件不接收业务指标公式。不支持的 kind 明确拒绝。例如 `STDDEV_SAMP` 的返回类型由该来源实现声明，不能由共同编译器统一假定为 DOUBLE。

只读属性 `CompilationSupport.parameterSubmission` 必须明确声明 `BIND_PARAMETERS` 或 `TYPED_LITERALS`。前者保留有序参数，后者通过 `parameterLiteral` 将已校验值映射成 SQL AST 字面量并保留编译类型 CAST；共同默认映射覆盖参数编码中的精确数值、文本、布尔与时间值，后端特殊映射由插件明确实现。主查询、关系检查和候选值均使用该选择，不做问号文本替换或运行失败后的自动切换。

`CORRELATED_DERIVED_PROJECTION` 声明派生关系的投影能引用外层查询值。普通 `EXISTS` 条件相关不要求这项能力；数量合同的行内求值槽若必须把外层人口条件绑定在派生投影中，则按本次实际构造检查。缺失时在提交 SQL 前抛出 `NativeCompilationCapabilityException`，公开错误 `NATIVE_COMPILATION_CAPABILITY_MISSING` 包含 `capability` 与 `operation=NUMERIC_EVALUATION_BINDING`。插件依据真实后端行为声明，它不代表来源事务或快照能力。内核不删除人口保护、不重复内联检查，也不在执行失败后切换方案。DuckDB 声明此能力；当前 H2 不声明。

主查询和候选值读取都通过 `SourceAccess.execute` 接收完整 `SourceSqlRequest`。候选结果的单列、类型和分页边界由候选应用核验；插件按同一执行合同交付结果，不实现单独的候选查询方法。

`execute` 的阶段耗时回调为 Kotlin 函数类型 `stageNanos: (String, Long) -> Unit`，参数分别是阶段名称和耗时纳秒。实现直接调用 `stageNanos(name, elapsedNanos)`；调用者可传 lambda 或函数引用。插件应报告实际执行阶段，异常与资源关闭仍由执行合同处理。

## 原生标量函数

只读属性 `CompilationSupport.nativeScalarFunctions` 声明该精确插件可调用的只读原生函数。每项 `NativeScalarFunction` 包含 `id`、有序 `parameters`、标量 `resultType`、`arithmetic` 和 `stability`。参数种类为 `SCALAR` 或 `PHYSICAL_TABLE`；只有标量参数填写 `scalarType`。数值结果必须声明算术保证，非数值结果的 `arithmetic` 为 NULL；函数不能返回 ARRAY 或 ROW。原生浮点结果不能声明精确算术。

`MetricValueContract.Arithmetic` 区分 `EXACT_DECIMAL`、带结果域及误差容差的 `NATIVE_APPROXIMATION`，以及不声明结果域或误差界限的 `NATIVE_FLOATING`。后者仍核验容量、有限值及溢出，不能经 CAST 或 DECIMAL 结果类型取得输入没有的精确性或容差保证。插件的原生结果类型与算术保证必须分别如实声明。

表达式 `NATIVE_CALL` 保存精确 `connectorRef`、函数 `function` 和 `arguments`。`SCALAR` 参数携带完整 `expression`；`PHYSICAL_TABLE` 参数携带 `physicalTableId/physicalTableVersion`，它是需要冻结与核验的真实物理依赖，不是表名字符串或任意 SQL。`nativeScalarCall` 只负责把核心校验后的 `ScalarArgument(SqlNode)`、`TableArgument(qualifiedPath)` 映射为后端 SQL AST，不接管业务关系规划。

`SourceSqlRequest.sourceRequirements` 的 `uses` 明确区分 `ROWS` 与 `NATIVE_TABLE`，同一表可以同时具有两种用途。插件保留两者的精确来源和表身份核对；行约束与有限集合核验只对实际行读取要求执行。原生函数随本次 SQL 求值，不在客户端预取结果。

稳定性显式区分 `IMMUTABLE`、`STATEMENT`、`VOLATILE`，不能仅凭函数名把调用视为可折叠常量或可共享计算。精确物理表版本不冻结其数据，读取表内容的函数须按真实依赖声明稳定性。函数声明不授予额外来源、物理表或查询输出权限；发布与本次执行仍核对精确插件、来源绑定及数量合同。服务的 `read-connector-capabilities` 按当前主体可访问来源返回 `connectorRef`、`nativeScalarFunctions`、`compilationCapabilities`、`parameterSubmission` 和 `configurationParameters`，调用者以实际声明和表达式合同组织调用。没有原生函数的插件显式返回空列表，不从后端产品名推测函数支持。

该操作也接受 `aggregate={connectorRef,aggregation,inputType,grouped}`，用现有 `typeSystem` 与 `nativeAggregate` 推导精确输入类型的原生聚合结果，不要求另一份聚合支持表。AVG 复用编译时的 `deriveAvgAggType`、原生函数容量一致性核验及 `decimalAverageRounding`；无法兑现时返回 `UNAVAILABLE`，不猜返回类型。原生映射成功才披露适用的 `aggregationRounding`，它不等于业务最终结果的舍入。插件的这些声明必须纯粹、确定，不连接来源或依赖运行参数；实际后端映射及证据仍由插件维护。正式请求和响应见[来源合同](../../../../docs/source-access.md)。

## 来源访问与结果

`SourceAccess.configurationParameters` 用 `SourceConfigurationParameters(identityNames,runtimeNames)` 声明参数角色。配置分别提交 `identityParameters/runtimeParameters`；来源摘要只纳入前者和精确插件、方言、凭据槽。预算或回执目录不应改变业务数据身份，影响数据选择、比较或类型语义的设置也不能伪装成运行参数。未知键和错放角色由核心拒绝，必填值与值域仍由插件校验。

`SourceAccess.execute` 返回 `SourceExecutionResult(frame,readBasis,request)`。`request` 以受控字段表达来源请求身份、接受、响应和执行结局；失败通过 `SourceExecutionException` 保留安全 `SourceFailureEvidence`。`WarehouseExecutionControl.cancellationEvidence()` 区分未请求、未确认、确认和失败；只有来源确认回执才能证明远端取消。原始协议错误留在私有 cause，不把任意 headers、SQL 或响应正文塞进公开证据。

`readBasis` 如实为 Unknown 或 Versioned；来源支持强制精确数据版本时才声明 PINNED_READ 并落实 `SourceSqlRequest.expectedReadBasis`。返回基础必须与要求相符，不能忽略 pin 后读取最新。分区名或访问绑定本身不证明内容不可变，也不保证历史版本永远可用。

`SourceAccess.supportsConstraintVerification` 表达对冻结证据的约束核验能力。访问层的 `CONSISTENT_READ` 表达检查与计算针对一致数据状态成立，具体可以使用事务快照、原子 SQL 或不可变数据版本；不把 `TRANSACTION_SNAPSHOT` 作为所有实现的前置。声明能力不能替代实际执行每项必需检查，空结果也不能吞掉保证失败。

集合结果、逐项展开、排序和文本显示分别声明 `COLLECTION_RESULTS/EXPANSION/SORT/TEXT` 能力。`sortCollection` 映射明确的方向及 NULL 顺序，`collectionText` 映射明确的分隔符与 NULL 替代；不能把数组提前解码成字符串代替这些运算。

`expandCollection(outputType,tableAlias)` 在 Rel 转 SQL 时返回 `CompilationSupport.CollectionExpansion` 的原生放置计划。`outputType.fieldList` 依次声明完整元素、从 1 开始的位置两列的名称和类型，保留 ROW 字段顺序、递归类型及 NULL 性质。两种计划分别对应原生 FROM 与输入投影 SRF；精确插件在编译时明确选择，不在来源执行失败后切换。

- `CollectionExpansion.Relation(generate)`：核心将当前输入行的 ARRAY 引用交给 `generate`，取得 `CollectionRows(relation,element,ordinal)`。`relation` 是使用给定 `tableAlias` 的原生 FROM AST；核心构造 CROSS／LEFT JOIN、完整输入透传与输出投影。必要的表函数 LATERAL 语法由插件映射。
- `CollectionExpansion.InputProjection(generate,values)`：核心在完整输入上添加恰好一个 `generate(array)` 返回的 SELECT-list SRF，并将生成值绑定为内部列；下一层调用 `values(array,generated)` 取得 `CollectionValues(element,ordinal)`。两个回调只使用各自拿到的当前作用域引用，不能捕获另一层的字段别名。原生生成值可以是下标或完整有类型值，不能将多个独立 SRF 放入同一生成表达式。

例如，位置生成器的原生下标可由插件映射为 `element=array[index]`，`ordinal=index-array_lower(array,1)+1` 并按输出整数类型定型。FROM 表函数和 SELECT-list SRF 的放置由各自计划表达；插件不需要解析核心别名、取得完整输入关系、复制聚合或改写生成后的 SQL。原生类型、下标取值与数组下界由插件负责，不宣称任意后端都具有相同语法或能力。

内核拥有输入人口、输出投影和 INNER／OUTER：NULL 数组或空数组在 INNER 下无记录，在 OUTER 下补一个 NULL 元素及 NULL 序号；实际 NULL 元素有自己的非 NULL 序号，重复元素不丢弃。FROM 计划由核心 LEFT JOIN 并依据真实位置的非空性补整元素 NULL。输入投影计划的 OUTER 另需明确声明 `COLLECTION_PROJECTION_OUTER`：核心用标准 CARDINALITY 绑定存在标记，将 NULL／空数组替换为仅含一个 typed NULL 的同元素类型数组；生成后按存在标记将整个元素和序号置 NULL，原始输入列仍原样透传。该能力要求插件的原生类型与方言兑现 CARDINALITY、typed singleton ARRAY、CASE 和完整 ROW NULL 的组合；接受的类型超出保证时明确拒绝。缺少能力时在提交来源前返回 `NATIVE_COMPILATION_CAPABILITY_MISSING`、`capability=COLLECTION_PROJECTION_OUTER`、`operation=EXPAND_COLLECTION_OUTER`，不将 INNER 非空样本当作 OUTER 证明。

序号从 1 开始，实际元素的 `ordinal` 必须非 NULL；不直接暴露原生数组下界，不凭元素或所有 ROW 字段为 NULL 推断缺行。映射不得自行过滤、去重、归约、复制输入或改变求值作用域；生成与取值针对同一条输入记录的同一绑定数组。运行验证须覆盖真实表行与聚合输入、空／NULL 数组、整体 NULL ROW、字段全 NULL 的非空 ROW、重复元素及位置规范化。其他后端上的通用 fixture 或常量数组成功不替代目标后端的正式 Query 验收。

零条输入关系与一条输入记录上的 NULL／空数组是不同人口。带分组键的聚合在零条输入时不产生分组；集合聚合的 typed NULL 分支保留该聚合声明的空输入含义，不会创建分组。INNER 展开不制造输入记录，OUTER 只保留实际存在的输入记录。插件不能用预先 COUNT、跳过执行、额外分组、删除透传列或外围 SQL 改写来掩盖来源错误。

`COLLECTION_RESULTS/EXPANSION/SORT/TEXT` 与投影式 OUTER 的能力缺失均在来源提交前使用 `NATIVE_COMPILATION_CAPABILITY_MISSING` 拒绝，保留实际缺少的 `capability` 和触发编译的 `operation`。插件声明支持后，原生 ARRAY／ROW 类型、聚合结果描述符、SRF 放置与字段读取仍须共同成立；`InputProjection` 提供放置入口，不代替来源的类型保证。

调查原生描述符或集合执行错误时，沿同一次正式请求保留插件及来源的精确引用、完整出站 SQL 与实际参数、来源请求身份、原生错误阶段与 SQLSTATE，以及来源实际提供的读基础。类型定位以传入 `expandCollection` 的有序 `outputType`、ARRAY 的完整 `componentType`、`RexFieldAccess.referenceExpr.type/field.index` 和插件的 cast／字段映射为准；需要来源执行计划或原生类型描述符时，由连接器取得来源证据。不同请求在 Unknown 读基础下观察到的空人口、非空成功或同 SQL 的不同结果，不能单独证明核心类型错误、原生类型普遍不可用或唯一的空人口根因。

`ROW_FIELD` 的逻辑字段索引仍由内核验证。需要原生匿名字段名或专用字段运算的插件，可以在 `SqlDialect.prepareUnparse(RexNode)` 处理 `RexFieldAccess`：此时保有 `referenceExpr.type`、`field.index` 和结果类型，再转换为插件自己的有类型 Rex 调用及 SQL AST。不要等普通 SQL 标识符已经生成后解析 `EXPR$N`，也不能把逻辑字段名普遍替换成某个后端的 `fN`。

`textIdentityEquals(builder,left,right)` 返回两个真实文本操作数的完整精确身份 BOOLEAN 比较，保留 NULL 三值逻辑，不要求产生二进制中间值。原生等号只有在插件能够兑现类型、编码及 collation 的适用保证时才可直接使用。后端是否保留 HashJoin、过滤下推及运行时过滤，由实际执行计划确认；此映射不授予插件改写业务人口、LEFT 补空或重复度的权限。

插件构造原生 Rex 函数时，应通过 Calcite 的正式运算符合同如实披露 NULL 传播。仅声明可空返回类型不足以证明输入 NULL 时结果必为 NULL；严格传播且非 NULL 输入不产生 NULL 的函数可通过 `SqlOperator.getStrongPolicyInference()` 返回 `Strong.Policy.ANY`。内置 DuckDB `ENCODE`、H2 `RAWTOHEX/STRINGTOUTF8` 按实际编码函数语义声明这一性质，SQL 与精确身份比较保持原样。核心据此分析过滤条件排除 NULL 的事实；函数语义未知或证明不完整时，保留运行关系检查，不能用声明跳过人口、NULL、重复度或数量保证。

lowering 结果在本次计算内持有与精确引用对应的同一编译支持，结果转换显式使用该支持；它不写入发布、JSON 或另一份原生类型表。连接器不能用不透明业务 SQL 替换完整查询。

`JdbcSupport` 在明确调用时取得连接描述与凭据，拥有只读连接、原生内存配置、快照建立、元数据约束、封存锚点、参数绑定及标量解码。`arrayElement(Array, ResultSet)` 解码已定位的实际数组元素；规范 JDBC 实现可复用第2列的元数据，缺少该元数据的原生游标由插件明确解码。共同物化器保留递归容器和共享预算，不将元素转成文本。普通在线源无需宣称封存能力。连接和语句由实际调用方关闭，取消沿 `WarehouseExecutionControl` 传递；插件关闭时注销自己装载的驱动。`warehouse-jdbc` 的标准支持代码可复用，具体后端插件不依赖 `semantic-calcite` 内部实现。

`sourceAnchor` 的取消检查参数为 `checkCancellation: () -> Unit`；封存核验在开始及分块读取过程中调用它，不能将长时间读取变成不可取消的任务。

公开 `NativeValueType` 为数组和 MULTISET 保存必需的递归 `elementType`，为 ROW 保存按实际序号排列的 `fields` 技术类型；不复制业务输出形态或物理字段名。标量保持 `name/precision/scale/nullable` 四个字段。实际数组缺少元素类型时明确拒绝，不按历史内容或样本猜测。

仓库中的 `connector-h2` 与 `connector-duckdb` 为两个独立实现。它们的存在不代表其他 JDBC 后端已经具有相同的数值、时间、集合或源一致性支持。

使用 DuckDB 等原生 JDBC 驱动时，JRE 临时目录需要允许驱动解包和加载共享库。容器采用只读根文件系统并单独挂载 `/tmp` 时，应为该目录明确配置可写及可执行映射；`noexec` 会使原生库加载失败。普通纯 Java JDBC 驱动不因此获得额外的原生运行依赖。

## 独立插件工程

独立 Kotlin 插件工程从 SDK 仓库声明宿主提供的公开构件，例如 Gradle Kotlin DSL：

```kotlin
plugins { kotlin("jvm") version "2.2.21" }

repositories {
    mavenCentral()
    maven("https://jitpack.io") {
        content { includeGroup("com.github.hanger-source.agentic-olap") }
    }
}
// 使用 agentic-olap sdk 返回的精确坐标。
dependencies { compileOnly("com.github.hanger-source.agentic-olap:connector-api:v0.11.0") }
kotlin { jvmToolchain(21) }
```

使用 `StandardJdbcSupport` 或标准约束读取时，声明同版本 `warehouse-jdbc`，将其实现重定位并私有打入插件 JAR；宿主不提供这份 JDBC 实现。仓库参考插件的 Gradle Shadow 配置只合入 `warehouse-jdbc`，将 JDBC 包及对应调查实现重定位到 `com.olap.connectors.jdbc`，保留嵌套类和包内访问关系。独立插件使用自己的私有前缀，不重定位或打入共同合同、Kotlin runtime、连接器 API 或 Calcite。跨插件边界使用公开 `SourceAccess` 或 `JdbcAccess`，不能强转宿主 JDBC 实现。

具体 JDBC 驱动由插件工程声明，并作为插件组内依赖 JAR 交付。该工程使用自己的构建与实际后端行为试验；宿主 API、Calcite、Kotlin runtime 和共同合同不合入插件 JAR。独立工程不加入产品源码目录，也不依赖 `semantic-calcite` 内部类。实现字节或驱动改变后重新计算真实插件组摘要，不能沿用旧 `ConnectorRef`。

实现 `OlapConnector` 的公开无参 provider，在 `META-INF/services/com.olap.prototype.connector.OlapConnector` 中写入其完整类名。provider 声明自身 id/version，发行装配对实际插件及驱动 JAR 计算摘要，形成 manifest。驱动缺失、API 不匹配、重复精确引用和摘要错误由真实装载失败检查；原生数值、时间、集合、NULL 与源保证仍需在对应实际数据库上取得行为证据。

应用装载用法及生命周期见 [connector-loader](../../applications/connector-loader/README.md)。
