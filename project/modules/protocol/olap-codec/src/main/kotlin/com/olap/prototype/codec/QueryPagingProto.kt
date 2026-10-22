package com.olap.prototype.codec

import com.olap.prototype.query.contract.*
import com.olap.prototype.engine.executable.ExecutableBusinessResult
import java.time.Instant
import com.olap.prototype.wire.query.ResultRunSummary as WRun
import com.olap.prototype.wire.query.ResultPublication as WPublication
import com.olap.prototype.wire.query.ResultSourceEvidence as WSource
import com.olap.prototype.wire.query.ResultMetadataContent as WMetadataContent
import com.olap.prototype.wire.query.ResultMetadataPage as WMetadata
import com.olap.prototype.wire.query.ResultValueView as WValue
import com.olap.prototype.wire.query.ResultValueReferenceView as WValueReference
import com.olap.prototype.wire.query.ResultValueViews as WValues
import com.olap.prototype.wire.query.ResultPage as WPage
import com.olap.prototype.wire.query.ResultValuePage as WValuePage
import com.olap.prototype.wire.query.ResultColumnProfile as WColumnProfile
import com.olap.prototype.wire.query.ResultProfile as WProfile
import com.olap.prototype.wire.query.ResultNumericExtrema as WExtrema
import com.olap.prototype.wire.query.ResultValueCounts as WCounts
import com.olap.prototype.wire.query.ResultValueCount as WCount

