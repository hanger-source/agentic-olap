# Agentic OLAP · 对象人口、独立测量与完成关系复用

本用法用公开合成数据，通过完整服务的正式 MCP 建设来源与定义、冻结发布、保存查询、执行计算并读取结果。完整问题覆盖对象集合、日去重、独立事实、名义数量转换、属性袋贡献、多值标签完整身份、账本完成关系及结果恢复；目录同时展示 Unicode 逻辑出口和 BOOLEAN 三值，属性贡献展示零 Metric 内联 guard 和六个保存算法两种责任形态。共享资格、归属和完成量再由独立消费者继续组合。输入和独立预期由示例作者给出，客户端只提交 Kotlin 作者程序、读取正式回执并核对结果，不在外围实现 OLAP 计算。

[Populations.kt](Populations.kt) 只准备原始 DuckDB 数据；[main.olap.kts](main.olap.kts) 构造正式模型并完成业务操作；[consume.olap.kts](consume.olap.kts) 在独立会话中发现并消费已发布定义。全部十四张表、78 个物理列均可空，无 PK、UNIQUE 或记录唯一性声明，共 89 条原始记录。对象／数量场景使用九表、58 列、55 行；标签与账本使用独立五表、20 列、34 行，两组输入互不改写。重复行、空串、NULL 和历史分区保留，不把样本中的单次匹配推断为一对一。

## 运行与交付

本用法通过完整 MCP 执行核对下述独立预期；运行任务只有在计算、保存、完整读取与生命周期检查都完成后才写出 `passed=true`。这些证据对应本例合成输入，不代表其他业务、后端或任意规模已经通过。

Unicode 逻辑出口已通过本用法的正式建模、发布、查询及完整保存结果读取。外部来源变化后，新旧文本结果分别保存，并在服务重启且移除来源配置后完整读回；下方预期由合成原始数据独立推得，不能代替其他后端或真实业务数据的验收。

正式共享定义、Q1／Q2／Q3 消费和目录资格显式修订已通过完整用法验收。五次消费者调用使用各自独立会话，从获准精确发布发现定义并完整读取保存结果；修订后再消费原发布，保留其原有口径。下述预期由原始数据独立给出，不代表其他后端或私有业务已完成同样采用。

多值标签与账本关系的三个 SOURCE 查询通过同一建模、保存及完整分页入口核对独立预期，完整标签元组、引用日归属和两侧缺组事实均保留。它们不证明其他后端的实际业务已验收，也不涵盖不同 CORE 完成出口的汇合或 CORE 输出继续关系消费。

在仓库根使用 JDK 21，显式执行本用法；输出目录必须不存在。

```sh
./project/gradlew -p project :development:populations
```

默认产物目录为 `.tmp/populations`，可用 `-Partifacts=/absolute/path` 指定。任务装配同轮 JVM 服务与连接器，独立 Kotlin 客户端准备临时签名者和 HTTPS JWKS，再通过 MCP 调用正式服务。它不默认执行订单、采购或订阅用法，也不增加 push／PR 自动运行入口。

`http/report.json` 记录建设、部分成功恢复、服务重启、无来源结果读取及来源变化的回执。`mcp-calls.jsonl` 保留实际 MCP 请求和响应，服务日志分别位于 `build.log`、`reopen.log`、`saved-result-without-source.log` 与 `changed-source.log`。运行中的每项预期来自下述原始数据；任务只有在所有检查完成后才写出 `passed=true`。

## 一、集合选人口，再分别测量目录和原始袋

`activity` 的前期为 `19991231`：A、B、C、D、E、空串以及 NULL 各有一条 `first_flag=TRUE`、非空 actor、amount=1 的记录。本期原始记录如下：

| subject | day | first_flag | actor | amount |
| --- | --- | --- | --- | ---: |
| A | 20000101 | FALSE | NULL | NULL |
| A | 20000102 | FALSE | NULL | NULL |
| B | 20000101 | FALSE | NULL | 7 |
| D | 20000101 | TRUE | now | 5 |
| `""` | 20000101 | FALSE | NULL | NULL |

`catalog` 给出独立的目录和资格：

| item | subject | current_partition | eligible | caption |
| --- | --- | --- | --- | --- |
| a1 | A | TRUE | TRUE | 山川🦊 |
| a2 | A | TRUE | TRUE | 示例样本 |
| b1 | B | TRUE | TRUE | NULL |
| b1 | B | TRUE | TRUE | NULL |
| c1 | C | TRUE | FALSE | plain |
| d1 | D | TRUE | TRUE | plain |
| empty | `""` | TRUE | TRUE | 山川 |
| old | E | FALSE | TRUE | plain |

`visits` 在 `20000101` 各有一条对 a1、b1、c1、d1、empty、missing 的访问，actor 分别为 va、vb、vc、vd、v0、vn。访问对象通过当前目录映射为 subject；missing 没有目录匹配，不能用日志中的其他字段替代身份。目录的 `eligible` 不参与“访问过”的人口筛选。

人口由三个独立关系组成，SET 的 `all=false` 表示完整身份行去重：

```text
前期 first_flag=TRUE 且有非空 actor 的对象：{A,B,C,D,E,""}
本期访问并匹配当前目录的对象：             {A,B,C,D,""}
本期 20000101 的 first_flag=TRUE 对象：      {D}

(前期 INTERSECT 访问) EXCEPT 本期 = {"",A,B,C}
```

NULL subject 不进入身份集合，空串 subject 是合法身份。`ObjectMeasure` 用该集合筛选各自原始袋；目录数量与 activity 金额分别完成，再以集合人口为 BASE、按完整 subject 键对齐，缺组使用 `PRESERVE_NULL`。

| subject | items | qualified_items | amount | records | known |
| --- | ---: | ---: | ---: | ---: | ---: |
| `""` | 1 | 1 | NULL | 1 | 0 |
| A | 2 | 2 | NULL | 2 | 0 |
| B | 1 | 1 | 7 | 1 | 1 |
| C | 1 | 0 | NULL | NULL | NULL |

