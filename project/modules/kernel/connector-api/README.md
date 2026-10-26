# 连接器公开接口

`connector-api` 为独立后端实现提供共同合同和 Calcite 1.42.0 的公开类型。它不依赖治理、服务应用或内部编译实现。连接器实现已定义语义的原生映射与来源访问，业务指标、对象资格、数量、NULL 与查询组织仍由语义定义和共同内核拥有。

SDK 按精确发行标签发布到 JitPack。`agentic-olap sdk` 返回本版 API、随包文档、Maven 仓库及 `artifacts` 中的完整坐标；`sdk --repository` 只输出仓库地址。构件使用 `com.github.hanger-source.agentic-olap` group，版本为 `v` 加产品版本，公开入口为 `connector-api`，可复用 JDBC 支持为 `warehouse-jdbc`，应用装载器为 `connector-loader`。新建或确需重新构建的插件使用目标宿主命令返回的精确坐标，不使用 SNAPSHOT。API 标识为 `agentic-olap-connector/v18-kotlin-native-scalar-expression-calcite-1.42`。插件须实现该 API 的实际公开签名，不能仅改 manifest 的版本标签。

SDK 的 JAR、POM 由插件工程的 Gradle 从 JitPack 匿名取得，第三方传递依赖从 Maven Central 取得。插件开发仍需 Kotlin/Gradle 工具链；运行产品无需这些构建工具。部署到其他服务前核对该服务的实际 API，不能把本地安装版本当作远程运行版本。

产品版本或 SDK 坐标变化本身不要求重新构建插件。同一连接器 API 下，插件实际使用的公共 ABI 及 JVM、Kotlin、Calcite 要求仍满足时，可以沿用原完整插件组、manifest、`ConnectorRef` 和来源绑定。装载器核验精确字节与接口身份，不比较插件构建时的产品版本；API 字符串相同或装载成功，也不单独证明所有延迟执行分支可用。真正更换构件字节时，生成新的精确引用及来源绑定，为未变的数据合同登记新兑现采用，再由新发布明确选择其引用；不能覆写旧字节或自动切换已有发布。数据合同或原生函数的精确依赖发生变化时，才修订相应定义。

`OlapConnector` 使用 Kotlin 接口，通过 JVM ServiceLoader 注册；只读属性 `id`、`version`、`apiVersion`、`compilation` 声明实现与编译能力，`access(verifiedIdentity)` 接收装载器核验后的精确身份并返回来源访问实现。发现和纯编译不读取源凭据、不打开业务连接。原生类型、参数、转换、数值失败与标量解码按实际输入类型实现，不支持的映射明确拒绝。

完整服务的配置、精确绑定、显式结构登记、可选调查、结果交付与能力检查见[来源访问扩展](../../../../docs/source-access.md)。SDK 和插件必须匹配服务实际 API。`JdbcAccess : SourceAccess` 是可选 JDBC 扩展，通过只读属性 `jdbc` 提供 `JdbcSupport`；`JdbcSourceAccess` 实现该合同。通用服务依赖 `SourceAccess`，HTTP 等插件无需实现 `JdbcAccess`。

## 精确身份与部署

`ConnectorRef={id,version,sha256}` 标识完整插件组。`sha256` 由组内 JAR 的文件名与内容摘要按 manifest 顺序计算；每个条目依次输入 UTF-8 文件名、一个零字节和 SHA-256 摘要的 32 字节，再对整个序列取 SHA-256。插件自身不能在源码中嵌入自身 JAR 的哈希；部署装载器核验 JAR 后，构造 `ResolvedConnector` 和不可变 `ConnectorRegistry`。调用方按完整引用解析，不选择默认或最新版本。

部署每个插件为同一目录中的 manifest、插件 JAR 和该访问实现需要的依赖；HTTP 实现无需数据库驱动：

```json
{
  "connectorRef": {"id": "backend", "version": "1.0.0", "sha256": "完整插件组摘要"},
  "apiVersion": "agentic-olap-connector/v18-kotlin-native-scalar-expression-calcite-1.42",
  "jars": [
    {"file": "connector-backend.jar", "sha256": "该JAR摘要"},
    {"file": "backend-jdbc.jar", "sha256": "该JAR摘要"}
  ]
}
```

摘要使用 64 位小写十六进制。宿主提供共同合同、连接器 API 和 Calcite，插件不打包这些宿主类。JDBC 插件自行携带 JDBC 实现；仓库参考插件将共同实现重定位到 `com.olap.connectors.jdbc` 后打入插件 JAR。插件实现使用自己的包名，例如 `com.olap.connectors.backend`。独立插件组的类与原生资源从已核验 JAR 读取，宿主同名驱动不能遮蔽它们。

应用侧 `connector-loader` 的唯一装载入口是 `ConnectorPlugins.load(List<Path>)`，接收明确的绝对 manifest 路径列表。完整服务从部署配置的 `connectors` 数组取得路径，主服务按本次工作所需的精确来源将对应列表交给工作进程，由后者装载并持有资源；应用关闭时关闭装载器。业务请求不接受插件路径。重复精确身份、API 不匹配、字节摘要不符或缺驱动在装载时失败。

同一连接器 ID 的不同版本或不同构件摘要可以并存，各插件组使用独立目录。源码装配入口按输入序号隔离目录并返回准确 manifest 列表，发行归档沿该列表收集插件组；目录名不代替精确 `ConnectorRef`，不能只按 ID 推导路径或选择某个版本。

## 消费与责任

`ConnectorRegistry.require(ref)` 返回精确 `ResolvedConnector`。编译消费者使用其 `compilation`，来源消费者使用其 `access`；不能按方言名称重新选择实现，也没有全局当前后端。物理版本只拥有稳定数据合同，所选兑现采用、冻结执行绑定和实际编译结果保留精确连接器引用，执行时核对部署与冻结内容一致。

## 数据合同与兑现采用

API v18 的来源输入分为两个明确对象：

