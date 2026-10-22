package products

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

type RuntimeDistribution struct {
	MainClass string     `json:"mainClass"`
	Classpath []Identity `json:"classpath"`
}
type ServiceDistribution struct {
	Path   string `json:"path"`
	SHA256 string `json:"sha256"`
	OS     string `json:"os"`
	Arch   string `json:"arch"`
}
type Distribution struct {
	FormatVersion string              `json:"formatVersion"`
	Version       string              `json:"version"`
	Runtime       RuntimeDistribution `json:"runtime"`
	Service       ServiceDistribution `json:"service"`
}

func (w Workspace) ServerDistribution(ctx context.Context, destination string) error {
	jvm, err := w.ReadJVM()
	if err != nil {
		return err
	}
	service, err := w.ReadService()
	if err != nil {
		return err
	}
	if err = newDirectory(destination); err != nil {
		return err
	}
	if err = CopyFile(service.Binary, filepath.Join(destination, "runtime", "agentic-olap-runtime")); err != nil {
		return err
	}
	classpath := make([]Identity, 0, len(jvm.RuntimeClasspath))
	names := map[string]bool{}
	for _, identity := range jvm.RuntimeClasspath {
		source, err := identity.Verify()
		if err != nil {
			return err
		}
		name := filepath.Base(source)
		if names[name] {
			return fmt.Errorf("JVM classpath 文件名冲突：%s", name)
		}
		names[name] = true
		relative := filepath.Join("java", "lib", name)
		if err = CopyFile(source, filepath.Join(destination, relative)); err != nil {
			return err
		}
		classpath = append(classpath, Identity{Path: filepath.ToSlash(relative), SHA256: identity.SHA256})
	}
	if err = w.goLicenses(ctx, filepath.Join(destination, "licenses", "go")); err != nil {
		return err
	}
	for _, license := range jvm.Licenses {
		source, err := license.Verify()
		if err != nil {
			return err
		}
		relative, err := filepath.Rel(w.Path("project", "build", "runtime-licenses"), source)
		if err != nil {
			return err
		}
		target, err := cleanRelative(filepath.Join(destination, "licenses", "jvm"), relative)
		if err != nil {
			return err
		}
		if err = CopyFile(source, target); err != nil {
			return err
		}
	}
	for _, pair := range [][2]string{{w.Path("LICENSE"), "LICENSE"}, {w.Path("project", "service", "Dockerfile"), "Dockerfile"}, {w.Path("skills", "agentic-olap", "references", "server.example.json"), "server.example.json"}} {
		if err = CopyFile(pair[0], filepath.Join(destination, pair[1])); err != nil {
			return err
		}
	}
	return WriteJSON(filepath.Join(destination, "distribution.json"), Distribution{FormatVersion: "agentic-olap-distribution/v2", Version: service.Version,
		Runtime: RuntimeDistribution{MainClass: jvm.MainClass, Classpath: classpath}, Service: ServiceDistribution{Path: "runtime/agentic-olap-runtime", SHA256: service.SHA256, OS: service.OS, Arch: service.Arch}})
}
func ReadDistribution(root string) (Distribution, error) {
	var manifest Distribution
	if !filepath.IsAbs(root) {
		return manifest, errors.New("发行根必须为绝对路径")
	}
	if err := ReadJSON(filepath.Join(root, "distribution.json"), &manifest); err != nil {
		return manifest, err
	}
	if manifest.FormatVersion != "agentic-olap-distribution/v2" || manifest.Version == "" || manifest.Runtime.MainClass == "" || len(manifest.Runtime.Classpath) == 0 {
		return manifest, errors.New("服务发行清单不完整")
	}
	files := append([]Identity{{Path: manifest.Service.Path, SHA256: manifest.Service.SHA256}}, manifest.Runtime.Classpath...)
	for _, identity := range files {
		path, err := cleanRelative(root, identity.Path)
		if err != nil {
			return manifest, err
		}
		resolved, err := filepath.EvalSymlinks(path)
		if err != nil {
			return manifest, err
		}
		relative, err := filepath.Rel(root, resolved)
		if err != nil || relative == ".." || strings.HasPrefix(relative, ".."+string(filepath.Separator)) {
			return manifest, errors.New("发行文件越过发行根")
		}
		if _, err = (Identity{Path: resolved, SHA256: identity.SHA256}).Verify(); err != nil {
			return manifest, err
		}
	}
	return manifest, nil
}

