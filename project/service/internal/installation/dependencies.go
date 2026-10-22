package installation

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

type dependencyLocation struct {
	Target string `json:"target"`
	Path   string `json:"path"`
}
type jvmDependency struct {
	Coordinate string               `json:"coordinate"`
	URL        string               `json:"url"`
	SHA256     string               `json:"sha256"`
	Size       int64                `json:"size"`
	Locations  []dependencyLocation `json:"locations"`
}
type jvmDependencies struct {
	FormatVersion  string          `json:"formatVersion"`
	Version        string          `json:"version"`
	RuntimePackage string          `json:"runtimePackage"`
	Artifacts      []jvmDependency `json:"artifacts"`
}

// InstallDependencies 仅由 npm postinstall 调用；启动、SDK 读取和查询均不调用它。
func InstallDependencies(ctx context.Context, version string) error {
	product, err := Read(version)
	if err != nil {
		return err
	}
	home := os.Getenv("AGENTIC_OLAP_HOME")
	if !filepath.IsAbs(home) {
		return errors.New("npm 平台运行目录缺失")
	}
	runtimeRoot, err := filepath.EvalSymlinks(filepath.Dir(home))
	if err != nil {
		return err
	}
	var manifest jvmDependencies
	data, err := os.ReadFile(filepath.Join(product.root, "jvm-dependencies.json"))
	if err != nil {
		return err
	}
	if err = json.Unmarshal(data, &manifest); err != nil {
		return err
	}
	var native struct {
		Name    string `json:"name"`
		Version string `json:"version"`
	}
	data, err = os.ReadFile(filepath.Join(runtimeRoot, "package.json"))
	if err != nil {
		return err
	}
	if err = json.Unmarshal(data, &native); err != nil {
		return err
	}
	if manifest.FormatVersion != "agentic-olap-jvm-dependencies/v1" || manifest.Version != version || native.Version != version || native.Name != manifest.RuntimePackage || len(manifest.Artifacts) == 0 {
		return errors.New("npm JVM 依赖清单与安装版本不一致")
	}
	roots := map[string]string{"product": product.root, "runtime": runtimeRoot}
	cache := os.Getenv("AGENTIC_OLAP_CACHE")
	if cache == "" {
		cache, err = os.UserCacheDir()
		if err != nil {
			return err
		}
		cache = filepath.Join(cache, "agentic-olap")
	}
	if !filepath.IsAbs(cache) {
		return errors.New("AGENTIC_OLAP_CACHE 必须为绝对路径")
	}
	client := &http.Client{Timeout: 30 * time.Minute, CheckRedirect: func(request *http.Request, via []*http.Request) error {
		if request.URL.Scheme != "https" || request.URL.User != nil || len(via) >= 10 {
			return errors.New("Maven 下载重定向必须保持 HTTPS 且不含凭据")
		}
		return nil
	}}
	return installJVMDependencies(ctx, manifest.Artifacts, roots, filepath.Join(cache, "jvm", "sha256"), client, os.Stderr)
}

func installJVMDependencies(ctx context.Context, artifacts []jvmDependency, roots map[string]string, cache string, client *http.Client, log io.Writer) error {
	seen := map[string]bool{}
	for _, artifact := range artifacts {
		digest, err := hex.DecodeString(artifact.SHA256)
		if err != nil || len(digest) != sha256.Size || strings.ToLower(artifact.SHA256) != artifact.SHA256 || artifact.Size <= 0 || artifact.Coordinate == "" || len(artifact.Locations) == 0 {
			return errors.New("Maven 安装依赖缺少精确身份、容量或目标")
		}
		address, err := url.Parse(artifact.URL)
		if err != nil || address.Scheme != "https" || address.Host == "" || address.User != nil || address.RawQuery != "" || address.Fragment != "" {
			return errors.New("Maven 安装地址必须是无凭据的 HTTPS 构件地址")
		}
		for _, location := range artifact.Locations {
			path, err := dependencyPath(roots, location)
			if err != nil {
				return err
			}
			if seen[path] {
				return fmt.Errorf("JVM 安装目标重复：%s", location.Path)
			}
			seen[path] = true
		}
	}
	if err := os.MkdirAll(cache, 0755); err != nil {
		return err
	}
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	var workers sync.WaitGroup
	var first sync.Once
	var failure error
	capacity := make(chan struct{}, 6)
	for _, artifact := range artifacts {
		workers.Add(1)
		go func() {
			defer workers.Done()
			select {
			case capacity <- struct{}{}:
			case <-ctx.Done():
				return
			}
			defer func() { <-capacity }()
			if err := installJVMDependency(ctx, artifact, roots, cache, client, log); err != nil {
				first.Do(func() { failure = fmt.Errorf("安装 JVM 依赖 %s 失败：%w", artifact.Coordinate, err); cancel() })
			}
		}()
	}
	workers.Wait()
	if failure != nil {
		return failure
	}
	return ctx.Err()
}

