package products

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strings"
)

// Workspace 是研发命令明确选定的源码根，不参与产品运行配置。
type Workspace struct{ Root string }

func Open(root string) (Workspace, error) {
	root, err := filepath.Abs(root)
	if err != nil {
		return Workspace{}, err
	}
	for _, path := range []string{"project/settings.gradle.kts", "project/service/go.mod", "tools/go.mod"} {
		if info, err := os.Stat(filepath.Join(root, filepath.FromSlash(path))); err != nil || !info.Mode().IsRegular() {
			return Workspace{}, fmt.Errorf("不是完整源码根：%s", root)
		}
	}
	return Workspace{Root: root}, nil
}
func Locate() (Workspace, error) {
	directory, err := os.Getwd()
	if err != nil {
		return Workspace{}, err
	}
	for {
		if workspace, err := Open(directory); err == nil {
			return workspace, nil
		}
		parent := filepath.Dir(directory)
		if parent == directory {
			return Workspace{}, errors.New("请在源码仓库中运行研发命令，或明确提供 --root")
		}
		directory = parent
	}
}
func (w Workspace) Path(parts ...string) string {
	return filepath.Join(append([]string{w.Root}, parts...)...)
}
func Run(ctx context.Context, directory string, environment []string, command ...string) error {
	if len(command) == 0 {
		return errors.New("命令不能为空")
	}
	process := exec.CommandContext(ctx, command[0], command[1:]...)
	process.Dir = directory
	process.Env = append(os.Environ(), environment...)
	process.Stdin = os.Stdin
	process.Stdout = os.Stdout
	process.Stderr = os.Stderr
	return process.Run()
}
func output(ctx context.Context, directory string, command ...string) ([]byte, error) {
	process := exec.CommandContext(ctx, command[0], command[1:]...)
	process.Dir = directory
	process.Stderr = os.Stderr
	value, err := process.Output()
	if err != nil {
		return nil, fmt.Errorf("%s: %w", command[0], err)
	}
	return value, nil
}
func (w Workspace) Gradle(ctx context.Context, arguments ...string) error {
	return Run(ctx, w.Path("project"), nil, append([]string{w.Path("project", "gradlew"), "--no-daemon", "--console=plain"}, arguments...)...)
}
func Java(tool string) (string, error) {
	home := os.Getenv("JAVA_HOME")
	if home == "" || !filepath.IsAbs(home) {
		return "", errors.New("JAVA_HOME 必须明确指向完整 JDK 21")
	}
	path := filepath.Join(home, "bin", tool)
	info, err := os.Stat(path)
	if err != nil {
		return "", err
	}
	if !info.Mode().IsRegular() {
		return "", fmt.Errorf("JDK 工具不是普通文件：%s", path)
	}
	return path, nil
}
func Digest(path string) (string, error) {
	input, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer input.Close()
	hash := sha256.New()
	if _, err = io.Copy(hash, input); err != nil {
		return "", err
	}
	return hex.EncodeToString(hash.Sum(nil)), nil
}
func ReadJSON(path string, value any) error {
	file, err := os.Open(path)
	if err != nil {
		return err
	}
	defer file.Close()
	decoder := json.NewDecoder(file)
	decoder.UseNumber()
	decoder.DisallowUnknownFields()
	if err = decoder.Decode(value); err != nil {
		return fmt.Errorf("%s: %w", path, err)
	}
	var extra any
	if err = decoder.Decode(&extra); err != io.EOF {
		return fmt.Errorf("%s 必须是单个 JSON 文档", path)
	}
	return nil
}
func WriteJSON(path string, value any) error {
	data, err := json.MarshalIndent(value, "", "  ")
	if err != nil {
		return err
	}
	if err = os.MkdirAll(filepath.Dir(path), 0755); err != nil {
		return err
	}
	return os.WriteFile(path, append(data, '\n'), 0644)
}
func newDirectory(path string) error {
	if _, err := os.Lstat(path); err == nil {
		return fmt.Errorf("输出目录必须不存在：%s", path)
	} else if !errors.Is(err, os.ErrNotExist) {
		return err
	}
	return os.MkdirAll(path, 0755)
}
func CopyFile(source, destination string) (failure error) {
	input, err := os.Open(source)
	if err != nil {
		return err
	}
	defer input.Close()
	info, err := input.Stat()
	if err != nil {
		return err
	}
	if !info.Mode().IsRegular() {
		return fmt.Errorf("不能复制非普通文件：%s", source)
	}
	if err = os.MkdirAll(filepath.Dir(destination), 0755); err != nil {
		return err
	}
	output, err := os.OpenFile(destination, os.O_CREATE|os.O_EXCL|os.O_WRONLY, info.Mode().Perm())
	if err != nil {
		return err
	}
	defer func() { failure = errors.Join(failure, output.Close()) }()
	_, failure = io.Copy(output, input)
	return failure
}
func CopyTree(source, destination string, exclude func(string) bool) error {
	return filepath.WalkDir(source, func(path string, entry fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		relative, err := filepath.Rel(source, path)
		if err != nil {
			return err
		}
		if relative != "." && exclude != nil && exclude(filepath.ToSlash(relative)) {
			if entry.IsDir() {
				return filepath.SkipDir
			}
			return nil
		}
		target := filepath.Join(destination, relative)
		if entry.IsDir() {
			return os.MkdirAll(target, 0755)
		}
		if !entry.Type().IsRegular() {
			return fmt.Errorf("发行输入不能包含符号链接或特殊文件：%s", path)
		}
		return CopyFile(path, target)
	})
}
func fileHashes(root string, paths []string) (map[string]string, error) {
	files := map[string]bool{}
	for _, path := range paths {
		err := filepath.WalkDir(path, func(current string, entry fs.DirEntry, err error) error {
			if errors.Is(err, os.ErrNotExist) {
				return nil
			}
			if err != nil {
				return err
			}
			if entry.IsDir() {
				if entry.Name() == "build" || entry.Name() == ".gradle" || entry.Name() == ".git" || entry.Name() == "node_modules" {
					return filepath.SkipDir
				}
				return nil
			}
			if entry.Name() == ".DS_Store" {
				return nil
			}
			if !entry.Type().IsRegular() {
				return fmt.Errorf("源码输入不是普通文件：%s", current)
			}
			files[current] = true
			return nil
		})
		if err != nil {
			return nil, err
		}
	}
	result := map[string]string{}
	for path := range files {
		relative, err := filepath.Rel(root, path)
		if err != nil {
			return nil, err
		}
		digest, err := Digest(path)
		if err != nil {
			return nil, err
		}
		result[filepath.ToSlash(relative)] = digest
	}
	return result, nil
}
func sameHashes(a, b map[string]string) bool {
	if len(a) != len(b) {
		return false
	}
	for path, digest := range a {
		if b[path] != digest {
			return false
		}
	}
	return true
}
func sortedKeys[V any](values map[string]V) []string {
	keys := make([]string, 0, len(values))
	for key := range values {
		keys = append(keys, key)
	}
	sort.Strings(keys)
	return keys
}
func cleanRelative(root, path string) (string, error) {
	if filepath.IsAbs(path) {
		return "", fmt.Errorf("发行路径必须为相对路径：%s", path)
	}
	clean := filepath.Clean(filepath.FromSlash(path))
	if clean == "." || clean == ".." || strings.HasPrefix(clean, ".."+string(filepath.Separator)) {
		return "", fmt.Errorf("发行路径越界：%s", path)
	}
	return filepath.Join(root, clean), nil
}
