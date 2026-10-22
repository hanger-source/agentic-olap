package com.olap.prototype.query.usage

import com.fasterxml.jackson.databind.JsonNode
import com.olap.prototype.calculation.MetricInstanceRef
import com.olap.prototype.calculation.MetricRef
import com.olap.prototype.member.MemberRef
import com.olap.prototype.query.contract.*
import com.olap.prototype.query.definition.QueryDefinitionArtifact
import com.olap.prototype.runtime.BoundedJson
import com.olap.prototype.runtime.JsonResourceLimits
import com.olap.prototype.storage.PlatformDatabase
import com.olap.prototype.util.OlapJsonUtils
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Collections
import java.util.UUID

/** 实际执行与交付分别提交；这里不拥有聊天会话、业务任务或定义提升策略。 */
class QueryUsageRepository(private val database: PlatformDatabase, private val limits: JsonResourceLimits) {
    enum class Execution { ACCEPTED, RUNNING, SUCCEEDED, FAILED, CANCELLED, INTERRUPTED }
    enum class Delivery { PENDING, DIRECT, SAVED, FAILED }

    class Run(
        val runId: String, val subject: String, val publication: QueryCapabilityRequest,
        val subjectAreaId: String, val subjectAreaVersion: Long,
        val catalogHash: String, val capabilityHash: String, val intentHash: String,
        val execution: Execution, val delivery: Delivery, val acceptedAt: Instant, val completedAt: Instant?,
        val historyEnabled: Boolean, val saveRequested: Boolean, intent: JsonNode?,
        val definition: QueryDefinitionArtifact.Reference?, val question: String?,
        val executionError: String?, val deliveryError: String?, val result: ResultManifest?, executionEvidence: JsonNode?,
    ) {
        private val frozenIntent = intent?.deepCopy<JsonNode>()
        val intent: JsonNode? get() = frozenIntent?.deepCopy()
        private val frozenExecutionEvidence = executionEvidence?.deepCopy<JsonNode>()
        val executionEvidence: JsonNode? get() = frozenExecutionEvidence?.deepCopy()
        override fun equals(other: Any?): Boolean = other is Run && runId == other.runId && subject == other.subject &&
            publication == other.publication && subjectAreaId == other.subjectAreaId && subjectAreaVersion == other.subjectAreaVersion &&
            catalogHash == other.catalogHash && capabilityHash == other.capabilityHash && intentHash == other.intentHash &&
            execution == other.execution && delivery == other.delivery && acceptedAt == other.acceptedAt && completedAt == other.completedAt &&
            historyEnabled == other.historyEnabled && saveRequested == other.saveRequested && frozenIntent == other.frozenIntent &&
            definition == other.definition && question == other.question && executionError == other.executionError &&
            deliveryError == other.deliveryError && result == other.result && frozenExecutionEvidence == other.frozenExecutionEvidence
        override fun hashCode(): Int = listOf(runId, subject, publication, subjectAreaId, subjectAreaVersion, catalogHash,
            capabilityHash, intentHash, execution, delivery, acceptedAt, completedAt, historyEnabled, saveRequested,
            frozenIntent, definition, question, executionError, deliveryError, result, frozenExecutionEvidence).hashCode()
    }

    data class ResultManifest(val runId: String, val sha256: String, val bytes: Long, val rows: Long, val savedAt: Instant) {
        init {
            requireRun(runId)
            require(sha256.matches(HASH)) { "exact result hash is required" }
            require(bytes >= 0 && rows >= 0) { "result size must be nonnegative" }
        }
    }
    data class Note(val id: String, val runId: String, val subject: String, val text: String, val createdAt: Instant)
    data class SemanticReference(val assetType: String, val id: String, val version: String)
    class Page(items: List<Run>, val nextOffset: Int?) { val items: List<Run> = Collections.unmodifiableList(ArrayList(items)) }

