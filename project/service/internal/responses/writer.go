package responses

import (
	"encoding/hex"
	"encoding/json"
	"errors"
	"hash"
	"io"
	"log/slog"
	"os"
	"path/filepath"
)

// Writer captures the actual transport bytes once. It does not marshal,
// truncate or reinterpret the original operation's response.
type Writer struct {
	store      Store
	file       *os.File
	responseID string
	hash       hash.Hash
	bytes      int64
	failure    error
	closed     bool
	committed  bool
}

func (w *Writer) Write(content []byte) (int, error) {
	if w.closed || w.failure != nil {
		return 0, errors.New("saved response writer is not writable")
	}
	n, err := w.file.Write(content)
	if n > 0 {
		_, _ = w.hash.Write(content[:n])
		w.bytes += int64(n)
	}
	if err == nil && n != len(content) {
		err = io.ErrShortWrite
	}
	if err != nil {
		w.failure = err
	}
	return n, err
}

func (w *Writer) Commit(metadata Metadata) (Reference, error) {
	ref := Reference{ResponseID: w.responseID, SHA256: hex.EncodeToString(w.hash.Sum(nil))}
	if w.closed || w.failure != nil {
		return ref, errors.Join(errors.New("saved response capture did not complete"), w.failure)
	}
	metadata.Bytes = w.bytes
	metadata.Scopes = NormalizeScopes(metadata.Scopes)
	if err := metadata.validate(); err != nil {
		return ref, err
	}
	if err := w.file.Sync(); err != nil {
		return ref, err
	}
	if err := w.file.Close(); err != nil {
		w.closed = true
		return ref, err
	}
	w.closed = true
	encoded, err := json.Marshal(record{Format: Format, Reference: ref, Metadata: metadata})
	if err != nil {
		return ref, err
	}
	meta, err := os.CreateTemp(w.store.Directory, "."+w.responseID+"-meta-")
	if err != nil {
		return ref, err
	}
	defer os.Remove(meta.Name())
	defer meta.Close()
	if err := meta.Chmod(0600); err != nil {
		return ref, err
	}
	if _, err := meta.Write(encoded); err != nil {
		return ref, err
	}
	if err := meta.Sync(); err != nil {
		return ref, err
	}
	if err := meta.Close(); err != nil {
		return ref, err
	}
	// A link never overwrites an existing immutable body or metadata record.
	// A body without its metadata link is not exposed by the read operation.
	if err := os.Link(w.file.Name(), filepath.Join(w.store.Directory, w.responseID+".json")); err != nil {
		return ref, err
	}
	if err := os.Link(meta.Name(), filepath.Join(w.store.Directory, w.responseID+".meta.json")); err != nil {
		return ref, err
	}
	directory, err := os.Open(w.store.Directory)
	if err != nil {
		return ref, err
	}
	defer directory.Close()
	if err := directory.Sync(); err != nil {
		return ref, err
	}
	w.committed = true
	// Only the private staging name is removed; the complete archive remains.
	if err := os.Remove(w.file.Name()); err != nil {
		slog.Warn("saved response staging cleanup failed after commit", "responseId", ref.ResponseID, "error", err)
	}
	return ref, nil
}

// Abort only removes an unpublished staging file. It never removes an archive.
func (w *Writer) Abort() error {
	if w.committed {
		return nil
	}
	var closeErr error
	if !w.closed {
		closeErr = w.file.Close()
		w.closed = true
	}
	removeErr := os.Remove(w.file.Name())
	if errors.Is(removeErr, os.ErrNotExist) {
		removeErr = nil
	}
	return errors.Join(closeErr, removeErr)
}

func (w *Writer) Close() error { return w.Abort() }
