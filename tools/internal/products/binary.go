package products

import (
	"archive/tar"
	"compress/gzip"
	"context"
	"crypto/sha256"
	"encoding/hex"
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
	manifest, err := ReadDistribution(distribution)
	if err != nil {
		return err
	}
	if runtime.GOOS != "darwin" || runtime.GOARCH != "arm64" {
		return errors.New("npm 产品只支持 macOS arm64")
	}
	if manifest.Version != metadata.Version || manifest.Service.OS != runtime.GOOS || manifest.Service.Arch != runtime.GOARCH {
		return errors.New("npm 包必须消费本平台同轮服务发行")
	}
	head, err := output(ctx, w.Root, "git", "rev-parse", "HEAD")
	if err != nil {
		return err
	}
	tag, err := output(ctx, w.Root, "git", "rev-parse", "v"+metadata.Version+"^{commit}")
	if err != nil || strings.TrimSpace(string(tag)) != strings.TrimSpace(string(head)) {
		return errors.New("npm 装配必须位于本版 JitPack 精确发行标签")
	}
	if err = newDirectory(destination); err != nil {
		return err
	}
	build, artifacts, err := w.publishedJVM(ctx, build, filepath.Join(destination, "maven"))
	if err != nil {
		return err
	}
	main := filepath.Join(destination, "package")
	if err = CopyTree(w.Path("project", "cli"), main, func(path string) bool {
		return path == "tests" || strings.HasPrefix(path, "tests/") || path == "release" || strings.HasPrefix(path, "release/") || path == "LICENSE" || path == "licenses" || strings.HasPrefix(path, "licenses/")
	}); err != nil {
		return err
	}
	if err = CopyTree(distribution, main, func(path string) bool {
		return path == "Dockerfile" || strings.HasSuffix(path, ".jar") || path == "distribution.json"
	}); err != nil {
		return err
	}
	manifest.Runtime.Classpath = make([]Identity, 0, len(build.RuntimeClasspath))
	for _, artifact := range build.RuntimeClasspath {
		manifest.Runtime.Classpath = append(manifest.Runtime.Classpath, Identity{Path: "java/lib/" + filepath.Base(artifact.Path), SHA256: artifact.SHA256})
	}
	if err = WriteJSON(filepath.Join(main, "distribution.json"), manifest); err != nil {
		return err
	}
	if err = w.bundleExtensions(main, filepath.Join(destination, "connectors"), build, metadata.Version); err != nil {
		return err
	}
	if err = writeJVMDownloads(main, metadata.Version, build, artifacts); err != nil {
		return err
	}
	if err = bundleJRE(ctx, w.Root, main, destination); err != nil {
		return err
	}
	return w.packNPM(ctx, destination, main, metadata.Name, metadata.Version, strings.TrimSpace(string(head)))
}

func bundleJRE(ctx context.Context, workspace, main, destination string) error {
	jlink, err := Java("jlink")
	if err != nil {
		return err
	}
	release, err := os.ReadFile(filepath.Join(filepath.Dir(filepath.Dir(jlink)), "release"))
	if err != nil {
		return err
	}
	if !strings.Contains(string(release), `JAVA_VERSION="21`) {
		return errors.New("发行要求 JAVA_HOME 指向完整 JDK 21")
	}
	image := filepath.Join(destination, "jre-image")
	if err = Run(ctx, workspace, nil, jlink, "--add-modules", "java.se,jdk.unsupported,jdk.crypto.ec", "--strip-debug", "--no-header-files", "--no-man-pages", "--compress=2", "--output", image); err != nil {
		return err
	}
	if err = copyJRE(image, filepath.Join(main, "jre")); err != nil {
		return err
	}
	return nil
}

