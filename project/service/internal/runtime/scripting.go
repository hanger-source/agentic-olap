package runtime

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	pb "github.com/hanger-source/agentic-olap/service/internal/protocol"
	_ "github.com/hanger-source/agentic-olap/service/internal/protocol/governance"
	_ "github.com/hanger-source/agentic-olap/service/internal/protocol/query"
	"google.golang.org/protobuf/encoding/protojson"
	"google.golang.org/protobuf/proto"
	"google.golang.org/protobuf/types/known/anypb"
)

type admissionKey struct{}
type requestAdmission struct {
	owner  *Manager
	active atomic.Bool
}

// 准入只发生在外部请求；内部能力调用继承 Go 持有的同一父预算。
func (m *Manager) admit(ctx context.Context) (context.Context, func(), error) {
	if existing, ok := ctx.Value(admissionKey{}).(*requestAdmission); ok {
		if existing.owner != m || !existing.active.Load() {
			return nil, nil, errors.New("parent request admission is no longer active")
		}
		return ctx, func() {}, nil
	}
	select {
	case m.capacity <- struct{}{}:
	case <-ctx.Done():
		return nil, nil, ctx.Err()
	case <-m.closing:
		return nil, nil, errors.New("runtime is closed")
	}
	permit := &requestAdmission{owner: m}
	permit.active.Store(true)
	return context.WithValue(ctx, admissionKey{}, permit), func() { permit.active.Store(false); <-m.capacity }, nil
}

type ScriptSubmission struct {
	Source        string          `json:"source"`
	SourceName    string          `json:"sourceName"`
	DSLAPIVersion string          `json:"dslApiVersion"`
	Parameters    json.RawMessage `json:"parameters"`
}

type ScriptCallReceipt struct {
	CallID       string `json:"callId"`
	OperationID  string `json:"operationId"`
	RequestID    string `json:"requestId"`
	State        string `json:"state"`
	Success      bool   `json:"success"`
	Stage        string `json:"stage,omitempty"`
	Code         string `json:"code,omitempty"`
	ElapsedNanos int64  `json:"elapsedNanos"`
}

type ScriptReport struct {
	RequestID     string                 `json:"requestId"`
	WorkerID      string                 `json:"workerId"`
	WorkerOutcome string                 `json:"workerOutcome"`
	Success       bool                   `json:"success"`
	Value         json.RawMessage        `json:"value"`
	Diagnostics   []*pb.ScriptDiagnostic `json:"diagnostics"`
	Calls         []ScriptCallReceipt    `json:"calls"`
	TimingNanos   map[string]int64       `json:"timingNanos"`
}

// 查询回执须经过应用层的保存和交付确认，不能从来源 worker 直接返回脚本。
type CapabilityHandler func(context.Context, *pb.CapabilityInvocation) (Reply, error)

func validateProto(value *anypb.Any, maximum int64) error {
	if value == nil || int64(proto.Size(value)) > maximum || !strings.HasPrefix(value.TypeUrl, "type.googleapis.com/agentic.olap.") {
		return errors.New("bounded registered Protobuf message is required")
	}
	return nil
}

type scriptCalls struct {
	mu       sync.Mutex
	id       string
	maximum  int
	closed   bool
	seen     map[string]bool
	active   chan struct{}
	receipts []ScriptCallReceipt
}

func (s *scriptCalls) begin(request *pb.CapabilityRequest) (int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed || request.RequestId != s.id || request.CallId == "" || len(request.CallId) > 128 || s.seen[request.CallId] {
		return 0, errors.New("SCRIPT_CAPABILITY_REQUEST_INVALID")
	}
	if s.active != nil {
		return 0, errors.New("SCRIPT_CAPABILITY_CONCURRENCY_REJECTED")
	}
	if len(s.seen) >= s.maximum {
		return 0, errors.New("SCRIPT_CAPABILITY_BUDGET_EXCEEDED")
	}
	s.seen[request.CallId] = true
	s.active = make(chan struct{})
	operation := ""
	if request.Invocation != nil {
		operation = request.Invocation.OperationId
	}
	s.receipts = append(s.receipts, ScriptCallReceipt{CallID: request.CallId, OperationID: operation, State: "STARTED"})
	return len(s.receipts) - 1, nil
}