`items` 为当前目录 item 去重计数，`qualified_items` 只计 eligible 为 TRUE 的 item；B 的重复目录行不能把金额 7 放大。`records` 为本期 activity 的 subject 非空计数，`known` 为 amount 非空计数。A 和空串有真实组但金额全 NULL，C 没有本期 activity 组，三者不能统一填零。前期资格也不会隐式变成本期测量条件。

### Unicode 逻辑出口与完整三值

catalog 在同一正式逻辑表创建请求中保留 nullable `caption` 直列，并以创建物理表返回的精确 caption 列引用声明标量 BOOLEAN 逻辑出口：

```text
caption_accepted = NOT(caption LIKE '%样本%')
```

该出口通过正常拓扑、事件、共享成员、场景授权和精确发布进入查询，客户端不计算 LIKE。`山川🦊` 得 TRUE，`示例样本` 得 FALSE，NULL 得 UNKNOWN／NULL，`plain` 得 TRUE。这个资格与原 `eligible` 分别负责自己的问题，不相互替代，不使用 COALESCE、改模式编码或默认值。

同一查询还通过已经授权的 EQ 比较来源 caption 与 typed TEXT 字面量 `山川🦊`，保存 `caption_is_mountain_fox`。初始 a1 为 TRUE，其余非 NULL 为 FALSE，NULL 保 NULL；来源变成 `山川样本🦊` 后 a1 为 FALSE。这让非 BMP 文本同时进入来源值、实际查询字面量、保存内容与历史读取，不只展示一个脱离采用上下文的常量。

一份 `SOURCE` 查询读取当前目录的原 subject、item、caption、caption_accepted，同时在独立分支按完整 subject 键完成来源行数与 TRUE／FALSE／UNKNOWN 数，再把完成数量连接回同一原袋：

| subject | catalog_rows | true_rows | false_rows | unknown_rows |
| --- | ---: | ---: | ---: | ---: |
| A | 2 | 1 | 1 | 0 |
| B | 2 | 0 | 0 | 2 |
| C | 1 | 1 | 0 | 0 |
| D | 1 | 1 | 0 | 0 |
| `""` | 1 | 1 | 0 | 0 |

保存结果保留七条当前目录行，其中 b1 两行完全重复；不按 item 去重，不从示例值推出记录唯一性。原文、两个 BOOLEAN／NULL 和四个完成数量同行输出，数量列前缀为 `counts__`，同 subject 的完成数量会随原袋行重复展示，不能再把这些展示值相加。作者用完整行的多重集逐值核对保存结果，因此既检查 Unicode 和 NULL，也检查重复行没有丢失或增加；仍使用正式 `limit=1` 分页完整读回。

## 二、每日去重、四次调用同一保存算法与明确十九日

`participation` 是独立来源：

| subject | day | actor | eligible |
| --- | --- | --- | --- |
| A | 20000101 | u1 | TRUE |
| A | 20000101 | u1 | TRUE |
| A | 20000101 | u2 | FALSE |
| A | 20000102 | u1 | TRUE |
| A | 20000102 | `""` | TRUE |
| A | 20000102 | NULL | TRUE |
| B | 20000101 | `""` | TRUE |
| B | 20000102 | NULL | NULL |

期间 actor 去重忽略 NULL、保留空串；非空期间数另行排除空串。每日数量先按完整 `(subject,day,actor)` 对象去重，排除空串和 NULL，再对各日完成数量求和。u1 同日重复只贡献一次，跨日分别贡献。

| subject | 期间去重 | 非空期间去重 | 每日非空数合计 | TRUE 资格合计 | FALSE 资格合计 | UNKNOWN 资格合计 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| A | 3 | 2 | 3 | 2 | 1 | 0 |
| B | 1 | 0 | 0 | 0 | 0 | 0 |

TRUE、FALSE、UNKNOWN 是三个独立资格分支，各自完成完整日／标识元组。只有完成资格分支缺组时，才按本次明确政策向已有 subject 人口补 0；不生成来源中不存在的 C。B 的日期组真实存在，非空计数为 0。

本地日均模板只接收具有完整 subject 键的已完成关系 `completed(subject,quantity)` 和明确数值参数 `days`，输出 `quantity/days`。在零 Metric 发布中，作者先直接内联该模板，再把同一计算体嵌入具有总体／TRUE／FALSE／UNKNOWN 四个完成关系形参的比较模板。内层通过 `ParentSlots` 显式使用父关系槽并转发 days，外层按总体为 BASE、以完整 subject 键对齐四个结果；此时没有日均 Metric 或比较成员的持久资产。

主题 carry 到下一 DRAFT 后，同一内层模板通过正式治理保存为日均算法，其精确采用被调用四次。外层比较模板则保存为共享 `MemberSourceDefinition.Computed` 并正式采用，公开 subject 和四个带数量合同的平均值输出。采用保留四个开放关系参数和 days 参数；公开成员调用明确传入同一实际完成关系的四种数量，不重写外层 body，也不把内层改成已保存 Metric 的引用。直接内联、嵌套内联、内层保存调用与外层成员调用都以 `days=2` 核对同一参照：

| subject | 每日总体日均 | TRUE 日均 | FALSE 日均 | UNKNOWN 日均 |
| --- | ---: | ---: | ---: | ---: |
| A | 1.500000 | 1.000000 | 0.500000 | 0.000000 |
| B | 0.000000 | 0.000000 | 0.000000 | 0.000000 |

模板内联、四次保存算法调用及外层比较成员调用均明确使用 `CORE_FINALIZATION`。输入数量为 INTEGER64／Exact，days 为 ONE／Exact／DECIMAL(20,1)，输出为 ONE／Exact／DECIMAL(25,6)，舍入 NONE、溢出 FAIL。来源完成去重和计数，核心完成精确除法；不把 DuckDB 原生浮点商宣称为 Exact。各次结果保存后全页读取，外层还核对五个完整输出及四列正式数量合同；三种模板使用方式的精确 Query／结果引用进入 `http/report.json` 的 `dailyTemplateReuse`。

