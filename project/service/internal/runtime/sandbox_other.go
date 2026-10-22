//go:build !darwin

package runtime

import (
	"errors"
	"io"
	"os/exec"
)

func (s *scriptSandbox) command(string, []string, io.Writer) (*exec.Cmd, error) {
	return nil, errors.New("SCRIPT_SANDBOX_UNAVAILABLE: this platform has no configured script isolation implementation")
}
