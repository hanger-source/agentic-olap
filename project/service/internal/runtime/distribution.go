package runtime

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
)

type classpathIdentity struct {
	Path   string `json:"path"`
	SHA256 string `json:"sha256"`
}

type distributionPaths struct {
	Runtime       string
	Script        string
	DSL           []string
	DSLAPIVersion string
}

func distributionClassPaths(directory string) (distributionPaths, error) {
	var paths distributionPaths
	root, err := filepath.EvalSymlinks(directory)
	if err != nil {
		return paths, err
	}
	root, err = filepath.Abs(root)
	if err != nil {
		return paths, err
	}
	data, err := os.ReadFile(filepath.Join(root, "distribution.json"))
	if err != nil {
		return paths, err
	}
	var manifest struct {
		Version string `json:"formatVersion"`
		Runtime struct {
			Main      string              `json:"mainClass"`
			Classpath []classpathIdentity `json:"classpath"`
		} `json:"runtime"`
		Scripting struct {
			DSLAPIVersion string              `json:"dslApiVersion"`
			Runtime       []classpathIdentity `json:"runtime"`
			DSL           []classpathIdentity `json:"dsl"`
		} `json:"scripting"`
	}
	if err = json.Unmarshal(data, &manifest); err != nil {
		return paths, err
	}
	if manifest.Version != "agentic-olap-distribution/v3" || manifest.Runtime.Main != "com.olap.prototype.runtime.ApplicationMainKt" || len(manifest.Runtime.Classpath) == 0 || manifest.Scripting.DSLAPIVersion != "agentic-olap-kotlin-dsl/v1" || len(manifest.Scripting.Runtime) == 0 || len(manifest.Scripting.DSL) == 0 {
		return paths, errors.New("unsupported or incomplete JVM distribution")
	}
	verified := make(map[string]string)
	resolveGroup := func(group []classpathIdentity) ([]string, error) {
		resolved := make([]string, 0, len(group))
		seen := make(map[string]bool)
		for _, artifact := range group {
			if filepath.IsAbs(artifact.Path) || strings.Contains(artifact.Path, "\\") || filepath.ToSlash(filepath.Clean(artifact.Path)) != artifact.Path || !strings.HasPrefix(artifact.Path, "java/lib/") || !strings.HasSuffix(artifact.Path, ".jar") {
				return nil, errors.New("runtime artifact must name an exact relative java/lib JAR")
			}
			path, err := filepath.EvalSymlinks(filepath.Join(root, filepath.FromSlash(artifact.Path)))
			if err != nil {
				return nil, err
			}
			relative, err := filepath.Rel(root, path)
			if err != nil || relative == ".." || strings.HasPrefix(relative, ".."+string(filepath.Separator)) || seen[path] {
				return nil, errors.New("runtime artifact escapes distribution or is repeated")
			}
			seen[path] = true
			if hash, exists := verified[path]; exists {
				if hash != artifact.SHA256 {
					return nil, errors.New("runtime artifact has conflicting exact identities")
				}
				resolved = append(resolved, path)
				continue
			}
			file, err := os.Open(path)
			if err != nil {
				return nil, err
			}
			info, err := file.Stat()
			if err != nil {
				file.Close()
				return nil, err
			}
			if !info.Mode().IsRegular() {
				file.Close()
				return nil, errors.New("runtime artifact must be a regular JAR file")
			}
			digest := sha256.New()
			_, readErr := io.Copy(digest, file)
			closeErr := file.Close()
			if readErr != nil {
				return nil, readErr
			}
			if closeErr != nil {
				return nil, closeErr
			}
			if hex.EncodeToString(digest.Sum(nil)) != artifact.SHA256 {
				return nil, fmt.Errorf("runtime artifact hash mismatch: %s", artifact.Path)
			}
			verified[path] = artifact.SHA256
			resolved = append(resolved, path)
		}
		return resolved, nil
	}
	groups := [][]classpathIdentity{manifest.Runtime.Classpath, manifest.Scripting.Runtime, manifest.Scripting.DSL}
	resolved := make([][]string, 0, len(groups))
	for _, group := range groups {
		files, err := resolveGroup(group)
		if err != nil {
			return paths, err
		}
		resolved = append(resolved, files)
	}
	paths.Runtime = strings.Join(resolved[0], string(filepath.ListSeparator))
	paths.Script = strings.Join(resolved[1], string(filepath.ListSeparator))
	paths.DSL, paths.DSLAPIVersion = resolved[2], manifest.Scripting.DSLAPIVersion
	return paths, nil
}
