//go:build darwin

package runtime

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"syscall"
	"testing"
	"time"

	pb "github.com/hanger-source/agentic-olap/service/internal/protocol"
	gov "github.com/hanger-source/agentic-olap/service/internal/protocol/governance"
	query "github.com/hanger-source/agentic-olap/service/internal/protocol/query"
	"google.golang.org/protobuf/encoding/protojson"
	"google.golang.org/protobuf/types/known/anypb"
)

// 使用同一份真实发行、元数据服务和正式能力回调；两个槽位是本试验的显式部署。
func actualSessionRuntime(t *testing.T) *Manager {
	t.Helper()
	distribution, javaHome := os.Getenv("AGENTIC_OLAP_DISTRIBUTION"), os.Getenv("JAVA_HOME")
	if distribution == "" || javaHome == "" {
		t.Fatal("AGENTIC_OLAP_DISTRIBUTION and JAVA_HOME must name the actual built distribution and JDK")
	}
	directory := t.TempDir()
	log, err := os.Create(filepath.Join(directory, "runtime.log"))
	if err != nil {
		t.Fatal(err)
	}
	deployment, err := json.Marshal(map[string]any{
		"dataRoot": filepath.Join(directory, "data"), "connectors": []string{}, "dataSources": []any{},
		"auth": map[string]any{"grants": []any{map[string]any{
			"subjects": []string{trialPrincipal.Subject}, "scopes": trialPrincipal.Scopes,
			"operations":  []string{"inspect-warehouse", "list-data-sources"},
			"dataSources": []string{}, "subjectAreas": []string{}, "releases": []string{}, "allGovernanceAssets": false,
		}, map[string]any{
			"subjects": []string{trialPrincipal.Subject}, "scopes": trialPrincipal.Scopes,
			"operations":  []string{"write-governance", "read-governance"},
			"dataSources": []string{}, "subjectAreas": []string{}, "releases": []string{}, "allGovernanceAssets": true,
		}}},
		"history":   map[string]any{"enabled": false, "retentionDays": 1},
		"scripting": map[string]any{"workerCount": 2, "jvmHeapMiB": 768, "maxCalls": 100, "nativeLogBytes": 1048576},
		"limits": map[string]any{
			"requestSeconds": 60, "startupSeconds": 60, "maxInFlight": 1, "workerCount": 1,
			"catalogEntriesPerWorker": 2, "requestBytes": 8192, "inputDepth": 16, "inputNodes": 200000,
			"inputFileBytes": 33554432, "inputFiles": 64, "responseBytes": 33554432,
			"pendingOutputBytes": 67108864, "workerJvmHeapMiB": 512, "nativeMemoryBytes": 268435456,
			"catalogBytes": 33554432, "sqlBytes": 4194304, "parameters": 10000,
			"resultRows": 100000, "resultValues": 1000000, "resultBytes": 16777216, "resultDepth": 64,
		},
	})
	if err != nil {
		log.Close()
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
		ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
		defer cancel()
		if err := m.Close(ctx); err != nil {
			t.Errorf("runtime shutdown failed: %v", err)
		}
		for _, pid := range pids {
			requireProcessExited(t, pid)
		}
		log.Close()
		if t.Failed() {
			content, _ := os.ReadFile(log.Name())
			const maximum = 8 * 1024
			if len(content) > maximum {
				content = content[len(content)-maximum:]
			}
			t.Logf("actual runtime diagnostics (full log %s):\n%s", log.Name(), content)
		}
	})
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	if err := m.Start(ctx); err != nil {
		t.Fatal(err)
	}
	return m
}

func sessionSubmission(t *testing.T, m *Manager, sessionID, executionID, source string, arguments any) ScriptSubmission {
	t.Helper()
	parameters, err := json.Marshal(arguments)
	if err != nil {
		t.Fatal(err)
	}
	return ScriptSubmission{SessionID: sessionID, ExecutionID: executionID, WaitMillis: 60000,
		Source: source, SourceName: executionID + ".olap.kts", DSLAPIVersion: m.classpaths.DSLAPIVersion, Parameters: parameters}
}

func submitSession(t *testing.T, m *Manager, request ScriptSubmission) *ScriptReport {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 90*time.Second)
	defer cancel()
	report, err := m.Script(ctx, trialPrincipal, request, func(ctx context.Context, invocation *pb.CapabilityInvocation) (Reply, error) {
		return m.CallCapability(ctx, trialPrincipal, invocation)
	})
	if err != nil {
		t.Fatalf("actual script submission failed: %v; report=%+v", err, report)
	}
	return report
}

