package scenario

import (
	"bufio"
	"bytes"
	"context"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"reflect"
	"strings"
	"time"

	"github.com/hanger-source/agentic-olap/tooling/internal/products"
)

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
	manager := identity.token(audience, "http-trial-manager")
	configuration := obj(read(w.Path("skills", "agentic-olap", "references", "server.example.json")))
	port := freePort()
	configuration["listen"] = object{"host": "127.0.0.1", "port": port, "allowedHosts": array{"127.0.0.1:*"}, "allowedOrigins": array{}}
	configuration["dataRoot"] = filepath.Join(root, "data")
	configuration["connectors"] = array{}
	configuration["dataSources"] = array{}
	if name == "server-http" {
		configuration["connectors"] = read(connectors)
		for _, path := range arr(configuration["connectors"]) {
			reference := obj(obj(read(str(path)))["connectorRef"])
			if reference["id"] == "h2" {
				configuration["dataSources"] = array{object{"dataSourceId": "native-types", "connectorRef": reference, "dialectId": "h2",
					"identityParameters": object{"jdbcUrl": "jdbc:h2:mem:capability-types", "driverClassName": "org.h2.Driver"},
					"runtimeParameters":  object{}, "credentialEnvironmentVariables": object{}}}
			}
		}
		require(len(arr(configuration["dataSources"])) == 1, "服务试验需要一个精确 H2 来源以读取原生聚合映射")
	}
	limits := obj(configuration["limits"])
	limits["maxInFlight"] = 4
	limits["workerCount"] = 1
	sourceFiles := map[string]string{}
	if input != "" {
		configuration["dataSources"] = read(filepath.Join(input, "data-sources.json"))
		configuration["connectors"] = read(connectors)
		for _, value := range arr(configuration["dataSources"]) {
			source := obj(value)
			url := str(obj(source["identityParameters"])["jdbcUrl"])
			require(source["dialectId"] == "duckdb" && strings.HasPrefix(url, "jdbc:duckdb:"), "作者输入需要明确 DuckDB 文件来源")
			path := strings.TrimPrefix(url, "jdbc:duckdb:")
			hash, err := products.Digest(path)
			must(err)
			sourceFiles[path] = hash
		}
	}
	if httpSource {
		configuration["connectors"] = read(filepath.Join(fixture, "connectors.json"))
		configuration["dataSources"] = read(filepath.Join(fixture, "http-sources.json"))
		for _, source := range arr(configuration["dataSources"]) {
			require(obj(obj(source)["identityParameters"])["jdbcUrl"] == nil, "HTTP 产品进程不得取得 JDBC URL")
		}
	}
	originalGrant := obj(arr(obj(configuration["auth"])["grants"])[0])
	ids := array{}
	for _, source := range arr(configuration["dataSources"]) {
		ids = append(ids, obj(source)["dataSourceId"])
	}
	grant := merge(originalGrant, object{"subjects": array{"http-trial-manager"}, "dataSources": ids, "subjectAreas": array{"*"}, "releases": array{"*"}, "allGovernanceAssets": true})
	metadataGrant := object{"subjects": array{"metadata-only-manager"}, "scopes": array{"olap.manage"}, "operations": array{"describe-operation", "read-connector-capabilities", "discover-governance-reads", "describe-governance-read", "read-governance", "discover-governance-commands", "describe-governance-command", "write-governance", "inspect-warehouse", "execute-query"}, "dataSources": array{}, "subjectAreas": array{}, "releases": array{}, "allGovernanceAssets": true}
	configuration["auth"] = object{"mode": "external-jwt", "issuer": identity.address, "audience": audience, "jwksUri": identity.address + "jwks", "algorithms": array{"RS256"}, "grants": array{grant, metadataGrant}}
	configPath := filepath.Join(root, "server.json")
	write(configPath, configuration)
	environment := []string{"AGENTIC_OLAP_HOME=" + options.Distribution, "SSL_CERT_FILE=" + identity.ca, "NO_PROXY=127.0.0.1,localhost", "OLAP_TEST_HTTP_AUTH=Bearer fixture-query-access"}
	if options.Java != "" {
		environment = append(environment, "JAVA_HOME="+filepath.Dir(filepath.Dir(options.Java)))
	}
	service := &service{ctx: ctx, workspace: w, distribution: options.Distribution, root: root, config: configPath, address: net.JoinHostPort("127.0.0.1", fmt.Sprint(port)), token: manager, environment: environment}
	defer cleanup(service.stop)
	report := object{"passed": false, "serviceRuntime": "Go+Kotlin", "sourcesBefore": sourceFiles, "distribution": options.Distribution}
	defer cleanup(func() {
		identity.lock.Lock()
		report["issuerReads"] = append([]string{}, identity.reads...)
		identity.lock.Unlock()
		write(filepath.Join(root, "report.json"), report)
	})
	service.start("create")
	authenticationHTTP(service, identity, audience)
	var sourceBindings array
	first := service.remote("discovery")
	func() {
		defer cleanup(first.close)
		contractDiscovery(first)
		connectorCapabilityDiscovery(first)
		first.call("inspect-warehouse", object{"operation": "read-inspection-capability", "payload": object{}})
		sourceBindings = arr(first.call("list-data-sources", object{}))
		if input != "" && !httpSource {
			directInspection(first)
		}
		first.callStatus("write-governance", object{"operation": "create-business-line", "payload": object{"code": "forged_actor", "displayName": "错误主体", "owner": "business-owner", "description": "不得落盘", "createdBy": "forged-user"}}, false)
		first.write("create-business-line", object{"code": "http_trial", "displayName": "HTTP真实治理业务线", "owner": "business-owner", "description": "外部认证到持久元数据", "createdBy": first.actor})
	}()
	denied := connect(ctx, "http://"+service.address+"/mcp", identity.token(audience, "ungranted-user"), "ungranted-user", filepath.Join(root, "denied"))
	func() {
		defer cleanup(denied.close)
		count := 0
		for _, err := range denied.session.Tools(ctx, nil) {
			must(err)
			count++
		}
		require(count == 0, "无grant主体仍发现工具")
		response := denied.callStatus("write-governance", object{"operation": "create-business-line", "payload": object{"code": "denied", "displayName": "禁止", "owner": "owner", "description": nil, "createdBy": denied.actor}}, false)
		require(obj(response["error"])["code"] == "AUTHORIZATION_DENIED", "无grant主体未拒绝")
	}()
	metadata := connect(ctx, "http://"+service.address+"/mcp", identity.token(audience, "metadata-only-manager"), "metadata-only-manager", filepath.Join(root, "metadata-only"))
	func() {
		defer cleanup(metadata.close)
		metadata.write("create-business-line", object{"code": "metadata_only", "displayName": "无来源执行权限的治理主体", "owner": metadata.actor, "description": "治理不隐含来源或发布授权", "createdBy": metadata.actor})
		if len(sourceBindings) > 0 {
			source := obj(sourceBindings[0])
			visible := obj(metadata.call("read-connector-capabilities", object{}))
			require(len(arr(visible["connectors"])) == 0, "无来源授权的主体读取了连接器声明")
			mapping := metadata.callStatus("read-connector-capabilities", object{"aggregate": object{"connectorRef": source["connectorRef"], "aggregation": "AVG",
				"inputType": object{"name": "BIGINT", "precision": nil, "scale": nil, "nullable": true}, "grouped": false}}, false)
			require(obj(mapping["error"])["code"] == "AUTHORIZATION_DENIED", "操作权限不能代替连接器所属来源授权")
			response := metadata.callStatus("inspect-warehouse", object{"operation": "discover", "payload": object{"dataSourceId": source["dataSourceId"], "sourceIdentityHash": source["sourceIdentityHash"], "schemaPattern": "main", "limit": 10}}, false)
			require(obj(response["error"])["code"] == "AUTHORIZATION_DENIED", "元数据治理主体越过来源授权")
		}
	}()
	// CLI 与 SDK 都访问同一个正式 HTTP 产品入口。
	cliExercise(ctx, w, options, service)
	var saved object
	var publications []object
	var verify func(string, []string, array)
	if input != "" {
		business := service.remote("business")
		func() {
			defer cleanup(business.close)
			authored := obj(read(filepath.Join(input, "authored-definitions.json")))
			switch name {
			case "orders":
				if httpSource {
					business.declared = obj(read(filepath.Join(fixture, "declared-structure.json")))
					httpCapabilities(business)
				}
				publications = buildOrders(business, authored)
				saved = executeOrders(business, publications)
				verify = verifyOrders
			case "procurement":
				publication := buildProcurement(business, authored)
				publications = []object{publication}
				queries := procurementQueries(publication)
				write(filepath.Join(business.output, "bounded-result-query.json"), queries[0].Intent)
				saved = business.executeQueries(publication, queries, "procurement-reusable-comparison", verifyProcurement)
				verify = verifyProcurement
			case "subscriptions":
				publication := buildSubscriptions(business, authored)
				publications = []object{publication}
				subscriptionCandidates(business, obj(publication["capability"]))
				queries := subscriptionQueries(publication)
				write(filepath.Join(business.output, "bounded-result-query.json"), queries[1].Intent)
				saved = business.executeQueries(publication, queries, "subscriptions-reusable-history", verifySubscriptions)
				verify = verifySubscriptions
			}
			write(filepath.Join(business.output, "publications.json"), publications)
			report["business"] = business.report
		}()
		withoutExecution := connect(ctx, "http://"+service.address+"/mcp", identity.token(audience, "metadata-only-manager"), "metadata-only-manager", filepath.Join(root, "metadata-without-execution"))
		func() {
			defer cleanup(withoutExecution.close)
			withoutExecution.write("create-business-line", object{"code": "metadata_after_business", "displayName": "治理不依赖执行授权", "owner": withoutExecution.actor, "description": "真实发布存在后仍独立治理", "createdBy": withoutExecution.actor})
			query := obj(read(filepath.Join(root, "business", "bounded-result-query.json")))
			rejected := withoutExecution.callStatus("execute-query", object{"intent": query, "saveResult": true}, false)
			require(obj(rejected["error"])["code"] == "AUTHORIZATION_DENIED", "元数据治理主体越过真实发布执行授权")
		}()
		consumption := service.remote("result-consumption")
		func() {
			defer cleanup(consumption.close)
			executionEvidence(consumption, saved)
			for _, operation := range []string{"read-query-result-metadata", "read-query-result-value", "profile-query-result"} {
				failure := consumption.callStatus(operation, object{"resultReference": saved["resultReference"]}, false)
				require(obj(failure["error"])["code"] == "INVALID_REQUEST", "缺少必要参数未拒绝：%s", operation)
			}
			storageFailure(consumption, root, saved)
			if name == "orders" && !httpSource {
				localContractUse(consumption, obj(read(filepath.Join(root, "business", "bounded-result-query.json"))))
			}
		}()
		if name == "orders" && !httpSource {
			service.stop()
			businessLimits := clone(configuration["limits"])
			configuration["limits"] = merge(obj(businessLimits), object{"nativeMemoryBytes": 536870912, "inputNodes": 250000, "sqlBytes": 8388608, "parameters": 100000})
			write(configPath, configuration)
			service.start("large-result")
			large := service.remote("large-result-consumption")
			func() { defer cleanup(large.close); largeResult(large, obj(publications[0]["capability"]), root) }()
			service.stop()
			configuration["limits"] = businessLimits
			write(configPath, configuration)
			service.start("continued-business")
		}
	}
	service.stop()
	if httpSource {
		for _, value := range arr(configuration["dataSources"]) {
			runtime := obj(obj(value)["runtimeParameters"])
			require(integer(runtime["timeoutMillis"]) == 30000, "HTTP作者需要明确初始等待预算")
			runtime["timeoutMillis"] = 60000
		}
	}
	obj(configuration["limits"])["maxInFlight"] = 1
	write(configPath, configuration)
	service.start("reopen")
	reopened := service.remote("reopened")
	func() {
		defer cleanup(reopened.close)
		lines := string(encoded(reopened.read("list-business-lines", object{"offset": 0, "limit": 20})))
		require(strings.Contains(lines, "http_trial") && strings.Contains(lines, "metadata_only") && !strings.Contains(lines, "forged_actor"), "治理重启持久化或actor拒绝不成立")
		if saved != nil {
			reopened.reopen(saved, verify)
			if httpSource {
				current := arr(reopened.call("list-data-sources", object{}))
				require(len(sourceBindings) == len(current), "仅修改运行预算改变了来源绑定数量")
				for index, value := range current {
					before, after := obj(sourceBindings[index]), obj(value)
					for _, field := range []string{"dataSourceId", "sourceIdentityHash", "connectorRef", "dialectId"} {
						require(reflect.DeepEqual(before[field], after[field]), "仅修改运行预算改变了来源绑定：%s", field)
					}
				}
				receipt := obj(reopened.call("execute-query", object{"queryReference": saved["queryReference"], "saveResult": true, "question": "只调整来源运行预算后复用原发布与查询"}))
				require(receipt["status"] == "SUCCEEDED", "运行预算调整要求重新采用业务资产：%s", encoded(receipt))
				names, rows := reopened.rows(receipt["resultReference"])
				verify(str(saved["label"]), names, rows)
				write(filepath.Join(reopened.output, "runtime-parameter-change.json"), object{"before": sourceBindings, "after": current, "receipt": receipt, "rows": rows})
			}
		}
	}()
	admissionHTTP(service)
	service.stop()
	if name == "orders" && !httpSource {
		normal := clone(configuration["limits"])
		configuration["limits"] = merge(obj(normal), object{"requestSeconds": 5})
		write(configPath, configuration)
		service.start("body-deadline")
		requestBodyDeadlineHTTP(service, 5*time.Second)
		service.stop()
		configuration["limits"] = normal
		write(configPath, configuration)
	}
	for path, before := range sourceFiles {
		after, err := products.Digest(path)
		must(err)
		require(before == after, "只读服务改变业务来源：%s", path)
	}
	if saved != nil {
		bounded := obj(read(filepath.Join(root, "business", "bounded-result-query.json")))
		normal := clone(configuration["limits"])
		for field, code := range map[string]string{"resultRows": "RESULT_ROWS_EXCEEDED", "resultBytes": "RESULT_BYTES_EXCEEDED"} {
			configuration["limits"] = merge(obj(normal), object{field: 1})
			write(configPath, configuration)
			service.start("bounded-" + field)
			r := service.remote("bounded-read-" + field)
			func() { defer cleanup(r.close); boundedResult(r, bounded, code) }()
			service.stop()
		}
		configuration["limits"] = normal
		write(configPath, configuration)
	}
	if saved != nil {
		configuredSources, configuredConnectors := configuration["dataSources"], configuration["connectors"]
		configuration["dataSources"], configuration["connectors"] = array{}, array{}
		write(configPath, configuration)
		service.start("saved-result-without-source")
		r := service.remote("saved-result-without-source")
		func() { defer cleanup(r.close); resultWithoutExecutionSource(r, saved, verify) }()
		service.stop()
		configuration["dataSources"], configuration["connectors"] = configuredSources, configuredConnectors
		write(configPath, configuration)
	}
	if name == "orders" {
		exampleArguments(ctx, w, "orders", filepath.Join(options.Output, "advance-arguments.json"), []string{"--connectors", connectors, "advance-source", input})
		changed := false
		for path, before := range sourceFiles {
			after, err := products.Digest(path)
			must(err)
			changed = changed || after != before
		}
		require(changed, "作者新增支付没有改变真实来源")
		service.start("changed-source")
		r := service.remote("fresh-source")
		func() { defer cleanup(r.close); changedOrders(r, saved) }()
		service.stop()
	}
	report["passed"] = true
	if options.Name == "orders" {
		nested := options
		nested.Name = "http-source"
		nested.Output = filepath.Join(options.Output, "http-source")
		nested.BusinessInput = ""
		nested.HTTPFixture = ""
		must(Run(ctx, nested))
	}
}

