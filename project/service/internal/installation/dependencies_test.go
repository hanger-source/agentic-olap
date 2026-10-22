package installation

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"sync"
	"sync/atomic"
	"testing"
)

func installationRoots(t *testing.T) string {
	t.Helper()
	root := t.TempDir()
	return root
}

func lockedDependency(address string, body []byte) jvmDependency {
	digest := sha256.Sum256(body)
	return jvmDependency{Coordinate: "test:library:1.0", URL: address, SHA256: hex.EncodeToString(digest[:]), Size: int64(len(body)), Locations: []string{"java/lib/library.jar", "connectors/backend/library.jar"}}
}

func TestJVMInstallDownloadsOnceAndReusesCacheWithoutNetwork(t *testing.T) {
	body := bytes.Repeat([]byte("a JAR from the selected repository\n"), 4096)
	var requests atomic.Int32
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { requests.Add(1); w.Write(body) }))
	defer server.Close()
	artifact := lockedDependency(server.URL+"/library.jar", body)
	cache := t.TempDir()
	roots := installationRoots(t)
	if err := installJVMDependencies(context.Background(), []jvmDependency{artifact}, roots, cache, server.Client(), io.Discard); err != nil {
		t.Fatal(err)
	}
	for _, location := range artifact.Locations {
		path, err := dependencyPath(roots, location)
		if err != nil {
			t.Fatal(err)
		}
		actual, err := os.ReadFile(path)
		if err != nil || !bytes.Equal(body, actual) {
			t.Fatalf("installed bytes differ: %v", err)
		}
	}
	if requests.Load() != 1 {
		t.Fatalf("shared JAR downloaded %d times", requests.Load())
	}
	server.Close()
	// 新安装目录没有任何 JAR；同一缓存可在来源完全不可达时填充两个目标。
	if err := installJVMDependencies(context.Background(), []jvmDependency{artifact}, installationRoots(t), cache, server.Client(), io.Discard); err != nil {
		t.Fatal(err)
	}
	if requests.Load() != 1 {
		t.Fatal("cache reuse accessed the repository")
	}
}

func TestJVMInstallRejectsWrongBytesWithoutPublishingAnyFinalFile(t *testing.T) {
	expected := []byte("exact bytes")
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.Write([]byte("wrong bytes")) }))
	defer server.Close()
	artifact := lockedDependency(server.URL+"/library.jar", expected)
	cache := t.TempDir()
	roots := installationRoots(t)
	if err := installJVMDependencies(context.Background(), []jvmDependency{artifact}, roots, cache, server.Client(), io.Discard); err == nil {
		t.Fatal("accepted a same-length response with the wrong digest")
	}
	if _, err := os.Stat(filepath.Join(cache, artifact.SHA256+".jar")); !os.IsNotExist(err) {
		t.Fatalf("bad response became authoritative: %v", err)
	}
	for _, location := range artifact.Locations {
		path, err := dependencyPath(roots, location)
		if err != nil {
			t.Fatal(err)
		}
		if _, err := os.Stat(path); !os.IsNotExist(err) {
			t.Fatalf("bad response was installed: %v", err)
		}
	}
	entries, err := os.ReadDir(cache)
	if err != nil || len(entries) != 0 {
		t.Fatalf("partial download left cache files: %v", err)
	}
}

func TestJVMConcurrentInstallsShareOnlyVerifiedBytes(t *testing.T) {
	body := bytes.Repeat([]byte("content-addressed dependency\n"), 2048)
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.Write(body) }))
	defer server.Close()
	artifact := lockedDependency(server.URL+"/library.jar", body)
	cache := t.TempDir()
	var workers sync.WaitGroup
	failures := make(chan error, 8)
	for range 8 {
		roots := installationRoots(t)
		workers.Add(1)
		go func() {
			defer workers.Done()
			failures <- installJVMDependencies(context.Background(), []jvmDependency{artifact}, roots, cache, server.Client(), io.Discard)
		}()
	}
	workers.Wait()
	close(failures)
	for err := range failures {
		if err != nil {
			t.Fatal(err)
		}
	}
	if err := verifyDependency(filepath.Join(cache, artifact.SHA256+".jar"), artifact); err != nil {
		t.Fatal(err)
	}
	entries, err := os.ReadDir(cache)
	if err != nil || len(entries) != 1 {
		t.Fatalf("concurrent downloads left incomplete files: %v", err)
	}
}