func awaitSessionExecution(t *testing.T, m *Manager, executionID string) *ScriptReport {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 90*time.Second)
	defer cancel()
	report, err := m.WaitScript(ctx, trialPrincipal, executionID, 60000)
	if err != nil {
		t.Fatalf("actual script wait failed: %v; report=%+v", err, report)
	}
	if report.State == "RUNNING" {
		t.Fatalf("execution did not finish within its deployed deadline: %+v", report)
	}
	return report
}

func requireCompletedSession(t *testing.T, report *ScriptReport) {
	t.Helper()
	if !report.Success || report.State != "COMPLETED" || !report.SessionUsable || report.WorkerDisposition != "RETAINED" {
		t.Fatalf("actual session did not complete and remain usable: %+v", report)
	}
}

func sessionValues(t *testing.T, report *ScriptReport) []*query.ScriptValue {
	t.Helper()
	values := []*query.ScriptValue{}
	for index, output := range report.Outputs {
		if output.Sequence != uint64(index+1) {
			t.Fatalf("output sequence does not preserve actual delivery order: %+v", report.Outputs)
		}
		if output.Kind != "VALUE" {
			continue
		}
		var envelope anypb.Any
		if err := protojson.Unmarshal(output.Value, &envelope); err != nil {
			t.Fatal(err)
		}
		var value query.ScriptValue
		if err := envelope.UnmarshalTo(&value); err != nil {
			t.Fatal(err)
		}
		values = append(values, &value)
	}
	return values
}

func actualScriptProcess(t *testing.T, m *Manager, workerID string) (int, string) {
	t.Helper()
	m.scripts.mu.Lock()
	defer m.scripts.mu.Unlock()
	for _, slot := range m.scripts.slots {
		if slot.worker != nil && slot.worker.id == workerID {
			return slot.worker.process.cmd.Process.Pid, slot.worker.directory
		}
	}
	t.Fatalf("actual session worker %q is not present", workerID)
	return 0, ""
}

func requireProcessExited(t *testing.T, pid int) {
	t.Helper()
	if err := syscall.Kill(pid, 0); !errors.Is(err, syscall.ESRCH) {
		t.Errorf("script JVM %d remains after confirmed retirement: %v", pid, err)
	}
}

func waitForScriptFile(t *testing.T, directory, name string) {
	t.Helper()
	until := time.Now().Add(45 * time.Second)
	for time.Now().Before(until) {
		if _, err := os.Stat(filepath.Join(directory, name)); err == nil {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("actual evaluation never reached %s", filepath.Join(directory, name))
}

func closeActualSession(t *testing.T, m *Manager, sessionID string) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 90*time.Second)
	defer cancel()
	session, err := m.CloseScriptSession(ctx, trialPrincipal, sessionID)
	if err != nil || session.State != "CLOSED" || session.Usable {
		t.Errorf("actual session did not close: %v; session=%+v", err, session)
	}
}

