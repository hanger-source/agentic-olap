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

const protocolVersion = "agentic-olap-runtime/v1"

type process struct {
	cmd        *exec.Cmd
	input      io.WriteCloser
	output     io.ReadCloser
	maximum    int64
	write      chan struct{}
	mu         sync.Mutex
	pending    map[string]chan *pb.Response
	done       chan struct{}
	wait       chan error
	failure    error
	onWork     func(*pb.WorkRequest)
	stopOnce   sync.Once
	finishOnce sync.Once
}

func startProcess(ctx context.Context, cfg Config, classpath string, mode string, heap int, environment []string,
	bootstrap *pb.Bootstrap, maximum int64, onWork func(*pb.WorkRequest)) (*process, *pb.Response, error) {
	arguments := []string{}
	if heap > 0 {
		arguments = append(arguments, fmt.Sprintf("-Xmx%dm", heap))
	}
	arguments = append(arguments, "-cp", classpath, "com.olap.prototype.runtime.ApplicationMainKt", "--mode", mode)
	cmd := exec.Command(cfg.Java, arguments...)
	cmd.Dir, cmd.Env, cmd.Stderr = cfg.WorkDir, environment, cfg.Stderr
	configureProcessGroup(cmd)
	input, err := cmd.StdinPipe()
	if err != nil {
		return nil, nil, err
	}
	output, err := cmd.StdoutPipe()
	if err != nil {
		input.Close()
		return nil, nil, err
	}
	p := &process{cmd: cmd, input: input, output: output, maximum: maximum,
		write: make(chan struct{}, 1), pending: make(map[string]chan *pb.Response),
		done: make(chan struct{}), wait: make(chan error, 1), onWork: onWork}
	p.write <- struct{}{}
	if err = cmd.Start(); err != nil {
		input.Close()
		output.Close()
		return nil, nil, err
	}
	go p.read()
	go func() {
		err := cmd.Wait()
		p.wait <- err
		if err == nil {
			p.fail(errors.New("JVM exited"))
		} else {
			p.fail(fmt.Errorf("JVM exited: %w", err))
		}
	}()
	ready := p.register("bootstrap")
	if err = p.send(ctx, &pb.Frame{Body: &pb.Frame_Bootstrap{Bootstrap: bootstrap}}); err != nil {
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
		default:
			p.fail(errors.New("unexpected JVM protocol frame"))
			return
		}
	}
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
