package wire

import (
	"reflect"
	"testing"
)

func TestDecodeRejectsUnpairedUnicodeEscapes(t *testing.T) {
	for _, input := range []struct {
		name string
		raw  string
		path []any
	}{
		{"high value", `{"source":"before \uD800 after"}`, []any{"source"}},
		{"low value", `{"source":"\uDC00"}`, []any{"source"}},
		{"consecutive high values", `{"source":"\uD800\uD801"}`, []any{"source"}},
		{"array value", `{"values":["valid","\uDC00"]}`, []any{"values", 1}},
		{"high key", `{"\uD800":"value"}`, []any{}},
		{"low key", `{"\uDC00":"value"}`, []any{}},
		{"consecutive high key", `{"container":{"\uD800\uD801":"value"}}`, []any{"container"}},
	} {
		t.Run(input.name, func(t *testing.T) {
			value, err := Decode([]byte(input.raw), 8, 100)
			if err == nil {
				t.Fatalf("unpaired Unicode escape was accepted and decoded as %#v", value)
			}
			details := (&InputError{Cause: err}).Details()
			if !reflect.DeepEqual(details["fieldPath"], input.path) {
				t.Fatalf("Unicode failure did not identify the containing input: got %#v; want %#v", details["fieldPath"], input.path)
			}
			if details["expected"] != "WELL_FORMED_UNICODE_TEXT" || details["actual"] != "UNPAIRED_UNICODE_SURROGATE" {
				t.Fatalf("Unicode failure did not explain the representation problem: %#v", details)
			}
		})
	}
}

func TestDecodePreservesUnicodeAndLiteralEscapes(t *testing.T) {
	const input = `{
		"paired": "before \ud83d\ude00 after",
		"utf8": "中文 café 😀",
		"literal": "\\uD800",
		"quoted": "\"\\uD800\"",
		"\uD83D\uDE00": "\\uDC00",
		"\\uD800": "literal key",
		"正常": "原文"
	}`
	value, err := Decode([]byte(input), 8, 100)
	if err != nil {
		t.Fatal(err)
	}
	expected := map[string]any{
		"paired":  "before 😀 after",
		"utf8":    "中文 café 😀",
		"literal": `\uD800`,
		"quoted":  `"\uD800"`,
		"😀":       `\uDC00`,
		`\uD800`:  "literal key",
		"正常":      "原文",
	}
	if !reflect.DeepEqual(value, expected) {
		t.Fatalf("JSON text did not preserve its Unicode values and keys: got %#v; want %#v", value, expected)
	}
}
