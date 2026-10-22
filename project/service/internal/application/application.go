package application

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"time"

	"github.com/hanger-source/agentic-olap/service/internal/config"
	"github.com/hanger-source/agentic-olap/service/internal/results"
	"github.com/hanger-source/agentic-olap/service/internal/runtime"
	"github.com/hanger-source/agentic-olap/service/internal/wire"
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
	var v struct {
		Operations map[string]runtime.Operation `json:"operations"`
	}
	if err = json.Unmarshal(reply.Result, &v); err != nil {
		return nil, err
	}
	if v.Operations == nil {
		return nil, errors.New("authorized operation discovery is incomplete")
	}
	return v.Operations, nil
}
func (a *Application) Invoke(ctx context.Context, p runtime.Principal, operation string, payload json.RawMessage) (Envelope, error) {
	started := time.Now()
	if operation == "ready" || operation == "authorize-result" || operation == "complete-result-delivery" || operation == "operation-discovery" {
		return Envelope{}, &wire.InputError{Cause: errors.New("internal operation is not public")}
	}
	if _, err := wire.Object(payload, a.Config.Limits.InputDepth, a.Config.Limits.InputNodes); err != nil {
		return Envelope{}, &wire.InputError{Cause: err}
	}
	for _, name := range a.Runtime.Ready().ResultOperations {
		if name == operation {
			return a.read(ctx, p, operation, payload)
		}
	}
	reply, err := a.Runtime.Call(ctx, p, operation, payload)
	if err != nil {
		return Envelope{}, err
	}
	if reply.Artifact == nil {
		if operation == "read-deployment-availability" && reply.Success {
			return a.availability(ctx, reply)
		}
		return envelope(reply), nil
	}
	if operation != "execute-query" || !reply.Success {
		return Envelope{}, errors.New("result artifact has no accepted execution")
	}
	return a.deliver(ctx, p, reply, started)
}

