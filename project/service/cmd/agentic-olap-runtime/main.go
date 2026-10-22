package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"syscall"
	"time"

	"github.com/hanger-source/agentic-olap/service/internal/application"
	"github.com/hanger-source/agentic-olap/service/internal/auth"
	"github.com/hanger-source/agentic-olap/service/internal/client"
	"github.com/hanger-source/agentic-olap/service/internal/config"
	"github.com/hanger-source/agentic-olap/service/internal/installation"
	"github.com/hanger-source/agentic-olap/service/internal/local"
	"github.com/hanger-source/agentic-olap/service/internal/results"
	"github.com/hanger-source/agentic-olap/service/internal/runtime"
	"github.com/hanger-source/agentic-olap/service/internal/server"
	"github.com/hanger-source/agentic-olap/service/internal/wire"
)

var version string // Injected from the distribution's single release version.
func main() {
	code, err := run(os.Args[1:])
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		if code == 0 {
			code = 1
		}
	}
	os.Exit(code)
}
func run(args []string) (int, error) {
	if version == "" {
		return 1, errors.New("runtime artifact has no build version")
	}
	if len(args) == 1 && args[0] == "--version" {
		fmt.Println(version)
		return 0, nil
	}
	if len(args) == 1 && (args[0] == "--help" || args[0] == "-h") {
		fmt.Println("用法：agentic-olap <start|info|token|tools|call|sdk|connectors> [选项]\n使用 <命令> --help 读取具体用法。")
		return 0, nil
	}
	if len(args) == 0 {
		return 1, errors.New("expected start, info, token, tools or call")
	}
	command, args := args[0], args[1:]
	if command == "install-jvm-dependencies" {
		if len(args) != 0 {
			return 1, errors.New("npm JVM 安装不接受额外参数")
		}
		ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
		defer stop()
		return 0, installation.InstallDependencies(ctx, version)
	}
	if command == "sdk" || command == "connectors" {
		return resources(command, args)
	}
	switch command {
	case "start", "info", "token", "tools", "call":
	default:
		return 1, errors.New("unknown runtime command")
	}
	tool := ""
	if command == "call" && len(args) > 0 && !strings.HasPrefix(args[0], "-") {
		tool, args = args[0], args[1:]
	}
	flags := flag.NewFlagSet(command, flag.ContinueOnError)
	home := flags.String("home", "", "local persistent home")
	configuration := flags.String("config", "", "explicit deployment JSON")
	port := flags.Int("port", 0, "local listen port")
	address := flags.String("url", "", "explicit remote MCP URL")
	tokenEnv := flags.String("token-env", "", "name of the bearer environment variable")
	timeout := flags.Float64("timeout-seconds", 120, "client deadline seconds")
	payload := flags.String("payload", "", "complete tool payload JSON")
	if err := flags.Parse(args); err != nil {
		if errors.Is(err, flag.ErrHelp) {
			return 0, nil
		}
		return 1, err
	}
	if command == "call" && tool == "" && flags.NArg() == 1 {
		tool = flags.Arg(0)
	} else if flags.NArg() != 0 {
		return 1, errors.New("unexpected command arguments")
	}
	if *timeout <= 0 || *timeout > 3600 || *port < 0 || *port > 65535 {
		return 1, errors.New("invalid port or deadline")
	}
	root, err := local.Home(*home)
	if err != nil {
		return 1, err
	}
	if command == "start" {
		if *configuration != "" {
			if *home != "" || *port != 0 {
				return 1, errors.New("start --config cannot be combined with --home or --port")
			}
			return serve(*configuration)
		}
		distribution := os.Getenv("AGENTIC_OLAP_HOME")
		if distribution == "" {
			return 1, errors.New("AGENTIC_OLAP_HOME must identify the exact distribution")
		}
		path, c, err := local.Initialize(root, filepath.Join(distribution, "server.example.json"), *port)
		if err != nil {
			return 1, err
		}
		if err = json.NewEncoder(os.Stdout).Encode(local.Information(path, c)); err != nil {
			return 1, err
		}
		return serve(path)
	}
	if *configuration != "" || *port != 0 {
		return 1, errors.New("config and port belong only to start")
	}
	if command == "info" || command == "token" {
		path, c, err := local.Initialized(root)
		if err != nil {
			return 1, err
		}
		if command == "info" {
			information := local.Information(path, c)
			information["installation"] = map[string]any{"version": version, "packageRoot": os.Getenv("AGENTIC_OLAP_PACKAGE_ROOT"),
				"runtimeRoot": os.Getenv("AGENTIC_OLAP_HOME"), "javaHome": os.Getenv("JAVA_HOME")}
			token, err := auth.ReadLocalToken(c.Auth.TokenFile)
			if err != nil {
				return 1, err
			}
			ctx, cancel := context.WithTimeout(context.Background(), min(5*time.Second, time.Duration(*timeout*float64(time.Second))))
			defer cancel()
			service, observed := client.Information(ctx, c.URL(), token, version)
			information["service"] = service
			if err = json.NewEncoder(os.Stdout).Encode(information); err != nil {
				return 1, err
			}
			if observed != nil {
				return 1, fmt.Errorf("服务状态读取失败；已输出安装与配置位置：%w", observed)
			}
			if service["versionMatchesInstalled"] != true {
				return 1, errors.New("npm 已安装的版本与运行中服务不同；停止原进程后，用原配置重新运行 agentic-olap start")
			}
			return 0, nil
		}
		token, err := auth.ReadLocalToken(c.Auth.TokenFile)
		if err != nil {
			return 1, err
		}
		fmt.Println(token)
		return 0, nil
	}
	if (*address == "") != (*tokenEnv == "") || (*address != "" && *home != "") {
		return 1, errors.New("url and token-env must be explicit together, without home")
	}
	token := ""
	if *address == "" {
		_, c, err := local.Initialized(root)
		if err != nil {
			return 1, err
		}
		*address = c.URL()
		token, err = auth.ReadLocalToken(c.Auth.TokenFile)
		if err != nil {
			return 1, err
		}
	} else {
		token = os.Getenv(*tokenEnv)
		if token == "" {
			return 1, errors.New("explicit bearer environment variable is unset")
		}
	}
	raw := json.RawMessage(`{}`)
	if command == "call" {
		if tool == "" || *payload == "" {
			return 1, errors.New("call requires a tool and payload")
		}
		raw = []byte(*payload)
		if _, err = wire.Object(raw, 512, 10000000); err != nil {
			return 1, err
		}
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(*timeout*float64(time.Second)))
	defer cancel()
	return client.Invoke(ctx, *address, token, tool, raw, version)
}

