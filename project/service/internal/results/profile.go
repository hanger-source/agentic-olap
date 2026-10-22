package results

import (
	"context"
	"errors"
	"math/big"
	"sort"

	q "github.com/hanger-source/agentic-olap/service/internal/protocol/query"
	"google.golang.org/protobuf/proto"
)

type valueCount struct {
	value *q.BusinessValue
	count int64
	row   int
}

func (s Store) profile(ctx context.Context, ref Reference, record *q.SavedQueryResultRecord, r *q.ResultProfileRequest) (*q.ResultProfile, error) {
	result := record.Receipt.Result
	if record.Receipt.Status != "SUCCEEDED" || result == nil {
		return nil, errors.New("only a successful saved result can be profiled")
	}
	indexes, err := selection(result.Columns, r.ColumnIds, true)
	if err != nil {
		return nil, err
	}
	profile := &q.ResultProfile{ResultReference: ref.Proto(), TotalRows: int64(len(result.Rows)), Publication: publication(record), Run: runSummary(record)}
	for _, index := range indexes {
		column := result.Columns[index]
		value := &q.ResultColumnProfile{Column: column}
		counts := map[string]*valueCount{}
		var minimum, maximum *big.Rat
		var minRow, maxRow int
		for i, row := range result.Rows {
			if err = ctx.Err(); err != nil {
				return nil, err
			}
			if row == nil || len(row.Values) != len(result.Columns) {
				return nil, errors.New("saved row differs from its declared columns")
			}
			cell := row.Values[index]
			kind, _, _, e := valueKind(cell)
			if e != nil {
				return nil, e
			}
			if kind == "NULL" {
				value.NullCount++
				continue
			}
			value.NonNullCount++
			raw, e := proto.MarshalOptions{Deterministic: true}.Marshal(cell)
			if e != nil {
				return nil, e
			}
			key := string(raw)
			if count := counts[key]; count != nil {
				count.count++
			} else {
				counts[key] = &valueCount{cell, 1, i}
			}
			if kind == "INTEGER" || kind == "DECIMAL" {
				scalar := cell.GetScalar()
				number, ok := new(big.Rat).SetString(scalar.Value)
				if !ok {
					return nil, errors.New("saved exact numeric value is invalid")
				}
				if minimum == nil || number.Cmp(minimum) < 0 {
					minimum, minRow = number, i
				}
				if maximum == nil || number.Cmp(maximum) > 0 {
					maximum, maxRow = number, i
				}
			}
		}
		value.DistinctEncodedNonNullValueCount = int64(len(counts))
		if len(counts) <= 20 {
			value.ValueCounts = new(q.ResultValueCounts)
			keys := make([]string, 0, len(counts))
			for key := range counts {
				keys = append(keys, key)
			}
			sort.Strings(keys)
			for _, key := range keys {
				count := counts[key]
				view, e := valueView(count.value, count.row, column.ColumnId, nil)
				if e != nil {
					return nil, e
				}
				value.ValueCounts.Values = append(value.ValueCounts.Values, &q.ResultValueCount{Value: view, Count: count.count})
			}
		}
		if minimum != nil {
			lo, e := valueView(result.Rows[minRow].Values[index], minRow, column.ColumnId, nil)
			if e != nil {
				return nil, e
			}
			hi, e := valueView(result.Rows[maxRow].Values[index], maxRow, column.ColumnId, nil)
			if e != nil {
				return nil, e
			}
			value.NumericExtrema = &q.ResultNumericExtrema{Minimum: lo, Maximum: hi}
		}
		profile.Columns = append(profile.Columns, value)
	}
	return profile, nil
}
