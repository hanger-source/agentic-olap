package runtime

import (
	"context"
	"encoding/json"
	"errors"
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
	SessionID     string          `json:"sessionId,omitempty"`
	ExecutionID   string          `json:"executionId"`
	WaitMillis    int64           `json:"waitMillis"`
	Source        string          `json:"source"`
	SourceName    string          `json:"sourceName"`
	DSLAPIVersion string          `json:"dslApiVersion"`
	Parameters    json.RawMessage `json:"parameters"`
}

type ScriptCallReceipt struct {
	CallID       string        `json:"callId"`
	OperationID  string        `json:"operationId"`
	RequestID    string        `json:"requestId"`
	State        string        `json:"state"`
	Success      bool          `json:"success"`
	Stage        string        `json:"stage,omitempty"`
	Code         string        `json:"code,omitempty"`
	ElapsedNanos int64         `json:"elapsedNanos"`
	Effects      ScriptEffects `json:"effects"`
}

// 效果仍由正式 Protobuf 合同拥有；公开 JSON 不暴露生成代码的 oneof 包装。
type ScriptEffects []*pb.CapabilityEffect

func (effects ScriptEffects) MarshalJSON() ([]byte, error) {
	values := make([]json.RawMessage, 0, len(effects))
	for _, effect := range effects {
		value, err := protojson.Marshal(effect)
		if err != nil {
			return nil, err
		}
		values = append(values, value)
	}
	return json.Marshal(values)
}

type ScriptReport struct {
	SessionID         string                 `json:"sessionId"`
	ExecutionID       string                 `json:"executionId"`
	State             string                 `json:"state"`
	SessionUsable     bool                   `json:"sessionUsable"`
	RequestID         string                 `json:"requestId"`
	WorkerID          string                 `json:"workerId"`
	WorkerOutcome     string                 `json:"workerOutcome"`
	WorkerDisposition string                 `json:"workerDisposition,omitempty"`
	RecycleReasons    []string               `json:"recycleReasons"`
	Success           bool                   `json:"success"`
	Outputs           []ScriptOutput         `json:"outputs"`
	Diagnostics       []*pb.ScriptDiagnostic `json:"diagnostics"`
	Calls             []ScriptCallReceipt    `json:"calls"`
	TimingNanos       map[string]int64       `json:"timingNanos"`
}

// 查询回执须经过应用层的保存和交付确认，不能从来源 worker 直接返回脚本。
type CapabilityHandler func(context.Context, *pb.CapabilityInvocation) (Reply, error)

func validateProto(value *anypb.Any, maximum int64) error {
	if err := validateRegisteredProto(value); err != nil {
		return err
	}
	if int64(proto.Size(value)) > maximum {
		return errors.New("bounded registered Protobuf message is required")
	}
	return nil
}

func validateRegisteredProto(value *anypb.Any) error {
	if value == nil || !strings.HasPrefix(value.TypeUrl, "type.googleapis.com/agentic.olap.") {
		return errors.New("registered formal Protobuf message is required")
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
	value.Effects = reply.Effects
	if reply.Failure != nil {
		value.Stage, value.Code = reply.Failure.Stage, reply.Failure.Code
		if reply.Failure.Code == "CAPABILITY_DELIVERY_FAILED" {
			value.State = "UNKNOWN"
		}
	}
}

func (s *scriptCalls) settled() {
	s.mu.Lock()
	defer s.mu.Unlock()
	close(s.active)
	s.active = nil
}

func (s *scriptCalls) close() {
	s.mu.Lock()
	s.closed = true
	active := s.active
	s.mu.Unlock()
	if active != nil {
		<-active
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	s.seen = nil
}

func (s *scriptCalls) snapshot() []ScriptCallReceipt {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]ScriptCallReceipt{}, s.receipts...)
}

