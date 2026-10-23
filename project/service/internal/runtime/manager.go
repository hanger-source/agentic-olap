package runtime

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/hanger-source/agentic-olap/service/internal/config"
	pb "github.com/hanger-source/agentic-olap/service/internal/protocol"
	q "github.com/hanger-source/agentic-olap/service/internal/protocol/query"
)

type deployment struct {
	Connectors []string          `json:"connectors"`
	Sources    []json.RawMessage `json:"dataSources"`
	Scripting  config.Scripting  `json:"scripting"`
	Limits     struct {
		RequestSeconds float64 `json:"requestSeconds"`
		StartupSeconds float64 `json:"startupSeconds"`
		Workers        int     `json:"workerCount"`
		InFlight       int     `json:"maxInFlight"`
		FrameBytes     int64   `json:"pendingOutputBytes"`
		InputBytes     int64   `json:"requestBytes"`
		Heap           int     `json:"workerJvmHeapMiB"`
		ResponseBytes  int64   `json:"responseBytes"`
		InputDepth     uint32  `json:"inputDepth"`
		InputNodes     uint64  `json:"inputNodes"`
	} `json:"limits"`
}

type source struct {
	configuration json.RawMessage
	credentials   map[string]string
}

type requestState struct {
	ctx     context.Context
	cancel  context.CancelFunc
	mu      sync.Mutex
	leases  map[string]*lease
	closed  bool
	workers []WorkerAcquisition
}

type slot struct {
	mu      sync.Mutex
	process *process
	key     string
}
type lease struct {
	mu       sync.Mutex
	slot     *slot
	owner    *requestState
	id       string
	released bool
}

type Manager struct {
	cfg         Config
	classpath   string
	classpaths  distributionPaths
	deployment  deployment
	limits      json.RawMessage
	metadata    *process
	ready       Ready
	sources     map[string]source
	available   chan *slot
	slots       []*slot
	capacity    chan struct{}
	mu          sync.Mutex
	requests    map[string]*requestState
	closed      bool
	started     bool
	sequence    atomic.Uint64
	scriptRuns  map[string]context.CancelFunc
	scripts     *scriptPool
	closing     chan struct{}
	closeDone   chan struct{}
	scriptWait  sync.WaitGroup
	requestWait sync.WaitGroup
}

func New(cfg Config) (*Manager, error) {
	if cfg.Java == "" || cfg.Distribution == "" || !filepath.IsAbs(cfg.WorkDir) || !json.Valid(cfg.Deployment) {
		return nil, errors.New("runtime requires explicit java, distribution, absolute work directory and deployment JSON")
	}
	classpaths, err := distributionClassPaths(cfg.Distribution)
	if err != nil {
		return nil, err
	}
	var d deployment
	if err := json.Unmarshal(cfg.Deployment, &d); err != nil {
		return nil, err
	}
	if d.Limits.Workers < 1 || d.Limits.InFlight < 1 || d.Limits.FrameBytes < 1024 || d.Limits.InputBytes < 1024 || d.Limits.StartupSeconds <= 0 || d.Limits.RequestSeconds <= 0 || d.Limits.Heap < 32 {
		return nil, errors.New("invalid runtime process budgets")
	}
	if err := d.Scripting.Validate(); err != nil {
		return nil, err
	}
	var raw map[string]json.RawMessage
	if err := json.Unmarshal(cfg.Deployment, &raw); err != nil {
		return nil, err
	}
	if cfg.Stderr == nil {
		cfg.Stderr = io.Discard
	}
	if cfg.LookupCredential == nil {
		cfg.LookupCredential = os.LookupEnv
	}
	m := &Manager{cfg: cfg, classpath: classpaths.Runtime, classpaths: classpaths, deployment: d, limits: raw["limits"], sources: make(map[string]source), requests: make(map[string]*requestState), available: make(chan *slot, d.Limits.Workers), capacity: make(chan struct{}, d.Limits.InFlight), scripts: newScriptPool(d.Scripting.WorkerCount), scriptRuns: make(map[string]context.CancelFunc), closing: make(chan struct{})}
	for i := 0; i < d.Limits.Workers; i++ {
		s := new(slot)
		m.slots = append(m.slots, s)
		m.available <- s
	}
	return m, nil
}