func authenticationHTTP(s *service, identity *issuer, audience string) {
	requestBody := encoded(object{"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": object{"protocolVersion": "2025-11-25", "capabilities": object{}, "clientInfo": object{"name": "owned-trial", "version": "1"}}})
	transport := http.DefaultTransport.(*http.Transport).Clone()
	transport.Proxy = nil
	client := &http.Client{Transport: transport, Timeout: 10 * time.Second}
	for _, token := range []string{"", identity.token("https://wrong-audience.test/mcp", "http-trial-manager")} {
		request, err := http.NewRequestWithContext(s.ctx, "POST", "http://"+s.address+"/mcp", bytes.NewReader(requestBody))
		must(err)
		request.Header.Set("Accept", "application/json, text/event-stream")
		request.Header.Set("Content-Type", "application/json")
		if token != "" {
			request.Header.Set("Authorization", "Bearer "+token)
		}
		response, err := client.Do(request)
		must(err)
		io.Copy(io.Discard, response.Body)
		response.Body.Close()
		require(response.StatusCode == 401, "无认证/错误audience必须401，实际%d", response.StatusCode)
	}
}
func directInspection(r *remote) {
	observations := array{}
	for _, value := range arr(r.call("list-data-sources", object{})) {
		source := obj(value)
		base := object{"dataSourceId": source["dataSourceId"], "sourceIdentityHash": source["sourceIdentityHash"]}
		discovery := obj(r.call("inspect-warehouse", object{"operation": "discover", "payload": merge(base, object{"schemaPattern": "main", "tableTypes": array{"TABLE"}, "limit": 20})}))
		require(len(arr(discovery["tables"])) > 0, "实际来源未发现表")
		table := obj(arr(discovery["tables"])[0])["ref"]
		description := obj(r.call("inspect-warehouse", object{"operation": "describe", "payload": merge(base, object{"table": table})}))
		preview := obj(r.call("inspect-warehouse", object{"operation": "preview", "payload": merge(base, object{"table": table, "limit": 3})}))
		require(len(arr(description["columns"])) > 0 && len(arr(preview["rows"])) > 0, "实际来源未读取列或业务行")
		observations = append(observations, object{"discovery": discovery, "description": description, "preview": preview})
	}
	write(filepath.Join(r.output, "direct-inspection.json"), observations)
}
func httpCapabilities(r *remote) {
	source := r.source("example-orders")
	set := map[string]bool{}
	for _, value := range arr(source["accessCapabilities"]) {
		set[str(value)] = true
	}
	require(!set["STRUCTURE_INSPECTION"] && !set["PARAMETER_BINDING"] && !set["TRANSACTION_SNAPSHOT"] && set["CONSISTENT_READ"], "HTTP来源能力被误报JDBC")
	missing := r.callStatus("inspect-warehouse", object{"operation": "snapshot", "payload": object{"dataSourceId": source["dataSourceId"], "sourceIdentityHash": source["sourceIdentityHash"], "schemaName": "main", "limit": 10}}, false)
	require(obj(missing["error"])["code"] == "SOURCE_CAPABILITY_MISSING" && obj(obj(missing["error"])["details"])["capability"] == "STRUCTURE_INSPECTION", "缺调查能力未准确拒绝")
	r.report["unsupportedInspection"] = missing["error"]
}
func cliExercise(ctx context.Context, w products.Workspace, options Options, s *service) {
	binary := filepath.Join(options.Distribution, "runtime", "agentic-olap-runtime")
	for _, entry := range []struct {
		name      string
		arguments []string
		code      int
	}{{"client-list", []string{"tools"}, 0}, {"client-read", []string{"call", "read-governance", "--payload", string(encoded(object{"operation": "list-business-lines", "payload": object{"offset": 0, "limit": 20}}))}, 0}, {"client-denied", []string{"call", "write-governance", "--payload", string(encoded(object{"operation": "create-business-line", "payload": object{"code": "cli_forged", "displayName": "错误主体", "owner": "owner", "description": nil, "createdBy": "forged"}}))}, 1}} {
		args := append(entry.arguments, "--url", "http://"+s.address+"/mcp", "--token-env", "OLAP_TRIAL_TOKEN")
		command := exec.CommandContext(ctx, binary, args...)
		command.Dir = w.Root
		command.Env = append(os.Environ(), "NO_PROXY=127.0.0.1,localhost", "OLAP_TRIAL_TOKEN="+s.token)
		stdout, err := os.OpenFile(filepath.Join(s.root, entry.name+".stdout"), os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0644)
		must(err)
		stderr, err := os.OpenFile(filepath.Join(s.root, entry.name+".stderr"), os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0644)
		must(err)
		command.Stdout = stdout
		command.Stderr = stderr
		err = command.Run()
		stdout.Close()
		stderr.Close()
		code := 0
		if err != nil {
			exit, ok := err.(*exec.ExitError)
			require(ok, "CLI未启动：%v", err)
			code = exit.ExitCode()
		}
		require(code == entry.code, "%s 实际退出%d，应为%d", entry.name, code, entry.code)
	}
}
func admittedHTTPConnection(ctx context.Context, s *service) net.Conn {
	deadline, _ := ctx.Deadline()
	var connection net.Conn
	rejected := 0
	// TCP connect 成功不能证明已获准；前一会话关闭仍可能正在归还槽位。
	// 先在同一连接读取完整正常响应，再以其下一请求的半头占住所属槽位。
	for {
		var err error
		connection, err = (&net.Dialer{}).DialContext(ctx, "tcp", s.address)
		if err != nil {
			must(fmt.Errorf("HTTP准入占位未取得连接（此前明确收到%d次503）：%w", rejected, err))
		}
		if err = connection.SetDeadline(deadline); err != nil {
			connection.Close()
			must(err)
		}
		_, err = fmt.Fprintf(connection, "GET /.well-known/oauth-protected-resource/mcp HTTP/1.1\r\nHost: %s\r\n\r\n", s.address)
		if err != nil {
			connection.Close()
			must(err)
		}
		response, err := http.ReadResponse(bufio.NewReader(connection), nil)
		if err != nil {
			connection.Close()
			must(err)
		}
		_, readError := io.Copy(io.Discard, response.Body)
		closeError := response.Body.Close()
		if response.StatusCode == http.StatusServiceUnavailable && readError == nil && closeError == nil {
			must(connection.Close())
			rejected++
			continue
		}
		if readError != nil || closeError != nil || response.StatusCode != http.StatusOK || response.Close {
			connection.Close()
			must(readError)
			must(closeError)
			require(false, "准入占位未取得可复用连接：HTTP %d，close=%t", response.StatusCode, response.Close)
		}
		return connection
	}
}
func admissionHTTP(s *service) {
	ctx, cancel := context.WithTimeout(s.ctx, 10*time.Second)
	defer cancel()
	connection := admittedHTTPConnection(ctx, s)
	defer connection.Close()
	_, err := io.WriteString(connection, "POST /mcp HTTP/1.1\r\nHost: "+s.address+"\r\nContent-Length: 100\r\n")
	must(err)
	request, err := http.NewRequestWithContext(ctx, "POST", "http://"+s.address+"/mcp", bytes.NewReader(encoded(object{"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": object{"protocolVersion": "2025-11-25", "capabilities": object{}, "clientInfo": object{"name": "admission-trial", "version": "1"}}})))
	must(err)
	request.Header.Set("Authorization", "Bearer "+s.token)
	request.Header.Set("Accept", "application/json, text/event-stream")
	request.Header.Set("Content-Type", "application/json")
	transport := http.DefaultTransport.(*http.Transport).Clone()
	transport.Proxy = nil
	defer transport.CloseIdleConnections()
	response, err := (&http.Client{Transport: transport, Timeout: 10 * time.Second}).Do(request)
	must(err)
	response.Body.Close()
	require(response.StatusCode == 503, "已占满HTTP准入时应503，实际%d", response.StatusCode)
}

