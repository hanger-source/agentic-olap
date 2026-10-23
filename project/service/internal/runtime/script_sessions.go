package runtime

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"sync"
	"time"
	"unicode/utf8"

	pb "github.com/hanger-source/agentic-olap/service/internal/protocol"
	"google.golang.org/protobuf/encoding/protojson"
	"google.golang.org/protobuf/types/known/anypb"
)

// ScriptError 由脚本运行 owner 给出稳定失败事实，HTTP 层无需解析错误文本。
type ScriptError struct {
	Code        string `json:"code"`
	Message     string `json:"message"`
	SessionID   string `json:"sessionId,omitempty"`
	ExecutionID string `json:"executionId,omitempty"`
	State       string `json:"state,omitempty"`
}

func (e *ScriptError) Error() string { return e.Message }
func scriptError(code, message, sessionID, executionID, state string) error {
	return &ScriptError{Code: code, Message: message, SessionID: sessionID, ExecutionID: executionID, State: state}
}

type ScriptSession struct {
	SessionID          string `json:"sessionId"`
	State              string `json:"state"`
	Usable             bool   `json:"usable"`
	WorkerID           string `json:"workerId,omitempty"`
	CurrentExecutionID string `json:"currentExecutionId,omitempty"`
}
type scriptSession struct {
	id, owner, state string
	slot             *scriptSlot
	current          *scriptExecution
	retiredWorker    *scriptWorker
}

type scriptControlResult struct {
	session *ScriptSession
	err     error
}

// 只释放已确认不再承载 JVM 的位置；目录清理失败的真实 handle 留在会话供显式控制重试。
// 调用者持有 pool.mu，且本执行的全部回调已经结束。
func releaseLostScriptSlot(session *scriptSession, worker *scriptWorker, outcome string) {
	if session.state != "LOST" || outcome == "EXIT_UNCONFIRMED" {
		return
	}
	if outcome == "CLEANUP_FAILED" {
		session.retiredWorker = worker
	}
	if session.slot != nil {
		session.slot.worker, session.slot.session = nil, nil
		session.slot = nil
	}
}

func (s *scriptSession) snapshot() *ScriptSession {
	value := &ScriptSession{SessionID: s.id, State: s.state, Usable: s.state == "READY"}
	if s.slot != nil && s.slot.worker != nil {
		value.WorkerID = s.slot.worker.id
	}
	if s.current != nil {
		value.CurrentExecutionID = s.current.executionID
	}
	return value
}

// 一个有序输出列表拥有每份内容；打印只在 JSON 边界选择无损的文本或 base64 表示。
type ScriptOutput struct {
	Sequence uint64          `json:"sequence"`
	Kind     string          `json:"kind"`
	Value    json.RawMessage `json:"-"`
	Content  []byte          `json:"-"`
}

func (v ScriptOutput) MarshalJSON() ([]byte, error) {
	if v.Kind == "VALUE" {
		return json.Marshal(struct {
			Sequence uint64          `json:"sequence"`
			Kind     string          `json:"kind"`
			Value    json.RawMessage `json:"value"`
		}{v.Sequence, v.Kind, v.Value})
	}
	encoding, content := "UTF8", string(v.Content)
	if !utf8.Valid(v.Content) {
		encoding, content = "BASE64", base64.StdEncoding.EncodeToString(v.Content)
	}
	return json.Marshal(struct {
		Sequence uint64 `json:"sequence"`
		Kind     string `json:"kind"`
		Encoding string `json:"encoding"`
		Content  string `json:"content"`
	}{v.Sequence, v.Kind, encoding, content})
}

type scriptExecution struct {
	mu              sync.Mutex
	ctx             context.Context
	cancel          context.CancelFunc
	id, executionID string
	fingerprint     [32]byte
	calls           *scriptCalls
	invoke          CapabilityHandler
	report          ScriptReport
	done            chan struct{}
}

func (e *scriptExecution) requestCancel() {
	e.mu.Lock()
	cancel := e.cancel
	e.mu.Unlock()
	if cancel != nil {
		cancel()
	}
}

