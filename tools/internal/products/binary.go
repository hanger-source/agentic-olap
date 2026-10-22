package products

import (
	"archive/tar"
	"compress/gzip"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"os"
	"path/filepath"
	"runtime"
	"strings"
)

func (w Workspace) Binary(ctx context.Context, destination, distribution string) error {
	metadata, err := w.Package()
	if err != nil {
		return err
	}
	build, err := w.ReadJVM()
	if err != nil {
		return err
	}
	external := map[string]MavenArtifact{}
	for _, artifact := range build.ExternalArtifacts {
		name := filepath.Base(artifact.File.Path)
		if prior, exists := external[name]; exists && prior.File.SHA256 != artifact.File.SHA256 {
			return fmt.Errorf("Maven JAR 文件名冲突：%s", name)
		}
		external[name] = artifact
	}
	manifest, err := ReadDistribution(distribution)
	if err != nil {
		return err
	}
	if runtime.GOOS != "darwin" || runtime.GOARCH != "arm64" {
		return errors.New("npm 平台运行包只支持 macOS arm64")
	}
	target := "darwin-arm64"
	if manifest.Version != metadata.Version || manifest.Service.OS != runtime.GOOS || manifest.Service.Arch != runtime.GOARCH {
		return errors.New("平台包必须消费本平台同轮服务发行")
	}
	nativeName := "@hanger-source/agentic-olap-runtime-" + target
	if metadata.OptionalDependencies[nativeName] != metadata.Version {
		return errors.New("主包与平台包版本必须精确一致")
	}
	jlink, err := Java("jlink")
	if err != nil {
		return err
	}
	javaHome := filepath.Dir(filepath.Dir(jlink))
	release, err := os.ReadFile(filepath.Join(javaHome, "release"))
	if err != nil {
		return err
	}
	if !strings.Contains(string(release), `JAVA_VERSION="21`) {
		return errors.New("发行要求 JAVA_HOME 指向完整 JDK 21")
	}
	if err = newDirectory(destination); err != nil {
		return err
	}
	main := filepath.Join(destination, "packages", "agentic-olap")
	if err = CopyTree(w.Path("project", "cli"), main, func(path string) bool {
		return path == "tests" || strings.HasPrefix(path, "tests/") || path == "release" || strings.HasPrefix(path, "release/") || path == "LICENSE"
	}); err != nil {
		return err
	}
	if err = CopyFile(w.Path("LICENSE"), filepath.Join(main, "LICENSE")); err != nil {
		return err
	}
	native := filepath.Join(destination, "packages", "agentic-olap-runtime-"+target)
	if err = os.MkdirAll(native, 0755); err != nil {
		return err
	}
	pkg := map[string]any{"name": nativeName, "version": metadata.Version, "description": "Agentic OLAP bundled service runtime for " + target, "license": "Apache-2.0", "os": []string{runtime.GOOS}, "cpu": []string{strings.Split(target, "-")[1]}, "repository": metadata.Repository, "publishConfig": metadata.PublishConfig, "files": []string{"jre", "distribution", "LICENSE", "build.json"}}
	if err = WriteJSON(filepath.Join(native, "package.json"), pkg); err != nil {
		return err
	}
	if err = CopyFile(w.Path("LICENSE"), filepath.Join(native, "LICENSE")); err != nil {
		return err
	}
	image := filepath.Join(destination, "jre-image")
	if err = Run(ctx, w.Root, nil, jlink, "--add-modules", "java.se,jdk.unsupported,jdk.crypto.ec", "--strip-debug", "--no-header-files", "--no-man-pages", "--compress=2", "--output", image); err != nil {
		return err
	}
	if err = copyJRE(image, filepath.Join(native, "jre")); err != nil {
		return err
	}
	if err = CopyTree(distribution, filepath.Join(native, "distribution"), func(path string) bool {
		_, download := external[filepath.Base(path)]
		return path == "Dockerfile" || strings.HasPrefix(filepath.ToSlash(path), "java/lib/") && download
	}); err != nil {
		return err
	}
	files, err := fileHashes(native, []string{native})
	if err != nil {
		return err
	}
	head, err := output(ctx, w.Root, "git", "rev-parse", "HEAD")
	if err != nil {
		return err
	}
	modHash, err := Digest(w.Path("project", "service", "go.mod"))
	if err != nil {
		return err
	}
	sumHash, err := Digest(w.Path("project", "service", "go.sum"))
	if err != nil {
		return err
	}
	if err = WriteJSON(filepath.Join(native, "build.json"), map[string]any{"version": metadata.Version, "target": target, "revision": strings.TrimSpace(string(head)), "goModSha256": modHash, "goSumSha256": sumHash, "files": files}); err != nil {
		return err
	}
	tarballs := filepath.Join(destination, "tarballs")
	if err = os.Mkdir(tarballs, 0755); err != nil {
		return err
	}
	if err = w.bundleExtensions(main, filepath.Join(destination, "connectors"), distribution, metadata.Version); err != nil {
		return err
	}
	if err = writeJVMDownloads(main, nativeName, metadata.Version, build, external); err != nil {
		return err
	}
	for _, pkg := range []string{native, main} {
		if err = Run(ctx, w.Root, nil, "npm", "pack", "--quiet", pkg, "--pack-destination", tarballs); err != nil {
			return err
		}
	}
	filename := strings.ReplaceAll(strings.TrimPrefix(nativeName, "@"), "/", "-") + "-" + metadata.Version + ".tgz"
	if err = verifyNPMPackage(filepath.Join(tarballs, filename), files); err != nil {
		return err
	}
	var sums strings.Builder
	for _, name := range []string{nativeName, metadata.Name} {
		archive := strings.ReplaceAll(strings.TrimPrefix(name, "@"), "/", "-") + "-" + metadata.Version + ".tgz"
		digest, err := Digest(filepath.Join(tarballs, archive))
		if err != nil {
			return err
		}
		fmt.Fprintf(&sums, "%s  %s\n", digest, archive)
	}
	if err = os.WriteFile(filepath.Join(tarballs, "SHA256SUMS"), []byte(sums.String()), 0644); err != nil {
		return err
	}
	fmt.Printf("平台包已装配：%s\n", tarballs)
	return nil
}
func copyJRE(source, destination string) error {
	return filepath.WalkDir(source, func(path string, entry fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		relative, err := filepath.Rel(source, path)
		if err != nil {
			return err
		}
		target := filepath.Join(destination, relative)
		if entry.IsDir() {
			return os.MkdirAll(target, 0755)
		}
		resolved, err := filepath.EvalSymlinks(path)
		if err != nil {
			return err
		}
		inside, err := filepath.Rel(source, resolved)
		if err != nil || inside == ".." || strings.HasPrefix(inside, ".."+string(filepath.Separator)) {
			return errors.New("JRE 符号链接越过生成镜像")
		}
		return CopyFile(resolved, target)
	})
}
func verifyNPMPackage(path string, files map[string]string) error {
	file, err := os.Open(path)
	if err != nil {
		return err
	}
	defer file.Close()
	info, err := file.Stat()
	if err != nil {
		return err
	}
	if info.Size() > 150*1024*1024 {
		return errors.New("npm 平台包超过 150 MiB 发行预算")
	}
	reader, err := gzip.NewReader(file)
	if err != nil {
		return err
	}
	defer reader.Close()
	tarball := tar.NewReader(reader)
	seen := map[string]bool{}
	for {
		header, err := tarball.Next()
		if err == io.EOF {
			break
		}
		if err != nil {
			return err
		}
		relative := strings.TrimPrefix(header.Name, "package/")
		expected, exists := files[relative]
		if !exists {
			continue
		}
		hash := sha256.New()
		if _, err = io.Copy(hash, tarball); err != nil {
			return err
		}
		if header.Typeflag != tar.TypeReg || hex.EncodeToString(hash.Sum(nil)) != expected {
			return fmt.Errorf("npm 平台包字节不同：%s", relative)
		}
		seen[relative] = true
	}
	if len(seen) != len(files) {
		return errors.New("npm 平台包缺少实际发行文件")
	}
	return nil
}

