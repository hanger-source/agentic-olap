package server

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"strings"
	"time"

	"github.com/hanger-source/agentic-olap/service/internal/application"
	"github.com/hanger-source/agentic-olap/service/internal/auth"
	"github.com/hanger-source/agentic-olap/service/internal/config"
	"github.com/hanger-source/agentic-olap/service/internal/runtime"
	"github.com/hanger-source/agentic-olap/service/internal/wire"
	"github.com/modelcontextprotocol/go-sdk/mcp"
)

type principalKey struct{}
type HTTP struct {
	config   config.Deployment
	app      *application.Application
	verifier *auth.Verifier
	sdk      *mcp.StreamableHTTPHandler
	version  string
}

func New(c config.Deployment, app *application.Application, version string) (*HTTP, error) {
	v, err := auth.New(c.Auth)
	if err != nil {
		return nil, err
	}
	s := &HTTP{config: c, app: app, verifier: v, version: version}
	s.sdk = mcp.NewStreamableHTTPHandler(s.session, &mcp.StreamableHTTPOptions{Stateless: true, JSONResponse: true, MaxRequestBodyBytes: int64(c.Limits.RequestBytes), DisableLocalhostProtection: true, Logger: slog.Default()})
	return s, nil
}
func (s *HTTP) Close() { s.verifier.Close() }
func matches(value string, patterns []string) bool {
	for _, p := range patterns {
		if strings.EqualFold(value, p) {
			return true
		}
		if strings.HasSuffix(p, "*") && strings.HasPrefix(strings.ToLower(value), strings.ToLower(strings.TrimSuffix(p, "*"))) {
			return true
		}
	}
	return false
}
func (s *HTTP) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if !matches(r.Host, s.config.Listen.AllowedHosts) {
		http.Error(w, "host is not authorized", http.StatusForbidden)
		return
	}
	if origin := r.Header.Get("Origin"); origin != "" && !matches(origin, s.config.Listen.AllowedOrigins) {
		http.Error(w, "origin is not authorized", http.StatusForbidden)
		return
	}
	if r.URL.Path == "/.well-known/oauth-protected-resource" || r.URL.Path == "/.well-known/oauth-protected-resource/mcp" {
		if r.Method != http.MethodGet {
			w.WriteHeader(http.StatusMethodNotAllowed)
			return
		}
		issuers := []string{}
		if s.config.Auth.Mode == "external-jwt" {
			issuers = append(issuers, s.config.Auth.Issuer)
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"resource": s.config.URL(), "authorization_servers": issuers, "bearer_methods_supported": []string{"header"}})
		return
	}
	if r.URL.Path != "/mcp" {
		http.NotFound(w, r)
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), time.Duration(s.config.Limits.RequestSeconds*float64(time.Second)))
	defer cancel()
	header := r.Header.Get("Authorization")
	if !strings.HasPrefix(header, "Bearer ") {
		metadata := strings.TrimSuffix(s.config.URL(), "/mcp") + "/.well-known/oauth-protected-resource/mcp"
		w.Header().Set("WWW-Authenticate", `Bearer resource_metadata="`+metadata+`"`)
		http.Error(w, "bearer required", http.StatusUnauthorized)
		return
	}
	p, err := s.verifier.Verify(ctx, strings.TrimPrefix(header, "Bearer "))
	if err != nil {
		http.Error(w, "bearer is not authorized", http.StatusUnauthorized)
		return
	}
	rawRequest, err := io.ReadAll(http.MaxBytesReader(w, r.Body, int64(s.config.Limits.RequestBytes)))
	_ = r.Body.Close()
	if err != nil {
		http.Error(w, "request body could not be read within the deployed input budget", http.StatusRequestEntityTooLarge)
		return
	}
	if trimmed := bytes.TrimSpace(rawRequest); len(trimmed) != 0 && trimmed[0] == '[' {
		http.Error(w, "MCP JSON-RPC batch requests are not supported; submit one complete request", http.StatusBadRequest)
		return
	}
	var rpc struct {
		ID     json.RawMessage `json:"id"`
		Method string          `json:"method"`
	}
	_ = json.Unmarshal(rawRequest, &rpc) // Invalid messages remain the SDK's responsibility.
	state := &responseState{method: rpc.Method, id: rpc.ID}
	ctx = context.WithValue(context.WithValue(ctx, principalKey{}, p), responseStateKey{}, state)
	ctx = application.WithMCPResponseID(ctx, rpc.ID)
	r = r.WithContext(ctx)
	r.Body = io.NopCloser(bytes.NewReader(rawRequest))
	buffer := &responseBuffer{header: make(http.Header), limit: s.config.Limits.ResponseBytes, store: s.app.ResponseStore()}
	s.sdk.ServeHTTP(buffer, r)
	s.deliver(w, buffer, state, p)
}
func (s *HTTP) session(r *http.Request) *mcp.Server {
	principal := r.Context().Value(principalKey{}).(runtime.Principal)
	operations, err := s.app.Operations(r.Context(), principal)
	server := mcp.NewServer(&mcp.Implementation{Name: "agentic-olap", Version: s.version}, &mcp.ServerOptions{Instructions: "先用 describe-script-api 发现正式 Kotlin 类型和能力，再通过 execute-script 提交完整 kts 程序。脚本可调查来源、管理定义、采用发布并组合查询；每次能力调用照常授权。工具参数放在 payload 中。", Logger: slog.Default()})
	if err != nil {
		slog.Warn("authorized operation discovery failed", "error", err)
		return nil
	}
	server.AddReceivingMiddleware(func(next mcp.MethodHandler) mcp.MethodHandler {
		return func(ctx context.Context, method string, request mcp.Request) (mcp.Result, error) {
			result, err := next(ctx, method, request)
			if state, ok := r.Context().Value(responseStateKey{}).(*responseState); ok {
				if method != "tools/call" || err != nil {
					state.success = err == nil
				}
			}
			if err != nil || method != "tools/list" {
				return result, err
			}
			page, ok := result.(*mcp.ListToolsResult)
			if !ok {
				return nil, errors.New("SDK tools discovery returned an unexpected result")
			}
			visible := make([]*mcp.Tool, 0, len(page.Tools))
			for _, tool := range page.Tools {
				if _, authorized := operations[tool.Name]; authorized {
					visible = append(visible, tool)
				}
			}
			page.Tools = visible
			return page, nil
		}
	})
	for operation, contract := range operations {
		if operation == "ready" || operation == "authorize-result" || operation == "complete-result-delivery" || operation == "operation-discovery" {
			continue
		}
		destructive, open := !contract.ReadOnly, false
		schema := json.RawMessage(`{"type":"object","properties":{"payload":{"type":"object"}},"required":["payload"],"additionalProperties":false}`)
		if operation == "execute-script" {
			schema = json.RawMessage(`{"type":"object","properties":{"payload":{"type":"object","properties":{"source":{"type":"string","description":"完整 Kotlin 脚本源码"},"sourceName":{"type":"string","description":"用于错误定位的 .kts 名称"},"dslApiVersion":{"type":"string","description":"describe-script-api 返回的精确 DSL API 版本"},"parameters":{"type":"object","description":"本次脚本的有界普通参数；无参数时传空对象。容量与计数规则见 describe-script-api 的 com.olap.prototype.dsl.ScriptArguments.rules；部署输入预算另外生效。"}},"required":["source","sourceName","dslApiVersion","parameters"],"additionalProperties":false}},"required":["payload"],"additionalProperties":false}`)
		}
		server.AddTool(&mcp.Tool{Name: operation, Description: contract.Description, InputSchema: schema, Annotations: &mcp.ToolAnnotations{ReadOnlyHint: contract.ReadOnly, DestructiveHint: &destructive, OpenWorldHint: &open}}, func(ctx context.Context, request *mcp.CallToolRequest) (*mcp.CallToolResult, error) {
			var args struct {
				Payload json.RawMessage `json:"payload"`
			}
			fields, err := wire.Object(request.Params.Arguments, s.config.Limits.InputDepth, s.config.Limits.InputNodes)
			if err == nil && (len(fields) != 1 || fields["payload"] == nil) {
				err = errors.New("tool arguments require only payload")
			}
			if err == nil {
				err = json.Unmarshal(request.Params.Arguments, &args)
			}
			if err != nil {
				err = &wire.InputError{Cause: err}
			}
			var response application.Envelope
			if err == nil {
				response, err = s.app.Invoke(ctx, principal, operation, args.Payload)
			}
			if err != nil {
				response = failure(operation, err)
			}
			if state, ok := r.Context().Value(responseStateKey{}).(*responseState); ok {
				state.operation, state.requestID, state.success = operation, response.RequestID, response.Success
			}
			raw, encodeErr := json.Marshal(response)
			if encodeErr != nil {
				return nil, encodeErr
			}
			return &mcp.CallToolResult{Content: []mcp.Content{&mcp.TextContent{Text: string(raw)}}}, nil
		})
	}
	return server
}
func failure(operation string, err error) application.Envelope {
	random := make([]byte, 16)
	_, _ = rand.Read(random)
	id := "mcp-" + hex.EncodeToString(random)
	code, message, stage := "APPLICATION_FAILED", "服务操作未完成，请按请求身份查看诊断记录", "APPLICATION"
	references := map[string]any{}
	var delivery *application.DeliveryFailure
	var input *wire.InputError
	if errors.As(err, &delivery) {
		code, message, stage = "RESULT_DELIVERY_FAILED", delivery.Error(), "RESULT_DELIVERY"
		references["runId"] = delivery.RunID
	} else if errors.Is(err, context.DeadlineExceeded) {
		code, message, stage = "CLIENT_WAIT_DEADLINE_EXCEEDED", "等待期限已结束；操作实际结局尚未确认，请核对运行记录", "CLIENT_WAIT"
		references["clientOutcome"] = "DEADLINE_EXCEEDED"
	} else if errors.Is(err, context.Canceled) {
		code, message, stage = "CLIENT_WAIT_CANCELLED", "客户端已停止等待；操作实际结局尚未确认，请核对运行记录", "CLIENT_WAIT"
		references["clientOutcome"] = "CANCELLED"
	} else if errors.As(err, &input) {
		code, stage, references = input.Code(), input.Stage(), input.Details()
		message = "请求不符合公开合同，请读取准确操作描述"
		if code == "JSON_INPUT_RESOURCE_LIMIT" {
			message = "JSON 输入超出本次实际结构预算，请按资源项、限制、观测值和路径修正"
		}
	}
	if stage == "CLIENT_WAIT" && (operation == "execute-query" || operation == "candidate-values") {
		references["sourceOutcome"] = map[string]any{"state": "UNKNOWN", "cancellation": "UNCONFIRMED"}
	}
	slog.Warn("service operation failed", "requestId", id, "operation", operation, "error", err)
	raw, _ := json.Marshal(map[string]any{"operation": operation, "stage": stage, "code": code, "message": message, "references": references})
	return application.Envelope{RequestID: id, Success: false, Result: json.RawMessage("null"), Error: raw}
}
