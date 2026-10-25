import com.olap.prototype.calculation.*
import com.olap.prototype.connector.*
import com.olap.prototype.connector.loader.ConnectorPlugins
import com.olap.prototype.contract.JdbcColumnType
import com.olap.prototype.engine.EngineExecutionException
import com.olap.prototype.engine.OlapEngine
import com.olap.prototype.engine.catalog.*
import com.olap.prototype.engine.compiler.CompiledQuery
import com.olap.prototype.engine.compiler.OlapSemanticCompiler
import com.olap.prototype.engine.executable.*
import com.olap.prototype.event.EventIdentity
import com.olap.prototype.event.EventRef
import com.olap.prototype.event.RelationAccessPath
import com.olap.prototype.expression.SemanticExpression
import com.olap.prototype.member.*
import com.olap.prototype.metric.MetricAggregation
import com.olap.prototype.metric.MetricValueContract
import com.olap.prototype.query.*
import com.olap.prototype.query.PostOperationContract.Derive
import com.olap.prototype.query.PostOperationContract.JoinInput
import com.olap.prototype.query.PostOperationContract.OrderKey
import com.olap.prototype.query.PostOperationContract.Project
import com.olap.prototype.query.PostOperationContract.Regroup
import com.olap.prototype.query.PostOperationContract.ResultAggregate
import com.olap.prototype.query.PostOperationContract.ResultGrouping
import com.olap.prototype.query.PostOperationContract.Sort
import com.olap.prototype.query.ResultExpression.*
import com.olap.prototype.util.OlapJsonUtils
import com.olap.prototype.warehouse.DeadlineExecutionControl
import com.olap.prototype.warehouse.ResultMaterializationLimits
import com.olap.prototype.warehouse.contract.*
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.Driver
import java.sql.SQLException
import java.util.HexFormat
import java.util.Properties
import java.util.TreeMap

/** 研发者直接消费内核的确定性工程示例，不创建主题域、场景或发布。 */
object Kernel {
    private const val CATALOG = "kernel-orders"
    private const val SOURCE = "orders-source"
    private const val EVENT = "orders"
    private const val TOPOLOGY = "orders-topology"
    private const val BINDING = "orders-table"
    private val AMOUNT_DEFINITION = MetricRef("amount_sum", "1")
    private val COUNT_DEFINITION = MetricRef("order_count", "1")
    private val AMOUNT_INSTANCE = MetricInstanceRef("orders-amount")
    private val COUNT_INSTANCE = MetricInstanceRef("orders-count")
    private val PER_RECORD_DEFINITION = MetricRef("amount_per_record", "1")
    private val BASE_PER_RECORD = MetricInstanceRef("orders-base-per-record")
    private val DOUBLE_PER_RECORD = MetricInstanceRef("orders-double-per-record")
    private val LOCAL = RelationAccessPath(emptyList())
    private val CUSTOMER = ScopedInputContract.DimensionRef("customers", "1", "customer")
    private val MONEY = MetricValueContract(MetricValueContract.Fixed(MetricValueContract.UnitIdentity("currency", "EUR", 1)),
        MetricValueContract.Exact(), MetricValueContract.Decimal(38, 2, MetricValueContract.Rounding.NONE), emptyMap(), MetricValueContract.Overflow.FAIL)
    private val MEAN_MONEY = MetricValueContract(MONEY.unit,
        MetricValueContract.Approximate("-1000000000000", "1000000000000", "0.000000001", "0.000000000001"),
        MetricValueContract.BinaryFloat(64), emptyMap(), MetricValueContract.Overflow.FAIL)
    private val COUNT = MetricValueContract(MetricValueContract.Fixed(MetricValueContract.ONE), MetricValueContract.Exact(),
        MetricValueContract.Integer(64), emptyMap(), MetricValueContract.Overflow.FAIL)
    private val CREATE = """
        CREATE TABLE orders (
          order_id BIGINT PRIMARY KEY,
          customer VARCHAR NOT NULL,
          amount DECIMAL(12,2)
        )
    """.trimIndent()
    private val INSERT = """
        INSERT INTO orders VALUES
          (1, 'A', 10.00), (2, 'A', NULL),
          (3, 'B', 7.50), (4, 'B', 2.50)
    """.trimIndent()

