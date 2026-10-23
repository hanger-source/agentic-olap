package server

import (
	"bytes"
	"encoding/json"
	"log/slog"
	"net/http"

	"github.com/hanger-source/agentic-olap/service/internal/application"
	"github.com/hanger-source/agentic-olap/service/internal/responses"
	"github.com/hanger-source/agentic-olap/service/internal/runtime"
	"github.com/modelcontextprotocol/go-sdk/mcp"
)

// 最终 SDK 写出的完整消息是唯一的内联大小依据；越界后继续接收全部字节。
type responseBuffer struct {
	header        http.Header
	body          bytes.Buffer
	status, limit int
	store         responses.Store
	file          *responses.Writer
	failure       error
}

func (b *responseBuffer) Header() http.Header { return b.header }
func (b *responseBuffer) WriteHeader(status int) {
	if b.status == 0 {
		b.status = status
	}
}
func (b *responseBuffer) Write(p []byte) (int, error) {
	if b.failure != nil {
		return 0, b.failure
	}
	if b.file == nil && len(p) <= b.limit-b.body.Len() {
		return b.body.Write(p)
	}
	if b.file == nil {
		b.file, b.failure = b.store.Begin()
		if b.failure == nil {
			_, b.failure = b.file.Write(b.body.Bytes())
		}
		b.body.Reset()
	}
	if b.failure != nil {
		return 0, b.failure
	}
	n, err := b.file.Write(p)
	b.failure = err
	return n, err
}
func (b *responseBuffer) Flush() {}

type responseState struct {
	operation, requestID string
	method               string
	id                   json.RawMessage
	success              bool
}
type responseStateKey struct{}

func (s *HTTP) deliver(w http.ResponseWriter, b *responseBuffer, state *responseState, principal runtime.Principal) {
	if b.file != nil {
		defer b.file.Abort()
	}
	status := b.status
	if status == 0 {
		status = http.StatusOK
	}
	body := b.body.Bytes()
	if b.failure != nil || b.file != nil {
		if len(state.id) == 0 {
			slog.Error("response delivery could not retain JSON-RPC identity", "error", b.failure)
			http.Error(w, "response delivery could not retain request identity", http.StatusInternalServerError)
			return
		}
		if state.operation == "" {
			state.operation = state.method
		}
		var result any
		if b.failure == nil {
			ref, err := b.file.Commit(responses.Metadata{Subject: principal.Subject, Scopes: principal.Scopes,
				AuthorizationPolicyHash: s.app.Runtime.Ready().AuthorizationPolicyHash,
				Operation:               state.operation, RequestID: state.requestID, OriginalSuccess: state.success})
			b.failure = err
			if err == nil {
				location, err := b.store.Location(ref)
				b.failure = err
				if err == nil {
					result = map[string]any{"delivery": "SAVED", "message": "结果较大，完整响应已保存；按需读取，无需重新执行。",
						"location": location, "read": map[string]any{"operation": "read-response", "payload": map[string]any{"reference": ref, "offset": 0}}}
					slog.Info("complete MCP response saved", "requestId", state.requestID, "operation", state.operation, "location", location, "success", state.success)
				}
			}
		}
		if b.failure != nil {
			slog.Error("complete MCP response persistence failed", "requestId", state.requestID, "operation", state.operation, "operationSuccess", state.success, "error", b.failure)
			result = map[string]any{"delivery": "FAILED", "code": "RESPONSE_PERSISTENCE_FAILED", "message": "完整响应未能保存；操作状态保持，勿自动重新执行，请按请求标识查看服务诊断。"}
		}
		rawResult, _ := json.Marshal(result)
		errValue := json.RawMessage("null")
		if !state.success {
			errValue = json.RawMessage(`{"code":"ORIGINAL_OPERATION_NOT_SUCCEEDED","message":"原操作未成功；完整错误见已保存响应或服务诊断。"}`)
		}
		envelope, err := json.Marshal(application.Envelope{RequestID: state.requestID, Success: state.success, Result: rawResult, Error: errValue})
		if err != nil {
			http.Error(w, "response delivery encoding failed", http.StatusInternalServerError)
			return
		}
		if state.method == "tools/call" {
			body, err = json.Marshal(struct {
				JSONRPC string              `json:"jsonrpc"`
				ID      json.RawMessage     `json:"id"`
				Result  *mcp.CallToolResult `json:"result"`
			}{"2.0", state.id, &mcp.CallToolResult{Content: []mcp.Content{&mcp.TextContent{Text: string(envelope)}}}})
		} else {
			// 协议发现与初始化没有通用的成功提示结构，不能伪装成空工具列表。
			message := "完整协议响应已保存；读取入口见 data。"
			if b.failure != nil {
				message = "完整协议响应未能交付；交付信息见 data，原操作不重执行。"
			}
			body, err = json.Marshal(map[string]any{"jsonrpc": "2.0", "id": state.id, "error": map[string]any{
				"code": -32001, "message": message,
				"data": map[string]any{"originalSuccess": state.success, "delivery": result}}})
		}
		if err != nil {
			http.Error(w, "response delivery encoding failed", http.StatusInternalServerError)
			return
		}
		b.header.Set("Content-Type", "application/json")
		b.header.Del("Content-Length")
	}
	for key, values := range b.header {
		w.Header()[key] = values
	}
	w.WriteHeader(status)
	if _, err := w.Write(body); err != nil {
		slog.Warn("HTTP response delivery failed", "requestId", state.requestID, "error", err)
	}
}
