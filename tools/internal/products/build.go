package products

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"strings"
)

type Identity struct {
	Path   string `json:"path"`
	SHA256 string `json:"sha256"`
}
type Module struct {
	GroupID    string   `json:"groupId"`
	ArtifactID string   `json:"artifactId"`
	Version    string   `json:"version"`
	Directory  string   `json:"directory"`
	Category   string   `json:"category"`
	Jar        Identity `json:"jar"`
	POM        Identity `json:"pom"`
}
type JVMBuild struct {
	FormatVersion       string                `json:"formatVersion"`
	Version             string                `json:"version"`
	MainClass           string                `json:"mainClass"`
	Modules             []Module              `json:"modules"`
	RuntimeClasspath    []Identity            `json:"runtimeClasspath"`
	Scripting           ScriptClasspaths      `json:"scripting"`
	Drivers             map[string][]Identity `json:"drivers"`
	ExternalArtifacts   []MavenArtifact       `json:"externalArtifacts"`
	Licenses            []Identity            `json:"licenses"`
	GitHead             string                `json:"gitHead,omitempty"`
	SourceDateEpoch     string                `json:"sourceDateEpoch,omitempty"`
	ConnectorAPIVersion string                `json:"connectorApiVersion,omitempty"`
	Sources             map[string]string     `json:"sources,omitempty"`
}

type ScriptClasspaths struct {
	DSLAPIVersion string     `json:"dslApiVersion"`
	Runtime       []Identity `json:"runtime"`
	DSL           []Identity `json:"dsl"`
}

func (b JVMBuild) classpathGroups() [][]Identity {
	return [][]Identity{b.RuntimeClasspath, b.Scripting.Runtime, b.Scripting.DSL}
}

func (b JVMBuild) classpathFiles() []Identity {
	seen := make(map[string]bool)
	var files []Identity
	for _, group := range b.classpathGroups() {
		for _, file := range group {
			if !seen[file.Path] {
				files = append(files, file)
				seen[file.Path] = true
			}
		}
	}
	return files
}

type MavenArtifact struct {
	File       Identity `json:"file"`
	Coordinate string   `json:"coordinate"`
	URL        string   `json:"url"`
	Size       int64    `json:"size"`
}
type ServiceBuild struct {
	Version string            `json:"version"`
	OS      string            `json:"os"`
	Arch    string            `json:"arch"`
	Sources map[string]string `json:"sources"`
	Binary  string            `json:"binary"`
	SHA256  string            `json:"sha256"`
}
type Package struct {
	Name          string          `json:"name"`
	Version       string          `json:"version"`
	Repository    json.RawMessage `json:"repository"`
	PublishConfig json.RawMessage `json:"publishConfig"`
}