type Plugin struct {
	ID      string
	Version string
	Jars    []string
}
type ConnectorReference struct {
	ID      string `json:"id"`
	Version string `json:"version"`
	SHA256  string `json:"sha256"`
}
type ConnectorJar struct {
	File   string `json:"file"`
	SHA256 string `json:"sha256"`
}
type ConnectorManifest struct {
	ConnectorRef ConnectorReference `json:"connectorRef"`
	APIVersion   string             `json:"apiVersion"`
	Jars         []ConnectorJar     `json:"jars"`
}

// DeployConnectors 使用真实构件组形成内容地址，不按方言或当前目录猜插件。
func DeployConnectors(directory, api string, plugins []Plugin) (string, error) {
	if !strings.HasPrefix(api, "agentic-olap-connector/") {
		return "", errors.New("连接器部署缺少精确编译 API 版本")
	}
	if err := newDirectory(directory); err != nil {
		return "", err
	}
	manifests := make([]string, 0, len(plugins))
	for index, plugin := range plugins {
		if plugin.ID == "" || strings.ContainsAny(plugin.ID, "/\\.") || plugin.Version == "" || len(plugin.Jars) == 0 {
			return "", errors.New("插件必须提供明确身份、版本与实际 JAR 组")
		}
		group := filepath.Join(directory, fmt.Sprintf("%04d-%s", index+1, plugin.ID))
		if err := os.Mkdir(group, 0755); err != nil {
			return "", err
		}
		hash := sha256.New()
		jars := make([]ConnectorJar, 0, len(plugin.Jars))
		for _, source := range plugin.Jars {
			if filepath.Ext(source) != ".jar" {
				return "", errors.New("插件输入必须为 JAR")
			}
			target := filepath.Join(group, filepath.Base(source))
			if err := CopyFile(source, target); err != nil {
				return "", err
			}
			digest, err := Digest(target)
			if err != nil {
				return "", err
			}
			bytes, err := hex.DecodeString(digest)
			if err != nil {
				return "", err
			}
			hash.Write([]byte(filepath.Base(target)))
			hash.Write([]byte{0})
			hash.Write(bytes)
			jars = append(jars, ConnectorJar{File: filepath.Base(target), SHA256: digest})
		}
		manifest := filepath.Join(group, "connector.json")
		if err := WriteJSON(manifest, ConnectorManifest{ConnectorRef: ConnectorReference{ID: plugin.ID, Version: plugin.Version, SHA256: hex.EncodeToString(hash.Sum(nil))}, APIVersion: api, Jars: jars}); err != nil {
			return "", err
		}
		manifests = append(manifests, manifest)
	}
	configuration := filepath.Join(directory, "connectors.json")
	return configuration, WriteJSON(configuration, manifests)
}
func (w Workspace) ReferenceConnectors(directory string) (string, error) {
	build, err := w.ReadJVM()
	if err != nil {
		return "", err
	}
	plugins := []Plugin{}
	for _, id := range []string{"h2", "duckdb"} {
		module, err := build.Module("connector-" + id)
		if err != nil {
			return "", err
		}
		plugin, err := module.Jar.Verify()
		if err != nil {
			return "", err
		}
		jars := []string{plugin}
		drivers := build.Drivers[module.ArtifactID]
		if len(drivers) == 0 {
			return "", fmt.Errorf("%s 缺少实际驱动", id)
		}
		for _, driver := range drivers {
			path, err := driver.Verify()
			if err != nil {
				return "", err
			}
			jars = append(jars, path)
		}
		plugins = append(plugins, Plugin{ID: id, Version: "1.0.0", Jars: jars})
	}
	return DeployConnectors(directory, build.ConnectorAPIVersion, plugins)
}
func (w Workspace) Build(ctx context.Context) error {
	if err := w.BuildJVM(ctx); err != nil {
		return err
	}
	return w.BuildService(ctx)
}
