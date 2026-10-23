package scenario

import (
	"context"
	"crypto/rand"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"time"

	"github.com/hanger-source/agentic-olap/tooling/internal/products"
	"github.com/modelcontextprotocol/go-sdk/mcp"
)

// Go只准备来源、运行正式产品和提交完整脚本；业务定义与Query由Kotlin脚本构造。
func runHTTP(ctx context.Context, w products.Workspace, options Options) {
	name := options.Name
	httpSource := name == "http-source"
	if httpSource {
		name = "orders"
	}
	require(name == "orders" || name == "procurement" || name == "subscriptions" || name == "server-http", "未知正式场景：%s", name)
	connectors := options.Connectors
	if connectors == "" {
		var err error
		connectors, err = w.ReferenceConnectors(filepath.Join(options.Output, "connectors"))
		must(err)
	}
	input := options.BusinessInput
	if name != "server-http" && input == "" {
		input = filepath.Join(options.Output, "input")
		exampleArguments(ctx, w, name, filepath.Join(options.Output, "prepare-arguments.json"), []string{"--connectors", connectors, "prepare", input})
	}
	fixture := options.HTTPFixture
	if httpSource && fixture == "" {
		fixture = filepath.Join(options.Output, "fixture")
		build, err := w.ReadJVM()
		must(err)
		module, err := build.Module("connector-duckdb")
		must(err)
		plugin, err := module.Jar.Verify()
		must(err)
		arguments := filepath.Join(options.Output, "fixture-arguments.json")
		write(arguments, []string{input, fixture, connectors})
		launch := filepath.Join(options.Output, "fixture-launch.json")
		must(w.Gradle(ctx, ":integration-tests:prepareHttpSourceFixture", "-PfixtureArguments="+arguments, "-PduckdbConnectorJar="+plugin, "-PfixtureLaunchOutput="+launch))
		command := []string{}
		for _, value := range arr(obj(read(launch))["command"]) {
			command = append(command, str(value))
		}
		child := startProcess(ctx, w.Root, options.Output, "source", nil, command...)
		defer cleanup(child.stop)
		child.ready(ctx, func() bool { _, err := os.Stat(filepath.Join(fixture, "ready.json")); return err == nil })
	}
	root := filepath.Join(options.Output, "http")
	must(os.Mkdir(root, 0755))
	identity := newIssuer(root)
	defer cleanup(identity.close)
	audience := "https://owned-olap.test/mcp"
	configuration := obj(read(w.Path("skills", "agentic-olap", "references", "server.example.json")))
	port := freePort()
	configuration["listen"] = object{"host": "127.0.0.1", "port": port, "allowedHosts": array{"127.0.0.1:*"}, "allowedOrigins": array{}}
	configuration["dataRoot"] = filepath.Join(root, "data")
	configuration["connectors"], configuration["dataSources"] = array{}, array{}
	if input != "" {
		configuration["connectors"] = read(connectors)
		configuration["dataSources"] = read(filepath.Join(input, "data-sources.json"))
	}
	if httpSource {
		configuration["connectors"] = read(filepath.Join(fixture, "connectors.json"))
		configuration["dataSources"] = read(filepath.Join(fixture, "http-sources.json"))
	}
	sourceHashes := map[string]string{}
	if input != "" {
		for _, value := range arr(read(filepath.Join(input, "data-sources.json"))) {
			source := obj(value)
			url := str(obj(source["identityParameters"])["jdbcUrl"])
			require(strings.HasPrefix(url, "jdbc:duckdb:"), "示例数据准备必须明确为DuckDB文件")
			path := sourceFile(url)
			hash, err := products.Digest(path)
			must(err)
			sourceHashes[path] = hash
		}
	}
	ids := array{}
	for _, value := range arr(configuration["dataSources"]) {
		ids = append(ids, obj(value)["dataSourceId"])
	}
	grant := merge(obj(arr(obj(configuration["auth"])["grants"])[0]), object{"subjects": array{"http-trial-manager"}, "dataSources": ids, "subjectAreas": array{"*"}, "releases": array{"*"}, "allGovernanceAssets": true})
	configuration["auth"] = object{"mode": "external-jwt", "issuer": identity.address, "audience": audience, "jwksUri": identity.address + "jwks", "algorithms": array{"RS256"}, "grants": array{grant}}
	obj(configuration["limits"])["maxInFlight"] = 4
	obj(configuration["limits"])["workerCount"] = 1
	obj(configuration["scripting"])["workerCount"] = 1
	obj(configuration["scripting"])["maxCalls"] = 1000
	if name == "server-http" {
		obj(configuration["limits"])["responseBytes"] = 65536
	}
	configPath := filepath.Join(root, "server.json")
	write(configPath, configuration)
	environment := []string{"AGENTIC_OLAP_HOME=" + options.Distribution, "SSL_CERT_FILE=" + identity.ca, "NO_PROXY=127.0.0.1,localhost", "OLAP_TEST_HTTP_AUTH=Bearer fixture-query-access"}
	if options.Java != "" {
		environment = append(environment, "JAVA_HOME="+filepath.Dir(filepath.Dir(options.Java)))
	}
	s := &service{ctx: ctx, workspace: w, distribution: options.Distribution, root: root, config: configPath, address: net.JoinHostPort("127.0.0.1", fmt.Sprint(port)), token: identity.token(audience, "http-trial-manager"), environment: environment}
	defer cleanup(s.stop)
	report := object{"passed": false, "serviceRuntime": "Go+Kotlin", "distribution": options.Distribution}
	defer cleanup(func() { write(filepath.Join(root, "report.json"), report) })
	s.start("create")
	authenticationHTTP(s, identity, audience)
	first := s.remote("business")
	sourceBindings := first.call("list-data-sources", object{})
	api := obj(first.call("describe-script-api", object{}))
	version := str(api["dslApiVersion"])
	report["api"] = api
	first.call("execute-script", scriptPayload([]byte("val a = olap.sources.list(com.olap.prototype.source.contract.EmptyRequest)\nval b = olap.sources.list(com.olap.prototype.source.contract.EmptyRequest)\ncheck(a == b)\nolap.output(b)"), "source-discovery", version, object{}))
	if name == "server-http" {
		const source = `print("\u0001".repeat(14000))
olap.output(com.olap.prototype.dsl.ScriptValue.Text("after-large-print"))
Any()`
		payload := scriptPayload([]byte(source), "large-print-delivery", version, object{})
		printed := obj(first.call("execute-script", payload))
		require(printed["success"] == true && printed["state"] == "COMPLETED", "大打印交付改变了实际代码执行状态：%s", encoded(printed["diagnostics"]))
		outputs := arr(printed["outputs"])
		require(len(outputs) == 2, "大打印与显式值应完整有序交付，普通末值不自动输出；实际%d项", len(outputs))
		stdout, value := obj(outputs[0]), obj(outputs[1])
		expected := strings.Repeat("\u0001", 14000)
		require(stdout["kind"] == "STDOUT" && stdout["encoding"] == "UTF8" && stdout["content"] == expected,
			"完整STDOUT字节或顺序不符")
		require(value["kind"] == "VALUE" && obj(value["value"])["text"] == "after-large-print", "大打印后的显式输出未按顺序交付")
		// 复用客户端的真实调用留档观察保存及读取，不另造响应解包路径。
		trace, err := os.ReadFile(first.trace.Name())
		must(err)
		var delivery object
		submitted, pages := 0, 0
		for _, line := range strings.Split(string(trace), "\n") {
			if line == "" {
				continue
			}
			entry := obj(decode([]byte(line)))
			operation := entry["operation"]
			input := obj(entry["payload"])
			if (operation == "execute-script" || operation == "wait-script") && input["executionId"] == payload["executionId"] {
				if operation == "execute-script" {
					submitted++
				}
				if result, ok := obj(entry["response"])["result"].(map[string]any); ok && result["delivery"] == "SAVED" {
					delivery = result
				}
			}
			if operation == "read-response" && delivery != nil &&
				reflect.DeepEqual(input["reference"], obj(obj(delivery["read"])["payload"])["reference"]) {
				pages++
			}
		}
		require(submitted == 1 && delivery != nil && pages > 0, "大打印必须一次提交、自动完整保存并正式回读；提交%d次，读取%d页", submitted, pages)
		report["largePrintDelivery"] = object{"execution": printed, "savedDelivery": delivery, "stdoutBytes": len(expected),
			"executionsSubmitted": submitted, "responsePagesRead": pages}
	}
	var source []byte
	var refs object
	parameters := object{"phase": "build", "sourceId": "example-" + name}
	if httpSource {
		parameters["declaredStructure"] = read(filepath.Join(fixture, "declared-structure.json"))
		const unsupportedCatalog = `
val source = olap.sources.list(com.olap.prototype.source.contract.EmptyRequest).items.single {
    it.binding.dataSourceId == parameters.text("sourceId")
}
val failure = runCatching {
    olap.sources.inspect(com.olap.prototype.source.contract.SourceInspection.Snapshot(source.binding, null, "main", null, listOf("TABLE"), 10))
}.exceptionOrNull()
val rejected = failure as com.olap.prototype.dsl.transport.CapabilityFailureException
check(rejected.failure.code == "SOURCE_CAPABILITY_MISSING") { rejected.failure.toString() }
olap.output(com.olap.prototype.dsl.ScriptValue.Text(rejected.failure.code))
`
		report["unsupportedHttpCatalog"] = first.call("execute-script", scriptPayload([]byte(unsupportedCatalog), "unsupported-http-catalog", version, parameters))
	}
	if name != "server-http" {
		var err error
		source, err = os.ReadFile(w.Path("examples", name, "main.olap.kts"))
		must(err)
		built := obj(first.call("execute-script", scriptPayload(source, name, version, parameters)))
		refs = scriptReferences(built)
		report["build"] = built
	}
	first.close()
	denied := connect(ctx, "http://"+s.address+"/mcp", identity.token(audience, "ungranted-user"), filepath.Join(root, "denied"))
	count := 0
	for _, err := range denied.session.Tools(ctx, nil) {
		must(err)
		count++
	}
	require(count == 0, "无grant主体仍发现工具")
	deniedResult, deniedError := denied.session.CallTool(ctx, &mcp.CallToolParams{Name: "execute-script", Arguments: object{
		"payload": scriptPayload([]byte("error(\"must not run\")"), "denied", version, object{}),
	}})
	require(deniedError != nil || deniedResult != nil && deniedResult.IsError, "无grant主体仍能调用未公开工具")
	denied.close()
	s.stop()
	obj(configuration["limits"])["maxInFlight"] = 1
	write(configPath, configuration)
	s.start("reopen")
	if refs != nil {
		reopened := s.remote("reopened")
		sameBindings(sourceBindings, reopened.call("list-data-sources", object{}))
		parameters["phase"], parameters["query"], parameters["result"] = "reopen", refs["query"], refs["result"]
		report["reopen"] = reopened.call("execute-script", scriptPayload(source, name, version, parameters))
		reopened.close()
	}
	admissionHTTP(s)
	s.stop()
	if refs != nil {
		configuration["connectors"], configuration["dataSources"] = array{}, array{}
		write(configPath, configuration)
		s.start("saved-result-without-source")
		r := s.remote("saved-result-without-source")
		parameters["phase"] = "read-result"
		report["savedResultWithoutSource"] = r.call("execute-script", scriptPayload(source, name, version, parameters))
		r.close()
		s.stop()
	}
	if name == "orders" && !httpSource {
		requestSeconds := obj(configuration["limits"])["requestSeconds"]
		obj(configuration["limits"])["requestSeconds"] = 5
		write(configPath, configuration)
		s.start("body-deadline")
		requestBodyDeadlineHTTP(s, 5*time.Second)
		s.stop()
		obj(configuration["limits"])["requestSeconds"] = requestSeconds
	}
	for path, hash := range sourceHashes {
		actual, err := products.Digest(path)
		must(err)
		require(hash == actual, "只读服务改变业务来源：%s", path)
	}
	if name == "orders" && !httpSource {
		exampleArguments(ctx, w, name, filepath.Join(options.Output, "advance-source-arguments.json"), []string{"--connectors", connectors, "advance-source", input})
		for path, hash := range sourceHashes {
			actual, err := products.Digest(path)
			must(err)
			require(hash != actual, "业务来源没有发生预期变化：%s", path)
		}
		configuration["connectors"] = read(connectors)
		configuration["dataSources"] = read(filepath.Join(input, "data-sources.json"))
		write(configPath, configuration)
		s.start("changed-source")
		r := s.remote("changed-source")
		parameters["phase"] = "refresh-source"
		report["changedSource"] = r.call("execute-script", scriptPayload(source, name, version, parameters))
		r.close()
		s.stop()
	}
	report["passed"] = true
}

