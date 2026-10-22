package scenario

import (
	"context"
	"crypto"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"math/big"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"sync"
	"syscall"
	"time"

	"github.com/hanger-source/agentic-olap/tooling/internal/products"
)

// 每个真实子进程由持有 command、日志与 Wait 结果的同一个对象关闭。
type process struct {
	command *exec.Cmd
	done    chan error
	files   []*os.File
}

func startProcess(ctx context.Context, directory, output, name string, environment []string, command ...string) *process {
	require(len(command) > 0, "进程命令不能为空")
	p := &process{command: exec.CommandContext(ctx, command[0], command[1:]...), done: make(chan error, 1)}
	p.command.Dir = directory
	p.command.Env = append(os.Environ(), environment...)
	for _, extension := range []string{"stdout", "stderr"} {
		file, err := os.OpenFile(filepath.Join(output, name+"."+extension), os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0644)
		must(err)
		p.files = append(p.files, file)
	}
	p.command.Stdout = p.files[0]
	p.command.Stderr = p.files[1]
	if err := p.command.Start(); err != nil {
		for _, file := range p.files {
			file.Close()
		}
		must(err)
	}
	go func() { p.done <- p.command.Wait() }()
	return p
}
func (p *process) stop() {
	if p == nil {
		return
	}
	var outcome error
	terminationSent := false
	select {
	case outcome = <-p.done:
	default:
		err := p.command.Process.Signal(syscall.SIGTERM)
		if err != nil && !errors.Is(err, os.ErrProcessDone) {
			must(err)
		}
		terminationSent = err == nil
		select {
		case outcome = <-p.done:
		case <-time.After(30 * time.Second):
			panic(failure{fmt.Errorf("子进程未能停止：%s", p.command.Path)})
		}
	}
	// 保留真正的 Wait 结局，异常展开再次关闭时不重复等待已退出进程。
	p.done <- outcome
	if outcome != nil {
		var exit *exec.ExitError
		terminated := false
		if terminationSent && errors.As(outcome, &exit) {
			status, ok := exit.Sys().(syscall.WaitStatus)
			terminated = exit.ExitCode() == 143 || ok && status.Signaled() && status.Signal() == syscall.SIGTERM
		}
		if terminated {
			outcome = nil
		} else {
			outcome = fmt.Errorf("子进程失败：%w", outcome)
		}
	}
	remaining := p.files[:0]
	for _, file := range p.files {
		if err := file.Close(); err != nil {
			outcome = errors.Join(outcome, err)
			remaining = append(remaining, file)
		}
	}
	p.files = remaining
	must(outcome)
}
func (p *process) ready(ctx context.Context, predicate func() bool) {
	timer := time.NewTimer(120 * time.Second)
	defer timer.Stop()
	tick := time.NewTicker(50 * time.Millisecond)
	defer tick.Stop()
	for {
		if predicate() {
			return
		}
		select {
		case err := <-p.done:
			p.done <- err
			panic(failure{fmt.Errorf("子进程在就绪前退出：%v", err)})
		case <-ctx.Done():
			must(ctx.Err())
		case <-timer.C:
			panic(failure{fmt.Errorf("子进程未就绪：%s", p.command.Path)})
		case <-tick.C:
		}
	}
}

type issuer struct {
	key         *rsa.PrivateKey
	server      *http.Server
	listener    net.Listener
	address, ca string
	lock        sync.Mutex
	reads       []string
}

func newIssuer(output string) *issuer {
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	must(err)
	serial, err := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 128))
	must(err)
	template := &x509.Certificate{SerialNumber: serial, Subject: pkix.Name{CommonName: "owned-olap-test-issuer"}, NotBefore: time.Now().Add(-time.Minute), NotAfter: time.Now().Add(2 * time.Hour), BasicConstraintsValid: true, IsCA: true, KeyUsage: x509.KeyUsageDigitalSignature | x509.KeyUsageKeyEncipherment | x509.KeyUsageCertSign | x509.KeyUsageCRLSign, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth}, IPAddresses: []net.IP{net.ParseIP("127.0.0.1")}}
	der, err := x509.CreateCertificate(rand.Reader, template, template, &key.PublicKey, key)
	must(err)
	certPEM := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})
	keyDER, err := x509.MarshalPKCS8PrivateKey(key)
	must(err)
	keyPEM := pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: keyDER})
	ca := filepath.Join(output, "issuer-ca.pem")
	must(os.WriteFile(ca, certPEM, 0644))
	must(os.WriteFile(filepath.Join(output, "issuer-key.pem"), keyPEM, 0600))
	certificate, err := tls.X509KeyPair(certPEM, keyPEM)
	must(err)
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	must(err)
	identity := &issuer{key: key, listener: listener, ca: ca, address: "https://" + listener.Addr().String() + "/"}
	identity.server = &http.Server{ReadHeaderTimeout: 10 * time.Second, Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		identity.lock.Lock()
		identity.reads = append(identity.reads, r.URL.Path)
		identity.lock.Unlock()
		if r.URL.Path != "/jwks" {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		e := big.NewInt(int64(key.PublicKey.E)).Bytes()
		json.NewEncoder(w).Encode(object{"keys": array{object{"kty": "RSA", "use": "sig", "alg": "RS256", "kid": "owned-key", "n": base64.RawURLEncoding.EncodeToString(key.PublicKey.N.Bytes()), "e": base64.RawURLEncoding.EncodeToString(e)}}})
	})}
	go func() {
		err := identity.server.Serve(tls.NewListener(listener, &tls.Config{Certificates: []tls.Certificate{certificate}, MinVersion: tls.VersionTLS12}))
		if err != nil && !errors.Is(err, http.ErrServerClosed) {
			fmt.Fprintln(os.Stderr, "场景 JWKS 服务失败：", err)
		}
	}()
	return identity
}
func (i *issuer) close() {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	must(i.server.Shutdown(ctx))
}
func (i *issuer) token(audience, subject string) string {
	header := base64.RawURLEncoding.EncodeToString(encoded(object{"alg": "RS256", "typ": "JWT", "kid": "owned-key"}))
	claims := base64.RawURLEncoding.EncodeToString(encoded(object{"iss": i.address, "aud": audience, "sub": subject, "scope": "olap.manage", "iat": time.Now().Unix(), "exp": time.Now().Add(30 * time.Minute).Unix()}))
	input := header + "." + claims
	digest := sha256.Sum256([]byte(input))
	signature, err := rsa.SignPKCS1v15(rand.Reader, i.key, crypto.SHA256, digest[:])
	must(err)
	return input + "." + base64.RawURLEncoding.EncodeToString(signature)
}
func freePort() int {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	must(err)
	port := listener.Addr().(*net.TCPAddr).Port
	must(listener.Close())
	return port
}

