package results

import (
	"errors"
	"sort"

	q "github.com/hanger-source/agentic-olap/service/internal/protocol/query"
	"google.golang.org/protobuf/proto"
)

func portion[T any](values []T, offset, limit int32) ([]T, *int32) {
	start := min(int(offset), len(values))
	end := min(start+int(limit), len(values))
	var next *int32
	if end < len(values) {
		next = proto.Int32(int32(end))
	}
	return values[start:end], next
}
func explanation(view *q.QueryExplanationView, section string, offset, limit int32) (*q.QueryExplanationContent, int32, *int32, error) {
	if view == nil {
		return nil, 0, nil, errors.New("this result has no saved execution explanation")
	}
	content := new(q.QueryExplanationContent)
	switch section {
	case "SUMMARY":
		if offset != 0 {
			return nil, 0, nil, errors.New("summary does not accept an offset")
		}
		content.Kind = &q.QueryExplanationContent_Summary{Summary: view.Summary}
		return content, 1, nil, nil
	case "SCOPES":
		values, next := portion(view.Scopes, offset, limit)
		content.Kind = &q.QueryExplanationContent_Scopes{Scopes: &q.ExplanationScopes{Items: values, TotalItems: int32(len(view.Scopes)), NextOffset: next}}
		return content, int32(len(view.Scopes)), next, nil
	case "SOURCES":
		values, next := portion(view.Sources, offset, limit)
		content.Kind = &q.QueryExplanationContent_Sources{Sources: &q.ExplanationSources{Items: values, TotalItems: int32(len(view.Sources)), NextOffset: next}}
		return content, int32(len(view.Sources)), next, nil
	case "PLAN":
		values, next := portion(view.Plan, offset, limit)
		content.Kind = &q.QueryExplanationContent_Plan{Plan: &q.ExplanationPlan{Items: values, TotalItems: int32(len(view.Plan)), NextOffset: next}}
		return content, int32(len(view.Plan)), next, nil
	case "COMPILATION_SCALE":
		if offset != 0 {
			return nil, 0, nil, errors.New("compilation scale does not accept an offset")
		}
		content.Kind = &q.QueryExplanationContent_Scale{Scale: &q.ExplanationScale{Value: view.CompilationScale}}
		return content, 1, nil, nil
	case "EXECUTION_REQUIREMENTS":
		if offset != 0 {
			return nil, 0, nil, errors.New("execution requirements do not accept an offset")
		}
		content.Kind = &q.QueryExplanationContent_ExecutionRequirements{ExecutionRequirements: &q.ExplanationRequirementSection{Value: view.ExecutionRequirements}}
		return content, 1, nil, nil
	default:
		return nil, 0, nil, errors.New("exact saved explanation section is required")
	}
}
func (s Store) metadata(ref Reference, record *q.SavedQueryResultRecord, r *q.ResultMetadataRequest) (*q.ResultMetadataPage, error) {
	if r.Offset < 0 || r.Limit < 1 || r.Limit > 2000 {
		return nil, errors.New("metadata requires offset>=0 and limit 1..2000")
	}
	if (r.Section == "EXPLANATION") != (r.ExplanationSection != nil) || (r.ScopePath != nil && r.Section != "SCOPE_INPUT_COLUMNS") {
		return nil, errors.New("metadata selectors do not match their selected section")
	}
	content := new(q.ResultMetadataContent)
	page := &q.ResultMetadataPage{ResultReference: ref.Proto(), Section: r.Section, Content: content, Offset: r.Offset, TotalItems: 1}
	result := record.Receipt.Result
	switch r.Section {
	case "RUN":
		content.Kind = &q.ResultMetadataContent_Run{Run: runSummary(record)}
	case "SOURCE_EVIDENCE":
		receipt := record.Receipt
		content.Kind = &q.ResultMetadataContent_Source{Source: &q.ResultSourceEvidence{State: receipt.SourceState, Request: receipt.SourceRequest, ReadEvidence: receipt.SourceReadEvidence}}
	case "PUBLICATION":
		content.Kind = &q.ResultMetadataContent_Publication{Publication: publication(record)}
	case "QUERY_INTENT":
		content.Kind = &q.ResultMetadataContent_Intent{Intent: record.Intent}
	case "COLUMNS", "INPUT_COLUMNS":
		var columns []*q.BusinessColumn
		if result != nil {
			if r.Section == "COLUMNS" {
				columns = result.Columns
			} else {
				columns = result.InputColumns
			}
		}
		values, next := portion(columns, r.Offset, r.Limit)
		content.Kind = &q.ResultMetadataContent_Columns{Columns: &q.BusinessColumns{Values: values}}
		page.TotalItems, page.NextOffset = int32(len(columns)), next
	case "SCOPE_INPUT_COLUMNS":
		if r.ScopePath != nil {
			if result == nil {
				return nil, errors.New("this result has no completed input columns")
			}
			columns, ok := result.ScopeInputColumns[*r.ScopePath]
			if !ok || columns == nil {
				return nil, errors.New("saved input scope is unavailable")
			}
			values, next := portion(columns.Values, r.Offset, r.Limit)
			content.Kind = &q.ResultMetadataContent_Columns{Columns: &q.BusinessColumns{Values: values}}
			page.TotalItems, page.NextOffset = int32(len(columns.Values)), next
		} else {
			var paths []string
			if result != nil {
				for path := range result.ScopeInputColumns {
					paths = append(paths, path)
				}
			}
			sort.Strings(paths)
			selected, next := portion(paths, r.Offset, r.Limit)
			index := new(q.ResultScopeIndex)
			for _, path := range selected {
				index.Values = append(index.Values, &q.ResultScopeColumns{ScopePath: path, ColumnCount: int32(len(result.ScopeInputColumns[path].Values))})
			}
			content.Kind = &q.ResultMetadataContent_ScopeIndex{ScopeIndex: index}
			page.TotalItems, page.NextOffset = int32(len(paths)), next
		}
	case "SOURCE_SYMBOLS":
		if result == nil {
			content.Kind = &q.ResultMetadataContent_Symbols{Symbols: new(q.ResultSymbols)}
			page.TotalItems = 0
		} else {
			values, next := portion(result.SourceSymbols, r.Offset, r.Limit)
			content.Kind = &q.ResultMetadataContent_Symbols{Symbols: &q.ResultSymbols{Values: values}}
			page.TotalItems, page.NextOffset = int32(len(result.SourceSymbols)), next
		}
	case "EXPLANATION":
		value, total, next, err := explanation(record.Explanation, *r.ExplanationSection, r.Offset, r.Limit)
		if err != nil {
			return nil, err
		}
		content.Kind = &q.ResultMetadataContent_Explanation{Explanation: value}
		page.TotalItems, page.NextOffset = total, next
	default:
		return nil, errors.New("unknown saved result metadata section")
	}
	if r.Offset != 0 && (r.Section == "RUN" || r.Section == "SOURCE_EVIDENCE" || r.Section == "PUBLICATION" || r.Section == "QUERY_INTENT") {
		return nil, errors.New("this metadata section does not accept an offset")
	}
	return page, nil
}
