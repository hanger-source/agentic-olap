package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"

	"github.com/hanger-source/agentic-olap/tooling/internal/products"
	"github.com/hanger-source/agentic-olap/tooling/internal/scenario"
)

type selections []string

func (s *selections) String() string { return fmt.Sprint([]string(*s)) }
func (s *selections) Set(value string) error {
	if value == "" {
		return errors.New("试验选择不能为空")
	}
	*s = append(*s, value)
	return nil
}

func main() {
	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()
	if err := run(ctx, os.Args[1:]); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
func run(ctx context.Context, args []string) error {
	if len(args) == 1 && (args[0] == "--help" || args[0] == "-h" || args[0] == "help") {
		fmt.Println("用法：olap-dev bootstrap|build|protocol|jvm-build|service-build|server-distribution|binary|example|test|test-jvm|test-go")
		return nil
	}
	if len(args) == 0 {
		return errors.New("用法：olap-dev build|protocol|jvm-build|service-build|server-distribution|binary|example|test|test-jvm|test-go")
	}
	command := args[0]
	flags := flag.NewFlagSet(command, flag.ContinueOnError)
	root := flags.String("root", "", "明确源码根")
	artifacts := flags.String("artifacts", "", "本次新输出目录")
	distribution := flags.String("distribution", "", "明确同轮服务发行目录")
	name := flags.String("example", "", "orders|procurement|subscriptions|kernel")
	goSelection := flags.String("go-test", "", "Go -run 选择")
	var jvmSelections selections
	flags.Var(&jvmSelections, "jvm-test", "Gradle --tests 选择，可重复")
	if err := flags.Parse(args[1:]); err != nil {
		return err
	}
	if flags.NArg() != 0 {
		return errors.New("命令不接受额外位置参数")
	}
	var workspace products.Workspace
	var err error
	if *root == "" {
		workspace, err = products.Locate()
	} else {
		workspace, err = products.Open(*root)
	}
	if err != nil {
		return err
	}
	absolute := func(path string) string {
		if filepath.IsAbs(path) {
			return filepath.Clean(path)
		}
		return workspace.Path(path)
	}
	output := func() (string, error) {
		if *artifacts == "" {
			return "", errors.New("该命令要求 --artifacts 明确本次输出根")
		}
		return absolute(*artifacts), nil
	}
	selectedDistribution := func() (string, error) {
		if *distribution == "" {
			return "", errors.New("该命令要求 --distribution 明确同轮服务发行")
		}
		path := absolute(*distribution)
		_, err := products.ReadDistribution(path)
		return path, err
	}
	switch command {
	case "bootstrap":
		if _, err = products.Java("java"); err != nil {
			return err
		}
		for _, module := range []string{workspace.Path("project", "service"), workspace.Path("tools")} {
			if err = products.Run(ctx, module, nil, "go", "mod", "download"); err != nil {
				return err
			}
		}
		return nil
	case "protocol":
		return workspace.Gradle(ctx, "generateGoProtocol")
	case "jvm-build":
		return workspace.BuildJVM(ctx)
	case "service-build":
		return workspace.BuildService(ctx)
	case "build":
		return workspace.Build(ctx)
	case "server-distribution":
		destination, err := output()
		if err != nil {
			return err
		}
		return workspace.ServerDistribution(ctx, destination)
	case "binary":
		destination, err := output()
		if err != nil {
			return err
		}
		source, err := selectedDistribution()
		if err != nil {
			return err
		}
		return workspace.Binary(ctx, destination, source)
	case "example":
		destination, err := output()
		if err != nil {
			return err
		}
		source, err := selectedDistribution()
		if err != nil {
			return err
		}
		if *name == "" {
			return errors.New("example 要求 --example 明确业务用法")
		}
		return scenario.Run(ctx, scenario.Options{Root: workspace.Root, Name: *name, Output: destination, Distribution: source})
	case "test", "test-jvm", "test-go":
		if command != "test-go" {
			arguments := []string{":integration-tests:test"}
			for _, selection := range jvmSelections {
				arguments = append(arguments, "--tests", selection)
			}
			if err = workspace.Gradle(ctx, arguments...); err != nil {
				return err
			}
		}
		if command != "test-jvm" {
			source, err := selectedDistribution()
			if err != nil {
				return err
			}
			environment := []string{"AGENTIC_OLAP_DISTRIBUTION=" + source}
			arguments := []string{"go", "test"}
			if *goSelection != "" {
				arguments = append(arguments, "-run", *goSelection)
			}
			arguments = append(arguments, "./...")
			for _, module := range []string{workspace.Path("project", "service"), workspace.Path("tools")} {
				if err = products.Run(ctx, module, environment, arguments...); err != nil {
					return err
				}
			}
		}
		return nil
	default:
		return fmt.Errorf("未知研发命令：%s", command)
	}
}