func (s *scriptCalls) finish(index int, reply *pb.CapabilityResponse, elapsed time.Duration) {
	s.mu.Lock()
	defer s.mu.Unlock()
	value := &s.receipts[index]
	value.RequestID, value.Success, value.State, value.ElapsedNanos = reply.OperationRequestId, reply.Success, "COMPLETED", elapsed.Nanoseconds()
	if reply.Failure != nil {
		value.Stage, value.Code = reply.Failure.Stage, reply.Failure.Code
	}
	close(s.active)
	s.active = nil
}

func (s *scriptCalls) close(ctx context.Context) []ScriptCallReceipt {
	s.mu.Lock()
	s.closed = true
	active := s.active
	s.mu.Unlock()
	if active != nil {
		select {
		case <-active:
		case <-ctx.Done():
		}
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]ScriptCallReceipt{}, s.receipts...)
}

func (m *Manager) Script(ctx context.Context, principal Principal, submission ScriptSubmission, invoke CapabilityHandler) (*ScriptReport, error) {
	if submission.Source == "" || int64(len(submission.Source)) > m.deployment.Limits.InputBytes || submission.SourceName == "" || submission.DSLAPIVersion != m.classpaths.DSLAPIVersion || !json.Valid(submission.Parameters) || int64(len(submission.Parameters)) > m.deployment.Limits.InputBytes || invoke == nil {
		return nil, errors.New("script requires bounded source, sourceName, exact DSL API version and parameters")
	}
	owner, err := scriptOwner(principal)
	if err != nil {
		return nil, err
	}
	started := time.Now()
	parent, cancel := context.WithTimeout(ctx, time.Duration(m.deployment.Limits.RequestSeconds*float64(time.Second)))
	defer cancel()
	parent, release, err := m.admit(parent)
	if err != nil {
		return nil, err
	}
	defer release()
	id := fmt.Sprintf("script-%d", m.sequence.Add(1))
	m.mu.Lock()
	if m.closed {
		m.mu.Unlock()
		return nil, errors.New("runtime is closed")
	}
	m.scriptRuns[id] = cancel
	m.scriptWait.Add(1)
	m.mu.Unlock()
	defer func() { m.mu.Lock(); delete(m.scriptRuns, id); m.mu.Unlock(); m.scriptWait.Done() }()
	report := &ScriptReport{RequestID: id, Calls: []ScriptCallReceipt{}, Diagnostics: []*pb.ScriptDiagnostic{}, TimingNanos: map[string]int64{}}
	fail := func(cause error) (*ScriptReport, error) {
		report.Success = false
		code := "SCRIPT_RUNTIME_FAILED"
		if errors.Is(cause, context.DeadlineExceeded) {
			code = "SCRIPT_DEADLINE_EXCEEDED"
		} else if errors.Is(cause, context.Canceled) {
			code = "SCRIPT_CANCELLED"
		}
		report.Diagnostics = append(report.Diagnostics, &pb.ScriptDiagnostic{Stage: "RUNTIME", Severity: "ERROR", Code: code, Message: "script runtime did not complete; inspect the recorded capability calls"})
		report.TimingNanos["SCRIPT_TOTAL"] = time.Since(started).Nanoseconds()
		slog.Warn("script did not complete", "requestId", id, "error", cause)
		return report, cause
	}
	queued := time.Now()
	slot, worker, outcome, startup, err := m.acquireScript(parent, owner)
	if err != nil {
		return fail(err)
	}
	report.WorkerID, report.WorkerOutcome = worker.id, outcome
	report.TimingNanos["SCRIPT_WORKER_STARTUP"] = startup.Nanoseconds()
	report.TimingNanos["SCRIPT_WORKER_QUEUE"] = time.Since(queued).Nanoseconds() - startup.Nanoseconds()
	recycle := true
	defer func() { m.releaseScript(slot, worker, recycle) }()
	directory, err := worker.executionDirectory()
	if err != nil {
		return fail(err)
	}
	defer func() {
		if err := os.RemoveAll(directory); err != nil {
			slog.Error("script directory cleanup failed", "requestId", id, "error", err)
			recycle = true
		}
	}()
	calls := &scriptCalls{id: id, maximum: m.deployment.Scripting.MaxCalls, seen: map[string]bool{}, receipts: []ScriptCallReceipt{}}
	if err = worker.bind(&scriptExecution{ctx: parent, id: id, calls: calls, invoke: invoke, outputRemaining: m.deployment.Limits.ResponseBytes}); err != nil {
		return fail(err)
	}
	slog.Info("script started", "requestId", id, "sourceName", submission.SourceName, "workerId", worker.id, "workerOutcome", outcome)
	response, runErr := worker.process.script(parent, &pb.ScriptRequest{RequestId: id, RemainingMillis: remainingMillis(parent), Source: &pb.ScriptSource{Source: submission.Source, SourceName: submission.SourceName, DslApiVersion: submission.DSLAPIVersion}, ParametersJson: submission.Parameters, CallerSubject: principal.Subject, ExecutionDirectory: directory})
	recycle = response == nil || response.GetRecycleWorker() || !worker.process.alive()
	if runErr != nil {
		cancel()
	}
	finalization, finish := context.WithTimeout(context.WithoutCancel(parent), 5*time.Second)
	report.Calls = calls.close(finalization)
	finish()
	if runErr != nil {
		return fail(runErr)
	}
	report.Success = response.Success
	report.Diagnostics = append(report.Diagnostics, response.Diagnostics...)
	for stage, nanos := range response.TimingNanos {
		report.TimingNanos[stage] = int64(nanos)
	}
	if response.Success {
		if err := validateProto(response.Result, m.deployment.Limits.ResponseBytes); err != nil {
			return fail(err)
		}
		bytes, err := protojson.MarshalOptions{}.Marshal(response.Result)
		if err != nil {
			return fail(err)
		}
		report.Value = bytes
	}
	report.TimingNanos["SCRIPT_TOTAL"] = time.Since(started).Nanoseconds()
	slog.Info("script completed", "requestId", id, "success", report.Success, "calls", len(report.Calls))
	return report, nil
}

