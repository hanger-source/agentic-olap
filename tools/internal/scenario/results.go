package scenario

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"unicode/utf8"

	"github.com/hanger-source/agentic-olap/tooling/internal/products"
)

func contentHash(value any) string {
	var buffer bytes.Buffer
	encoder := json.NewEncoder(&buffer)
	encoder.SetEscapeHTML(false)
	must(encoder.Encode(value))
	content := bytes.TrimSuffix(buffer.Bytes(), []byte{'\n'})
	sum := sha256.Sum256(content)
	return hex.EncodeToString(sum[:])
}

func connectorCapabilityDiscovery(r *remote) {
	contract := r.call("describe-operation", object{"operation": "read-connector-capabilities"})
	connectors := arr(obj(r.call("read-connector-capabilities", object{}))["connectors"])
	if len(connectors) == 0 {
		return
	}
	reference := obj(connectors[0])["connectorRef"]
	aggregate := object{"connectorRef": reference, "aggregation": "AVG", "inputType": object{"name": "BIGINT", "precision": nil, "scale": nil, "nullable": true}, "grouped": false}
	response := obj(r.call("read-connector-capabilities", object{"aggregate": aggregate}))
	mapping := obj(obj(response["aggregate"])["mapping"])
	require(mapping["status"] == "MAPPED" && obj(mapping["resultType"])["name"] != nil && mapping["aggregationRounding"] != nil, "正式 MCP 未交出该输入的原生 AVG 映射")
	unsupported := merge(aggregate, object{"inputType": object{"name": "VARCHAR", "precision": 10, "scale": nil, "nullable": true}})
	refused := obj(r.call("read-connector-capabilities", object{"aggregate": unsupported}))
	require(obj(obj(refused["aggregate"])["mapping"])["status"] == "UNAVAILABLE", "文本输入被误报为可用数值 AVG")
	invalid := r.callStatus("read-connector-capabilities", object{"aggregate": merge(aggregate, object{"grouped": "false"})}, false)
	problem := obj(invalid["error"])
	require(problem["code"] == "INVALID_REQUEST" && reflect.DeepEqual(obj(problem["details"])["fieldPath"], array{"aggregate", "grouped"}), "错误类型没有通过正式接口定位到输入字段")
	ungranted := merge(obj(reference), object{"sha256": strings.Repeat("0", 64)})
	denied := r.callStatus("read-connector-capabilities", object{"aggregate": merge(aggregate, object{"connectorRef": ungranted})}, false)
	require(obj(denied["error"])["code"] == "AUTHORIZATION_DENIED", "不能通过聚合发现读取未获准的精确插件")
	write(filepath.Join(r.output, "aggregate-capability.json"), object{"contract": contract, "connectors": connectors, "mapped": response, "unsupported": refused, "invalid": invalid, "denied": denied})
}
func directArtifact(artifact object) object {
	require(artifact["formatVersion"] == "olap-query-result/v3", "直接结果需要正式封存合同")
	body := []byte(str(artifact["resultJson"]))
	hash := sha256.Sum256(body)
	require(hex.EncodeToString(hash[:]) == artifact["sha256"], "直接结果字节与精确身份不符")
	return obj(decode(body))
}
func files(directory string) object {
	entries, err := os.ReadDir(directory)
	must(err)
	hashes := object{}
	for _, entry := range entries {
		if !entry.IsDir() {
			sum, err := products.Digest(filepath.Join(directory, entry.Name()))
			must(err)
			hashes[entry.Name()] = sum
		}
	}
	return hashes
}
func storageFailure(r *remote, root string, saved object) {
	directory := filepath.Join(root, "data", "results")
	before := files(directory)
	info, err := os.Stat(directory)
	must(err)
	must(os.Chmod(directory, 0500))
	var rejected object
	func() {
		defer cleanup(func() { must(os.Chmod(directory, info.Mode().Perm())) })
		rejected = r.callStatus("execute-query", object{"queryReference": saved["queryReference"], "saveResult": true, "question": "readonly-result-delivery"}, false)
	}()
	problem := obj(rejected["error"])
	require(problem["code"] == "RESULT_DELIVERY_FAILED", "真实只读目录未报告交付失败")
	id := obj(problem["references"])["runId"]
	usage := obj(r.call("read-query-usage", object{"runId": id}))
	run := obj(usage["run"])
	require(run["execution"] == "SUCCEEDED" && run["delivery"] == "FAILED" && run["result"] == nil, "保存失败改变了真实执行终态或伪造引用")
	search := obj(r.call("search-query-usage", object{"coordinate": saved["coordinate"], "text": "readonly-result-delivery", "offset": 0, "limit": 200}))
	items := arr(search["items"])
	require(len(items) == 1 && obj(items[0])["runId"] == id, "交付失败重跑了查询")
	require(reflect.DeepEqual(before, files(directory)), "交付失败留下半成品或改变历史结果")
	r.call("read-query-result", object{"resultReference": saved["resultReference"], "offset": 0, "limit": 1})
	write(filepath.Join(r.output, "storage-failure.json"), object{"response": rejected, "usage": usage})
}
func boundedResult(r *remote, query object, code string) {
	receipt := obj(r.call("execute-query", object{"intent": query, "saveResult": true, "question": "bounded-" + code}))
	require(receipt["status"] != "SUCCEEDED" && receipt["result"] == nil, "结果超限被伪装为成功")
	reference := receipt["resultReference"]
	status := obj(r.call("read-query-result-metadata", object{"resultReference": reference, "path": array{"status"}, "offset": 0, "limit": 200}))
	problem := obj(r.call("read-query-result-metadata", object{"resultReference": reference, "path": array{"error", "code"}, "offset": 0, "limit": 200}))
	page := obj(r.call("read-query-result", object{"resultReference": reference, "offset": 0, "limit": 10}))
	usage := obj(r.call("read-query-usage", object{"runId": receipt["runId"]}))
	execution := "FAILED"
	if receipt["status"] == "OUTCOME_UNKNOWN" {
		execution = "INTERRUPTED"
		require(obj(receipt["sourceOutcome"])["state"] == "UNKNOWN", "来源未知不能被包装成取消或失败终态")
	}
	require(status["text"] == receipt["status"] && problem["text"] == code && len(arr(page["rows"])) == 0 && obj(usage["run"])["execution"] == execution, "预算失败封套、空行与使用记录不一致")
	write(filepath.Join(r.output, "bounded-result.json"), object{"receipt": receipt, "usage": usage, "page": page, "error": problem})
}

