package com.olap.prototype.release.catalog.capability

import java.time.Instant

data class ReleasedScenarioEntrypoint(val scenarioId: String, val scenarioVersion: Long, val scenarioDisplayName: String,
    val subjectAreaId: String, val subjectAreaVersion: Long, val subjectAreaDisplayName: String,
    val releaseId: String, val releaseVersion: String, val releaseDisplayName: String, val releasedAt: Instant)