func (m *Manager) Start(ctx context.Context) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.closed || m.started {
		return errors.New("runtime cannot be started twice or after close")
	}
	m.started = true
	if err := os.MkdirAll(m.cfg.WorkDir, 0700); err != nil {
		return err
	}
	startup, cancel := context.WithTimeout(ctx, time.Duration(m.deployment.Limits.StartupSeconds*float64(time.Second)))
	defer cancel()
	p, response, err := startProcess(startup, m.cfg, processLaunch{Classpath: m.classpath, Mode: "metadata", Environment: baselineEnvironment(), Bootstrap: &pb.Bootstrap{Protocol: protocolVersion, DeploymentJson: m.cfg.Deployment, LimitsJson: m.limits, ConnectorManifests: m.deployment.Connectors}, MaximumBytes: m.deployment.Limits.FrameBytes, Work: m.work})
	if err != nil {
		return err
	}
	m.metadata = p
	if response.Ready == nil {
		p.stop()
		return errors.New("metadata omitted runtime readiness")
	}
	m.ready = Ready{Operations: make(map[string]Operation), ResultOperations: append([]string(nil), response.Ready.ResultOperations...), ConnectorAPI: response.Ready.ConnectorApi, AuthorizationPolicyHash: response.Ready.AuthorizationPolicyHash}
	for _, op := range response.Ready.Operations {
		if op.Name == "" || m.ready.Operations[op.Name].Description != "" {
			p.stop()
			return errors.New("duplicate or empty runtime operation")
		}
		m.ready.Operations[op.Name] = Operation{ReadOnly: op.ReadOnly, Description: op.Description, Payload: append(json.RawMessage(nil), op.PayloadJson...)}
	}
	for _, registered := range response.Ready.Sources {
		if registered.Binding == nil || int(registered.ConfigurationIndex) >= len(m.deployment.Sources) {
			p.stop()
			return errors.New("metadata returned invalid source registration")
		}
		raw := m.deployment.Sources[registered.ConfigurationIndex]
		var configured struct {
			ID          string            `json:"dataSourceId"`
			Credentials map[string]string `json:"credentialEnvironmentVariables"`
		}
		if err := json.Unmarshal(raw, &configured); err != nil {
			p.stop()
			return err
		}
		key := bindingKey(registered.Binding)
		if configured.ID != registered.Binding.DataSourceId || registered.Binding.SourceIdentityHash == "" {
			p.stop()
			return errors.New("source registration differs from deployment")
		}
		if _, exists := m.sources[key]; exists {
			p.stop()
			return errors.New("duplicate exact source binding")
		}
		m.sources[key] = source{configuration: raw, credentials: configured.Credentials}
	}
	if len(m.sources) != len(m.deployment.Sources) {
		p.stop()
		return errors.New("metadata did not register every configured source binding")
	}
	return nil
}

func (m *Manager) Ready() Ready {
	m.mu.Lock()
	defer m.mu.Unlock()
	result := Ready{Operations: make(map[string]Operation), ResultOperations: append([]string(nil), m.ready.ResultOperations...), ConnectorAPI: m.ready.ConnectorAPI, AuthorizationPolicyHash: m.ready.AuthorizationPolicyHash}
	for name, operation := range m.ready.Operations {
		operation.Payload = append(json.RawMessage(nil), operation.Payload...)
		result.Operations[name] = operation
	}
	return result
}

func (m *Manager) Call(ctx context.Context, principal Principal, operation string, payload []byte) (Reply, error) {
	if !json.Valid(payload) || int64(len(payload)) > m.deployment.Limits.InputBytes {
		return Reply{}, errors.New("invalid or oversized runtime request JSON")
	}
	return m.call(ctx, principal, &pb.Request{Operation: operation, PayloadJson: payload})
}

func (m *Manager) CallCapability(ctx context.Context, principal Principal, invocation *pb.CapabilityInvocation) (Reply, error) {
	if invocation == nil || invocation.OperationId == "" {
		return Reply{}, errors.New("registered capability invocation is required")
	}
	if err := validateProto(invocation.Input, m.deployment.Limits.InputBytes); err != nil {
		return Reply{}, err
	}
	return m.call(ctx, principal, &pb.Request{Operation: "call-capability", Capability: invocation})
}

func (m *Manager) ConfirmDelivery(ctx context.Context, principal Principal, delivery *pb.DeliveryConfirmation) (Reply, error) {
	if delivery == nil || delivery.RunId == "" || delivery.Status == pb.DeliveryStatus_DELIVERY_STATUS_UNSPECIFIED {
		return Reply{}, errors.New("exact delivery confirmation is required")
	}
	if err := validateRegisteredProto(delivery.ExecutionReceipt); err != nil {
		return Reply{}, err
	}
	return m.call(ctx, principal, &pb.Request{Operation: "complete-result-delivery", Delivery: delivery})
}

