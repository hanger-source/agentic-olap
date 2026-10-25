package com.olap.prototype.server.results

import com.olap.prototype.codec.ProtoBytes
import com.olap.prototype.codec.QueryResultProto
import com.olap.prototype.dsl.*
import com.olap.prototype.engine.executable.ExecutableBusinessResult
import com.olap.prototype.engine.executable.ExecutableBusinessResult.*
import com.olap.prototype.query.QueryExplanationContent
import com.olap.prototype.query.contract.*
import java.math.BigDecimal

/** 在正式保存模型上投影；不复制来源类型和数值的解释规则。 */
class ResultPages(private val files: ResultFiles) {
    fun read(request: ResultReadRequest): ResultPage {
        require(request.offset >= 0 && request.limit >= 0)
        val record = files.read(request.resultReference)
        val result = record.receipt.result
        val columns = result?.columns.orEmpty()
        val rows = result?.rows.orEmpty()
        val selected = selection(columns, request.columnIds)
        val range = range(rows.size, request.offset, request.limit)
        return ResultPage(request.resultReference, summary(record), publication(record), selected.map { columns[it] },
            range.map { row -> selected.map { column -> view(rows[row][column], row, columns[column].columnId) } },
            request.offset, rows.size, next(range, rows.size))
    }
    fun metadata(request: ResultMetadataRequest): ResultMetadataPage {
        val record = files.read(request.resultReference)
        val result = record.receipt.result
        var total = 1
        var continuation: Int? = null
        fun <T> portion(values: List<T>): List<T> {
            total = values.size
            val range = range(total, request.offset, request.limit)
            continuation = next(range, total)
            return range.map(values::get)
        }
        val content = when (request.section) {
            ResultMetadataSection.RUN -> ResultMetadataContent.Run(summary(record))
            ResultMetadataSection.PUBLICATION -> ResultMetadataContent.Publication(publication(record))
            ResultMetadataSection.QUERY_INTENT -> ResultMetadataContent.Intent(record.intent)
            ResultMetadataSection.SOURCE_EVIDENCE -> record.receipt.let { ResultMetadataContent.Source(ResultSourceEvidence(it.sourceState, it.sourceRequest, it.sourceReadEvidence)) }
            ResultMetadataSection.COLUMNS -> ResultMetadataContent.Columns(portion(result?.columns.orEmpty()))
            ResultMetadataSection.INPUT_COLUMNS -> ResultMetadataContent.Columns(portion(result?.inputColumns.orEmpty()))
            ResultMetadataSection.SOURCE_SYMBOLS -> ResultMetadataContent.Symbols(portion(result?.sourceSymbols.orEmpty()))
            ResultMetadataSection.SCOPE_INPUT_COLUMNS -> if (request.scopePath != null) {
                val values = requireNotNull(result?.scopeInputColumns?.get(request.scopePath)) { "saved input scope is unavailable" }
                ResultMetadataContent.Columns(portion(values))
            } else ResultMetadataContent.ScopeIndex(portion(result?.scopeInputColumns.orEmpty().toSortedMap().map { ResultScopeColumns(it.key, it.value.size) }))
            ResultMetadataSection.EXPLANATION -> {
                val explanation = requireNotNull(record.explanation) { "this result has no saved execution explanation" }
                val value = requireNotNull(request.explanationSection).project(explanation, request.offset, request.limit)
                when (value) {
                    is QueryExplanationContent.Scopes -> { total = value.totalItems; continuation = value.nextOffset }
                    is QueryExplanationContent.Sources -> { total = value.totalItems; continuation = value.nextOffset }
                    is QueryExplanationContent.Plan -> { total = value.totalItems; continuation = value.nextOffset }
                    is QueryExplanationContent.Summary, is QueryExplanationContent.CompilationScale,
                    is QueryExplanationContent.ExecutionRequirements -> Unit
                }
                ResultMetadataContent.Explanation(value)
            }
        }
        return ResultMetadataPage(request.resultReference, request.section, content, request.offset, total, continuation)
    }
    fun value(request: ResultValueRequest): ResultValuePage {
        require(request.offset >= 0 && request.limit > 0)
        val record = files.read(request.resultReference)
        val result = requireNotNull(record.receipt.result)
        val ref = request.valueReference
        val column = selection(result.columns, listOf(ref.columnId)).single()
        var value = result.rows.getOrNull(ref.rowIndex)?.get(column) ?: error("saved row is unavailable")
        ref.path.forEach { value = members(value).getOrNull(it) ?: error("value path is outside its saved container") }
        require(ProtoBytes.digest(ProtoBytes.encode(QueryResultProto.toProto(value))) == ref.sha256) { "value reference differs from exact saved bytes" }
        val length = length(value)
        val range = range(length, request.offset, request.limit)
        val content = when (val actual = value) {
            is ScalarValue -> {
                val points = actual.value.codePoints().toArray()
                ResultValuePageContent.Text(String(points, range.first, range.count()))
            }
            is ArrayValue, is RowValue -> ResultValuePageContent.Members(range.map { index -> view(members(actual)[index], ref.rowIndex, ref.columnId, ref.path + index) })
            is NullValue -> error("NULL has no continuation")
        }
        return ResultValuePage(request.resultReference, ref, value.type, unit(value), length, request.offset, next(range, length), content)
    }
    fun profile(request: ResultProfileRequest): ResultProfile {
        val record = files.read(request.resultReference)
        require(record.receipt.status == QueryExecutionStatus.SUCCEEDED)
        val result = requireNotNull(record.receipt.result)
        val profiles = selection(result.columns, request.columnIds).map { index ->
            val column = result.columns[index]
            val values = result.rows.map { it[index] }
            val groups = values.withIndex().filter { it.value !is NullValue }.groupBy { ProtoBytes.encode(QueryResultProto.toProto(it.value)).toList() }
            val counts = if (groups.size <= 20) groups.values.map { group -> ResultValueCount(view(group.first().value, group.first().index, column.columnId), group.size.toLong()) } else null
            val numeric = values.withIndex().filter { it.value.type in setOf(ValueKind.INTEGER, ValueKind.DECIMAL) }
            val extrema = if (numeric.isEmpty()) null else {
                val sorted = numeric.sortedBy { BigDecimal((it.value as ScalarValue).value) }
                ResultNumericExtrema(view(sorted.first().value, sorted.first().index, column.columnId), view(sorted.last().value, sorted.last().index, column.columnId))
            }
            val nulls = values.count { it is NullValue }.toLong()
            ResultColumnProfile(column, nulls, values.size - nulls, groups.size.toLong(), counts, extrema)
        }
        return ResultProfile(request.resultReference, result.rows.size.toLong(), publication(record), summary(record), profiles)
    }
    private fun selection(columns: List<ExecutableBusinessResult.Column>, ids: List<String>?): List<Int> {
        if (ids == null) return columns.indices.toList()
        require(ids.isNotEmpty() && ids.distinct().size == ids.size)
        return ids.map { id -> columns.indexOfFirst { it.columnId == id }.also { require(it >= 0) { "result column is unavailable: $id" } } }
    }
    private fun range(size: Int, offset: Int, limit: Int): IntRange {
        require(offset >= 0 && limit >= 0)
        val start = minOf(size, offset)
        return start until minOf(size.toLong(), start.toLong() + limit).toInt()
    }
    private fun next(range: IntRange, size: Int): Int? = (range.last + 1).takeIf { it < size }
    private fun members(value: Value) = when (value) { is ArrayValue -> value.value; is RowValue -> value.value; else -> error("value path requires ARRAY or ROW") }
    private fun length(value: Value): Int = when (value) { is ScalarValue -> value.value.codePointCount(0, value.value.length); is NullValue -> 1; else -> members(value).size }
    private fun unit(value: Value) = when (value) { is ScalarValue -> ResultLengthUnit.UNICODE_CODE_POINTS; is NullValue -> ResultLengthUnit.VALUE; else -> ResultLengthUnit.MEMBERS }
    private fun view(value: Value, row: Int, column: String, path: List<Int> = emptyList()): ResultValueView {
        val bytes = ProtoBytes.encode(QueryResultProto.toProto(value))
        return if (value is NullValue || bytes.size <= 1024) ResultValueView.Complete(value)
        else ResultValueView.Reference(ResultValueReference(row, column, path, ProtoBytes.digest(bytes)), value.type, length(value), unit(value))
    }
    private fun summary(record: SavedQueryResultRecord) = record.receipt.let { ResultRunSummary(it.runId, it.status, true, it.rows, it.clientOutcome, record.startedAt, record.completedAt, it.timingNanos, it.error) }
    private fun publication(record: SavedQueryResultRecord) = ResultPublication(record.publication, record.catalogHash, record.capabilityHash, record.intentHash, record.queryReference)
}