func (m *Manager) invokeScriptCapability(ctx context.Context, request *pb.CapabilityRequest, invoke CapabilityHandler) *pb.CapabilityResponse {
	reply := &pb.CapabilityResponse{RequestId: request.RequestId, CallId: request.CallId}
	if request.Invocation == nil || request.Invocation.OperationId == "" || validateProto(request.Invocation.Input, m.deployment.Limits.InputBytes) != nil {
		reply.Failure = &pb.CapabilityFailure{Stage: "INPUT", Code: "CAPABILITY_REQUEST_INVALID", Message: "a registered formal capability request is required", CallId: request.CallId}
		if request.Invocation != nil {
			reply.Failure.OperationId = request.Invocation.OperationId
		}
		return reply
	}
	reply.Failure = &pb.CapabilityFailure{OperationId: request.Invocation.OperationId, CallId: request.CallId}
	result, err := invoke(ctx, request.Invocation)
	reply.OperationRequestId = result.RequestID
	if err != nil {
		reply.Failure.Stage, reply.Failure.Code, reply.Failure.Message = "APPLICATION", "CAPABILITY_DELIVERY_FAILED", "capability did not complete; inspect its exact request and run"
		return reply
	}
	if !result.Success {
		var failure struct {
			Stage   string          `json:"stage"`
			Code    string          `json:"code"`
			Message string          `json:"message"`
			Details json.RawMessage `json:"details"`
		}
		if json.Unmarshal(result.Error, &failure) != nil {
			reply.Failure.Stage, reply.Failure.Code, reply.Failure.Message = "PROTOCOL", "CAPABILITY_FAILURE_CONTRACT_INVALID", "application omitted its formal failure contract"
			return reply
		}
		reply.Failure.Stage, reply.Failure.Code, reply.Failure.Message, reply.Failure.DetailsJson = failure.Stage, failure.Code, failure.Message, failure.Details
		var details struct {
			FieldPath    []json.RawMessage `json:"fieldPath"`
			DiagnosticID string            `json:"diagnosticId"`
		}
		if json.Unmarshal(failure.Details, &details) == nil {
			reply.Failure.DiagnosticId = details.DiagnosticID
			for _, part := range details.FieldPath {
				var field string
				if json.Unmarshal(part, &field) == nil {
					reply.Failure.FieldPath = append(reply.Failure.FieldPath, field)
				} else {
					reply.Failure.FieldPath = append(reply.Failure.FieldPath, string(part))
				}
			}
		}
		return reply
	}
	if err := validateProto(result.Model, m.deployment.Limits.ResponseBytes); err != nil {
		reply.Failure.Stage, reply.Failure.Code, reply.Failure.Message = "PROTOCOL", "CAPABILITY_RESULT_CONTRACT_INVALID", "application omitted its declared Protobuf result"
		return reply
	}
	reply.Success, reply.Result, reply.Failure = true, result.Model, nil
	return reply
}

