// Package results stores and projects already validated immutable domain artifacts.
// It does not interpret units, source guarantees or query semantics.
package results

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"os"
	"path/filepath"
	"regexp"

	"github.com/hanger-source/agentic-olap/service/internal/wire"
)

type Reference struct {
	RunID  string `json:"runId"`
	SHA256 string `json:"sha256"`
}

const ArtifactFormat = "olap-query-result/v3"

type UnsupportedFormatError struct {
	Observed string
}

func (e *UnsupportedFormatError) Error() string {
	return "saved result format is not supported by this service"
}

type Availability struct {
	Reference Reference `json:"reference"`
	Status    string    `json:"status"`
	Issues    []string  `json:"issues"`
}

var runPattern = regexp.MustCompile(`^run-[a-zA-Z0-9_-]+$`)
var hashPattern = regexp.MustCompile(`^[0-9a-f]{64}$`)

func (r Reference) Validate() error {
	if !runPattern.MatchString(r.RunID) || !hashPattern.MatchString(r.SHA256) {
		return errors.New("invalid exact result reference")
	}
	return nil
}

type Store struct {
	Directory    string
	MaxFileBytes int
	Depth, Nodes int
}

func Hash(raw []byte) string { sum := sha256.Sum256(raw); return hex.EncodeToString(sum[:]) }
func (s Store) artifact(raw []byte, ref Reference) (map[string]any, error) {
	if err := ref.Validate(); err != nil {
		return nil, err
	}
	if len(raw) > s.MaxFileBytes {
		return nil, errors.New("result artifact exceeds deployed file budget")
	}
	envelope, err := wire.Object(raw, s.Depth, s.Nodes)
	if err != nil {
		return nil, err
	}
	body, ok := envelope["resultJson"].(string)
	if envelope["formatVersion"] != ArtifactFormat {
		observed, _ := envelope["formatVersion"].(string)
		return nil, &UnsupportedFormatError{Observed: observed}
	}
	if !ok || len(envelope) != 3 || envelope["sha256"] != ref.SHA256 || Hash([]byte(body)) != ref.SHA256 {
		return nil, errors.New("exact result bytes differ from reference")
	}
	content, err := wire.Object([]byte(body), s.Depth, s.Nodes)
	if err != nil {
		return nil, err
	}
	if content["runId"] != ref.RunID {
		return nil, errors.New("result run identity differs from reference")
	}
	return content, nil
}
func (s Store) Save(ctx context.Context, ref Reference, raw []byte) (int64, error) {
	if _, err := s.artifact(raw, ref); err != nil {
		return 0, err
	}
	if err := ctx.Err(); err != nil {
		return 0, err
	}
	if err := os.MkdirAll(s.Directory, 0700); err != nil {
		return 0, err
	}
	temporary, err := os.CreateTemp(s.Directory, "."+ref.RunID+"-")
	if err != nil {
		return 0, err
	}
	path := temporary.Name()
	defer os.Remove(path)
	defer temporary.Close()
	if err = temporary.Chmod(0600); err != nil {
		return 0, err
	}
	if _, err = temporary.Write(raw); err != nil {
		return 0, err
	}
	if err = temporary.Sync(); err != nil {
		return 0, err
	}
	if err = temporary.Close(); err != nil {
		return 0, err
	}
	if err = ctx.Err(); err != nil {
		return 0, err
	}
	if err = os.Link(path, filepath.Join(s.Directory, ref.RunID+".json")); err != nil {
		return 0, err
	}
	directory, err := os.Open(s.Directory)
	if err != nil {
		return 0, err
	}
	defer directory.Close()
	if err = directory.Sync(); err != nil {
		return 0, err
	}
	return int64(len(raw)), nil
}
func (s Store) Read(ref Reference) (map[string]any, error) {
	if err := ref.Validate(); err != nil {
		return nil, err
	}
	root, err := os.OpenRoot(s.Directory)
	if err != nil {
		return nil, err
	}
	defer root.Close()
	f, err := root.Open(ref.RunID + ".json")
	if err != nil {
		return nil, err
	}
	defer f.Close()
	st, err := f.Stat()
	if err != nil || !st.Mode().IsRegular() {
		return nil, errors.New("result must be a regular file")
	}
	raw, err := io.ReadAll(io.LimitReader(f, int64(s.MaxFileBytes)+1))
	if err != nil {
		return nil, err
	}
	return s.artifact(raw, ref)
}
func (s Store) Validate(ref Reference, raw []byte) error { _, err := s.artifact(raw, ref); return err }

// Inspect 只读取已保存字节；不装载执行目录、不连接来源，也不尝试改写旧格式。
func (s Store) Inspect(ref Reference) Availability {
	check := Availability{Reference: ref, Status: "AVAILABLE", Issues: []string{}}
	_, err := s.Read(ref)
	if err == nil {
		return check
	}
	var unsupported *UnsupportedFormatError
	switch {
	case errors.Is(err, os.ErrNotExist):
		check.Status, check.Issues = "MISSING", []string{"RESULT_BYTES_MISSING"}
	case errors.As(err, &unsupported):
		check.Status, check.Issues = "UNSUPPORTED", []string{"RESULT_FORMAT_UNSUPPORTED"}
	default:
		check.Status, check.Issues = "INVALID", []string{"RESULT_BYTES_OR_REFERENCE_INVALID"}
	}
	return check
}
func ReferenceFrom(value any) (Reference, error) {
	fields, ok := value.(map[string]any)
	if !ok || len(fields) != 2 {
		return Reference{}, errors.New("exact runId and sha256 result reference required")
	}
	raw, err := json.Marshal(value)
	if err != nil {
		return Reference{}, err
	}
	var r Reference
	if err = json.Unmarshal(raw, &r); err != nil {
		return r, err
	}
	return r, r.Validate()
}