func (e *scriptExecution) print(stream string, content []byte) {
	e.mu.Lock()
	defer e.mu.Unlock()
	if len(content) == 0 {
		return
	}
	outputs := e.report.Outputs
	if len(outputs) > 0 && outputs[len(outputs)-1].Kind == stream {
		e.report.Outputs[len(outputs)-1].Content = append(outputs[len(outputs)-1].Content, content...)
		return
	}
	e.report.Outputs = append(outputs, ScriptOutput{Sequence: uint64(len(outputs) + 1), Kind: stream, Content: append([]byte(nil), content...)})
}
func (e *scriptExecution) emit(value *anypb.Any) error {
	if err := validateRegisteredProto(value); err != nil {
		return err
	}
	raw, err := protojson.Marshal(value)
	if err != nil {
		return err
	}
	e.mu.Lock()
	defer e.mu.Unlock()
	e.report.Outputs = append(e.report.Outputs, ScriptOutput{Sequence: uint64(len(e.report.Outputs) + 1), Kind: "VALUE", Value: raw})
	return nil
}
func (e *scriptExecution) snapshot() *ScriptReport {
	e.mu.Lock()
	value := e.report
	value.Outputs = make([]ScriptOutput, len(e.report.Outputs))
	for i, output := range e.report.Outputs {
		value.Outputs[i] = output
		value.Outputs[i].Content = append([]byte(nil), output.Content...)
	}
	value.Diagnostics = append([]*pb.ScriptDiagnostic{}, e.report.Diagnostics...)
	value.RecycleReasons = append([]string{}, e.report.RecycleReasons...)
	value.TimingNanos = make(map[string]int64, len(e.report.TimingNanos))
	for stage, nanos := range e.report.TimingNanos {
		value.TimingNanos[stage] = nanos
	}
	e.mu.Unlock()
	value.Calls = e.calls.snapshot()
	return &value
}
func executionKey(owner, id string) string { return owner + "\x00" + id }

