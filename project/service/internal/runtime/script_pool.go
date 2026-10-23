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

// 池的容量固定；归属改变必须重建沙箱进程，不建立无界的按身份进程表。
type scriptPool struct {
	mu      sync.Mutex
	slots   []*scriptSlot
	changed chan struct{}
	closed  bool
}
type scriptSlot struct {
	worker   *scriptWorker
	borrowed bool
}
type scriptWorker struct {
	mu                   sync.Mutex
	process              *process
	directory, owner, id string
	current              *scriptExecution
	invalidReason        string
	closeOnce            sync.Once
	processExitState     string
	log                  *slog.Logger
}
type scriptExecution struct {
	ctx             context.Context
	id              string
	calls           *scriptCalls
	invoke          CapabilityHandler
	outputRemaining int64
}

func newScriptPool(count int) *scriptPool {
	p := &scriptPool{changed: make(chan struct{})}
	for range count {
		p.slots = append(p.slots, new(scriptSlot))
	}
	return p
}
func (p *scriptPool) notify() { close(p.changed); p.changed = make(chan struct{}) }
func scriptOwner(principal Principal) (string, error) {
	if principal.Subject == "" {
		return "", errors.New("authenticated script subject is required")
	}
	scopes := slices.Clone(principal.Scopes)
	slices.Sort(scopes)
	value, err := json.Marshal(Principal{Subject: principal.Subject, Scopes: slices.Compact(scopes)})
	return string(value), err
}
func (m *Manager) acquireScript(ctx context.Context, owner string) (*scriptSlot, *scriptWorker, string, time.Duration, error) {
	p := m.scripts
	for {
		p.mu.Lock()
		if p.closed {
			p.mu.Unlock()
			return nil, nil, "", 0, errors.New("script pool is closed")
		}
		var selected *scriptSlot
		for _, slot := range p.slots {
			if !slot.borrowed && slot.worker != nil && slot.worker.owner == owner && slot.worker.process.alive() {
				selected = slot
				break
			}
		}
		if selected == nil {
			for _, slot := range p.slots {
				if !slot.borrowed && slot.worker == nil {
					selected = slot
					break
				}
			}
		}
		if selected == nil {
			for _, slot := range p.slots {
				if !slot.borrowed {
					selected = slot
					break
				}
			}
		}
		if selected == nil {
			changed := p.changed
			p.mu.Unlock()
			select {
			case <-changed:
				continue
			case <-ctx.Done():
				return nil, nil, "", 0, ctx.Err()
			}
		}
		selected.borrowed = true
		worker := selected.worker
		if worker != nil && worker.owner == owner && worker.process.alive() {
			p.mu.Unlock()
			return selected, worker, "REUSED", 0, nil
		}
		selected.worker = nil
		p.mu.Unlock()
		outcome := "STARTED"
		if worker != nil {
			outcome = "REBUILT"
			reasons := []string{}
			if worker.owner != owner {
				reasons = append(reasons, "SCRIPT_OWNER_CHANGED")
			}
			if !worker.process.alive() {
				reasons = append(reasons, "SCRIPT_PROCESS_UNAVAILABLE")
			}
			worker.close(reasons...)
		}
		started := time.Now()
		directory, err := m.scriptDirectory()
		if err == nil {
			worker = &scriptWorker{directory: directory, owner: owner, id: fmt.Sprintf("script-worker-%d", m.sequence.Add(1)),
				log: slog.New(slog.NewTextHandler(m.cfg.Stderr, nil))}
			var process *process
			process, err = m.startScriptProcess(ctx, directory, func(request *pb.CapabilityRequest) { worker.call(m, request) }, func(output *pb.ScriptOutput) { worker.output(m, output) })
			worker.mu.Lock()
			worker.process = process
			worker.mu.Unlock()
		}
		p.mu.Lock()
		closed := p.closed
		if err == nil && !closed {
			selected.worker = worker
		}
		if err != nil || closed {
			selected.borrowed = false
			p.notify()
		}
		p.mu.Unlock()
		if err != nil || closed {
			if worker != nil {
				reasons := []string{}
				if err != nil {
					reasons = append(reasons, "SCRIPT_WORKER_STARTUP_FAILED")
				}
				if closed {
					reasons = append(reasons, "SCRIPT_SERVICE_SHUTDOWN")
				}
				worker.close(reasons...)
			}
			if err == nil {
				err = errors.New("script pool closed during startup")
			}
			return nil, nil, "", 0, err
		}
		return selected, worker, outcome, time.Since(started), nil
	}
}
func (w *scriptWorker) bind(execution *scriptExecution) error {
	w.mu.Lock()
	defer w.mu.Unlock()
	if w.invalidReason != "" || w.current != nil || !w.process.alive() {
		return errors.New("script worker cannot accept an execution")
	}
	w.current = execution
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
		reply = m.invokeScriptCapability(current.ctx, request, current.invoke)
		current.calls.finish(index, reply, time.Since(began))
	}
	_ = w.process.send(current.ctx, &pb.Frame{Body: &pb.Frame_CapabilityResponse{CapabilityResponse: reply}})
}