保存请求通过 `QueryDefinitionCommand.executionMode` 明确选择保存前完整编译的模式，本例将同一次操作的 `mode` 分别传给保存请求与执行请求。保存请求未指定时使用 `SOURCE`；这个字段只决定保存前校验，不进入 QueryIntent、保存查询内容或哈希，也不规定以后执行的默认模式。执行保存查询时仍由 `QueryExecutionRequest.executionMode` 明确选择 `CORE_FINALIZATION` 或 `SOURCE`，没有模式切换重试或自动回退。

另一条查询显式选择十九日，仍使用 `CORE_FINALIZATION`，输出 DECIMAL(38,18)、HALF_UP、FAIL：A 为 `3/19 = 0.157894736842105263`，B 为 0。`每日数量合计−非空期间去重` 为 A=1、B=0。十九日由调用者明确提供，不从两天活跃记录推断；每日累计 3 也不冒充期间 UV 2。整个过程不用字符串拼接表达复合身份。

## 三、两个事实分别选时钟、资格和 NULL，再按 UNION 对齐

`fact_a`、`fact_b` 各自使用自己的 clock，选 `20000101—20000102` 且 `current_partition=TRUE`，先分别完成，再按 subject 的 UNION 人口对齐。

| 来源 | subject | clock | current_partition | amount | 额外字段 |
| --- | --- | --- | --- | ---: | --- |
| fact_a | A | 20000101 | TRUE | NULL | eligible=TRUE |
| fact_a | A | 20000101 | TRUE | 9 | eligible=FALSE |
| fact_a | B | 20000102 | TRUE | 5 | eligible=TRUE |
| fact_a | A | 20000101 | FALSE | 999 | eligible=TRUE |
| fact_b | A | 20000102 | TRUE | 5 | deduction=NULL |
| fact_b | C | 20000102 | TRUE | 8 | deduction=3 |
| fact_b | C | 20000102 | TRUE | NULL | deduction=2 |
| fact_b | C | 20000102 | FALSE | 999 | deduction=0 |

fact_a 同时保留原始 SUM 与条件 SUM：资格 TRUE 取原 amount（含 NULL），FALSE／UNKNOWN 取同单位 0。fact_b 则先逐行 `amount−deduction`，再 SUM；不能换成两个 SUM 相减。

| subject | a | conditional_a | a_present | b | b_present | b_rows | known_b |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| A | 9 | 0 | 1 | NULL | 1 | 1 | 0 |
| B | 5 | 5 | 1 | NULL | NULL | NULL | NULL |
| C | NULL | NULL | NULL | 5 | 1 | 2 | 1 |

C 的两行分别贡献 `8−3=5` 与 NULL，结果为 5；若改成 `SUM(amount)−SUM(deduction)` 会错误得到 3。A 的 fact_b 组存在但净额全 NULL，B 没有 fact_b 组，用 presence 和计数区分。FALSE 分区中的 999 不进入当前计算。两个事实的数量单位分别声明，不因列同名就相减或直接连接明细。

## 四、零 Metric 发布中的逐行 Scale、SUM 与完成比率

`scaled` 有三条原始记录：

| subject | record_ref | actor | gross | adjustment_text | quantity | label |
| --- | --- | --- | ---: | --- | ---: | --- |
| A | r1 | u1 | 100 | `"200"` | 2 | alpha |
| A | r2 | u1 | NULL | NULL | 1 | beta |
| B | r3 | u2 | NULL | NULL | NULL | gamma |

adjustment_text 在正式逻辑字段中 CAST 为 DOUBLE。查询分别对 gross 与 adjustment 逐行除以 `100.0`，用两个真实 `Scale` 方程表达各自来源单位到报告单位的变化，再按 subject 求和。quantity 直接 SUM，actor 与 record_ref 分别 COUNT DISTINCT，最后完成 gross／actor 的 `FixedBinary` 比率。

| subject | gross_total | adjustment_total | quantity_total | references | actors | ratio | label |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| A | 1 | 2 | 3 | 2 | 1 | 1 | beta |
| B | NULL | NULL | NULL | 1 | 1 | NULL | gamma |

该发布有明确成员、来源和阶段授权，冻结 Metric 数量为 0。方程写在实际内联 NUMERIC 运算处，不为取得计算资格创建空壳 Metric。两个报告单位独立命名，不推测币种；数量 SUM 不偷换 COUNT，标识数不推测自然人数或唯一订单数。MAX(label) 只是 TEXT 分类最大值，不承担时间上的“最新”选择。

本组使用 `SOURCE` 与明确的 NativeFloating／Float64 数量合同，不把逐行除法和完成后除法宣称为位级等价，也不添加默认值、TRY_CAST 或客户端补偿。

## 五、属性 LEFT 袋、独立分母与明确的 guard 归属

`bases` 的五条记录独立提供分母和来源人口，列名中的 count 是实际输入值：

| subject | record_ref | count_a | count_b | base_a | base_b | state_c |
| --- | --- | ---: | ---: | ---: | ---: | --- |
| A | rA | 1 | 1 | 100 | 50 | Y |
| B | rB | 1 | 1 | 50 | 25 | Y |
| C | rC | 1 | 1 | -10 | -5 | N |
| D | rD | 1 | 1 | 0 | 0 | NULL |
| E | rE | 1 | 1 | 20 | 10 | Y |

`attributes` 保留全部分区，只按 record_ref LEFT 关联，不补分区条件或唯一性声明：

| record_ref | partition_day | flag_a | flag_b | a | b | c | d | e | f |
| --- | --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| rA | 20000101 | TRUE | TRUE | 10 | NULL | 3 | 4 | 5 | 6 |
| rA | 20000102 | TRUE | FALSE | 20 | NULL | 7 | 8 | 9 | 10 |
| rB | 20000101 | TRUE | TRUE | 8 | NULL | NULL | NULL | NULL | NULL |
| rC | 20000101 | TRUE | TRUE | 5 | 1 | 2 | 3 | 4 | 5 |
| rD | 20000101 | NULL | FALSE | 9 | 9 | 9 | 9 | 9 | 9 |

