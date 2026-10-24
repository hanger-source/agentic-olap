package com.olap.prototype.server.http

import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.*

/** 仅描述公开输入；会话归属、执行状态和预算由正式运行器决定。 */
internal object ScriptToolSchemas {
    fun schema(operation: String): ToolSchema {
        val fields = linkedMapOf<String, JsonObject>()
        fun text(name: String, description: String) { fields[name] = buildJsonObject { put("type", "string"); put("minLength", 1); put("description", description) } }
        fun wait() { fields["waitMillis"] = buildJsonObject { put("type", "integer"); put("minimum", 0); put("description", "只等待原执行，不重执行、不延长其期限；0读取当前状态。") } }
        val required = when (operation) {
            "execute-script" -> {
                text("sessionId", "属于当前主体的已有语言会话；省略时建立新会话。")
                text("executionId", "本次明确执行身份；同身份同输入观察原执行，输入不同拒绝。")
                text("source", "完整 Kotlin 片段，可引用同会话既有声明。")
                text("sourceName", "诊断使用的 .kts 显示名称。")
                text("dslApiVersion", "describe-script-api 返回的精确 DSL API 版本。")
                fields["parameters"] = buildJsonObject { put("type", "object"); put("description", "本次不可变参数快照；按实际部署输入预算检查。") }
                wait()
                listOf("executionId", "source", "sourceName", "dslApiVersion", "parameters")
            }
            "wait-script" -> { text("executionId", "本人的既有执行身份。"); wait(); listOf("executionId") }
            "cancel-script" -> { text("executionId", "本人的既有执行身份；停止脚本不表示来源取消。"); listOf("executionId") }
            "read-script-session", "reset-script-session", "close-script-session" -> { text("sessionId", "属于当前主体的语言会话；重置及关闭核对受管进程退出。"); listOf("sessionId") }
            else -> return ToolSchema(properties = buildJsonObject { put("payload", buildJsonObject { put("type", "object") }) }, required = listOf("payload"))
        }
        return ToolSchema(properties = buildJsonObject { put("payload", buildJsonObject {
            put("type", "object"); put("properties", JsonObject(fields)); put("required", JsonArray(required.map(::JsonPrimitive))); put("additionalProperties", false)
        }) }, required = listOf("payload"))
    }
}