    fun accept(runId: String, subject: String, entry: PublishedQueryEntry, intent: JsonNode,
        definition: QueryDefinitionArtifact.Reference?, historyEnabled: Boolean, saveResult: Boolean, question: String?): Run {
        requireRun(runId)
        text(subject)
        val coordinate = PublishedQueryEntry.key(entry.capability)
        require(PublishedQueryContract.coordinate(intent) == coordinate) { "run intent differs from its exact publication" }
        val context = entry.capability.subjectAreaCapabilityContext
        val references = linkedSetOf<SemanticReference>()
        if (historyEnabled) collectReferences(PublishedQueryContract.parseIntent(intent, entry.capability), entry, references)
        database.inTransaction {
            update("cannot record accepted run", """
                INSERT INTO query_run(run_id,subject,scenario_id,scenario_version,release_id,release_version,subject_area_id,subject_area_version,
                catalog_hash,capability_hash,intent_hash,execution_state,delivery_state,accepted_at,history_enabled,save_requested,intent_json,definition_json,question)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,'ACCEPTED','PENDING',?,?,?,?,?,?)
            """.trimIndent(), runId, subject, coordinate.scenarioId, coordinate.scenarioVersion, coordinate.releaseId, coordinate.releaseVersion,
                context.subjectAreaId, context.subjectAreaVersion, entry.catalogHash, entry.capability.capabilityHash, QueryIntentHash.compute(intent),
                Instant.now(), historyEnabled, saveResult, if (historyEnabled) json(intent) else null,
                if (historyEnabled && definition != null) json(definition) else null, if (historyEnabled) question else null)
            database.connection().use { connection ->
                connection.prepareStatement("INSERT INTO query_usage_reference(run_id,asset_type,asset_id,asset_version) VALUES(?,?,?,?)").use {
                    for (reference in references) {
                        it.bind(listOf(runId, reference.assetType, reference.id, reference.version))
                        it.addBatch()
                    }
                    it.executeBatch()
                }
            }
        }
        return require(runId)
    }

    fun dispatched(runId: String) = transition(runId, "execution_state='RUNNING'", "execution_state='ACCEPTED'")

    /** 交付观察不改写冻结结果字节；历史读取可区分内核执行与服务准备交付的耗时。 */
    fun recordServiceObservation(runId: String, observation: JsonNode) = database.inTransaction {
        requireRun(runId)
        val evidence = query("cannot lock actual run observation", "SELECT execution_evidence_json FROM query_run WHERE run_id=? FOR UPDATE", listOf(runId)) { rows ->
            require(rows.next()) { "run is unavailable" }
            parse(rows.getString(1), JsonNode::class.java)
        } ?: return@inTransaction
        val updated = (evidence as com.fasterxml.jackson.databind.node.ObjectNode).apply { set<JsonNode>("serviceObservation", observation) }
        check(update("cannot record service observation", "UPDATE query_run SET execution_evidence_json=? WHERE run_id=? AND execution_state NOT IN ('ACCEPTED','RUNNING') AND (history_enabled=TRUE OR save_requested=TRUE)",
            json(updated), runId) == 1) { "run is unavailable for service observation" }
    }

    fun complete(runId: String, outcome: Execution, error: String?, evidence: JsonNode) {
        requireRun(runId)
        require(outcome in setOf(Execution.SUCCEEDED, Execution.FAILED, Execution.CANCELLED, Execution.INTERRUPTED)) { "actual execution terminal outcome is required" }
        check(update("cannot record execution outcome", """
            UPDATE query_run SET execution_state=?,execution_error=?,completed_at=?,
            execution_evidence_json=CASE WHEN history_enabled=TRUE OR save_requested=TRUE THEN ? ELSE NULL END
            WHERE run_id=? AND (execution_state='RUNNING' OR (execution_state='ACCEPTED' AND ?<>'SUCCEEDED'))
        """.trimIndent(), outcome, error, Instant.now(), json(evidence), runId, outcome) == 1) { "run execution outcome cannot replace its committed state" }
    }

    fun resultSaved(runId: String, sha256: String, bytes: Long, rows: Long): ResultManifest {
        val manifest = ResultManifest(runId, sha256, bytes, rows, Instant.now())
        return database.inTransaction {
            transition(runId, "delivery_state='SAVED'", "save_requested=TRUE AND $DELIVERABLE")
            update("cannot attach actual result manifest", "INSERT INTO query_result_manifest(run_id,sha256,byte_count,row_count,saved_at) VALUES(?,?,?,?,?)",
                runId, sha256, bytes, rows, manifest.savedAt)
            manifest
        }
    }

    /** 已生成直接业务封套；不表示客户端已收到响应。 */
    fun directProduced(runId: String) = transition(runId, "delivery_state='DIRECT'", "save_requested=FALSE AND $DELIVERABLE")

    fun resultFailed(runId: String, error: String) {
        requireRun(runId)
        text(error)
        check(update("cannot record result delivery failure", "UPDATE query_run SET delivery_state='FAILED',delivery_error=? WHERE run_id=? AND $DELIVERABLE",
            error, runId) == 1) { "result delivery outcome is already committed or absent" }
    }

