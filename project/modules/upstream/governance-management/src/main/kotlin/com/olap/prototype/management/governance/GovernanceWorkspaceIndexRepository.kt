package com.olap.prototype.management.governance

import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.governance.contract.*
import com.olap.prototype.management.governance.GovernanceJdbcSupport.instant
import com.olap.prototype.management.governance.GovernanceJdbcSupport.nullableLong
import com.olap.prototype.management.governance.GovernanceJdbcSupport.query
import com.olap.prototype.storage.PlatformDatabase
import java.time.Instant

/** Workspace presentation derives exclusively from exact owner versions and frozen releases. */
class GovernanceWorkspaceIndexRepository(private val database: PlatformDatabase) {
    private val snapshots = GovernanceSnapshotReader(database)
    private val working = GovernanceWorkingVersionResolver(database)

    fun listBusinessLineIndexItems(): List<BusinessLineIndexItem> {
        val businessLines = working.list("business_line_version", "business_line_id").map { snapshots.businessLine(it.objectId, it.version) }
        val subjects = working.list("subject_area_version", "subject_area_id").map { snapshots.subjectArea(it.objectId, it.version) }
        val releases = releases()
        return businessLines.sortedBy { it.code }.map { business ->
            val owned = subjects.filter { it.businessLineId == business.id && it.businessLineVersion == business.currentVersion }
            val published = releases.filter { release -> owned.any { it.id == release.subjectAreaId && it.currentVersion == release.subjectAreaVersion } }
            val recent = recent(published)
            val publishedCount = owned.count { subject -> published.any { it.subjectAreaId == subject.id && it.subjectAreaVersion == subject.currentVersion } }.toLong()
            val blocking = published.sumOf { it.blockingIssueCount }
            val changed = latest(business.updatedAt, business.createdAt, recent?.createdAt, owned.maxOfOrNull { latest(it.updatedAt, it.createdAt) })
            val status = when { blocking > 0 -> "BLOCKED"; owned.isEmpty() -> "EMPTY"; recent == null -> "UNPUBLISHED"; else -> "READY" }
            BusinessLineIndexItem(business.id, business.code, business.displayName, business.description, business.owner, business.status, business.currentVersion,
                business.createdBy, business.createdAt, business.updatedBy, business.updatedAt, owned.size.toLong(), publishedCount, blocking, recent?.releaseVersion,
                status, statusName("business line", status), readiness(status), "${owned.size} 主题域 · $publishedCount 已发布 · $blocking 阻塞",
                recent?.let { "最近发布 ${it.releaseVersion}" } ?: "最近变更 $changed", changed)
        }
    }
    fun listSubjectAreaIndexItems(): List<SubjectAreaIndexItem> {
        val releases = releases()
        return working.list("subject_area_version", "subject_area_id").map { ref ->
            val subject = snapshots.subjectArea(ref.objectId, ref.version)
            val business = snapshots.businessLine(subject.businessLineId, subject.businessLineVersion)
            val exact = releases.filter { it.subjectAreaId == subject.id && it.subjectAreaVersion == subject.currentVersion }
            val recent = recent(exact)
            val metrics = ownerCount("subject_area_metric_ref", "subject_area", subject.id, subject.currentVersion)
            val dimensions = ownerCount("subject_area_analysis_dimension_ref", "subject_area", subject.id, subject.currentVersion)
            val scenarios = ownerCount("subject_area_scenario_ref", "subject_area", subject.id, subject.currentVersion)
            val blocking = exact.sumOf { it.blockingIssueCount }
            val status = when { blocking > 0 -> "BLOCKED"; recent == null -> "UNPUBLISHED"; else -> "READY" }
            val changed = latest(subject.updatedAt, subject.createdAt, recent?.createdAt)
            SubjectAreaIndexItem(subject.id, subject.code, subject.displayName, business.id, business.currentVersion, business.displayName, subject.description, subject.owner,
                subject.status, subject.currentVersion, subject.createdBy, subject.createdAt, subject.updatedBy, subject.updatedAt, metrics, dimensions, scenarios,
                if (recent == null) "UNPUBLISHED" else "PUBLISHED", recent?.engineLoadStatus, blocking, recent?.releaseVersion, status, statusName("subject area", status), readiness(status),
                "$metrics 指标 · $dimensions 分析维度 · $scenarios 场景 · $blocking 阻塞", recent?.let { "已发布 ${it.releaseVersion}" } ?: "最近变更 $changed", changed)
        }.sortedWith(compareBy(SubjectAreaIndexItem::businessLineDisplayName, SubjectAreaIndexItem::code))
    }
    fun listScenarioIndexItems(): List<ScenarioIndexItem> = working.list("scenario_version", "scenario_id")
        .map { scenario(snapshots.scenario(it.objectId, it.version)) }
        .sortedWith(compareBy(ScenarioIndexItem::businessLineDisplayName, ScenarioIndexItem::subjectAreaDisplayName, ScenarioIndexItem::code))
    private fun scenario(scenario: ScenarioRecord): ScenarioIndexItem {
        val subject = snapshots.subjectArea(scenario.subjectAreaId, scenario.subjectAreaVersion)
        val business = snapshots.businessLine(subject.businessLineId, subject.businessLineVersion)
        val metrics = ownerCount("scenario_metric_ref", "scenario", scenario.id, scenario.currentVersion)
        val dimensions = ownerCount("scenario_analysis_dimension_ref", "scenario", scenario.id, scenario.currentVersion)
        val filters = ownerCount("scenario_filter_ref", "scenario", scenario.id, scenario.currentVersion)
        val stages = ownerCount("scenario_pipeline_rule", "scenario", scenario.id, scenario.currentVersion)
        val current = count("SELECT COUNT(*) FROM subject_area_scenario_ref WHERE subject_area_id=? AND subject_area_version=? AND scenario_id=? AND scenario_version=?", subject.id, subject.currentVersion, scenario.id, scenario.currentVersion)
        val any = count("SELECT COUNT(*) FROM subject_area_scenario_ref WHERE subject_area_id=? AND subject_area_version=? AND scenario_id=?", subject.id, subject.currentVersion, scenario.id)
        val release = recentScenario(scenario.id, scenario.currentVersion)
        val blocking = count("SELECT COUNT(*) FROM release_unavailable_reason WHERE scenario_id=? AND scenario_version=? AND blocking=TRUE", scenario.id, scenario.currentVersion)
        val stale = when { current > 0 -> "CURRENT"; any > 0 -> "STALE"; else -> "NOT_OPENED" }
        val status = when { blocking > 0 -> "BLOCKED"; stale != "CURRENT" -> "STALE"; release == null -> "UNPUBLISHED"; else -> "READY" }
        val summary = if (metrics == 0L && dimensions == 0L && filters == 0L && stages == 0L) "默认样板未配置" else "$metrics 指标 · $dimensions 分析维度 · $filters 过滤 · $stages 后处理"
        val publication = release?.let { "已发布 ${it.releaseVersion}" } ?: "未发布"
        return ScenarioIndexItem(scenario.id, scenario.code, scenario.displayName, subject.id, subject.currentVersion, subject.displayName, business.id, business.displayName,
            scenario.description, scenario.owner, scenario.status, scenario.currentVersion, scenario.createdBy, scenario.createdAt, scenario.updatedBy, scenario.updatedAt,
            metrics, dimensions, filters, stages, summary, if (release == null) "UNPUBLISHED" else "PUBLISHED", stale, status, statusName("scenario", status), readiness(status),
            "$metrics 指标 · $dimensions 分析维度", "${staleName(stale)} · $publication", blocking, release?.releaseId, release?.releaseVersion, release?.releaseDisplayName,
            latest(scenario.updatedAt, scenario.createdAt, release?.createdAt))
    }
    fun listReleaseIndexItems(): List<ReleaseIndexItem> = releases().sortedWith(compareByDescending<ReleaseSummary> { it.createdAt }.thenByDescending { it.releaseVersion }).map { release ->
        val subject = snapshots.subjectArea(release.subjectAreaId, release.subjectAreaVersion)
        val business = snapshots.businessLine(subject.businessLineId, subject.businessLineVersion)
        val metrics = releaseCount(release, "METRIC")
        val dimensions = releaseCount(release, "ANALYSIS_DIMENSION")
        val scenarios = releaseCount(release, "SCENARIO")
        val capabilities = count("SELECT COUNT(*) FROM release_metric_dimension_capability WHERE release_id=? AND release_version=?", release.releaseId, release.releaseVersion)
        val status = when { release.blockingIssueCount > 0 -> "BLOCKED"; release.engineLoadStatus == "FAILED" -> "FAILED"; release.engineLoadStatus in setOf("BUILT", "LOADED") -> "READY"; else -> "PENDING" }
        val engineName = engineName(release.engineLoadStatus)
        ReleaseIndexItem(release.releaseId, release.releaseDisplayName, release.releaseVersion, subject.id, subject.currentVersion, subject.displayName, business.id, business.displayName,
            release.engineLoadStatus, engineName, metrics, dimensions, scenarios, capabilities, release.blockingIssueCount, status, statusName("release", status), readiness(status),
            "$metrics 指标 · $dimensions 分析维度 · $scenarios 场景", "$engineName · $capabilities 能力组合", release.createdBy, release.createdAt)
    }

