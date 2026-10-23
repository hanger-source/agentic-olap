package com.olap.prototype.runtime

import com.olap.prototype.codec.JsonDocumentException
import com.olap.prototype.codec.ScriptOutputRepresentationException
import com.olap.prototype.codec.ProtoStringRepresentationException
import com.olap.prototype.contract.InputContractException
import com.olap.prototype.dsl.ScriptValueException
import com.olap.prototype.dsl.transport.CapabilityFailureException
import com.olap.prototype.protocol.CapabilityFailure
import com.olap.prototype.protocol.ScriptDiagnostic
import com.olap.prototype.protocol.SourceLocation
import com.olap.prototype.repl.ReplDiagnostic
import java.util.concurrent.CancellationException

/** 语言层只提供位置和异常；正式领域、参数及输出诊断由 OLAP 应用投影。 */
internal object ScriptDiagnostics {
    fun fromRepl(value: ReplDiagnostic): ScriptDiagnostic {
        val result = project(value.exception, value.code, value.phase.name, value.message)
            .setSeverity(if (value.severity.name == "FATAL") "ERROR" else value.severity.name)
        val location = SourceLocation.newBuilder().setSourceName(value.location.sourceName).setSnippetId(value.location.snippetId)
        value.location.line?.let(location::setStartLine)
        value.location.column?.let(location::setStartColumn)
        value.location.endLine?.let(location::setEndLine)
        value.location.endColumn?.let(location::setEndColumn)
        return result.setLocation(location).build()
    }

    fun fromFailure(failure: Exception, sourceName: String): ScriptDiagnostic {
        val phase = if (causes(failure).any { it is ScriptValueException }) "SCRIPT_PARAMETERS" else "SCRIPT_INPUT"
        val result = project(failure, "SCRIPT_REQUEST_REJECTED", phase, failure.message ?: failure.javaClass.simpleName)
            .setSeverity("ERROR")
        if (sourceName.isNotBlank()) result.setLocation(SourceLocation.newBuilder().setSourceName(sourceName))
        return result.build()
    }

    fun isCancellation(failure: Throwable?): Boolean = causes(failure).any { it is CancellationException || it is InterruptedException }

    private fun project(failure: Throwable?, code: String, stage: String, message: String): ScriptDiagnostic.Builder {
        val chain = causes(failure)
        val result = ScriptDiagnostic.newBuilder().setCode(code).setStage(stage).setMessage(message)
        val capability = chain.filterIsInstance<CapabilityFailureException>().firstOrNull()?.failure
        if (capability != null) return result.setCode(capability.code).setStage(capability.stage).setMessage(capability.message)
            .addAllFieldPath(capability.fieldPath).setCapabilityFailure(CapabilityFailure.newBuilder()
                .setOperationId(capability.operationId).setCallId(capability.callId).setCode(capability.code)
                .setStage(capability.stage).setMessage(capability.message).addAllFieldPath(capability.fieldPath)
                .setDiagnosticId(capability.diagnosticId.orEmpty()))
        val input = chain.filterIsInstance<InputContractException>().firstOrNull()
        if (input != null) return result.setCode(input.rule.code).setStage(input.rule.stage).setMessage(input.message)
            .addAllFieldPath((input.details["fieldPath"] as? List<*>)?.map { it.toString() } ?: emptyList())
            .setExpected(input.details["expectedKind"] as? String ?: "").setActual(input.details["actualKind"] as? String ?: "")
        val document = chain.filterIsInstance<JsonDocumentException>().firstOrNull()
        if (document != null) {
            result.setCode(document.code).setStage(document.stage).setMessage(document.message)
                .addAllFieldPath(document.fieldPath.map(Any::toString)).setExpected(document.expected).setActual(document.actual)
            document.resource?.let { result.setResource(it.name).setLimit(requireNotNull(document.limit)).setObserved(requireNotNull(document.observed)) }
            return result
        }
        val output = chain.filterIsInstance<ScriptOutputRepresentationException>().firstOrNull()
        if (output != null) return result.setCode("SCRIPT_OUTPUT_REPRESENTATION").setStage("OUTPUT").setMessage(output.message)
            .addAllFieldPath(output.fieldPath).setExpected("WELL_FORMED_UNICODE_TEXT").setActual("UNPAIRED_UTF16_SURROGATE")
        val encoding = chain.filterIsInstance<ScriptEmissionEncodingException>().firstOrNull()
        val representation = chain.filterIsInstance<ProtoStringRepresentationException>().firstOrNull()
        if (encoding != null && representation != null) return result.setCode("SCRIPT_OUTPUT_REPRESENTATION").setStage("OUTPUT")
            .setMessage(requireNotNull(representation.message)).addAllFieldPath(representation.fieldPath)
            .setExpected("WELL_FORMED_UNICODE_TEXT").setActual("UNPAIRED_UTF16_SURROGATE")
        if (encoding != null) return result.setCode("SCRIPT_OUTPUT_ENCODING_FAILED").setStage("OUTPUT").setMessage(encoding.message)
        val value = chain.filterIsInstance<ScriptValueException>().firstOrNull()
        if (value != null) return result.setCode(value.code).setStage(stage).setMessage(value.message)
            .addAllFieldPath(value.fieldPath.map(Any::toString)).setExpected(value.expected).setActual(value.actual)
        if (isCancellation(failure)) return result.setCode("CANCELLED").setStage("SCRIPT")
        return result
    }

    private fun causes(failure: Throwable?): List<Throwable> {
        val result = mutableListOf<Throwable>()
        var current = failure
        while (current != null && result.size < 16 && result.none { it === current }) {
            result += current
            current = current.cause
        }
        return result
    }
}