func dependencyPath(roots map[string]string, location dependencyLocation) (string, error) {
	root, exists := roots[location.Target]
	if !exists || !filepath.IsAbs(root) || strings.Contains(location.Path, "\\") || filepath.IsAbs(location.Path) || filepath.ToSlash(filepath.Clean(location.Path)) != location.Path || !strings.HasSuffix(location.Path, ".jar") {
		return "", errors.New("JVM 安装目标必须是包内精确 JAR 路径")
	}
	prefix := "connectors/"
	if location.Target == "runtime" {
		prefix = "distribution/java/lib/"
	}
	if !strings.HasPrefix(location.Path, prefix) {
		return "", errors.New("JVM 安装目标不属于运行库或插件")
	}
	parent, err := filepath.EvalSymlinks(filepath.Join(root, filepath.Dir(filepath.FromSlash(location.Path))))
	if err != nil {
		return "", err
	}
	resolvedRoot, err := filepath.EvalSymlinks(root)
	if err != nil {
		return "", err
	}
	relative, err := filepath.Rel(resolvedRoot, parent)
	if err != nil || relative == ".." || strings.HasPrefix(relative, ".."+string(filepath.Separator)) {
		return "", errors.New("JVM 安装目标越出 npm 包")
	}
	return filepath.Join(parent, filepath.Base(location.Path)), nil
}

func verifyDependency(path string, artifact jvmDependency) error {
	info, err := os.Lstat(path)
	if err != nil {
		return err
	}
	if !info.Mode().IsRegular() || info.Size() != artifact.Size {
		return fmt.Errorf("JAR 类型或长度不符：%s", path)
	}
	file, err := os.Open(path)
	if err != nil {
		return err
	}
	defer file.Close()
	digest := sha256.New()
	if _, err = io.Copy(digest, file); err != nil {
		return err
	}
	if hex.EncodeToString(digest.Sum(nil)) != artifact.SHA256 {
		return fmt.Errorf("JAR 摘要不符：%s", path)
	}
	return nil
}

func installJVMDependency(ctx context.Context, artifact jvmDependency, roots map[string]string, cache string, client *http.Client, log io.Writer) error {
	missing := []string{}
	for _, location := range artifact.Locations {
		path, err := dependencyPath(roots, location)
		if err != nil {
			return err
		}
		if err = verifyDependency(path, artifact); errors.Is(err, os.ErrNotExist) {
			missing = append(missing, path)
		} else if err != nil {
			return err
		}
	}
	if len(missing) == 0 {
		return nil
	}
	cached := filepath.Join(cache, artifact.SHA256+".jar")
	if err := verifyDependency(cached, artifact); errors.Is(err, os.ErrNotExist) {
		fmt.Fprintf(log, "下载 JVM 依赖 %s\n", artifact.Coordinate)
		request, err := http.NewRequestWithContext(ctx, http.MethodGet, artifact.URL, nil)
		if err != nil {
			return err
		}
		response, err := client.Do(request)
		if err != nil {
			return err
		}
		defer response.Body.Close()
		if response.StatusCode != http.StatusOK {
			return fmt.Errorf("Maven HTTP %d", response.StatusCode)
		}
		if err = writeDependency(cached, io.LimitReader(response.Body, artifact.Size+1), artifact); err != nil {
			return err
		}
	} else if err != nil {
		return err
	}
	for _, path := range missing {
		file, err := os.Open(cached)
		if err != nil {
			return err
		}
		err = writeDependency(path, file, artifact)
		closeErr := file.Close()
		if err != nil {
			return err
		}
		if closeErr != nil {
			return closeErr
		}
	}
	return nil
}

func writeDependency(destination string, input io.Reader, artifact jvmDependency) error {
	file, err := os.CreateTemp(filepath.Dir(destination), ".jvm-*.part")
	if err != nil {
		return err
	}
	defer os.Remove(file.Name())
	_, copyErr := io.Copy(file, input)
	closeErr := file.Close()
	if copyErr != nil {
		return copyErr
	}
	if closeErr != nil {
		return closeErr
	}
	if err = verifyDependency(file.Name(), artifact); err != nil {
		return err
	}
	if err = os.Chmod(file.Name(), 0644); err != nil {
		return err
	}
	return os.Rename(file.Name(), destination)
}