    private data class ReleaseSummary(val releaseId: String, val releaseVersion: String, val releaseDisplayName: String, val subjectAreaId: String,
        val subjectAreaVersion: Long, val engineLoadStatus: String, val blockingIssueCount: Long, val createdBy: String, val createdAt: Instant)
    private data class ScenarioRelease(val releaseId: String, val releaseVersion: String, val releaseDisplayName: String, val createdAt: Instant)
    private fun releases(): List<ReleaseSummary> = try {
        query(database, """SELECT sar.*, (SELECT COUNT(*) FROM release_unavailable_reason reason WHERE reason.release_id=sar.release_id
            AND reason.release_version=sar.release_version AND reason.blocking=TRUE) AS blocking_issue_count FROM subject_area_release sar""") {
            ReleaseSummary(text(it.getString("release_id"), "subject_area_release.release_id"), text(it.getString("release_version"), "subject_area_release.release_version"),
                text(it.getString("release_display_name"), "subject_area_release.release_display_name"), text(it.getString("subject_area_id"), "subject_area_release.subject_area_id"),
                positive(nullableLong(it, "subject_area_version"), "subject_area_release.subject_area_version"), text(it.getString("engine_load_status"), "subject_area_release.engine_load_status"),
                it.getLong("blocking_issue_count"), text(it.getString("created_by"), "subject_area_release.created_by"), requireNotNull(instant(it, "created_at")))
        }
    } catch (failure: Exception) { throw IllegalStateException("Failed to list release summaries", failure) }
    private fun recentScenario(id: String, version: Long): ScenarioRelease? = try {
        query(database, """SELECT sar.release_id,sar.release_version,sar.release_display_name,sar.created_at FROM subject_area_release sar
            JOIN release_object_ref ref ON ref.release_id=sar.release_id AND ref.release_version=sar.release_version
            WHERE ref.object_type='SCENARIO' AND ref.object_id=? AND ref.object_version=? ORDER BY sar.created_at DESC,sar.release_id,sar.release_version LIMIT 1""", listOf(id, version)) {
            ScenarioRelease(text(it.getString("release_id"), "subject_area_release.release_id"), text(it.getString("release_version"), "subject_area_release.release_version"),
                text(it.getString("release_display_name"), "subject_area_release.release_display_name"), requireNotNull(instant(it, "created_at")))
        }.singleOrNull()
    } catch (failure: Exception) { throw IllegalStateException("Failed to find released scenario: $id@$version", failure) }
    private fun ownerCount(table: String, owner: String, id: String, version: Long): Long = count("SELECT COUNT(*) FROM $table WHERE ${owner}_id=? AND ${owner}_version=?", id, version)
    private fun releaseCount(release: ReleaseSummary, type: String): Long = count("SELECT COUNT(*) FROM release_object_ref WHERE release_id=? AND release_version=? AND object_type=?", release.releaseId, release.releaseVersion, type)
    private fun count(sql: String, vararg values: Any): Long = try {
        query(database, sql, values.toList()) { it.getLong(1) }.singleOrNull() ?: throw ProducerContractException("count query returned no row")
    } catch (failure: ProducerContractException) { throw failure }
      catch (failure: Exception) { throw IllegalStateException("Failed to count exact workspace projection facts", failure) }
    private fun recent(releases: List<ReleaseSummary>): ReleaseSummary? = releases.maxWithOrNull(compareBy(ReleaseSummary::createdAt, ReleaseSummary::releaseVersion))
    private fun latest(vararg values: Instant?): Instant = requireNotNull(values.filterNotNull().maxOrNull())
    private fun text(value: String?, field: String): String = value?.takeUnless { it.isBlank() } ?: throw ProducerContractException("$field is blank")
    private fun positive(value: Long?, field: String): Long = value?.takeIf { it > 0 } ?: throw ProducerContractException("$field must be a positive version")
    private fun readiness(status: String): String = if (status == "READY") "ready" else "warning"
    private fun statusName(kind: String, status: String): String = when (kind) {
        "business line" -> when (status) { "READY" -> "已有发布"; "BLOCKED" -> "存在阻塞问题"; "EMPTY" -> "尚未组织主题域"; "UNPUBLISHED" -> "尚未发布"; else -> unknown(kind, status) }
        "subject area" -> when (status) { "READY" -> "已有发布"; "BLOCKED" -> "存在阻塞问题"; "UNPUBLISHED" -> "尚未发布"; else -> unknown(kind, status) }
        "scenario" -> when (status) { "READY" -> "可查询"; "BLOCKED" -> "存在阻塞问题"; "STALE" -> "主题域开放版本滞后"; "UNPUBLISHED" -> "尚未发布"; else -> unknown(kind, status) }
        "release" -> when (status) { "READY" -> "可执行目录已生成"; "BLOCKED" -> "存在阻塞问题"; "FAILED" -> "加载失败"; "PENDING" -> "等待加载"; else -> unknown(kind, status) }
        else -> unknown(kind, status)
    }
    private fun unknown(kind: String, status: String): Nothing = throw ProducerContractException("Unknown $kind index status: $status")
    private fun staleName(status: String): String = when (status) { "CURRENT" -> "当前主题域版本已开放"; "STALE" -> "主题域开放版本滞后"; "NOT_OPENED" -> "尚未纳入主题域开放目录"; else -> throw ProducerContractException("Unknown scenario stale status: $status") }
    private fun engineName(status: String): String = when (status) { "BUILT" -> "已生成目录"; "NOT_LOADED" -> "未加载"; "PENDING" -> "待加载"; "LOADING" -> "加载中"; "LOADED" -> "已加载"; "FAILED" -> "加载失败"; else -> throw ProducerContractException("Unsupported release engine load status: $status") }
}