同五条属性记录的其余原值如下，均有各自正式声明的名义来源单位：

| record_ref | partition_day | g | h | i | j | k | l | m |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| rA | 20000101 | 2 | 10 | NULL | 2 | 4 | 5 | 6 |
| rA | 20000102 | 3 | 20 | NULL | 3 | 6 | 7 | 8 |
| rB | 20000101 | NULL | 5 | NULL | NULL | NULL | NULL | NULL |
| rC | 20000101 | 4 | 2 | 3 | 4 | 1 | 2 | 3 |
| rD | 20000101 | 5 | 9 | 9 | 9 | 9 | 9 | 9 |

### 零 Metric 的十三贡献与六个内联 guard

首个 inline 发布冻结 0 Metric。一份 `SOURCE` Query 在同一主记录人口上完成独立分母、属性 LEFT 原袋、十三项 CASE 贡献、六个内联商和观察列，再保存并完整分页读回；guard 和除法直接属于本 Query。

| 贡献 | 完整资格 |
| --- | --- |
| amount_a | flag_b AND count_a=1 |
| amount_b、amount_c、amount_d | flag_b AND count_b=1 |
| amount_e、amount_f | 主记录 state_c=Y |
| amount_g | count_b=1，TRUE 时原 g 逐行乘 -1.0 |
| amount_h、amount_i、amount_j | flag_a AND count_a=1 |
| amount_k、amount_l、amount_m | flag_a AND count_b=1 |

每项只在自己的 CASE 中使用资格：TRUE 保原金额的 NULL，FALSE／UNKNOWN 取本项同单位 0，再 SUM。负倍率的 `FixedBinary(MULTIPLY, g的单位, ONE, g的单位)` 写在实际 MULTIPLY 的 NUMERIC 节点上，乘数为无量纲 NativeFloating／Float64 的 -1.0。主记录状态和 count 资格不因属性缺匹配而改写。

| subject | amount_a | amount_b | amount_c | amount_d | amount_e | amount_f | amount_g | amount_h | amount_i | amount_j | amount_k | amount_l | amount_m |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| A | 10 | 0 | 3 | 4 | 14 | 16 | -5 | 30 | NULL | 5 | 10 | 12 | 14 |
| B | 8 | NULL | NULL | NULL | NULL | NULL | NULL | 5 | NULL | NULL | NULL | NULL | NULL |
| C | 5 | 1 | 2 | 3 | 0 | 0 | -4 | 2 | 3 | 4 | 1 | 2 | 3 |
| D | 0 | 0 | 0 | 0 | 0 | 0 | -5 | 0 | 0 | 0 | 0 | 0 | 0 |
| E | 0 | 0 | 0 | 0 | NULL | NULL | NULL | 0 | 0 | 0 | 0 | 0 | 0 |

A 的 amount_b 是一条 TRUE+NULL 加一条 FALSE 的 ELSE 0，SUM 为 0；amount_i 是两条 TRUE+NULL，SUM 为 NULL。两者都保留各自 TRUE 原值 NULL 行数，不能把零解释成原值确实为零。D 的 amount_g=-5 来自不依赖属性 flag 的 count 资格；E 的 amount_e、amount_f、amount_g 因主记录资格成立而保留未匹配属性 NULL。

| 内联商 | 分子／分母 | guard 不成立时 |
| --- | --- | --- |
| ratio_a、ratio_b | amount_h/base_a、amount_i/base_a；分母非 NULL 且非 0 | NULL，允许负非零分母 |
| ratio_c、ratio_d | amount_k/base_b、amount_l/base_b；分母非 NULL 且非 0 | NULL，允许负非零分母 |
| ratio_e | amount_a/base_a；分母大于 0 | 同结果单位 0 |
| ratio_f | amount_c/amount_a；分母大于 0 | 同结果单位 0 |

| subject | ratio_a | ratio_b | ratio_c | ratio_d | ratio_e | ratio_f |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| A | 0.3 | NULL | 0.2 | 0.24 | 0.1 | 0.3 |
| B | 0.1 | NULL | NULL | NULL | 0.16 | NULL |
| C | -0.2 | -0.3 | -0.2 | -0.4 | 0 | 0.4 |
| D | NULL | NULL | NULL | NULL | 0 | 0 |
| E | 0 | 0 | 0 | 0 | 0 | 0 |

B 的 ratio_f 分母 amount_a=8 合法，分子 amount_c=NULL，因此保留 NULL；D／E 的同商分母为 0，按原 guard 产生 0。实际 NUMERIC 的 DIVIDE 各声明本次单位方程，来源浮点商保持 NativeFloating／Float64，不升级为 Exact，不进入 CORE 终算。

宽结果共 59 列：subject、十三贡献、六商、两个独立基数和 37 个 ONE／Exact／INTEGER64／FAIL 观察数量。观察包括来源行、LEFT 行、匹配／未匹配／净扩行、每对象匹配属性分区数、六个完整资格的 TRUE／FALSE／UNKNOWN 行数以及十三个 TRUE 原值 NULL 行数。A 为 1 条主记录、2 条 LEFT 匹配、2 个属性分区和净扩 1 行；E 为 1 条主记录、1 条 LEFT 行、0 匹配和 1 未匹配。分区数不是每记录版本数，行数不是独立订单数，边际资格计数也不证明多个资格同行共人口。

全部保存值由五条原主记录与六条 LEFT 行独立手算后逐列核对；正式结果页还核对 21 个浮点数量及 37 个精确计数的保存单位、运算语义、结果类型和溢出政策，期望来自正式成员声明和本次内联输出声明。没有用旧系统金额、SQL 长度或运行时长作为预期。

### 六个保存算法拥有各自 guard

