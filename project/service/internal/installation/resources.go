// Package installation reads resources already delivered by npm; it does not install or download files.
package installation

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

type Resources struct {
	Version             string            `json:"version"`
	ConnectorAPIVersion string            `json:"connectorApiVersion"`
	SDK                 string            `json:"sdk"`
	SDKRepository       string            `json:"sdkRepository"`
	SDKArtifacts        map[string]string `json:"sdkArtifacts"`
	Connectors          []string          `json:"connectors"`
	root                string
}

func Read(version string) (Resources, error) {
	var resources Resources
	root := os.Getenv("AGENTIC_OLAP_PACKAGE_ROOT")
	if !filepath.IsAbs(root) {
		return resources, errors.New("npm 产品位置缺失；请通过 agentic-olap 命令使用已安装产品")
	}
	data, err := os.ReadFile(filepath.Join(root, "resources.json"))
	if err != nil {
		return resources, fmt.Errorf("已安装产品资源不完整；请重新执行 npm install：%w", err)
	}
	if err = json.Unmarshal(data, &resources); err != nil {
		return resources, err
	}
	if resources.Version != version || resources.ConnectorAPIVersion == "" || resources.SDK == "" || resources.SDKRepository != "https://jitpack.io" || len(resources.SDKArtifacts) == 0 || len(resources.Connectors) == 0 {
		return resources, errors.New("npm 资源与运行程序版本不一致；请重新安装同版本完整产品")
	}
	resources.root = root
	return resources, nil
}

func (r Resources) path(relative string) (string, error) {
	if filepath.IsAbs(relative) || relative == "." || filepath.ToSlash(filepath.Clean(relative)) != relative || strings.HasPrefix(relative, "../") {
		return "", errors.New("随包资源必须位于 npm 产品目录内")
	}
	path := filepath.Join(r.root, filepath.FromSlash(relative))
	if _, err := os.Stat(path); err != nil {
		return "", fmt.Errorf("npm 产品缺少资源 %s；请重新安装完整产品：%w", relative, err)
	}
	return path, nil
}

func (r Resources) SDKInformation() (map[string]any, error) {
	root, err := r.path(r.SDK)
	if err != nil {
		return nil, err
	}
	documents := map[string]string{}
	for _, name := range []string{"API.md", "SOURCE-ACCESS.md", "LOADER.md"} {
		path, err := r.path(r.SDK + "/" + name)
		if err != nil {
			return nil, err
		}
		documents[name] = path
	}
	return map[string]any{"version": r.Version, "connectorApiVersion": r.ConnectorAPIVersion, "root": root,
		"mavenRepository": r.SDKRepository, "artifacts": r.SDKArtifacts, "documents": documents}, nil
}

func (r Resources) ConnectorInformation() (map[string]any, error) {
	connectors := make([]any, 0, len(r.Connectors))
	for _, relative := range r.Connectors {
		path, err := r.path(relative)
		if err != nil {
			return nil, err
		}
		data, err := os.ReadFile(path)
		if err != nil {
			return nil, err
		}
		var manifest struct {
			ConnectorRef json.RawMessage `json:"connectorRef"`
			APIVersion   string          `json:"apiVersion"`
		}
		if err = json.Unmarshal(data, &manifest); err != nil {
			return nil, err
		}
		if manifest.APIVersion != r.ConnectorAPIVersion || len(manifest.ConnectorRef) == 0 {
			return nil, fmt.Errorf("随包插件 API 或身份不完整：%s", path)
		}
		connectors = append(connectors, map[string]any{"manifestPath": path, "connectorRef": manifest.ConnectorRef, "apiVersion": manifest.APIVersion})
	}
	return map[string]any{"version": r.Version, "connectorApiVersion": r.ConnectorAPIVersion, "connectors": connectors}, nil
}