| 对象 | 内容与责任 |
| --- | --- |
| `PhysicalDataContract` | 精确物理版本 P、稳定来源和表坐标、按列名配对的精确列版本与 SQL 类型／NULL、约束、快照唯一规则及 anchor、封存集合的原完整声明。P 不选择连接器、方言或结构快照。 |
| `PhysicalSourceAdoption` | 发布明确选定的不可变采用，包含采用引用、P 引用、精确 `SourceBindingRef`、连接器、方言、表坐标及具体结构快照 S 的兑现证据。 |
| `PhysicalSourceEvidence` | S 的精确身份、结构出处、观察摘要与所选声明列类型，以及实际数据库／驱动观察、约束、快照唯一证明 ID 和该绑定的集合登记。它不等于本次执行已经核验。 |

`SourceAccess` 的纯能力声明签名为：

```kotlin
fun supportsConstraintVerification(
    dataContract: PhysicalDataContract,
    adoption: PhysicalSourceAdoption
): Boolean
```

该声明同时取得 P 的要求和所选实现的证据，不连接来源，也不从 P 原结构出处推导执行绑定。`SourceAccess.execute` 的 `SourceSqlRequest.sourceRequirements` 每项持有 `dataContract`、`adoption`、本次实际 `columns`、`uses` 与 `constraintUses`。`dataSourceId/connectorRef/dialect/table/bindingRef()` 来自所选采用；`snapshotUniqueKeys` 来自 P，`sourceCollections` 只包含兑现 P 声明的对应登记。`adoption.evidence` 给出实际 S 的观察，不能使用 P 的原观察冒称另一实现已核验。

`adoption.requireContract(dataContract)` 核对冻结证据能否兑现 P；`requirement.requireDeployment(source)` 核对当前配置是否仍是其精确绑定。两者都不替代 `execute` 对实际读基础、约束及关系检查的兑现。B 可以提供额外列，但 P 声明的每个列类型／NULL 和实际依赖的保证都必须成立；不能用完整列数或结构摘要相同代替核验。

采用的登记与发布由完整服务负责，插件不创建、修订或选择采用。初次 `createPhysicalTable` 在同一事务返回首份 `SourceAdoptions`；更换兑现实现使用 `registerSourceAdoptions`，已有采用按精确 S 读取，Kotlin 将正式返回的 `references` 直接传给 `buildRelease`。发布计算实际 P 闭包，缺少采用或同 P 多选明确拒绝，未用余项不成为依赖；不按最近记录选择。相同 P、绑定的不同 S 可以产生不同精确采用，审计主体与时间不产生另一份内容身份。

旧发布保留原采用；读取不可变采用只要求元数据读取权限，执行仍要求其精确绑定当前可用。原生表达式的 `NativeCall(A)` 是真实精确依赖，发布选择 B 不会将它改写，冲突需明确修订相应定义。未变的数据合同及业务定义不因实现替换而修订。采用和定义冻结不固定动态数据的 `SourceReadBasis`。

可选 JDBC 支持的 `supportsColumnInspection` 与 `supportsConstraintSnapshot` 均接收 `PhysicalSourceEvidence`。内置 JDBC 按选定 S 的 `structureProvenance` 决定 catalog／技术观察核验，数据库产品／版本及驱动名／版本与该证据比较；不从 P 原出处继承 A 的核验，也不要求 HTTP 来源提供 JDBC 元数据。具体执行要求见[来源访问合同](../../../../docs/source-access.md)。

`CompilationSupport` 提供 `SqlDialect`、`RelDataTypeSystem`、JDBC 类型映射、表坐标、参数、精确文本身份比较、类型化集合展开及原生数值操作。`matchesNativeResult(NativeValueType, JdbcColumnType)` 核验来源响应或显式合同提供的 SQL 标量类型能否承载编译类型；原始观察元数据保持原值，DuckDB 的 HUGEINT 等专属匹配由对应插件拥有。共同编译器继续组织关系与阶段、进行类型和数量分析并生成 SQL，内核保留结果容器形态、NULL 与逐值数值约束。

有限十进制采用明确的 `NativeValueType("FINITE_DECIMAL",null,null,nullable)`；结果元数据使用同名 Scalar，p/s 必须同时为空，普通 DECIMAL 仍要求完整合法容量。SDK 的 `FiniteDecimalTypes.create(nullable)` 构造该实际 Calcite 类型，`isFinite(type)` 核验其身份，不能用缺容量的普通 DECIMAL 代替。对应数量结果为 `MetricValueContract.FiniteDecimal`，不预定共同 p/s、不隐含舍入，也不自行赋予 Exact 或聚合工作容量；原生函数仍单独声明 `arithmetic`。Integer／固定 Decimal 可无损进入 Finite，反向及与 BinaryFloat 的共同表示不自动成立；最终量化由明确的节点承担。来源以 BigDecimal 等无损精确数值载体交付有限值，业务值沿用 DECIMAL 编码，不通过 Double 转换伪造精确输入。

H2 只将真实 DECFLOAT 映射为有限原值读取，并支持显式 CORE 承接；其 Finite SOURCE SUM／AVG／CAST 及参数映射尚无兑现声明。DuckDB 不声明 Finite 原生数值运算。插件必须逐实际类型和节点兑现，不能从输入有限、返回 DECIMAL、最终容量足够或某个后端的成功推定原生工作精确；SOURCE 不支持时准确拒绝，不自动切到 CORE。本参考实现不证明 Hologres 的解析、收入混合公式或原规模业务已通过。

显式 CORE 可将末端 Exact SUM、数值 MIN／MAX 及其混合的已支持 COUNT 留在核心，不要求同时出现数学 AVG，也不以 Finite 输入特判。实际来源关系消费者及后续来源表达仍要求完整来源前缀，不会读取先前 CORE 结果；来源插件继续兑现自身节点。该边界不增加本地 JOIN、浮点核心运算或未知原生 AVG，也不改变 SOURCE 的执行选择。

`nativeAggregate(SqlKind)` 返回该精确来源的 `SqlAggFunction`，包括真实返回类型推导。核心将已定义的聚合映射为原生 kind，并继续组织 DISTINCT、分组、窗口和数量保证；插件不接收业务指标公式。不支持的 kind 明确拒绝。例如 `STDDEV_SAMP` 的返回类型由该来源实现声明，不能由共同编译器统一假定为 DOUBLE。

普通聚合的 SQL 发射使用必需的类型化入口：

```kotlin
fun aggregateSql(
    owner: Aggregate,
    call: AggregateCall,
    arguments: List<SqlNode>,
    expression: SqlNode
): SqlNode
```

