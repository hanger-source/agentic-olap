package runtime

import (
	"bufio"
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"sync"
	"time"

	pb "github.com/hanger-source/agentic-olap/service/internal/protocol"
	"google.golang.org/protobuf/encoding/protodelim"
	"google.golang.org/protobuf/proto"
)

const protocolVersion = "agentic-olap-runtime/v2"

type processLaunch struct {
	Classpath    string
	Mode         string
	HeapMiB      int
	Environment  []string
	Bootstrap    *pb.Bootstrap
	MaximumBytes int64
	LogBytes     int64
	Work         func(*pb.WorkRequest)
	Capability   func(*pb.CapabilityRequest)
	ScriptOutput func(*pb.ScriptOutput)
	Sandbox      *scriptSandbox
}

type process struct {
	cmd            *exec.Cmd
	input          io.WriteCloser
	output         io.ReadCloser
	maximum        int64
	write          chan struct{}
	mu             sync.Mutex
	pending        map[string]chan *pb.Response
	scripts        map[string]chan *pb.ScriptResponse
	done           chan struct{}
	wait           chan error
	failure        error
	onWork         func(*pb.WorkRequest)
	onCapability   func(*pb.CapabilityRequest)
	onScriptOutput func(*pb.ScriptOutput)
	logs           *processOutputBudget
	stopOnce       sync.Once
	finishOnce     sync.Once
}

func startProcess(ctx context.Context, cfg Config, launch processLaunch) (*process, *pb.Response, error) {
	arguments := []string{}
	if launch.HeapMiB > 0 {
		arguments = append(arguments, fmt.Sprintf("-Xmx%dm", launch.HeapMiB))
	}
	var main string
	switch launch.Mode {
	case "metadata", "worker":
		main = "com.olap.prototype.runtime.ApplicationMainKt"
	case "script":
		main = "com.olap.prototype.runtime.ScriptMainKt"
	default:
		return nil, nil, errors.New("explicit runtime process role is required")
	}
	arguments = append(arguments, "-cp", launch.Classpath, main, "--mode", launch.Mode)
	cmd := exec.Command(cfg.Java, arguments...)
	cmd.Dir, cmd.Env, cmd.Stderr = cfg.WorkDir, launch.Environment, cfg.Stderr
	if launch.Sandbox != nil {
		var err error
		cmd, err = launch.Sandbox.command(cfg.Java, arguments, cfg.Stderr)
		if err != nil {
			return nil, nil, err
		}
	}
	configureProcessGroup(cmd)
	input, err := cmd.StdinPipe()
	if err != nil {
		return nil, nil, err
	}
	output, protocolWriter, err := os.Pipe()
	if err != nil {
		input.Close()
		return nil, nil, err
	}
	cmd.Stdout = protocolWriter
	p := &process{cmd: cmd, input: input, output: output, maximum: launch.MaximumBytes,
		write: make(chan struct{}, 1), pending: make(map[string]chan *pb.Response),
		scripts: make(map[string]chan *pb.ScriptResponse),
		done:    make(chan struct{}), wait: make(chan error, 1), onWork: launch.Work, onCapability: launch.Capability, onScriptOutput: launch.ScriptOutput}
	p.write <- struct{}{}
	if launch.LogBytes > 0 {
		p.logs = &processOutputBudget{writer: cfg.Stderr, remaining: launch.LogBytes, exceeded: func() {
			p.fail(errors.New("SCRIPT_NATIVE_LOG_BUDGET_EXCEEDED"))
			go p.stop()
		}}
		cmd.Stderr = p.logs
	}
	if err = cmd.Start(); err != nil {
		input.Close()
		output.Close()
		protocolWriter.Close()
		return nil, nil, err
	}
	protocolWriter.Close()
	go p.read()
	go func() {
		err := cmd.Wait()
		p.wait <- err
		// stdout由reader读到EOF后结束，不能在进程退出时丢掉已写入的最终回执。
		if err != nil {
			fmt.Fprintln(cfg.Stderr, "JVM process exited:", launch.Mode, err)
		}
	}()
	ready := p.register("bootstrap")
	if err = p.send(ctx, &pb.Frame{Body: &pb.Frame_Bootstrap{Bootstrap: launch.Bootstrap}}); err != nil {
		p.stop()
		return nil, nil, err
	}
	select {
	case response := <-ready:
		if !response.Success {
			p.stop()
			return nil, nil, fmt.Errorf("JVM bootstrap failed: %s", response.ErrorJson)
		}
		return p, response, nil
	case <-ctx.Done():
		p.stop()
		return nil, nil, ctx.Err()
	case <-p.done:
		p.stop()
		return nil, nil, p.err()
	}
}