func requestBodyDeadlineHTTP(s *service, requestLimit time.Duration) {
	ctx, cancel := context.WithTimeout(s.ctx, requestLimit+10*time.Second)
	defer cancel()
	connection := admittedHTTPConnection(ctx, s)
	defer connection.Close()
	body := encoded(object{"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": object{"protocolVersion": "2025-11-25", "capabilities": object{}, "clientInfo": object{"name": "body-deadline-trial", "version": "1"}}})
	started := time.Now()
	_, err := fmt.Fprintf(connection, "POST /mcp HTTP/1.1\r\nHost: %s\r\nAuthorization: Bearer %s\r\nContent-Type: application/json\r\nAccept: application/json, text/event-stream\r\nConnection: close\r\nContent-Length: %d\r\n\r\n", s.address, s.token, len(body))
	must(err)
	_, err = connection.Write(body[:len(body)/2])
	must(err)
	reader := bufio.NewReader(connection)
	response, err := http.ReadResponse(reader, nil)
	must(err)
	diagnostic, err := io.ReadAll(response.Body)
	must(err)
	must(response.Body.Close())
	elapsed := time.Since(started)
	require(response.StatusCode == http.StatusBadRequest, "未完成body应被读取期限拒绝，实际HTTP %d：%s", response.StatusCode, diagnostic)
	require(elapsed >= requestLimit-time.Second, "未完成body在期限前被其它输入规则拒绝：%s", elapsed)
	_, err = reader.ReadByte()
	require(err == io.EOF, "body读取期限结束后连接未关闭：%v", err)
	must(connection.Close())
	// 恢复请求必须由另一个真实连接取得同一个容量为1的监听器名额。
	recovered := admittedHTTPConnection(ctx, s)
	must(recovered.Close())
	write(filepath.Join(s.root, "body-deadline.json"), object{"requestSeconds": requestLimit.Seconds(), "elapsedMillis": elapsed.Milliseconds(), "status": response.StatusCode, "connectionClosed": true, "recoveredStatus": http.StatusOK})
}