func (m *Manager) AuthorizeResult(ctx context.Context, principal Principal, operation string, reference *q.QueryResultReference) (Reply, error) {
	if reference == nil {
		return Reply{}, errors.New("exact result reference is required")
	}
	return m.call(ctx, principal, &pb.Request{Operation: "authorize-result", ResultAuthorization: &pb.ResultAuthorization{OperationId: operation, Reference: reference}})
}

func (m *Manager) call(ctx context.Context, principal Principal, request *pb.Request) (Reply, error) {
	started := time.Now()
	requestCtx, cancel := context.WithTimeout(ctx, time.Duration(m.deployment.Limits.RequestSeconds*float64(time.Second)))
	defer cancel()
	// 控制元数据不执行业务或借 worker；满载时仍须能发现、授权和停止正在执行的脚本。
	switch request.Operation {
	case "operation-discovery", "describe-script-api", "describe-operation", "authorize-script":
		if err := requestCtx.Err(); err != nil {
			return Reply{}, err
		}
	default:
		admittedContext, release, err := m.admit(requestCtx)
		if err != nil {
			return Reply{}, err
		}
		requestCtx = admittedContext
		defer release()
	}
	admitted := time.Now()
	m.mu.Lock()
	if m.closed || m.metadata == nil {
		m.mu.Unlock()
		return Reply{}, errors.New("runtime is not available")
	}
	id := fmt.Sprintf("request-%d", m.sequence.Add(1))
	state := &requestState{ctx: requestCtx, cancel: cancel, leases: make(map[string]*lease)}
	m.requests[id] = state
	m.requestWait.Add(1)
	p := m.metadata
	m.mu.Unlock()
	defer func() {
		defer m.requestWait.Done()
		m.mu.Lock()
		delete(m.requests, id)
		m.mu.Unlock()
		m.releaseRequest(state, requestCtx.Err() != nil)
	}()
	request.RequestId, request.RemainingMillis, request.Principal = id, remainingMillis(requestCtx), &pb.Principal{Subject: principal.Subject, Scopes: principal.Scopes}
	response, err := p.call(requestCtx, request, false)
	if err != nil {
		return Reply{RequestID: id}, err
	}
	reply := Reply{RequestID: id, Success: response.Success, Result: response.ResultJson, Error: response.ErrorJson, Model: response.Model, Effects: response.Effects}
	reply.TimingNanos = map[string]int64{"RUNTIME_ADMISSION": admitted.Sub(started).Nanoseconds(), "RUNTIME_METADATA_EXCHANGE": time.Since(admitted).Nanoseconds()}
	state.mu.Lock()
	reply.Workers = append([]WorkerAcquisition{}, state.workers...)
	state.mu.Unlock()
	if a := response.Artifact; a != nil {
		reply.Artifact = &ResultArtifact{RunID: a.RunId, SHA256: a.Sha256, Content: a.Content, Rows: a.Rows, Save: a.Save}
	}
	return reply, nil
}

func (m *Manager) work(request *pb.WorkRequest) {
	m.mu.Lock()
	state := m.requests[request.RequestId]
	p := m.metadata
	m.mu.Unlock()
	reply := &pb.WorkResponse{RequestId: request.RequestId, WorkId: request.WorkId}
	if state == nil {
		reply.ErrorJson = []byte(`{"code":"REQUEST_NOT_ACTIVE"}`)
	} else {
		ctx, cancel := context.WithTimeout(state.ctx, time.Duration(request.RemainingMillis)*time.Millisecond)
		err := m.performWork(ctx, state, request, reply)
		cancel()
		if err != nil {
			fmt.Fprintln(m.cfg.Stderr, "runtime work failed:", request.RequestId, request.WorkId, err)
			code := "WORKER_TRANSPORT_FAILED"
			if errors.Is(err, context.DeadlineExceeded) {
				code = "WORKER_DEADLINE_EXCEEDED"
			} else if errors.Is(err, context.Canceled) {
				code = "CANCELLED"
			}
			reply.ErrorJson, _ = json.Marshal(map[string]string{"code": code})
			reply.LeaseInvalidated = true
		}
	}
	if p != nil {
		ctx, cancel := context.WithTimeout(context.Background(), time.Second)
		defer cancel()
		if err := p.send(ctx, &pb.Frame{Body: &pb.Frame_WorkResponse{WorkResponse: reply}}); err != nil {
			fmt.Fprintln(m.cfg.Stderr, "runtime work response failed:", err)
		}
	}
}

