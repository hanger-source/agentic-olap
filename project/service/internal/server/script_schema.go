package server

import "encoding/json"

// 只描述公开通信字段；会话归属、去重、状态与执行预算由 runtime 决定。
func scriptToolSchema(operation string) json.RawMessage {
	text := func(description string) map[string]any {
		return map[string]any{"type": "string", "minLength": 1, "description": description}
	}
	wait := map[string]any{"type": "integer", "minimum": 0, "description": "仅本次等待毫秒数；0读取当前状态。等待不重执行业务，也不延长执行期限。"}
	var properties map[string]any
	var required []string
	switch operation {
	case "execute-script":
		properties = map[string]any{
			"sessionId":     text("已有且属于调用者的语言会话；省略时建立新会话。"),
			"executionId":   text("调用者生成的本次执行身份。同身份同输入观察原执行，输入不同明确拒绝。"),
			"source":        text("完整 Kotlin 代码片段；可引用同会话既有声明。"),
			"sourceName":    text("用于诊断的 .kts 显示名称；实际片段身份由运行器独立生成。"),
			"dslApiVersion": text("describe-script-api 返回的精确 DSL API 版本。"),
			"parameters":    map[string]any{"type": "object", "description": "本次不可变参数快照；无参数传空对象。仅按实际部署 JSON 输入预算检查。"},
			"waitMillis":    wait,
		}
		required = []string{"executionId", "source", "sourceName", "dslApiVersion", "parameters"}
	case "wait-script":
		properties = map[string]any{"executionId": text("本人的既有执行身份。"), "waitMillis": wait}
		required = []string{"executionId"}
	case "cancel-script":
		properties = map[string]any{"executionId": text("本人的既有执行身份。停止脚本不代表远端来源已取消。")}
		required = []string{"executionId"}
	case "read-script-session", "reset-script-session", "close-script-session":
		properties = map[string]any{"sessionId": text("明确属于调用者的语言会话。重置及关闭停止活动代码，并核对原进程退出。")}
		required = []string{"sessionId"}
	default:
		return nil
	}
	value := map[string]any{"type": "object", "properties": map[string]any{"payload": map[string]any{
		"type": "object", "properties": properties, "required": required, "additionalProperties": false,
	}}, "required": []string{"payload"}, "additionalProperties": false}
	encoded, _ := json.Marshal(value) // 此结构仅包含静态 JSON 原生类型。
	return encoded
}