func (w *scriptWorker) output(m *Manager, output *pb.ScriptOutput) {
	w.mu.Lock()
	current := w.current
	valid := current != nil && current.id == output.RequestId && w.invalidReason == "" &&
		(output.Stream == pb.ScriptOutput_STDOUT || output.Stream == pb.ScriptOutput_STDERR)
	if !valid || int64(len(output.Content)) > current.outputRemaining {
		if w.invalidReason == "" {
			w.invalidReason = "SCRIPT_OUTPUT_SCOPE_OR_BUDGET_VIOLATION"
		}
		process := w.process
		w.mu.Unlock()
		if process != nil {
			process.fail(errors.New("SCRIPT_OUTPUT_SCOPE_OR_BUDGET_VIOLATION"))
			go process.stop()
		}
		return
	}
	current.outputRemaining -= int64(len(output.Content))
	w.mu.Unlock()
	if _, err := m.cfg.Stderr.Write(output.Content); err != nil {
		w.mu.Lock()
		if w.invalidReason == "" {
			w.invalidReason = "SCRIPT_OUTPUT_DELIVERY_FAILED"
		}
		w.mu.Unlock()
		w.process.fail(err)
		go w.process.stop()
	}
}
func (m *Manager) releaseScript(slot *scriptSlot, worker *scriptWorker, reasons []string) (string, []string) {
	worker.mu.Lock()
	requestID := ""
	if worker.current != nil {
		requestID = worker.current.id
	}
	worker.current = nil
	if worker.invalidReason != "" {
		reasons = appendScriptReason(reasons, worker.invalidReason)
	}
	if !worker.process.alive() {
		reasons = appendScriptReason(reasons, "SCRIPT_PROCESS_UNAVAILABLE")
	}
	p := m.scripts
	p.mu.Lock()
	if p.closed {
		reasons = appendScriptReason(reasons, "SCRIPT_SERVICE_SHUTDOWN")
	}
	if len(reasons) == 0 {
		slot.borrowed = false
		p.notify()
		p.mu.Unlock()
		worker.mu.Unlock()
		worker.log.Info("script worker released", "requestId", requestID, "workerId", worker.id, "workerDisposition", "RETAINED")
		return "RETAINED", []string{}
	}
	slot.worker = nil
	// 退出检查及清理完成前，不让其他请求占用这个正在归还的槽位。
	p.mu.Unlock()
	worker.mu.Unlock()
	exitState := worker.close(reasons...)
	if exitState != "EXIT_CONFIRMED" {
		reasons = appendScriptReason(reasons, "PROCESS_EXIT_UNCONFIRMED")
	}
	p.mu.Lock()
	slot.borrowed = false
	p.notify()
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
	w.closeOnce.Do(func() {
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
			w.processExitState = "EXIT_UNCONFIRMED"
			if process.cmd.ProcessState != nil {
				w.processExitState = "EXIT_CONFIRMED"
			}
		}
		if w.processExitState == "EXIT_UNCONFIRMED" {
			w.log.Error("script worker exit could not be confirmed", "requestId", requestID, "workerId", w.id, "recycleReasons", reasons, "processExitState", w.processExitState)
			return
		}
		if w.directory != "" {
			if err := os.RemoveAll(w.directory); err != nil {
				w.log.Error("script worker directory cleanup failed", "workerId", w.id, "error", err)
			}
		}
		w.log.Info("script worker retired", "requestId", requestID, "workerId", w.id, "recycleReasons", reasons, "processExitState", w.processExitState)
	})
	return w.processExitState
}
func (p *scriptPool) close() []*scriptWorker {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.closed = true
	p.notify()
	workers := []*scriptWorker{}
	for _, slot := range p.slots {
		if slot.worker != nil {
			workers = append(workers, slot.worker)
		}
	}
	return workers
}
func (w *scriptWorker) executionDirectory() (string, error) {
	return os.MkdirTemp(filepath.Join(w.directory), "execution-")
}