    @JvmStatic
    fun main(arguments: Array<String>) {
        val args = arguments.toMutableList()
        val option = args.indexOf("--connectors")
        require(option >= 0 && option + 1 < args.size && args.count { it == "--connectors" } == 1) {
            "用法：Kernel --connectors <配置> <尚不存在的输出目录> <请求期限毫秒> <原生内存字节>"
        }
        val configuration = Path.of(args.removeAt(option + 1))
        args.removeAt(option)
        require(configuration.isAbsolute) { "连接器配置路径必须为绝对路径" }
        val paths = OlapJsonUtils.mapper().readTree(Files.readAllBytes(configuration))
        require(paths.isArray && paths.all { it.isTextual && Path.of(it.textValue()).isAbsolute }) { "连接器配置必须包含绝对 manifest 路径数组" }
        ConnectorPlugins.load(paths.map { Path.of(it.textValue()) }).use { deployment ->
            val selected = deployment.registry.references().filter { it.id == "duckdb" }
            require(selected.size == 1) { "本例需要明确部署唯一 DuckDB 连接器" }
            require(args.size == 3) { "用法：Kernel --connectors <配置> <尚不存在的输出目录> <请求期限毫秒> <原生内存字节>" }
            val output = Path.of(args[0]).toAbsolutePath().normalize()
            val requestMillis = args[1].toLong()
            val nativeMemoryBytes = args[2].toLong()
            require(requestMillis > 0 && nativeMemoryBytes > 0) { "请求期限与原生内存预算必须为正数" }
            Files.createDirectories(output.parent)
            Files.createDirectory(output)
            run(output, requestMillis, nativeMemoryBytes, deployment.registry, selected.single())
        }
    }

    private fun openSource(spec: JdbcConnectionSpec, registry: ConnectorRegistry): Connection {
        val jdbc = (registry.require(spec.connectorRef).access as JdbcAccess).jdbc
        val driver = Class.forName("org.duckdb.DuckDBDriver", true, jdbc.javaClass.classLoader)
            .getDeclaredConstructor().newInstance() as Driver
        return driver.connect(spec.jdbcUrl, Properties())
    }

