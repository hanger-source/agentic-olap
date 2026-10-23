package runtime

import (
	"bufio"
	"bytes"
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"sync"
	"syscall"
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
	cmd               *exec.Cmd
	input             io.WriteCloser
	output            io.ReadCloser
	maximum           int64
	transferDirectory string
	write             chan struct{}
	mu                sync.Mutex
	pending           map[string]chan *pb.Response
	scripts           map[string]chan *pb.ScriptResponse
	done              chan struct{}
	wait              chan error
	failure           error
	onWork            func(*pb.WorkRequest)
	onCapability      func(*pb.CapabilityRequest)
	onScriptOutput    func(*pb.ScriptOutput)
	logs              *processOutputBudget
	stopOnce          sync.Once
	finishOnce        sync.Once
}

func startProcess(ctx context.Context, cfg Config, launch processLaunch) (*process, *pb.Response, error) {
	if launch.Bootstrap == nil {
		return nil, nil, errors.New("runtime bootstrap is required")
	}
	transferParent := cfg.WorkDir
	if launch.Sandbox != nil {
		transferParent = launch.Sandbox.Directory
	}
	transferDirectory, err := os.MkdirTemp(transferParent, "protocol-transfer-")
	if err != nil {
		return nil, nil, err
	}
	createdTransferDirectory := transferDirectory
	started := false
	defer func() {
		if !started {
			_ = os.RemoveAll(createdTransferDirectory)
		}
	}()
	transferDirectory, err = filepath.EvalSymlinks(transferDirectory)
	if err != nil {
		return nil, nil, err
	}
	transferDirectory, err = filepath.Abs(transferDirectory)
	if err != nil {
		return nil, nil, err
	}
	launch.Bootstrap.TransferDirectory = transferDirectory
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
	p := &process{cmd: cmd, input: input, output: output, maximum: launch.MaximumBytes, transferDirectory: transferDirectory,
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
	started = true
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
		if err == nil && frame.GetFile() != nil {
			frame, err = p.consumeFrame(frame.GetFile())
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
	select {
	case <-p.write:
	case <-ctx.Done():
		return ctx.Err()
	case <-p.done:
		return p.err()
	}
	defer func() { p.write <- struct{}{} }()
	completed := make(chan error, 1)
	go func() {
		outgoing, err := p.outboundFrame(frame)
		if err == nil {
			if int64(proto.Size(outgoing)) > p.maximum {
				err = errors.New("protocol transfer reference exceeds pipe frame budget")
			} else {
				_, err = protodelim.MarshalTo(p.input, outgoing)
			}
		}
		completed <- err
	}()
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
		if p.transferDirectory != "" && p.cmd.ProcessState != nil {
			if err := os.RemoveAll(p.transferDirectory); err != nil {
				fmt.Fprintln(p.cmd.Stderr, "protocol transfer directory cleanup failed:", p.transferDirectory, err)
			}
		}
		p.fail(errors.New("runtime process stopped by owner"))
	})
}

var protocolFileName = regexp.MustCompile(`^frame-[0-9a-f]{32}\.pb$`)
var protocolFileSHA256 = regexp.MustCompile(`^[0-9a-f]{64}$`)

// The file contains the same complete official Frame that would use the pipe.
func (p *process) outboundFrame(frame *pb.Frame) (*pb.Frame, error) {
	if frame.GetFile() != nil {
		return nil, errors.New("protocol file references cannot be nested")
	}
	if int64(proto.Size(frame)) <= p.maximum {
		return frame, nil
	}
	if frame.GetBootstrap() != nil {
		return nil, errors.New("bootstrap must fit the initial inline protocol handshake")
	}
	if p.transferDirectory == "" {
		return nil, errors.New("runtime process has no private protocol transfer directory")
	}
	identity := make([]byte, 16)
	if _, err := rand.Read(identity); err != nil {
		return nil, err
	}
	name := "frame-" + hex.EncodeToString(identity) + ".pb"
	partial := filepath.Join(p.transferDirectory, name+".part")
	destination := filepath.Join(p.transferDirectory, name)
	content, err := proto.Marshal(frame)
	if err != nil {
		return nil, err
	}
	file, err := os.OpenFile(partial, os.O_WRONLY|os.O_CREATE|os.O_EXCL|syscall.O_NOFOLLOW, 0600)
	if err != nil {
		return nil, err
	}
	defer func() { _ = file.Close(); _ = os.Remove(partial) }()
	digest := sha256.New()
	count, err := io.Copy(io.MultiWriter(file, digest), bytes.NewReader(content))
	if err != nil {
		return nil, err
	}
	if err = file.Close(); err != nil {
		return nil, err
	}
	if err = os.Rename(partial, destination); err != nil {
		return nil, err
	}
	return &pb.Frame{Body: &pb.Frame_File{File: &pb.ProtocolFile{Name: name, Bytes: uint64(count), Sha256: hex.EncodeToString(digest.Sum(nil))}}}, nil
}

func (p *process) consumeFrame(reference *pb.ProtocolFile) (*pb.Frame, error) {
	if p.transferDirectory == "" {
		return nil, errors.New("runtime process has no private protocol transfer directory")
	}
	if !protocolFileName.MatchString(reference.Name) || reference.Bytes == 0 || !protocolFileSHA256.MatchString(reference.Sha256) {
		return nil, errors.New("protocol file requires an exact private basename, byte length and SHA-256")
	}
	path := filepath.Join(p.transferDirectory, reference.Name)
	before, err := os.Lstat(path)
	if err != nil {
		return nil, err
	}
	if !before.Mode().IsRegular() || uint64(before.Size()) != reference.Bytes {
		return nil, errors.New("protocol transfer file differs from its declared regular file")
	}
	file, err := os.OpenFile(path, os.O_RDONLY|syscall.O_NOFOLLOW, 0)
	if err != nil {
		return nil, err
	}
	defer file.Close()
	opened, err := file.Stat()
	if err != nil {
		return nil, err
	}
	if !opened.Mode().IsRegular() || !os.SameFile(before, opened) || opened.Size() != before.Size() {
		return nil, errors.New("protocol transfer file identity changed while opening")
	}
	defer func() {
		current, err := os.Lstat(path)
		if err == nil && current.Mode().IsRegular() && os.SameFile(opened, current) {
			if err := os.Remove(path); err != nil {
				fmt.Fprintln(p.cmd.Stderr, "protocol transfer file cleanup failed:", path, err)
			}
		}
	}()
	digest := sha256.New()
	content, err := io.ReadAll(io.TeeReader(io.NewSectionReader(file, 0, opened.Size()), digest))
	if err != nil {
		return nil, err
	}
	after, err := os.Lstat(path)
	if err != nil {
		return nil, err
	}
	if !after.Mode().IsRegular() || !os.SameFile(opened, after) || after.Size() != opened.Size() ||
		uint64(len(content)) != reference.Bytes || hex.EncodeToString(digest.Sum(nil)) != reference.Sha256 {
		return nil, errors.New("protocol transfer file identity or content changed")
	}
	frame := new(pb.Frame)
	if err := (proto.UnmarshalOptions{RecursionLimit: len(content) + 1}).Unmarshal(content, frame); err != nil {
		return nil, err
	}
	if frame.GetFile() != nil {
		return nil, errors.New("protocol file references cannot be nested")
	}
	return frame, nil
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
