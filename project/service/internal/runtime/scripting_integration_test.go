//go:build darwin

package runtime

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync/atomic"
	"syscall"
	"testing"
	"time"

	pb "github.com/hanger-source/agentic-olap/service/internal/protocol"
	query "github.com/hanger-source/agentic-olap/service/internal/protocol/query"
	"google.golang.org/protobuf/encoding/protojson"
	"google.golang.org/protobuf/types/known/anypb"
)

func actualScript(t *testing.T, m *Manager, principal Principal, source string, arguments any) *ScriptReport {
	t.Helper()
	parameters, err := json.Marshal(arguments)
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
	defer cancel()
	report, err := m.Script(ctx, principal, ScriptSubmission{Source: source, SourceName: "trial.olap.kts", DSLAPIVersion: m.classpaths.DSLAPIVersion, Parameters: parameters},
		func(ctx context.Context, invocation *pb.CapabilityInvocation) (Reply, error) {
			return m.CallCapability(ctx, principal, invocation)
		})
	if err != nil {
		t.Fatalf("actual script failed outside the program: %v; report=%+v", err, report)
	}
	return report
}

func actualScriptValue(t *testing.T, report *ScriptReport) *query.ScriptValue {
	t.Helper()
	if !report.Success {
		t.Fatalf("actual program failed: %+v", report.Diagnostics)
	}
	var envelope anypb.Any
	if err := protojson.Unmarshal(report.Value, &envelope); err != nil {
		t.Fatal(err)
	}
	var value query.ScriptValue
	if err := envelope.UnmarshalTo(&value); err != nil {
		t.Fatal(err)
	}
	return &value
}

func actualScriptProcess(t *testing.T, m *Manager, id string) (int, string) {
	t.Helper()
	m.scripts.mu.Lock()
	defer m.scripts.mu.Unlock()
	for _, slot := range m.scripts.slots {
		if slot.worker != nil && slot.worker.id == id {
			return slot.worker.process.cmd.Process.Pid, slot.worker.directory
		}
	}
	t.Fatalf("actual script worker %q is not in the bounded pool", id)
	return 0, ""
}

func requireProcessExited(t *testing.T, pid int) {
	t.Helper()
	if err := syscall.Kill(pid, 0); !errors.Is(err, syscall.ESRCH) {
		t.Fatalf("script JVM %d remains after retirement: %v", pid, err)
	}
}