    private fun run(output: Path, requestMillis: Long, nativeMemoryBytes: Long, registry: ConnectorRegistry, connector: ConnectorRef) {
        val spec = JdbcConnectionSpec(SOURCE, connector, "duckdb", "jdbc:duckdb:${output.resolve("orders.duckdb")}", null, null, "org.duckdb.DuckDBDriver")
        DeadlineExecutionControl(requestMillis).use { control ->
            val physical = openSource(spec, registry).use { connection ->
                connection.createStatement().use { sql -> sql.execute(CREATE); sql.execute(INSERT) }
                describe(connection, spec, control, registry)
            }
            Files.writeString(output.resolve("source.sql"), "$CREATE;\n$INSERT;\n")
            save(output.resolve("source-description.json"), mapOf(
                "table" to JdbcTableConstraints.Table(physical.catalogName, physical.schemaName, physical.tableName),
                "columns" to physical.physicalColumnTypes, "evidence" to physical.sourceConstraints))
            val catalog = catalog(physical)
            val request = request("kernel-orders-normal", AMOUNT_INSTANCE, COUNT_INSTANCE)
            save(output.resolve("catalog.json"), catalog)
            save(output.resolve("request.json"), request)
            val provider = SourceProvider(spec.source(), registry)
            val resultLimits = ResultMaterializationLimits(1_000, 20_000, 2_097_152, 16)
            val engine = OlapEngine(OlapSemanticCompiler(CalciteRelCompiler(registry)), SourceExecutableSqlExecutor(
                { binding -> provider.takeIf { it.bindingRef == binding } }, resultLimits, nativeMemoryBytes, null), resultLimits)
            val completed = engine.execute(catalog, request, observer(output, "normal"), control)
            save(output.resolve("business-result.json"), completed.businessResult)
            save(output.resolve("compiled-query.json"), completed.compiledQuery)
            requireOrders(completed.businessResult)
            val measurementRequest = measurementRequest()
            save(output.resolve("measurement-request.json"), measurementRequest)
            val measurements = engine.execute(catalog, measurementRequest, observer(output, "measurement"), control)
            save(output.resolve("measurement-business-result.json"), measurements.businessResult)
            save(output.resolve("measurement-compiled-query.json"), measurements.compiledQuery)
            requireMeasurements(measurements.businessResult)

            val badReference = request("kernel-orders-bad-instance", MetricInstanceRef("unknown-amount-instance"), COUNT_INSTANCE)
            save(output.resolve("bad-reference-request.json"), badReference)
            val failures = linkedMapOf<String, Any?>()
            failures["badReference"] = expectFailure(output, "bad-reference", "BIND_INPUT", "EXECUTABLE_INPUT_BIND_FAILED") {
                engine.execute(catalog, badReference, observer(output, "bad-reference"), control)
            }
            // 只修改本示例创建的源；保持原目录及请求，不用新调查替换冻结类型。
            val drift = listOf("DROP TABLE orders", "CREATE TABLE orders (order_id BIGINT PRIMARY KEY, customer VARCHAR NOT NULL, amount VARCHAR)",
                "INSERT INTO orders VALUES (1,'A','10.00'),(2,'A',NULL),(3,'B','7.50'),(4,'B','2.50')")
            Files.writeString(output.resolve("source-drift.sql"), drift.joinToString(";\n", postfix = ";\n"))
            openSource(spec, registry).use { connection -> connection.createStatement().use { sql -> drift.forEach { sql.execute(it) } } }
            failures["sourceDrift"] = expectFailure(output, "source-drift", "VERIFY_SOURCE", "SOURCE_COLUMN_TYPE_DRIFT") {
                engine.execute(catalog, request, observer(output, "source-drift"), control)
            }
            save(output.resolve("boundary-failures.json"), failures)
            control.checkCancellation()
        }
        Files.writeString(output.resolve("answer.md"), """
            # 订单内核计算

            4 条订单均以 EUR 计价。客户 A、B 的金额合计各为 10.00，记录数各为 2。
            A 的第 2 条订单金额为 NULL；它仍计入记录数，并在独立收集的明细袋中保留 NULL。
            B 的明细金额为 7.50 和 2.50。明细袋不承诺元素顺序。

            同一每记录金额计算显式绑定真实金额 SUM 与记录 COUNT 两个测量实例，
            依各自 EUR／ONE 数量合同按客户对齐，基数为 10.00 / 2 = 5.00。
            两个 wrapper 实例分别固定 factor=1、factor=2，查询只填写 offset=1.00，
            因此 measurement-business-result.json 中每个客户的两个结果分别为 6.00、11.00。
            固定实参与查询实参由共同计算规则绑定。事实 SUM 仍为精确十进制；
            均值及其后续调整明确采用 64 位有界浮点计算与输出，评价域为 [-10^12,10^12]，
            绝对容差 10^-9、相对容差 10^-12，溢出失败。6.00／11.00 是本例数据的独立预期，
            不表示通用均值承诺精确十进制运算。

            SUM／COUNT 与明细成员分别计算，明细经 ROW、REGROUP、ARRAY_AGG 后按客户连接。
            business-result.json 保留无损数量、NULL、ROW 和 ARRAY，compiled-query.json 保存技术证据。

            错误实例引用在 BIND_INPUT 被拒绝；实际金额列改为 VARCHAR 后，持有原目录的请求在
            VERIFY_SOURCE 以 SOURCE_COLUMN_TYPE_DRIFT 失败。两个失败均保留结构化诊断。

            本例是人工确定性 library 调用，不包含治理发布或自主模型过程。
        """.trimIndent())
        println("内核订单示例完成：$output")
    }

