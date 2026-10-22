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

func distributionClassPath(directory string) (string, error) {
	root, err := filepath.EvalSymlinks(directory)
	if err != nil {
		return "", err
	}
	root, err = filepath.Abs(root)
	if err != nil {
		return "", err
	}
	data, err := os.ReadFile(filepath.Join(root, "distribution.json"))
	if err != nil {
		return "", err
	}
	var manifest struct {
		Version string `json:"formatVersion"`
		Runtime struct {
			Main      string `json:"mainClass"`
			Classpath []struct {
				Path   string `json:"path"`
				SHA256 string `json:"sha256"`
			} `json:"classpath"`
		} `json:"runtime"`
	}
	if err = json.Unmarshal(data, &manifest); err != nil {
		return "", err
	}
	if manifest.Version != "agentic-olap-distribution/v2" || manifest.Runtime.Main != "com.olap.prototype.runtime.ApplicationMainKt" || len(manifest.Runtime.Classpath) == 0 {
		return "", errors.New("unsupported or incomplete JVM distribution")
	}
	paths := make([]string, 0, len(manifest.Runtime.Classpath))
	seen := make(map[string]bool)
	for _, artifact := range manifest.Runtime.Classpath {
		if filepath.IsAbs(artifact.Path) || strings.Contains(artifact.Path, "\\") || filepath.ToSlash(filepath.Clean(artifact.Path)) != artifact.Path || !strings.HasPrefix(artifact.Path, "java/lib/") || !strings.HasSuffix(artifact.Path, ".jar") {
			return "", errors.New("runtime artifact must name an exact relative java/lib JAR")
		}
		path, err := filepath.EvalSymlinks(filepath.Join(root, filepath.FromSlash(artifact.Path)))
		if err != nil {
			return "", err
		}
		relative, err := filepath.Rel(root, path)
		if err != nil || relative == ".." || strings.HasPrefix(relative, ".."+string(filepath.Separator)) || seen[path] {
			return "", errors.New("runtime artifact escapes distribution or is repeated")
		}
		seen[path] = true
		file, err := os.Open(path)
		if err != nil {
			return "", err
		}
		info, err := file.Stat()
		if err != nil {
			file.Close()
			return "", err
		}
		if !info.Mode().IsRegular() {
			file.Close()
			return "", errors.New("runtime artifact must be a regular JAR file")
		}
		digest := sha256.New()
		_, readErr := io.Copy(digest, file)
		closeErr := file.Close()
		if readErr != nil {
			return "", readErr
		}
		if closeErr != nil {
			return "", closeErr
		}
		if hex.EncodeToString(digest.Sum(nil)) != artifact.SHA256 {
			return "", fmt.Errorf("runtime artifact hash mismatch: %s", artifact.Path)
		}
		paths = append(paths, path)
	}
	return strings.Join(paths, string(filepath.ListSeparator)), nil
}
