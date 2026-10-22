package application

import (
	"context"
	"errors"

	g "github.com/hanger-source/agentic-olap/service/internal/protocol/governance"
	"github.com/hanger-source/agentic-olap/service/internal/results"
	"github.com/hanger-source/agentic-olap/service/internal/runtime"
	"github.com/hanger-source/agentic-olap/service/internal/wire"
	"google.golang.org/protobuf/proto"
	"google.golang.org/protobuf/types/known/anypb"
)

func (a *Application) typedAvailability(ctx context.Context, reply runtime.Reply) (runtime.Reply, error) {
	view := new(g.DeploymentAvailability)
	if err := anypb.UnmarshalTo(reply.Model, view, proto.UnmarshalOptions{RecursionLimit: a.Config.Limits.InputDepth}); err != nil {
		return reply, err
	}
	for _, check := range view.Checks {
		if err := ctx.Err(); err != nil {
			return reply, err
		}
		if check.Kind != "RESULT_READ" || check.Status != "NOT_CHECKED" {
			continue
		}
		ref := results.Reference{}
		seen := map[string]bool{}
		for _, entry := range check.Reference {
			value, ok := entry.GetValue().GetKind().(*g.DiagnosticValue_Text)
			if !ok || seen[entry.Key] {
				return reply, errors.New("availability result reference requires unique text fields")
			}
			seen[entry.Key] = true
			switch entry.Key {
			case "runId":
				ref.RunID = value.Text
			case "sha256":
				ref.SHA256 = value.Text
			default:
				return reply, errors.New("availability result reference has an unknown field")
			}
		}
		if err := ref.Validate(); err != nil {
			return reply, err
		}
		observed := a.Results.Inspect(ref)
		check.Status, check.Issues = observed.Status, nil
		for _, code := range observed.Issues {
			check.Issues = append(check.Issues, &g.DeploymentAvailabilityIssue{Code: code, Message: "saved result bytes are not readable by this deployment"})
		}
	}
	var err error
	reply.Model, err = anypb.New(view)
	return reply, err
}

// 文件可读性由持有结果目录的Go检查，语义资产可用性由正式元数据服务检查。
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
		if err = ctx.Err(); err != nil {
			return Envelope{}, err
		}
		check, ok := item.(map[string]any)
		if !ok {
			return Envelope{}, errors.New("availability check must be an object")
		}
		if check["kind"] != "RESULT_READ" || check["status"] != "NOT_CHECKED" {
			continue
		}
		ref, e := results.ReferenceFrom(check["reference"])
		if e != nil {
			return Envelope{}, e
		}
		observed := a.Results.Inspect(ref)
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