func waitForScriptFile(t *testing.T, directory, name string) {
	t.Helper()
	until := time.Now().Add(20 * time.Second)
	for time.Now().Before(until) {
		entries, _ := os.ReadDir(directory)
		for _, entry := range entries {
			if entry.IsDir() {
				if _, err := os.Stat(filepath.Join(directory, entry.Name(), name)); err == nil {
					return
				}
			}
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatalf("actual script never reached %s", name)
}

func TestActualScriptReuseKeepsRequestsAndPrincipalOwnershipSeparate(t *testing.T) {
	m := actualRuntime(t, 4)
	first := actualScript(t, m, trialPrincipal, `
val requestLocal = parameters.text("value")
java.nio.file.Files.writeString(olap.temporaryDirectory.resolve("owned"), requestLocal)
ScriptValue.ObjectValue(mapOf("subject" to ScriptValue.Text(olap.caller.subject), "directory" to ScriptValue.Text(olap.temporaryDirectory.toString()), "value" to ScriptValue.Text(requestLocal)))`, map[string]any{"value": "first"})
	value := actualScriptValue(t, first).GetObject().GetValues()
	if value["subject"].GetText() != trialPrincipal.Subject || value["value"].GetText() != "first" {
		t.Fatalf("script did not receive this request's caller and arguments: %v", value)
	}
	if _, err := os.Stat(value["directory"].GetText()); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("request temporary directory remains after delivery: %v", err)
	}
	pid, _ := actualScriptProcess(t, m, first.WorkerID)
	second := actualScript(t, m, trialPrincipal, `ScriptValue.Text(parameters.text("value"))`, map[string]any{"value": "second"})
	if actualScriptValue(t, second).GetText() != "second" || second.WorkerID != first.WorkerID {
		t.Fatal("same principal did not reuse the worker with fresh arguments")
	}
	actualPID, _ := actualScriptProcess(t, m, second.WorkerID)
	if actualPID != pid {
		t.Fatal("reported reuse replaced the actual JVM")
	}
	compileFailure := actualScript(t, m, trialPrincipal, `ScriptValue.Text(requestLocal)`, map[string]any{})
	if compileFailure.Success || compileFailure.WorkerID != first.WorkerID {
		t.Fatal("previous variables leaked, or an ordinary compiler rejection retired the worker")
	}
	businessFailure := actualScript(t, m, trialPrincipal, `
olap.sources.inspect(SourceInspection.Describe(SourceBindingRef("forbidden", "0".repeat(64)), com.olap.prototype.warehouse.inspection.WarehouseTableRef(null, "public", "forbidden")))
ScriptValue.Null`, map[string]any{})
	if businessFailure.Success || businessFailure.WorkerID != first.WorkerID || len(businessFailure.Calls) != 1 || businessFailure.Calls[0].Success {
		t.Fatal("formal business refusal was lost or caused unconditional worker retirement")
	}
	outputMarker := fmt.Sprintf("script-output-order-%d", time.Now().UnixNano())
	multiple := actualScript(t, m, trialPrincipal, `
repeat(3) {
    println("${parameters.text("marker")}:$it:stdout")
    System.err.println("${parameters.text("marker")}:$it:stderr")
    val observed = olap.sources.inspect(SourceInspection.Capabilities)
    require(observed is SourceInspectionResult.Capabilities && observed.operations.isNotEmpty())
}
ScriptValue.BooleanValue(true)`, map[string]any{"marker": outputMarker})
	if !actualScriptValue(t, multiple).GetBoolean() || multiple.WorkerID != first.WorkerID || len(multiple.Calls) != 3 {
		t.Fatal("formal repeated source calls did not continue inside the same script worker")
	}
	for _, call := range multiple.Calls {
		if !call.Success || call.RequestID == "" {
			t.Fatalf("formal capability did not return its actual service receipt: %+v", call)
		}
	}
	log, err := os.ReadFile(filepath.Join(m.cfg.WorkDir, "runtime.log"))
	if err != nil {
		t.Fatal(err)
	}
	position := 0
	for index := range 3 {
		for _, stream := range []string{"stdout", "stderr"} {
			line := []byte(fmt.Sprintf("%s:%d:%s\n", outputMarker, index, stream))
			found := bytes.Index(log[position:], line)
			if found < 0 {
				t.Fatalf("completed script output did not reach its actual log in order: %s", line)
			}
			position += found + len(line)
		}
	}
	afterOutput := actualScript(t, m, trialPrincipal, `ScriptValue.BooleanValue(true)`, map[string]any{})
	if !actualScriptValue(t, afterOutput).GetBoolean() || afterOutput.WorkerID != first.WorkerID {
		t.Fatal("ordered script output was mistaken for idle worker output or left the worker unusable")
	}
	changedScopes := Principal{Subject: trialPrincipal.Subject, Scopes: []string{"runtime-test", "extra-scope"}}
	otherScopes := actualScript(t, m, changedScopes, `ScriptValue.Text(olap.caller.subject)`, map[string]any{})
	actualScriptValue(t, otherScopes)
	if otherScopes.WorkerID == first.WorkerID {
		t.Fatal("different scopes reused the previous isolation owner")
	}
	requireProcessExited(t, pid)
	otherPID, _ := actualScriptProcess(t, m, otherScopes.WorkerID)
	otherSubject := actualScript(t, m, Principal{Subject: "other-subject", Scopes: changedScopes.Scopes}, `ScriptValue.Text(olap.caller.subject)`, map[string]any{})
	if actualScriptValue(t, otherSubject).GetText() != "other-subject" || otherSubject.WorkerID == otherScopes.WorkerID {
		t.Fatal("different subject reused the previous isolation owner")
	}
	requireProcessExited(t, otherPID)
}

func TestActualCloseWaitsForColdScriptStartupAndCleanup(t *testing.T) {
	m := actualRuntime(t, 2)
	ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
	defer cancel()
	completed := make(chan struct {
		report *ScriptReport
		err    error
	}, 1)
	go func() {
		report, err := m.Script(ctx, trialPrincipal, ScriptSubmission{
			Source: "Thread.sleep(60_000)\nScriptValue.BooleanValue(true)", SourceName: "cold-start.olap.kts",
			DSLAPIVersion: m.classpaths.DSLAPIVersion, Parameters: json.RawMessage(`{}`)},
			func(ctx context.Context, invocation *pb.CapabilityInvocation) (Reply, error) {
				return m.CallCapability(ctx, trialPrincipal, invocation)
			})
		completed <- struct {
			report *ScriptReport
			err    error
		}{report, err}
	}()
	root := filepath.Join(m.cfg.WorkDir, "scripting")
	directory, pid := "", 0
	until := time.Now().Add(20 * time.Second)
	for time.Now().Before(until) && pid == 0 {
		m.scripts.mu.Lock()
		starting := m.scripts.slots[0].borrowed && m.scripts.slots[0].worker == nil
		m.scripts.mu.Unlock()
		if starting {
			entries, _ := os.ReadDir(root)
			for _, entry := range entries {
				if !entry.IsDir() {
					continue
				}
				candidate, err := filepath.EvalSymlinks(filepath.Join(root, entry.Name()))
				if err != nil {
					continue
				}
				processes, err := exec.Command("ps", "-axww", "-o", "pid=,command=").Output()
				if err != nil {
					t.Fatal(err)
				}
				for _, line := range strings.Split(string(processes), "\n") {
					if !strings.Contains(line, "com.olap.prototype.runtime.ScriptMainKt") || !strings.Contains(line, "-Duser.home="+candidate) {
						continue
					}
					fields := strings.Fields(line)
					observed, err := strconv.Atoi(fields[0])
					if err != nil {
						t.Fatal(err)
					}
					m.scripts.mu.Lock()
					stillStarting := m.scripts.slots[0].borrowed && m.scripts.slots[0].worker == nil
					m.scripts.mu.Unlock()
					if stillStarting {
						directory, pid = candidate, observed
						break
					}
				}
			}
		}
		if pid == 0 {
			time.Sleep(20 * time.Millisecond)
		}
	}
	if pid == 0 {
		t.Fatal("did not observe the real cold script JVM before worker publication")
	}
	shutdown, stop := context.WithTimeout(context.Background(), 10*time.Second)
	defer stop()
	if err := m.Close(shutdown); err != nil {
		t.Fatal(err)
	}
	m.mu.Lock()
	active := len(m.scriptRuns)
	m.mu.Unlock()
	if active != 0 {
		t.Fatal("Close returned while the cold script call still owned runtime state")
	}
	if _, err := os.Stat(directory); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("Close returned before its cold script directory was reclaimed: %v", err)
	}
	requireProcessExited(t, pid)
	select {
	case result := <-completed:
		if result.err == nil || result.report == nil || result.report.Success {
			t.Fatalf("cold script startup did not terminate with runtime shutdown: %+v %v", result.report, result.err)
		}
	case <-time.After(time.Second):
		t.Fatal("cold script call did not return after Close reclaimed its actual JVM")
	}
}

