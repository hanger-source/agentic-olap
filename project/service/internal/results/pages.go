package results

import (
	"context"
	"encoding/json"
	"errors"
	"math/big"
	"sort"
	"strconv"
	"unicode/utf8"

	"github.com/hanger-source/agentic-olap/service/internal/wire"
)

const responseBudget = 256 * 1024

func sortMembers(items []any) {
	sort.Slice(items, func(i, j int) bool {
		return items[i].(map[string]any)["key"].(string) < items[j].(map[string]any)["key"].(string)
	})
}
func encoded(v any) []byte { raw, _ := wire.Canonical(v); return raw }
func bounded(v map[string]any) (map[string]any, error) {
	n, err := wire.ResponseSize(v)
	if err != nil {
		return nil, err
	}
	if n > responseBudget {
		return nil, errors.New("result response exceeds 256 KiB; select fewer columns or read metadata in pages")
	}
	return v, nil
}
func tooLarge(v any) bool { n, err := wire.ResponseSize(v); return err != nil || n > responseBudget }
func metadataView(v any, path []any, member bool) any {
	raw := encoded(v)
	if len(raw) <= 1024 {
		return v
	}
	if member {
		n, err := wire.ResponseSize(map[string]any{"members": []any{map[string]any{"key": path[len(path)-1], "value": v}}})
		if err == nil && n <= 240*1024 {
			return v
		}
	}
	kind, length, unit := "SCALAR", 1, "VALUE"
	switch x := v.(type) {
	case map[string]any:
		kind, length, unit = "OBJECT", len(x), "FIELDS"
	case []any:
		kind, length, unit = "ARRAY", len(x), "MEMBERS"
	case string:
		kind, length, unit = "TEXT", utf8.RuneCountInString(x), "UNICODE_CODE_POINTS"
	}
	return map[string]any{"metadataReference": map[string]any{"path": path, "sha256": Hash(raw)}, "kind": kind, "length": length, "lengthUnit": unit}
}
func columnView(c map[string]any, i int) map[string]any {
	t := c["type"].(map[string]any)
	symbol := c["symbol"].(map[string]any)
	summary := map[string]any{"kind": t["kind"], "nullable": t["nullable"]}
	if t["kind"] == "SCALAR" {
		for _, k := range []string{"name", "precision", "scale"} {
			summary[k] = t[k]
		}
	} else if t["kind"] == "ROW" {
		summary["fieldCount"] = len(t["fields"].([]any))
	}
	p := []any{"result", "columns", i}
	return map[string]any{"columnId": c["columnId"], "displayName": metadataView(c["displayName"], appendPath(p, "displayName"), false), "name": metadataView(symbol["name"], appendPath(p, "symbol", "name"), false), "symbolId": metadataView(symbol["id"], appendPath(p, "symbol", "id"), false), "typeSummary": summary, "type": metadataView(t, appendPath(p, "type"), false), "symbol": metadataView(symbol, appendPath(p, "symbol"), false)}
}
func appendPath(p []any, tail ...any) []any { return append(append([]any{}, p...), tail...) }
func integer(p map[string]any, key string, defaultValue, min, max int) (int, error) {
	v, ok := p[key]
	if !ok {
		return defaultValue, nil
	}
	n, ok := v.(json.Number)
	if !ok {
		return 0, errors.New("integer pagination required")
	}
	i, err := strconv.ParseInt(string(n), 10, 64)
	if err != nil || i < int64(min) || i > int64(max) {
		return 0, errors.New("pagination outside allowed range")
	}
	return int(i), nil
}
func keys(p map[string]any, required, optional []string) error {
	allowed := map[string]bool{}
	for _, k := range required {
		allowed[k] = true
		if _, ok := p[k]; !ok {
			return errors.New("required result parameter missing")
		}
	}
	for _, k := range optional {
		allowed[k] = true
	}
	for k := range p {
		if !allowed[k] {
			return errors.New("unknown result parameter")
		}
	}
	return nil
}
func indexes(columns []any, selection any, required bool) ([]int, error) {
	if selection == nil && !required {
		out := make([]int, len(columns))
		for i := range out {
			out[i] = i
		}
		return out, nil
	}
	items, ok := selection.([]any)
	if !ok || len(items) == 0 {
		return nil, errors.New("explicit result column IDs required")
	}
	out := []int{}
	seen := map[string]bool{}
	for _, item := range items {
		id, ok := item.(string)
		if !ok || seen[id] {
			return nil, errors.New("invalid or duplicate result column")
		}
		seen[id] = true
		found := -1
		for i, c := range columns {
			if c.(map[string]any)["columnId"] == id {
				found = i
				break
			}
		}
		if found < 0 {
			return nil, errors.New("column is outside exact result schema")
		}
		out = append(out, found)
	}
	return out, nil
}

