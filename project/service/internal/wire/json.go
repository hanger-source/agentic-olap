// Package wire preserves JSON numbers and enforces the deployment's structural budget.
package wire

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"sort"
	"strconv"
	"unicode/utf8"
)

// InputError is attached only at a decoded public-input boundary, never to file or JVM failures.
type InputError struct{ Cause error }

func (e *InputError) Error() string { return e.Cause.Error() }
func (e *InputError) Unwrap() error { return e.Cause }
func (e *InputError) Code() string {
	var budget *jsonInputResourceError
	if errors.As(e.Cause, &budget) {
		return "JSON_INPUT_RESOURCE_LIMIT"
	}
	return "INVALID_REQUEST"
}
func (e *InputError) Stage() string {
	var budget *jsonInputResourceError
	if errors.As(e.Cause, &budget) {
		return "DECODE_DOCUMENT"
	}
	return "DECODE_INPUT"
}
func (e *InputError) Details() map[string]any {
	var unicode *jsonUnicodeError
	if errors.As(e.Cause, &unicode) {
		return map[string]any{
			"fieldPath": append([]any{}, unicode.fieldPath...),
			"expected":  "WELL_FORMED_UNICODE_TEXT", "actual": "UNPAIRED_UNICODE_SURROGATE",
		}
	}
	var budget *jsonInputResourceError
	if !errors.As(e.Cause, &budget) {
		return map[string]any{}
	}
	return map[string]any{
		"resource": budget.resource, "limit": budget.limit, "observed": budget.observed,
		"fieldPath": append([]any{}, budget.fieldPath...),
		"expected":  fmt.Sprintf("%s<=%d", budget.resource, budget.limit), "actual": strconv.FormatInt(budget.observed, 10),
	}
}

type jsonUnicodeError struct{ fieldPath []any }

func (e *jsonUnicodeError) Error() string {
	return "JSON string contains an unpaired Unicode surrogate escape"
}

type jsonInputResourceError struct {
	resource        string
	limit, observed int64
	fieldPath       []any
}

func (e *jsonInputResourceError) Error() string {
	return fmt.Sprintf("JSON input resource %s exceeds limit %d at %v: observed %d", e.resource, e.limit, e.fieldPath, e.observed)
}

func inputResourceError(resource string, limit, observed int64, fieldPath []any) error {
	return &jsonInputResourceError{resource: resource, limit: limit, observed: observed, fieldPath: append([]any{}, fieldPath...)}
}

func Decode(raw []byte, depth, nodes int) (any, error) {
	if depth <= 0 || nodes <= 0 {
		return nil, errors.New("JSON input budgets must be positive")
	}
	if !utf8.Valid(raw) {
		return nil, errors.New("JSON must be UTF-8")
	}
	d := json.NewDecoder(bytes.NewReader(raw))
	d.UseNumber()
	token := func(path []any) (json.Token, error) {
		start := d.InputOffset()
		value, err := d.Token()
		if err == nil {
			if _, text := value.(string); text {
				err = requirePairedSurrogates(raw[start:d.InputOffset()], path)
			}
		}
		return value, err
	}
	observedNodes := int64(0)
	var read func(int64, []any) (any, error)
	read = func(level int64, path []any) (any, error) {
		if level > int64(depth) {
			return nil, inputResourceError("JSON_INPUT_DEPTH", int64(depth), level, path)
		}
		observedNodes++
		if observedNodes > int64(nodes) {
			return nil, inputResourceError("JSON_INPUT_NODES", int64(nodes), observedNodes, path)
		}
		t, err := token(path)
		if err != nil {
			return nil, err
		}
		if delimiter, ok := t.(json.Delim); ok {
			switch delimiter {
			case '{':
				m := map[string]any{}
				for d.More() {
					key, err := token(path)
					if err != nil {
						return nil, err
					}
					k, ok := key.(string)
					if !ok {
						return nil, errors.New("invalid object key")
					}
					if _, exists := m[k]; exists {
						return nil, errors.New("duplicate JSON field")
					}
					v, err := read(level+1, append(path, k))
					if err != nil {
						return nil, err
					}
					m[k] = v
				}
				if _, err = token(path); err != nil {
					return nil, err
				}
				return m, nil
			case '[':
				a := []any{}
				for d.More() {
					v, err := read(level+1, append(path, len(a)))
					if err != nil {
						return nil, err
					}
					a = append(a, v)
				}
				if _, err = token(path); err != nil {
					return nil, err
				}
				return a, nil
			default:
				return nil, errors.New("invalid JSON delimiter")
			}
		}
		return t, nil
	}
	v, err := read(1, nil)
	if err != nil {
		return nil, err
	}
	if _, err = token(nil); err != io.EOF {
		return nil, errors.New("JSON must contain one complete value")
	}
	return v, nil
}

