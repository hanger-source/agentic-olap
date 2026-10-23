package application

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"path/filepath"
	"slices"
	"unicode/utf8"

	"github.com/hanger-source/agentic-olap/service/internal/responses"
	"github.com/hanger-source/agentic-olap/service/internal/runtime"
	"github.com/hanger-source/agentic-olap/service/internal/wire"
	"github.com/modelcontextprotocol/go-sdk/mcp"
)

func (a *Application) ResponseStore() responses.Store {
	return responses.Store{Directory: filepath.Join(a.Config.DataRoot, "responses")}
}

type mcpResponseIDKey struct{}

// WithMCPResponseID carries only the exact transport identity. It does not
// identify or authorize the caller and must not replace the service request ID.
func WithMCPResponseID(ctx context.Context, id json.RawMessage) context.Context {
	return context.WithValue(ctx, mcpResponseIDKey{}, append(json.RawMessage(nil), id...))
}

func responseReadOperation() runtime.Operation {
	return runtime.Operation{
		ReadOnly:    true,
		Description: "按精确引用分段读取本人在同一作用域与授权策略下保存的完整原始 MCP 响应；不重执行原操作。",
		Payload:     json.RawMessage(`{"reference":{"responseId":"exact saved response ID","sha256":"SHA-256 of all original response bytes"},"offset":"nonnegative UTF-8 byte boundary; follow nextOffset","length":"optional positive requested bytes; effective page follows the current message budget"}`),
	}
}

func (a *Application) ReadResponse(ctx context.Context, principal runtime.Principal, payload json.RawMessage) (Envelope, error) {
	if err := ctx.Err(); err != nil {
		return Envelope{}, err
	}
	var input struct {
		Reference responses.Reference `json:"reference"`
		Offset    *int64              `json:"offset"`
		Length    *int64              `json:"length"`
	}
	decoder := json.NewDecoder(bytes.NewReader(payload))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&input); err != nil {
		return Envelope{}, &wire.InputError{Cause: err}
	}
	if err := decoder.Decode(new(any)); err != io.EOF {
		return Envelope{}, &wire.InputError{Cause: errors.New("read-response requires exactly one complete input object")}
	}
	if err := input.Reference.Validate(); err != nil {
		return Envelope{}, &wire.InputError{Cause: err}
	}
	if input.Offset == nil || *input.Offset < 0 || (input.Length != nil && *input.Length < 1) {
		return Envelope{}, &wire.InputError{Cause: errors.New("read-response requires an explicit nonnegative offset and an optional positive length")}
	}
	store := a.ResponseStore()
	metadata, err := store.Metadata(input.Reference)
	if err != nil {
		return Envelope{}, err
	}
	identity := make([]byte, 16)
	if _, err := rand.Read(identity); err != nil {
		return Envelope{}, err
	}
	requestID := "response-read-" + hex.EncodeToString(identity)
	policy := a.Runtime.Ready().AuthorizationPolicyHash
	if principal.Subject != metadata.Subject || !slices.Equal(responses.NormalizeScopes(principal.Scopes), metadata.Scopes) ||
		policy == "" || policy != metadata.AuthorizationPolicyHash {
		failure, err := json.Marshal(map[string]any{
			"operation": "read-response", "stage": "AUTHORIZATION", "code": "SAVED_RESPONSE_AUTHORIZATION_CHANGED",
			"message": "保存响应的主体、作用域或完整授权策略与本次不一致；原操作不会被执行。",
		})
		if err != nil {
			return Envelope{}, err
		}
		return Envelope{RequestID: requestID, Success: false, Result: json.RawMessage("null"), Error: failure}, nil
	}
	// This is a read-page transport size, not a limit on the stored response.
	// Start small, then measure the SDK result with its actual escaping layers.
	length := max(int64(4), int64(a.Config.Limits.ResponseBytes)/32)
	if input.Length != nil {
		length = min(length, *input.Length)
	}
	rpcID, hasRPCID := ctx.Value(mcpResponseIDKey{}).(json.RawMessage)
	for {
		if err := ctx.Err(); err != nil {
			return Envelope{}, err
		}
		page, err := store.Read(input.Reference, *input.Offset, length)
		if err != nil {
			return Envelope{}, err
		}
		encoded, err := json.Marshal(page)
		if err != nil {
			return Envelope{}, err
		}
		response := Envelope{RequestID: requestID, Success: true, Result: encoded, Error: json.RawMessage("null")}
		if !hasRPCID || len(rpcID) == 0 {
			// A different delivery owner measures its own actual envelope.
			return response, nil
		}
		raw, err := json.Marshal(response)
		if err != nil {
			return Envelope{}, err
		}
		message, err := json.Marshal(struct {
			JSONRPC string              `json:"jsonrpc"`
			ID      json.RawMessage     `json:"id"`
			Result  *mcp.CallToolResult `json:"result"`
		}{JSONRPC: "2.0", ID: rpcID, Result: &mcp.CallToolResult{Content: []mcp.Content{&mcp.TextContent{Text: string(raw)}}}})
		if err != nil {
			return Envelope{}, err
		}
		if int64(len(message)) <= int64(a.Config.Limits.ResponseBytes) {
			return response, nil
		}
		_, firstRuneBytes := utf8.DecodeRuneInString(page.Content)
		if len(page.Content) <= firstRuneBytes {
			return Envelope{}, errors.New("the current message budget cannot contain the saved response's exact range and reference")
		}
		length = max(int64(firstRuneBytes), min(length/2, int64(len(page.Content)-1)))
	}
}
