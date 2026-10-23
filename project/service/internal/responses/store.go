// Package responses delivers immutable, complete MCP response bytes without
// interpreting their business result or creating a semantic/query asset.
package responses

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"slices"
)

const Format = "mcp-jsonrpc-response/v1"

type Reference struct {
	ResponseID string `json:"responseId"`
	SHA256     string `json:"sha256"`
}

var responsePattern = regexp.MustCompile(`^response-[0-9a-f]{32}$`)
var hashPattern = regexp.MustCompile(`^[0-9a-f]{64}$`)

func (r Reference) Validate() error {
	if !responsePattern.MatchString(r.ResponseID) || !hashPattern.MatchString(r.SHA256) {
		return errors.New("an exact responseId and SHA-256 reference is required")
	}
	return nil
}

// Metadata binds the captured bytes to the authority under which they were
// produced. RequestID and OriginalSuccess describe the original operation.
type Metadata struct {
	Subject                 string   `json:"subject"`
	Scopes                  []string `json:"scopes"`
	AuthorizationPolicyHash string   `json:"authorizationPolicyHash"`
	Operation               string   `json:"operation"`
	RequestID               string   `json:"requestId"`
	OriginalSuccess         bool     `json:"originalSuccess"`
	Bytes                   int64    `json:"bytes"`
}

func NormalizeScopes(scopes []string) []string {
	result := append([]string{}, scopes...)
	slices.Sort(result)
	return slices.Compact(result)
}

func (m Metadata) validate() error {
	if m.Subject == "" || m.Operation == "" || !hashPattern.MatchString(m.AuthorizationPolicyHash) || m.Bytes < 0 {
		return errors.New("saved response requires its original subject, operation, authorization policy and byte count")
	}
	if !slices.Equal(m.Scopes, NormalizeScopes(m.Scopes)) {
		return errors.New("saved response scopes are not canonical")
	}
	return nil
}

type record struct {
	Format    string    `json:"format"`
	Reference Reference `json:"reference"`
	Metadata  Metadata  `json:"metadata"`
}

type Store struct {
	Directory string
}

func (s Store) Begin() (*Writer, error) {
	if !filepath.IsAbs(s.Directory) {
		return nil, errors.New("saved responses require an absolute private directory")
	}
	if err := os.MkdirAll(s.Directory, 0700); err != nil {
		return nil, err
	}
	identity := make([]byte, 16)
	if _, err := rand.Read(identity); err != nil {
		return nil, err
	}
	id := "response-" + hex.EncodeToString(identity)
	file, err := os.CreateTemp(s.Directory, "."+id+"-")
	if err != nil {
		return nil, err
	}
	if err := file.Chmod(0600); err != nil {
		file.Close()
		os.Remove(file.Name())
		return nil, err
	}
	return &Writer{store: s, file: file, responseID: id, hash: sha256.New()}, nil
}

func (s Store) Location(ref Reference) (string, error) {
	if err := ref.Validate(); err != nil {
		return "", err
	}
	return filepath.Join(s.Directory, ref.ResponseID+".json"), nil
}

// Metadata is read before content authorization. The metadata link is the
// commit marker, published only after the complete body has been synced.
func (s Store) Metadata(ref Reference) (Metadata, error) {
	if err := ref.Validate(); err != nil {
		return Metadata{}, err
	}
	file, err := openRegular(filepath.Join(s.Directory, ref.ResponseID+".meta.json"))
	if err != nil {
		return Metadata{}, err
	}
	defer file.Close()
	decoder := json.NewDecoder(file)
	decoder.DisallowUnknownFields()
	var stored record
	if err := decoder.Decode(&stored); err != nil {
		return Metadata{}, err
	}
	if err := decoder.Decode(new(any)); err != io.EOF {
		return Metadata{}, errors.New("saved response metadata has trailing content")
	}
	if stored.Format != Format || stored.Reference != ref {
		return Metadata{}, errors.New("saved response metadata differs from its exact reference or format")
	}
	if err := stored.Metadata.validate(); err != nil {
		return Metadata{}, err
	}
	return stored.Metadata, nil
}

func openRegular(path string) (*os.File, error) {
	before, err := os.Lstat(path)
	if err != nil {
		return nil, err
	}
	if !before.Mode().IsRegular() {
		return nil, errors.New("saved response must be a regular file")
	}
	file, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	opened, err := file.Stat()
	if err != nil || !os.SameFile(before, opened) {
		file.Close()
		if err != nil {
			return nil, err
		}
		return nil, errors.New("saved response changed while opening")
	}
	return file, nil
}
