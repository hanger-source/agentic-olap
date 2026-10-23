package application

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"time"

	"github.com/hanger-source/agentic-olap/service/internal/config"
	pb "github.com/hanger-source/agentic-olap/service/internal/protocol"
	q "github.com/hanger-source/agentic-olap/service/internal/protocol/query"
	"github.com/hanger-source/agentic-olap/service/internal/results"
	"github.com/hanger-source/agentic-olap/service/internal/runtime"
	"github.com/hanger-source/agentic-olap/service/internal/wire"
	"google.golang.org/protobuf/encoding/protojson"
	"google.golang.org/protobuf/proto"
	"google.golang.org/protobuf/types/known/anypb"
)

type Application struct {
	Runtime *runtime.Manager
	Config  config.Deployment
	Results results.Store
}
type Envelope struct {
	RequestID string          `json:"requestId"`
	Success   bool            `json:"success"`
	Result    json.RawMessage `json:"result"`
	Error     json.RawMessage `json:"error"`
}
type DeliveryFailure struct {
	RunID string
	Cause error
}

func (e *DeliveryFailure) Error() string {
	return "result delivery did not complete; inspect the exact run without re-executing"
}
func (e *DeliveryFailure) Unwrap() error { return e.Cause }
func envelope(reply runtime.Reply) Envelope {
	return Envelope{reply.RequestID, reply.Success, reply.Result, reply.Error}
}
func (a *Application) Operations(ctx context.Context, p runtime.Principal) (map[string]runtime.Operation, error) {
	reply, err := a.Runtime.Call(ctx, p, "operation-discovery", []byte(`{}`))
	if err != nil {
		return nil, err
	}
	if !reply.Success {
		return nil, errors.New("operation discovery is not authorized")
	}
	var value struct {
		Operations map[string]runtime.Operation `json:"operations"`
	}
	if err = json.Unmarshal(reply.Result, &value); err != nil {
		return nil, err
	}
	if value.Operations == nil {
		return nil, errors.New("operation discovery is incomplete")
	}
	return value.Operations, nil
}
func (a *Application) Invoke(ctx context.Context, p runtime.Principal, operation string, payload json.RawMessage) (Envelope, error) {
	switch operation {
	case "ready", "authorize-script", "authorize-result", "complete-result-delivery", "operation-discovery", "call-capability":
		return Envelope{}, &wire.InputError{Cause: errors.New("internal operation is not public")}
	}
	if _, err := wire.Object(payload, a.Config.Limits.InputDepth, a.Config.Limits.InputNodes); err != nil {
		return Envelope{}, &wire.InputError{Cause: err}
	}
	if operation == "execute-script" {
		return a.script(ctx, p, payload)
	}
	for _, name := range a.Runtime.Ready().ResultOperations {
		if name == operation {
			return a.readJSON(ctx, p, operation, payload)
		}
	}
	reply, err := a.Runtime.Call(ctx, p, operation, payload)
	if err != nil {
		return Envelope{}, err
	}
	if reply.Artifact != nil || reply.Model != nil {
		return Envelope{}, errors.New("structured read returned an unexpected model or result artifact")
	}
	if operation == "read-deployment-availability" && reply.Success {
		return a.availability(ctx, reply)
	}
	return envelope(reply), nil
}
func (a *Application) script(ctx context.Context, p runtime.Principal, raw json.RawMessage) (Envelope, error) {
	var submission runtime.ScriptSubmission
	decoder := json.NewDecoder(bytes.NewReader(raw))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&submission); err != nil {
		return Envelope{}, &wire.InputError{Cause: err}
	}
	authorized, err := a.Runtime.Call(ctx, p, "authorize-script", []byte(`{}`))
	if err != nil {
		return Envelope{}, err
	}
	if !authorized.Success {
		return envelope(authorized), nil
	}
	report, err := a.Runtime.Script(ctx, p, submission, func(callCtx context.Context, invocation *pb.CapabilityInvocation) (runtime.Reply, error) {
		return a.InvokeCapability(callCtx, p, invocation)
	})
	if report == nil {
		return Envelope{}, err
	}
	encoded, encodeErr := json.Marshal(report)
	if encodeErr != nil {
		return Envelope{}, encodeErr
	}
	response := Envelope{RequestID: report.RequestID, Success: report.Success, Result: encoded, Error: json.RawMessage("null")}
	if !report.Success {
		response.Error, _ = json.Marshal(map[string]any{"stage": "SCRIPT", "code": "SCRIPT_NOT_COMPLETED", "message": "script did not complete; inspect diagnostics and actual capability calls"})
	}
	return response, nil
}
func (a *Application) InvokeCapability(ctx context.Context, p runtime.Principal, invocation *pb.CapabilityInvocation) (runtime.Reply, error) {
	if invocation == nil {
		return runtime.Reply{}, errors.New("formal capability invocation is required")
	}
	for _, operation := range a.Runtime.Ready().ResultOperations {
		if invocation.OperationId == operation {
			request, err := results.RequestMessage(operation)
			if err != nil {
				return runtime.Reply{}, err
			}
			if err = anypb.UnmarshalTo(invocation.Input, request, proto.UnmarshalOptions{RecursionLimit: a.Config.Limits.InputDepth}); err != nil {
				return runtime.Reply{}, &wire.InputError{Cause: err}
			}
			return a.read(ctx, p, operation, request)
		}
	}
	reply, err := a.Runtime.CallCapability(ctx, p, invocation)
	if err != nil {
		return reply, err
	}
	if invocation.OperationId == "read-deployment-availability" && reply.Success {
		return a.typedAvailability(ctx, reply)
	}
	if reply.Artifact == nil {
		return reply, nil
	}
	if invocation.OperationId != "execute-query" || !reply.Success {
		return reply, errors.New("result artifact has no accepted execution")
	}
	return a.deliver(ctx, p, reply)
}
func (a *Application) readJSON(ctx context.Context, p runtime.Principal, operation string, raw []byte) (Envelope, error) {
	request, err := results.RequestMessage(operation)
	if err != nil {
		return Envelope{}, err
	}
	if err = (protojson.UnmarshalOptions{RecursionLimit: a.Config.Limits.InputDepth}).Unmarshal(raw, request); err != nil {
		return Envelope{}, &wire.InputError{Cause: err}
	}
	reply, err := a.read(ctx, p, operation, request)
	if err != nil {
		return Envelope{}, err
	}
	if reply.Success {
		reply.Result, err = protojson.MarshalOptions{EmitDefaultValues: true}.Marshal(reply.Model)
		if err != nil {
			return Envelope{}, err
		}
	}
	return envelope(reply), nil
}
func (a *Application) read(ctx context.Context, p runtime.Principal, operation string, request proto.Message) (runtime.Reply, error) {
	ref, err := results.RequestReference(request)
	if err != nil {
		return runtime.Reply{}, &wire.InputError{Cause: err}
	}
	reply, err := a.Runtime.AuthorizeResult(ctx, p, operation, ref.Proto())
	if err != nil || !reply.Success {
		return reply, err
	}
	authorized := new(q.QueryResultReference)
	if err = anypb.UnmarshalTo(reply.Model, authorized, proto.UnmarshalOptions{RecursionLimit: a.Config.Limits.InputDepth}); err != nil {
		return reply, err
	}
	if !proto.Equal(authorized, ref.Proto()) {
		return reply, errors.New("result authorization selected a different exact reference")
	}
	page, err := a.Results.Page(ctx, request)
	if err != nil {
		return reply, err
	}
	reply.Model, err = anypb.New(page)
	reply.Result = nil
	return reply, err
}
func (a *Application) deliver(ctx context.Context, p runtime.Principal, reply runtime.Reply) (runtime.Reply, error) {
	started := time.Now()
	artifact := reply.Artifact
	ref := results.Reference{RunID: artifact.RunID, SHA256: artifact.SHA256}
	receipt := new(q.QueryExecutionReceipt)
	if reply.Model == nil {
		return reply, &DeliveryFailure{ref.RunID, errors.New("execution omitted its declared receipt")}
	}
	if err := anypb.UnmarshalTo(reply.Model, receipt, proto.UnmarshalOptions{RecursionLimit: a.Config.Limits.InputDepth}); err != nil {
		return reply, &DeliveryFailure{ref.RunID, err}
	}
	confirmation := &pb.DeliveryConfirmation{RunId: ref.RunID, Sha256: ref.SHA256, Bytes: uint64(len(artifact.Content)), Rows: artifact.Rows, ExecutionReceipt: reply.Model, TimingNanos: map[string]uint64{}}
	for stage, nanos := range reply.TimingNanos {
		if nanos < 0 {
			return reply, errors.New("runtime timing is negative")
		}
		confirmation.TimingNanos[stage] = uint64(nanos)
	}
	for _, worker := range reply.Workers {
		confirmation.Workers = append(confirmation.Workers, &pb.WorkerObservation{Kind: worker.Kind, Outcome: worker.Outcome, QueueNanos: uint64(worker.QueueNanos), StartupNanos: uint64(worker.StartupNanos)})
	}
	finish := func(status pb.DeliveryStatus) (runtime.Reply, error) {
		confirmation.Status = status
		confirmation.TimingNanos["SERVICE_PREPARE_DELIVERY"] = uint64(time.Since(started).Nanoseconds())
		finalization, cancel := context.WithTimeout(context.WithoutCancel(ctx), min(5*time.Second, time.Duration(a.Config.Limits.RequestSeconds*float64(time.Second))))
		defer cancel()
		return a.Runtime.ConfirmDelivery(finalization, p, confirmation)
	}
	failed := func(cause error) (runtime.Reply, error) {
		if _, err := finish(pb.DeliveryStatus_FAILED); err != nil {
			cause = errors.Join(cause, err)
		}
		slog.Error("result delivery failed", "runId", ref.RunID, "error", cause)
		reply.Success = false
		reply.Artifact = nil
		reply.Model = nil
		reply.Error, _ = json.Marshal(map[string]any{"stage": "RESULT_DELIVERY", "code": "RESULT_DELIVERY_FAILED", "message": "result delivery failed; execution was not repeated", "details": map[string]any{"runId": ref.RunID}})
		return reply, nil
	}
	record, err := a.Results.Record(ref, artifact.Content)
	if err != nil {
		return failed(err)
	}
	preparedReceipt := proto.Clone(record.Receipt).(*q.QueryExecutionReceipt)
	preparedReceipt.Result = nil
	if receipt.RunId != ref.RunID || receipt.Result != nil || receipt.Saved || receipt.ResultReference != nil || receipt.Rows != int64(artifact.Rows) || !proto.Equal(receipt, preparedReceipt) {
		return failed(errors.New("execution receipt differs from its prepared artifact"))
	}
	delivered := proto.Clone(receipt).(*q.QueryExecutionReceipt)
	if artifact.Save {
		delivered.ResultReference = ref.Proto()
		delivered.Saved = true
	} else {
		delivered.Result = record.Receipt.Result
	}
	if proto.Size(delivered) > a.Config.Limits.ResponseBytes {
		return failed(errors.New("direct result exceeds capacity; request explicit saving"))
	}
	storeStarted := time.Now()
	if artifact.Save {
		_, err = a.Results.Save(ctx, ref, artifact.Content, func(stage string, elapsed time.Duration) {
			confirmation.TimingNanos[stage] = uint64(elapsed.Nanoseconds())
		})
	}
	if err != nil {
		return failed(err)
	}
	confirmation.TimingNanos["RESULT_STORE"] = uint64(time.Since(storeStarted).Nanoseconds())
	status := pb.DeliveryStatus_DIRECT
	if artifact.Save {
		status = pb.DeliveryStatus_SAVED
	}
	finalized, err := finish(status)
	if err != nil {
		return reply, &DeliveryFailure{ref.RunID, err}
	}
	if !finalized.Success {
		return finalized, &DeliveryFailure{ref.RunID, errors.New("delivery confirmation was rejected")}
	}
	finalized.RequestID = reply.RequestID
	finalized.Artifact = nil
	return finalized, nil
}
