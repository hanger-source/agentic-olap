# Agentic OLAP · 对象人口、独立测量与完成关系复用

本用法用公开合成数据，通过完整服务的正式 MCP 建设来源与定义、冻结发布、保存查询、执行计算并读取结果。六组问题分别展示对象集合、日去重、独立事实、名义数量转换、属性袋贡献及结果恢复；目录同时展示 Unicode 逻辑出口和 BOOLEAN 三值，属性贡献展示零 Metric 内联 guard 和六个保存算法两种责任形态。输入和独立预期由示例作者给出，客户端只提交 Kotlin 作者程序、读取正式回执并核对结果，不在外围实现 OLAP 计算。

[Populations.kt](Populations.kt) 只准备原始 DuckDB 数据；[main.olap.kts](main.olap.kts) 构造正式模型并完成业务操作。全部九张表、58 列均可空，无 PK、UNIQUE 或记录唯一性声明，共 55 条原始记录。重复行、空串、NULL 和历史分区保留，不把样本中的单次匹配推断为一对一。

## 运行与交付

本用法通过完整 MCP 执行核对下述独立预期；运行任务只有在计算、保存、完整读取与生命周期检查都完成后才写出 `passed=true`。这些证据对应本例合成输入，不代表其他业务、后端或任意规模已经通过。

Unicode 逻辑出口已通过本用法的正式建模、发布、查询及完整保存结果读取。外部来源变化后，新旧文本结果分别保存，并在服务重启且移除来源配置后完整读回；下方预期由合成原始数据独立推得，不能代替其他后端或真实业务数据的验收。

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

一个保存算法只接收具有完整 subject 键的已完成关系 `completed(subject,quantity)` 和明确数值参数 `days`，输出 `quantity/days`。同一个精确采用被调用四次，分别传入每日总体及三种资格数量，`days=2`：

| subject | 每日总体日均 | TRUE 日均 | FALSE 日均 | UNKNOWN 日均 |
| --- | ---: | ---: | ---: | ---: |
| A | 1.500000 | 1.000000 | 0.500000 | 0.000000 |
| B | 0.000000 | 0.000000 | 0.000000 | 0.000000 |

四次保存算法调用明确使用 `CORE_FINALIZATION`。输入数量为 INTEGER64／Exact，days 为 ONE／Exact／DECIMAL(20,1)，输出为 ONE／Exact／DECIMAL(25,6)，舍入 NONE、溢出 FAIL。来源完成去重和计数，核心完成精确除法；不把 DuckDB 原生浮点商宣称为 Exact。

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

主题版本 1 冻结零 Metric 的 inline 发布，供集合、Unicode 目录三值、独立事实、Scale 及十三贡献／六个内联 guard 的完整宽查询使用。随后读取其 `contextSelection`，用显式 carry 修订同一主题到版本 2，读取延续后的成员和维度映射精确版本，新增一个日均和六个比率算法的采用，再建立场景及 saved 发布。物理合同 P、结构观察 S 和原始定义不因此重建；原 inline 发布与已保存查询仍保持原精确引用。

独立客户端还观察以下完整边界：

1. 一段作者程序先正式创建业务线，再主动抛错。脚本状态失败，已提交动作不回滚；正式回执保留唯一 effect 及其精确资产版本。后续只调用 `read-asset-version` 读取该对象，不重放成功写入。
2. 每个正式查询保存完整结果，以 `limit=1` 读取全部值页，核对正式引用、完整列、NULL 与对象人口，不把首屏当全结果。
3. 本用法部署的消息容量为 8 KiB，用于实际触发完整作者回执 `SAVED` 和 `read-response`。客户端继续读取同一完整响应，不截断字段、不重新执行业务操作；原操作的成功状态不因消息变大而改变。
4. build 回执分别返回独立事实的 `query/result` 和文本查询的 `captionQuery/captionResult` 正式引用。服务重启后，读取精确 Query 和两份旧结果；原事实查询再次执行时，来源未变，新结果与旧保存结果的人口均为 A、B、C。旧文本结果保留中文、emoji、BOOLEAN／NULL 和两条 b1。
5. 从部署配置移除全部来源和连接器后，再重启服务，只按原结果引用读取旧事实和旧文本结果，仍得到 A、B、C 与七条目录行。来源缺失影响新执行，不影响已有结果资产的读取；只读阶段还核对原始库字节未变。
6. 外部准备者的 `advance-source` 删除 `fact_b` 中 subject=C 的三条记录，并把目录 a1 的 caption 改成 `山川样本🦊`、a2 改成 NULL、两条 b1 改成 `plain`；P、S、来源绑定、字段、原资格和 current 标记保持相同。恢复来源配置后，以旧保存 Query 新执行，事实 UNION 人口变为 A、B；旧保存结果仍返回 A、B、C 及 C 的净额 5。原精确 `captionQuery` 也以明确 SOURCE 新执行并保存 `freshCaptionResult`，目录仍有七行：A 的三值数量为 TRUE=0／FALSE=1／UNKNOWN=1，B 为 TRUE=2／FALSE=0／UNKNOWN=0，其余对象不变。
7. 受控变化后的文本结果保存后再重启服务，`read-caption-history` 只通过公开结果读取接口按 `captionResult` 与 `freshCaptionResult` 分别全页读取。旧引用仍是 `山川🦊`／`示例样本`／NULL，新引用是 `山川样本🦊`／NULL／`plain`，两个 BOOLEAN 三值、重复袋和各自完成数量分别保持；历史读取不重新执行来源或用 live 文本替代保存字节。

来源变化由研发准备者直接作用于自有合成库，不给产品新增写源工具。发布固定定义、采用和授权，不冻结持续变化的数据；保存结果则保留当次实际结果。每次执行明确检查读基础为 `UNKNOWN`，不把不同执行描述成共同事务快照；脚本失败也不意味着先前治理写入或远端来源操作被回滚。

正常使用方式见[作者使用指引](../../docs/agent-usage.md)，部署、认证和预算见[服务说明](../../skills/agentic-olap/references/server.md)。本用法不要求业务项目读服务器私有文件、放宽合同、改写生成 SQL 或维护外围计算器。