    fun interruptUnfinished(): Int = database.inTransaction {
        fun interrupted(source: String) = json(mapOf("clientOutcome" to "INTERRUPTED",
            "sourceOutcome" to mapOf("state" to source), "sourceReadEvidence" to null))
        val error = json(mapOf("stage" to "SERVICE_INTERRUPTION", "code" to "EXECUTION_RECORD_INTERRUPTED",
            "message" to "service restarted before a terminal execution fact was committed", "details" to emptyMap<String, Any>()))
        val count = update("cannot mark interrupted runs", """
            UPDATE query_run SET execution_evidence_json=CASE WHEN history_enabled=TRUE OR save_requested=TRUE
              THEN CASE WHEN execution_state='ACCEPTED' THEN ? ELSE ? END ELSE NULL END,
              execution_state='INTERRUPTED',execution_error=?,completed_at=?
            WHERE execution_state IN ('ACCEPTED','RUNNING')
        """.trimIndent(), interrupted("NOT_SUBMITTED"), interrupted("UNKNOWN"), error, Instant.now())
        update("cannot mark interrupted result deliveries", "UPDATE query_run SET delivery_state='FAILED',delivery_error='service restarted before a delivery outcome was committed' WHERE delivery_state='PENDING'")
        count
    }

    /** 详细历史的保留期不删除运行授权事实、保存查询或显式结果 manifest。 */
    fun purgeHistoryBefore(cutoff: Instant): Int = database.inTransaction {
        update("cannot apply explicit history retention", "DELETE FROM query_usage_note WHERE run_id IN (SELECT run_id FROM query_run WHERE accepted_at<? AND history_enabled=TRUE)", cutoff)
        update("cannot apply explicit history retention", "DELETE FROM query_usage_reference WHERE run_id IN (SELECT run_id FROM query_run WHERE accepted_at<? AND history_enabled=TRUE)", cutoff)
        update("cannot apply explicit history retention", "UPDATE query_run SET history_enabled=FALSE,intent_json=NULL,definition_json=NULL,question=NULL,execution_evidence_json=NULL WHERE accepted_at<? AND history_enabled=TRUE", cutoff)
    }

    fun require(runId: String): Run {
        requireRun(runId)
        return database.inReadSnapshot {
            query("cannot read actual run", "$RUN_SELECT WHERE r.run_id=?", listOf(runId)) { rows ->
                require(rows.next()) { "run is unavailable" }
                readRun(rows)
            }
        }
    }

    private fun readRun(row: ResultSet): Run {
        val id = row.getString("run_id")
        val saved = row.getTimestamp("saved_at")
        return Run(id, row.getString("subject"), QueryCapabilityRequest(row.getString("scenario_id"), row.getLong("scenario_version"), row.getString("release_id"), row.getString("release_version")),
            row.getString("subject_area_id"), row.getLong("subject_area_version"), row.getString("catalog_hash"), row.getString("capability_hash"), row.getString("intent_hash"),
            Execution.valueOf(row.getString("execution_state")), Delivery.valueOf(row.getString("delivery_state")), row.getTimestamp("accepted_at").toInstant(), row.getTimestamp("completed_at")?.toInstant(),
            row.getBoolean("history_enabled"), row.getBoolean("save_requested"), parse(row.getString("intent_json"), JsonNode::class.java),
            parse(row.getString("definition_json"), QueryDefinitionArtifact.Reference::class.java), row.getString("question"), row.getString("execution_error"), row.getString("delivery_error"),
            saved?.let { ResultManifest(id, row.getString("sha256"), row.getLong("byte_count"), row.getLong("row_count"), it.toInstant()) },
            parse(row.getString("execution_evidence_json"), JsonNode::class.java))
    }

    fun appendNote(runId: String, subject: String, note: String): Note {
        text(subject)
        text(note)
        require(require(runId).historyEnabled) { "detailed usage history is not retained for this run" }
        val saved = Note(UUID.randomUUID().toString(), runId, subject, note, Instant.now().truncatedTo(ChronoUnit.MICROS))
        update("cannot append explicit usage note", "INSERT INTO query_usage_note(id,run_id,subject,note_text,created_at) VALUES(?,?,?,?,?)", saved.id, runId, subject, note, saved.createdAt)
        return saved
    }

    fun notes(runId: String, offset: Int, limit: Int): List<Note> {
        page(offset, limit)
        require(runId)
        return query("cannot read usage notes", "SELECT * FROM query_usage_note WHERE run_id=? ORDER BY created_at,id LIMIT ? OFFSET ?", listOf(runId, limit, offset)) { rows ->
            buildList { while (rows.next()) add(Note(rows.getString("id"), runId, rows.getString("subject"), rows.getString("note_text"), rows.getTimestamp("created_at").toInstant())) }
        }
    }

    fun references(runId: String): List<SemanticReference> {
        require(runId)
        return query("cannot read exact usage references", "SELECT asset_type,asset_id,asset_version FROM query_usage_reference WHERE run_id=? ORDER BY asset_type,asset_id,asset_version", listOf(runId)) { rows ->
            buildList { while (rows.next()) add(SemanticReference(rows.getString(1), rows.getString(2), rows.getString(3))) }
        }
    }

