package runtime

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"slices"
	"sync"
	"time"

	pb "github.com/hanger-source/agentic-olap/service/internal/protocol"
)

// 每个活动会话固定拥有一个槽位；不按身份借用、不排队或淘汰空闲会话。
type scriptPool struct {
	mu         sync.Mutex
	slots      []*scriptSlot
	sessions   map[string]*scriptSession
	executions map[string]*scriptExecution
	closed     bool
}
type scriptSlot struct {
	worker  *scriptWorker
	session *scriptSession
}
type scriptWorker struct {
	mu                   sync.Mutex
	process              *process
	directory, owner, id string
	current              *scriptExecution
	invalidReason        string
	closeMu              sync.Mutex
	processExitState     string
	log                  *slog.Logger
}

func newScriptPool(count int) *scriptPool {
	p := &scriptPool{sessions: map[string]*scriptSession{}, executions: map[string]*scriptExecution{}}
	for range count {
		p.slots = append(p.slots, new(scriptSlot))
	}
	return p
}
func scriptOwner(principal Principal) (string, error) {
	if principal.Subject == "" {
		return "", errors.New("authenticated script subject is required")
	}
	scopes := slices.Clone(principal.Scopes)
	slices.Sort(scopes)
	value, err := json.Marshal(Principal{Subject: principal.Subject, Scopes: slices.Compact(scopes)})
	return string(value), err
}
func (m *Manager) newScriptWorker(ctx context.Context, owner string) (*scriptWorker, error) {
	directory, err := m.scriptDirectory()
	if err != nil {
		return nil, err
	}
	worker := &scriptWorker{directory: directory, owner: owner, id: fmt.Sprintf("script-worker-%d", m.sequence.Add(1)),
		log: slog.New(slog.NewTextHandler(m.cfg.Stderr, nil))}
	process, err := m.startScriptProcess(ctx, directory,
		func(request *pb.CapabilityRequest) { worker.call(m, request) },
		func(output *pb.ScriptOutput) { worker.output(output) },
		func(emission *pb.ScriptEmission) { worker.emit(emission) })
	worker.mu.Lock()
	worker.process = process
	worker.mu.Unlock()
	if err != nil {
		worker.close("SCRIPT_WORKER_STARTUP_FAILED")
		return worker, err
	}
	if process.logs != nil {
		process.logs.idle()
	}
	return worker, nil
}
func (w *scriptWorker) bind(execution *scriptExecution) error {
	w.mu.Lock()
	defer w.mu.Unlock()
	if w.invalidReason != "" || w.current != nil || w.process == nil || !w.process.alive() {
		return errors.New("script worker cannot accept an execution")
	}
	w.current = execution
	if w.process.logs != nil {
		w.process.logs.reset()
	}
	return nil
}
func (w *scriptWorker) call(m *Manager, request *pb.CapabilityRequest) {
	w.mu.Lock()
	current := w.current
	if current == nil || request.RequestId != current.id || w.invalidReason != "" {
		if w.invalidReason == "" {
			w.invalidReason = "SCRIPT_EXPIRED_CAPABILITY_CALL"
		}
		process := w.process
		w.mu.Unlock()
		if process != nil {
			process.fail(errors.New("SCRIPT_EXPIRED_CAPABILITY_CALL"))
			go process.stop()
		}
		return
	}
	current.mu.Lock()
	ctx, invoke := current.ctx, current.invoke
	current.mu.Unlock()
	w.mu.Unlock()
	began := time.Now()
	reply := &pb.CapabilityResponse{RequestId: request.RequestId, CallId: request.CallId}
	index, err := current.calls.begin(request)
	if err != nil {
		reply.Failure = &pb.CapabilityFailure{Stage: "APPLICATION", Code: err.Error(), Message: "capability call is outside the active script contract", CallId: request.CallId}
		if request.Invocation != nil {
			reply.Failure.OperationId = request.Invocation.OperationId
		}
	} else {
		defer current.calls.settled()
		reply = m.invokeScriptCapability(ctx, request, invoke)
		current.calls.finish(index, reply, time.Since(began))
	}
	if ctx != nil {
		_ = w.process.send(ctx, &pb.Frame{Body: &pb.Frame_CapabilityResponse{CapabilityResponse: reply}})
	}
}