func (s Store) Page(ctx context.Context, operation string, p map[string]any) (map[string]any, error) {
	required, optional := []string{"resultReference"}, []string{}
	switch operation {
	case "read-query-result":
		optional = []string{"offset", "limit", "columnIds"}
	case "read-query-result-metadata":
		required = append(required, "path")
		optional = []string{"offset", "limit"}
	case "read-query-result-value":
		required = append(required, "valueReference")
		optional = []string{"offset", "limit"}
	case "profile-query-result":
		required = append(required, "columnIds")
	default:
		return nil, &wire.InputError{Cause: errors.New("unsupported result read operation")}
	}
	if err := keys(p, required, optional); err != nil {
		return nil, &wire.InputError{Cause: err}
	}
	ref, err := ReferenceFrom(p["resultReference"])
	if err != nil {
		return nil, &wire.InputError{Cause: err}
	}
	content, err := s.Read(ref)
	if err != nil {
		return nil, err
	}
	if err = ctx.Err(); err != nil {
		return nil, err
	}
	var page map[string]any
	switch operation {
	case "read-query-result":
		page, err = readPage(ctx, content, ref, p)
	case "read-query-result-metadata":
		page, err = metadataPage(ctx, content, ref, p)
	case "read-query-result-value":
		page, err = valuePage(ctx, content, ref, p)
	default:
		page, err = profile(ctx, content, ref, p)
	}
	if err != nil && ctx.Err() == nil {
		return nil, &wire.InputError{Cause: err}
	}
	return page, err
}

func readPage(ctx context.Context, content map[string]any, ref Reference, p map[string]any) (map[string]any, error) {
	offset, err := integer(p, "offset", 0, 0, int(^uint(0)>>1))
	if err != nil {
		return nil, err
	}
	limit, err := integer(p, "limit", 50, 0, 200)
	if err != nil {
		return nil, err
	}
	columns, rows := []any{}, []any{}
	if frame, ok := content["result"].(map[string]any); ok {
		columns = frame["columns"].([]any)
		rows = frame["rows"].([]any)
	}
	selected, err := indexes(columns, p["columnIds"], false)
	if err != nil {
		return nil, err
	}
	metadata := map[string]any{}
	for k, v := range content {
		if k != "result" {
			metadata[k] = metadataView(v, []any{k}, false)
		}
	}
	views := []any{}
	for _, i := range selected {
		views = append(views, columnView(columns[i].(map[string]any), i))
	}
	page := map[string]any{"formatVersion": "olap-query-result-page/v2", "resultReference": ref, "metadata": metadata, "columns": views, "rows": []any{}, "offset": offset, "totalRows": len(rows), "nextOffset": nil}
	if offset < len(rows) {
		page["nextOffset"] = offset
	}
	if _, err = bounded(page); err != nil {
		return nil, err
	}
	out := []any{}
	for i := offset; i < len(rows) && i-offset < limit; i++ {
		if err = ctx.Err(); err != nil {
			return nil, err
		}
		values := []any{}
		row := rows[i].([]any)
		for _, j := range selected {
			values = append(values, valueView(row[j].(map[string]any), i, columns[j].(map[string]any)["columnId"].(string), []any{}, false))
		}
		out = append(out, values)
		page["rows"] = out
		page["nextOffset"] = nil
		if i+1 < len(rows) {
			page["nextOffset"] = i + 1
		}
		if tooLarge(page) {
			out = out[:len(out)-1]
			page["rows"] = out
			page["nextOffset"] = i
			if len(out) == 0 {
				return nil, errors.New("one result row exceeds response capacity; select fewer columns")
			}
			break
		}
	}
	return bounded(page)
}