func (w Workspace) packNPM(ctx context.Context, destination, main, name, version, revision string) error {
	files, err := fileHashes(main, []string{main})
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
	if err = WriteJSON(filepath.Join(main, "build.json"), map[string]any{"version": version, "target": "darwin-arm64", "revision": revision, "goModSha256": modHash, "goSumSha256": sumHash, "files": files}); err != nil {
		return err
	}
	tarballs := filepath.Join(destination, "tarballs")
	if err = os.Mkdir(tarballs, 0755); err != nil {
		return err
	}
	if err = Run(ctx, w.Root, nil, "npm", "pack", "--quiet", main, "--pack-destination", tarballs); err != nil {
		return err
	}
	archive := strings.ReplaceAll(strings.TrimPrefix(name, "@"), "/", "-") + "-" + version + ".tgz"
	if err = verifyNPMPackage(filepath.Join(tarballs, archive), files); err != nil {
		return err
	}
	digest, err := Digest(filepath.Join(tarballs, archive))
	if err != nil {
		return err
	}
	if err = os.WriteFile(filepath.Join(tarballs, "SHA256SUMS"), []byte(fmt.Sprintf("%s  %s\n", digest, archive)), 0644); err != nil {
		return err
	}
	fmt.Printf("npm 产品已装配：%s\n", tarballs)
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
		return errors.New("npm 包超过 150 MiB 发行预算")
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
			return fmt.Errorf("npm 包字节不同：%s", relative)
		}
		seen[relative] = true
	}
	if len(seen) != len(files) {
		return errors.New("npm 包缺少实际发行文件")
	}
	return nil
}

func (w Workspace) bundleExtensions(main, directory string, build JVMBuild, version string) error {
	sdk := filepath.Join(main, "sdk")
	if err := os.MkdirAll(sdk, 0755); err != nil {
		return err
	}
	configuration, err := referenceConnectors(directory, build)
	if err != nil {
		return err
	}
	var manifests []string
	if err = ReadJSON(configuration, &manifests); err != nil {
		return err
	}
	installed := make([]string, 0, len(manifests))
	for _, manifest := range manifests {
		group := filepath.Dir(manifest)
		if err = CopyTree(group, filepath.Join(main, "connectors", filepath.Base(group)), func(path string) bool { return strings.HasSuffix(path, ".jar") }); err != nil {
			return err
		}
		installed = append(installed, filepath.ToSlash(filepath.Join("connectors", filepath.Base(group), "connector.json")))
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
	return WriteJSON(filepath.Join(main, "resources.json"), map[string]any{"version": version, "connectorApiVersion": build.ConnectorAPIVersion, "sdk": "sdk", "sdkRepository": "https://jitpack.io", "sdkArtifacts": coordinates, "connectors": installed})
}

func writeJVMDownloads(main, version string, build JVMBuild, available map[string]MavenArtifact) error {
	artifacts := map[string]map[string]any{}
	add := func(name, path, digest string) error {
		artifact, exists := available[name]
		if !exists || artifact.File.SHA256 != digest {
			return fmt.Errorf("安装 JAR 缺少匹配的远端构件：%s", path)
		}
		entry := artifacts[digest]
		if entry == nil {
			entry = map[string]any{"coordinate": artifact.Coordinate, "url": artifact.URL, "sha256": digest, "size": artifact.Size, "locations": []string{}}
			artifacts[digest] = entry
		}
		entry["locations"] = append(entry["locations"].([]string), path)
		return nil
	}
	for _, artifact := range build.RuntimeClasspath {
		name := filepath.Base(artifact.Path)
		if err := add(name, "java/lib/"+name, artifact.SHA256); err != nil {
			return err
		}
	}
	var resources struct {
		Connectors []string `json:"connectors"`
	}
	if err := ReadJSON(filepath.Join(main, "resources.json"), &resources); err != nil {
		return err
	}
	for _, path := range resources.Connectors {
		var manifest ConnectorManifest
		if err := ReadJSON(filepath.Join(main, path), &manifest); err != nil {
			return err
		}
		for _, jar := range manifest.Jars {
			if err := add(jar.File, filepath.ToSlash(filepath.Join(filepath.Dir(path), jar.File)), jar.SHA256); err != nil {
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
	return WriteJSON(filepath.Join(main, "jvm-dependencies.json"), map[string]any{"formatVersion": "agentic-olap-jvm-dependencies/v2", "version": version, "artifacts": ordered})
}