func (m *Manager) performWork(ctx context.Context, state *requestState, request *pb.WorkRequest, reply *pb.WorkResponse) error {
	switch action := request.Action.(type) {
	case *pb.WorkRequest_Acquire:
		observed := WorkerAcquisition{Kind: action.Acquire.Kind.String(), Outcome: "FAILED"}
		defer func() {
			state.mu.Lock()
			state.workers = append(state.workers, observed)
			state.mu.Unlock()
		}()
		if action.Acquire.Kind == pb.WorkerKind_WORKER_KIND_UNSPECIFIED {
			return errors.New("worker kind is required")
		}
		key, configs, environment, err := m.workerInputs(action.Acquire)
		if err != nil {
			return err
		}
		var s *slot
		queued := time.Now()
		select {
		case s = <-m.available:
		case <-ctx.Done():
			observed.QueueNanos = time.Since(queued).Nanoseconds()
			return ctx.Err()
		}
		observed.QueueNanos = time.Since(queued).Nanoseconds()
		s.mu.Lock()
		m.mu.Lock()
		closed := m.closed
		m.mu.Unlock()
		if closed || ctx.Err() != nil {
			s.mu.Unlock()
			m.available <- s
			return errors.New("runtime request is no longer active")
		}
		if s.process == nil || !s.process.alive() || s.key != key {
			startup := time.Now()
			if s.process != nil {
				s.process.stop()
				s.process = nil
			}
			boot := &pb.Bootstrap{Protocol: protocolVersion, LimitsJson: m.limits, ConnectorManifests: m.deployment.Connectors, WorkerKind: action.Acquire.Kind, SourceConfigurationsJson: configs}
			p, _, err := startProcess(ctx, m.cfg, processLaunch{Classpath: m.classpath, Mode: "worker", HeapMiB: m.deployment.Limits.Heap, Environment: environment, Bootstrap: boot, MaximumBytes: m.deployment.Limits.FrameBytes})
			observed.StartupNanos = time.Since(startup).Nanoseconds()
			if err != nil {
				s.mu.Unlock()
				m.available <- s
				return err
			}
			s.process = p
			s.key = key
			observed.Outcome = "STARTED"
		} else {
			observed.Outcome = "REUSED"
		}
		s.mu.Unlock()
		id := fmt.Sprintf("lease-%d", m.sequence.Add(1))
		l := &lease{slot: s, owner: state, id: id}
		state.mu.Lock()
		if state.closed || ctx.Err() != nil {
			state.mu.Unlock()
			s.mu.Lock()
			s.process.stop()
			s.mu.Unlock()
			m.available <- s
			return errors.New("parent request ended during worker admission")
		}
		state.leases[id] = l
		state.mu.Unlock()
		reply.LeaseId = id
		reply.Success = true
		return nil
	case *pb.WorkRequest_Exchange:
		state.mu.Lock()
		l := state.leases[action.Exchange.LeaseId]
		state.mu.Unlock()
		if l == nil {
			return errors.New("worker lease does not belong to this request")
		}
		l.mu.Lock()
		defer l.mu.Unlock()
		if l.released {
			return errors.New("worker lease already released")
		}
		id := fmt.Sprintf("worker-%d", m.sequence.Add(1))
		command := &pb.Request{RequestId: id, RemainingMillis: remainingMillis(ctx), Operation: "worker-command"}
		if err := validateRegisteredProto(action.Exchange.CommandModel); err != nil {
			return err
		}
		command.WorkerModel = action.Exchange.CommandModel
		response, err := l.slot.process.call(ctx, command, true)
		if err != nil {
			l.slot.process.stop()
			return err
		}
		reply.Success = response.Success
		reply.ErrorJson = response.ErrorJson
		reply.Model = response.Model
		reply.WorkerFailure = response.WorkerFailure
		reply.TimingNanos = response.TimingNanos
		reply.LeaseId = l.id
		return nil
	case *pb.WorkRequest_Release:
		state.mu.Lock()
		l := state.leases[action.Release.LeaseId]
		state.mu.Unlock()
		if l == nil {
			return errors.New("worker lease does not belong to this request")
		}
		m.releaseLease(l, false)
		reply.Success = true
		return nil
	default:
		return errors.New("work request action is required")
	}
}