func metadataPage(ctx context.Context, content map[string]any, ref Reference, p map[string]any) (map[string]any, error) {
	path, ok := p["path"].([]any)
	if !ok || len(path) < 1 || len(path) > 256 {
		return nil, errors.New("explicit bounded metadata path required")
	}
	root, ok := path[0].(string)
	if !ok {
		return nil, errors.New("metadata root must be a field")
	}
	if root == "result" {
		if len(path) < 2 {
			return nil, errors.New("result rows use value reads")
		}
		allowed := map[string]bool{"columns": true, "inputColumns": true, "scopeInputColumns": true, "sourceSymbols": true}
		name, ok := path[1].(string)
		if !ok || !allowed[name] {
			return nil, errors.New("metadata path must identify schema; rows use value reads")
		}
	}
	node := any(content)
	for _, part := range path {
		switch current := node.(type) {
		case map[string]any:
			k, ok := part.(string)
			if !ok {
				return nil, errors.New("metadata object path requires a field")
			}
			node, ok = current[k]
			if !ok {
				return nil, errors.New("metadata path is outside exact result")
			}
		case []any:
			n, err := pathIndex(part)
			if err != nil || n >= len(current) {
				return nil, errors.New("metadata array path is outside exact result")
			}
			node = current[n]
		default:
			return nil, errors.New("metadata path is outside exact result")
		}
	}
	offset, err := integer(p, "offset", 0, 0, int(^uint(0)>>1))
	if err != nil {
		return nil, err
	}
	limit, err := integer(p, "limit", 50, 0, 2000)
	if err != nil {
		return nil, err
	}
	kind, length, unit := "SCALAR", 1, "VALUE"
	items := []any{}
	switch x := node.(type) {
	case map[string]any:
		kind, length, unit = "OBJECT", len(x), "FIELDS"
		for k, v := range x {
			items = append(items, map[string]any{"key": k, "value": v})
		}
		sortMembers(items)
	case []any:
		kind, length, unit = "ARRAY", len(x), "MEMBERS"
		for i, v := range x {
			items = append(items, map[string]any{"key": i, "value": v})
		}
	case string:
		kind, length, unit = "TEXT", utf8.RuneCountInString(x), "UNICODE_CODE_POINTS"
	}
	page := map[string]any{"formatVersion": "olap-query-result-metadata-page/v1", "resultReference": ref, "metadataReference": map[string]any{"path": path, "sha256": Hash(encoded(node))}, "kind": kind, "lengthUnit": unit, "totalLength": length, "offset": offset, "nextOffset": nil}
	if offset < length {
		page["nextOffset"] = offset
	}
	if kind == "OBJECT" || kind == "ARRAY" {
		members := []any{}
		page["members"] = members
		for i := offset; i < length && i-offset < limit; i++ {
			if err = ctx.Err(); err != nil {
				return nil, err
			}
			item := items[i].(map[string]any)
			members = append(members, map[string]any{"key": item["key"], "value": metadataView(item["value"], appendPath(path, item["key"]), true)})
			page["members"] = members
			page["nextOffset"] = nil
			if i+1 < length {
				page["nextOffset"] = i + 1
			}
			if tooLarge(page) {
				members = members[:len(members)-1]
				page["members"] = members
				page["nextOffset"] = i
				if len(members) == 0 {
					return nil, errors.New("metadata member reference exceeds response capacity")
				}
				break
			}
		}
	} else if kind == "TEXT" {
		runes := []rune(node.(string))
		start := min(offset, length)
		end := min(start+limit, length)
		page["text"] = string(runes[start:end])
		page["nextOffset"] = nil
		if end < length {
			page["nextOffset"] = end
		}
	} else if offset == 0 && limit > 0 {
		page["value"] = node
		page["nextOffset"] = nil
	}
	return bounded(page)
}