func TestActualSessionIdentityDoesNotAliasAfterRuntimeRestart(t *testing.T) {
	previous := actualSessionRuntime(t)
	const initial = `
val restartMarker = parameters.text("marker")
val restartSources = olap.sources.list(EmptyRequest)
olap.output(ScriptValue.Text(restartMarker))`
	old := submitSession(t, previous, sessionSubmission(t, previous, "", "restart-first", initial, map[string]any{"marker": "before"}))
	requireCompletedSession(t, old)
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	err := previous.Close(ctx)
	cancel()
	if err != nil {
		t.Fatal(err)
	}

	// 同部署、主体、启动及首次执行顺序；不能靠额外请求改变旧计数器来错开身份。
	restarted, err := New(previous.cfg)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		pids := runtimePIDs(restarted)
		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer cancel()
		if err := restarted.Close(ctx); err != nil {
			t.Errorf("重启后的运行时未正常退出：%v", err)
		}
		for _, pid := range pids {
			requireProcessExited(t, pid)
		}
	})
	ctx, cancel = context.WithTimeout(context.Background(), 60*time.Second)
	err = restarted.Start(ctx)
	cancel()
	if err != nil {
		t.Fatal(err)
	}
	fresh := submitSession(t, restarted, sessionSubmission(t, restarted, "", "restart-first", initial, map[string]any{"marker": "after"}))
	requireCompletedSession(t, fresh)
	if fresh.SessionID == old.SessionID {
		t.Fatalf("重启后的首次会话复用了旧身份：%s", old.SessionID)
	}
	for name, action := range map[string]func(context.Context) error{
		"read": func(ctx context.Context) error {
			_, err := restarted.ReadScriptSession(ctx, trialPrincipal, old.SessionID)
			return err
		},
		"reset": func(ctx context.Context) error {
			_, err := restarted.ResetScriptSession(ctx, trialPrincipal, old.SessionID)
			return err
		},
		"close": func(ctx context.Context) error {
			_, err := restarted.CloseScriptSession(ctx, trialPrincipal, old.SessionID)
			return err
		},
		"continue": func(ctx context.Context) error {
			_, err := restarted.Script(ctx, trialPrincipal, sessionSubmission(t, restarted, old.SessionID, "restart-stale", `olap.output(ScriptValue.Text(restartMarker))`, map[string]any{}),
				func(ctx context.Context, invocation *pb.CapabilityInvocation) (Reply, error) {
					return restarted.CallCapability(ctx, trialPrincipal, invocation)
				})
			return err
		},
	} {
		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		err := action(ctx)
		cancel()
		var unavailable *ScriptError
		if !errors.As(err, &unavailable) || unavailable.Code != "SCRIPT_SESSION_UNAVAILABLE" {
			t.Fatalf("旧会话身份仍能执行 %s：%v", name, err)
		}
	}
	continued := submitSession(t, restarted, sessionSubmission(t, restarted, fresh.SessionID, "restart-current", `
check(restartMarker == "after")
check(olap.sources.list(EmptyRequest).items.isEmpty())
olap.output(ScriptValue.Text(restartMarker))`, map[string]any{}))
	requireCompletedSession(t, continued)
	if len(continued.Calls) != 1 || !continued.Calls[0].Success || len(sessionValues(t, continued)) != 1 || sessionValues(t, continued)[0].GetText() != "after" {
		t.Fatalf("旧身份操作影响了新会话历史或正式调用：%+v", continued)
	}
}