func resources(command string, args []string) (int, error) {
	flags := flag.NewFlagSet(command, flag.ContinueOnError)
	onlyRepository := flags.Bool("repository", false, "只输出 SDK 的 Maven 仓库地址")
	if err := flags.Parse(args); err != nil {
		if errors.Is(err, flag.ErrHelp) {
			return 0, nil
		}
		return 1, err
	}
	if flags.NArg() != 0 || (command == "connectors" && *onlyRepository) {
		return 1, errors.New("用法：agentic-olap sdk [--repository] 或 agentic-olap connectors")
	}
	installed, err := installation.Read(version)
	if err != nil {
		return 1, err
	}
	var information map[string]any
	if command == "sdk" {
		information, err = installed.SDKInformation()
	} else {
		information, err = installed.ConnectorInformation()
	}
	if err != nil {
		return 1, err
	}
	if *onlyRepository {
		fmt.Println(information["mavenRepository"])
		return 0, nil
	}
	return 0, json.NewEncoder(os.Stdout).Encode(information)
}
func serve(path string) (code int, failure error) {
	c, err := config.Read(path)
	if err != nil {
		return 1, err
	}
	deployment, err := c.JSON()
	if err != nil {
		return 1, err
	}
	distribution := os.Getenv("AGENTIC_OLAP_HOME")
	javaHome := os.Getenv("JAVA_HOME")
	if distribution == "" || javaHome == "" {
		return 1, errors.New("exact AGENTIC_OLAP_HOME and JAVA_HOME are required")
	}
	manager, err := runtime.New(runtime.Config{Java: filepath.Join(javaHome, "bin", "java"), Distribution: distribution, WorkDir: filepath.Join(c.DataRoot, "runtime"), Deployment: deployment, Stderr: os.Stderr, LookupCredential: os.LookupEnv})
	if err != nil {
		return 1, err
	}
	defer func() {
		ctx, cancel := context.WithTimeout(context.Background(), time.Duration(c.Limits.StartupSeconds*float64(time.Second)))
		defer cancel()
		failure = errors.Join(failure, manager.Close(ctx))
		if failure != nil {
			code = 1
		}
	}()
	startup, cancel := context.WithTimeout(context.Background(), time.Duration(c.Limits.StartupSeconds*float64(time.Second)))
	err = manager.Start(startup)
	cancel()
	if err != nil {
		return 1, err
	}
	app := &application.Application{Runtime: manager, Config: c, Results: results.Store{Directory: filepath.Join(c.DataRoot, "results"), MaxFileBytes: c.Limits.InputFileBytes, Depth: 512, Nodes: c.Limits.InputFileBytes}}
	handler, err := server.New(c, app, version)
	if err != nil {
		return 1, err
	}
	defer handler.Close()
	ctx, cancelRequests := context.WithCancel(context.Background())
	defer cancelRequests()
	signals := make(chan os.Signal, 1)
	signal.Notify(signals, syscall.SIGINT, syscall.SIGTERM)
	defer signal.Stop(signals)
	httpServer := &http.Server{Addr: net.JoinHostPort(c.Listen.Host, fmt.Sprint(c.Listen.Port)), Handler: handler, ReadHeaderTimeout: 5 * time.Second, ReadTimeout: time.Duration(c.Limits.RequestSeconds * float64(time.Second)), IdleTimeout: 60 * time.Second, BaseContext: func(net.Listener) context.Context { return ctx }}
	defer func() { failure = errors.Join(failure, httpServer.Close()) }()
	listener, err := server.Listen(httpServer.Addr, c.Limits.MaxInFlight)
	if err != nil {
		return 1, err
	}
	defer listener.Close()
	done := make(chan error, 1)
	go func() { slog.Info("OLAP HTTP service listening", "url", c.URL()); done <- httpServer.Serve(listener) }()
	select {
	case err := <-done:
		if !errors.Is(err, http.ErrServerClosed) {
			return 1, err
		}
		return 0, nil
	case received := <-signals:
		cancelRequests()
		exitCode := 143
		if received == syscall.SIGINT {
			exitCode = 130
		}
		shutdown, cancel := context.WithTimeout(context.Background(), time.Duration(c.Limits.StartupSeconds*float64(time.Second)))
		defer cancel()
		if err = httpServer.Shutdown(shutdown); err != nil {
			return 1, err
		}
		err = <-done
		if !errors.Is(err, http.ErrServerClosed) {
			return 1, err
		}
		return exitCode, nil
	}
}
