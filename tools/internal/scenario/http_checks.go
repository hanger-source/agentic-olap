package scenario

import (
	"bufio"
	"bytes"
	"context"
	"fmt"
	"io"
	"net"
	"net/http"
	"path/filepath"
	"time"
)

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
	require(response.StatusCode >= 400 && response.StatusCode < 500, "未完成body应被拒绝，实际HTTP %d：%s", response.StatusCode, diagnostic)
	require(elapsed >= requestLimit-time.Second, "未完成body在期限前被其它输入规则拒绝：%s", elapsed)
	_, err = reader.ReadByte()
	require(err == io.EOF, "body读取期限结束后连接未关闭：%v", err)
	must(connection.Close())
	// 恢复请求必须由另一个真实连接取得同一个容量为1的监听器名额。
	recovered := admittedHTTPConnection(ctx, s)
	must(recovered.Close())
	write(filepath.Join(s.root, "body-deadline.json"), object{"requestSeconds": requestLimit.Seconds(), "elapsedMillis": elapsed.Milliseconds(), "status": response.StatusCode, "connectionClosed": true, "recoveredStatus": http.StatusOK})
}