type service struct {
	ctx                                        context.Context
	workspace                                  products.Workspace
	distribution, root, config, address, token string
	environment                                []string
	process                                    *process
}

func (s *service) start(phase string) {
	require(s.process == nil, "服务进程仍由上阶段持有")
	manifest, err := products.ReadDistribution(s.distribution)
	must(err)
	require(manifest.Service.Path == "runtime/agentic-olap-runtime", "发行缺少正式 Go 入口")
	s.process = startProcess(s.ctx, s.workspace.Root, s.root, phase, s.environment, filepath.Join(s.distribution, manifest.Service.Path), "start", "--config", s.config)
	s.process.ready(s.ctx, func() bool {
		connection, err := net.DialTimeout("tcp", s.address, 100*time.Millisecond)
		if err != nil {
			return false
		}
		connection.Close()
		return true
	})
}
func (s *service) stop() {
	if s.process == nil {
		return
	}
	s.process.stop()
	s.process = nil
	entries, err := filepath.Glob(filepath.Join(s.root, "data", ".olap-server-*"))
	must(err)
	require(len(entries) == 0, "正常停止后仍留所属临时部署目录")
}
func (s *service) remote(phase string) *remote {
	return connect(s.ctx, "http://"+s.address+"/mcp", s.token, filepath.Join(s.root, phase))
}

type Options struct {
	Root, Name, Output, Distribution, Connectors, Plugin, Java string
	BusinessInput, HTTPFixture                                 string
}

func Run(ctx context.Context, options Options) (err error) {
	defer func() {
		if recovered := recover(); recovered != nil {
			if problem, ok := recovered.(failure); ok {
				err = problem.err
			} else {
				panic(recovered)
			}
		}
	}()
	workspace, err := products.Open(options.Root)
	if err != nil {
		return err
	}
	require(options.Name != "" && options.Output != "" && options.Distribution != "", "场景需要明确 name/output/distribution")
	absolute, err := filepath.Abs(options.Output)
	must(err)
	options.Output = absolute
	options.Distribution, err = filepath.Abs(options.Distribution)
	must(err)
	if options.Connectors != "" {
		options.Connectors, err = filepath.Abs(options.Connectors)
		must(err)
	}
	if options.BusinessInput != "" {
		options.BusinessInput, err = filepath.Abs(options.BusinessInput)
		must(err)
	}
	if options.HTTPFixture != "" {
		options.HTTPFixture, err = filepath.Abs(options.HTTPFixture)
		must(err)
	}
	_, err = os.Lstat(absolute)
	require(errors.Is(err, os.ErrNotExist), "场景输出目录必须不存在：%s", absolute)
	must(os.MkdirAll(absolute, 0755))
	if options.Name == "kernel" {
		runKernel(ctx, workspace, options)
		return nil
	}
	runHTTP(ctx, workspace, options)
	return nil
}

func exampleArguments(ctx context.Context, workspace products.Workspace, name, path string, args []string) {
	write(path, args)
	must(workspace.Gradle(ctx, ":examples:"+name, "-PexampleArguments="+path))
}
func runKernel(ctx context.Context, workspace products.Workspace, options Options) {
	connectors := options.Connectors
	if connectors == "" {
		var err error
		connectors, err = workspace.ReferenceConnectors(filepath.Join(options.Output, "connectors"))
		must(err)
	}
	limits := obj(obj(read(filepath.Join(options.Distribution, "server.example.json")))["limits"])
	seconds := numberText(limits["requestSeconds"])
	duration, err := time.ParseDuration(seconds + "s")
	must(err)
	exampleArguments(ctx, workspace, "kernel", filepath.Join(options.Output, "arguments.json"), []string{"--connectors", connectors, filepath.Join(options.Output, "run"), fmt.Sprint(duration.Milliseconds()), numberText(limits["nativeMemoryBytes"])})
}

func numberText(value any) string {
	switch n := value.(type) {
	case string:
		return n
	case interface{ String() string }:
		return n.String()
	default:
		panic(failure{fmt.Errorf("值不是精确数字：%T", value)})
	}
}