保存算法链仍消费同一批原始数据，但其六个贡献使用自己的明确资格：a、b、c、d 用 flag_a，e、f 用 flag_b；a、b、e 还要求 count_a=1，c、d、f 要求 count_b=1。判断完整 `flag AND count=1`，TRUE 取属性原值（保 NULL），FALSE／UNKNOWN 取同单位 0。D 同时提供 UNKNOWN 和 FALSE，E 提供真实 LEFT 未匹配；A 有跨分区多匹配，B 的 b—f 有真组且原值全 NULL，C 有负分母。

分母先在 bases 自己的 ROOT 完成，不被属性匹配次数放大。属性贡献则按真实 LEFT 袋计算：

| subject | a | b | c | d | e | f | 原始行 | LEFT 行 | 匹配行 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| A | 30 | NULL | 10 | 12 | 5 | 6 | 1 | 2 | 2 |
| B | 8 | NULL | NULL | NULL | NULL | NULL | 1 | 1 | 1 |
| C | 5 | 1 | 2 | 3 | 4 | 5 | 1 | 1 | 1 |
| D | 0 | 0 | 0 | 0 | 0 | 0 | 1 | 1 | 1 |
| E | 0 | 0 | 0 | 0 | 0 | 0 | 1 | 1 | 0 |

六个保存算法分别拥有关系参数、单位方程和 guard，调用方传入同一完成 basis 的明确列，不复制算法内的来源计算：

| 算法 | 分母 | guard 不成立时 |
| --- | --- | --- |
| a、b | base_a；要求非 NULL 且非 0 | NULL |
| c、d | base_b；要求非 NULL 且非 0 | NULL |
| e | base_a；要求大于 0 | 同结果单位 0 |
| f | 已完成贡献 e；要求大于 0 | 同结果单位 0 |

合法分母配合 NULL 分子仍得到 NULL。a—d 允许负分母，e、f 不允许；f 的分母是贡献 e，不是 ratio_e。

| subject | ratio_a | ratio_b | ratio_c | ratio_d | ratio_e | ratio_f |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| A | 0.3 | NULL | 0.2 | 0.24 | 0.05 | 1.2 |
| B | 0.16 | NULL | NULL | NULL | NULL | 0 |
| C | -0.5 | -0.1 | -0.4 | -0.6 | 0 | 1.25 |
| D | NULL | NULL | NULL | NULL | 0 | 0 |
| E | 0 | 0 | 0 | 0 | 0 | 0 |

来源金额、完成分母及六个商分别声明单位，运算使用 NativeFloating／Float64。这里不制造金融精确性承诺，不任意 MAX 属性金额、不偷偷去重，也不替作者选一个属性版本赢家。

### 完成对象后的 79 列人口报告

报告只归约行计数和完成对象的条件计数，不把金额或比率再次 SUM。七个总体值为：对象数 5、去重对象数 5、来源行 5、LEFT 行 6、匹配行 5、未匹配行 1、额外行 1。

每种贡献产生五列原始袋计数；总计 30 列。`known_rows`、`null_rows` 仅统计资格 TRUE 下的原值：

| 贡献 | true_rows | false_rows | unknown_rows | known_rows | null_rows |
| --- | ---: | ---: | ---: | ---: | ---: |
| a | 4 | 0 | 2 | 4 | 0 |
| b | 4 | 0 | 2 | 1 | 3 |
| c | 4 | 0 | 2 | 3 | 1 |
| d | 4 | 0 | 2 | 3 | 1 |
| e | 3 | 2 | 1 | 2 | 1 |
| f | 3 | 2 | 1 | 2 | 1 |

每个完成算法再产生七列 subject 条件计数；总计 42 列。“TRUE 原值 NULL”表示该对象至少有一次这样的真实贡献：

| 算法 | 分母 NULL | 分母 0 | 分母负数 | 分子 NULL | 比率 NULL | 比率 0 | TRUE 原值 NULL |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| a | 0 | 1 | 1 | 0 | 1 | 1 | 0 |
| b | 0 | 1 | 1 | 2 | 3 | 1 | 2 |
| c | 0 | 1 | 1 | 1 | 2 | 1 | 1 |
| d | 0 | 1 | 1 | 1 | 2 | 1 | 1 |
| e | 0 | 1 | 1 | 1 | 1 | 3 | 1 |
| f | 1 | 2 | 0 | 1 | 0 | 3 | 1 |

七个总体值、30 个贡献计数与 42 个完成对象计数合为 79 列。此数据实际覆盖了正／负／零分母、合法分母下的 NULL 分子、三值资格、多匹配、未匹配和真实组全 NULL；没有用这些有限输入宣称全部浮点边界或其他后端均已验证。

## 六、精确采用、部分成功、大消息与持续变化的数据

初次 `createPhysicalTable` 与 initialAdoption 同事务完成，作者直接把返回的 `SourceAdoptions.references` 交给发布；再按精确结构观察 S 读取采用，核对与初次返回一致，不猜最近来源或自行拼引用。

主题版本 1 冻结零 Metric 的 inline 发布，供集合、Unicode 目录三值、独立事实、Scale、十三贡献／六个内联 guard、多值标签、账本归属／交集，以及本地日均模板的直接使用和嵌套比较使用。随后读取其 `contextSelection`，用显式 carry 修订同一主题到版本 2，读取延续后的成员和维度映射精确版本，新增一个日均和六个比率算法的采用，以及多量输出的日均比较成员，再建立场景及 saved 发布。物理合同 P、结构观察 S 和原始定义不因此重建；原 inline 发布与已保存查询仍保持原精确引用。

独立客户端还观察以下完整边界：

