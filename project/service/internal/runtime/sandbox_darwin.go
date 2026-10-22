//go:build darwin

package runtime

import (
	"encoding/json"
	"io"
	"os/exec"
	"strings"
)

// 路径与Seatbelt策略装配参考SRT macos-sandbox-utils.ts，固定审阅来源：
// https://github.com/anthropics/sandbox-runtime/blob/3a2954dc8ab938877fe4e279f04dfe4dd18ab40d/src/sandbox/macos-sandbox-utils.ts
// 这里只采用产品所需的路径转义和OS隔离装配，不引入其Node运行栈或通用权限默认值。
func (s *scriptSandbox) command(java string, arguments []string, stderr io.Writer) (*exec.Cmd, error) {
	executable, jre, directory, files, err := s.paths(java)
	if err != nil {
		return nil, err
	}
	// Seatbelt字符串使用JSON转义；路径只用literal/subpath，不把文件名解释成glob。
	quote := func(path string) string { value, _ := json.Marshal(path); return string(value) }
	var profile strings.Builder
	profile.WriteString(`(version 1)
(deny default)
(allow process-exec (literal ` + quote(executable) + `))
(allow process-info* (target self))
(allow mach-priv-task-port (target self))
(allow signal (target self))
(allow sysctl-read)
(allow file-read-metadata (vnode-type DIRECTORY))
(allow file-read* (literal "/") (subpath "/System") (subpath "/usr/lib")
  (literal "/dev/null") (literal "/dev/random") (literal "/dev/urandom")
  (subpath ` + quote(jre) + `) (subpath ` + quote(directory) + `))
(allow file-write* (literal "/dev/null") (subpath ` + quote(directory) + `))
(allow file-ioctl (literal "/dev/null"))
`)
	for _, file := range files {
		profile.WriteString("(allow file-read* (literal " + quote(file) + "))\n")
	}
	options := []string{"-p", profile.String(), executable, "-XX:-UsePerfData", "-Duser.home=" + directory, "-Djava.io.tmpdir=" + directory}
	options = append(options, arguments...)
	command := exec.Command("/usr/bin/sandbox-exec", options...)
	command.Dir, command.Stderr = directory, stderr
	// 固定基础环境；不能继承服务的令牌、凭据、代理或用户 JVM 注入选项。
	command.Env = []string{"JAVA_HOME=" + jre, "HOME=" + directory, "TMPDIR=" + directory, "LANG=en_US.UTF-8", "TZ=UTC"}
	return command, nil
}