func pathIndex(v any) (int, error) {
	switch n := v.(type) {
	case int:
		if n >= 0 {
			return n, nil
		}
	case json.Number:
		i, err := strconv.ParseInt(string(n), 10, 64)
		if err == nil && i >= 0 && i <= int64(int(^uint(0)>>1)) {
			return int(i), nil
		}
	}
	return 0, errors.New("nonnegative path index required")
}
func valueView(cell map[string]any, row int, column string, path []any, member bool) any {
	raw := encoded(cell)
	if cell["type"] == "NULL" || len(raw) <= 1024 {
		return cell
	}
	if member {
		n, e := wire.ResponseSize(map[string]any{"members": []any{cell}})
		if e == nil && n <= 240*1024 {
			return cell
		}
	}
	length, unit := 0, "UNICODE_CODE_POINTS"
	if cell["type"] == "ARRAY" || cell["type"] == "ROW" {
		length = len(cell["value"].([]any))
		unit = "MEMBERS"
	} else {
		length = utf8.RuneCountInString(cell["value"].(string))
	}
	return map[string]any{"type": cell["type"], "valueReference": map[string]any{"rowIndex": row, "columnId": column, "path": path, "sha256": Hash(raw)}, "length": length, "lengthUnit": unit}
}

func valuePage(ctx context.Context, c map[string]any, ref Reference, p map[string]any) (map[string]any, error) {
	if c["status"] != "SUCCEEDED" {
		return nil, errors.New("only successful result data has readable values")
	}
	v, ok := p["valueReference"].(map[string]any)
	if !ok {
		return nil, errors.New("value reference required")
	}
	if err := keys(v, []string{"rowIndex", "columnId", "path", "sha256"}, nil); err != nil {
		return nil, err
	}
	row, err := integer(v, "rowIndex", -1, 0, int(^uint(0)>>1))
	if err != nil {
		return nil, err
	}
	column, ok := v["columnId"].(string)
	if !ok {
		return nil, errors.New("column ID required")
	}
	path, ok := v["path"].([]any)
	if !ok || len(path) > 256 {
		return nil, errors.New("bounded value path required")
	}
	frame := c["result"].(map[string]any)
	columns, rows := frame["columns"].([]any), frame["rows"].([]any)
	selected, err := indexes(columns, []any{column}, true)
	if err != nil || row < 0 || row >= len(rows) {
		return nil, errors.New("value reference is outside exact result")
	}
	cell := rows[row].([]any)[selected[0]].(map[string]any)
	for _, part := range path {
		n, e := pathIndex(part)
		if e != nil || (cell["type"] != "ARRAY" && cell["type"] != "ROW") {
			return nil, errors.New("value path is outside typed members")
		}
		members := cell["value"].([]any)
		if n >= len(members) {
			return nil, errors.New("value path is outside typed members")
		}
		cell = members[n].(map[string]any)
	}
	if Hash(encoded(cell)) != v["sha256"] {
		return nil, errors.New("value reference hash mismatch")
	}
	if cell["type"] == "NULL" {
		return nil, errors.New("NULL has no paginated value")
	}
	offset, err := integer(p, "offset", 0, 0, int(^uint(0)>>1))
	if err != nil {
		return nil, err
	}
	limit, err := integer(p, "limit", 200, 1, 2000)
	if err != nil {
		return nil, err
	}
	structured := cell["type"] == "ARRAY" || cell["type"] == "ROW"
	length := 0
	unit := "UNICODE_CODE_POINTS"
	if structured {
		length = len(cell["value"].([]any))
		unit = "MEMBERS"
	} else {
		length = utf8.RuneCountInString(cell["value"].(string))
	}
	page := map[string]any{"formatVersion": "olap-query-result-value-page/v1", "resultReference": ref, "valueReference": v, "type": cell["type"], "lengthUnit": unit, "totalLength": length, "offset": offset, "nextOffset": nil}
	if offset < length {
		page["nextOffset"] = offset
	}
	if structured {
		members := []any{}
		page["members"] = members
		raw := cell["value"].([]any)
		for i := offset; i < length && i-offset < limit; i++ {
			if err = ctx.Err(); err != nil {
				return nil, err
			}
			members = append(members, valueView(raw[i].(map[string]any), row, column, appendPath(path, i), true))
			page["members"] = members
			page["nextOffset"] = nil
			if i+1 < length {
				page["nextOffset"] = i + 1
			}
			if tooLarge(page) {
				members = members[:len(members)-1]
				page["members"] = members
				page["nextOffset"] = i
				if len(members) == 0 {
					return nil, errors.New("value member reference exceeds response capacity")
				}
				break
			}
		}
	} else {
		runes := []rune(cell["value"].(string))
		start := min(offset, length)
		end := min(start+limit, length)
		page["text"] = string(runes[start:end])
		page["nextOffset"] = nil
		if end < length {
			page["nextOffset"] = end
		}
	}
	return bounded(page)
}