func (p *process) register(id string) chan *pb.Response {
	p.mu.Lock()
	defer p.mu.Unlock()
	response := make(chan *pb.Response, 1)
	p.pending[id] = response
	return response
}

func (p *process) read() {
	defer p.stop()
	reader := bufio.NewReader(p.output)
	for {
		frame := new(pb.Frame)
		length, err := binary.ReadUvarint(reader)
		if err == nil && (length > uint64(p.maximum) || length > uint64(^uint(0)>>1)) {
			err = fmt.Errorf("runtime protobuf frame exceeds byte budget: bytes=%d maximum=%d", length, p.maximum)
		}
		if err == nil {
			payload := make([]byte, int(length))
			if _, err = io.ReadFull(reader, payload); err == nil {
				if err = proto.Unmarshal(payload, frame); err != nil {
					err = fmt.Errorf("invalid runtime protobuf frame: bytes=%d prefix=%x: %w", length, payload[:min(8, len(payload))], err)
				}
			}
		}
		if err != nil {
			p.fail(err)
			p.stop()
			return
		}
		switch body := frame.Body.(type) {
		case *pb.Frame_Response:
			p.mu.Lock()
			pending := p.pending[body.Response.RequestId]
			delete(p.pending, body.Response.RequestId)
			p.mu.Unlock()
			if pending == nil {
				p.fail(errors.New("JVM response has no matching request"))
				return
			}
			pending <- body.Response
		case *pb.Frame_WorkRequest:
			if p.onWork == nil {
				p.fail(errors.New("worker attempted supervisor callback"))
				return
			}
			go p.onWork(body.WorkRequest)
		case *pb.Frame_ScriptResponse:
			p.mu.Lock()
			pending := p.scripts[body.ScriptResponse.RequestId]
			delete(p.scripts, body.ScriptResponse.RequestId)
			p.mu.Unlock()
			if pending == nil {
				p.fail(errors.New("script response has no matching request"))
				return
			}
			pending <- body.ScriptResponse
		case *pb.Frame_CapabilityRequest:
			if p.onCapability == nil {
				p.fail(errors.New("process role cannot call application capabilities"))
				return
			}
			p.onCapability(body.CapabilityRequest)
		case *pb.Frame_ScriptWorkerFault:
			p.fail(fmt.Errorf("script worker fault: requestId=%s code=%s", body.ScriptWorkerFault.RequestId, body.ScriptWorkerFault.Code))
			return
		case *pb.Frame_ScriptOutput:
			if p.onScriptOutput == nil {
				p.fail(errors.New("process role cannot emit script output"))
				return
			}
			p.onScriptOutput(body.ScriptOutput)
		default:
			p.fail(errors.New("unexpected JVM protocol frame"))
			return
		}
	}
}

