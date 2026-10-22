package server

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
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
	r = r.WithContext(context.WithValue(ctx, principalKey{}, p))
	buffer := &responseBuffer{header: make(http.Header), limit: s.config.Limits.ResponseBytes}
	s.sdk.ServeHTTP(buffer, r)
	if buffer.failure != nil {
		http.Error(w, "response exceeds the deployed transport budget", http.StatusInternalServerError)
		return
	}
	for key, values := range buffer.header {
		w.Header()[key] = values
	}
	status := buffer.status
	if status == 0 {
		status = http.StatusOK
	}
	w.WriteHeader(status)
	if _, err = w.Write(buffer.body.Bytes()); err != nil {
		slog.Warn("HTTP response delivery failed", "error", err)
	}
}

type responseBuffer struct {
	header        http.Header
	body          bytes.Buffer
	status, limit int
	failure       error
}

func (b *responseBuffer) Header() http.Header { return b.header }
func (b *responseBuffer) WriteHeader(status int) {
	if b.status == 0 {
		b.status = status
	}
}
func (b *responseBuffer) Write(p []byte) (int, error) {
	if b.body.Len()+len(p) > b.limit {
		b.failure = errors.New("response budget exceeded")
		return 0, b.failure
	}
	return b.body.Write(p)
}
func (b *responseBuffer) Flush() {}
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
	for operation, contract := range s.app.Runtime.Ready().Operations {
		if operation == "ready" || operation == "authorize-result" || operation == "complete-result-delivery" || operation == "operation-discovery" {
			continue
		}
		destructive, open := !contract.ReadOnly, false
		schema := json.RawMessage(`{"type":"object","properties":{"payload":{"type":"object"}},"required":["payload"],"additionalProperties":false}`)
		if operation == "execute-script" {
			schema = json.RawMessage(`{"type":"object","properties":{"payload":{"type":"object","properties":{"source":{"type":"string","description":"完整 Kotlin 脚本源码"},"sourceName":{"type":"string","description":"用于错误定位的 .kts 名称"},"dslApiVersion":{"type":"string","description":"describe-script-api 返回的精确 DSL API 版本"},"parameters":{"type":"object","description":"本次脚本的显式参数；无参数时传空对象"}},"required":["source","sourceName","dslApiVersion","parameters"],"additionalProperties":false}},"required":["payload"],"additionalProperties":false}`)
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
		code, message = "INVALID_REQUEST", "请求或结果不符合公开合同，请读取准确操作描述"
		switch input.Cause.Error() {
		case "INPUT_DEPTH_EXCEEDED", "INPUT_NODES_EXCEEDED":
			code = input.Cause.Error()
		}
	}
	if stage == "CLIENT_WAIT" && (operation == "execute-query" || operation == "candidate-values") {
		references["sourceOutcome"] = map[string]any{"state": "UNKNOWN", "cancellation": "UNCONFIRMED"}
	}
	slog.Warn("service operation failed", "requestId", id, "operation", operation, "error", err)
	raw, _ := json.Marshal(map[string]any{"operation": operation, "stage": stage, "code": code, "message": message, "references": references})
	return application.Envelope{RequestID: id, Success: false, Result: json.RawMessage("null"), Error: raw}
}