    fun search(subject: String, publication: QueryCapabilityRequest, text: String?, offset: Int, limit: Int): Page {
        page(offset, limit)
        text(subject)
        return database.inReadSnapshot {
            val runs = query("cannot search usage history", """
                $RUN_SELECT WHERE r.history_enabled=TRUE AND r.subject=? AND r.scenario_id=? AND r.scenario_version=? AND r.release_id=? AND r.release_version=?
                AND (? IS NULL OR LOCATE(LOWER(?),LOWER(COALESCE(r.question,'')))>0 OR EXISTS(SELECT 1 FROM query_usage_note n WHERE n.run_id=r.run_id AND LOCATE(LOWER(?),LOWER(n.note_text))>0))
                ORDER BY r.accepted_at DESC,r.run_id LIMIT ? OFFSET ?
            """.trimIndent(), listOf(subject, publication.scenarioId, publication.scenarioVersion, publication.releaseId, publication.releaseVersion, text, text, text, limit + 1, offset)) { rows ->
                buildList { while (rows.next()) add(readRun(rows)) }
            }
            Page(runs.take(limit), if (runs.size > limit) Math.addExact(offset, limit) else null)
        }
    }

    private fun transition(runId: String, change: String, condition: String) {
        requireRun(runId)
        check(update("cannot record actual run transition", "UPDATE query_run SET $change WHERE run_id=? AND $condition", runId) == 1) { "actual run transition conflicts with committed state" }
    }

    private fun update(label: String, sql: String, vararg arguments: Any?): Int = try {
        database.connection().use { connection -> connection.prepareStatement(sql).use { it.bind(arguments.asList()); it.executeUpdate() } }
    } catch (failure: SQLException) { throw IllegalStateException(label, failure) }

    private fun <T> query(label: String, sql: String, arguments: List<Any?>, read: (ResultSet) -> T): T = try {
        database.connection().use { connection -> connection.prepareStatement(sql).use { it.bind(arguments); it.executeQuery().use(read) } }
    } catch (failure: SQLException) { throw IllegalStateException(label, failure) }

    private fun PreparedStatement.bind(arguments: List<Any?>) {
        arguments.forEachIndexed { index, value -> when (value) {
            is Instant -> setTimestamp(index + 1, Timestamp.from(value))
            is Enum<*> -> setString(index + 1, value.name)
            else -> setObject(index + 1, value)
        } }
    }

    /** 遍历实际类型化查询值，字段来自共同 Jackson 绑定，不维护另一份 DSL 引用解析器。 */
    private fun collectReferences(value: Any?, entry: PublishedQueryEntry, references: MutableSet<SemanticReference>) {
        when (value) {
            null -> return
            is MetricInstanceRef -> {
                references.add(SemanticReference("METRIC_INSTANCE", value.id, ""))
                val definition = entry.metricDefinition(value).definition
                references.add(SemanticReference("METRIC", definition.id, definition.version))
            }
            is MetricRef -> references.add(SemanticReference("METRIC", value.id, value.version))
            is MemberRef -> references.add(SemanticReference("MEMBER_SOURCE", value.id, value.version.toString()))
            is Map<*, *> -> value.values.forEach { collectReferences(it, entry, references) }
            is Iterable<*> -> value.forEach { collectReferences(it, entry, references) }
            is CharSequence, is Number, is Boolean, is Enum<*> -> return
            else -> {
                for (field in mapper.serializationConfig.introspect(mapper.constructType(value.javaClass)).findProperties()) {
                    if (!field.couldSerialize()) continue
                    val accessor = requireNotNull(field.accessor) { "typed query property has no accessor: ${field.name}" }
                    accessor.fixAccess(true)
                    collectReferences(accessor.getValue(value), entry, references)
                }
            }
        }
    }

    private fun json(value: Any): String = BoundedJson.encode(value, limits.inputFileBytes).toString(Charsets.UTF_8)
    private fun <T> parse(value: String?, type: Class<T>): T? = value?.let { mapper.treeToValue(BoundedJson.decode(it.toByteArray(Charsets.UTF_8), limits), type) }

    companion object {
        private val mapper = OlapJsonUtils.mapper()
        private val RUN = Regex("run-[0-9a-f]{32}")
        private val HASH = Regex("[0-9a-f]{64}")
        private const val DELIVERABLE = "execution_state IN ('SUCCEEDED','FAILED','CANCELLED','INTERRUPTED') AND delivery_state='PENDING'"
        private const val RUN_SELECT = "SELECT r.*,m.sha256,m.byte_count,m.row_count,m.saved_at FROM query_run r LEFT JOIN query_result_manifest m ON m.run_id=r.run_id"
        private fun requireRun(id: String) { require(RUN.matches(id)) { "exact run identity is required" } }
        private fun text(value: String) { require(value.isNotBlank()) { "explicit nonempty text is required" } }
        private fun page(offset: Int, limit: Int) { require(offset >= 0 && limit in 1..1000) { "invalid history page" } }
    }
}
