package com.olap.prototype.runtime

import com.olap.prototype.codec.CapabilityProtos
import com.olap.prototype.codec.JsonDocumentException
import com.olap.prototype.codec.JsonStructure
import com.olap.prototype.dsl.ApiDiscovery
import com.olap.prototype.dsl.OlapClients
import com.olap.prototype.dsl.OlapEnvironment
import com.olap.prototype.protocol.*
import com.olap.prototype.repl.*
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

/** 一个进程拥有一份明确会话及语言历史；每次执行仅替换当前参数与授权调用绑定。 */
fun main(args: Array<String>) {
    val protocolInput = BufferedInputStream(System.`in`)
    val protocolOutput = System.out
    val privateDiagnostics = System.err
    System.setIn(InputStream.nullInputStream())
    System.setOut(privateDiagnostics)
    require(args.contentEquals(arrayOf("--mode", "script"))) { "runtime --mode script" }
    val frame = ScriptProtocolPeer.readFrame(protocolInput, 64 * 1024 * 1024) ?: error("session bootstrap is required")
    require(frame.hasBootstrap() && frame.bootstrap.protocol == "agentic-olap-runtime/v5" && frame.bootstrap.hasScripting()) {
        "unsupported session protocol bootstrap"
    }
    val configuration = frame.bootstrap.scripting
    require(frame.bootstrap.sourceConfigurationsJson.isEmpty &&
        frame.bootstrap.connectorManifestsCount == 0) { "session process cannot receive deployment, source or plugin configuration" }
    require(configuration.dslApiVersion == ApiDiscovery.VERSION) { "session must select its exact deployed DSL API" }
    val requestBytes = positiveInt(configuration.maximumRequestBytes, "maximum request bytes")
    val depth = positiveInt(configuration.maximumInputDepth.toLong(), "input depth")
    val parameters = JsonStructure.Limits(requestBytes.toLong(), depth,
        positiveInt(configuration.maximumInputNodes, "input nodes"))
    val workerDirectory = Path.of("").toAbsolutePath().toRealPath()
    ScriptProtocolPeer(protocolInput, protocolOutput, positiveInt(configuration.maximumFrameBytes, "maximum frame bytes"), requestBytes, depth,
        Path.of(frame.bootstrap.transferDirectory)).use { peer ->
        val environment = OlapSessionEnvironment(peer, CapabilityProtos(requestBytes, depth), workerDirectory)
        val language = ReplConfiguration(configuration.compilationClasspathList.map { Path.of(it) }, ApiDiscovery.imports,
            "21", OlapEnvironment::class.java.classLoader)
        KotlinRepl(language).use { repl ->
            val session = OlapReplSession(repl, environment, peer, configuration.dslApiVersion, parameters,
                positiveInt(configuration.maximumSourceBytes, "maximum source bytes"), workerDirectory, privateDiagnostics)
            peer.redirectStandardOutput().use {
                peer.send(Frame.newBuilder().setResponse(Response.newBuilder().setRequestId("bootstrap").setSuccess(true)).build())
                peer.serve(session::execute)
            }
        }
    }
}