func TestActualScriptWorkerReleasesInstancesLoadersAndReturnedModels(t *testing.T) {
	m := actualRuntime(t, 2)
	key := fmt.Sprintf("agentic.olap.lifetime-trial.%d", time.Now().UnixNano())
	workerID := ""
	for generation := range 12 {
		source := fmt.Sprintf(`
val generation = %d
val key = parameters.text("key")
@Suppress("UNCHECKED_CAST")
val references = System.getProperties().computeIfAbsent(key) {
    java.util.concurrent.ConcurrentLinkedQueue<Pair<String, java.lang.ref.WeakReference<Any>>>()
} as java.util.concurrent.ConcurrentLinkedQueue<Pair<String, java.lang.ref.WeakReference<Any>>>
val response = olap.sources.inspect(SourceInspection.Capabilities)
val holder = object { val client = olap; val arguments = parameters; val result = response }
references.add("$generation:instance" to java.lang.ref.WeakReference<Any>(holder))
references.add("$generation:classloader" to java.lang.ref.WeakReference<Any>(holder.javaClass.classLoader))
references.add("$generation:client" to java.lang.ref.WeakReference<Any>(olap))
references.add("$generation:parameters" to java.lang.ref.WeakReference<Any>(parameters))
references.add("$generation:response" to java.lang.ref.WeakReference<Any>(response))
olap.output(response)`, generation)
		report := actualScript(t, m, trialPrincipal, source, map[string]any{"key": key})
		if actualScriptValue(t, report).GetRegisteredReply() == nil {
			t.Fatal("lifetime trial did not deliver its real registered response")
		}
		if workerID != "" && report.WorkerID != workerID {
			t.Fatal("lifetime trial replaced the worker instead of observing release in a reused JVM")
		}
		workerID = report.WorkerID
	}
	observed := actualScript(t, m, trialPrincipal, `
val key = parameters.text("key")
@Suppress("UNCHECKED_CAST")
val references = System.getProperties().remove(key) as java.util.concurrent.ConcurrentLinkedQueue<Pair<String, java.lang.ref.WeakReference<Any>>>
System.gc()
Thread.sleep(250)
ScriptValue.ObjectValue(mapOf(
    "observed" to ScriptValue.DecimalValue(references.size.toString()),
    "alive" to ScriptValue.ArrayValue(references.mapNotNull { (label, reference) -> if (reference.get() == null) null else ScriptValue.Text(label) })
))`, map[string]any{"key": key})
	fields := actualScriptValue(t, observed).GetObject().GetValues()
	if observed.WorkerID != workerID || fields["observed"].GetDecimal() != "60" {
		t.Fatal("lifetime observation did not inspect the preceding real executions")
	}
	if alive := fields["alive"].GetArray().GetValues(); len(alive) != 0 {
		t.Fatalf("completed scripts remain strongly reachable after finite GC: %v", alive)
	}
}