// Decoder owns JSON syntax. Check each original string token before using its
// decoded value, because encoding/json silently replaces unpaired surrogates.
func requirePairedSurrogates(token []byte, path []any) error {
	token = token[bytes.IndexByte(token, '"')+1 : len(token)-1]
	for index := 0; index < len(token); index++ {
		if token[index] != '\\' {
			continue
		}
		index++
		if token[index] != 'u' {
			continue
		}
		code, err := strconv.ParseUint(string(token[index+1:index+5]), 16, 16)
		if err != nil {
			return err
		}
		index += 4
		if code < 0xd800 || code > 0xdfff {
			continue
		}
		if code >= 0xdc00 || index+6 >= len(token) || token[index+1] != '\\' || token[index+2] != 'u' {
			return &jsonUnicodeError{fieldPath: append([]any{}, path...)}
		}
		low, err := strconv.ParseUint(string(token[index+3:index+7]), 16, 16)
		if err != nil {
			return err
		}
		if low < 0xdc00 || low > 0xdfff {
			return &jsonUnicodeError{fieldPath: append([]any{}, path...)}
		}
		index += 6
	}
	return nil
}

func Object(raw []byte, depth, nodes int) (map[string]any, error) {
	v, err := Decode(raw, depth, nodes)
	if err != nil {
		return nil, err
	}
	m, ok := v.(map[string]any)
	if !ok {
		return nil, errors.New("JSON object required")
	}
	return m, nil
}

// Canonical encodes typed result values without float conversion or HTML escaping.
func Canonical(value any) ([]byte, error) {
	var b bytes.Buffer
	err := appendValue(&b, value)
	return b.Bytes(), err
}
func appendString(b *bytes.Buffer, s string) {
	b.WriteByte('"')
	for _, r := range s {
		switch r {
		case '"', '\\':
			b.WriteByte('\\')
			b.WriteRune(r)
		case '\b':
			b.WriteString(`\b`)
		case '\f':
			b.WriteString(`\f`)
		case '\n':
			b.WriteString(`\n`)
		case '\r':
			b.WriteString(`\r`)
		case '\t':
			b.WriteString(`\t`)
		default:
			if r < 32 {
				fmt.Fprintf(b, `\u%04x`, r)
			} else {
				b.WriteRune(r)
			}
		}
	}
	b.WriteByte('"')
}
func appendValue(b *bytes.Buffer, v any) error {
	switch x := v.(type) {
	case nil:
		b.WriteString("null")
	case bool:
		b.WriteString(strconv.FormatBool(x))
	case string:
		appendString(b, x)
	case json.Number:
		if !json.Valid([]byte(x)) {
			return errors.New("invalid JSON number")
		}
		b.WriteString(string(x))
	case json.RawMessage:
		if !json.Valid(x) {
			return errors.New("invalid raw JSON")
		}
		b.Write(x)
	case int:
		b.WriteString(strconv.Itoa(x))
	case int64:
		b.WriteString(strconv.FormatInt(x, 10))
	case uint64:
		b.WriteString(strconv.FormatUint(x, 10))
	case []any:
		b.WriteByte('[')
		for i, e := range x {
			if i > 0 {
				b.WriteByte(',')
			}
			if err := appendValue(b, e); err != nil {
				return err
			}
		}
		b.WriteByte(']')
	case map[string]any:
		keys := make([]string, 0, len(x))
		for k := range x {
			keys = append(keys, k)
		}
		sort.Strings(keys)
		b.WriteByte('{')
		for i, k := range keys {
			if i > 0 {
				b.WriteByte(',')
			}
			appendString(b, k)
			b.WriteByte(':')
			if err := appendValue(b, x[k]); err != nil {
				return err
			}
		}
		b.WriteByte('}')
	default:
		raw, err := json.Marshal(v)
		if err != nil {
			return err
		}
		decoded, err := Decode(raw, 512, 10000000)
		if err != nil {
			return err
		}
		return appendValue(b, decoded)
	}
	return nil
}

// ResponseSize retains the conservative escaped, indented 256 KiB public budget.
func ResponseSize(v any) (int, error) {
	raw, err := Canonical(v)
	if err != nil {
		return 0, err
	}
	var pretty bytes.Buffer
	if err = json.Indent(&pretty, raw, "", "  "); err != nil {
		return 0, err
	}
	n := 0
	for _, r := range pretty.String() {
		if r > 0xffff {
			n += 12
		} else if r > 127 {
			n += 6
		} else {
			n++
		}
	}
	return n, nil
}
