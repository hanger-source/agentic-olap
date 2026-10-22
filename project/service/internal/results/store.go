// Package results stores immutable producer-validated Protobuf artifacts.
// It projects saved values and never repeats semantic planning or source execution.
package results

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"io"
	"os"
	"path/filepath"
	"regexp"

	q "github.com/hanger-source/agentic-olap/service/internal/protocol/query"
	"google.golang.org/protobuf/proto"
)

const ArtifactFormat = "olap-query-result-artifact/v4"
const RecordFormat = "olap-query-result-record/v4"

type Reference struct {
	RunID  string `json:"runId"`
	SHA256 string `json:"sha256"`
}

var runPattern = regexp.MustCompile(`^run-[0-9a-f]{32}$`)
var hashPattern = regexp.MustCompile(`^[0-9a-f]{64}$`)

func (r Reference) Validate() error {
	if !runPattern.MatchString(r.RunID) || !hashPattern.MatchString(r.SHA256) {
		return errors.New("invalid exact result reference")
	}
	return nil
}
func (r Reference) Proto() *q.QueryResultReference {
	return &q.QueryResultReference{RunId: r.RunID, Sha256: r.SHA256}
}
func Hash(raw []byte) string { sum := sha256.Sum256(raw); return hex.EncodeToString(sum[:]) }

type UnsupportedFormatError struct{ Observed string }

func (e *UnsupportedFormatError) Error() string {
	return "saved result format is not supported by this service"
}

type Availability struct {
	Reference Reference `json:"reference"`
	Status    string    `json:"status"`
	Issues    []string  `json:"issues"`
}
type Store struct {
	Directory                          string
	MaxFileBytes, ResponseBytes, Depth int
}

func (s Store) artifact(raw []byte, ref Reference) (*q.SavedQueryResultRecord, error) {
	if err := ref.Validate(); err != nil {
		return nil, err
	}
	if len(raw) > s.MaxFileBytes || s.Depth < 1 {
		return nil, errors.New("result artifact exceeds deployed budget")
	}
	decode := proto.UnmarshalOptions{RecursionLimit: s.Depth}
	artifact := new(q.SavedQueryResultArtifact)
	if err := decode.Unmarshal(raw, artifact); err != nil {
		return nil, err
	}
	if artifact.FormatVersion != ArtifactFormat {
		return nil, &UnsupportedFormatError{artifact.FormatVersion}
	}
	if artifact.Sha256 != ref.SHA256 || Hash(artifact.RecordBytes) != ref.SHA256 {
		return nil, errors.New("exact result bytes differ from reference")
	}
	record := new(q.SavedQueryResultRecord)
	if err := decode.Unmarshal(artifact.RecordBytes, record); err != nil {
		return nil, err
	}
	if record.FormatVersion != RecordFormat {
		return nil, &UnsupportedFormatError{record.FormatVersion}
	}
	if record.RunId != ref.RunID || record.Receipt == nil || record.Receipt.RunId != ref.RunID || record.Intent == nil || record.Publication == nil || !proto.Equal(record.Intent.Publication, record.Publication) {
		return nil, errors.New("saved result differs from its exact execution identity")
	}
	return record, nil
}
func (s Store) Validate(ref Reference, raw []byte) error { _, err := s.artifact(raw, ref); return err }
func (s Store) Record(ref Reference, raw []byte) (*q.SavedQueryResultRecord, error) {
	return s.artifact(raw, ref)
}
func (s Store) Save(ctx context.Context, ref Reference, raw []byte) (int64, error) {
	if err := s.Validate(ref, raw); err != nil {
		return 0, err
	}
	if err := ctx.Err(); err != nil {
		return 0, err
	}
	if err := os.MkdirAll(s.Directory, 0700); err != nil {
		return 0, err
	}
	file, err := os.CreateTemp(s.Directory, "."+ref.RunID+"-")
	if err != nil {
		return 0, err
	}
	defer os.Remove(file.Name())
	defer file.Close()
	if err = file.Chmod(0600); err != nil {
		return 0, err
	}
	if _, err = file.Write(raw); err != nil {
		return 0, err
	}
	if err = file.Sync(); err != nil {
		return 0, err
	}
	if err = file.Close(); err != nil {
		return 0, err
	}
	if err = ctx.Err(); err != nil {
		return 0, err
	}
	if err = os.Link(file.Name(), filepath.Join(s.Directory, ref.RunID+".pb")); err != nil {
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
func (s Store) load(ctx context.Context, ref Reference) (*q.SavedQueryResultRecord, error) {
	if err := ref.Validate(); err != nil {
		return nil, err
	}
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	path := filepath.Join(s.Directory, ref.RunID+".pb")
	info, err := os.Lstat(path)
	if err != nil {
		return nil, err
	}
	if !info.Mode().IsRegular() {
		return nil, errors.New("saved result must be a regular file")
	}
	file, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer file.Close()
	opened, err := file.Stat()
	if err != nil {
		return nil, err
	}
	if !os.SameFile(info, opened) {
		return nil, errors.New("saved result changed while opening")
	}
	raw, err := io.ReadAll(io.LimitReader(file, int64(s.MaxFileBytes)+1))
	if err != nil {
		return nil, err
	}
	if err = ctx.Err(); err != nil {
		return nil, err
	}
	return s.artifact(raw, ref)
}
func (s Store) Inspect(ref Reference) Availability {
	status := Availability{Reference: ref, Status: "READABLE", Issues: []string{}}
	_, err := s.load(context.Background(), ref)
	if err == nil {
		return status
	}
	status.Status = "UNAVAILABLE"
	code := "RESULT_UNREADABLE"
	var unsupported *UnsupportedFormatError
	if errors.As(err, &unsupported) {
		status.Status = "UNSUPPORTED_FORMAT"
		code = "RESULT_FORMAT_UNSUPPORTED"
	} else if errors.Is(err, os.ErrNotExist) {
		code = "RESULT_NOT_FOUND"
	}
	status.Issues = append(status.Issues, code)
	return status
}
func (s Store) budget(message proto.Message) error {
	if s.ResponseBytes < 1 || proto.Size(message) > s.ResponseBytes {
		return errors.New("selected saved result exceeds the response budget")
	}
	return nil
}