`owner` 和 `call` 是本次实际普通聚合，输入类型从 `owner.input` 按 `call.argList` 读取，结果类型为 `call.type`。`arguments` 严格按 `call.argList` 顺序提供当前 SQL 作用域中的输入列表达式，不能用另一层的列引用替代。`expression` 是 Calcite 已完成 DISTINCT、FILTER、聚合内排序及方言基础改写的整项标准聚合表达式，不含最外层 AS；输出别名由共同转换器保留。插件据本次实际类型和形态返回 SQL AST，必须保留分组、人口、NULL、重复及排序含义；不能重写整个关系、引入另一来源、解析最终 SQL 字符串或在类型推导时缓存渲染状态。

所有实现都须显式提供此方法；普通映射直接 `return expression`，接口没有默认实现或失败后替换路线。特殊映射不能因为取得原始参数就忽略 `expression` 中已组织的 FILTER、DISTINCT 或排序；尚不能兑现的形态应明确拒绝。窗口不经过该普通 Aggregate 入口；窗口的映射和行为保证仍分别核验。

该入口只提供类型化 SQL 映射位置，`nativeAggregate` 的返回类型推导和 `aggregateBehavior` 的实际算术保证继续独立。提供 hook 不证明任何后端的 TO_NUMBER 有限解析、跨 scale SUM 或 NUMERIC→TEXT 无损往返；Hologres 候选必须分别取得真实映射与采用证据，不能由接口存在、类型标签或少量正常值推成支持。

`aggregateBehavior(Aggregate, AggregateCall)` 和 `aggregateBehavior(RexOver)` 描述当前真实调用，返回可空的 `NativeAggregateBehavior`；NULL 表示未声明，原生结果容量仍取自实际节点。`ExactOrFail` 声明本次 SUM／COUNT 保留实际分组身份、NULL 与袋人口，不做中间量化，容量不足必须失败，不能静默溢出或回绕。`QuantizedMean(rounding)` 必须兑现对参与值精确累加、精确计数且仅最后向原生 DECIMAL scale 量化；`NativeFloating` 不提供误差上界。不能根据 DECIMAL／BIGINT 结果类型猜工作保证，AVG 也不能用 `ExactOrFail` 代替均值行为。

AVG 的业务目标由 `MeanTarget` 表达，最终数量合同声明输出表示与量化。共同编译器据实际输入、原生行为和节点类型判断 MATHEMATICAL 或 SOURCE_NATIVE 能否兑现，不把原生舍入字段交给作者照填。完成关系 REGROUP 的数学 AVG 还可在显式 CORE 求值：来源可以无损交付原袋，或由引擎在完整输入域、工作容量及实际聚合行为均已证明时生成精确工作 SUM 和各列独立非 NULL 计数状态，最后除法与量化仍在 CORE。Finite 本身不能证明固定工作容量或原生 AVG；未量化输出仍须能有限表示。作者无需另写 SUM／COUNT、重建 ROOT 或改保存定义；工作保证未知时保留原袋路线，不在执行失败后换计划。内置 H2 的适用固定域 SUM 有明确工作声明，原生 COUNT 保持未声明；DuckDB 的 SUM／COUNT 也不因数值类型而取得精确工作保证。

原生行为按实际聚合或窗口节点声明，需涵盖其输入类型、参与人口、NULL 与中间容量；不能仅以少量小值、一个常量执行路径或最终结果类型为依据。不同执行形态可以具有不同的原生 scale，核心使用各自实际结果检查目标，不要求它们共享一个全局舍入值。单纯增大声明 precision／scale 不能恢复丢失的位数。若插件提供有依据的原生输入转换或聚合映射，SQL AST 与类型推导必须一致，保留人口、权重和错误含义，不在执行失败后切换策略。

`deriveAvgAggType` 接收的是输入技术类型，不接收本次数据的最小值、最大值或行数；发现请求中的 `inputType` 也不是业务值域证明。最终数量容量及 `Approximate.minimum/maximum` 不能充当 AVG 的受限输入合同。当前没有从样本、字段名或过滤文本自动推导“安全评分范围”的机制。仅当来源先给出明确可证明的受限策略、而现有正式表达仍无法承载其前提时，才有依据讨论相应扩展；保证未知时保持拒绝。

`DIVIDE` 表达允许小数的算术商，不因两个输入采用整数技术类型就截断为整数商。例如 `7/2` 的含义是 `3.5`，不是 `3`；后端不能兑现时应明确拒绝。`CompilationSupport.division` 是来源表达式、完成关系和分摊计算的共同原生入口；SAFE_DIVIDE 也使用它，零分母由核心保留为 `NULLIF`。

两个操作数都是整数 SQL 类型时，核心在语义与原生编译中都先按各自完整整数域无损转换为 `DECIMAL(p,0)`，保留 NULL：有符号 TINYINT／SMALLINT／INTEGER／BIGINT 分别使用 3／5／10／19 位，无符号对应类型分别使用 3／5／10／20 位。这里的容量取自输入整数域，不使用最终数量输出的 precision／scale；例如 BIGINT 输入不会为了 `DECIMAL(28,18)` 输出而提前缩窄成只有十个整数位的值。来源无法容纳所需整数域时明确拒绝，不缩域、不转浮点。

连接器可以提供自定义 `SqlOperator`：返回类型推导接收规范化后的实际有序输入类型，`unparse` 必须生成兑现同一非截断语义的 SQL AST。原生结果仍为整型时，核心拒绝该映射。DECIMAL 的 scale 为零本身不证明静默截断：连接器可以检查可整除性，对不能精确表示的非整除输入明确失败；不能只改贴 DECIMAL 类型来掩盖实际整数运算。采用标准 DECIMAL 除法推导时，实际工作类型来自 `typeSystem.deriveDecimalDivideType`；自定义算子也可以提供自己的返回类型推导，不能假设一定调用该类型系统方法。`maximumDeclaredDecimalPrecision` 限制正式数量输出，不等于插件已证明任何中间除法策略，也不替插件决定工作类型。原生映射返回浮点时，Exact 数量合同继续拒绝，不因整数规范化而升级算术保证。