func (m *Manager) runScript(execution *scriptExecution, session *scriptSession, principal Principal, submission ScriptSubmission) (finalState string, finalSuccess bool, worker *scriptWorker) {
	started := time.Now()
	p := m.scripts
	p.mu.Lock()
	worker = session.slot.worker
	p.mu.Unlock()
	reasons := []string{}
	directory := ""
	finalState, finalSuccess = "LOST", false
	defer func() {
		execution.calls.close()
		if worker != nil && len(reasons) > 0 {
			worker.close(reasons...)
		}
		if directory != "" {
			if err := os.RemoveAll(directory); err != nil {
				reasons = appendScriptReason(reasons, "SCRIPT_EXECUTION_DIRECTORY_CLEANUP_FAILED")
				slog.Error("script execution directory cleanup failed", "requestId", execution.id, "error", err)
			}
		}
		disposition := "RETIRED"
		if worker != nil {
			disposition, reasons = m.finishScriptWorker(session, worker, reasons)
		} else {
			p.mu.Lock()
			if session.state != "RESETTING" && session.state != "CLOSING" {
				session.state = "LOST"
				releaseLostScriptSlot(session, nil, "NO_PROCESS_HANDLE")
			}
			p.mu.Unlock()
		}
		execution.mu.Lock()
		execution.report.WorkerDisposition, execution.report.RecycleReasons = disposition, reasons
		execution.report.TimingNanos["SCRIPT_TOTAL"] = time.Since(started).Nanoseconds()
		execution.invoke, execution.ctx, execution.cancel = nil, nil, nil
		execution.mu.Unlock()
	}()
	fail := func(cause error) {
		execution.requestCancel()
		state, code := "LOST", "SCRIPT_RUNTIME_LOST"
		if errors.Is(cause, context.DeadlineExceeded) {
			state, code = "CANCELLED", "SCRIPT_DEADLINE_EXCEEDED"
		}
		if errors.Is(cause, context.Canceled) {
			state, code = "CANCELLED", "SCRIPT_CANCELLED"
		}
		execution.mu.Lock()
		finalState, finalSuccess = state, false
		execution.report.Diagnostics = append(execution.report.Diagnostics, &pb.ScriptDiagnostic{Stage: "RUNTIME", Severity: "ERROR", Code: code, Message: "script execution did not complete; recorded outputs, calls and effects remain available"})
		execution.mu.Unlock()
		slog.Warn("script execution did not complete", "sessionId", session.id, "executionId", execution.executionID, "requestId", execution.id, "error", cause)
	}
	if worker == nil {
		startup := time.Now()
		var err error
		worker, err = m.newScriptWorker(execution.ctx, session.owner)
		if err != nil {
			if worker != nil {
				p.mu.Lock()
				session.slot.worker = worker
				p.mu.Unlock()
			}
			reasons = append(reasons, "SCRIPT_WORKER_STARTUP_FAILED")
			fail(err)
			return
		}
		p.mu.Lock()
		session.slot.worker = worker
		if session.state == "STARTING" {
			session.state = "BUSY"
		}
		p.mu.Unlock()
		execution.mu.Lock()
		execution.report.WorkerOutcome = "STARTED"
		execution.report.TimingNanos["SCRIPT_WORKER_STARTUP"] = time.Since(startup).Nanoseconds()
		execution.mu.Unlock()
		go m.observeScriptWorker(session, worker)
	} else {
		execution.mu.Lock()
		execution.report.WorkerOutcome = "SESSION"
		execution.mu.Unlock()
	}
	execution.mu.Lock()
	execution.report.WorkerID = worker.id
	execution.mu.Unlock()
	var err error
	directory, err = worker.executionDirectory()
	if err != nil {
		reasons = append(reasons, "SCRIPT_EXECUTION_DIRECTORY_FAILED")
		fail(err)
		return
	}
	if err = worker.bind(execution); err != nil {
		reasons = append(reasons, "SCRIPT_WORKER_BIND_FAILED")
		fail(err)
		return
	}
	slog.Info("script started", "sessionId", session.id, "executionId", execution.executionID, "requestId", execution.id, "workerId", worker.id)
	response, runErr := worker.process.script(execution.ctx, &pb.ScriptRequest{RequestId: execution.id, SessionId: session.id,
		RemainingMillis: remainingMillis(execution.ctx), Source: &pb.ScriptSource{Source: submission.Source, SourceName: submission.SourceName, DslApiVersion: submission.DSLAPIVersion},
		ParametersJson: submission.Parameters, CallerSubject: principal.Subject, ExecutionDirectory: directory})
	if response == nil {
		reasons = append(reasons, "SCRIPT_RESPONSE_MISSING")
		fail(runErr)
		return
	}
	state := response.State
	if state != "COMPLETED" && state != "COMPILE_FAILED" && state != "RUNTIME_FAILED" && state != "CANCELLED" {
		reasons = append(reasons, "SCRIPT_RESPONSE_CONTRACT_INVALID")
		fail(errors.New("invalid script completion state"))
		return
	}
	for _, issue := range response.ResourceIssues {
		worker.log.Warn("script resource observation", "sessionId", session.id, "executionId", execution.executionID, "requestId", execution.id, "workerId", worker.id, "resourceIssue", issue)
		if !response.SessionUsable {
			reasons = appendScriptReason(reasons, issue.Code)
		}
	}
	if !response.SessionUsable && len(reasons) == 0 {
		reasons = append(reasons, "SCRIPT_SESSION_UNUSABLE")
	}
	execution.mu.Lock()
	finalState, finalSuccess = state, state == "COMPLETED" && response.Success
	execution.report.Diagnostics = append(execution.report.Diagnostics, response.Diagnostics...)
	for stage, nanos := range response.TimingNanos {
		execution.report.TimingNanos[stage] = int64(nanos)
	}
	execution.mu.Unlock()
	slog.Info("script completed", "sessionId", session.id, "executionId", execution.executionID, "requestId", execution.id, "state", state)
	return
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
	reply.Effects = result.Effects
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
	if err := validateRegisteredProto(result.Model); err != nil {
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

func (m *Manager) startScriptProcess(ctx context.Context, directory string, callback func(*pb.CapabilityRequest), output func(*pb.ScriptOutput), emission func(*pb.ScriptEmission)) (*process, error) {
	configuration := &pb.ScriptConfiguration{DslApiVersion: m.classpaths.DSLAPIVersion, CompilationClasspath: m.classpaths.DSL, MaximumSourceBytes: uint64(m.deployment.Limits.InputBytes), MaximumFrameBytes: uint64(m.deployment.Limits.FrameBytes), MaximumInputDepth: m.deployment.Limits.InputDepth, MaximumInputNodes: m.deployment.Limits.InputNodes}
	configuration.MaximumRequestBytes = uint64(m.deployment.Limits.InputBytes)
	files := append(strings.Split(m.classpaths.Script, string(filepath.ListSeparator)), m.classpaths.DSL...)
	startup, cancel := context.WithTimeout(ctx, time.Duration(m.deployment.Limits.StartupSeconds*float64(time.Second)))
	defer cancel()
	p, _, err := startProcess(startup, m.cfg, processLaunch{Classpath: m.classpaths.Script, Mode: "script", HeapMiB: m.deployment.Scripting.JVMHeapMiB, Bootstrap: &pb.Bootstrap{Protocol: protocolVersion, Scripting: configuration}, MaximumBytes: m.deployment.Limits.FrameBytes, LogBytes: m.deployment.Scripting.NativeLogBytes, Capability: callback, ScriptOutput: output, ScriptEmission: emission, Sandbox: &scriptSandbox{Directory: directory, ReadFiles: files}})
	if err != nil {
		return nil, err
	}
	return p, nil
}
