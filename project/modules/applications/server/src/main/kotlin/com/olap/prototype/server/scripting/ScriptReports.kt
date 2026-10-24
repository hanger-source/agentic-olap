package com.olap.prototype.server.scripting

import com.fasterxml.jackson.databind.JsonNode
import com.google.protobuf.Any
import com.olap.prototype.protocol.CapabilityEffect
import com.olap.prototype.protocol.ScriptDiagnostic

data class ScriptSubmission(val sessionId: String? = null, val executionId: String, val waitMillis: Long,
    val source: String, val sourceName: String, val dslApiVersion: String, val parameters: JsonNode)

data class ScriptSession(val sessionId: String, val state: String, val usable: Boolean,
    val workerId: String? = null, val currentExecutionId: String? = null)

/** Content has one owner; JSON delivery chooses lossless UTF8/base64 and explicit Protobuf encoding. */
data class ScriptOutput(val sequence: Long, val kind: String, val content: ByteArray? = null, val value: Any? = null)

data class ScriptCallReceipt(val callId: String, val operationId: String, val requestId: String,
    val state: String, val success: Boolean, val stage: String?, val code: String?, val elapsedNanos: Long,
    val effects: List<CapabilityEffect>)

data class ScriptReport(val sessionId: String, val executionId: String, val state: String,
    val sessionUsable: Boolean, val requestId: String, val workerId: String?, val workerOutcome: String?,
    val workerDisposition: String?, val recycleReasons: List<String>, val success: Boolean,
    val outputs: List<ScriptOutput>, val diagnostics: List<ScriptDiagnostic>, val calls: List<ScriptCallReceipt>,
    val timingNanos: Map<String, Long>)

class ScriptException(val code: String, override val message: String, val sessionId: String? = null,
    val executionId: String? = null, val state: String? = null) : RuntimeException(message)