除法的原生计算与最终数量输出是两个位置。`ResultNumeric` 的 DECIMAL precision／scale／rounding 在该表达式完成后应用；当前 `division` 入口不接收外围 `MetricValueContract`。例如原生 `DECIMAL(38,0) / BIGINT` 已舍入为六位商，再 CAST 成 DECIMAL(38,18)，只会增加尾零，不能兑现要求十八位 HALF_UP 的数学商。外层类型容量、BigDecimal 解码及结果保存都不能恢复已经丢失的信息；AVG 的原生均值行为与显式 SUM／COUNT 的 DIVIDE 无关。

插件应先确定真实后端对所接受输入类型的中间容量、舍入、NULL、零分母与溢出行为，再用一致的类型推导和 AST 映射表达。统一的原生策略若能兑现接受域，可以使用现有入口；尚无保证的精确输入应由自定义除法算子的返回类型推导明确拒绝，不返回猜测类型或静默转浮点。不能只把返回类型声明为 DECIMAL(38,18)，也不能为增大商的 scale 将一个需要三十八个整数位的分子提前窄化为只有二十个整数位的 DECIMAL(38,18)。

原生除法绑定或返回类型推导明确抛出 `IllegalArgumentException`／`UnsupportedOperationException` 时，核心以 `NATIVE_ARITHMETIC_MAPPING_UNAVAILABLE` 交付拒绝，附 `operation=DIVIDE`、两个实际 `inputTypes` 和受控 `reason=MAPPING_REJECTED`。两个整数输入规范化后，`inputTypes` 是映射实际收到的 DECIMAL 类型。不能无损容纳输入整数域时为 `INTEGER_INPUT_CAPACITY_UNAVAILABLE`；映射只返回整数商时为 `INTEGRAL_QUOTIENT`；返回非数值时为 `NON_NUMERIC_QUOTIENT`。错误不包含插件私有 cause 原文，不执行来源 SQL；作者的非数值输入先按表达式合同拒绝。这个错误说明映射没有建立，不将未知算术写成数据已经违例，也不代表核心能够自动验证一个已经返回成功的原生算子。

下面是尚未建立任何原生除法保证时的明确拒绝方式；建立真实映射后，替换的是同一个算子的类型推导和原生 AST 实现，不在运行失败后切换策略：

```kotlin
override val division: SqlOperator = SqlBinaryOperator(
    "/", SqlKind.DIVIDE, 60, true,
    SqlReturnTypeInference { binding ->
        val inputs = (0 until binding.operandCount).map { binding.getOperandType(it).fullTypeString }
        throw UnsupportedOperationException("native division mapping is not established for $inputs")
    },
    null, OperandTypes.NUMERIC_NUMERIC
)
```

方言 `prepareUnparse(RexNode)` 可以观察仍有类型的 AST；目标 CAST 的 precision／scale 不携带完整业务舍入政策。不能靠反查生成 SQL 或猜目标类型重建该政策，也不能把外围输出量化自动应用到所有嵌套除法：`ROUND((a/b)*c,s)` 与先量化 `a/b` 再乘 `c` 并不普遍等价。只有提供了可兑现的后端策略、并具体说明其必须取得而现有入口确实不提供的信息后，才能据此判断需要的通用扩展。核心不以最终类型合规或少量正常值样本替来源证明算术。

整数规范化解决的是隐式整数截断，不证明原生商保留足够的小数位，也不消除双重舍入。即使原生商的 scale 大于最终 scale，先发生的原生舍入仍可能把真实商移到最终舍入中点；外层 HALF_UP、HALF_EVEN 等政策无法恢复这项信息。尚未证明所接受输入及执行形态能够兑现目标量化时，连接器仍须拒绝，不能把一次有限小数成功当作通用精确映射。

只读属性 `CompilationSupport.parameterSubmission` 必须明确声明 `BIND_PARAMETERS` 或 `TYPED_LITERALS`。前者保留有序参数，后者通过 `parameterLiteral` 将已校验值映射成 SQL AST 字面量并保留编译类型 CAST；共同默认映射覆盖参数编码中的精确数值、文本、布尔与时间值，后端特殊映射由插件明确实现。主查询、关系检查和候选值均使用该选择，不做问号文本替换或运行失败后的自动切换。

`CORRELATED_DERIVED_PROJECTION` 声明派生关系的投影能引用外层查询值。普通 `EXISTS` 条件相关不要求这项能力；数量合同的行内求值槽若必须把外层人口条件绑定在派生投影中，则按本次实际构造检查。缺失时在提交 SQL 前抛出 `NativeCompilationCapabilityException`，公开错误 `NATIVE_COMPILATION_CAPABILITY_MISSING` 包含 `capability` 与 `operation=SQL_EVALUATION_BINDING`。插件依据真实后端行为声明，它不代表来源事务或快照能力。内核不删除人口保护、不重复内联检查，也不在执行失败后切换方案。DuckDB 声明此能力；当前 H2 不声明。

主查询和候选值读取都通过 `SourceAccess.execute` 接收完整 `SourceSqlRequest`。候选结果的单列、类型和分页边界由候选应用核验；插件按同一执行合同交付结果，不实现单独的候选查询方法。

`execute(source,request,control,nativeMemoryBytes,stageNanos)` 不接收最终结果物化限额。来源原袋及编译生成的工作状态仍受取消、原生内存和来源自身约束，不按最终 `resultRows/resultValues/resultBytes/resultDepth` 提前截断。宿主先核来源交付的实际类型与值，结果 owner 再对最终 `ExecutableBusinessResult.Value` 统一计量；这不表示来源读取或核心工作可以无限占用资源。

`execute` 的阶段耗时回调为 Kotlin 函数类型 `stageNanos: (String, Long) -> Unit`，参数分别是阶段名称和耗时纳秒。实现直接调用 `stageNanos(name, elapsedNanos)`；调用者可传 lambda 或函数引用。插件应报告实际执行阶段，异常与资源关闭仍由执行合同处理。

## 原生标量函数

只读属性 `CompilationSupport.nativeScalarFunctions` 声明该精确插件可调用的只读原生函数。每项 `NativeScalarFunction` 包含 `id`、有序 `parameters`、标量 `resultType`、`arithmetic` 和 `stability`。参数种类为 `SCALAR` 或 `PHYSICAL_TABLE`；只有标量参数填写 `scalarType`。数值结果必须声明算术保证，非数值结果的 `arithmetic` 为 NULL；函数不能返回 ARRAY 或 ROW。原生浮点结果不能声明精确算术。