1. 一段作者程序先正式创建业务线，再主动抛错。脚本状态失败，已提交动作不回滚；正式回执保留唯一 effect 及其精确资产版本。后续只调用 `read-asset-version` 读取该对象，不重放成功写入。
2. 每个正式查询保存完整结果，以 `limit=1` 读取全部值页，核对正式引用、完整列、NULL 与对象人口，不把首屏当全结果。
3. 本用法部署的消息容量为 8 KiB，用于实际触发完整作者回执 `SAVED` 和 `read-response`。客户端继续读取同一完整响应，不截断字段、不重新执行业务操作；原操作的成功状态不因消息变大而改变。
4. build 回执分别返回独立事实的 `query/result` 和文本查询的 `captionQuery/captionResult` 正式引用。服务重启后，读取精确 Query 和两份旧结果；原事实查询再次执行时，来源未变，新结果与旧保存结果的人口均为 A、B、C。旧文本结果保留中文、emoji、BOOLEAN／NULL 和两条 b1。
5. 从部署配置移除全部来源和连接器后，再重启服务，只按原结果引用读取旧事实和旧文本结果，仍得到 A、B、C 与七条目录行。来源缺失影响新执行，不影响已有结果资产的读取；只读阶段还核对原始库字节未变。
6. 外部准备者的 `advance-source` 删除 `fact_b` 中 subject=C 的三条记录，并把目录 a1 的 caption 改成 `山川样本🦊`、a2 改成 NULL、两条 b1 改成 `plain`；P、S、来源绑定、字段、原资格和 current 标记保持相同。恢复来源配置后，以旧保存 Query 新执行，事实 UNION 人口变为 A、B；旧保存结果仍返回 A、B、C 及 C 的净额 5。原精确 `captionQuery` 也以明确 SOURCE 新执行并保存 `freshCaptionResult`，目录仍有七行：A 的三值数量为 TRUE=0／FALSE=1／UNKNOWN=1，B 为 TRUE=2／FALSE=0／UNKNOWN=0，其余对象不变。
7. 受控变化后的文本结果保存后再重启服务，`read-caption-history` 只通过公开结果读取接口按 `captionResult` 与 `freshCaptionResult` 分别全页读取。旧引用仍是 `山川🦊`／`示例样本`／NULL，新引用是 `山川样本🦊`／NULL／`plain`，两个 BOOLEAN 三值、重复袋和各自完成数量分别保持；历史读取不重新执行来源或用 live 文本替代保存字节。

来源变化由研发准备者直接作用于自有合成库，不给产品新增写源工具。发布固定定义、采用和授权，不冻结持续变化的数据；保存结果则保留当次实际结果。每次执行明确检查读基础为 `UNKNOWN`，不把不同执行描述成共同事务快照；脚本失败也不意味着先前治理写入或远端来源操作被回滚。

## 七、首次建设正式含义，再在独立会话继续提问

原 inline 发布位于主题版本 1，保存算法发布位于版本 2。建设作者随后显式 carry 到版本 3 的 DRAFT，创建、激活并采用以下四个共享 `MemberSourceDefinition.Computed`，再冻结 `reuse-original` 发布：

| 正式采用 code | 正式定义拥有的业务事实 | 公开输入与完整输出 |
| --- | --- | --- |
| paid_subjects | 活动期间、first_flag=TRUE、actor 去重计数大于 0、排 NULL subject | from/to；UNIQUE_MEMBERS，完整键 subject |
| visited_subjects | 访问期间、当前目录归属 LEFT、非 NULL 归属与 actor 计数、对象去重 | from/to；UNIQUE_MEMBERS，完整键 subject |
| period_activity | 期间活动原袋、完整 subject 分组、金额 SUM、来源行 COUNT、金额非 NULL COUNT | from/to；RECORDS，完整键 subject；amount、records、known |
| current_catalog | 当前目录 item 去重；eligible 仅 TRUE 参与 qualified_items | 无期间参数；RECORDS，完整键 subject；items、qualified_items |

from/to 是明确的 `yyyyMMdd` TEXT 参数，直接进入各定义自己的条件，不靠调用方继承根条件。付费标记定义沿用已有 `COUNT DISTINCT(actor)>0` 的非 NULL 计数含义；没有为 actor 空串新增政策。访问归属明确使用当前目录，不冒充历史归属；目录原 `eligible` 也不改变访问人口。UNIQUE_MEMBERS 和完成关系的 subject 键来自实际归约，不给原始来源袋补唯一性声明。

共享定义通过 `createMemberDefinition`、激活与 `adoptMemberDefinition` 成为持久资产。来源成员和维度映射由精确采用绑定，正式计算闭包拥有来源筛选、资格、关联和归约；这些内容不会在每个消费问题中重写。`period_activity.amount` 保留现有 activity-value／NativeFloating／Float64；records、known 以及目录数量保持 ONE／Exact／INTEGER64／FAIL。

来源记录数 `source_record_count` 与金额非 NULL 数 `known_amount_count` 是两个真实 `MetricCalculation`。各自通过正式成员参数的采用绑定调用 period_activity 的实际完成出口，并传递 from/to；二者具备同一 subject 完整键、参数签名和 ONE／Exact／INTEGER64／FAIL 合同。消费者从正式目录选择实际计数实例，Q3 显式换用金额非 NULL 数，不为具体实例再复制一份 `MeasurementSlot`。需要可替换测量的正式定义仍可声明自己的接受合同，不能把不同名义单位的六个比率也据此互换。

### 从正式目录发现并精确读取

研发客户端先完整分页调用正式 MCP `list-publications`，按明确的 releaseId、releaseVersion、scenarioDisplayName 选择唯一授权条目，再将返回的 scenarioId/scenarioVersion 连同发布坐标传给独立消费脚本。同名歧义应要求明确选择；不按 latest 或列表第一项猜测。这个发布目录按调用者的发布授权过滤，消费脚本不以治理全资产读取作为前提。

`consume.olap.kts` 先调用 `publication` 核对精确入口，再在同一发布中完整分页 `search`，分别找到对象、归属、完成量和计数测量候选。随后 `publishedAsset` 与 `publishedDefinition` 读取真实采用后的成员、实际测量实例和计算定义，核对完整键、参数、输出数量、授权槽位及维度绑定。目录引用使用搜索返回的 coordinate 与语义目录 hash；保存查询则使用 publication 的 capability hash。消费者使用返回的实际引用，不重建资产 ID，也不把执行目录 hash、语义目录 hash 和 capability hash 混用。