func (w Workspace) Package() (Package, error) {
	// package.json 还拥有 npm 自身字段；这里只提取发行真正消费的事实。
	data, err := os.ReadFile(w.Path("project", "cli", "package.json"))
	if err != nil {
		return Package{}, err
	}
	var value Package
	if err = json.Unmarshal(data, &value); err != nil {
		return value, err
	}
	if value.Name == "" || value.Version == "" {
		return value, errors.New("npm 产品缺少明确名称和版本")
	}
	return value, nil
}
func (identity Identity) Verify() (string, error) {
	if !filepath.IsAbs(identity.Path) {
		return "", errors.New("构件记录必须保存绝对路径")
	}
	digest, err := Digest(identity.Path)
	if err != nil {
		return "", err
	}
	if digest != identity.SHA256 {
		return "", fmt.Errorf("构件不同于本轮记录：%s", identity.Path)
	}
	return identity.Path, nil
}
func (build JVMBuild) Module(name string) (Module, error) {
	for _, module := range build.Modules {
		if module.ArtifactID == name {
			return module, nil
		}
	}
	return Module{}, fmt.Errorf("Gradle 构建没有模块：%s", name)
}
func (w Workspace) jvmSources(build JVMBuild) (map[string]string, error) {
	paths := []string{w.Path("LICENSE"), w.Path("project", "cli", "package.json"), w.Path("project", "settings.gradle.kts"), w.Path("project", "build.gradle.kts"), w.Path("project", "gradle.properties"), w.Path("project", "gradle"), w.Path("project", "protocol"), w.Path("project", "distribution-resources")}
	for _, module := range build.Modules {
		directory := w.Path("project", module.Directory)
		paths = append(paths, filepath.Join(directory, "build.gradle.kts"), filepath.Join(directory, "src", "main"))
		if module.ArtifactID == "examples" {
			err := filepath.WalkDir(directory, func(path string, entry os.DirEntry, err error) error {
				if err != nil {
					return err
				}
				if entry.IsDir() {
					if entry.Name() == "build" || entry.Name() == ".gradle" || entry.Name() == "node_modules" || entry.Name() == ".tmp" {
						return filepath.SkipDir
					}
					return nil
				}
				if strings.HasSuffix(path, ".kt") {
					paths = append(paths, path)
				}
				return nil
			})
			if err != nil {
				return nil, err
			}
		}
	}
	return fileHashes(w.Root, paths)
}
func (w Workspace) BuildJVM(ctx context.Context) error {
	if err := w.Gradle(ctx, "assembleJvm"); err != nil {
		return err
	}
	var build JVMBuild
	if err := ReadJSON(w.Path("project", "build", "jvm-artifacts.json"), &build); err != nil {
		return err
	}
	if build.FormatVersion != "agentic-olap-jvm-build/v4" {
		return errors.New("Gradle 构件清单格式不匹配")
	}
	api, err := build.Module("connector-api")
	if err != nil {
		return err
	}
	jar, err := api.Jar.Verify()
	if err != nil {
		return err
	}
	javap, err := Java("javap")
	if err != nil {
		return err
	}
	inspection, err := output(ctx, w.Root, javap, "-constants", "-classpath", jar, "com.olap.prototype.connector.OlapConnector")
	if err != nil {
		return err
	}
	versions := regexp.MustCompile(`(?m)^\s*public static final java\.lang\.String API_VERSION = "([^"\n]+)";\s*$`).FindAllSubmatch(inspection, -1)
	if len(versions) != 1 {
		return errors.New("连接器 API 构件必须公开唯一 API_VERSION")
	}
	build.ConnectorAPIVersion = string(versions[0][1])
	head, err := output(ctx, w.Root, "git", "rev-parse", "HEAD")
	if err != nil {
		return err
	}
	epoch, err := output(ctx, w.Root, "git", "log", "-1", "--format=%ct")
	if err != nil {
		return err
	}
	build.GitHead = strings.TrimSpace(string(head))
	build.SourceDateEpoch = strings.TrimSpace(string(epoch))
	build.Sources, err = w.jvmSources(build)
	if err != nil {
		return err
	}
	return WriteJSON(w.Path("project", "build", "build.json"), build)
}
func (w Workspace) ReadJVM() (JVMBuild, error) {
	var build JVMBuild
	if err := ReadJSON(w.Path("project", "build", "build.json"), &build); err != nil {
		return build, err
	}
	sources, err := w.jvmSources(build)
	if err != nil {
		return build, err
	}
	if build.FormatVersion != "agentic-olap-jvm-build/v4" || build.ConnectorAPIVersion == "" || build.Scripting.DSLAPIVersion != "agentic-olap-kotlin-dsl/v1" || len(build.Scripting.Runtime) == 0 || len(build.Scripting.DSL) == 0 || !sameHashes(build.Sources, sources) {
		return build, errors.New("JVM 源码与构建记录不同，请先完成同轮 jvm-build")
	}
	identities := build.classpathFiles()
	identities = append(identities, build.Licenses...)
	for _, module := range build.Modules {
		identities = append(identities, module.Jar, module.POM)
	}
	for _, group := range build.Drivers {
		identities = append(identities, group...)
	}
	for _, identity := range identities {
		if _, err := identity.Verify(); err != nil {
			return build, err
		}
	}
	return build, nil
}
func (w Workspace) serviceSources() (map[string]string, error) {
	paths := []string{w.Path("project", "service", "go.mod"), w.Path("project", "service", "go.sum"), w.Path("project", "cli", "package.json")}
	err := filepath.WalkDir(w.Path("project", "service"), func(path string, entry os.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if !entry.IsDir() && strings.HasSuffix(path, ".go") {
			paths = append(paths, path)
		}
		return nil
	})
	if err != nil {
		return nil, err
	}
	return fileHashes(w.Root, paths)
}
func (w Workspace) BuildService(ctx context.Context) error {
	metadata, err := w.Package()
	if err != nil {
		return err
	}
	destination := w.Path("project", "build", "service", "agentic-olap-runtime")
	if err = os.MkdirAll(filepath.Dir(destination), 0755); err != nil {
		return err
	}
	if err = Run(ctx, w.Path("project", "service"), []string{"CGO_ENABLED=0"}, "go", "build", "-mod=readonly", "-trimpath", "-buildvcs=true", "-ldflags", "-s -w -X main.version="+metadata.Version, "-o", destination, "./cmd/agentic-olap-runtime"); err != nil {
		return err
	}
	osName, err := output(ctx, w.Path("project", "service"), "go", "env", "GOOS")
	if err != nil {
		return err
	}
	arch, err := output(ctx, w.Path("project", "service"), "go", "env", "GOARCH")
	if err != nil {
		return err
	}
	sources, err := w.serviceSources()
	if err != nil {
		return err
	}
	digest, err := Digest(destination)
	if err != nil {
		return err
	}
	return WriteJSON(w.Path("project", "build", "service.json"), ServiceBuild{Version: metadata.Version, OS: strings.TrimSpace(string(osName)), Arch: strings.TrimSpace(string(arch)), Sources: sources, Binary: destination, SHA256: digest})
}
func (w Workspace) ReadService() (ServiceBuild, error) {
	var build ServiceBuild
	if err := ReadJSON(w.Path("project", "build", "service.json"), &build); err != nil {
		return build, err
	}
	metadata, err := w.Package()
	if err != nil {
		return build, err
	}
	sources, err := w.serviceSources()
	if err != nil {
		return build, err
	}
	if metadata.Version != build.Version || !sameHashes(build.Sources, sources) {
		return build, errors.New("Go 源码与构建记录不同，请先完成同轮 service-build")
	}
	_, err = (Identity{Path: build.Binary, SHA256: build.SHA256}).Verify()
	return build, err
}