func contractDiscovery(r *remote) {
	full := obj(r.call("describe-governance-command", object{"operation": "create-member-source"}))
	selector := object{"operation": "create-member-source", "path": array{"definition"}, "variant": "PROJECTION"}
	local := obj(r.call("describe-governance-command", selector))
	repeated := r.call("describe-governance-command", selector)
	require(reflect.DeepEqual(local, repeated) && len(encoded(local)) < len(encoded(full)), "局部投影合同不稳定或仍返回全量")
	fields := map[string]bool{}
	for _, value := range arr(local["fields"]) {
		fields[str(obj(value)["name"])] = true
	}
	require(reflect.DeepEqual(fields, map[string]bool{"kind": true, "event": true, "result": true}), "局部PROJECTION缺少真实输入")
	leaf := obj(r.call("read-query-language-contract", object{"root": "MEMBERS", "path": array{"condition"}, "variant": "LEAF"}))
	regroup := obj(r.call("read-query-language-contract", object{"operation": "REGROUP"}))
	envelope := obj(r.call("read-query-language-contract", object{"section": "intent"}))
	measurement := r.call("read-query-language-contract", object{"root": "COMPUTE", "path": array{"calculation", "computation", "root"}, "variant": "MEASURES"})
	require(len(arr(obj(envelope["intentContract"])["requiredFields"])) == 6 && leaf["contentHash"] != regroup["contentHash"], "局部语言合同缺完整封套或精确摘要")
	for _, entry := range []struct {
		tool    string
		payload object
	}{{"describe-governance-command", merge(selector, object{"path": array{"missing"}})}, {"describe-governance-command", merge(selector, object{"variant": "UNKNOWN"})}, {"read-query-language-contract", object{"root": "UNKNOWN"}}, {"read-query-language-contract", object{"operation": "UNKNOWN"}}, {"read-query-language-contract", object{"section": "UNKNOWN"}}, {"read-query-language-contract", object{"root": "MEMBERS", "operation": "REGROUP"}}} {
		response := r.callStatus(entry.tool, entry.payload, false)
		require(obj(response["error"])["code"] == "INVALID_REQUEST", "未知selector未明确拒绝")
	}
	write(filepath.Join(r.output, "local-contract-discovery.json"), object{"projection": local, "memberLeaf": leaf, "regroup": regroup, "intent": envelope, "measurement": measurement})
}