脚本通过 `Computations.members`、`invoke`、`measure`、`set` 和 `align` 组织同一个 `Calculation.Scope` 计算体，直接把实际成员调用、计数实例和已完成 Scope 交给对象 SET 与 BASE／PRESERVE_NULL 对齐。具体输入不经过额外 `RelationSlot`、`RelationParameter` 或 `LocalRelation` 装配，也不重述其数量合同。成员定义内部的 CASE、JOIN 和 SUM 不复制到消费作者；REPL 变量、建设作者函数和保存结果也不成为它的输入关系。维度绑定仍归正式采用实例所有。

消费参数如下，精确 scenario 坐标必须来自所选正式目录条目：

| 参数 | 本用法的明确选择 |
| --- | --- |
| releaseId | populations-release |
| releaseVersion | reuse-original 或 reuse-revised |
| scenarioDisplayName | 对象人口复用 |
| scenarioId / scenarioVersion | 对应正式发布条目返回的实际值，scenarioVersion 以数值文本传入 |
| question | Q1、Q2 或 Q3 |
| selectedCount | Q1/Q2 为 source_record_count；Q3 为 known_amount_count |
| previousStart / previousEnd | 19991231 / 19991231 |
| currentStart / currentEnd | Q1/Q3 为 20000101 / 20000102；Q2 为 20000101 / 20000101 |

### 相邻问题的独立预期

A 为前期付费标记对象，B 为本期按当前目录归属的访问对象，C 为本期付费标记对象。Q1 选择 `(A INTERSECT B) EXCEPT C`；Q2 明确换本期并选择 `(A INTERSECT B) INTERSECT C`；Q3 保留 Q1 期间及人口，只把获准计数实例换成金额非 NULL 数。

| 问题与发布 | subject 人口 | amount / records / known | selected_count |
| --- | --- | --- | --- |
| 原发布 Q1 | 空串、A、B、C | 空串 NULL/1/0；A NULL/2/0；B 7/1/1；C NULL/NULL/NULL | 空串 1、A 2、B 1、C NULL |
| 原发布 Q2 | D | 5/1/1 | 1 |
| 原发布 Q3 | 空串、A、B、C | 与 Q1 相同 | 空串 0、A 0、B 1、C NULL |

Q3 通过正式 `validate-query`、保存、执行和完整读取，核对两个真实计数实例的选择结果。数量合同不匹配另在建设阶段通过已有日均保存算法的真实 `completed(subject,quantity)` 形参观察：算法要求 ONE／Exact 计数，错误调用却把 activity-value／NativeFloating 金额完成量映射给 quantity。正式校验在 `ANALYZE_STAGES` 以 `RESULT_UNIT_MISMATCH` 拒绝实际金额单位与声明单位不符的输入，原始诊断以 `dailyInputRejected` 进入作者回执和 `http/report.json` 的 `dailyInputContractMismatch`；错误请求不执行来源，不修改形参要求或实际金额单位。

### 显式修订资格与新旧精确发布

建设作者继续 carry 到主题版本 4 的 DRAFT，将共享 current_catalog 定义修订为 `eligible AND caption_accepted`，激活新的共享版本，再用带 expectedDefinition 的 `reviseMemberAdoption` 更新本主题采用，最后冻结 `reuse-revised`。完整输出、subject 键、items 和数量合同不变，改变的是 qualified_items 的明确业务资格。原 `reuse-original` 发布保留原定义。

在来源尚未 advance 时，新发布 Q1 的 A qualified_items 为 1（原发布为 2），B 为 0（原发布为 1）；空串仍为 1、C 仍为 0，items、人口、活动量和计数不变。独立新会话依次消费原发布 Q1/Q2/Q3、新发布 Q1，再读原发布 Q1，核对两个精确发布各自含义。所有查询都正式保存、明确 SOURCE 执行，并以 limit=1 完整读回；规则修订先于外部来源变化，两个责任分别观察。

build 回执中的 `reuseOriginalPublication`、`reuseRevisedPublication` 只投影正式发布的可追溯坐标；独立消费不接收建设过程中成员 ID、轴 map 或本机私有路径。正式定义负责持久语义，保存 Query 固定本次选择和组合，保存结果保留实际执行内容；这些资产分别拥有自己的生命周期。

## 八、多值标签：完整身份与存在未匹配对象的事实

`label_facts` 的六条原始记录具有独立 record_id；全部 partition_key 为 `20010101`、activity_day 为 `20010103`。本次明确选择活动日 `20010101` 至 `20010107`、事实物理分区下界 `20000101`，排 NULL subject，不清洗 object_ref：

| subject | object_ref | 原事实袋行数 |
| --- | --- | ---: |
| A | object-1 | 2 |
| A | object-2 | 1 |
| A | NULL | 1 |
| B | object-3 | 1 |
| C | object-4 | 1 |

`labels` 的当前数据记录如下，另一条旧记录为 object-4／CATEGORY_A／old-label／Old／`20010106`：

| object_ref | label_kind | label_id | label_display | partition_key | 原标签袋行数 |
| --- | --- | --- | --- | --- | ---: |
| object-1 | CATEGORY_A | label-1 | Same | 20010107 | 2 |
| object-1 | CATEGORY_A | label-2 | Same | 20010107 | 1 |
| object-1 | CATEGORY_B | label-3 | Other | 20010107 | 1 |
| object-3 | CATEGORY_A | label-4 | Alpha | 20010107 | 1 |
| NULL | CATEGORY_A | label-null-object | Unattached | 20010107 | 1 |

`latestPartitionRows` 对标签自己的完整数据记录计算 `MAX(partition_key)`，再以正式关系关联和筛选保留该分区；最大值的输入不继承事实期间或类别过滤。两类标签再共同按 `(object_ref,label_kind,label_id,label_display)` 形成完整值集合，事实按 `(subject,object_ref)` 形成涉及对象集合，两者仅做一次标签 LEFT，最后按下面完整五列归约：