func TestActualUnresponsiveAndBackgroundScriptsCannotKeepWorkerOrBorrowLaterAuthority(t *testing.T) {
	m := actualRuntime(t, 2)
	warm := actualScript(t, m, trialPrincipal, `ScriptValue.BooleanValue(true)`, map[string]any{})
	actualScriptValue(t, warm)
	pid, directory := actualScriptProcess(t, m, warm.WorkerID)
	cooperative, stopCooperative := context.WithCancel(context.Background())
	defer stopCooperative()
	cooperated := make(chan error, 1)
	go func() {
		_, err := m.Script(cooperative, trialPrincipal, ScriptSubmission{Source: `
java.nio.file.Files.writeString(olap.temporaryDirectory.resolve("sleep-started"), "started")
try { Thread.sleep(60_000) } catch (_: InterruptedException) { }
ScriptValue.BooleanValue(true)`, SourceName: "cooperative.olap.kts", DSLAPIVersion: m.classpaths.DSLAPIVersion, Parameters: json.RawMessage(`{}`)},
			func(ctx context.Context, input *pb.CapabilityInvocation) (Reply, error) {
				return m.CallCapability(ctx, trialPrincipal, input)
			})
		cooperated <- err
	}()
	waitForScriptFile(t, directory, "sleep-started")
	stopCooperative()
	select {
	case err := <-cooperated:
		if !errors.Is(err, context.Canceled) {
			t.Fatalf("cooperative cancellation lost caller outcome: %v", err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("cooperative script did not return within the cancellation grace")
	}
	afterCooperative := actualScript(t, m, trialPrincipal, `ScriptValue.BooleanValue(true)`, map[string]any{})
	if !actualScriptValue(t, afterCooperative).GetBoolean() || afterCooperative.WorkerID != warm.WorkerID {
		t.Fatal("cooperative cancellation unconditionally discarded a clean worker")
	}
	ctx, cancel := context.WithCancel(context.Background())
	finished := make(chan error, 1)
	go func() {
		_, err := m.Script(ctx, trialPrincipal, ScriptSubmission{Source: `java.nio.file.Files.writeString(olap.temporaryDirectory.resolve("loop-started"), "started")
while (true) { }`, SourceName: "loop.olap.kts", DSLAPIVersion: m.classpaths.DSLAPIVersion, Parameters: json.RawMessage(`{}`)},
			func(ctx context.Context, input *pb.CapabilityInvocation) (Reply, error) {
				return m.CallCapability(ctx, trialPrincipal, input)
			})
		finished <- err
	}()
	defer cancel()
	waitForScriptFile(t, directory, "loop-started")
	cancel()
	select {
	case err := <-finished:
		if !errors.Is(err, context.Canceled) {
			t.Fatalf("infinite program did not preserve caller cancellation: %v", err)
		}
	case <-time.After(8 * time.Second):
		t.Fatal("unresponsive script was not forcibly reaped")
	}
	requireProcessExited(t, pid)
	clean := actualScript(t, m, trialPrincipal, `ScriptValue.BooleanValue(true)`, map[string]any{})
	if !actualScriptValue(t, clean).GetBoolean() || clean.WorkerID == warm.WorkerID {
		t.Fatal("next request reused the unresponsive process")
	}
	var escapedCalls atomic.Int32
	background, err := m.Script(context.Background(), trialPrincipal, ScriptSubmission{Source: `
Thread {
    Thread.sleep(500)
    runCatching { olap.sources.inspect(SourceInspection.Capabilities) }
}.start()
ScriptValue.BooleanValue(true)`, SourceName: "background.olap.kts", DSLAPIVersion: m.classpaths.DSLAPIVersion, Parameters: json.RawMessage(`{}`)},
		func(ctx context.Context, input *pb.CapabilityInvocation) (Reply, error) {
			escapedCalls.Add(1)
			return m.CallCapability(ctx, trialPrincipal, input)
		})
	if err != nil {
		t.Fatalf("completed program failed before its background resource decision: %v", err)
	}
	actualScriptValue(t, background)
	next := actualScript(t, m, Principal{Subject: "another-owner", Scopes: []string{"runtime-test"}}, `ScriptValue.BooleanValue(true)`, map[string]any{})
	if !actualScriptValue(t, next).GetBoolean() || next.WorkerID == background.WorkerID || escapedCalls.Load() != 0 {
		t.Fatal("background callback escaped its request or kept the worker for another principal")
	}
	if len(background.Calls) != 0 {
		t.Fatalf("expired callback became an authorized call: %s", fmt.Sprint(background.Calls))
	}
}
