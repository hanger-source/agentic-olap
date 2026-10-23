// Package runtime is the sole owner of JVM processes and worker admission.
package runtime

import (
	"context"
	"encoding/json"
	pb "github.com/hanger-source/agentic-olap/service/internal/protocol"
	"google.golang.org/protobuf/types/known/anypb"
	"io"
)

type Principal struct {
	Subject string
	Scopes  []string
}

type Operation struct {
	ReadOnly    bool            `json:"readOnly"`
	Description string          `json:"description"`
	Payload     json.RawMessage `json:"payload"`
}

type Ready struct {
	Operations              map[string]Operation
	ResultOperations        []string
	ConnectorAPI            string
	AuthorizationPolicyHash string
}

type ResultArtifact struct {
	RunID   string
	SHA256  string
	Content []byte
	Rows    uint64
	Save    bool
}

type Reply struct {
	RequestID   string
	Success     bool
	Result      json.RawMessage
	Error       json.RawMessage
	Artifact    *ResultArtifact
	Model       *anypb.Any
	TimingNanos map[string]int64
	Workers     []WorkerAcquisition
	Effects     []*pb.CapabilityEffect
}

type WorkerAcquisition struct {
	Kind         string `json:"kind"`
	Outcome      string `json:"outcome"`
	QueueNanos   int64  `json:"queueNanos"`
	StartupNanos int64  `json:"startupNanos"`
}

type Config struct {
	Java             string
	Distribution     string
	WorkDir          string
	Deployment       []byte
	Stderr           io.Writer
	LookupCredential func(string) (string, bool)
}

// Close stops request admission, cancels owned work, then closes metadata last.
// Context bounds shutdown; no request is replayed after a transport failure.
type Client interface {
	Start(context.Context) error
	Call(context.Context, Principal, string, []byte) (Reply, error)
	Ready() Ready
	Close(context.Context) error
}
