package scenario

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"

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
	output    string
	trace     *os.File
	count     int
}

func connect(ctx context.Context, url, token, output string) *remote {
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
	return &remote{ctx: ctx, session: session, transport: transport, output: output, trace: trace}
}
func (r *remote) close() {
	defer r.transport.CloseIdleConnections()
	must(errors.Join(r.session.Close(), r.trace.Close()))
}
func (r *remote) tool(operation string, payload any) object {
	result, err := r.session.CallTool(r.ctx, &mcp.CallToolParams{Name: operation, Arguments: object{"payload": payload}})
	must(err)
	require(!result.IsError && len(result.Content) == 1, "MCP 响应不完整：%s", operation)
	text, ok := result.Content[0].(*mcp.TextContent)
	require(ok, "MCP 响应不是正式 JSON：%s", operation)
	envelope := obj(decode([]byte(text.Text)))
	r.count++
	must(json.NewEncoder(r.trace).Encode(object{"index": r.count, "operation": operation, "payload": payload, "response": envelope}))
	return envelope
}

// 读取完整交付只消费已保存的原始 MCP 消息，不再次提交原操作。
func (r *remote) deliveredTool(operation string, payload any) object {
	envelope := r.tool(operation, payload)
	result, ok := envelope["result"].(map[string]any)
	if !ok || result["delivery"] == nil {
		return envelope
	}
	require(result["delivery"] == "SAVED", "%s 完整响应未能交付，勿重新执行业务：%s", operation, encoded(result))
	read := obj(result["read"])
	require(read["operation"] == "read-response", "完整响应缺少正式读取入口")
	input := obj(read["payload"])
	reference := obj(input["reference"])
	var content bytes.Buffer
	offset := jsonInteger(input["offset"])
	require(offset == 0, "完整响应必须从 offset=0 读取")
	for {
		pageEnvelope := r.tool("read-response", merge(input, object{"offset": offset}))
		require(pageEnvelope["success"] == true, "保存响应读取失败：%s", encoded(pageEnvelope))
		page := obj(pageEnvelope["result"])
		require(jsonInteger(page["offset"]) == offset, "保存响应页未从请求的精确位置开始")
		content.WriteString(str(page["content"]))
		if page["nextOffset"] == nil {
			require(int64(content.Len()) == jsonInteger(page["totalBytes"]), "完整保存响应长度不符")
			break
		}
		next := jsonInteger(page["nextOffset"])
		require(next > offset && next == int64(content.Len()), "保存响应页未连续前进")
		offset = next
	}
	hash := sha256.Sum256(content.Bytes())
	require(fmt.Sprintf("%x", hash) == reference["sha256"], "完整保存响应 SHA-256 不符")
	rpc := obj(decode(content.Bytes()))
	require(rpc["error"] == nil, "保存的原始 MCP 调用有协议错误：%s", encoded(rpc["error"]))
	items := arr(obj(rpc["result"])["content"])
	require(len(items) == 1 && obj(items[0])["type"] == "text", "保存的完整 MCP 调用缺少正式 JSON 内容")
	return obj(decode([]byte(str(obj(items[0])["text"]))))
}

func jsonInteger(value any) int64 {
	number, ok := value.(json.Number)
	require(ok, "需要精确 JSON 整数，实际 %T", value)
	result, err := number.Int64()
	must(err)
	return result
}

func (r *remote) callStatus(operation string, payload any, success bool) object {
	envelope := r.deliveredTool(operation, payload)
	if operation == "execute-script" && envelope["result"] != nil {
		input := obj(payload)
		report := obj(envelope["result"])
		if sessionID, ok := report["sessionId"].(string); ok && sessionID != "" {
			if requested, _ := input["sessionId"].(string); requested == "" {
				defer cleanup(func() {
					closed := obj(r.call("close-script-session", object{"sessionId": sessionID}))
					require(closed["state"] == "CLOSED", "自建脚本会话尚未确认关闭：%s", encoded(closed))
				})
			}
			executionID := str(input["executionId"])
			for report["state"] == "RUNNING" {
				envelope = r.deliveredTool("wait-script", object{"executionId": executionID, "waitMillis": 10000})
				require(envelope["result"] != nil, "等待已有执行失败：%s", encoded(envelope))
				report = obj(envelope["result"])
				require(report["executionId"] == executionID && report["sessionId"] == sessionID, "等待响应改变了执行或会话身份")
			}
		}
	}
	if envelope["success"] != success {
		diagnostic := object{"requestId": envelope["requestId"], "error": envelope["error"]}
		if operation == "execute-script" && envelope["result"] != nil {
			report := obj(envelope["result"])
			diagnostic["diagnostics"] = report["diagnostics"]
		}
		require(false, "%s 实际结局错误：%s；完整调用见 %s", operation, encoded(diagnostic), filepath.Join(r.output, "mcp-calls.jsonl"))
	}
	return envelope
}
func (r *remote) call(operation string, payload any) any {
	return r.callStatus(operation, payload, true)["result"]
}
