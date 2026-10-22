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
	"strconv"
	"strings"
	"sync"
	"time"
)

type jvmDependency struct {
	Coordinate string   `json:"coordinate"`
	URL        string   `json:"url"`
	SHA256     string   `json:"sha256"`
	Size       int64    `json:"size"`
	Locations  []string `json:"locations"`
}
type jvmDependencies struct {
	FormatVersion string          `json:"formatVersion"`
	Version       string          `json:"version"`
	Artifacts     []jvmDependency `json:"artifacts"`
}

// InstallDependencies 仅由 npm postinstall 调用；启动、SDK 读取和查询均不调用它。
func InstallDependencies(ctx context.Context, version string) error {
	ctx, cancel := context.WithTimeout(ctx, 30*time.Minute)
	defer cancel()
	product, err := Read(version)
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
	var installed struct {
		Name    string `json:"name"`
		Version string `json:"version"`
	}
	data, err = os.ReadFile(filepath.Join(product.root, "package.json"))
	if err != nil {
		return err
	}
	if err = json.Unmarshal(data, &installed); err != nil {
		return err
	}
	if manifest.FormatVersion != "agentic-olap-jvm-dependencies/v2" || manifest.Version != version || installed.Version != version || installed.Name != "@hanger-source/agentic-olap" || len(manifest.Artifacts) == 0 {
		return errors.New("npm JVM 依赖清单与安装版本不一致")
	}
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
	return installJVMDependencies(ctx, manifest.Artifacts, product.root, filepath.Join(cache, "jvm", "sha256"), client, os.Stderr)
}

func installJVMDependencies(ctx context.Context, artifacts []jvmDependency, root string, cache string, client *http.Client, log io.Writer) error {
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
			path, err := dependencyPath(root, location)
			if err != nil {
				return err
			}
			if seen[path] {
				return fmt.Errorf("JVM 安装目标重复：%s", location)
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
			if err := installJVMDependency(ctx, artifact, root, cache, client, log); err != nil {
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

func dependencyPath(root, location string) (string, error) {
	if !filepath.IsAbs(root) || strings.Contains(location, "\\") || filepath.IsAbs(location) || filepath.ToSlash(filepath.Clean(location)) != location || !strings.HasSuffix(location, ".jar") {
		return "", errors.New("JVM 安装目标必须是包内精确 JAR 路径")
	}
	if !strings.HasPrefix(location, "java/lib/") && !strings.HasPrefix(location, "connectors/") {
		return "", errors.New("JVM 安装目标不属于运行库或插件")
	}
	resolvedRoot, err := filepath.EvalSymlinks(root)
	if err != nil {
		return "", err
	}
	parent := resolvedRoot
	for _, part := range strings.Split(filepath.ToSlash(filepath.Dir(location)), "/") {
		parent = filepath.Join(parent, part)
		if err = os.Mkdir(parent, 0755); err != nil && !errors.Is(err, os.ErrExist) {
			return "", err
		}
		info, err := os.Lstat(parent)
		if err != nil {
			return "", err
		}
		if !info.IsDir() || info.Mode()&os.ModeSymlink != 0 {
			return "", errors.New("JVM 安装目录必须是 npm 包内的真实目录")
		}
	}
	return filepath.Join(parent, filepath.Base(location)), nil
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

func installJVMDependency(ctx context.Context, artifact jvmDependency, root string, cache string, client *http.Client, log io.Writer) error {
	missing := []string{}
	for _, location := range artifact.Locations {
		path, err := dependencyPath(root, location)
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
		response, err := requestDependency(ctx, client, artifact, log)
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

// 仓库限流只重试同一不可变 URL，不改来源、版本或摘要。
func requestDependency(ctx context.Context, client *http.Client, artifact jvmDependency, log io.Writer) (*http.Response, error) {
	for attempt := 0; ; attempt++ {
		request, err := http.NewRequestWithContext(ctx, http.MethodGet, artifact.URL, nil)
		if err != nil {
			return nil, err
		}
		response, err := client.Do(request)
		if err != nil {
			return nil, err
		}
		if response.StatusCode != http.StatusTooManyRequests || attempt == 4 {
			return response, nil
		}
		response.Body.Close()
		delay := time.Duration(2<<attempt) * time.Second
		if value := response.Header.Get("Retry-After"); value != "" {
			if seconds, err := strconv.Atoi(value); err == nil && seconds >= 0 {
				if seconds > 1800 {
					return nil, errors.New("仓库要求的限流等待超出 30 分钟安装预算")
				}
				delay = time.Duration(seconds) * time.Second
			} else if date, err := http.ParseTime(value); err == nil {
				delay = max(time.Until(date), 0)
			} else {
				return nil, fmt.Errorf("Maven 429 返回了无效 Retry-After：%s", value)
			}
		}
		fmt.Fprintf(log, "仓库限流，%s 后重试 %s（%d/4）\n", delay, artifact.Coordinate, attempt+1)
		timer := time.NewTimer(delay)
		select {
		case <-ctx.Done():
			timer.Stop()
			return nil, ctx.Err()
		case <-timer.C:
		}
	}
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