func (w *scriptWorker) output(output *pb.ScriptOutput) {
	w.mu.Lock()
	current := w.current
	valid := current != nil && current.id == output.RequestId && w.invalidReason == "" &&
		(output.Stream == pb.ScriptOutput_STDOUT || output.Stream == pb.ScriptOutput_STDERR)
	if !valid {
		if w.invalidReason == "" {
			w.invalidReason = "SCRIPT_OUTPUT_SCOPE_VIOLATION"
		}
		process := w.process
		w.mu.Unlock()
		if process != nil {
			process.fail(errors.New("SCRIPT_OUTPUT_SCOPE_VIOLATION"))
			go process.stop()
		}
		return
	}
	w.mu.Unlock()
	stream := "STDOUT"
	if output.Stream == pb.ScriptOutput_STDERR {
		stream = "STDERR"
	}
	current.print(stream, output.Content)
}
func (w *scriptWorker) emit(emission *pb.ScriptEmission) {
	w.mu.Lock()
	current := w.current
	valid := current != nil && current.id == emission.RequestId && w.invalidReason == ""
	w.mu.Unlock()
	if valid {
		if err := current.emit(emission.Output); err == nil {
			return
		}
	}
	w.mu.Lock()
	if w.invalidReason == "" {
		w.invalidReason = "SCRIPT_EMISSION_CONTRACT_INVALID"
	}
	process := w.process
	w.mu.Unlock()
	if process != nil {
		process.fail(errors.New("SCRIPT_EMISSION_CONTRACT_INVALID"))
		go process.stop()
	}
}
func (m *Manager) finishScriptWorker(session *scriptSession, worker *scriptWorker, reasons []string) (string, []string) {
	worker.mu.Lock()
	requestID := ""
	if worker.current != nil {
		requestID = worker.current.id
	}
	worker.current = nil
	if worker.invalidReason != "" {
		reasons = appendScriptReason(reasons, worker.invalidReason)
	}
	if worker.process == nil || !worker.process.alive() {
		reasons = appendScriptReason(reasons, "SCRIPT_PROCESS_UNAVAILABLE")
	}
	p := m.scripts
	p.mu.Lock()
	if p.closed {
		reasons = appendScriptReason(reasons, "SCRIPT_SERVICE_SHUTDOWN")
	}
	if session.state == "LOST" || worker.process == nil || !worker.process.alive() {
		reasons = appendScriptReason(reasons, "SCRIPT_PROCESS_UNAVAILABLE")
	}
	if len(reasons) == 0 && session.state != "RESETTING" && session.state != "CLOSING" {
		if worker.process.logs != nil {
			worker.process.logs.idle()
		}
		p.mu.Unlock()
		worker.mu.Unlock()
		worker.log.Info("script worker released", "requestId", requestID, "workerId", worker.id, "workerDisposition", "RETAINED")
		return "RETAINED", []string{}
	}
	if len(reasons) == 0 {
		reasons = append(reasons, "SCRIPT_SESSION_CONTROL")
	}
	p.mu.Unlock()
	worker.mu.Unlock()
	exitState := worker.close(reasons...)
	if exitState == "EXIT_UNCONFIRMED" {
		reasons = appendScriptReason(reasons, "PROCESS_EXIT_UNCONFIRMED")
	}
	if exitState == "CLEANUP_FAILED" {
		reasons = appendScriptReason(reasons, "SCRIPT_DIRECTORY_CLEANUP_FAILED")
	}
	p.mu.Lock()
	if session.state != "RESETTING" && session.state != "CLOSING" {
		session.state = "LOST"
		releaseLostScriptSlot(session, worker, exitState)
	}
	p.mu.Unlock()
	worker.log.Info("script worker released", "requestId", requestID, "workerId", worker.id, "workerDisposition", "RETIRED", "recycleReasons", reasons, "processExitState", exitState)
	return "RETIRED", reasons
}
func appendScriptReason(reasons []string, code string) []string {
	if !slices.Contains(reasons, code) {
		return append(reasons, code)
	}
	return reasons
}
func (w *scriptWorker) close(reasons ...string) string {
	w.closeMu.Lock()
	defer w.closeMu.Unlock()
	if len(reasons) == 0 {
		reasons = []string{"SCRIPT_SERVICE_SHUTDOWN"}
	}
	w.mu.Lock()
	process := w.process
	requestID := ""
	if w.current != nil {
		requestID = w.current.id
	}
	if w.invalidReason == "" {
		w.invalidReason = reasons[0]
	}
	w.mu.Unlock()
	w.processExitState = "NO_PROCESS_HANDLE"
	if process != nil {
		// stop 同步等待 cmd.Wait；done 只描述协议，不足以确认 JVM 已退出。
		process.stop()
		// 已接收的输出/能力回调完成后，才能清理会话目录或给出最终执行记录。
		<-process.readDone
		w.processExitState = "EXIT_UNCONFIRMED"
		if process.cmd.ProcessState != nil {
			w.processExitState = "EXIT_CONFIRMED"
		}
	}
	if w.processExitState == "EXIT_UNCONFIRMED" {
		w.log.Error("script worker exit could not be confirmed", "requestId", requestID, "workerId", w.id, "recycleReasons", reasons, "processExitState", w.processExitState)
		return w.processExitState
	}
	if w.directory != "" {
		if err := os.RemoveAll(w.directory); err != nil {
			w.processExitState = "CLEANUP_FAILED"
			w.log.Error("script worker directory cleanup failed", "workerId", w.id, "error", err)
		}
	}
	w.log.Info("script worker retired", "requestId", requestID, "workerId", w.id, "recycleReasons", reasons, "processExitState", w.processExitState)
	return w.processExitState
}
func (p *scriptPool) close() []*scriptWorker {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.closed = true
	workers := []*scriptWorker{}
	for _, slot := range p.slots {
		if slot.worker != nil && (slot.session == nil || slot.session.current == nil) {
			workers = append(workers, slot.worker)
		}
	}
	for _, session := range p.sessions {
		if session.retiredWorker != nil {
			workers = append(workers, session.retiredWorker)
		}
	}
	return workers
}
func (w *scriptWorker) executionDirectory() (string, error) {
	return os.MkdirTemp(filepath.Join(w.directory), "execution-")
}