func (a *Application) availability(ctx context.Context, reply runtime.Reply) (Envelope, error) {
	view, err := wire.Object(reply.Result, a.Config.Limits.InputDepth, a.Config.Limits.InputNodes)
	if err != nil {
		return Envelope{}, err
	}
	checks, ok := view["checks"].([]any)
	if !ok {
		return Envelope{}, errors.New("availability response requires checks")
	}
	for _, item := range checks {
		if err := ctx.Err(); err != nil {
			return Envelope{}, err
		}
		check, ok := item.(map[string]any)
		if !ok {
			return Envelope{}, errors.New("availability check must be an object")
		}
		if check["kind"] != "RESULT_READ" || check["status"] != "NOT_CHECKED" {
			continue
		}
		ref, err := results.ReferenceFrom(check["reference"])
		if err != nil {
			return Envelope{}, err
		}
		observed := a.Results.Inspect(ref)
		if err := ctx.Err(); err != nil {
			return Envelope{}, err
		}
		check["status"] = observed.Status
		issues := []any{}
		for _, code := range observed.Issues {
			issues = append(issues, map[string]any{"code": code, "message": "saved result bytes are not readable by this deployment", "details": map[string]any{}})
		}
		check["issues"] = issues
	}
	reply.Result, err = wire.Canonical(view)
	if err != nil {
		return Envelope{}, err
	}
	return envelope(reply), nil
}
func (a *Application) read(ctx context.Context, p runtime.Principal, operation string, raw json.RawMessage) (Envelope, error) {
	payload, err := wire.Object(raw, a.Config.Limits.InputDepth, a.Config.Limits.InputNodes)
	if err != nil {
		return Envelope{}, &wire.InputError{Cause: err}
	}
	ref, err := results.ReferenceFrom(payload["resultReference"])
	if err != nil {
		return Envelope{}, &wire.InputError{Cause: err}
	}
	authorization, _ := json.Marshal(map[string]any{"resultReference": ref, "operation": operation})
	reply, err := a.Runtime.Call(ctx, p, "authorize-result", authorization)
	if err != nil {
		return Envelope{}, err
	}
	if !reply.Success {
		return envelope(reply), nil
	}
	var allowed struct {
		ResultReference results.Reference `json:"resultReference"`
	}
	if err = json.Unmarshal(reply.Result, &allowed); err != nil {
		return Envelope{}, err
	}
	if allowed.ResultReference != ref {
		return Envelope{}, errors.New("result authorization returned a different exact reference")
	}
	page, err := a.Results.Page(ctx, operation, payload)
	if err != nil {
		return Envelope{}, err
	}
	encoded, err := wire.Canonical(page)
	if err != nil {
		return Envelope{}, err
	}
	reply.Result = encoded
	return envelope(reply), nil
}
func (a *Application) complete(p runtime.Principal, payload map[string]any) error {
	ctx, cancel := context.WithTimeout(context.Background(), min(5*time.Second, time.Duration(a.Config.Limits.RequestSeconds*float64(time.Second))))
	defer cancel()
	raw, err := wire.Canonical(payload)
	if err != nil {
		return err
	}
	reply, err := a.Runtime.Call(ctx, p, "complete-result-delivery", raw)
	if err != nil {
		return err
	}
	if !reply.Success {
		return errors.New("delivery manifest was not committed")
	}
	return nil
}
func (a *Application) deliver(ctx context.Context, p runtime.Principal, reply runtime.Reply, started time.Time) (Envelope, error) {
	artifact := reply.Artifact
	ref := results.Reference{RunID: artifact.RunID, SHA256: artifact.SHA256}
	observation := map[string]any{"scope": "SERVER_BEFORE_DELIVERY_CONFIRMATION", "timingNanos": reply.TimingNanos, "workers": reply.Workers}
	payload := map[string]any{"runId": ref.RunID, "resultReference": nil, "error": nil, "resultBytes": int64(0), "resultRows": artifact.Rows, "serviceObservation": observation}
	finishFailure := func(original error) (Envelope, error) {
		reply.TimingNanos["SERVICE_PREPARE_DELIVERY"] = time.Since(started).Nanoseconds()
		payload["deliveryStatus"] = "FAILED"
		payload["error"] = map[string]any{"message": "result delivery failed; execution was not repeated"}
		if err := a.complete(p, payload); err != nil {
			original = errors.Join(original, err)
		}
		slog.Error("result delivery failed", "runId", ref.RunID, "error", original)
		return Envelope{}, &DeliveryFailure{ref.RunID, original}
	}
	if err := ref.Validate(); err != nil {
		return finishFailure(err)
	}
	result, err := wire.Object(reply.Result, a.Config.Limits.InputDepth, a.Config.Limits.InputNodes)
	if err != nil {
		return finishFailure(err)
	}
	if result["runId"] != ref.RunID {
		return finishFailure(errors.New("delivery run differs from execution"))
	}
	stored := time.Now()
	if artifact.Save {
		size, err := a.Results.Save(ctx, ref, artifact.Content)
		if err != nil {
			return finishFailure(err)
		}
		payload["deliveryStatus"] = "SAVED"
		payload["resultReference"] = ref
		payload["resultBytes"] = size
		result["result"] = nil
		result["resultReference"] = ref
	} else {
		if err := a.Results.Validate(ref, artifact.Content); err != nil {
			return finishFailure(err)
		}
		payload["deliveryStatus"] = "DIRECT"
		result["result"] = json.RawMessage(artifact.Content)
		result["resultReference"] = nil
	}
	reply.TimingNanos["RESULT_STORE"] = time.Since(stored).Nanoseconds()
	timings, ok := result["timingNanos"].(map[string]any)
	if !ok {
		return finishFailure(errors.New("execution omitted its timing facts"))
	}
	for stage, nanos := range reply.TimingNanos {
		timings[stage] = nanos
	}
	observation["timingNanos"] = timings
	result["workers"] = reply.Workers
	size, err := wire.ResponseSize(result)
	if err != nil {
		return finishFailure(err)
	}
	if size > min(256*1024, a.Config.Limits.ResponseBytes) {
		return finishFailure(fmt.Errorf("direct result exceeds response capacity; request explicit result saving"))
	}
	reply.TimingNanos["SERVICE_PREPARE_DELIVERY"] = time.Since(started).Nanoseconds()
	timings["SERVICE_PREPARE_DELIVERY"] = reply.TimingNanos["SERVICE_PREPARE_DELIVERY"]
	confirmation := time.Now()
	if err = a.complete(p, payload); err != nil {
		return Envelope{}, &DeliveryFailure{ref.RunID, err}
	}
	timings["SERVICE_PREPARE_DELIVERY"] = reply.TimingNanos["SERVICE_PREPARE_DELIVERY"]
	timings["RESULT_DELIVERY_CONFIRM"] = time.Since(confirmation).Nanoseconds()
	timings["SERVICE_TOTAL"] = time.Since(started).Nanoseconds()
	if size, err := wire.ResponseSize(result); err != nil || size > min(256*1024, a.Config.Limits.ResponseBytes) {
		if err == nil {
			err = errors.New("execution response exceeds capacity after delivery confirmation; inspect the recorded run")
		}
		return Envelope{}, &DeliveryFailure{ref.RunID, err}
	}
	reply.Result, err = wire.Canonical(result)
	if err != nil {
		return Envelope{}, &DeliveryFailure{ref.RunID, err}
	}
	return envelope(reply), nil
}
