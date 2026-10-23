package com.olap.prototype.runtime

import com.olap.prototype.codec.*
import com.olap.prototype.dsl.OlapClients
import com.olap.prototype.dsl.Caller
import com.olap.prototype.dsl.ScriptValueException
import com.olap.prototype.dsl.transport.CapabilityTransport
import com.olap.prototype.protocol.*
import com.olap.prototype.scripting.ScriptDiagnostic as RuntimeDiagnostic
import com.olap.prototype.scripting.ScriptSource as RuntimeSource
import com.olap.prototype.scripting.ScriptEnvironment
import com.olap.prototype.scripting.ScriptLimits
import com.olap.prototype.scripting.ScriptRuntime
import java.io.BufferedInputStream
import java.io.InputStream
import java.nio.file.Path
import java.nio.file.Files

/** 一个有界worker串行执行完整程序；只复用固定编译环境，不复用请求实例。 */
fun main(args: Array<String>) {
    val protocolInput = BufferedInputStream(System.`in`)
    val protocolOutput = System.out
    System.setIn(InputStream.nullInputStream())
    System.setOut(System.err)
    require(args.contentEquals(arrayOf("--mode", "script"))) { "script-runtime --mode script" }
    val frame = ScriptProtocolPeer.readFrame(protocolInput, 64 * 1024 * 1024) ?: error("script bootstrap is required")
    require(frame.hasBootstrap() && frame.bootstrap.protocol == "agentic-olap-runtime/v2" && frame.bootstrap.hasScripting()) {
        "unsupported script protocol bootstrap"
    }
    val configuration = frame.bootstrap.scripting
    require(frame.bootstrap.deploymentJson.isEmpty && frame.bootstrap.sourceConfigurationsJson.isEmpty &&
        frame.bootstrap.connectorManifestsCount == 0) { "script process cannot receive deployment, source or plugin configuration" }
    val outputBytes = positiveInt(configuration.maximumOutputBytes, "maximum output bytes")
    val requestBytes = positiveInt(configuration.maximumRequestBytes, "maximum request bytes")
    val depth = positiveInt(configuration.maximumInputDepth.toLong(), "input depth")
    val parameters = JsonStructure.Limits(requestBytes.toLong(), depth,
        positiveInt(configuration.maximumInputNodes, "input nodes"))
    val runtime = ScriptRuntime(ScriptEnvironment(configuration.dslApiVersion, configuration.compilationClasspathList.map { Path.of(it) },
        ScriptLimits(maximumSourceBytes = positiveInt(configuration.maximumSourceBytes, "maximum source bytes"))))
    val workerDirectory = Path.of("").toAbsolutePath().toRealPath()
    ScriptProtocolPeer(protocolInput, protocolOutput, positiveInt(configuration.maximumFrameBytes, "maximum frame bytes"), requestBytes, outputBytes, depth).use { peer ->
        peer.redirectStandardOutput().use {
            peer.send(Frame.newBuilder().setResponse(Response.newBuilder().setRequestId("bootstrap").setSuccess(true)).build())
            peer.serve { request, transport, checkActive -> executeScript(request, transport, runtime, configuration.dslApiVersion, parameters, outputBytes, workerDirectory, checkActive) }
        }
    }
}

