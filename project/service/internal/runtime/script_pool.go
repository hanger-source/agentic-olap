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
	invalid              bool
	closeOnce            sync.Once
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
			worker.close()
		}
		started := time.Now()
		directory, err := m.scriptDirectory()
		if err == nil {
			worker = &scriptWorker{directory: directory, owner: owner, id: fmt.Sprintf("script-worker-%d", m.sequence.Add(1))}
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
				worker.close()
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
	if w.invalid || w.current != nil || !w.process.alive() {
		return errors.New("script worker cannot accept an execution")
	}
	w.current = execution
	return nil
}
func (w *scriptWorker) call(m *Manager, request *pb.CapabilityRequest) {
	w.mu.Lock()
	current := w.current
	if current == nil || request.RequestId != current.id || w.invalid {
		w.invalid = true
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
	valid := current != nil && current.id == output.RequestId && !w.invalid &&
		(output.Stream == pb.ScriptOutput_STDOUT || output.Stream == pb.ScriptOutput_STDERR)
	if !valid || int64(len(output.Content)) > current.outputRemaining {
		w.invalid = true
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
		w.process.fail(err)
		go w.process.stop()
	}
}
func (m *Manager) releaseScript(slot *scriptSlot, worker *scriptWorker, recycle bool) {
	worker.mu.Lock()
	worker.current = nil
	recycle = recycle || worker.invalid || !worker.process.alive()
	worker.mu.Unlock()
	p := m.scripts
	p.mu.Lock()
	recycle = recycle || p.closed
	if recycle {
		slot.worker = nil
	}
	// 清理完成前不让其他请求借到尚在关闭的worker。
	p.mu.Unlock()
	if recycle {
		worker.close()
	}
	p.mu.Lock()
	slot.borrowed = false
	p.notify()
	p.mu.Unlock()
}
func (w *scriptWorker) close() {
	w.closeOnce.Do(func() {
		if w.process != nil {
			w.process.stop()
		}
		if w.directory != "" {
			if err := os.RemoveAll(w.directory); err != nil {
				slog.Error("script worker directory cleanup failed", "workerId", w.id, "error", err)
			}
		}
	})
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
