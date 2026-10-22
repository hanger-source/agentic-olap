package client

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"github.com/hanger-source/agentic-olap/service/internal/httpclient"
	"net/http"
	"net/url"
	"os"

	"github.com/modelcontextprotocol/go-sdk/mcp"
)

type bearerTransport struct {
	token     string
	transport http.RoundTripper
}

func (t bearerTransport) RoundTrip(r *http.Request) (*http.Response, error) {
	copy := r.Clone(r.Context())
	copy.Header = r.Header.Clone()
	copy.Header.Set("Authorization", "Bearer "+t.token)
	return t.transport.RoundTrip(copy)
}
func connect(ctx context.Context, address, token, version string) (*mcp.ClientSession, func(), error) {
	u, err := url.Parse(address)
	if err != nil || u.Hostname() == "" || (u.Scheme != "http" && u.Scheme != "https") || u.User != nil || u.Fragment != "" {
		return nil, nil, errors.New("explicit credential-free HTTP MCP URL required")
	}
	transport, err := httpclient.Transport()
	if err != nil {
		return nil, nil, err
	}
	httpClient := &http.Client{Transport: bearerTransport{token, transport}, CheckRedirect: func(req *http.Request, via []*http.Request) error {
		return errors.New("MCP endpoint redirects are not authorized")
	}}
	c := mcp.NewClient(&mcp.Implementation{Name: "agentic-olap-cli", Version: version}, nil)
	session, err := c.Connect(ctx, &mcp.StreamableClientTransport{Endpoint: address, HTTPClient: httpClient, MaxRetries: -1, DisableStandaloneSSE: true}, nil)
	if err != nil {
		transport.CloseIdleConnections()
		return nil, nil, err
	}
	return session, func() { session.Close(); transport.CloseIdleConnections() }, nil
}

func Invoke(ctx context.Context, address, token, tool string, payload json.RawMessage, version string) (int, error) {
	session, close, err := connect(ctx, address, token, version)
	if err != nil {
		return 1, err
	}
	defer close()
	out := json.NewEncoder(os.Stdout)
	out.SetEscapeHTML(false)
	if tool == "" {
		tools := []*mcp.Tool{}
		for tool, err := range session.Tools(ctx, nil) {
			if err != nil {
				return 1, err
			}
			tools = append(tools, tool)
		}
		return 0, out.Encode(map[string]any{"serverInfo": session.InitializeResult().ServerInfo, "protocolVersion": session.InitializeResult().ProtocolVersion, "tools": tools})
	}
	result, err := session.CallTool(ctx, &mcp.CallToolParams{Name: tool, Arguments: map[string]json.RawMessage{"payload": payload}})
	if err != nil {
		return 1, err
	}
	if err = out.Encode(result); err != nil {
		return 1, err
	}
	if result.IsError {
		return 1, nil
	}
	if len(result.Content) != 1 {
		return 1, errors.New("tool did not return its formal response")
	}
	text, ok := result.Content[0].(*mcp.TextContent)
	if !ok {
		return 1, errors.New("formal tool response must be text JSON")
	}
	var response struct {
		Success *bool `json:"success"`
	}
	if err = json.Unmarshal([]byte(text.Text), &response); err != nil || response.Success == nil {
		return 1, errors.New("formal tool response lacks execution status")
	}
	if !*response.Success {
		return 1, nil
	}
	return 0, nil
}

// Information observes the running service through its existing authorized MCP reads; it never queries business data.
func Information(ctx context.Context, address, token, version string) (map[string]any, error) {
	session, close, err := connect(ctx, address, token, version)
	if err != nil {
		return map[string]any{"status": "UNREACHABLE", "stage": "CONNECT_MCP", "message": err.Error()}, err
	}
	defer close()
	initialized := session.InitializeResult()
	if initialized.ServerInfo == nil || initialized.ServerInfo.Name != "agentic-olap" || initialized.ServerInfo.Version == "" {
		return nil, errors.New("MCP endpoint did not identify an Agentic OLAP service version")
	}
	report := map[string]any{"status": "REACHABLE", "version": initialized.ServerInfo.Version,
		"versionMatchesInstalled": initialized.ServerInfo.Version == version, "protocolVersion": initialized.ProtocolVersion,
		"sourceQuery": "NOT_CHECKED"}
	visible := map[string]bool{}
	for tool, err := range session.Tools(ctx, nil) {
		if err != nil {
			return report, fmt.Errorf("读取获准工具失败：%w", err)
		}
		visible[tool.Name] = true
	}
	checks := map[string]any{}
	report["checks"] = checks
	for _, operation := range []string{"read-connector-capabilities", "list-data-sources"} {
		if !visible[operation] {
			checks[operation] = map[string]any{"status": "NOT_AUTHORIZED"}
			continue
		}
		result, err := session.CallTool(ctx, &mcp.CallToolParams{Name: operation, Arguments: map[string]any{"payload": map[string]any{}}})
		if err != nil {
			checks[operation] = map[string]any{"status": "FAILED", "message": err.Error()}
			continue
		}
		var response map[string]any
		if !result.IsError && len(result.Content) == 1 {
			if text, ok := result.Content[0].(*mcp.TextContent); ok {
				err = json.Unmarshal([]byte(text.Text), &response)
			}
		}
		if err != nil || response == nil {
			checks[operation] = map[string]any{"status": "FAILED", "message": "service did not return its formal response"}
			continue
		}
		if response["success"] != true {
			checks[operation] = map[string]any{"status": "FAILED", "requestId": response["requestId"], "error": response["error"]}
			continue
		}
		checks[operation] = map[string]any{"status": "AVAILABLE", "result": response["result"]}
	}
	return report, nil
}
