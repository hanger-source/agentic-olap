package scenario

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"reflect"

	"github.com/modelcontextprotocol/go-sdk/mcp"
)

type object = map[string]any
type array = []any
type failure struct{ err error }

func must(err error) {
	if err != nil {
		panic(failure{err})
	}
}

// 清理仍执行；已发生的业务失败保持为首个诊断，清理失败附在其后。
func cleanup(action func()) {
	original := recover()
	var cleanupFailure any
	func() {
		defer func() { cleanupFailure = recover() }()
		action()
	}()
	if original != nil {
		if first, ok := original.(failure); ok {
			if closing, ok := cleanupFailure.(failure); ok {
				panic(failure{errors.Join(first.err, fmt.Errorf("场景清理失败：%w", closing.err))})
			}
		}
		if cleanupFailure != nil {
			fmt.Fprintln(os.Stderr, "场景清理失败：", cleanupFailure)
		}
		panic(original)
	}
	if cleanupFailure != nil {
		panic(cleanupFailure)
	}
}
func require(ok bool, format string, args ...any) {
	if !ok {
		panic(failure{fmt.Errorf(format, args...)})
	}
}
func obj(value any) object {
	result, ok := value.(map[string]any)
	require(ok, "需要 JSON 对象，实际 %T", value)
	return result
}
func arr(value any) array {
	result, ok := value.([]any)
	require(ok, "需要 JSON 数组，实际 %T", value)
	return result
}
func str(value any) string {
	result, ok := value.(string)
	require(ok, "需要 JSON 文本，实际 %T", value)
	return result
}
func integer(value any) int {
	switch n := value.(type) {
	case int:
		return n
	case json.Number:
		result, err := n.Int64()
		must(err)
		return int(result)
	}
	panic(failure{fmt.Errorf("需要精确 JSON 整数，实际 %T", value)})
}
func decode(data []byte) any {
	var result any
	d := json.NewDecoder(bytes.NewReader(data))
	d.UseNumber()
	must(d.Decode(&result))
	var extra any
	require(d.Decode(&extra) == io.EOF, "需要唯一 JSON 文档")
	return result
}
func encoded(value any) []byte { data, err := json.Marshal(value); must(err); return data }
func read(path string) any     { data, err := os.ReadFile(path); must(err); return decode(data) }
func write(path string, value any) {
	data, err := json.MarshalIndent(value, "", "  ")
	must(err)
	must(os.MkdirAll(filepath.Dir(path), 0755))
	must(os.WriteFile(path, append(data, '\n'), 0644))
}
func clone(value any) any { return decode(encoded(value)) }
func merge(base object, values object) object {
	result := object{}
	for k, v := range base {
		result[k] = v
	}
	for k, v := range values {
		result[k] = v
	}
	return result
}
func exactRef(asset object) object { return object{"id": asset["id"], "version": asset["version"]} }
func one(values any, key string, value any) object {
	var selected []object
	for _, entry := range arr(values) {
		item := obj(entry)
		if reflect.DeepEqual(item[key], value) {
			selected = append(selected, item)
		}
	}
	require(len(selected) == 1, "需要唯一 %s=%v，实际 %d", key, value, len(selected))
	return selected[0]
}

type bearer struct {
	token string
	next  http.RoundTripper
}

func (b bearer) RoundTrip(request *http.Request) (*http.Response, error) {
	copy := request.Clone(request.Context())
	copy.Header = request.Header.Clone()
	copy.Header.Set("Authorization", "Bearer "+b.token)
	return b.next.RoundTrip(copy)
}

type remote struct {
	ctx       context.Context
	session   *mcp.ClientSession
	transport *http.Transport
	actor     string
	output    string
	trace     *os.File
	count     int
	declared  object
	report    object
}