// npm 交付自有构件、SDK 声明和插件；第三方 JAR 由同一安装清单取得。
func (w Workspace) bundleExtensions(main, directory, distribution, version string) error {
	build, err := w.ReadJVM()
	if err != nil {
		return err
	}
	sdk := filepath.Join(main, "sdk")
	if err = os.MkdirAll(sdk, 0755); err != nil {
		return err
	}
	if err = CopyTree(filepath.Join(distribution, "licenses", "jvm"), filepath.Join(sdk, "licenses"), nil); err != nil {
		return err
	}
	if err = CopyFile(w.Path("LICENSE"), filepath.Join(sdk, "LICENSE")); err != nil {
		return err
	}
	configuration, err := w.ReferenceConnectors(directory)
	if err != nil {
		return err
	}
	var manifests []string
	if err = ReadJSON(configuration, &manifests); err != nil {
		return err
	}
	installed := make([]string, 0, len(manifests))
	external := map[string]bool{}
	for _, artifact := range build.ExternalArtifacts {
		external[filepath.Base(artifact.File.Path)] = true
	}
	for _, manifest := range manifests {
		group := filepath.Dir(manifest)
		if err = CopyTree(group, filepath.Join(main, "connectors", filepath.Base(group)), func(path string) bool { return external[filepath.Base(path)] }); err != nil {
			return err
		}
		installed = append(installed, filepath.ToSlash(filepath.Join("connectors", filepath.Base(group), "connector.json")))
	}
	h2License, err := os.ReadFile(w.Path("project", "distribution-resources", "META-INF", "third-party", "h2-2.2.224", "LICENSE.txt"))
	if err != nil {
		return err
	}
	h2Sources, err := os.ReadFile(w.Path("project", "distribution-resources", "META-INF", "third-party", "h2-2.2.224", "SOURCES.txt"))
	if err != nil {
		return err
	}
	if err = os.WriteFile(filepath.Join(main, "connectors", "H2-LICENSE.txt"), h2License, 0644); err != nil {
		return err
	}
	if err = os.WriteFile(filepath.Join(main, "connectors", "H2-SOURCES.txt"), h2Sources, 0644); err != nil {
		return err
	}
	sources := map[string]string{"API.md": "project/modules/kernel/connector-api/README.md", "SOURCE-ACCESS.md": "docs/source-access.md", "LOADER.md": "project/modules/applications/connector-loader/README.md"}
	replacements := strings.NewReplacer("../../../../docs/source-access.md", "SOURCE-ACCESS.md", "../../applications/connector-loader/README.md", "LOADER.md", "../project/modules/kernel/connector-api/README.md", "API.md", "../../kernel/connector-api/README.md", "API.md")
	for name, path := range sources {
		content, err := os.ReadFile(w.Path(filepath.FromSlash(path)))
		if err != nil {
			return err
		}
		if err = os.WriteFile(filepath.Join(sdk, name), []byte(replacements.Replace(string(content))), 0644); err != nil {
			return err
		}
	}
	coordinates := map[string]string{}
	for _, module := range build.Modules {
		if module.Category == "kernel" || module.ArtifactID == "connector-loader" {
			coordinates[module.ArtifactID] = module.GroupID + ":" + module.ArtifactID + ":" + module.Version
		}
	}
	return WriteJSON(filepath.Join(main, "resources.json"), map[string]any{
		"version": version, "connectorApiVersion": build.ConnectorAPIVersion,
		"sdk": "sdk", "sdkRepository": "https://jitpack.io", "sdkArtifacts": coordinates,
		"connectors": installed,
	})
}

