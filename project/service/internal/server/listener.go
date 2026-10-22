package server

import (
	"errors"
	"io"
	"log/slog"
	"net"
	"sync"
	"time"
)

// Listen 的配额属于已接入连接，覆盖 HTTP 头尚未完成的实际资源。
// http.Server 关闭连接时归还；请求处理不另建第二份配额。
func Listen(address string, capacity int) (net.Listener, error) {
	if capacity < 1 {
		return nil, errors.New("HTTP connection capacity must be positive")
	}
	listener, err := net.Listen("tcp", address)
	if err != nil {
		return nil, err
	}
	return &admittedListener{Listener: listener, slots: make(chan struct{}, capacity)}, nil
}

type admittedListener struct {
	net.Listener
	slots chan struct{}
}

func (l *admittedListener) Accept() (net.Conn, error) {
	for {
		connection, err := l.Listener.Accept()
		if err != nil {
			return nil, err
		}
		select {
		case l.slots <- struct{}{}:
			return &admittedConnection{Conn: connection, release: func() { <-l.slots }}, nil
		default:
			// 满额连接不进入 HTTP goroutine 或业务派发，不等待活跃请求结束。
			if err = connection.SetWriteDeadline(time.Now().Add(time.Second)); err == nil {
				_, err = io.WriteString(connection, "HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
			}
			if err != nil {
				slog.Debug("HTTP admission rejection delivery failed", "error", err)
			}
			if err = connection.Close(); err != nil {
				slog.Warn("HTTP rejected connection close failed", "error", err)
			}
		}
	}
}

type admittedConnection struct {
	net.Conn
	once       sync.Once
	release    func()
	closeError error
}

func (c *admittedConnection) Close() error {
	c.once.Do(func() { c.closeError = c.Conn.Close(); c.release() })
	return c.closeError
}