func executionEvidence(r *remote, saved object) {
	usage := obj(r.call("read-query-usage", object{"runId": saved["runId"]}))
	facts := obj(obj(usage["run"])["executionEvidence"])
	require(obj(facts["sourceOutcome"])["state"] == "SUCCEEDED", "实际成功来源事实未持久化")
	observed := obj(r.call("read-query-result-metadata", object{"resultReference": saved["resultReference"], "path": array{"sourceReadEvidence", "request", "execution"}}))
	require(observed["text"] == "SUCCEEDED", "保存结果丢失来源成功回执")
	explanation := obj(r.call("explain-query", object{"queryReference": saved["queryReference"], "section": "scopes", "limit": 1}))
	require(len(arr(explanation["value"])) == 1 && obj(arr(explanation["value"])[0])["finalGrain"] != nil, "真实编译未交出本次作用域与已证明粒度")
	availability := obj(r.call("read-deployment-availability", object{"publications": array{saved["coordinate"]}, "queries": array{saved["queryReference"]}, "results": array{saved["resultReference"]}}))
	for _, entry := range arr(availability["checks"]) {
		require(obj(entry)["status"] == "AVAILABLE", "实际可用资产被错误拒绝：%s", encoded(entry))
	}
	write(filepath.Join(r.output, "execution-evidence.json"), object{"usage": usage, "explanation": explanation, "availability": availability})
}

func resultWithoutExecutionSource(r *remote, saved object, verify func(string, []string, array)) {
	names, rows := r.rows(saved["resultReference"])
	verify(str(saved["label"]), names, rows)
	availability := obj(r.call("read-deployment-availability", object{"publications": array{saved["coordinate"]}, "queries": array{saved["queryReference"]}, "results": array{saved["resultReference"]}}))
	resultReadable, executionUnavailable := false, false
	for _, entry := range arr(availability["checks"]) {
		check := obj(entry)
		if check["kind"] == "RESULT_READ" {
			resultReadable = check["status"] == "AVAILABLE"
		}
		if check["kind"] == "QUERY_EXECUTION" && check["status"] != "AVAILABLE" {
			executionUnavailable = true
		}
	}
	require(resultReadable && executionUnavailable, "保存结果读取被错误绑定到旧来源或执行可用性")
	write(filepath.Join(r.output, "availability-without-source.json"), availability)
}

