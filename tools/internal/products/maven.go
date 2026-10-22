package products

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

// publishedJVM 取得 JitPack 实际发布的字节，运行清单和插件身份均采用远端摘要。
func (w Workspace) publishedJVM(ctx context.Context, build JVMBuild, directory string) (JVMBuild, map[string]MavenArtifact, error) {
	artifacts := map[string]MavenArtifact{}
	add := func(artifact MavenArtifact) error {
		name := filepath.Base(artifact.File.Path)
		if prior, exists := artifacts[name]; exists && prior.File.SHA256 != artifact.File.SHA256 {
			return fmt.Errorf("Maven JAR 文件名冲突：%s", name)
		}
		artifacts[name] = artifact
		return nil
	}
	for _, artifact := range build.ExternalArtifacts {
		if err := add(artifact); err != nil {
			return build, nil, err
		}
	}
	selected := map[string]Module{}
	for _, module := range build.Modules {
		for _, input := range build.RuntimeClasspath {
			if module.Jar.Path == input.Path {
				selected[module.ArtifactID] = module
			}
		}
		if module.Category == "connector" {
			selected[module.ArtifactID] = module
		}
	}
	if len(selected) == 0 {
		return build, nil, errors.New("JVM 产品没有可发布的实际运行模块")
	}
	if err := os.MkdirAll(directory, 0755); err != nil {
		return build, nil, err
	}
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	client := &http.Client{Timeout: 30 * time.Minute}
	var workers sync.WaitGroup
	var lock sync.Mutex
	var first error
	remote := map[string]Identity{}
	capacity := make(chan struct{}, 6)
	for _, module := range selected {
		workers.Add(1)
		go func() {
			defer workers.Done()
			select {
			case capacity <- struct{}{}:
			case <-ctx.Done():
				return
			}
			defer func() { <-capacity }()
			filename := filepath.Base(module.Jar.Path)
			address := "https://jitpack.io/" + strings.ReplaceAll(module.GroupID, ".", "/") + "/" + module.ArtifactID + "/" + module.Version + "/" + filename
			artifact, err := fetchPublishedJar(ctx, client, address, filepath.Join(directory, filename))
			artifact.Coordinate = module.GroupID + ":" + module.ArtifactID + ":" + module.Version
			if module.Category == "connector" {
				artifact.Coordinate += ":plugin"
			}
			lock.Lock()
			defer lock.Unlock()
			if err == nil {
				err = add(artifact)
			}
			if err != nil {
				if first == nil {
					first = fmt.Errorf("JitPack 构件 %s：%w", artifact.Coordinate, err)
					cancel()
				}
				return
			}
			remote[module.Jar.Path] = artifact.File
		}()
	}
	workers.Wait()
	if first != nil {
		return build, nil, first
	}
	for index, input := range build.RuntimeClasspath {
		if actual, exists := remote[input.Path]; exists {
			build.RuntimeClasspath[index] = actual
		}
		if _, exists := artifacts[filepath.Base(input.Path)]; !exists {
			return build, nil, fmt.Errorf("运行 JAR 缺少正式 Maven 地址：%s", input.Path)
		}
	}
	for index, module := range build.Modules {
		if actual, exists := remote[module.Jar.Path]; exists {
			build.Modules[index].Jar = actual
		}
	}
	return build, artifacts, nil
}

func fetchPublishedJar(ctx context.Context, client *http.Client, address, destination string) (MavenArtifact, error) {
	var artifact MavenArtifact
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, address, nil)
	if err != nil {
		return artifact, err
	}
	response, err := client.Do(request)
	if err != nil {
		return artifact, err
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return artifact, fmt.Errorf("HTTP %d：%s", response.StatusCode, address)
	}
	file, err := os.Create(destination)
	if err != nil {
		return artifact, err
	}
	size, copyErr := io.Copy(file, response.Body)
	closeErr := file.Close()
	if copyErr != nil {
		return artifact, copyErr
	}
	if closeErr != nil {
		return artifact, closeErr
	}
	if size == 0 {
		return artifact, errors.New("远端 JAR 为空")
	}
	digest, err := Digest(destination)
	if err != nil {
		return artifact, err
	}
	return MavenArtifact{File: Identity{Path: destination, SHA256: digest}, URL: address, Size: size}, nil
}
