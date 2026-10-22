package httpclient

import (
	"crypto/tls"
	"crypto/x509"
	"errors"
	"fmt"
	"net/http"
	"os"
)

// Transport 拥有进程明确配置的 TLS 信任；JWKS 与 CLI 保留各自重定向政策。
// 显式 CA 文件在各平台含义一致，不允许配置错误退回其他信任来源。
func Transport() (*http.Transport, error) {
	transport := http.DefaultTransport.(*http.Transport).Clone()
	if path := os.Getenv("SSL_CERT_FILE"); path != "" {
		content, err := os.ReadFile(path)
		if err != nil {
			return nil, fmt.Errorf("read configured TLS CA file: %w", err)
		}
		roots, err := x509.SystemCertPool()
		if err != nil {
			return nil, fmt.Errorf("read system TLS roots: %w", err)
		}
		if !roots.AppendCertsFromPEM(content) {
			return nil, errors.New("configured TLS CA file contains no valid certificates")
		}
		transport.TLSClientConfig = &tls.Config{RootCAs: roots, MinVersion: tls.VersionTLS12}
	}
	return transport, nil
}