private fun executeScript(request: ScriptRequest, transport: CapabilityTransport, runtime: ScriptRuntime,
                          dslVersion: String, parametersLimit: JsonStructure.Limits, outputBytes: Int, workerDirectory: Path,
                          checkActive: () -> Unit): ScriptResponse {
    val response = ScriptResponse.newBuilder().setRequestId(request.requestId)
    try {
        require(request.hasSource() && request.source.dslApiVersion == dslVersion) { "explicit source must select the deployed DSL API version" }
        require(!request.parametersJson.isEmpty) { "explicit parameters JSON is required" }
        require(request.callerSubject.isNotBlank() && request.executionDirectory.isNotBlank()) { "trusted caller and request execution directory are required" }
        val executionDirectory = Path.of(request.executionDirectory).toRealPath()
        require(Files.isDirectory(executionDirectory) && executionDirectory != workerDirectory && executionDirectory.startsWith(workerDirectory)) {
            "script execution directory must be inside its worker private root"
        }
        val parameters = scriptParameters(request.parametersJson.toByteArray(), parametersLimit)
        val client = OlapClients.connect(transport, CapabilityProtos(parametersLimit.bytes.toInt(), outputBytes, parametersLimit.depth), Caller(request.callerSubject), executionDirectory)
        val result = runtime.run(RuntimeSource(request.source.source, request.source.sourceName), client, parameters, checkActive)
        response.setSuccess(result.successful).setRecycleWorker(result.recycleWorker).addAllDiagnostics(result.diagnostics.map(::diagnostic))
            .putTimingNanos("SCRIPT_COMPILE", result.compilationNanos).putTimingNanos("SCRIPT_EVALUATE", result.evaluationNanos)
        if (result.successful) {
            val value = QueryProto.scriptValue(requireNotNull(result.value), outputBytes, parametersLimit.depth)
            val encoded = if (value.serializedSize in 0..outputBytes) ProtoBytes.pack(value, outputBytes) else null
            if (encoded == null || encoded.serializedSize > outputBytes) response.setSuccess(false).addDiagnostics(
                ScriptDiagnostic.newBuilder().setCode("SCRIPT_OUTPUT_LIMIT").setStage("OUTPUT").setSeverity("ERROR")
                    .setMessage("script output exceeds its configured Protobuf byte budget"))
            else response.setResult(encoded)
        }
    } catch (failure: Exception) {
        System.err.println("script request failed: requestId=${request.requestId}")
        failure.printStackTrace(System.err)
        val diagnostic = ScriptDiagnostic.newBuilder().setSeverity("ERROR").setMessage((failure.message ?: failure.javaClass.simpleName).take(4_096))
        if (failure is java.util.concurrent.CancellationException) diagnostic.setCode("CANCELLED").setStage("SCRIPT")
        else if (failure is JsonDocumentException) diagnostic.setCode(failure.code).setStage(failure.stage)
            .addAllFieldPath(failure.fieldPath.map(Any::toString)).setExpected(failure.expected).setActual(failure.actual)
        else if (failure is ScriptValueException) diagnostic.setCode(failure.code).setStage("SCRIPT_PARAMETERS")
            .addAllFieldPath(failure.fieldPath.map(Any::toString)).setExpected(failure.expected).setActual(failure.actual)
        else diagnostic.setCode("SCRIPT_REQUEST_REJECTED").setStage("SCRIPT_INPUT_OR_OUTPUT")
        response.clearResult().setSuccess(false).addDiagnostics(diagnostic)
    }
    return response.build()
}

private fun diagnostic(value: RuntimeDiagnostic): ScriptDiagnostic {
    val result = ScriptDiagnostic.newBuilder().setCode(value.code).setStage(value.capabilityFailure?.stage ?: value.inputRule?.stage ?: value.phase.name)
        .setSeverity(value.severity.name).setMessage(value.message)
        .addAllFieldPath(value.fieldPath)
    value.expectedKind?.let(result::setExpected); value.actualKind?.let(result::setActual)
    if (value.sourceName != null) {
        val location = SourceLocation.newBuilder().setSourceName(value.sourceName)
        value.line?.let(location::setStartLine); value.column?.let(location::setStartColumn)
        value.endLine?.let(location::setEndLine); value.endColumn?.let(location::setEndColumn)
        result.setLocation(location)
    }
    value.capabilityFailure?.let { failure -> result.setCapabilityFailure(CapabilityFailure.newBuilder().setOperationId(failure.operationId)
        .setCallId(failure.callId).setCode(failure.code).setStage(failure.stage).setMessage(failure.message)
        .addAllFieldPath(failure.fieldPath).setDiagnosticId(failure.diagnosticId.orEmpty())) }
    return result.build()
}

private fun positiveInt(value: Long, label: String): Int = value.also {
    require(it in 1..Int.MAX_VALUE.toLong()) { "$label must be positive and within JVM capacity" }
}.toInt()
