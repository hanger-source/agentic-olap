package results

import (
	"context"
	"errors"
	"fmt"
	"unicode/utf8"

	q "github.com/hanger-source/agentic-olap/service/internal/protocol/query"
	"google.golang.org/protobuf/proto"
)

func RequestMessage(operation string) (proto.Message, error) {
	switch operation {
	case "read-query-result":
		return new(q.ResultReadRequest), nil
	case "read-query-result-metadata":
		return new(q.ResultMetadataRequest), nil
	case "read-query-result-value":
		return new(q.ResultValueRequest), nil
	case "profile-query-result":
		return new(q.ResultProfileRequest), nil
	default:
		return nil, errors.New("unregistered saved result operation")
	}
}
func RequestReference(request proto.Message) (Reference, error) {
	var ref *q.QueryResultReference
	switch r := request.(type) {
	case *q.ResultReadRequest:
		ref = r.ResultReference
	case *q.ResultMetadataRequest:
		ref = r.ResultReference
	case *q.ResultValueRequest:
		ref = r.ResultReference
	case *q.ResultProfileRequest:
		ref = r.ResultReference
	default:
		return Reference{}, errors.New("result read requires a registered concrete request")
	}
	if ref == nil {
		return Reference{}, errors.New("exact result reference is required")
	}
	value := Reference{ref.RunId, ref.Sha256}
	return value, value.Validate()
}
func ReferenceFrom(value any) (Reference, error) {
	object, ok := value.(map[string]any)
	if !ok || len(object) != 2 {
		return Reference{}, errors.New("exact result reference is required")
	}
	id, idOK := object["runId"].(string)
	hash, hashOK := object["sha256"].(string)
	if !idOK || !hashOK {
		return Reference{}, errors.New("result reference requires runId and sha256")
	}
	ref := Reference{id, hash}
	return ref, ref.Validate()
}
func (s Store) Page(ctx context.Context, request proto.Message) (proto.Message, error) {
	ref, err := RequestReference(request)
	if err != nil {
		return nil, err
	}
	record, err := s.load(ctx, ref)
	if err != nil {
		return nil, err
	}
	var result proto.Message
	switch r := request.(type) {
	case *q.ResultReadRequest:
		result, err = s.rows(ctx, ref, record, r)
	case *q.ResultMetadataRequest:
		result, err = s.metadata(ref, record, r)
	case *q.ResultValueRequest:
		result, err = s.valuePage(ref, record, r)
	case *q.ResultProfileRequest:
		result, err = s.profile(ctx, ref, record, r)
	}
	if err != nil {
		return nil, err
	}
	if err = ctx.Err(); err != nil {
		return nil, err
	}
	return result, nil
}
func runSummary(record *q.SavedQueryResultRecord) *q.ResultRunSummary {
	r := record.Receipt
	return &q.ResultRunSummary{RunId: r.RunId, Status: r.Status, Saved: true, Rows: r.Rows, ClientOutcome: r.ClientOutcome,
		StartedAt: record.StartedAt, CompletedAt: record.CompletedAt, TimingNanos: r.TimingNanos, Error: r.Error}
}
func publication(record *q.SavedQueryResultRecord) *q.ResultPublication {
	return &q.ResultPublication{Publication: record.Publication, CatalogHash: record.CatalogHash, CapabilityHash: record.CapabilityHash,
		IntentHash: record.IntentHash, QueryReference: record.QueryReference}
}
func selection(columns []*q.BusinessColumn, ids []string, explicit bool) ([]int, error) {
	if ids == nil && !explicit {
		out := make([]int, len(columns))
		for i := range out {
			out[i] = i
		}
		return out, nil
	}
	if len(ids) == 0 {
		return nil, errors.New("nonempty columnIds is required")
	}
	out := make([]int, 0, len(ids))
	seen := map[string]bool{}
	for _, id := range ids {
		if seen[id] {
			return nil, errors.New("columnIds cannot contain duplicates")
		}
		seen[id] = true
		found := -1
		for i, column := range columns {
			if column.ColumnId == id {
				found = i
				break
			}
		}
		if found < 0 {
			return nil, fmt.Errorf("result column is unavailable: %s", id)
		}
		out = append(out, found)
	}
	return out, nil
}
func valueKind(v *q.BusinessValue) (string, int, string, error) {
	if v == nil {
		return "", 0, "", errors.New("saved result contains an absent value")
	}
	switch k := v.Kind.(type) {
	case *q.BusinessValue_NullValue:
		return "NULL", 1, "VALUE", nil
	case *q.BusinessValue_Scalar:
		if k.Scalar == nil {
			return "", 0, "", errors.New("saved scalar is absent")
		}
		return k.Scalar.Type, utf8.RuneCountInString(k.Scalar.Value), "UNICODE_CODE_POINTS", nil
	case *q.BusinessValue_Array:
		if k.Array == nil {
			return "", 0, "", errors.New("saved array is absent")
		}
		return "ARRAY", len(k.Array.Values), "MEMBERS", nil
	case *q.BusinessValue_Row:
		if k.Row == nil {
			return "", 0, "", errors.New("saved row is absent")
		}
		return "ROW", len(k.Row.Values), "MEMBERS", nil
	default:
		return "", 0, "", errors.New("saved result value has no concrete variant")
	}
}
func valueView(v *q.BusinessValue, row int, column string, path []int32) (*q.ResultValueView, error) {
	kind, length, unit, err := valueKind(v)
	if err != nil {
		return nil, err
	}
	if kind == "NULL" || proto.Size(v) <= 1024 {
		return &q.ResultValueView{Kind: &q.ResultValueView_Complete{Complete: v}}, nil
	}
	bytes, err := proto.MarshalOptions{Deterministic: true}.Marshal(v)
	if err != nil {
		return nil, err
	}
	return &q.ResultValueView{Kind: &q.ResultValueView_Reference{Reference: &q.ResultValueReferenceView{
		Reference: &q.ResultValueReference{RowIndex: int32(row), ColumnId: column, Path: append([]int32{}, path...), Sha256: Hash(bytes)},
		Type:      kind, Length: int32(length), LengthUnit: unit}}}, nil
}
func (s Store) rows(ctx context.Context, ref Reference, record *q.SavedQueryResultRecord, r *q.ResultReadRequest) (*q.ResultPage, error) {
	if r.Offset < 0 || r.Limit < 0 || r.Limit > 200 {
		return nil, errors.New("result page requires offset>=0 and limit 0..200")
	}
	result := record.Receipt.Result
	var columns []*q.BusinessColumn
	var rows []*q.BusinessValues
	if result != nil {
		columns, rows = result.Columns, result.Rows
	}
	var ids []string
	if r.ColumnIds != nil {
		ids = r.ColumnIds.Values
	}
	indexes, err := selection(columns, ids, r.ColumnIds != nil)
	if err != nil {
		return nil, err
	}
	page := &q.ResultPage{ResultReference: ref.Proto(), Run: runSummary(record), Publication: publication(record), Offset: r.Offset, TotalRows: int32(len(rows))}
	for _, i := range indexes {
		page.Columns = append(page.Columns, columns[i])
	}
	start := min(int(r.Offset), len(rows))
	end := min(start+int(r.Limit), len(rows))
	for i := start; i < end; i++ {
		if err = ctx.Err(); err != nil {
			return nil, err
		}
		if rows[i] == nil || len(rows[i].Values) != len(columns) {
			return nil, errors.New("saved row differs from its declared columns")
		}
		row := &q.ResultValueViews{}
		for _, j := range indexes {
			value, e := valueView(rows[i].Values[j], i, columns[j].ColumnId, nil)
			if e != nil {
				return nil, e
			}
			row.Values = append(row.Values, value)
		}
		page.Rows = append(page.Rows, row)
	}
	if end < len(rows) {
		page.NextOffset = proto.Int32(int32(end))
	}
	return page, nil
}
func savedValue(record *q.SavedQueryResultRecord, reference *q.ResultValueReference) (*q.BusinessValue, error) {
	if reference == nil || reference.RowIndex < 0 || !hashPattern.MatchString(reference.Sha256) {
		return nil, errors.New("exact result value reference is required")
	}
	result := record.Receipt.Result
	if record.Receipt.Status != "SUCCEEDED" || result == nil || int(reference.RowIndex) >= len(result.Rows) {
		return nil, errors.New("saved result row is unavailable")
	}
	indexes, err := selection(result.Columns, []string{reference.ColumnId}, true)
	if err != nil {
		return nil, err
	}
	row := result.Rows[reference.RowIndex]
	if row == nil || len(row.Values) != len(result.Columns) {
		return nil, errors.New("saved row differs from its declared columns")
	}
	value := row.Values[indexes[0]]
	for _, position := range reference.Path {
		var members []*q.BusinessValue
		switch k := value.GetKind().(type) {
		case *q.BusinessValue_Array:
			members = k.Array.Values
		case *q.BusinessValue_Row:
			members = k.Row.Values
		default:
			return nil, errors.New("value path requires an ARRAY or ROW")
		}
		if position < 0 || int(position) >= len(members) {
			return nil, errors.New("value path is outside its saved container")
		}
		value = members[position]
	}
	bytes, err := proto.MarshalOptions{Deterministic: true}.Marshal(value)
	if err != nil {
		return nil, err
	}
	if Hash(bytes) != reference.Sha256 {
		return nil, errors.New("value reference differs from exact saved bytes")
	}
	return value, nil
}
func (s Store) valuePage(ref Reference, record *q.SavedQueryResultRecord, r *q.ResultValueRequest) (*q.ResultValuePage, error) {
	if r.Offset < 0 || r.Limit < 1 || r.Limit > 2000 {
		return nil, errors.New("value page requires offset>=0 and limit 1..2000")
	}
	value, err := savedValue(record, r.ValueReference)
	if err != nil {
		return nil, err
	}
	kind, length, unit, err := valueKind(value)
	if err != nil {
		return nil, err
	}
	page := &q.ResultValuePage{ResultReference: ref.Proto(), ValueReference: r.ValueReference, Type: kind, LengthUnit: unit, TotalLength: int32(length), Offset: r.Offset}
	start := min(int(r.Offset), length)
	end := min(start+int(r.Limit), length)
	if scalar, ok := value.Kind.(*q.BusinessValue_Scalar); ok {
		runes := []rune(scalar.Scalar.Value)
		page.Content = &q.ResultValuePage_Text{Text: string(runes[start:end])}
	} else {
		var members []*q.BusinessValue
		switch k := value.Kind.(type) {
		case *q.BusinessValue_Array:
			members = k.Array.Values
		case *q.BusinessValue_Row:
			members = k.Row.Values
		default:
			return nil, errors.New("NULL values have no continuation")
		}
		selected := &q.ResultValueViews{}
		page.Content = &q.ResultValuePage_Members{Members: selected}
		for i := start; i < end; i++ {
			path := append(append([]int32{}, r.ValueReference.Path...), int32(i))
			child, e := valueView(members[i], int(r.ValueReference.RowIndex), r.ValueReference.ColumnId, path)
			if e != nil {
				return nil, e
			}
			selected.Values = append(selected.Values, child)
		}
	}
	if end < length {
		page.NextOffset = proto.Int32(int32(end))
	}
	return page, nil
}
