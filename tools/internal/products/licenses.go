package products

import (
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

func (w Workspace) goLicenses(ctx context.Context, destination string) error {
	value, err := output(ctx, w.Path("project", "service"), "go", "list", "-mod=readonly", "-deps", "-f", "{{if .Module}}{{if not .Module.Main}}{{.Module.Path}} {{.Module.Dir}}{{end}}{{end}}", "./cmd/agentic-olap-runtime")
	if err != nil {
		return err
	}
	if err = CopyTree(w.Path("project", "distribution-resources", "go"), filepath.Join(destination, "go-toolchain"), nil); err != nil {
		return err
	}
	modules := map[string]string{}
	for _, line := range strings.Split(string(value), "\n") {
		if strings.TrimSpace(line) == "" {
			continue
		}
		parts := strings.SplitN(line, " ", 2)
		if len(parts) != 2 || parts[1] == "" {
			return errors.New("Go 实际依赖缺少模块目录")
		}
		modules[parts[0]] = parts[1]
	}
	for _, name := range sortedKeys(modules) {
		entries, err := os.ReadDir(modules[name])
		if err != nil {
			return err
		}
		count := 0
		for _, entry := range entries {
			lower := strings.ToLower(entry.Name())
			if entry.IsDir() || !(strings.HasPrefix(lower, "license") || strings.HasPrefix(lower, "notice") || strings.HasPrefix(lower, "copying") || strings.HasPrefix(lower, "copyright")) {
				continue
			}
			if err = CopyFile(filepath.Join(modules[name], entry.Name()), filepath.Join(destination, strings.ReplaceAll(name, "/", "+"), entry.Name())); err != nil {
				return err
			}
			count++
		}
		if count == 0 {
			return fmt.Errorf("Go 依赖没有许可文件：%s", name)
		}
	}
	return nil
}