func scriptPayload(source []byte, name, version string, parameters object) object {
	identity := make([]byte, 16)
	_, err := rand.Read(identity)
	must(err)
	return object{"executionId": fmt.Sprintf("%s-%x", name, identity), "waitMillis": 10000,
		"source": string(source), "sourceName": name + ".kts", "dslApiVersion": version, "parameters": parameters}
}
func scriptReferences(report object) object {
	var references object
	for _, raw := range arr(report["outputs"]) {
		event := obj(raw)
		if event["kind"] != "VALUE" {
			continue
		}
		value := obj(event["value"])
		container, ok := value["object"].(map[string]any)
		if !ok {
			continue
		}
		values := obj(container["values"])
		if values["query"] == nil || values["result"] == nil {
			continue
		}
		require(references == nil, "作者程序输出了多份查询/结果引用，不能猜测其业务含义")
		query := obj(obj(values["query"])["reference"])["query"]
		result := obj(obj(values["result"])["reference"])["result"]
		require(query != nil && result != nil, "VALUE 输出必须包含已保存查询和结果的精确引用")
		references = object{"query": query, "result": result}
	}
	require(references != nil, "正式脚本必须显式输出已保存查询和结果的精确引用")
	return references
}

// 原生请求与当前声明的来源绑定没有被示例脚本改写。
func sameBindings(before, after any) {
	bindings := func(value any) object {
		result := object{}
		for _, item := range arr(obj(value)["items"]) {
			source := obj(item)
			key := string(encoded(source["binding"]))
			_, duplicate := result[key]
			require(!duplicate, "来源列表包含重复精确绑定：%s", key)
			capabilities := map[string]bool{}
			for _, capability := range arr(source["accessCapabilities"]) {
				capabilities[str(capability)] = true
			}
			// 能力在正式合同中是集合，跨JVM重启不要求其JSON枚举顺序相同。
			result[key] = merge(source, object{"accessCapabilities": capabilities})
		}
		return result
	}
	require(reflect.DeepEqual(bindings(before), bindings(after)), "来源绑定在只读运行中变化")
}
func sourceFile(url string) string { return strings.TrimPrefix(url, "jdbc:duckdb:") }