func TestActualPersistentScriptSessions(t *testing.T) {
	m := actualSessionRuntime(t)

	t.Run("language_history_formal_objects_and_explicit_outputs", func(t *testing.T) {
		first := submitSession(t, m, sessionSubmission(t, m, "", "history-setup", `
val originalParameters = parameters
val observedSources = olap.sources.list(EmptyRequest)
data class Holder<T>(val value: T)
val heldSources = Holder(observedSources)
val selectedTotals = when (observedSources.items.size) {
    0 -> listOf("first" to 2, "second" to 3).map { (label, amount) -> label to amount * 2 }
    else -> emptyList()
}
check(selectedTotals == listOf("first" to 4, "second" to 6))
fun <T> Holder<T>.unwrap(): T = value
fun AvailableSources.sourceCount(): Int = items.size
fun currentLabel(): String = parameters.text("label")
fun readCurrentSources() = olap.sources.list(EmptyRequest)
fun selectedLabel(): String = "first"
fun selectedThroughOldHelper(): String = selectedLabel()
heldSources`, map[string]any{"label": "first"}))
		requireCompletedSession(t, first)
		t.Cleanup(func() { closeActualSession(t, m, first.SessionID) })
		if len(first.Outputs) != 0 || len(first.Calls) != 1 || !first.Calls[0].Success {
			t.Fatalf("an arbitrary last expression became output, or the formal read was lost: %+v", first)
		}
		pid, directory := actualScriptProcess(t, m, first.WorkerID)
		second := submitSession(t, m, sessionSubmission(t, m, first.SessionID, "history-continue", `
check(originalParameters.text("label") == "first")
check(currentLabel() == "second")
check(heldSources.unwrap().sourceCount() == 0)
check(selectedTotals.sumOf { (_, amount) -> amount } == 10)
println("before-formal-output")
olap.output(heldSources.unwrap())
System.err.println("between-formal-outputs")
olap.output(readCurrentSources())
olap.output(ScriptValue.Text(originalParameters.text("label") + ":" + currentLabel()))
olap.output(ScriptValue.Text(olap.temporaryDirectory.toString()))
Holder(Any())`, map[string]any{"label": "second"}))
		requireCompletedSession(t, second)
		if second.WorkerID != first.WorkerID || len(second.Calls) != 1 || !second.Calls[0].Success || second.Calls[0].RequestID == first.Calls[0].RequestID {
			t.Fatalf("historical helper did not use the current formal execution: %+v", second)
		}
		actualPID, _ := actualScriptProcess(t, m, second.WorkerID)
		if actualPID != pid {
			t.Fatal("continuing the same session replaced its actual JVM")
		}
		values := sessionValues(t, second)
		if len(values) != 4 || values[2].GetText() != "first:second" || values[3].GetText() != directory {
			t.Fatalf("parameter snapshots, current getters or session directory differ: %v", values)
		}
		for _, value := range values[:2] {
			reply := value.GetRegisteredReply()
			if reply == nil || reply.OperationId != "list-data-sources" || reply.Content == nil {
				t.Fatalf("saved or current formal object lost its registered origin: %v", value)
			}
			var sources gov.AvailableSources
			if err := reply.Content.UnmarshalTo(&sources); err != nil || len(sources.Items) != 0 {
				t.Fatalf("formal source reply was not retained exactly: %v; %v", err, &sources)
			}
		}
		if len(second.Outputs) != 6 || second.Outputs[0].Kind != "STDOUT" || string(second.Outputs[0].Content) != "before-formal-output\n" || second.Outputs[1].Kind != "VALUE" || second.Outputs[2].Kind != "STDERR" || string(second.Outputs[2].Content) != "between-formal-outputs\n" {
			t.Fatalf("printing and explicit snapshots lost their actual order: %+v", second.Outputs)
		}

		rejected := submitSession(t, m, sessionSubmission(t, m, first.SessionID, "history-compile-error", `val incomplete =`, map[string]any{}))
		if rejected.State != "COMPILE_FAILED" || rejected.Success || !rejected.SessionUsable || rejected.WorkerID != first.WorkerID || len(rejected.Outputs) != 0 || len(rejected.Calls) != 0 {
			t.Fatalf("ordinary compiler rejection damaged the session or executed code: %+v", rejected)
		}
		after := submitSession(t, m, sessionSubmission(t, m, first.SessionID, "history-after-compile-error", `
olap.output(ScriptValue.Text(originalParameters.text("label") + ":" + currentLabel()))
olap.output(heldSources.unwrap())`, map[string]any{"label": "third"}))
		requireCompletedSession(t, after)
		if after.WorkerID != first.WorkerID || len(sessionValues(t, after)) != 2 || sessionValues(t, after)[0].GetText() != "first:third" {
			t.Fatalf("successful history was not usable after a compiler rejection: %+v", after)
		}

		// 官方语言的重声明绑定需要实际观察，不能从宿主接口推断旧 helper 会重绑。
		redeclared := submitSession(t, m, sessionSubmission(t, m, first.SessionID, "history-redeclaration", `
fun selectedLabel(): String = "second"
olap.output(ScriptValue.Text("direct=" + selectedLabel() + ";old-helper=" + selectedThroughOldHelper()))`, map[string]any{}))
		t.Logf("official redeclaration observation: state=%s usable=%t outputs=%v diagnostics=%v", redeclared.State, redeclared.SessionUsable, sessionValues(t, redeclared), redeclared.Diagnostics)
		historyAvailable := redeclared.SessionUsable
		if !redeclared.SessionUsable {
			// 不猜测官方历史是否可恢复；显式 reset 后独立观察普通运行异常。
			ctx, cancel := context.WithTimeout(context.Background(), 90*time.Second)
			reset, err := m.ResetScriptSession(ctx, trialPrincipal, first.SessionID)
			cancel()
			if err != nil || reset.State != "READY" {
				t.Fatalf("cannot prepare an independent runtime-error observation: %v; %+v", err, reset)
			}
		}
		runtimeFailure := submitSession(t, m, sessionSubmission(t, m, first.SessionID, "history-runtime-error", `
olap.output(ScriptValue.Text("emitted-before-error"))
error("actual-history-runtime-error")`, map[string]any{}))
		if runtimeFailure.State != "RUNTIME_FAILED" || runtimeFailure.Success || len(sessionValues(t, runtimeFailure)) != 1 || sessionValues(t, runtimeFailure)[0].GetText() != "emitted-before-error" {
			t.Fatalf("runtime failure erased prior explicit output or reported completion: %+v", runtimeFailure)
		}
		t.Logf("official runtime-error continuation observation: usable=%t diagnostics=%v", runtimeFailure.SessionUsable, runtimeFailure.Diagnostics)
		if runtimeFailure.SessionUsable && historyAvailable {
			continued := submitSession(t, m, sessionSubmission(t, m, first.SessionID, "history-after-runtime-error", `olap.output(ScriptValue.Text(originalParameters.text("label")))`, map[string]any{}))
			t.Logf("official history after runtime error: state=%s usable=%t outputs=%v diagnostics=%v", continued.State, continued.SessionUsable, sessionValues(t, continued), continued.Diagnostics)
			requireCompletedSession(t, continued)
			if values := sessionValues(t, continued); len(values) != 1 || values[0].GetText() != "first" {
				t.Fatalf("an available session lost untouched history after a runtime error: %v", values)
			}
		}
	})

	t.Run("unicode_formal_requests_and_committed_effects", func(t *testing.T) {
		t.Run("valid_unicode_round_trips_through_governance", func(t *testing.T) {
			report := submitSession(t, m, sessionSubmission(t, m, "", "unicode-valid", `
val unicodeName = "经营🙂e\u0301𐐷"
val unicodeCreated = olap.governance.createBusinessLine(BusinessLineCreateRequest("unicode_valid", unicodeName, "team", null, olap.caller.subject))
olap.output(unicodeCreated)
val unicodeStored = olap.semantics.businessLines(PageInput(0, 100)).items.single { it.id == "unicode_valid" }
check(unicodeStored.displayName == unicodeName)
olap.output(ScriptValue.Text(unicodeStored.displayName))`, map[string]any{}))
			t.Cleanup(func() { closeActualSession(t, m, report.SessionID) })
			requireCompletedSession(t, report)
			values := sessionValues(t, report)
			if len(report.Calls) != 2 || len(values) != 2 || values[1].GetText() != "经营🙂e\u0301𐐷" {
				t.Fatalf("合法 Unicode 未按原字符回读：report=%+v values=%v", report, values)
			}
			var created gov.BusinessLineCreateResult
			if reply := values[0].GetRegisteredReply(); reply == nil || reply.Content == nil || reply.OperationId != "create-business-line" {
				t.Fatalf("正式创建回执来源丢失：%v", values[0])
			} else if err := reply.Content.UnmarshalTo(&created); err != nil || created.GetBusinessLine().GetDisplayName() != "经营🙂e\u0301𐐷" {
				t.Fatalf("正式 Protobuf 回执改写了合法 Unicode：%v；%v", err, &created)
			}
		})

		t.Run("invalid_formal_input_never_sends_a_capability_call", func(t *testing.T) {
			report := submitSession(t, m, sessionSubmission(t, m, "", "unicode-invalid-request", `
olap.governance.createBusinessLine(BusinessLineCreateRequest("unicode_invalid", "\uD800", "team", null, olap.caller.subject))`, map[string]any{}))
			t.Cleanup(func() { closeActualSession(t, m, report.SessionID) })
			if report.Success || report.State != "RUNTIME_FAILED" || len(report.Calls) != 0 || len(report.Outputs) != 0 {
				t.Fatalf("非法正式字符串产生了能力调用或成功结果：%+v", report)
			}
			located := false
			for _, diagnostic := range report.Diagnostics {
				if diagnostic.Code == "INVALID_REQUEST" && diagnostic.Stage == "DECODE_INPUT" && len(diagnostic.FieldPath) == 1 && diagnostic.FieldPath[0] == "displayName" && diagnostic.Expected == "WELL_FORMED_UNICODE_TEXT" && diagnostic.Actual == "UNPAIRED_UTF16_SURROGATE" {
					located = true
				}
			}
			if !located {
				t.Fatalf("正式请求拒绝缺少可修正的 Unicode 字段位置：%+v", report.Diagnostics)
			}
			observed := submitSession(t, m, sessionSubmission(t, m, "", "unicode-invalid-readback", `
check(olap.semantics.businessLines(PageInput(0, 100)).items.none { it.id == "unicode_invalid" })
olap.output(ScriptValue.BooleanValue(true))`, map[string]any{}))
			t.Cleanup(func() { closeActualSession(t, m, observed.SessionID) })
			requireCompletedSession(t, observed)
			if values := sessionValues(t, observed); len(values) != 1 || !values[0].GetBoolean() {
				t.Fatalf("非法输入留下了治理资产：%v", values)
			}
		})

		t.Run("invalid_reference_output_retains_the_committed_write", func(t *testing.T) {
			report := submitSession(t, m, sessionSubmission(t, m, "", "unicode-output-after-write", `
val unicodeEffect = olap.governance.createBusinessLine(BusinessLineCreateRequest("unicode_effect", "已提交🙂", "team", null, olap.caller.subject))
olap.output(unicodeEffect)
olap.output(MemberRef("\uD800", 1))`, map[string]any{}))
			t.Cleanup(func() { closeActualSession(t, m, report.SessionID) })
			if report.Success || report.State != "RUNTIME_FAILED" || len(report.Calls) != 1 || !report.Calls[0].Success || len(report.Calls[0].Effects) != 1 {
				t.Fatalf("输出失败抹去了实际已提交能力或效果：%+v", report)
			}
			effect := report.Calls[0].Effects[0]
			if reference := effect.GetAssetVersion(); effect.ReadOperation != "read-asset-version" || reference == nil || reference.GetAssetType() != "BUSINESS_LINE" || reference.GetId() != "unicode_effect" || reference.GetVersion() != "1" {
				t.Fatalf("输出失败后的效果坐标错误：%v", effect)
			}
			located := false
			for _, diagnostic := range report.Diagnostics {
				if diagnostic.Code == "SCRIPT_OUTPUT_REPRESENTATION" && diagnostic.Stage == "OUTPUT" && len(diagnostic.FieldPath) == 3 && diagnostic.FieldPath[0] == "reference" && diagnostic.FieldPath[1] == "member" && diagnostic.FieldPath[2] == "id" {
					located = true
				}
			}
			if !located {
				t.Fatalf("正式引用输出没有明确拒绝非法字符串：%+v", report.Diagnostics)
			}
			if values := sessionValues(t, report); len(values) != 1 || values[0].GetRegisteredReply().GetOperationId() != "create-business-line" {
				t.Fatalf("失败前的正式输出丢失或非法输出被替换后发送：%v", values)
			}
			observed := submitSession(t, m, sessionSubmission(t, m, "", "unicode-effect-readback", `
val committedUnicode = olap.semantics.businessLines(PageInput(0, 100)).items.single { it.id == "unicode_effect" }
check(committedUnicode.displayName == "已提交🙂")
olap.output(ScriptValue.Text(committedUnicode.displayName))`, map[string]any{}))
			t.Cleanup(func() { closeActualSession(t, m, observed.SessionID) })
			requireCompletedSession(t, observed)
			if values := sessionValues(t, observed); len(values) != 1 || values[0].GetText() != "已提交🙂" {
				t.Fatalf("输出失败后的实际治理写入无法继续读取：%v", values)
			}
		})
	})

	t.Run("isolated_jvms_waiting_and_execution_deduplication", func(t *testing.T) {
		first := submitSession(t, m, sessionSubmission(t, m, "", "isolation-first", `
val sessionLabel = "first-session"
val firstSessionOnly = "private-first"
var completedRuns = 0`, map[string]any{}))
		requireCompletedSession(t, first)
		t.Cleanup(func() { closeActualSession(t, m, first.SessionID) })
		second := submitSession(t, m, sessionSubmission(t, m, "", "isolation-second", `
val sessionLabel = "second-session"
olap.output(ScriptValue.Text(sessionLabel))`, map[string]any{}))
		requireCompletedSession(t, second)
		t.Cleanup(func() { closeActualSession(t, m, second.SessionID) })
		firstPID, directory := actualScriptProcess(t, m, first.WorkerID)
		secondPID, _ := actualScriptProcess(t, m, second.WorkerID)
		if first.SessionID == second.SessionID || first.WorkerID == second.WorkerID || firstPID == secondPID {
			t.Fatal("two sessions share an actual language environment or JVM")
		}
		isolated := submitSession(t, m, sessionSubmission(t, m, second.SessionID, "isolation-forbidden-variable", `olap.output(ScriptValue.Text(firstSessionOnly))`, map[string]any{}))
		if isolated.State != "COMPILE_FAILED" || !isolated.SessionUsable {
			t.Fatalf("the second session saw the first session's declarations: %+v", isolated)
		}
		other := submitSession(t, m, sessionSubmission(t, m, second.SessionID, "isolation-second-continue", `olap.output(ScriptValue.Text(sessionLabel))`, map[string]any{}))
		requireCompletedSession(t, other)
		if other.WorkerID != second.WorkerID || len(sessionValues(t, other)) != 1 || sessionValues(t, other)[0].GetText() != "second-session" {
			t.Fatalf("continuing the second JVM changed its own session: %+v", other)
		}

		request := sessionSubmission(t, m, first.SessionID, "waited-once", `
java.nio.file.Files.writeString(olap.temporaryDirectory.resolve("execution-entered"), "entered")
while (!java.nio.file.Files.exists(olap.temporaryDirectory.resolve("execution-release"))) Thread.sleep(10)
completedRuns += 1
olap.output(ScriptValue.DecimalValue(completedRuns.toString()))`, map[string]any{})
		request.WaitMillis = 0
		running := submitSession(t, m, request)
		if running.State != "RUNNING" || running.Success {
			t.Fatalf("waitMillis=0 did not return the ongoing execution: %+v", running)
		}
		waitForScriptFile(t, directory, "execution-entered")
		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Millisecond)
		waiting, err := m.WaitScript(ctx, trialPrincipal, request.ExecutionID, 1000)
		cancel()
		if !errors.Is(err, context.DeadlineExceeded) || waiting.State != "RUNNING" {
			t.Fatalf("ending an observer wait did not preserve the ongoing execution: %v; %+v", err, waiting)
		}
		retry := submitSession(t, m, request)
		if retry.RequestID != running.RequestID || retry.SessionID != running.SessionID || retry.State != "RUNNING" {
			t.Fatalf("retry created a second execution instead of observing the first: %+v", retry)
		}
		conflict := request
		conflict.Source += "\nprintln(\"a different request\")"
		_, err = m.Script(context.Background(), trialPrincipal, conflict, func(ctx context.Context, invocation *pb.CapabilityInvocation) (Reply, error) {
			return m.CallCapability(ctx, trialPrincipal, invocation)
		})
		var refusal *ScriptError
		if !errors.As(err, &refusal) || refusal.Code != "SCRIPT_EXECUTION_CONFLICT" {
			t.Fatalf("different input reused an execution identity: %v", err)
		}
		idle, err := m.ReadScriptSession(context.Background(), trialPrincipal, second.SessionID)
		if err != nil || idle.State != "READY" || idle.WorkerID != second.WorkerID {
			t.Fatalf("another execution changed the idle session's fixed worker: %v; %+v", err, idle)
		}
		next := sessionSubmission(t, m, first.SessionID, "isolation-final-state", `
olap.output(ScriptValue.Text(sessionLabel))
olap.output(ScriptValue.DecimalValue(completedRuns.toString()))`, map[string]any{})
		if err := os.WriteFile(filepath.Join(directory, "execution-release"), []byte("release"), 0600); err != nil {
			t.Fatal(err)
		}
		completed := awaitSessionExecution(t, m, request.ExecutionID)
		// 最小准入部署中，完成屏障必须已经释放容量，下一片段无需重试或等待。
		state := submitSession(t, m, next)
		requireCompletedSession(t, completed)
		requireCompletedSession(t, state)
		if completed.RequestID != running.RequestID || completed.WorkerID != first.WorkerID || len(sessionValues(t, completed)) != 1 || sessionValues(t, completed)[0].GetDecimal() != "1" {
			t.Fatalf("observing and retrying changed execution count or ownership: %+v", completed)
		}
		completedRetry := submitSession(t, m, request)
		if completedRetry.RequestID != completed.RequestID || !completedRetry.Success || sessionValues(t, completedRetry)[0].GetDecimal() != "1" {
			t.Fatalf("completed retry re-executed the program: %+v", completedRetry)
		}
		values := sessionValues(t, state)
		if len(values) != 2 || values[0].GetText() != "first-session" || values[1].GetDecimal() != "1" {
			t.Fatalf("persistent state shows a duplicated execution or cross-session leak: %v", values)
		}
		actualFirstPID, _ := actualScriptProcess(t, m, first.WorkerID)
		actualSecondPID, _ := actualScriptProcess(t, m, second.WorkerID)
		if actualFirstPID != firstPID || actualSecondPID != secondPID {
			t.Fatal("ordinary continuation replaced a session's fixed JVM")
		}
	})

	t.Run("reset_and_close_stop_active_code_and_discard_history", func(t *testing.T) {
		first := submitSession(t, m, sessionSubmission(t, m, "", "control-history", `val historyBeforeReset = "old-history"`, map[string]any{}))
		requireCompletedSession(t, first)
		t.Cleanup(func() { closeActualSession(t, m, first.SessionID) })
		oldPID, oldDirectory := actualScriptProcess(t, m, first.WorkerID)
		request := sessionSubmission(t, m, first.SessionID, "control-reset-running", `
java.nio.file.Files.writeString(olap.temporaryDirectory.resolve("reset-entered"), "entered")
while (true) { }`, map[string]any{})
		request.WaitMillis = 0
		submitSession(t, m, request)
		waitForScriptFile(t, oldDirectory, "reset-entered")
		ctx, cancel := context.WithTimeout(context.Background(), 90*time.Second)
		reset, err := m.ResetScriptSession(ctx, trialPrincipal, first.SessionID)
		cancel()
		if err != nil || reset.State != "READY" || !reset.Usable || reset.WorkerID == first.WorkerID {
			t.Fatalf("reset did not finish with a new ready language environment: %v; %+v", err, reset)
		}
		requireProcessExited(t, oldPID)
		if _, err := os.Stat(oldDirectory); !errors.Is(err, os.ErrNotExist) {
			t.Fatalf("reset returned before the old session directory was removed: %v", err)
		}
		stopped := awaitSessionExecution(t, m, request.ExecutionID)
		if stopped.Success || stopped.WorkerDisposition != "RETIRED" || stopped.SessionUsable {
			t.Fatalf("stopped old execution claims a retained environment: %+v", stopped)
		}
		absent := submitSession(t, m, sessionSubmission(t, m, first.SessionID, "control-history-absent", `olap.output(ScriptValue.Text(historyBeforeReset))`, map[string]any{}))
		if absent.State != "COMPILE_FAILED" || !absent.SessionUsable {
			t.Fatalf("reset replayed prior declarations or damaged the new environment: %+v", absent)
		}
		fresh := submitSession(t, m, sessionSubmission(t, m, first.SessionID, "control-fresh-history", `
val historyAfterReset = "fresh-history"
olap.output(ScriptValue.Text(historyAfterReset))`, map[string]any{}))
		requireCompletedSession(t, fresh)
		if fresh.WorkerID != reset.WorkerID || len(sessionValues(t, fresh)) != 1 || sessionValues(t, fresh)[0].GetText() != "fresh-history" {
			t.Fatalf("reset did not retain its new JVM and fresh history: %+v", fresh)
		}
		newPID, newDirectory := actualScriptProcess(t, m, fresh.WorkerID)
		if newPID == oldPID {
			t.Fatal("reset reused the old process")
		}
		closeRequest := sessionSubmission(t, m, first.SessionID, "control-close-running", `
java.nio.file.Files.writeString(olap.temporaryDirectory.resolve("close-entered"), "entered")
while (true) { }`, map[string]any{})
		closeRequest.WaitMillis = 0
		submitSession(t, m, closeRequest)
		waitForScriptFile(t, newDirectory, "close-entered")
		closeActualSession(t, m, first.SessionID)
		requireProcessExited(t, newPID)
		if _, err := os.Stat(newDirectory); !errors.Is(err, os.ErrNotExist) {
			t.Fatalf("close returned before the old session directory was removed: %v", err)
		}
		closedExecution := awaitSessionExecution(t, m, closeRequest.ExecutionID)
		if closedExecution.Success || closedExecution.WorkerDisposition != "RETIRED" {
			t.Fatalf("closed execution still claims a live worker: %+v", closedExecution)
		}
		closedSubmission := sessionSubmission(t, m, first.SessionID, "control-after-close", `olap.output(ScriptValue.Text("must-not-run"))`, map[string]any{})
		_, err = m.Script(context.Background(), trialPrincipal, closedSubmission, func(ctx context.Context, invocation *pb.CapabilityInvocation) (Reply, error) {
			return m.CallCapability(ctx, trialPrincipal, invocation)
		})
		var refusal *ScriptError
		if !errors.As(err, &refusal) || refusal.Code != "SCRIPT_SESSION_CLOSED" {
			t.Fatalf("closed history accepted another execution: %v", err)
		}
	})
}