`MetricValueContract.Arithmetic` 区分 `EXACT_DECIMAL`、带结果域及误差容差的 `NATIVE_APPROXIMATION`，以及不声明结果域或误差界限的 `NATIVE_FLOATING`。后者仍核验容量、有限值及溢出，不能经 CAST 或 DECIMAL 结果类型取得输入没有的精确性或容差保证。插件的原生结果类型与算术保证必须分别如实声明。

来源表达式 `SemanticExpression.NativeCall` 保存精确 `connectorRef`、函数 `function` 和 `arguments`。`SCALAR` 参数携带完整 `expression`；`PHYSICAL_TABLE` 参数携带 `physicalTableId/physicalTableVersion`，它是需要冻结与核验的真实物理依赖，不是表名字符串或任意 SQL。`nativeScalarCall` 只负责把核心校验后的 `ScalarArgument(SqlNode)`、`TableArgument(qualifiedPath)` 映射为后端 SQL AST，不接管业务关系规划。

需要把原生标量内部的纯中间表达式交给共同求值复用时，插件实现必需入口：

```kotlin
fun nativeScalarExpression(
    builder: RexBuilder,
    function: NativeScalarFunction,
    call: RexCall
): RexNode
```

`function` 是本次已授权的精确原生函数声明，`call.operands` 仅包含其有序 SCALAR 实参；已授权 PHYSICAL_TABLE 身份继续由原调用持有，不转为标量操作数，也不授权新增表读取。直接 SQL 原生映射显式 `return call`；需要开放纯中间值的映射返回完整 typed Rex 树。两者是插件明确选择的映射，接口没有默认实现，不在展开失败后回到另一条路线。`nativeScalarCall` 继续负责原生叶子的底层 SQL 渲染。

中间值使用普通带类型 `RexNode` 构造并继续作为后续操作数；同一中间值可以被多处引用。核心从实际依赖和稳定性建立求值列，不要求插件自行编造 `RexLocalRef`、输入列编号或 SQL 别名。后端专用叶子可用带明确返回类型的 `SqlOperator` 构造 Rex 调用，再由该算子的 SQL 映射渲染；不能把整块内部程序继续藏在一个不透明 SQL 叶子中，并声称其中间值已经可共享。

下沉在共同关系方言准备完成之后、`SqlEvaluationProjection` 之前执行，使共享器能够看到树内 trim、系数等中间表达式；下沉结果不再送回后续 Hep 优化或折叠链。返回节点的 type 必须与原 call.type 完全相等，包含 NULL 性及技术容量；所有自由列、参数及相关字段引用只能来自原实际实参，不能借已有相关行变量读取另一个字段；不能新增输入、表、子查询、相关引用、窗口或聚合，也不能通过返回另一种类型改变数量保证。原函数、实参和展开树须符合共同的 statement-stable 规则，才可展开并共享；VOLATILE 不能由纯树替换取得更强稳定性，IMMUTABLE／STATEMENT 也不代表跨语句固定值。

纯树复用现有列程序、CASE／COALESCE 活动域和普通当前行投影，内部复用不要求作者增加技术 DERIVE 或伪声明 `CORRELATED_DERIVED_PROJECTION`。本来包含真实相关输入的构造继续执行原能力检查。不能将可失败解析移到关联之前或未选分支外，也不承诺来源物化或恰好求值一次。接口不建立另一套 IR 或关系规划，不接受最终 SQL 字符串改写；解析算法、词法、NULL、越界与非有限值、来源算术保证及实际传输继续由插件兑现。

该入口提供标量内部复用的类型化位置，不自行赋予来源算术、类型转换或结果交付保证。来源请求未收到响应时，UNKNOWN 与取消未确认不表示来源失败或已取消，也不能由 SQL 长度、表达式重复或等待超时推断耗时根因。

完成关系使用 `ResultExpression.ResultNativeCall(connectorRef,function,arguments)`，实参为有序 `List<ResultExpression>`，只接受该精确来源连接器已经声明的纯 `SCALAR` 签名。它能放在 JOIN 后的 `DERIVE`、条件或 `ResultCaseWhen` 分支，直接消费当前结果符号；函数 ID、参数个数、原生类型和返回类型仍经共同原生编译入口核验。`PHYSICAL_TABLE` 依赖留在来源定义及其采用闭包，不能从完成关系新增表读取。发布的阶段表达式授权须允许 `NATIVE_CALL`，参数树继续逐节点核对授权。构造器、封闭变体和局部规则从正式 `describe-script-api` 发现，不另维护节点清单。

需要有条件解析时，把原生调用直接构造在 CASE 的 THEN 内，并把整个 CASE 放在业务要求的关联之后。核心保留该关系位置与分支结构，不把解析提前到独立源投影或在客户端预取；CASE 所有分支仍做静态函数、类型及授权检查，不能用恒假条件隐藏非法调用。插件负责真实后端的格式解释、NULL、解析错误和 CASE 求值行为，包括后端规划期折叠的适用边界；声明函数不等于已通过目标后端的脏输入试验。原生函数不能借此去重 LEFT bag、补零、选最新或改写业务公式。[作者示例](../../../../examples/agent-client/conditional-native-call.olap.kts)展示关联后的条件格式解析构造，不声明内置插件已经提供某个解析函数。

完成关系原生数值结果保留函数声明的原生容量及 `arithmetic`，数量为维数一；它不从函数名推导天、金额或其他业务单位。Measure 实参只允许在 NUMBER→NUMBER 调用中使用：单位须为维数一，原生结果保证须承接输入，不能把近似或原生浮点升级为 Exact。非数值返回的原生调用只消费分类标量，不能通过 NUMBER→TEXT→CAST 擦除数量含义。原生数值调用保留输入的观察与重复度来源；原始逐行实参可形成逐行贡献，已完成的非线性数量不会重新成为原始独立贡献。CAST、`ResultNumeric` 及最终 DECIMAL 形状也不创造精确性、误差容差或单位变换。后续日期差、贡献人口、均值分解、单位与舍入由正式业务模型明确组合。