| subject | label_kind | label_id | label_display | has_label_match |
| --- | --- | --- | --- | --- |
| A | CATEGORY_A | label-1 | Same | true |
| A | CATEGORY_A | label-2 | Same | true |
| A | CATEGORY_B | label-3 | Other | true |
| A | NULL | NULL | NULL | false |
| B | CATEGORY_A | label-4 | Alpha | true |
| C | NULL | NULL | NULL | false |

这六行独立来自原袋：重复完整值归约，同名不同 ID 保留，两个类别不做双 LEFT 乘积。A 的 false 行表示至少一个事实对象未匹配，不表示 A 的所有对象无标签；object-2 与 NULL 对象在移除对象粒度后归约为这一行。NULL 对象不与 NULL 标签键匹配，C 的旧分区标签不进入当前集合。匹配由已限制为两个非 NULL 常量的类别判定，不以可空 ID 或显示文本判定；本输入没有覆盖匹配标签 ID／display 为 NULL 或空串的域。

本例的动态最新语义是**有数据记录的 partition_key 最大值**。DuckDB 没有在此例声明独立物理分区目录，不能据此推断空预建分区是否存在，也不宣称该运算与 Hologres `MAX_PT` 的空分区规则等价。执行仍读取实际来源，读基础为 UNKNOWN，不称为不可变快照。

查询显式 SOURCE 保存、执行并用 `readAll` 逐页读取，以完整五列元组和六行数量核对，不按 subject 建 map 折叠 A 的四行。build 返回 `labelRelationsQuery`／`labelRelationsResult` 的实际正式引用；没有创建空壳 Metric、拼接 CSV 或将标签集合 JOIN 回金额袋。

## 九、先完成再关联：归属与两账本缺侧

`ledger_activity` 的七条原袋记录均为业务日 `20010103`，活动期间和分区下界与上节相同：

| reference_key（TEXT） | subject | partition_key |
| --- | --- | --- |
| 101 | A | 20010101 |
| 101 | A | 20010102 |
| 102 | B | 20010101 |
| 102 | C | 20010102 |
| 103 | D | 20010101 |
| 104 | E | 20010101 |
| 105 | F | 20010101 |

引用日不是事先宣布的唯一对象。101 同引用日有两行但只有一个账户，102 同引用日有两行且有两个账户，不用 MAX 任取账户，也不把七条原袋行称为五笔订单。`OWNERSHIP` 先按 `(reference_key,activity_day)` 完成原袋 COUNT 与非 NULL subject 的 COUNT DISTINCT，再输出调查组直方图：

| raw_activity_rows | account_count | activity_reference_is_null | activity_reference_is_empty | activity_coordinate_groups |
| ---: | ---: | --- | --- | ---: |
| 2 | 1 | false | false | 1 |
| 2 | 2 | false | false | 1 |
| 1 | 1 | false | false | 3 |

`fee_ledger` 的引用原列为 nullable BIGINT，正式逻辑列 `reference_text` 将其原意义转为 TEXT；`adjustment_ledger` 的引用原本就是 TEXT。活动 TEXT 不转数值，不 trim、补零或认为前导零等价。本输入均为上述简单数字文本，前导零差异没有计作已覆盖。

各账本通过自己的数据记录 MAX 独立选择分区：fee 为 `20010107`，adjustment 为 `20010108`，不共用一个来源日期或继承活动 floor。所有账本业务日为 `20010103`，当前分区原袋如下：

| 引用 | fee 当前原袋行数 | adjustment 当前原袋行数 |
| --- | ---: | ---: |
| 101 | 3 | 2 |
| 102 | 1 | 1 |
| 103 | 无组 | 无组 |
| 104 | 1 | 无组 |
| 105 | 无组 | 2 |
| NULL | 1 | 1 |

fee 另有一条 103／`20010106` 的旧分区行，adjustment 另有一条 104／`20010107` 的旧分区行；各自最大分区存在时它们均不进入本问题。两表均没有记录唯一性声明，重复原行不删除。每侧先按引用日完成一行正 COUNT，然后对活动的五个完成坐标分别 LEFT，普通 EQ 不让 NULL 引用跨键匹配。只有完成组的 COUNT 为 NULL 才表示本侧缺组；缺侧不补 0。

`LEDGER_INTERSECTION` 的完整九列预期如下。`activity_reference_is_null` 和 `activity_reference_is_empty` 在此五行均为 false，其余七列为：

| raw_activity_rows | account_count | fee_ledger_is_missing | adjustment_ledger_is_missing | fee_ledger_raw_rows | adjustment_ledger_raw_rows | activity_coordinate_groups |
| ---: | ---: | --- | --- | ---: | ---: | ---: |
| 2 | 1 | false | false | 3 | 2 | 1 |
| 2 | 2 | false | false | 1 | 1 | 1 |
| 1 | 1 | true | true | NULL | NULL | 1 |
| 1 | 1 | false | true | 1 | NULL | 1 |
| 1 | 1 | true | false | NULL | 2 | 1 |

两份问题分别显式 SOURCE 保存、执行及全页读取，按完整五列／九列元组比较；保存后的袋行数、账户数与调查组数另核对 ONE／Exact／INTEGER64／FAIL 数量合同。最终调查人口仍为五个活动坐标，COUNT 前直接双 LEFT 原袋会制造另一种权重，不能得到本预期。build 返回 `ledgerOwnershipQuery`／`ledgerOwnershipResult` 和 `ledgerIntersectionQuery`／`ledgerIntersectionResult` 的正式引用。

这是关系调查，不计算财务金额，不宣布重复袋行有误，也不从本次分布推出跨期间账户唯一性。三个关系查询不增加独立 runner；最新分区内容变化、空预建分区、所有 NULL／空串域及不同核心完成出口组合均不在这些有限输入的验收范围内。

正常使用方式见[作者使用指引](../../docs/agent-usage.md)，部署、认证和预算见[服务说明](../../skills/agentic-olap/references/server.md)。本用法不要求业务项目读服务器私有文件、放宽合同、改写生成 SQL 或维护外围计算器。