func (m *Manager) scriptDirectory() (string, error) {
	root := filepath.Join(m.cfg.WorkDir, "scripting")
	if err := os.MkdirAll(root, 0700); err != nil {
		return "", err
	}
	directory, err := os.MkdirTemp(root, "script-")
	if err != nil {
		return "", err
	}
	resolved, err := filepath.EvalSymlinks(directory)
	if err != nil {
		return "", errors.Join(err, os.RemoveAll(directory))
	}
	return resolved, nil
}

func (m *Manager) startScriptProcess(ctx context.Context, directory string, callback func(*pb.CapabilityRequest), output func(*pb.ScriptOutput)) (*process, error) {
	configuration := &pb.ScriptConfiguration{DslApiVersion: m.classpaths.DSLAPIVersion, CompilationClasspath: m.classpaths.DSL, MaximumSourceBytes: uint64(m.deployment.Limits.InputBytes), MaximumOutputBytes: uint64(m.deployment.Limits.ResponseBytes), MaximumFrameBytes: uint64(m.deployment.Limits.FrameBytes), MaximumInputDepth: m.deployment.Limits.InputDepth, MaximumInputNodes: m.deployment.Limits.InputNodes}
	configuration.MaximumRequestBytes = uint64(m.deployment.Limits.InputBytes)
	files := append(strings.Split(m.classpaths.Script, string(filepath.ListSeparator)), m.classpaths.DSL...)
	startup, cancel := context.WithTimeout(ctx, time.Duration(m.deployment.Limits.StartupSeconds*float64(time.Second)))
	defer cancel()
	p, _, err := startProcess(startup, m.cfg, processLaunch{Classpath: m.classpaths.Script, Mode: "script", HeapMiB: m.deployment.Scripting.JVMHeapMiB, Bootstrap: &pb.Bootstrap{Protocol: protocolVersion, Scripting: configuration}, MaximumBytes: m.deployment.Limits.FrameBytes, LogBytes: m.deployment.Scripting.NativeLogBytes, Capability: callback, ScriptOutput: output, Sandbox: &scriptSandbox{Directory: directory, ReadFiles: files}})
	if err != nil {
		return nil, err
	}
	return p, nil
}