func (m *Manager) workerInputs(acquire *pb.AcquireWorker) (string, []byte, []string, error) {
	bindings := append([]*pb.SourceBinding(nil), acquire.Bindings...)
	sort.Slice(bindings, func(i, j int) bool { return bindingKey(bindings[i]) < bindingKey(bindings[j]) })
	configs := []json.RawMessage{}
	environment := baselineEnvironment()
	keys := []string{acquire.Kind.String()}
	seen := make(map[string]bool)
	credentials := make(map[string]string)
	for _, binding := range bindings {
		key := bindingKey(binding)
		s, ok := m.sources[key]
		if !ok || seen[key] {
			return "", nil, nil, errors.New("worker requires distinct registered exact source bindings")
		}
		seen[key] = true
		keys = append(keys, key)
		configs = append(configs, s.configuration)
		for _, name := range s.credentials {
			if strings.ContainsAny(name, "=\x00") || name == "PATH" || name == "JAVA_HOME" || name == "LANG" || name == "LC_ALL" || name == "TZ" || name == "TMPDIR" || name == "JAVA_TOOL_OPTIONS" || name == "_JAVA_OPTIONS" || name == "JDK_JAVA_OPTIONS" {
				return "", nil, nil, errors.New("credential name conflicts with process environment")
			}
			value, ok := m.cfg.LookupCredential(name)
			if !ok || value == "" {
				return "", nil, nil, errors.New("selected source credential is unavailable")
			}
			credentials[name] = value
		}
	}
	names := make([]string, 0, len(credentials))
	for name := range credentials {
		names = append(names, name)
	}
	sort.Strings(names)
	for _, name := range names {
		environment = append(environment, name+"="+credentials[name])
	}
	encoded, err := json.Marshal(configs)
	return strings.Join(keys, "\n"), encoded, environment, err
}

func bindingKey(binding *pb.SourceBinding) string {
	if binding == nil {
		return ""
	}
	return binding.DataSourceId + "\x00" + binding.SourceIdentityHash
}
func remainingMillis(ctx context.Context) uint64 {
	deadline, ok := ctx.Deadline()
	if !ok {
		return 0
	}
	left := time.Until(deadline).Milliseconds()
	if left < 1 {
		return 1
	}
	return uint64(left)
}

func (m *Manager) releaseLease(l *lease, invalidate bool) {
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.released {
		return
	}
	l.released = true
	if invalidate {
		l.slot.mu.Lock()
		if l.slot.process != nil {
			l.slot.process.stop()
		}
		l.slot.mu.Unlock()
	}
	l.owner.mu.Lock()
	delete(l.owner.leases, l.id)
	l.owner.mu.Unlock()
	m.available <- l.slot
}

func (m *Manager) releaseRequest(state *requestState, invalidate bool) {
	state.mu.Lock()
	state.closed = true
	owned := make([]*lease, 0, len(state.leases))
	for _, l := range state.leases {
		owned = append(owned, l)
	}
	state.mu.Unlock()
	for _, l := range owned {
		m.releaseLease(l, invalidate)
	}
}

func (m *Manager) Close(ctx context.Context) error {
	m.mu.Lock()
	if m.closed {
		done := m.closeDone
		m.mu.Unlock()
		select {
		case <-done:
			return nil
		case <-ctx.Done():
			return ctx.Err()
		}
	}
	m.closed = true
	m.closeDone = make(chan struct{})
	close(m.closing)
	for _, cancel := range m.scriptRuns {
		cancel()
	}
	for _, request := range m.requests {
		request.cancel()
	}
	p := m.metadata
	m.mu.Unlock()
	scriptWorkers := m.scripts.close()
	var stopped sync.WaitGroup
	stopped.Add(1)
	go func() { defer stopped.Done(); m.scriptWait.Wait(); m.requestWait.Wait() }()
	for _, worker := range scriptWorkers {
		stopped.Add(1)
		go func() { defer stopped.Done(); worker.close() }()
	}
	for _, s := range m.slots {
		stopped.Add(1)
		go func(s *slot) {
			defer stopped.Done()
			s.mu.Lock()
			defer s.mu.Unlock()
			if s.process != nil {
				s.process.stop()
			}
		}(s)
	}
	done := make(chan struct{})
	go func() { stopped.Wait(); close(done) }()
	select {
	case <-done:
	case <-ctx.Done():
		if p != nil {
			p.stop()
		}
		go func() { <-done; close(m.closeDone) }()
		return ctx.Err()
	}
	if p != nil {
		_ = p.send(ctx, &pb.Frame{Body: &pb.Frame_Shutdown{Shutdown: &pb.Shutdown{}}})
		select {
		case <-p.done:
		case <-ctx.Done():
			p.stop()
			close(m.closeDone)
			return ctx.Err()
		}
		p.stop()
	}
	close(m.closeDone)
	return nil
}