func (m *Manager) Script(ctx context.Context, principal Principal, submission ScriptSubmission, invoke CapabilityHandler) (*ScriptReport, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	if err := validateScriptWait(submission.WaitMillis); err != nil {
		return nil, err
	}
	if submission.ExecutionID == "" || submission.Source == "" || int64(len(submission.Source)) > m.deployment.Limits.InputBytes || submission.SourceName == "" || submission.DSLAPIVersion != m.classpaths.DSLAPIVersion || !json.Valid(submission.Parameters) || int64(len(submission.Parameters)) > m.deployment.Limits.InputBytes || invoke == nil {
		return nil, scriptError("SCRIPT_INPUT_INVALID", "script requires executionId, source, sourceName, exact DSL API version and valid parameters within the deployed input budget", submission.SessionID, submission.ExecutionID, "")
	}
	owner, err := scriptOwner(principal)
	if err != nil {
		return nil, scriptError("SCRIPT_IDENTITY_REQUIRED", err.Error(), submission.SessionID, submission.ExecutionID, "")
	}
	// 等待时长不改变执行；其他字段保留精确请求含义，包括首次未指定 sessionId 的事实。
	request := submission
	request.WaitMillis = 0
	encoded, err := json.Marshal(request)
	if err != nil {
		return nil, err
	}
	fingerprint := sha256.Sum256(encoded)
	m.mu.Lock()
	if m.closed {
		m.mu.Unlock()
		return nil, scriptError("SCRIPT_RUNTIME_CLOSED", "script runtime is closed", submission.SessionID, submission.ExecutionID, "")
	}
	p := m.scripts
	p.mu.Lock()
	key := executionKey(owner, submission.ExecutionID)
	if previous := p.executions[key]; previous != nil {
		p.mu.Unlock()
		m.mu.Unlock()
		if previous.fingerprint != fingerprint {
			return nil, scriptError("SCRIPT_EXECUTION_CONFLICT", "executionId already identifies a different script request", previous.snapshot().SessionID, submission.ExecutionID, "")
		}
		return waitScriptExecution(ctx, previous, submission.WaitMillis)
	}
	var session *scriptSession
	var selected *scriptSlot
	newSessionID := ""
	if submission.SessionID != "" {
		session = p.sessions[submission.SessionID]
		if session == nil || session.owner != owner {
			p.mu.Unlock()
			m.mu.Unlock()
			return nil, scriptError("SCRIPT_SESSION_UNAVAILABLE", "script session is unavailable to this identity", submission.SessionID, submission.ExecutionID, "")
		}
		if session.state != "READY" {
			state := session.state
			code := "SCRIPT_SESSION_BUSY"
			if state == "LOST" {
				code = "SCRIPT_SESSION_LOST"
			}
			if state == "CLOSED" {
				code = "SCRIPT_SESSION_CLOSED"
			}
			if state == "RESETTING" || state == "CLOSING" {
				code = "SCRIPT_SESSION_CONTROL_IN_PROGRESS"
			}
			p.mu.Unlock()
			m.mu.Unlock()
			return nil, scriptError(code, "script session cannot accept an execution in its current state", session.id, submission.ExecutionID, state)
		}
		selected = session.slot
	} else {
		for _, slot := range p.slots {
			if slot.session == nil {
				selected = slot
				break
			}
		}
		if selected == nil {
			p.mu.Unlock()
			m.mu.Unlock()
			return nil, scriptError("SCRIPT_SESSION_CAPACITY_EXHAUSTED", "all script worker slots belong to sessions; close a session before creating another", "", submission.ExecutionID, "")
		}
		identity := make([]byte, 16)
		if _, err := rand.Read(identity); err != nil {
			p.mu.Unlock()
			m.mu.Unlock()
			return nil, fmt.Errorf("script session identity could not be generated: %w", err)
		}
		newSessionID = "session-" + hex.EncodeToString(identity)
	}
	select {
	case m.capacity <- struct{}{}:
	default:
		p.mu.Unlock()
		m.mu.Unlock()
		return nil, scriptError("SCRIPT_EXECUTION_CAPACITY_EXHAUSTED", "script execution admission is busy", submission.SessionID, submission.ExecutionID, "")
	}
	if session == nil {
		session = &scriptSession{id: newSessionID, owner: owner, state: "STARTING", slot: selected}
		selected.session = session
		p.sessions[session.id] = session
	} else {
		session.state = "BUSY"
	}
	id := fmt.Sprintf("script-%d", m.sequence.Add(1))
	parent, cancel := context.WithTimeout(context.Background(), time.Duration(m.deployment.Limits.RequestSeconds*float64(time.Second)))
	permit := &requestAdmission{owner: m}
	permit.active.Store(true)
	parent = context.WithValue(parent, admissionKey{}, permit)
	execution := &scriptExecution{ctx: parent, cancel: cancel, id: id, executionID: submission.ExecutionID, fingerprint: fingerprint,
		calls: &scriptCalls{id: id, maximum: m.deployment.Scripting.MaxCalls, seen: map[string]bool{}, receipts: []ScriptCallReceipt{}}, invoke: invoke, done: make(chan struct{}),
		report: ScriptReport{SessionID: session.id, ExecutionID: submission.ExecutionID, RequestID: id, State: "RUNNING", Outputs: []ScriptOutput{}, RecycleReasons: []string{}, Calls: []ScriptCallReceipt{}, Diagnostics: []*pb.ScriptDiagnostic{}, TimingNanos: map[string]int64{}}}
	session.current = execution
	p.executions[key] = execution
	m.scriptRuns[id] = cancel
	m.scriptWait.Add(1)
	p.mu.Unlock()
	m.mu.Unlock()
	go func() {
		defer m.scriptWait.Done()
		state, success, worker := m.runScript(execution, session, principal, submission)
		cancel()
		for {
			m.mu.Lock()
			p.mu.Lock()
			execution.mu.Lock()
			// 最后一次健康核对与公开完成处于同一 pool 临界区；已观察的故障/控制必须先实际收尾。
			if execution.report.WorkerDisposition == "RETAINED" &&
				(m.closed || p.closed || session.state != "BUSY" && session.state != "STARTING" || !worker.process.alive()) {
				reasons := execution.report.RecycleReasons
				if m.closed {
					reasons = appendScriptReason(reasons, "SCRIPT_SERVICE_SHUTDOWN")
				}
				execution.mu.Unlock()
				p.mu.Unlock()
				m.mu.Unlock()
				disposition, reasons := m.finishScriptWorker(session, worker, reasons)
				execution.mu.Lock()
				execution.report.WorkerDisposition, execution.report.RecycleReasons = disposition, reasons
				execution.report.SessionUsable = false
				execution.mu.Unlock()
				continue
			}
			delete(m.scriptRuns, id)
			permit.active.Store(false)
			<-m.capacity
			execution.report.State, execution.report.Success = state, success
			execution.report.SessionUsable = execution.report.WorkerDisposition == "RETAINED"
			if session.current == execution {
				session.current = nil
				if session.state == "BUSY" || session.state == "STARTING" {
					if execution.report.SessionUsable {
						session.state = "READY"
					} else {
						session.state = "LOST"
					}
				}
			}
			close(execution.done)
			execution.mu.Unlock()
			p.mu.Unlock()
			m.mu.Unlock()
			return
		}
	}()
	return waitScriptExecution(ctx, execution, submission.WaitMillis)
}

