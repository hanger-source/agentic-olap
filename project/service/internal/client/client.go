package client

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"github.com/hanger-source/agentic-olap/service/internal/httpclient"
	governance "github.com/hanger-source/agentic-olap/service/internal/protocol/governance"
	query "github.com/hanger-source/agentic-olap/service/internal/protocol/query"
	"net/http"
	"net/url"
	"os"

	"github.com/modelcontextprotocol/go-sdk/mcp"
	"google.golang.org/protobuf/encoding/protojson"
	"google.golang.org/protobuf/types/known/anypb"
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
	if !visible["list-data-sources"] {
		checks["list-data-sources"] = map[string]any{"status": "NOT_EXPOSED", "tool": "list-data-sources"}
	} else {
		response, err := informationRead(ctx, session, "list-data-sources", map[string]any{})
		checks["list-data-sources"] = informationStatus(response, err)
	}
	checks["read-connector-capabilities"] = informationConnectors(ctx, session, visible)
	return report, nil
}

type informationResponse struct {
	RequestID string          `json:"requestId"`
	Success   *bool           `json:"success"`
	Result    json.RawMessage `json:"result"`
	Error     json.RawMessage `json:"error"`
}

func informationRead(ctx context.Context, session *mcp.ClientSession, tool string, payload map[string]any) (informationResponse, error) {
	result, err := session.CallTool(ctx, &mcp.CallToolParams{Name: tool, Arguments: map[string]any{"payload": payload}})
	if err != nil {
		return informationResponse{}, err
	}
	if result.IsError || len(result.Content) != 1 {
		return informationResponse{}, errors.New("service did not return its formal response")
	}
	text, ok := result.Content[0].(*mcp.TextContent)
	if !ok {
		return informationResponse{}, errors.New("formal service response must be text JSON")
	}
	var response informationResponse
	if err := json.Unmarshal([]byte(text.Text), &response); err != nil || response.Success == nil {
		return informationResponse{}, errors.New("formal service response lacks execution status")
	}
	return response, nil
}

func informationStatus(response informationResponse, err error) map[string]any {
	if err != nil {
		return map[string]any{"status": "FAILED", "message": err.Error()}
	}
	if response.Success == nil || !*response.Success {
		return map[string]any{"status": "FAILED", "requestId": response.RequestID, "error": response.Error}
	}
	return map[string]any{"status": "AVAILABLE", "result": response.Result}
}

func informationConnectors(ctx context.Context, session *mcp.ClientSession, visible map[string]bool) map[string]any {
	for _, tool := range []string{"describe-script-api", "execute-script"} {
		if !visible[tool] {
			return map[string]any{"status": "NOT_EXPOSED", "tool": tool}
		}
	}
	api, err := informationRead(ctx, session, "describe-script-api", map[string]any{})
	if err != nil || !*api.Success {
		return informationStatus(api, err)
	}
	var discovery struct {
		DSLAPIVersion string `json:"dslApiVersion"`
		Operations    []struct {
			OperationID string `json:"operationId"`
		} `json:"operations"`
	}
	if err := json.Unmarshal(api.Result, &discovery); err != nil || discovery.DSLAPIVersion == "" {
		return informationStatus(informationResponse{}, errors.New("script API discovery omitted its exact DSL version"))
	}
	discovered := false
	for _, operation := range discovery.Operations {
		if operation.OperationID == "read-connector-capabilities" {
			discovered = true
			break
		}
	}
	if !discovered {
		return map[string]any{"status": "NOT_DISCOVERED", "operation": "read-connector-capabilities"}
	}
	response, err := informationRead(ctx, session, "execute-script", map[string]any{
		"source":        "olap.output(olap.sources.connectorCapabilities(ConnectorCapabilitiesRequest(aggregate = null)))",
		"sourceName":    "info-connectors.olap.kts",
		"dslApiVersion": discovery.DSLAPIVersion,
		"parameters":    map[string]any{},
	})
	if err != nil || !*response.Success {
		status := informationStatus(response, err)
		if response.Result != nil {
			status["script"] = response.Result
		}
		return status
	}
	var script struct {
		Success bool            `json:"success"`
		Value   json.RawMessage `json:"value"`
	}
	if err := json.Unmarshal(response.Result, &script); err != nil || !script.Success {
		return informationStatus(informationResponse{}, errors.New("connector inspection script did not complete"))
	}
	var output anypb.Any
	if err := protojson.Unmarshal(script.Value, &output); err != nil {
		return informationStatus(informationResponse{}, fmt.Errorf("decode connector inspection output: %w", err))
	}
	var value query.ScriptValue
	if err := output.UnmarshalTo(&value); err != nil {
		return informationStatus(informationResponse{}, fmt.Errorf("decode typed script output: %w", err))
	}
	returned := value.GetRegisteredReply()
	if returned == nil || returned.OperationId != "read-connector-capabilities" || returned.Content == nil {
		return informationStatus(informationResponse{}, errors.New("script did not return the exact connector capability receipt"))
	}
	var capabilities governance.ConnectorCapabilities
	if err := returned.Content.UnmarshalTo(&capabilities); err != nil {
		return informationStatus(informationResponse{}, fmt.Errorf("decode connector capabilities: %w", err))
	}
	encoded, err := (protojson.MarshalOptions{EmitDefaultValues: true}).Marshal(&capabilities)
	if err != nil {
		return informationStatus(informationResponse{}, err)
	}
	return map[string]any{"status": "AVAILABLE", "via": "execute-script", "result": json.RawMessage(encoded)}
}