object QueryPagingProto {
    fun toProto(v: ResultRunSummary): WRun = WRun.newBuilder().setRunId(v.runId).setStatus(v.status.name).setSaved(v.saved).setRows(v.rows).setClientOutcome(v.clientOutcome.name)
        .setStartedAt(v.startedAt.toString()).setCompletedAt(v.completedAt.toString()).putAllTimingNanos(v.timingNanos).apply { v.error?.let { setError(QueryProto.toProto(it)) } }.build()
    fun fromProto(v: WRun) = ResultRunSummary(v.runId, QueryExecutionStatus.valueOf(v.status), v.saved, v.rows, QueryClientOutcome.valueOf(v.clientOutcome), Instant.parse(v.startedAt), Instant.parse(v.completedAt), v.timingNanosMap.toMap(), if (v.hasError()) QueryProto.fromProto(v.error) else null)
    fun toProto(v: ResultPublication): WPublication = WPublication.newBuilder().setPublication(QueryProto.toProto(v.publication)).setCatalogHash(v.catalogHash).setCapabilityHash(v.capabilityHash).setIntentHash(v.intentHash).apply { v.queryReference?.let { setQueryReference(QueryProto.toProto(it)) } }.build()
    fun fromProto(v: WPublication): ResultPublication { require(v.hasPublication()); return ResultPublication(QueryProto.fromProto(v.publication), v.catalogHash, v.capabilityHash, v.intentHash, if (v.hasQueryReference()) QueryProto.fromProto(v.queryReference) else null) }
    fun toProto(v: ResultSourceEvidence): WSource = WSource.newBuilder().setState(v.state.name).apply { v.request?.let { setRequest(QuerySourceProto.toProto(it)) }; v.readEvidence?.let { setReadEvidence(QuerySourceProto.toProto(it)) } }.build()
    fun fromProto(v: WSource) = ResultSourceEvidence(com.olap.prototype.warehouse.contract.SourceExecutionState.valueOf(v.state), if (v.hasRequest()) QuerySourceProto.fromProto(v.request) else null, if (v.hasReadEvidence()) QuerySourceProto.fromProto(v.readEvidence) else null)
    fun toProto(v: ResultMetadataContent): WMetadataContent = WMetadataContent.newBuilder().apply { when (v) {
        is ResultMetadataContent.Run -> run = toProto(v.value)
        is ResultMetadataContent.Source -> source = toProto(v.value)
        is ResultMetadataContent.Publication -> publication = toProto(v.value)
        is ResultMetadataContent.Columns -> columns = com.olap.prototype.wire.query.BusinessColumns.newBuilder().addAllValues(v.values.map(QueryResultProto::toProto)).build()
        is ResultMetadataContent.ScopeIndex -> scopeIndex = com.olap.prototype.wire.query.ResultScopeIndex.newBuilder().addAllValues(v.values.map { com.olap.prototype.wire.query.ResultScopeColumns.newBuilder().setScopePath(it.scopePath).setColumnCount(it.columnCount).build() }).build()
        is ResultMetadataContent.Symbols -> symbols = com.olap.prototype.wire.query.ResultSymbols.newBuilder().addAllValues(v.values.map(SemanticProto::toProto)).build()
        is ResultMetadataContent.Intent -> intent = QueryProto.toProto(v.value)
        is ResultMetadataContent.Explanation -> explanation = QueryExplanationProto.toProto(v.value)
    } }.build()
    fun fromProto(v: WMetadataContent): ResultMetadataContent = when (v.kindCase) {
        WMetadataContent.KindCase.RUN -> ResultMetadataContent.Run(fromProto(v.run))
        WMetadataContent.KindCase.SOURCE -> ResultMetadataContent.Source(fromProto(v.source))
        WMetadataContent.KindCase.PUBLICATION -> ResultMetadataContent.Publication(fromProto(v.publication))
        WMetadataContent.KindCase.COLUMNS -> ResultMetadataContent.Columns(v.columns.valuesList.map(QueryResultProto::fromProto))
        WMetadataContent.KindCase.SCOPE_INDEX -> ResultMetadataContent.ScopeIndex(v.scopeIndex.valuesList.map { ResultScopeColumns(it.scopePath, it.columnCount) })
        WMetadataContent.KindCase.SYMBOLS -> ResultMetadataContent.Symbols(v.symbols.valuesList.map(SemanticProto::fromProto))
        WMetadataContent.KindCase.INTENT -> ResultMetadataContent.Intent(QueryProto.fromProto(v.intent))
        WMetadataContent.KindCase.EXPLANATION -> ResultMetadataContent.Explanation(QueryExplanationProto.fromProto(v.explanation))
        else -> error("result metadata requires one explicit section")
    }
    fun toProto(v: ResultMetadataPage): WMetadata = WMetadata.newBuilder().setResultReference(QueryProto.toProto(v.resultReference)).setSection(v.section.name).setContent(toProto(v.content)).setOffset(v.offset).setTotalItems(v.totalItems).apply { v.nextOffset?.let(::setNextOffset) }.build()
    fun fromProto(v: WMetadata): ResultMetadataPage { require(v.hasResultReference() && v.hasContent()); return ResultMetadataPage(QueryProto.fromProto(v.resultReference), ResultMetadataSection.valueOf(v.section), fromProto(v.content), v.offset, v.totalItems, if (v.hasNextOffset()) v.nextOffset else null) }
    fun toProto(v: ResultValueView): WValue = WValue.newBuilder().apply { when (v) {
        is ResultValueView.Complete -> complete = QueryResultProto.toProto(v.value)
        is ResultValueView.Reference -> reference = WValueReference.newBuilder().setReference(QueryReadProto.toProto(v.reference)).setType(v.type.name).setLength(v.length).setLengthUnit(v.lengthUnit.name).build()
    } }.build()
    fun fromProto(v: WValue): ResultValueView = when (v.kindCase) {
        WValue.KindCase.COMPLETE -> ResultValueView.Complete(QueryResultProto.fromProto(v.complete))
        WValue.KindCase.REFERENCE -> v.reference.let { require(it.hasReference()); ResultValueView.Reference(QueryReadProto.fromProto(it.reference), ExecutableBusinessResult.ValueKind.valueOf(it.type), it.length, ResultLengthUnit.valueOf(it.lengthUnit)) }
        else -> error("result value view requires a complete value or exact reference")
    }
    fun toProto(v: ResultPage): WPage = WPage.newBuilder().setResultReference(QueryProto.toProto(v.resultReference)).setRun(toProto(v.run)).setPublication(toProto(v.publication))
        .addAllColumns(v.columns.map(QueryResultProto::toProto)).addAllRows(v.rows.map { WValues.newBuilder().addAllValues(it.map(::toProto)).build() }).setOffset(v.offset).setTotalRows(v.totalRows).apply { v.nextOffset?.let(::setNextOffset) }.build()
    fun fromProto(v: WPage): ResultPage { require(v.hasResultReference() && v.hasRun() && v.hasPublication()); return ResultPage(QueryProto.fromProto(v.resultReference), fromProto(v.run), fromProto(v.publication), v.columnsList.map(QueryResultProto::fromProto), v.rowsList.map { it.valuesList.map(::fromProto) }, v.offset, v.totalRows, if (v.hasNextOffset()) v.nextOffset else null) }
    fun toProto(v: ResultValuePage): WValuePage = WValuePage.newBuilder().setResultReference(QueryProto.toProto(v.resultReference)).setValueReference(QueryReadProto.toProto(v.valueReference))
        .setType(v.type.name).setLengthUnit(v.lengthUnit.name).setTotalLength(v.totalLength).setOffset(v.offset).apply {
            v.nextOffset?.let(::setNextOffset)
            when (val c = v.content) { is ResultValuePageContent.Text -> text = c.text; is ResultValuePageContent.Members -> members = WValues.newBuilder().addAllValues(c.values.map(::toProto)).build() }
        }.build()
    fun fromProto(v: WValuePage): ResultValuePage { require(v.hasResultReference() && v.hasValueReference()); return ResultValuePage(QueryProto.fromProto(v.resultReference), QueryReadProto.fromProto(v.valueReference), ExecutableBusinessResult.ValueKind.valueOf(v.type), ResultLengthUnit.valueOf(v.lengthUnit), v.totalLength, v.offset, if (v.hasNextOffset()) v.nextOffset else null, when (v.contentCase) {
        WValuePage.ContentCase.TEXT -> ResultValuePageContent.Text(v.text)
        WValuePage.ContentCase.MEMBERS -> ResultValuePageContent.Members(v.members.valuesList.map(::fromProto))
        else -> error("result value page requires typed members or text")
    }) }
    fun toProto(v: ResultColumnProfile): WColumnProfile = WColumnProfile.newBuilder().setColumn(QueryResultProto.toProto(v.column)).setNullCount(v.nullCount).setNonNullCount(v.nonNullCount)
        .setDistinctEncodedNonNullValueCount(v.distinctEncodedNonNullValueCount).apply {
            v.valueCounts?.let { setValueCounts(WCounts.newBuilder().addAllValues(it.map { count -> WCount.newBuilder().setValue(toProto(count.value)).setCount(count.count).build() })) }
            v.numericExtrema?.let { setNumericExtrema(WExtrema.newBuilder().setMinimum(toProto(it.minimum)).setMaximum(toProto(it.maximum))) }
        }.build()
    fun fromProto(v: WColumnProfile): ResultColumnProfile { require(v.hasColumn()); return ResultColumnProfile(QueryResultProto.fromProto(v.column), v.nullCount, v.nonNullCount, v.distinctEncodedNonNullValueCount,
        if (v.hasValueCounts()) v.valueCounts.valuesList.map { require(it.hasValue()); ResultValueCount(fromProto(it.value), it.count) } else null,
        if (v.hasNumericExtrema()) v.numericExtrema.let { require(it.hasMinimum() && it.hasMaximum()); ResultNumericExtrema(fromProto(it.minimum), fromProto(it.maximum)) } else null) }
    fun toProto(v: ResultProfile): WProfile = WProfile.newBuilder().setResultReference(QueryProto.toProto(v.resultReference)).setTotalRows(v.totalRows).setPublication(toProto(v.publication)).setRun(toProto(v.run)).addAllColumns(v.columns.map(::toProto)).build()
    fun fromProto(v: WProfile): ResultProfile { require(v.hasResultReference() && v.hasPublication() && v.hasRun()); return ResultProfile(QueryProto.fromProto(v.resultReference), v.totalRows, fromProto(v.publication), fromProto(v.run), v.columnsList.map(::fromProto)) }
}