func validateScriptWait(millis int64) error {
	if millis < 0 || millis > int64((1<<63-1)/int64(time.Millisecond)) {
		return scriptError("SCRIPT_WAIT_INVALID", "waitMillis must be a representable non-negative duration", "", "", "")
	}
	return nil
}
func waitScriptExecution(ctx context.Context, execution *scriptExecution, millis int64) (*ScriptReport, error) {
	if millis == 0 {
		return execution.snapshot(), nil
	}
	timer := time.NewTimer(time.Duration(millis) * time.Millisecond)
	defer timer.Stop()
	select {
	case <-execution.done:
	case <-timer.C:
	case <-ctx.Done():
		return execution.snapshot(), ctx.Err()
	}
	return execution.snapshot(), nil
}
func (m *Manager) scriptExecution(principal Principal, id string) (*scriptExecution, error) {
	owner, err := scriptOwner(principal)
	if err != nil {
		return nil, scriptError("SCRIPT_IDENTITY_REQUIRED", err.Error(), "", id, "")
	}
	p := m.scripts
	p.mu.Lock()
	value := p.executions[executionKey(owner, id)]
	p.mu.Unlock()
	if value == nil {
		return nil, scriptError("SCRIPT_EXECUTION_UNAVAILABLE", "script execution is unavailable to this identity", "", id, "")
	}
	return value, nil
}
func (m *Manager) WaitScript(ctx context.Context, principal Principal, executionID string, waitMillis int64) (*ScriptReport, error) {
	if err := validateScriptWait(waitMillis); err != nil {
		return nil, err
	}
	execution, err := m.scriptExecution(principal, executionID)
	if err != nil {
		return nil, err
	}
	return waitScriptExecution(ctx, execution, waitMillis)
}
func (m *Manager) CancelScript(ctx context.Context, principal Principal, executionID string) (*ScriptReport, error) {
	execution, err := m.scriptExecution(principal, executionID)
	if err != nil {
		return nil, err
	}
	execution.requestCancel()
	select {
	case <-execution.done:
		return execution.snapshot(), nil
	case <-ctx.Done():
		return execution.snapshot(), ctx.Err()
	}
}
func (m *Manager) ReadScriptSession(ctx context.Context, principal Principal, sessionID string) (*ScriptSession, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	owner, err := scriptOwner(principal)
	if err != nil {
		return nil, scriptError("SCRIPT_IDENTITY_REQUIRED", err.Error(), sessionID, "", "")
	}
	p := m.scripts
	p.mu.Lock()
	defer p.mu.Unlock()
	session := p.sessions[sessionID]
	if session == nil || session.owner != owner {
		return nil, scriptError("SCRIPT_SESSION_UNAVAILABLE", "script session is unavailable to this identity", sessionID, "", "")
	}
	return session.snapshot(), nil
}
func (m *Manager) ResetScriptSession(ctx context.Context, principal Principal, sessionID string) (*ScriptSession, error) {
	return m.controlScriptSession(ctx, principal, sessionID, true)
}
func (m *Manager) CloseScriptSession(ctx context.Context, principal Principal, sessionID string) (*ScriptSession, error) {
	return m.controlScriptSession(ctx, principal, sessionID, false)
}
func (m *Manager) controlScriptSession(ctx context.Context, principal Principal, sessionID string, reset bool) (*ScriptSession, error) {
	owner, err := scriptOwner(principal)
	if err != nil {
		return nil, scriptError("SCRIPT_IDENTITY_REQUIRED", err.Error(), sessionID, "", "")
	}
	m.mu.Lock()
	if m.closed {
		m.mu.Unlock()
		return nil, scriptError("SCRIPT_RUNTIME_CLOSED", "script runtime is closed", sessionID, "", "")
	}
	p := m.scripts
	p.mu.Lock()
	session := p.sessions[sessionID]
	if session == nil || session.owner != owner {
		p.mu.Unlock()
		m.mu.Unlock()
		return nil, scriptError("SCRIPT_SESSION_UNAVAILABLE", "script session is unavailable to this identity", sessionID, "", "")
	}
	if session.state == "CLOSED" {
		value := session.snapshot()
		p.mu.Unlock()
		m.mu.Unlock()
		if reset {
			return value, scriptError("SCRIPT_SESSION_CLOSED", "closed script session cannot be reset", sessionID, "", "CLOSED")
		}
		return value, nil
	}
	if session.state == "RESETTING" || session.state == "CLOSING" {
		value := session.snapshot()
		p.mu.Unlock()
		m.mu.Unlock()
		return value, scriptError("SCRIPT_SESSION_CONTROL_IN_PROGRESS", "script session already has an active lifecycle operation", sessionID, "", value.State)
	}
	if session.slot == nil {
		if !reset && session.retiredWorker == nil {
			session.state = "CLOSED"
			value := session.snapshot()
			p.mu.Unlock()
			m.mu.Unlock()
			return value, nil
		}
		if reset {
			for _, slot := range p.slots {
				if slot.session == nil {
					session.slot = slot
					slot.session = session
					break
				}
			}
			if session.slot == nil {
				value := session.snapshot()
				p.mu.Unlock()
				m.mu.Unlock()
				return value, scriptError("SCRIPT_SESSION_CAPACITY_EXHAUSTED", "all script worker slots belong to active sessions", sessionID, "", value.State)
			}
		}
	}
	session.state = "CLOSING"
	if reset {
		session.state = "RESETTING"
	}
	result := make(chan scriptControlResult, 1)
	current := session.current
	m.scriptWait.Add(1)
	p.mu.Unlock()
	m.mu.Unlock()
	if current != nil {
		current.requestCancel()
	}
	go func() {
		defer m.scriptWait.Done()
		value, err := m.finishScriptSessionControl(session, current, reset)
		result <- scriptControlResult{session: value, err: err}
	}()
	select {
	case completed := <-result:
		return completed.session, completed.err
	case <-ctx.Done():
		p.mu.Lock()
		value := session.snapshot()
		p.mu.Unlock()
		return value, ctx.Err()
	}
}
func (m *Manager) finishScriptSessionControl(session *scriptSession, current *scriptExecution, reset bool) (*ScriptSession, error) {
	if current != nil {
		<-current.done
	}
	p := m.scripts
	p.mu.Lock()
	slot := session.slot
	worker := session.retiredWorker
	if slot != nil && slot.worker != nil {
		worker = slot.worker
	}
	p.mu.Unlock()
	if worker != nil {
		outcome := worker.close("SCRIPT_SESSION_CONTROL")
		if outcome == "EXIT_UNCONFIRMED" || outcome == "CLEANUP_FAILED" {
			code, message := "SCRIPT_PROCESS_EXIT_UNCONFIRMED", "old script JVM exit could not be confirmed"
			if outcome == "CLEANUP_FAILED" {
				code, message = "SCRIPT_SESSION_CLEANUP_FAILED", "old script session directory could not be cleaned"
			}
			p.mu.Lock()
			session.state = "LOST"
			session.current = nil
			releaseLostScriptSlot(session, worker, outcome)
			value := session.snapshot()
			p.mu.Unlock()
			return value, scriptError(code, message, session.id, "", "LOST")
		}
	}
	p.mu.Lock()
	session.retiredWorker = nil
	if slot != nil {
		slot.worker = nil
	}
	session.current = nil
	if !reset || p.closed {
		session.state = "CLOSED"
		if slot != nil {
			slot.session = nil
		}
		session.slot = nil
		value := session.snapshot()
		p.mu.Unlock()
		return value, nil
	}
	p.mu.Unlock()
	startup, cancel := context.WithTimeout(context.Background(), time.Duration(m.deployment.Limits.StartupSeconds*float64(time.Second)))
	go func() {
		select {
		case <-m.closing:
			cancel()
		case <-startup.Done():
		}
	}()
	newWorker, err := m.newScriptWorker(startup, session.owner)
	cancel()
	p.mu.Lock()
	if err == nil && !p.closed {
		slot.worker = newWorker
		session.state = "READY"
		value := session.snapshot()
		p.mu.Unlock()
		go m.observeScriptWorker(session, newWorker)
		return value, nil
	}
	p.mu.Unlock()
	outcome := "NO_PROCESS_HANDLE"
	if newWorker != nil {
		outcome = newWorker.close("SCRIPT_SESSION_RESET_FAILED")
	}
	p.mu.Lock()
	slot.worker = newWorker
	session.state = "LOST"
	releaseLostScriptSlot(session, newWorker, outcome)
	value := session.snapshot()
	p.mu.Unlock()
	return value, scriptError("SCRIPT_SESSION_RESET_FAILED", "fresh script JVM could not be established", session.id, "", "LOST")
}
func (m *Manager) observeScriptWorker(session *scriptSession, worker *scriptWorker) {
	<-worker.process.done
	p := m.scripts
	p.mu.Lock()
	owned := session.slot != nil && session.slot.worker == worker && (session.state == "READY" || session.state == "BUSY")
	clean := owned && session.current == nil
	if owned {
		session.state = "LOST"
	}
	p.mu.Unlock()
	if clean {
		outcome := worker.close("SCRIPT_PROCESS_UNAVAILABLE")
		p.mu.Lock()
		if session.state == "LOST" && session.slot != nil && session.slot.worker == worker {
			releaseLostScriptSlot(session, worker, outcome)
		}
		p.mu.Unlock()
	}
}
