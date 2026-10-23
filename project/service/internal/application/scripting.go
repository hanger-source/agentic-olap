package application

import (
	"bytes"
	"context"
	"encoding/json"

	pb "github.com/hanger-source/agentic-olap/service/internal/protocol"
	"github.com/hanger-source/agentic-olap/service/internal/runtime"
	"github.com/hanger-source/agentic-olap/service/internal/wire"
)

func scriptOperation(operation string) bool {
	switch operation {
	case "execute-script", "wait-script", "cancel-script", "read-script-session", "reset-script-session", "close-script-session":
		return true
	}
	return false
}

func scriptInput(raw json.RawMessage, value any) error {
	decoder := json.NewDecoder(bytes.NewReader(raw))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(value); err != nil {
		return &wire.InputError{Cause: err}
	}
	return nil
}

func (a *Application) script(ctx context.Context, p runtime.Principal, operation string, raw json.RawMessage) (Envelope, error) {
	permission, _ := json.Marshal(map[string]string{"operation": operation})
	authorized, err := a.Runtime.Call(ctx, p, "authorize-script", permission)
	if err != nil {
		return Envelope{}, err
	}
	if !authorized.Success {
		return envelope(authorized), nil
	}
	var report *runtime.ScriptReport
	switch operation {
	case "execute-script":
		var submission runtime.ScriptSubmission
		if err := scriptInput(raw, &submission); err != nil {
			return Envelope{}, err
		}
		report, err = a.Runtime.Script(ctx, p, submission, func(callCtx context.Context, invocation *pb.CapabilityInvocation) (runtime.Reply, error) {
			return a.InvokeCapability(callCtx, p, invocation)
		})
	case "wait-script":
		var input struct {
			ExecutionID string `json:"executionId"`
			WaitMillis  int64  `json:"waitMillis"`
		}
		if err := scriptInput(raw, &input); err != nil {
			return Envelope{}, err
		}
		report, err = a.Runtime.WaitScript(ctx, p, input.ExecutionID, input.WaitMillis)
	case "cancel-script":
		var input struct {
			ExecutionID string `json:"executionId"`
		}
		if err := scriptInput(raw, &input); err != nil {
			return Envelope{}, err
		}
		report, err = a.Runtime.CancelScript(ctx, p, input.ExecutionID)
	default:
		var input struct {
			SessionID string `json:"sessionId"`
		}
		if err := scriptInput(raw, &input); err != nil {
			return Envelope{}, err
		}
		var session *runtime.ScriptSession
		switch operation {
		case "read-script-session":
			session, err = a.Runtime.ReadScriptSession(ctx, p, input.SessionID)
		case "reset-script-session":
			session, err = a.Runtime.ResetScriptSession(ctx, p, input.SessionID)
		case "close-script-session":
			session, err = a.Runtime.CloseScriptSession(ctx, p, input.SessionID)
		}
		if session == nil {
			return Envelope{}, err
		}
		if err != nil {
			return Envelope{}, err
		}
		encoded, err := json.Marshal(session)
		return Envelope{RequestID: session.SessionID, Success: true, Result: encoded, Error: json.RawMessage("null")}, err
	}
	if report == nil {
		return Envelope{}, err
	}
	return scriptEnvelope(report)
}

func scriptEnvelope(report *runtime.ScriptReport) (Envelope, error) {
	encoded, err := json.Marshal(report)
	if err != nil {
		return Envelope{}, err
	}
	// RUNNING 仅表示已受理且仍可观察；是否执行成功由最终 state 与 success 明确说明。
	response := Envelope{RequestID: report.RequestID, Success: report.State == "RUNNING" || report.Success, Result: encoded, Error: json.RawMessage("null")}
	if !response.Success {
		response.Error, _ = json.Marshal(map[string]any{"stage": "SCRIPT", "code": "SCRIPT_NOT_COMPLETED",
			"message": "代码执行未成功结束；读取实际输出、诊断及正式调用记录", "details": map[string]string{
				"sessionId": report.SessionID, "executionId": report.ExecutionID, "state": report.State}})
	}
	return response, nil
}