func (p *process) script(ctx context.Context, request *pb.ScriptRequest) (*pb.ScriptResponse, error) {
	response := make(chan *pb.ScriptResponse, 1)
	p.mu.Lock()
	if _, exists := p.scripts[request.RequestId]; exists {
		p.mu.Unlock()
		return nil, errors.New("duplicate script request")
	}
	p.scripts[request.RequestId] = response
	p.mu.Unlock()
	if err := p.send(ctx, &pb.Frame{Body: &pb.Frame_ScriptRequest{ScriptRequest: request}}); err != nil {
		return nil, err
	}
	select {
	case value := <-response:
		return value, nil
	case <-ctx.Done():
		// 先让协作取消完成本次清理；不能响应的完整Kotlin程序才强制回收。
		control, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		defer cancel()
		if err := p.send(control, &pb.Frame{Body: &pb.Frame_Cancel{Cancel: &pb.Cancel{RequestId: request.RequestId}}}); err == nil {
			select {
			case value := <-response:
				return value, ctx.Err()
			case <-p.done:
				return nil, ctx.Err()
			case <-control.Done():
			}
		}
		p.stop()
		return nil, ctx.Err()
	case <-p.done:
		select {
		case value := <-response:
			return value, nil
		default:
		}
		return nil, p.err()
	}
}

type processOutputBudget struct {
	mu        sync.Mutex
	writer    io.Writer
	remaining int64
	exceeded  func()
}

func (b *processOutputBudget) Write(value []byte) (int, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	allowed := int64(len(value))
	if allowed > b.remaining {
		allowed = b.remaining
	}
	n, err := b.writer.Write(value[:allowed])
	b.remaining -= int64(n)
	if err != nil {
		return n, err
	}
	if int64(len(value)) > allowed {
		b.exceeded()
		return n, errors.New("script log exceeds byte budget")
	}
	return n, nil
}

func (p *process) send(ctx context.Context, frame *pb.Frame) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	if int64(proto.Size(frame)) > p.maximum {
		return errors.New("runtime protocol frame exceeds byte budget")
	}
	select {
	case <-p.write:
	case <-ctx.Done():
		return ctx.Err()
	case <-p.done:
		return p.err()
	}
	defer func() { p.write <- struct{}{} }()
	completed := make(chan error, 1)
	go func() { _, err := protodelim.MarshalTo(p.input, frame); completed <- err }()
	select {
	case err := <-completed:
		if err != nil {
			p.fail(err)
			p.stop()
		}
		return err
	case <-ctx.Done():
		p.stop()
		return ctx.Err()
	case <-p.done:
		return p.err()
	}
}

func (p *process) call(ctx context.Context, request *pb.Request, killOnCancel bool) (*pb.Response, error) {
	response := p.register(request.RequestId)
	if err := p.send(ctx, &pb.Frame{Body: &pb.Frame_Request{Request: request}}); err != nil {
		p.mu.Lock()
		delete(p.pending, request.RequestId)
		p.mu.Unlock()
		return nil, err
	}
	select {
	case value := <-response:
		return value, nil
	case <-ctx.Done():
		if killOnCancel {
			p.stop()
		} else {
			control, cancel := context.WithTimeout(context.Background(), time.Second)
			defer cancel()
			if err := p.send(control, &pb.Frame{Body: &pb.Frame_Cancel{Cancel: &pb.Cancel{RequestId: request.RequestId}}}); err != nil {
				p.stop()
			} else {
				select {
				case <-response:
				case <-p.done:
				case <-control.Done():
					p.stop()
				}
			}
		}
		return nil, ctx.Err()
	case <-p.done:
		return nil, p.err()
	}
}

func (p *process) fail(err error) {
	p.finishOnce.Do(func() { p.mu.Lock(); p.failure = err; p.mu.Unlock(); close(p.done) })
}

func (p *process) err() error { p.mu.Lock(); defer p.mu.Unlock(); return p.failure }

func (p *process) alive() bool {
	select {
	case <-p.done:
		return false
	default:
		return true
	}
}

func (p *process) stop() {
	p.stopOnce.Do(func() {
		p.input.Close()
		if p.cmd.Process != nil {
			terminateProcessGroup(p.cmd)
		}
		<-p.wait
		p.output.Close()
		p.fail(errors.New("runtime process stopped by owner"))
	})
}

func baselineEnvironment() []string {
	result := []string{}
	for _, name := range []string{"PATH", "JAVA_HOME", "LANG", "LC_ALL", "TZ", "TMPDIR"} {
		if value, ok := os.LookupEnv(name); ok {
			result = append(result, name+"="+value)
		}
	}
	return result
}