func localContractUse(r *remote, query object) {
	capability := obj(obj(r.call("read-publication", coordinateFromIntent(query)))["capability"])
	rules := array{}
	for _, value := range arr(capability["postProcessCapabilities"]) {
		rule := obj(value)
		if rule["ruleType"] == "REGROUP" && rule["available"] == true {
			rules = append(rules, rule)
		}
	}
	if len(rules) == 0 {
		write(filepath.Join(r.output, "local-contract-use.json"), object{"status": "NOT_AUTHORIZED_FOR_THIS_PUBLICATION"})
		return
	}
	require(len(rules) == 1, "COUNT需要无歧义REGROUP授权")
	original := obj(r.call("execute-query", object{"intent": query, "saveResult": true}))
	require(original["status"] == "SUCCEEDED", "COUNT原人口执行失败")
	page := obj(r.call("read-query-result", object{"resultReference": original["resultReference"], "offset": 0, "limit": 200}))
	require(page["nextOffset"] == nil && len(arr(page["rows"])) > 0, "COUNT原人口必须完整非空")
	local := obj(r.call("read-query-language-contract", object{"operation": "REGROUP"}))
	rounding := obj(obj(obj(local["aggregateValueContract"])["byAggregation"])["COUNT"])["aggregationRounding"]
	require(reflect.DeepEqual(rounding, array{"NONE"}), "COUNT舍入合同不明确")
	counted := obj(clone(query))
	computation := obj(counted["computation"])
	name := obj(arr(page["columns"])[0])["name"]
	require(name != nil, "计数列无公开名字")
	computation["stages"] = append(arr(computation["stages"]), object{"opType": "REGROUP", "arguments": object{"groupBy": array{}, "groupingLevels": nil, "aggregates": array{object{"input": name, "aggregation": "COUNT", "nullPolicy": "IGNORE", "outputAlias": "discovered_count", "outputDisplayName": "已完成人口计数", "valueContract": nil, "aggregationRounding": "NONE"}}}})
	result := obj(r.call("execute-query", object{"intent": counted, "saveResult": true}))
	require(result["status"] == "SUCCEEDED", "COUNT按发现合同执行失败")
	_, rows := r.rows(result["resultReference"])
	expected := 0
	for _, row := range arr(page["rows"]) {
		if obj(arr(row)[0])["type"] != "NULL" {
			expected++
		}
	}
	verifyScalar("发现COUNT", rows, json.Number(string(encoded(expected))).String())
	write(filepath.Join(r.output, "local-contract-use.json"), object{"original": original, "result": result, "expected": expected, "rows": rows})
}
func coordinateFromIntent(query object) object {
	return object{"scenarioId": query["scenarioId"], "scenarioVersion": query["scenarioVersion"], "releaseId": query["releaseId"], "releaseVersion": query["releaseVersion"]}
}

const resultWidth = 1024

var longText = "调用者类型化输入-é-🙂-\n\"\\" + strings.Repeat("x", 16384)

