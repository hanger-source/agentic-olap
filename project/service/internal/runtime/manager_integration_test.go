//go:build unix

package runtime

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"syscall"
	"testing"
	"time"
)

// These trials run the actual distribution; they never install a test JVM main
// or substitute the metadata/worker protocol with a fake process.
func actualRuntime(t *testing.T, inFlight int) *Manager {
	t.Helper()
	distribution := os.Getenv("AGENTIC_OLAP_DISTRIBUTION")
	javaHome := os.Getenv("JAVA_HOME")
	if distribution == "" || javaHome == "" {
		t.Fatal("AGENTIC_OLAP_DISTRIBUTION and JAVA_HOME must name the actual built distribution and JDK")
	}
	directory := t.TempDir()
	log, err := os.Create(filepath.Join(directory, "runtime.log"))
	if err != nil {
		t.Fatal(err)
	}
	grants := []any{map[string]any{
		"subjects": []string{"runtime-trial"}, "scopes": []string{"runtime-test"},
		"operations":  []string{"inspect-warehouse", "list-data-sources", "read-query-language-contract"},
		"dataSources": []string{}, "subjectAreas": []string{}, "releases": []string{}, "allGovernanceAssets": false,
	}}
	deployment, err := json.Marshal(map[string]any{
		"dataRoot": filepath.Join(directory, "data"), "connectors": []string{}, "dataSources": []any{},
		"auth": map[string]any{"grants": grants}, "history": map[string]any{"enabled": false, "retentionDays": 1},
		"limits": map[string]any{
			"requestSeconds": 30, "startupSeconds": 60, "maxInFlight": inFlight, "workerCount": 1,
			"catalogEntriesPerWorker": 2, "requestBytes": 8192, "inputDepth": 16, "inputNodes": 200000,
			"inputFileBytes": 33554432, "inputFiles": 64, "responseBytes": 33554432,
			"pendingOutputBytes": 67108864, "workerJvmHeapMiB": 512, "nativeMemoryBytes": 268435456,
			"catalogBytes": 33554432, "sqlBytes": 4194304, "parameters": 10000,
			"resultRows": 100000, "resultValues": 1000000, "resultBytes": 16777216, "resultDepth": 64,
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	m, err := New(Config{Java: filepath.Join(javaHome, "bin", "java"), Distribution: distribution,
		WorkDir: directory, Deployment: deployment, Stderr: log})
	if err != nil {
		log.Close()
		t.Fatal(err)
	}
	t.Cleanup(func() {
		pids := runtimePIDs(m)
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		if err := m.Close(ctx); err != nil {
			t.Errorf("runtime shutdown failed: %v", err)
		}
		for _, pid := range pids {
			if err := syscall.Kill(pid, 0); !errors.Is(err, syscall.ESRCH) {
				t.Errorf("owned JVM %d remains after Close: %v", pid, err)
			}
		}
		log.Close()
		if t.Failed() {
			bytes, _ := os.ReadFile(log.Name())
			t.Logf("actual runtime diagnostics:\n%s", bytes)
		}
	})
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	if err := m.Start(ctx); err != nil {
		t.Fatal(err)
	}
	return m
}

var trialPrincipal = Principal{Subject: "runtime-trial", Scopes: []string{"runtime-test"}}
var inspectionCapability = []byte(`{"operation":"read-inspection-capability","payload":{}}`)

func capabilityRequest(t *testing.T, m *Manager) Reply {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	reply, err := m.Call(ctx, trialPrincipal, "inspect-warehouse", inspectionCapability)
	if err != nil {
		t.Fatal(err)
	}
	if !reply.Success {
		t.Fatalf("real inspection worker failed: %s", reply.Error)
	}
	var body struct {
		Version    string   `json:"formatVersion"`
		Operations []string `json:"operations"`
	}
	if err := json.Unmarshal(reply.Result, &body); err != nil {
		t.Fatal(err)
	}
	if body.Version != "olap-inspection-capability/v2" || len(body.Operations) == 0 {
		t.Fatalf("unexpected actual worker result: %s", reply.Result)
	}
	return reply
}

func runtimePIDs(m *Manager) []int {
	pids := []int{}
	m.mu.Lock()
	if m.metadata != nil {
		pids = append(pids, m.metadata.cmd.Process.Pid)
	}
	m.mu.Unlock()
	for _, slot := range m.slots {
		slot.mu.Lock()
		if slot.process != nil {
			pids = append(pids, slot.process.cmd.Process.Pid)
		}
		slot.mu.Unlock()
	}
	return pids
}

func actualWorker(t *testing.T, m *Manager) *process {
	t.Helper()
	for _, slot := range m.slots {
		slot.mu.Lock()
		p := slot.process
		slot.mu.Unlock()
		if p != nil && p.alive() {
			return p
		}
	}
	t.Fatal("actual metadata callback did not start a worker")
	return nil
}

func waitForWorkerRequest(t *testing.T, p *process) {
	t.Helper()
	until := time.Now().Add(5 * time.Second)
	for time.Now().Before(until) {
		p.mu.Lock()
		active := len(p.pending)
		p.mu.Unlock()
		if active > 0 {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatal("request never reached actual worker transport")
}

func TestActualDuplexWorkerBudgetFailureAndSubsequentRequests(t *testing.T) {
	m := actualRuntime(t, 4)
	first := capabilityRequest(t, m)
	worker := actualWorker(t, m)
	if worker.cmd.Process.Pid == m.metadata.cmd.Process.Pid {
		t.Fatal("worker reused metadata process")
	}
	oversized := []byte(`{"text":"` + strings.Repeat("é", 5000) + `"}`)
	if _, err := m.Call(context.Background(), trialPrincipal, "inspect-warehouse", oversized); err == nil {
		t.Fatal("actual request byte budget was ignored")
	}
	deep := []byte(`{"nested":` + strings.Repeat("[", 20) + `0` + strings.Repeat("]", 20) + `}`)
	refused, err := m.Call(context.Background(), trialPrincipal, "inspect-warehouse", deep)
	if err != nil {
		t.Fatal(err)
	}
	if refused.Success {
		t.Fatal("actual JVM input depth budget was ignored")
	}
	// Concurrent results retain distinct parent IDs on the same duplex channel.
	type outcome struct {
		reply Reply
		err   error
	}
	results := make(chan outcome, 4)
	for range 4 {
		go func() {
			reply, err := m.Call(context.Background(), trialPrincipal, "inspect-warehouse", inspectionCapability)
			results <- outcome{reply, err}
		}()
	}
	ids := map[string]bool{first.RequestID: true}
	for range 4 {
		actual := <-results
		if actual.err != nil || !actual.reply.Success {
			t.Fatalf("subsequent duplex request failed: %v %s", actual.err, actual.reply.Error)
		}
		if ids[actual.reply.RequestID] {
			t.Fatalf("response reused request identity: %s", actual.reply.RequestID)
		}
		ids[actual.reply.RequestID] = true
	}
	capabilityRequest(t, m)
}

func TestActualWorkerCancellationReclaimsAdmissionAndProcess(t *testing.T) {
	m := actualRuntime(t, 1)
	capabilityRequest(t, m)
	worker := actualWorker(t, m)
	pid := worker.cmd.Process.Pid
	if err := syscall.Kill(pid, syscall.SIGSTOP); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = syscall.Kill(pid, syscall.SIGCONT) })
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	finished := make(chan error, 1)
	go func() {
		_, err := m.Call(ctx, trialPrincipal, "inspect-warehouse", inspectionCapability)
		finished <- err
	}()
	waitForWorkerRequest(t, worker)
	queued, stopQueued := context.WithTimeout(context.Background(), 100*time.Millisecond)
	_, err := m.Call(queued, trialPrincipal, "inspect-warehouse", inspectionCapability)
	stopQueued()
	if !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("bounded admission did not respect queued deadline: %v", err)
	}
	cancel()
	select {
	case err := <-finished:
		if !errors.Is(err, context.Canceled) {
			t.Fatalf("active cancellation lost: %v", err)
		}
	case <-time.After(6 * time.Second):
		t.Fatal("cancelled worker was not terminated and reclaimed")
	}
	if err := syscall.Kill(pid, 0); !errors.Is(err, syscall.ESRCH) {
		t.Fatalf("unresponsive cancelled worker %d was not reaped: %v", pid, err)
	}
	capabilityRequest(t, m)
	if actualWorker(t, m).cmd.Process.Pid == pid {
		t.Fatal("subsequent request reused terminated worker")
	}
}

func TestActualMetadataExitIsTerminalAndOwnedWorkersClose(t *testing.T) {
	m := actualRuntime(t, 1)
	capabilityRequest(t, m)
	metadataPID := m.metadata.cmd.Process.Pid
	if err := syscall.Kill(metadataPID, syscall.SIGKILL); err != nil {
		t.Fatal(err)
	}
	select {
	case <-m.metadata.done:
	case <-time.After(5 * time.Second):
		t.Fatal("metadata exit was not observed")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	if _, err := m.Call(ctx, trialPrincipal, "inspect-warehouse", inspectionCapability); err == nil {
		t.Fatal("dead metadata accepted work or was silently restarted")
	}
	if m.metadata.cmd.Process.Pid != metadataPID {
		t.Fatal("runtime replaced its terminal metadata process")
	}
}

func TestActualWorkerDeadlineTerminatesBlockedProcessAndReleasesCapacity(t *testing.T) {
	m := actualRuntime(t, 1)
	capabilityRequest(t, m)
	worker := actualWorker(t, m)
	pid := worker.cmd.Process.Pid
	if err := syscall.Kill(pid, syscall.SIGSTOP); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = syscall.Kill(pid, syscall.SIGCONT) })
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	finished := make(chan error, 1)
	go func() {
		_, err := m.Call(ctx, trialPrincipal, "inspect-warehouse", inspectionCapability)
		finished <- err
	}()
	waitForWorkerRequest(t, worker)
	select {
	case err := <-finished:
		if !errors.Is(err, context.DeadlineExceeded) {
			t.Fatalf("active worker deadline was lost: %v", err)
		}
	case <-time.After(8 * time.Second):
		t.Fatal("expired worker request did not release capacity")
	}
	if err := syscall.Kill(pid, 0); !errors.Is(err, syscall.ESRCH) {
		t.Fatalf("expired worker %d was not reaped: %v", pid, err)
	}
	capabilityRequest(t, m)
	if actualWorker(t, m).cmd.Process.Pid == pid {
		t.Fatal("request reused expired worker process")
	}
}