private class OlapReplSession(private val repl: KotlinRepl, private val environment: OlapSessionEnvironment,
    private val peer: ScriptProtocolPeer, private val dslVersion: String, private val parametersLimit: JsonStructure.Limits,
    private val maximumSourceBytes: Int, private val sessionDirectory: Path, private val privateDiagnostics: PrintStream) {

    fun execute(request: ScriptRequest, checkActive: () -> Unit): ScriptResponse {
        val response = ScriptResponse.newBuilder().setRequestId(request.requestId).setSessionUsable(true).setState("RUNTIME_FAILED")
        var resources: ScriptExecutionResources? = null
        var evaluationScope: AutoCloseable? = null
        var enteredLanguage = false
        try {
            require(request.hasSource() && request.source.dslApiVersion == dslVersion) { "snippet must select the deployed DSL API version" }
            require(request.source.source.toByteArray(Charsets.UTF_8).size <= maximumSourceBytes) { "snippet source exceeds its deployed input byte budget" }
            require(!request.parametersJson.isEmpty) { "explicit parameters JSON is required" }
            require(request.callerSubject.isNotBlank() && request.executionDirectory.isNotBlank()) { "trusted caller and internal execution directory are required" }
            val executionDirectory = Path.of(request.executionDirectory).toRealPath()
            require(Files.isDirectory(executionDirectory) && executionDirectory != sessionDirectory && executionDirectory.startsWith(sessionDirectory)) {
                "internal execution directory must be inside the session private root"
            }
            val parameters = scriptParameters(request.parametersJson.toByteArray(), parametersLimit)
            environment.bind(request, parameters)
            val executionResources = ScriptExecutionResources(sessionDirectory)
            resources = executionResources
            val result = repl.evaluate(ReplSource(request.source.source, request.source.sourceName), checkActive) {
                executionResources.prepareEvaluation()
                peer.beginEvaluation()
                enteredLanguage = true
                evaluationScope = OlapClients.bindEvaluation(environment)
            }
            result.failure?.let { failure ->
                privateDiagnostics.println("language execution failed: sessionId=${request.sessionId}, requestId=${request.requestId}, snippetId=${result.snippetId}, state=${result.status}")
                failure.printStackTrace(privateDiagnostics)
            }
            val state = if (ScriptDiagnostics.isCancellation(result.failure)) "CANCELLED" else when (result.status) {
                ReplStatus.COMPLETED -> "COMPLETED"
                ReplStatus.COMPILE_FAILED -> "COMPILE_FAILED"
                ReplStatus.RUNTIME_FAILED, ReplStatus.NOT_EVALUATED -> "RUNTIME_FAILED"
            }
            response.setState(state).setSuccess(state == "COMPLETED")
                .setSessionUsable(result.continuation == ReplContinuation.AVAILABLE)
                .addAllDiagnostics(result.diagnostics.filter { it.severity != ReplSeverity.DEBUG }.map(ScriptDiagnostics::fromRepl))
                .putTimingNanos("SCRIPT_COMPILE", result.compilationNanos).putTimingNanos("SCRIPT_EVALUATE", result.evaluationNanos)
        } catch (failure: Exception) {
            privateDiagnostics.println("session execution failed: sessionId=${request.sessionId}, requestId=${request.requestId}")
            failure.printStackTrace(privateDiagnostics)
            response.setSuccess(false).setState(if (ScriptDiagnostics.isCancellation(failure)) "CANCELLED" else "RUNTIME_FAILED")
                .setSessionUsable(!enteredLanguage && repl.continuation == ReplContinuation.AVAILABLE)
                .addDiagnostics(ScriptDiagnostics.fromFailure(failure, request.source.sourceName))
        } finally {
            try {
                evaluationScope?.close()
            } finally {
                peer.finishEvaluation()
                environment.release()
                resources?.let { observed ->
                    observed.close()
                    val issues = observed.resourceIssues
                    if (issues.isNotEmpty()) response.setSessionUsable(false)
                    response.addAllResourceIssues(issues.map { issue -> com.olap.prototype.protocol.ScriptResourceIssue.newBuilder()
                        .setCode(issue.code).setMessage(issue.message).setObservedCount(issue.observedCount).setTruncated(issue.truncated)
                        .addAllThreads(issue.threads.map { thread -> com.olap.prototype.protocol.ScriptThreadObservation.newBuilder()
                            .setId(thread.id).setName(thread.name).setState(thread.state).setDaemon(thread.daemon)
                            .setContextClassLoader(thread.contextClassLoader).addAllStack(thread.stack).build() }).build() })
                    response.addAllDiagnostics(issues.map { issue -> ScriptDiagnostic.newBuilder().setCode(issue.code)
                        .setSeverity("WARNING").setStage("RESOURCE").setMessage(issue.message).build() })
                }
            }
        }
        return response.build()
    }
}

private fun positiveInt(value: Long, label: String): Int = value.also {
    require(it in 1..Int.MAX_VALUE.toLong()) { "$label must be positive and within JVM capacity" }
}.toInt()