func largeQuery(capability object) object {
	member := one(capability["memberCandidates"], "displayName", "本期合格客户")
	derive := func(name string, expression object) object {
		return object{"opType": "DERIVE", "arguments": object{"outputAlias": name, "outputDisplayName": name, "expression": expression}}
	}
	aggregate := func(input, output, function, nulls string) object {
		return object{"input": input, "aggregation": function, "nullPolicy": nulls, "outputAlias": output, "outputDisplayName": output, "valueContract": nil, "aggregationRounding": "NONE"}
	}
	wide := array{}
	for i := 0; i < resultWidth; i++ {
		wide = append(wide, symbol("customer_value"))
	}
	stages := array{derive("long_text", object{"kind": "TYPED_LITERAL", "valueType": "TEXT", "value": longText}), derive("nested_row", object{"kind": "ROW", "operands": array{symbol("customer"), symbol("long_text"), object{"kind": "LITERAL", "value": nil}}}), object{"opType": "REGROUP", "arguments": object{"groupBy": array{}, "groupingLevels": nil, "aggregates": array{aggregate("customer", "customer_value", "MIN", "IGNORE"), aggregate("long_text", "long_value", "MIN", "IGNORE"), aggregate("nested_row", "nested_values", "ARRAY_AGG", "RESPECT")}}}, derive("wide_row", object{"kind": "ROW", "operands": wide}), object{"opType": "PROJECT", "arguments": object{"symbols": array{"long_value", "nested_values", "wide_row"}}}}
	return scopedIntent(capability, scope(object{"kind": "MEMBERS", "callId": "qualified", "source": object{"id": member["sourceId"], "version": member["sourceVersion"]}, "arguments": object{"window": period("2026-01-01", "2026-02-01")}, "members": object{}, "relations": object{}, "outputs": array{object{"kind": "SLOT", "outputAlias": "customer", "slot": "customer"}}, "range": nil, "clocks": object{}, "condition": object{"kind": "TRUE"}}, nil, stages))
}

type resultConsumer struct {
	remote                    *remote
	reference                 any
	values, metadata, maximum int
}

func (c *resultConsumer) page(operation string, payload object) object {
	page := obj(c.remote.call(operation, merge(object{"resultReference": c.reference}, payload)))
	var buffer bytes.Buffer
	must(json.Indent(&buffer, encoded(page), "", "  "))
	size := 0
	for _, r := range buffer.String() {
		if r > 0xffff {
			size += 12
		} else if r > 127 {
			size += 6
		} else {
			size++
		}
	}
	require(size <= 256*1024, "正式结果页越过256KB预算")
	if size > c.maximum {
		c.maximum = size
	}
	return page
}
func (c *resultConsumer) value(view object) object {
	if view["valueReference"] == nil {
		return view
	}
	kind := str(view["type"])
	structured := kind == "ARRAY" || kind == "ROW"
	offset := any(0)
	pieces := array{}
	var text strings.Builder
	for {
		page := c.page("read-query-result-value", object{"valueReference": view["valueReference"], "offset": offset, "limit": 2000})
		c.values++
		require(integer(page["offset"]) == integer(offset) && page["type"] == kind && integer(page["totalLength"]) == integer(view["length"]), "值页改变原类型或长度")
		if structured {
			pieces = append(pieces, arr(page["members"])...)
		} else {
			text.WriteString(str(page["text"]))
		}
		next := page["nextOffset"]
		if next == nil {
			break
		}
		require(integer(next) > integer(offset), "值页没有前进")
		offset = next
	}
	var value any
	var length int
	if structured {
		members := array{}
		for _, piece := range pieces {
			members = append(members, c.value(obj(piece)))
		}
		value = members
		length = len(members)
	} else {
		value = text.String()
		length = utf8.RuneCountInString(text.String())
	}
	recovered := object{"type": kind, "value": value}
	require(length == integer(view["length"]) && contentHash(recovered) == obj(view["valueReference"])["sha256"], "值重组改变精确身份")
	return recovered
}
func (c *resultConsumer) metadataValue(view any) any {
	reference, ok := view.(map[string]any)
	if !ok || reference["metadataReference"] == nil {
		return view
	}
	ref := obj(reference["metadataReference"])
	offset := any(0)
	members := array{}
	var text strings.Builder
	var scalar any
	kind := ""
	for {
		page := c.page("read-query-result-metadata", object{"path": ref["path"], "offset": offset, "limit": 100})
		c.metadata++
		kind = str(page["kind"])
		switch kind {
		case "TEXT":
			text.WriteString(str(page["text"]))
		case "ARRAY", "OBJECT":
			members = append(members, arr(page["members"])...)
		default:
			scalar = page["value"]
		}
		next := page["nextOffset"]
		if next == nil {
			break
		}
		require(integer(next) > integer(offset), "元数据页没有前进")
		offset = next
	}
	var recovered any
	switch kind {
	case "TEXT":
		recovered = text.String()
	case "OBJECT":
		fields := object{}
		for _, entry := range members {
			member := obj(entry)
			fields[str(member["key"])] = c.metadataValue(member["value"])
		}
		recovered = fields
	case "ARRAY":
		items := array{}
		for _, entry := range members {
			member := obj(entry)
			require(integer(member["key"]) == len(items), "元数据数组页顺序改变")
			items = append(items, c.metadataValue(member["value"]))
		}
		recovered = items
	default:
		recovered = scalar
	}
	require(contentHash(recovered) == ref["sha256"], "元数据重组改变精确身份")
	return recovered
}