`SourceSqlRequest.sourceRequirements` 的 `uses` 明确区分 `ROWS` 与 `NATIVE_TABLE`，同一表可以同时具有两种用途。插件保留两者的精确来源和表身份核对；行约束与有限集合核验只对实际行读取要求执行。原生函数随本次 SQL 求值，不在客户端预取结果。

稳定性显式区分 `IMMUTABLE`、`STATEMENT`、`VOLATILE`。STATEMENT 表示同一来源 SQL 内相同参数的结果稳定，跨语句不作此承诺；它可供该 SQL 中无外部相关、子查询或递归边界的同一关系节点共享使用，不授予编译期常量折叠或跨语句缓存。VOLATILE 不参与共同关系共享；其他算子继续按自身正式稳定性处理。不能仅凭函数名或精确物理版本推断稳定性，读取表内容的函数须按真实依赖声明。共同引用的 CTE 不承诺后端物化、单次扫描或一致数据快照。函数声明不授予额外来源、物理表或查询输出权限；发布与本次执行仍核对精确插件、来源绑定及数量合同。服务的 `read-connector-capabilities` 按当前主体可访问来源返回 `connectorRef`、`nativeScalarFunctions`、`compilationCapabilities`、`parameterSubmission` 和 `configurationParameters`，调用者以实际声明和表达式合同组织调用。没有原生函数的插件显式返回空列表，不从后端产品名推测函数支持。

该操作也接受 `aggregate={connectorRef,aggregation,inputType,grouped}`，构造请求明确指定的普通全局／分组节点，使用同一 `typeSystem`、`nativeAggregate` 与 `aggregateBehavior` 披露 `resultType` 和可空的 `behavior`；SUM、COUNT 与 AVG 使用共同的行为入口。映射成功但行为为 NULL，不证明工作归约可用。发现只描述该形态，真实 Query 继续核验实际 owner、分组身份、FILTER、DISTINCT、输入及输出类型，不以另一份全局聚合代替本次形态。无法兑现返回 UNAVAILABLE。声明必须纯粹、确定，不连接来源或依赖运行参数；后端算法、原生容量、舍入及适用形态的依据由精确插件拥有。正式请求和响应见[来源合同](../../../../docs/source-access.md)。