func writeJVMDownloads(main, runtimePackage, version string, build JVMBuild, external map[string]MavenArtifact) error {
	artifacts := map[string]map[string]any{}
	add := func(name, target, path string, digest string) error {
		artifact, exists := external[name]
		if !exists {
			return nil
		}
		if artifact.File.SHA256 != digest {
			return fmt.Errorf("安装依赖与原构件字节不同：%s", path)
		}
		entry := artifacts[digest]
		if entry == nil {
			entry = map[string]any{"coordinate": artifact.Coordinate, "url": artifact.URL, "sha256": digest, "size": artifact.Size, "locations": []map[string]string{}}
			artifacts[digest] = entry
		}
		entry["locations"] = append(entry["locations"].([]map[string]string), map[string]string{"target": target, "path": path})
		return nil
	}
	for _, artifact := range build.RuntimeClasspath {
		name := filepath.Base(artifact.Path)
		if err := add(name, "runtime", "distribution/java/lib/"+name, artifact.SHA256); err != nil {
			return err
		}
	}
	var resources map[string]json.RawMessage
	if err := ReadJSON(filepath.Join(main, "resources.json"), &resources); err != nil {
		return err
	}
	var connectors []string
	if err := json.Unmarshal(resources["connectors"], &connectors); err != nil {
		return err
	}
	for _, path := range connectors {
		var manifest ConnectorManifest
		if err := ReadJSON(filepath.Join(main, path), &manifest); err != nil {
			return err
		}
		for _, jar := range manifest.Jars {
			if err := add(jar.File, "product", filepath.ToSlash(filepath.Join(filepath.Dir(path), jar.File)), jar.SHA256); err != nil {
				return err
			}
		}
	}
	ordered := make([]map[string]any, 0, len(artifacts))
	for _, hash := range sortedKeys(artifacts) {
		ordered = append(ordered, artifacts[hash])
	}
	if len(ordered) == 0 {
		return errors.New("npm 产品缺少实际 Maven 安装依赖")
	}
	return WriteJSON(filepath.Join(main, "jvm-dependencies.json"), map[string]any{"formatVersion": "agentic-olap-jvm-dependencies/v1", "version": version, "runtimePackage": runtimePackage, "artifacts": ordered})
}