func largeResult(r *remote, capability object, root string) {
	query := largeQuery(capability)
	r.call("validate-query", object{"intent": query})
	directory := filepath.Join(root, "data", "results")
	before := files(directory)
	direct := r.callStatus("execute-query", object{"intent": query, "saveResult": false, "question": "large-direct-delivery"}, false)
	problem := obj(direct["error"])
	require(problem["code"] == "RESULT_DELIVERY_FAILED" && reflect.DeepEqual(before, files(directory)), "大结果直接交付未准确失败或偷偷保存")
	directUsage := obj(r.call("read-query-usage", object{"runId": obj(problem["references"])["runId"]}))
	require(obj(directUsage["run"])["execution"] == "SUCCEEDED" && obj(directUsage["run"])["delivery"] == "FAILED", "大结果交付失败丢失执行终态")
	receipt := obj(r.call("execute-query", object{"intent": query, "saveResult": true, "question": "explicit-large-result"}))
	require(receipt["status"] == "SUCCEEDED" && receipt["result"] == nil, "保存大结果未返回真实成功引用")
	consumer := &resultConsumer{remote: r, reference: receipt["resultReference"]}
	page := consumer.page("read-query-result", object{"offset": 0, "limit": 1})
	rows := arr(page["rows"])
	require(len(rows) == 1 && len(arr(rows[0])) == 3, "长值/嵌套/宽ROW不完整")
	recovered := array{}
	for _, value := range arr(rows[0]) {
		recovered = append(recovered, consumer.value(obj(value)))
	}
	text := object{"type": "TEXT", "value": longText}
	customer := object{"type": "TEXT", "value": "A"}
	wide := array{}
	for i := 0; i < resultWidth; i++ {
		wide = append(wide, customer)
	}
	expected := array{text, object{"type": "ARRAY", "value": array{object{"type": "ROW", "value": array{customer, text, object{"type": "NULL", "value": nil}}}}}, object{"type": "ROW", "value": wide}}
	require(reflect.DeepEqual(recovered, expected), "实际长值/嵌套值不等于独立作者输入")
	ids := array{}
	for index, value := range arr(page["columns"]) {
		column := obj(value)
		ids = append(ids, column["columnId"])
		schema := object{}
		for _, key := range []string{"columnId", "displayName", "type", "symbol"} {
			schema[key] = consumer.metadataValue(column[key])
		}
		symbol := obj(schema["symbol"])
		require(reflect.DeepEqual(consumer.metadataValue(column["name"]), symbol["name"]) && reflect.DeepEqual(consumer.metadataValue(column["symbolId"]), symbol["id"]), "基础摘要不等于原列身份")
		require(reflect.DeepEqual(consumer.metadataValue(column["displayName"]), schema["displayName"]), "列显示名摘要改变原值")
		original := consumer.metadataValue(object{"metadataReference": obj(consumer.page("read-query-result-metadata", object{"path": array{"result", "columns", index}, "offset": 0, "limit": 1})["metadataReference"])})
		require(reflect.DeepEqual(schema, original), "重组原四字段改变原始schema")
		typ := obj(schema["type"])
		summary := obj(column["typeSummary"])
		require(summary["kind"] == typ["kind"] && summary["nullable"] == typ["nullable"], "基础类型摘要与原列不符")
	}
	consumer.page("profile-query-result", object{"columnIds": ids})
	bad := r.callStatus("read-query-result", object{"resultReference": merge(obj(receipt["resultReference"]), object{"sha256": strings.Repeat("0", 64)})}, false)
	write(filepath.Join(r.output, "large-result.json"), object{"receipt": receipt, "directFailure": direct, "directUsage": directUsage, "valuePages": consumer.values, "metadataPages": consumer.metadata, "maxPageBytes": consumer.maximum, "mismatchedReference": bad})
}