func profile(ctx context.Context, c map[string]any, ref Reference, p map[string]any) (map[string]any, error) {
	if c["status"] != "SUCCEEDED" {
		return nil, errors.New("only successful result data can be profiled")
	}
	frame := c["result"].(map[string]any)
	columns, rows := frame["columns"].([]any), frame["rows"].([]any)
	selected, err := indexes(columns, p["columnIds"], true)
	if err != nil {
		return nil, err
	}
	profiles := []any{}
	for _, i := range selected {
		counts := map[string]int{}
		representatives := map[string]int{}
		order := []string{}
		nulls := 0
		var low, high *big.Rat
		lowRow, highRow := -1, -1
		numeric := true
		distinct := 0
		for j, row := range rows {
			if err = ctx.Err(); err != nil {
				return nil, err
			}
			cell := row.([]any)[i].(map[string]any)
			key := string(encoded(cell))
			if counts[key] == 0 {
				order = append(order, key)
				representatives[key] = j
			}
			counts[key]++
			if cell["type"] == "NULL" {
				nulls++
				continue
			}
			if counts[key] > 1 {
				continue
			}
			distinct++
			if cell["type"] != "INTEGER" && cell["type"] != "DECIMAL" {
				numeric = false
				continue
			}
			text, ok := cell["value"].(string)
			if !ok {
				return nil, errors.New("validated numeric encoding must remain text")
			}
			number, ok := new(big.Rat).SetString(text)
			if !ok {
				return nil, errors.New("invalid exact numeric encoding")
			}
			if low == nil || number.Cmp(low) < 0 {
				low = new(big.Rat).Set(number)
				lowRow = j
			}
			if high == nil || number.Cmp(high) > 0 {
				high = new(big.Rat).Set(number)
				highRow = j
			}
		}
		column := columns[i].(map[string]any)
		view := func(row int) any {
			return valueView(rows[row].([]any)[i].(map[string]any), row, column["columnId"].(string), []any{}, false)
		}
		var values, extrema any
		if len(order) <= 20 {
			a := []any{}
			for _, key := range order {
				a = append(a, map[string]any{"value": view(representatives[key]), "count": counts[key]})
			}
			values = a
		}
		if numeric && lowRow >= 0 {
			extrema = map[string]any{"min": view(lowRow), "max": view(highRow)}
		}
		profiles = append(profiles, map[string]any{"column": columnView(column, i), "nullCount": nulls, "nonNullCount": len(rows) - nulls, "distinctEncodedNonNullValueCount": distinct, "valueCounts": values, "numericExtrema": extrema})
	}
	page := map[string]any{"formatVersion": "olap-query-result-profile/v2", "resultReference": ref, "totalRows": len(rows), "columns": profiles}
	for _, field := range []string{"queryReference", "requestId", "coordinate", "catalogHash", "capabilityHash", "queryScope", "startedAt", "completedAt"} {
		page[field] = metadataView(c[field], []any{field}, false)
	}
	return bounded(page)
}
