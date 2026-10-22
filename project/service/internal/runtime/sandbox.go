package runtime

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"unicode/utf8"
)

// 脚本只能读取精确 JVM 依赖，并在同隔离归属的worker私有根内写入。
// 每次执行有独立临时子目录；环境中不携带来源凭据。
type scriptSandbox struct {
	Directory string
	ReadFiles []string
}

func (s *scriptSandbox) paths(java string) (string, string, string, []string, error) {
	resolve := func(path string) (string, error) {
		if !filepath.IsAbs(path) || strings.IndexByte(path, 0) >= 0 || !utf8.ValidString(path) {
			return "", errors.New("script sandbox requires absolute paths")
		}
		return filepath.EvalSymlinks(path)
	}
	executable, err := resolve(java)
	if err != nil {
		return "", "", "", nil, err
	}
	directory, err := resolve(s.Directory)
	if err != nil {
		return "", "", "", nil, err
	}
	info, err := os.Stat(directory)
	if err != nil || !info.IsDir() || info.Mode().Perm()&0077 != 0 {
		return "", "", "", nil, errors.New("script sandbox requires its private run directory")
	}
	if filepath.Base(executable) != "java" || filepath.Base(filepath.Dir(executable)) != "bin" {
		return "", "", "", nil, errors.New("script sandbox requires an exact JRE bin/java executable")
	}
	files := make([]string, 0, len(s.ReadFiles))
	for _, path := range s.ReadFiles {
		file, err := resolve(path)
		if err != nil {
			return "", "", "", nil, err
		}
		info, err := os.Stat(file)
		if err != nil || !info.Mode().IsRegular() {
			return "", "", "", nil, fmt.Errorf("script sandbox dependency is not a regular file: %s", file)
		}
		files = append(files, file)
	}
	return executable, filepath.Dir(filepath.Dir(executable)), directory, files, nil
}