    private fun describe(connection: Connection, spec: JdbcConnectionSpec, control: DeadlineExecutionControl, registry: ConnectorRegistry): ExecutablePhysicalExecutionBinding {
        val metadata = connection.metaData
        val table = JdbcTableConstraints.Table(connection.catalog, connection.schema, "orders")
        val constraints = (registry.require(spec.connectorRef).access as JdbcAccess).jdbc.constraints(connection, table, control)
        val refs = sortedMapOf<String, String>()
        val names = sortedMapOf<String, String>()
        val types = sortedMapOf<String, JdbcColumnType>()
        val expressions = sortedMapOf<String, SemanticExpression>()
        metadata.getColumns(table.catalog, table.schema, table.table, null).use { columns ->
            while (columns.next()) {
                control.checkCancellation()
                if (table.catalog != columns.getString("TABLE_CAT") || table.schema != columns.getString("TABLE_SCHEM") || table.table != columns.getString("TABLE_NAME")) continue
                val name = columns.getString("COLUMN_NAME")
                val nullable = columns.getInt("NULLABLE")
                if (nullable == DatabaseMetaData.columnNullableUnknown) throw SQLException("源列的 NULL 条件未确定：$name")
                val precision = columns.getInt("COLUMN_SIZE").let { if (columns.wasNull()) null else it }
                val scale = columns.getInt("DECIMAL_DIGITS").let { if (columns.wasNull()) null else it }
                val id = "orders_$name"
                refs[name] = "$id@1"
                names["$id@1"] = name
                types["$id@1"] = JdbcColumnType(columns.getInt("DATA_TYPE"), columns.getString("TYPE_NAME"), precision, scale, nullable == DatabaseMetaData.columnNullable)
                expressions["$name@1"] = SemanticExpression.Reference(SemanticExpression.ReferenceKind.PHYSICAL_COLUMN, id, 1)
            }
        }
        check(refs.keys == setOf("order_id", "customer", "amount")) { "实际订单列与示例声明不同" }
        val hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(OlapJsonUtils.mapper().writeValueAsBytes(mapOf("columns" to types, "constraints" to constraints))))
        val evidence = PhysicalSourceConstraints("jdbc-description-$hash", hash, spec.sourceIdentityHash(), spec.connectorRef,
            metadata.databaseProductName, metadata.databaseProductVersion, metadata.driverName, metadata.driverVersion, constraints, refs, emptyList(),
            StructureProvenance(StructureProvenance.Kind.DATABASE_CATALOG, metadata.databaseProductName, hash))
        return ExecutablePhysicalExecutionBinding(BINDING, "1", EVENT, "1", null, null, "orders-table", "1", "orders-table", "1", SOURCE,
            spec.connectorRef, registry.require(spec.connectorRef).compilation.tablePath(table.catalog, table.schema, table.table), spec.dialectId,
            table.catalog, table.schema, table.table, evidence, emptyList(), emptyMap(), expressions, names, types)
    }

    private fun catalog(physical: ExecutablePhysicalExecutionBinding): ExecutableCatalog {
        val units = mapOf("order_id" to ScalarValueType.NUMBER, "customer" to ScalarValueType.TEXT, "amount" to ScalarValueType.NUMBER).map { (id, type) ->
            "$id@1" to ExecutableLogicUnit(id, "1", type, null, TOPOLOGY, "1", id, id, BINDING, "1")
        }.toMap()
        val event = ExecutableEvent(EVENT, "1", TOPOLOGY, "1", "订单记录", EventIdentity(listOf(EventIdentity.LogicUnitRef("order_id", 1)),
            mapOf("order" to listOf(EventIdentity.LogicUnitRef("order_id", 1)), "customer" to listOf(EventIdentity.LogicUnitRef("customer", 1)))),
            mapOf("order" to LOCAL, "customer" to LOCAL), null, null, null)
        val mapping = ExecutableAnalysisDimensionEventMapping("orders-customer-mapping", "customers", "1", "customer", EVENT, "1",
            "customer", "1", "customer-binding", "1", "customer", "1", null, ValueAlignmentContract.SINGLE_EVENT_ONLY)
        val metrics = linkedMapOf(
            AMOUNT_DEFINITION.key() to fact(AMOUNT_DEFINITION, "amount", MetricAggregation.SUM, MONEY),
            COUNT_DEFINITION.key() to fact(COUNT_DEFINITION, "order_id", MetricAggregation.COUNT, COUNT),
            PER_RECORD_DEFINITION.key() to ExecutableMetric(PER_RECORD_DEFINITION, perRecord()),
        )
        val bindings = CalculationBindings(mapOf("customer" to CalculationBindings.Dimension(CUSTOMER, "customer", listOf("orders-customer-mapping"))), emptyMap(), emptyMap(), mapOf("amount" to AMOUNT_INSTANCE, "records" to COUNT_INSTANCE), emptyMap())
        val instances = linkedMapOf(
            AMOUNT_INSTANCE.id to MetricInstance(AMOUNT_DEFINITION, CalculationBindings.empty(), emptyMap(), "金额合计"),
            COUNT_INSTANCE.id to MetricInstance(COUNT_DEFINITION, CalculationBindings.empty(), emptyMap(), "订单记录数"),
            BASE_PER_RECORD.id to MetricInstance(PER_RECORD_DEFINITION, bindings, mapOf("factor" to number("1")), "每记录金额×1后调整"),
            DOUBLE_PER_RECORD.id to MetricInstance(PER_RECORD_DEFINITION, bindings, mapOf("factor" to number("2")), "每记录金额×2后调整"),
        )
        val outputs = linkedMapOf<String, MemberSourceDefinition.Output>(
            "order_id" to MemberSourceDefinition.PropertyOutput("订单编号", "order_id", ScalarValueType.NUMBER, "order_id", 1, LOCAL, null),
            "customer" to MemberSourceDefinition.DimensionOutput("客户", "customer", ScalarValueType.TEXT, "customer", 1, "customer-binding", 1, null),
            "amount" to MemberSourceDefinition.PropertyOutput("金额", "amount", ScalarValueType.NUMBER, "amount", 1, LOCAL, MONEY),
        )
        val member = MemberSource.Projection("order-records", 1, MemberSourceDefinition.Projection(EventRef(EVENT, 1),
            MemberResult("order", "order", MemberShape.RECORDS, listOf("order_id"), outputs, emptyMap(), null, emptyMap(), "逐条订单明细，保留未知金额")),
            mapOf("order_id" to logical("order_id"), "customer" to logical("customer"), "amount" to logical("amount")))
        return ExecutableCatalog(ExecutableCatalogManifest(CATALOG, ExecutableCatalogManifest.SCHEMA_VERSION), metrics, instances,
            mapOf("${member.id}@1" to member), emptyMap(), mapOf("customers@1" to ExecutableAnalysisDimension("customers", "1", "客户", "customer", "customer", "customer",
                listOf(ExecutableAnalysisDimensionLevel("customer", "客户", null)), listOf(mapping))), mapOf("$EVENT@1" to event),
            mapOf("$TOPOLOGY@1" to ExecutableTopology(TOPOLOGY, "1", "kernel-example", "订单源", "orders-table", "1", listOf(ExecutableEventRef(EVENT, "1")))),
            mapOf("customer@1" to ExecutableDimension("customer", "1", "customer", "kernel-example", "customer", "客户")),
            mapOf("customer-binding@1" to ExecutableDimensionBinding("customer-binding", "1", "customer", "1", EVENT, "1", TOPOLOGY, "1", "customer", "1",
                logical("customer"), LOCAL, emptyMap(), emptyList(), BINDING, "1")), units, emptyMap(), emptyMap(), mapOf("$BINDING@1" to physical))
    }

    private fun fact(reference: MetricRef, input: String, aggregation: MetricAggregation, contract: MetricValueContract) = ExecutableMetric(reference,
        MetricDefinition.FactMeasure(EventRef(EVENT, 1), logical(input), aggregation, false, null, emptyMap(), null, contract, MetricValueContract.Rounding.NONE))

    private fun perRecord(): MetricCalculation {
        val customer = Calculation.DimensionSlot("customer", "customer", 1, "customer")
        val parameters = mapOf(
            "factor" to Calculation.Parameter(Calculation.ParameterKind.SCALAR, "NUMBER", emptyList(), null, COUNT),
            "offset" to Calculation.Parameter(Calculation.ParameterKind.SCALAR, "NUMBER", emptyList(), null, MONEY),
        )
        val division = MetricValueContract(MEAN_MONEY.unit, MEAN_MONEY.arithmetic, MEAN_MONEY.result,
            mapOf("per_record" to MetricValueContract.PreserveUnit(MetricValueContract.BinaryOperator.DIVIDE, MetricValueContract.Operand.LEFT)), MEAN_MONEY.overflow)
        val multiplication = MetricValueContract(MEAN_MONEY.unit, MEAN_MONEY.arithmetic, MEAN_MONEY.result,
            mapOf("factor" to MetricValueContract.PreserveUnit(MetricValueContract.BinaryOperator.MULTIPLY, MetricValueContract.Operand.LEFT)), MEAN_MONEY.overflow)
        val perRecord = ResultNumeric(ResultOperation(ResultOperator.DIVIDE, listOf(symbol("selected_amount"), symbol("selected_records"))), division, "per_record")
        val scaled = ResultNumeric(ResultOperation(ResultOperator.MULTIPLY, listOf(perRecord, ResultArgument("factor", ScalarValueType.NUMBER))), multiplication, "factor")
        val root = Calculation.Measures(listOf(
            Calculation.Call("selected_amount", Calculation.Measurement("amount"), "selected_amount", emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), Calculation.Condition(DimensionCondition.Unrestricted())),
            Calculation.Call("selected_records", Calculation.Measurement("records"), "selected_records", emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), Calculation.Condition(DimensionCondition.Unrestricted()))),
            listOf("customer"), emptyList(), null, null, emptyMap(), Calculation.Population(listOf("selected_amount", "selected_records")), null)
        val computation = Calculation.Scope(root, emptyMap(), listOf(
            Derive(ResultNumeric(ResultOperation(ResultOperator.ADD, listOf(scaled, ResultArgument("offset", ScalarValueType.NUMBER))), MEAN_MONEY, null),
                "adjusted_per_record", "调整后的每记录金额"), Project(listOf("customer", "adjusted_per_record"))))
        return MetricCalculation(Calculation(listOf(customer), emptyList(), listOf(
            Calculation.MeasurementSlot("amount", emptyMap(), listOf(customer), emptyList(), MONEY),
            Calculation.MeasurementSlot("records", emptyMap(), listOf(customer), emptyList(), COUNT)), emptyList(), parameters, computation),
            "adjusted_per_record", listOf("customer"), MEAN_MONEY)
    }

    private fun request(requestId: String, sumInstance: MetricInstanceRef, countInstance: MetricInstanceRef): ExecutableQueryRequest {
        val members = QueryComputation.Scope(QueryComputation.Members("members", MemberRef("order-records", 1), emptyMap(), listOf(
            QueryComputation.Slot("order_id", "order_id"), QueryComputation.MappedDimension("customer", "customer", CUSTOMER), QueryComputation.Slot("amount", "amount")), null, emptyMap(), MemberCondition.Unrestricted(), emptyMap(), emptyMap()), emptyMap(), listOf(
            Derive(ResultRow(listOf(symbol("order_id"), symbol("amount"))), "order_row", "订单与金额"),
            Regroup(ResultGrouping(listOf("customer"), listOf(ResultAggregate("order_row", ResultAggregation.Collect(false), "orders", "订单明细袋")), null))))
        val measures = QueryComputation.Measures(listOf(QueryComputation.Invocation("sum_orders", sumInstance, "amount_sum", emptyMap(), emptyMap(), emptyMap(), DimensionCondition.Unrestricted()),
            QueryComputation.Invocation("count_orders", countInstance, "order_count", emptyMap(), emptyMap(), emptyMap(), DimensionCondition.Unrestricted())), listOf(CUSTOMER), null, emptyMap(),
            DimensionCondition.Unrestricted(), DimensionCondition.Unrestricted(), emptyList(), RowPopulationContract.calls(listOf("sum_orders", "count_orders")), null)
        return ExecutableQueryRequest(ExecutableQueryRequest.FORMAT_VERSION, requestId, CATALOG, QueryComputation.Scope(measures,
            mapOf("detail" to QueryComputation.Input("RELATION", members)), listOf(
                JoinInput("detail", "detail", "LEFT", ResultOperation(ResultOperator.EQ, listOf(symbol("customer"), symbol("detail__customer")))),
                Project(listOf("customer", "amount_sum", "order_count", "detail__orders")), Sort(listOf(OrderKey("customer", "ASC", "LAST"))))))
    }

    private fun measurementRequest(): ExecutableQueryRequest {
        val root = QueryComputation.Measures(listOf(
            QueryComputation.Invocation("base_per_record", BASE_PER_RECORD, "base_per_record", mapOf("offset" to number("1")), emptyMap(), emptyMap(), DimensionCondition.Unrestricted()),
            QueryComputation.Invocation("double_per_record", DOUBLE_PER_RECORD, "double_per_record", mapOf("offset" to number("1")), emptyMap(), emptyMap(), DimensionCondition.Unrestricted())),
            listOf(CUSTOMER), null, emptyMap(), DimensionCondition.Unrestricted(), DimensionCondition.Unrestricted(), emptyList(),
            RowPopulationContract.calls(listOf("base_per_record", "double_per_record")), null)
        return ExecutableQueryRequest(ExecutableQueryRequest.FORMAT_VERSION, "kernel-orders-measurement-bindings", CATALOG,
            QueryComputation.Scope(root, emptyMap(), listOf(Project(listOf("customer", "base_per_record", "double_per_record")), Sort(listOf(OrderKey("customer", "ASC", "LAST"))))))
    }

    private fun requireMeasurements(result: ExecutableBusinessResult) {
        val remaining = mutableSetOf("A", "B")
        check(result.rows.size == 2) { "测量绑定结果应有两个客户" }
        result.rows.forEach { row ->
            check(row.size == 3 && remaining.remove(scalar(row[0])) && BigDecimal(scalar(row[1])).compareTo(BigDecimal("6.00")) == 0 &&
                BigDecimal(scalar(row[2])).compareTo(BigDecimal("11.00")) == 0) { "同一 wrapper 的测量绑定、固定 factor 或查询 offset 改变了独立预期" }
        }
        check(remaining.isEmpty()) { "测量绑定结果缺少客户" }
    }

    private fun requireOrders(result: ExecutableBusinessResult) {
        check(result.rows.size == 2) { "订单结果应有两个客户" }
        val expected = linkedMapOf("A" to mapOf(1L to BigDecimal("10.00"), 2L to null),
            "B" to mapOf(3L to BigDecimal("7.50"), 4L to BigDecimal("2.50")))
        result.rows.forEach { row ->
            val orders = expected.remove(scalar(row[0]))
            val bag = row[3]
            check(orders != null && BigDecimal(scalar(row[1])).compareTo(BigDecimal("10.00")) == 0 && scalar(row[2]) == "2" &&
                bag is ExecutableBusinessResult.ArrayValue) { "客户合计、记录数或明细袋与独立业务预期不同" }
            val remaining = orders.toMutableMap()
            bag.value.forEach { value ->
                check(value is ExecutableBusinessResult.RowValue && value.value.size == 2) { "订单明细必须保留二元 ROW" }
                val id = scalar(value.value[0]).toLong()
                check(remaining.containsKey(id)) { "订单明细人口重复或出现未知订单" }
                val amount = remaining.remove(id)
                val actual = value.value[1]
                check(if (amount == null) actual is ExecutableBusinessResult.NullValue else actual is ExecutableBusinessResult.ScalarValue &&
                    amount.compareTo(BigDecimal(scalar(actual))) == 0) { "订单明细金额或 NULL 含义被改变" }
            }
            check(remaining.isEmpty()) { "订单明细人口缺失" }
        }
        check(expected.isEmpty()) { "订单结果缺少客户" }
    }

    private fun scalar(value: ExecutableBusinessResult.Value): String {
        check(value is ExecutableBusinessResult.ScalarValue) { "预期无损标量结果" }
        return value.value
    }

    private fun expectFailure(output: Path, name: String, stage: String, code: String, operation: () -> Unit): Map<String, Any?> {
        try { operation() }
        catch (failure: EngineExecutionException) {
            val record = linkedMapOf("stage" to failure.stage, "code" to failure.code, "message" to failure.message,
                "causeType" to failure.cause?.javaClass?.name, "causeMessage" to failure.cause?.message, "evidence" to failure.evidenceContext)
            save(output.resolve("$name-failure.json"), record)
            check(failure.stage == stage && failure.code == code) { "失败没有落在预期的真实边界：$name" }
            return record
        }
        error("应该拒绝的请求意外成功：$name")
    }

    private fun observer(output: Path, name: String) = object : ExecutableExecutionObserver {
        override fun bindingCompleted(input: ExecutableInputScope) = save(output.resolve("$name-binding.json"), input)
        override fun stageAnalysisCompleted(analyses: List<QueryStageAnalysis.Analysis>) = save(output.resolve("$name-stages.json"), analyses)
        override fun sourceResolutionCompleted(sources: List<CompiledQuery.SourceEvidence>) = save(output.resolve("$name-sources.json"), sources)
        override fun loweringCompleted(loweringResult: ExecutableLoweringResult) = save(output.resolve("$name-lowering.json"), loweringResult)
    }

    private fun number(value: String) = Calculation.Scalar(ScalarValueType.NUMBER, value)
    private fun logical(id: String) = SemanticExpression.Reference(SemanticExpression.ReferenceKind.LOGIC_UNIT, id, 1)
    private fun symbol(name: String) = ResultSymbol(name)
    private fun save(path: Path, value: Any) {
        Files.write(path, OlapJsonUtils.mapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(value), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
    }
}