原生映射依赖的后端版本、locale、编码等语义前提，由精确插件及身份参数记录，并由 `SourceAccess.execute` 对实际访问兑现；冻结配置及函数稳定性声明不是环境证明。`nativeScalarCall` 不取得来源配置，TEXT 参数也不提供词法域证明。参数角色、执行核验和明确拒绝的完整责任见[原生映射的来源环境](../../../../docs/source-access.md#原生映射的来源环境)。

## 来源访问与结果

`SourceAccess.configurationParameters` 用 `SourceConfigurationParameters(identityNames,runtimeNames)` 声明参数角色。配置分别提交 `identityParameters/runtimeParameters`；来源摘要只纳入前者和精确插件、方言、凭据槽。预算或回执目录不应改变业务数据身份，影响数据选择、比较或类型语义的设置也不能伪装成运行参数。未知键和错放角色由核心拒绝，必填值与值域仍由插件校验。

`SourceAccess.execute` 返回 `SourceExecutionResult(frame,readBasis,request)`。`request` 以受控字段表达来源请求身份、接受、响应和执行结局；失败通过 `SourceExecutionException` 保留安全 `SourceFailureEvidence`。`WarehouseExecutionControl.cancellationEvidence()` 区分未请求、未确认、确认和失败；只有来源确认回执才能证明远端取消。原始协议错误留在私有 cause，不把任意 headers、SQL 或响应正文塞进公开证据。

`readBasis` 如实为 Unknown 或 Versioned；来源支持强制精确数据版本时才声明 PINNED_READ 并落实 `SourceSqlRequest.expectedReadBasis`。返回基础必须与要求相符，不能忽略 pin 后读取最新。分区名或访问绑定本身不证明内容不可变，也不保证历史版本永远可用。

`SourceAccess.supportsConstraintVerification(dataContract, adoption)` 是不连接来源的纯能力声明，用于决定是否向优化器提供 P 的冻结键等约束。返回 false 不会把真实非空列改为可空：冻结非空类型进入 Calcite 时仍产生精确列的 `NOT_NULL` 采用，即使只是单表 RAW_BAG 投影。`SourceSqlRequest.sourceRequirements` 分别交出 P 的要求及实际采用的来源证据；`NOT_NULL.name` 是列名，其他项引用 P 的冻结键或规则。插件不解析 SQL 猜测检查对象，也不能仅凭业务 EventIdentity 推断检查范围。

`SourceAccess.execute` 拥有本次全部保证。访问层的 `CONSISTENT_READ` 要求检查与计算针对一致数据状态成立，可使用事务快照、原子 statement 或不可变数据版本，不把 `TRANSACTION_SNAPSHOT` 作为所有实现的前置。源端真实强制且覆盖既有数据的约束，也可在核验精确对象并保证执行期间 schema 稳定后兑现相应保证；仅目录 NULL 标记、约束名称、历史调查、DECLARED 或只检查后续写入的约束都不够。仅支持原生非空约束不代表能向优化器提供所有冻结键。内置 JDBC 继续使用数据非空见证，不因新增文档路径跳过扫描。

同一原子 statement 的实现须保证必要检查不会因主结果为空、优化器裁剪、延迟求值或结果预算而跳过，并让违例传播为失败；一次 HTTP、SQL 外壳、批次或未被消费的 CTE 不是证明。共同交付检查证据和主结果时，零行主结果也不能丢失必要证据。固定输入成功不替代真实物理来源路径；后端拒绝运输形态时保留原失败，不据常量结果声明能力已经兑现。插件保留核心主 SQL、检查 SQL 及各自参数的语义，不用 COUNT 前置、空人口跳过或运行后切换方式替代保证。发现真实 NULL 抛出 `SourceGuaranteeException("SOURCE_NOT_NULL_VIOLATION")`；能力不足使用 `SourceCapabilityException`，保留实际来源请求和阶段，不把无法核验写成数据已违例。单次执行保证可成立而 `readBasis` 仍为 Unknown；Unknown 不提供可重放版本坐标。完整机制条件与错误边界见[来源访问合同](../../../../docs/source-access.md)。

主 SQL 拥有最终结果的选行与行序语义，`SourceSqlRequest` 不另行提供排序键；`aliasMapping/outputTypes` 只是列合同。访问实现按来源返回顺序构造 `ResultFrame.rows`，运输、分页或预算处理不改变已有选行、重复度和排序。窗口排序、TopN 选行或集合内部排序不自动给出最终展示顺序；主 SQL 未给最终排序时不承诺稳定行序，并列键未区分的行也不额外确定顺序。不能仅凭有序子查询的样本认定外层 ARRAY／聚合会保序，也不能解析主 SQL 重建查询或在客户端重排来代替来源保证。

集合结果、逐项展开、排序和文本显示分别声明 `COLLECTION_RESULTS/EXPANSION/SORT/TEXT` 能力。`sortCollection` 映射明确的方向及 NULL 顺序，`collectionText` 映射明确的分隔符与 NULL 替代；不能把数组提前解码成字符串代替这些运算。

逻辑 ROW／ARRAY 不指定后端必须采用某一种记录或数组类型。`nativeAggregate` 返回的聚合函数参与已定型输入的类型推导，`SqlDialect.getCastSpec` 接收完整 `RelDataType`，`prepareUnparse(RexNode)` 可处理仍保有类型的构造及字段访问。原生映射须使记录构造、类型转换、集合去重、整体 NULL 与字段 NULL、字段读取及实际采用的后续运算一致成立；只替换最终运输包装不能解决中间物理算子的类型传递问题。结果仍须交付 `SqlResultValues.ArrayValue/RowValue` 并逐值核验；来源响应元数据或明确的结果列合同须满足 ARRAY 或 STRUCT／JAVA_OBJECT 容器类别。内部编码载体也须提供真实且可核验的完整映射，不能仅替换类型标签，或据现有入口直接声称任意编码已支持。

`COLLECTION_RESULTS` 和精确输入的聚合映射描述编译支持，不代替完整来源 Query 的物理执行事实。插件可在既有类型及运算映射中拒绝不能兑现的输入；一个常量、单对象或局部原生调用成功，不扩大为所有关联、分组和协议运输形态的保证。实际失败需区分核心主 SQL、访问实现增加的运输结构及来源物理边界；支持范围由真实映射决定，不按错误文字将已成立的其他集合路径一并关闭。

`expandCollection(outputType,tableAlias)` 在 Rel 转 SQL 时返回 `CompilationSupport.CollectionExpansion` 的原生放置计划。`outputType.fieldList` 依次声明完整元素、从 1 开始的位置两列的名称和类型，保留 ROW 字段顺序、递归类型及 NULL 性质。两种计划分别对应原生 FROM 与输入投影 SRF；精确插件在编译时明确选择，不在来源执行失败后切换。

- `CollectionExpansion.Relation(generate)`：核心将当前输入行的 ARRAY 引用交给 `generate`，取得 `CollectionRows(relation,element,ordinal)`。`relation` 是使用给定 `tableAlias` 的原生 FROM AST；核心构造 CROSS／LEFT JOIN、完整输入透传与输出投影。必要的表函数 LATERAL 语法由插件映射。
- `CollectionExpansion.InputProjection(generate,values)`：核心在完整输入上添加恰好一个 `generate(array)` 返回的 SELECT-list SRF，并将生成值绑定为内部列；下一层调用 `values(array,generated)` 取得 `CollectionValues(element,ordinal)`。两个回调只使用各自拿到的当前作用域引用，不能捕获另一层的字段别名。原生生成值可以是下标或完整有类型值，不能将多个独立 SRF 放入同一生成表达式。

例如，位置生成器的原生下标可由插件映射为 `element=array[index]`，`ordinal=index-array_lower(array,1)+1` 并按输出整数类型定型。FROM 表函数和 SELECT-list SRF 的放置由各自计划表达；插件不需要解析核心别名、取得完整输入关系、复制聚合或改写生成后的 SQL。原生类型、下标取值与数组下界由插件负责，不宣称任意后端都具有相同语法或能力。

内核拥有输入人口、输出投影和 INNER／OUTER：NULL 数组或空数组在 INNER 下无记录，在 OUTER 下补一个 NULL 元素及 NULL 序号；实际 NULL 元素有自己的非 NULL 序号，重复元素不丢弃。FROM 计划由核心 LEFT JOIN 并依据真实位置的非空性补整元素 NULL。输入投影计划的 OUTER 另需明确声明 `COLLECTION_PROJECTION_OUTER`：核心用标准 CARDINALITY 绑定存在标记，将 NULL／空数组替换为仅含一个 typed NULL 的同元素类型数组；生成后按存在标记将整个元素和序号置 NULL，原始输入列仍原样透传。该能力要求插件的原生类型与方言兑现 CARDINALITY、typed singleton ARRAY、CASE 和完整 ROW NULL 的组合；接受的类型超出保证时明确拒绝。缺少能力时在提交来源前返回 `NATIVE_COMPILATION_CAPABILITY_MISSING`、`capability=COLLECTION_PROJECTION_OUTER`、`operation=EXPAND_COLLECTION_OUTER`，不将 INNER 非空样本当作 OUTER 证明。

序号从 1 开始，实际元素的 `ordinal` 必须非 NULL；不直接暴露原生数组下界，不凭元素或所有 ROW 字段为 NULL 推断缺行。映射不得自行过滤、去重、归约、复制输入或改变求值作用域；生成与取值针对同一条输入记录的同一绑定数组，中间 ARRAY／ROW 保留类型与身份，不能压成展示字符串。运行验证须覆盖真实表行与聚合输入、空／NULL 数组、整体 NULL ROW、字段全 NULL 的非空 ROW、重复元素及位置规范化。其他后端上的通用 fixture 或常量数组成功不替代目标后端的正式 Query 验收；固定零行／一行外壳可能被折叠，不能证明物理扫描或原生引擎之间的集合类型传递已经成立。

零条输入关系与一条输入记录上的 NULL／空数组是不同人口。带分组键的聚合在零条输入时不产生分组；集合聚合的 typed NULL 分支保留该聚合声明的空输入含义，不会创建分组。INNER 展开不制造输入记录，OUTER 只保留实际存在的输入记录。插件不能用预先 COUNT、跳过执行、额外分组、删除透传列或外围 SQL 改写来掩盖来源错误。

REGROUP 的 COLLECT 仅在全局聚合或存在空 grouping set 时生成空输入 NULL 保护。没有空分组集合的实际分组至少包含一条输入，不再添加恒不命中的集合空输入 CASE；NULL 分组键、真实 NULL 元素及字段全 NULL 的 ROW 均仍是实际输入。标量 PROPAGATE 的非空计数检查与窗口帧的空帧规则分别处理，不由这项分组事实消除。

`COLLECTION_RESULTS/EXPANSION/SORT/TEXT` 与投影式 OUTER 的能力缺失均在来源提交前使用 `NATIVE_COMPILATION_CAPABILITY_MISSING` 拒绝，保留实际缺少的 `capability` 和触发编译的 `operation`。插件声明支持后，原生 ARRAY／ROW 类型、聚合结果描述符、SRF 放置与字段读取仍须共同成立；`InputProjection` 提供放置入口，不代替来源的类型保证。

调查原生描述符或集合执行错误时，沿同一次正式请求保留插件及来源的精确引用、完整出站 SQL 与实际参数、来源请求身份、原生错误阶段与 SQLSTATE，以及来源实际提供的读基础。类型定位以传入 `expandCollection` 的有序 `outputType`、ARRAY 的完整 `componentType`、`RexFieldAccess.referenceExpr.type/field.index` 和插件的 cast／字段映射为准；需要来源执行计划或原生类型描述符时，由连接器取得来源证据。不同请求在 Unknown 读基础下观察到的空人口、非空成功或同 SQL 的不同结果，不能单独证明核心类型错误、原生类型普遍不可用或唯一的空人口根因。

`ROW_FIELD` 的逻辑字段索引仍由内核验证。需要原生匿名字段名或专用字段运算的插件，可以在 `SqlDialect.prepareUnparse(RexNode)` 处理 `RexFieldAccess`：此时保有 `referenceExpr.type`、`field.index` 和结果类型，再转换为插件自己的有类型 Rex 调用及 SQL AST。不要等普通 SQL 标识符已经生成后解析 `EXPR$N`，也不能把逻辑字段名普遍替换成某个后端的 `fN`。

`textIdentityEquals(builder,left,right)` 返回两个真实文本操作数的完整精确身份 BOOLEAN 比较，保留 NULL 三值逻辑，不要求产生二进制中间值。原生等号只有在插件能够兑现类型、编码及 collation 的适用保证时才可直接使用。后端是否保留 HashJoin、过滤下推及运行时过滤，由实际执行计划确认；此映射不授予插件改写业务人口、LEFT 补空或重复度的权限。

`textIdentityKey(builder,value)` 提供同一文本表达式的注入式精确 BINARY／VARBINARY 身份，保留 NULL，未支持返回 NULL；它是纯 Rex 映射，相同表达式生成相同结构。引擎可把原文本键和此辅助键共同分组，再只输出原键；辅助键不成为业务字段，也不改变正式完整键。`NativeAggregateKeys.preservesExactIdentity(owner,support)` 核验每个实际 groupSet：精确数值、NULL、BOOLEAN 与二进制键可接受，文本必须在同一实际 Project 和分组中找到对应编码键。H2 复用 RAWTOHEX／STRINGTOUTF8，DuckDB 复用 ENCODE；支持编码不等于支持精确 SUM，未知 collation 也不会取得精确文本比较保证。

插件构造原生 Rex 函数时，应通过 Calcite 的正式运算符合同如实披露 NULL 传播。仅声明可空返回类型不足以证明输入 NULL 时结果必为 NULL；严格传播且非 NULL 输入不产生 NULL 的函数可通过 `SqlOperator.getStrongPolicyInference()` 返回 `Strong.Policy.ANY`。内置 DuckDB `ENCODE`、H2 `RAWTOHEX/STRINGTOUTF8` 按实际编码函数语义声明这一性质，SQL 与精确身份比较保持原样。核心据此分析过滤条件排除 NULL 的事实；函数语义未知或证明不完整时，保留运行关系检查，不能用声明跳过人口、NULL、重复度或数量保证。

lowering 结果在本次计算内持有与精确引用对应的同一编译支持，结果转换显式使用该支持；它不写入发布、JSON 或另一份原生类型表。连接器不能用不透明业务 SQL 替换完整查询。

`JdbcSupport` 在明确调用时取得连接描述与凭据，拥有只读连接、原生内存配置、快照建立、元数据约束、封存锚点、参数绑定及标量解码。`arrayElement(Array, ResultSet)` 解码已定位的实际数组元素；规范 JDBC 实现可复用第2列的元数据，缺少该元数据的原生游标由插件明确解码。共同来源物化器保留递归容器、实际类型及取消检查，不将元素转成文本，也不消费最终业务结果预算。普通在线源无需宣称封存能力。连接和语句由实际调用方关闭，取消沿 `WarehouseExecutionControl` 传递；插件关闭时注销自己装载的驱动。`warehouse-jdbc` 的标准支持代码可复用，具体后端插件不依赖 `semantic-calcite` 内部实现。

`sourceAnchor` 的取消检查参数为 `checkCancellation: () -> Unit`；封存核验在开始及分块读取过程中调用它，不能将长时间读取变成不可取消的任务。

公开 `NativeValueType` 为数组和 MULTISET 保存必需的递归 `elementType`，为 ROW 保存按实际序号排列的 `fields` 技术类型；不复制业务输出形态或物理字段名。标量保持 `name/precision/scale/nullable` 四个字段。实际数组缺少元素类型时明确拒绝，不按历史内容或样本猜测。

仓库中的 `connector-h2` 与 `connector-duckdb` 为两个独立实现。它们的存在不代表其他 JDBC 后端已经具有相同的数值、时间、集合或源一致性支持。

使用 DuckDB 等原生 JDBC 驱动时，JRE 临时目录需要允许驱动解包和加载共享库。容器采用只读根文件系统并单独挂载 `/tmp` 时，应为该目录明确配置可写及可执行映射；`noexec` 会使原生库加载失败。普通纯 Java JDBC 驱动不因此获得额外的原生运行依赖。

## 独立插件工程

独立 Kotlin 插件工程从 SDK 仓库声明宿主提供的公开构件，例如 Gradle Kotlin DSL：

```kotlin
plugins { kotlin("jvm") version "2.4.21" }

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