func connect(ctx context.Context, url, token, actor, output string) *remote {
	must(os.MkdirAll(output, 0755))
	trace, err := os.OpenFile(filepath.Join(output, "mcp-calls.jsonl"), os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0644)
	must(err)
	transport := http.DefaultTransport.(*http.Transport).Clone()
	transport.Proxy = nil
	client := mcp.NewClient(&mcp.Implementation{Name: "agentic-olap-real-scenario", Version: "1"}, nil)
	session, err := client.Connect(ctx, &mcp.StreamableClientTransport{Endpoint: url, HTTPClient: &http.Client{Transport: bearer{token, transport}, CheckRedirect: func(*http.Request, []*http.Request) error { return fmt.Errorf("正式 MCP 不能重定向凭据") }}, MaxRetries: -1, DisableStandaloneSSE: true}, nil)
	if err != nil {
		trace.Close()
		must(err)
	}
	return &remote{ctx: ctx, session: session, transport: transport, actor: actor, output: output, trace: trace, report: object{}}
}
func (r *remote) close() {
	defer r.transport.CloseIdleConnections()
	must(errors.Join(r.session.Close(), r.trace.Close()))
}
func (r *remote) callStatus(operation string, payload any, success bool) object {
	result, err := r.session.CallTool(r.ctx, &mcp.CallToolParams{Name: operation, Arguments: object{"payload": payload}})
	must(err)
	require(!result.IsError && len(result.Content) == 1, "MCP 响应不完整：%s", operation)
	text, ok := result.Content[0].(*mcp.TextContent)
	require(ok, "MCP 响应不是正式 JSON：%s", operation)
	envelope := obj(decode([]byte(text.Text)))
	r.count++
	must(json.NewEncoder(r.trace).Encode(object{"index": r.count, "operation": operation, "payload": payload, "response": envelope}))
	require(envelope["success"] == success, "%s 实际结局错误：%s", operation, encoded(envelope))
	return envelope
}
func (r *remote) call(operation string, payload any) any {
	return r.callStatus(operation, payload, true)["result"]
}
func (r *remote) read(operation string, payload any) any {
	return r.call("read-governance", object{"operation": operation, "payload": payload})
}
func (r *remote) pages(operation string, payload object) array {
	items := array{}
	offset := any(0)
	for {
		page := obj(r.read(operation, merge(payload, object{"offset": offset, "limit": 100})))
		items = append(items, arr(page["items"])...)
		offset = page["nextOffset"]
		if offset == nil {
			return items
		}
	}
}
func (r *remote) asset(assetType string, reference object) object {
	require(reference["id"] != nil && reference["version"] != nil, "作者读取必须提供明确资产身份与版本：%s", assetType)
	record := obj(r.read("read-asset-version", object{"assetType": assetType, "id": reference["id"], "version": reference["version"]}))
	// 作者后续引用使用本次已选择版本；领域记录保留自己的 currentVersion/subjectAreaVersion 字段。
	return merge(record, object{"version": reference["version"]})
}
func (r *remote) activate(kind string, reference object) any {
	return r.write("activate", object{"objectType": kind, "objectId": reference["id"], "objectVersion": reference["version"], "activatedBy": r.actor, "reason": "明确审核本例业务定义"})
}
func (r *remote) write(operation string, payload object) object {
	if operation == "discover-source" && r.declared != nil {
		operation = "register-source-structure"
		table := obj(clone(r.declared[str(payload["tablePattern"])]))
		for _, value := range arr(table["columns"]) {
			column := obj(value)
			delete(column, "autoIncrement")
			delete(column, "generated")
		}
		payload = object{"dataSourceId": payload["dataSourceId"], "sourceIdentityHash": payload["sourceIdentityHash"], "catalog": nil, "schemaName": "main", "authority": "http-source-fixture-owned-configuration", "revision": "orders-schema-v1", "tables": array{table}, "requestedBy": r.actor}
	}
	receipt := obj(r.call("write-governance", object{"operation": operation, "payload": payload}))
	require(receipt["formatVersion"] == "governance-write-receipt/v1", "不支持治理回执：%v", receipt["formatVersion"])
	result := obj(receipt["result"])
	if operation == "register-source-structure" {
		snapshot := obj(r.read("read-source-discovery", object{"snapshotId": result["snapshotId"]}))
		require(obj(snapshot["structureProvenance"])["kind"] == "DECLARED" && snapshot["driverName"] == nil, "声明结构不能伪造 JDBC 观察")
		for _, value := range r.pages("read-source-discovery-tables", object{"snapshotId": result["snapshotId"]}) {
			for _, c := range r.pages("read-source-discovery-columns", object{"discoveredTableId": obj(value)["id"]}) {
				require(obj(c)["autoIncrement"] == nil && obj(c)["generated"] == nil, "声明未知事实被填造")
			}
		}
	}
	if operation == "create-event" && r.declared != nil && r.report["rawBagEvent"] == nil {
		receipt := obj(r.call("write-governance", object{"operation": "create-event", "payload": merge(payload, object{"code": "http_unkeyed_bag", "displayName": "HTTP无键行袋事件", "identity": object{"recordKey": array{}, "entityRoles": object{}}, "roles": object{}, "description": "来源完整行袋，不声明唯一记录或实体身份"})}))
		require(receipt["formatVersion"] == "governance-write-receipt/v1", "无键事件需要正式写回执")
		bag := obj(receipt["result"])
		r.report["rawBagEvent"] = bag
		r.activate("EVENT", bag)
	}
	return result
}

func (r *remote) source(id string) object {
	values := arr(r.call("list-data-sources", object{}))
	selected := array{}
	for _, entry := range values {
		if obj(entry)["dataSourceId"] == id {
			selected = append(selected, entry)
		}
	}
	require(len(selected) == 1, "作者必须明确部署一份精确来源：%s", id)
	return obj(selected[0])
}
func (r *remote) rows(reference any) ([]string, array) {
	var columns any
	rows := array{}
	offset := any(0)
	for {
		page := obj(r.call("read-query-result", object{"resultReference": reference, "offset": offset, "limit": 1}))
		if columns != nil {
			require(reflect.DeepEqual(columns, page["columns"]), "不可变结果分页列发生变化")
		}
		columns = page["columns"]
		rows = append(rows, arr(page["rows"])...)
		offset = page["nextOffset"]
		if offset == nil {
			break
		}
	}
	names := []string{}
	for _, value := range arr(columns) {
		name := obj(value)["name"]
		if s, ok := name.(string); ok {
			names = append(names, s)
			continue
		}
		path := obj(obj(name)["metadataReference"])["path"]
		var text string
		offset = 0
		for {
			page := obj(r.call("read-query-result-metadata", object{"resultReference": reference, "path": path, "offset": offset, "limit": 2000}))
			require(page["kind"] == "TEXT", "列名元数据不是文本")
			text += str(page["text"])
			offset = page["nextOffset"]
			if offset == nil {
				break
			}
		}
		names = append(names, text)
	}
	return names, rows
}
