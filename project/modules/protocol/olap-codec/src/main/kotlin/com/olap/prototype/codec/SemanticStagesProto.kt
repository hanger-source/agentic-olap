package com.olap.prototype.codec

import com.olap.prototype.wire.semantic.*
import com.olap.prototype.query.PostOperationContract as D
import com.olap.prototype.query.ResultAggregation as A
import com.olap.prototype.query.GroupingLevels as G
import com.olap.prototype.query.WindowFrameContract as W
import com.olap.prototype.metric.MeanTarget

internal object SemanticStagesProto {
    fun aggregation(v: A): ResultAggregation = ResultAggregation.newBuilder().apply { when (v) {
        is A.Sum -> setSum(NumericAggregation.newBuilder().setValueContract(SemanticTypesProto.value(v.valueContract)).setNullPolicy(v.nullPolicy.name))
        is A.Average -> setAverage(AverageAggregation.newBuilder().setValueContract(SemanticTypesProto.value(v.valueContract)).setNullPolicy(v.nullPolicy.name).setMeanTarget(v.meanTarget.name))
        is A.SampleDeviation -> setSampleDeviation(NumericAggregation.newBuilder().setValueContract(SemanticTypesProto.value(v.valueContract)).setNullPolicy(v.nullPolicy.name))
        is A.Minimum -> setMinimumNullPolicy(v.nullPolicy.name)
        is A.Maximum -> setMaximumNullPolicy(v.nullPolicy.name)
        is A.Count -> setCount(CountAggregation.newBuilder().setDistinct(v.distinct).setNullPolicy(v.nullPolicy.name))
        is A.Collect -> setCollectDistinct(v.distinct)
    } }.build()
    fun aggregation(v: ResultAggregation): A = when (v.kindCase) {
        ResultAggregation.KindCase.SUM -> A.Sum(SemanticTypesProto.value(v.sum.valueContract), A.NullPolicy.valueOf(v.sum.nullPolicy))
        ResultAggregation.KindCase.AVERAGE -> A.Average(SemanticTypesProto.value(v.average.valueContract), A.NullPolicy.valueOf(v.average.nullPolicy),
            required(v.average.hasMeanTarget(), "AverageAggregation.meanTarget") { MeanTarget.valueOf(v.average.meanTarget) })
        ResultAggregation.KindCase.SAMPLE_DEVIATION -> A.SampleDeviation(SemanticTypesProto.value(v.sampleDeviation.valueContract), A.NullPolicy.valueOf(v.sampleDeviation.nullPolicy))
        ResultAggregation.KindCase.MINIMUM_NULL_POLICY -> A.Minimum(A.NullPolicy.valueOf(v.minimumNullPolicy))
        ResultAggregation.KindCase.MAXIMUM_NULL_POLICY -> A.Maximum(A.NullPolicy.valueOf(v.maximumNullPolicy))
        ResultAggregation.KindCase.COUNT -> A.Count(v.count.distinct, A.NullPolicy.valueOf(v.count.nullPolicy))
        ResultAggregation.KindCase.COLLECT_DISTINCT -> A.Collect(v.collectDistinct)
        else -> error("aggregate kind is required")
    }
    fun aggregate(v: D.ResultAggregate): ResultAggregate = ResultAggregate.newBuilder().setInput(v.input).setAggregation(aggregation(v.aggregation)).setOutputAlias(v.outputAlias).setOutputDisplayName(v.outputDisplayName).build()
    fun aggregate(v: ResultAggregate): D.ResultAggregate = D.ResultAggregate(v.input, aggregation(v.aggregation), v.outputAlias, v.outputDisplayName)
    private fun order(v: D.OrderKey): OrderKey = OrderKey.newBuilder().setSymbol(v.symbol).setDirection(v.direction).setNulls(v.nulls).build()
    private fun order(v: OrderKey): D.OrderKey = D.OrderKey(v.symbol, v.direction, v.nulls)
    private fun grouping(v: D.ResultGrouping): ResultGrouping = ResultGrouping.newBuilder().addAllGroupBy(v.groupBy).addAllAggregates(v.aggregates.map(::aggregate))
        .apply { v.groupingLevels?.let { levels -> setGroupingLevels(BoundGroupingLevels.newBuilder().setOutputAlias(levels.outputAlias).addAllLevels(levels.levels.map { BoundGroupingLevel.newBuilder().setId(it.id).setCode(it.code).addAllGroupBy(it.groupBy).build() })) } }.build()
    private fun grouping(v: ResultGrouping): D.ResultGrouping = D.ResultGrouping(v.groupByList, v.aggregatesList.map(::aggregate), v.groupingLevels.takeIf { v.hasGroupingLevels() }?.let { G.Bound(it.outputAlias, it.levelsList.map { level -> G.BoundLevel(level.id, level.code, level.groupByList) }) })
    fun frame(v: W.Frame): WindowFrame = WindowFrame.newBuilder().setMode(v.mode).setLower(bound(v.lower)).setUpper(bound(v.upper)).build()
    fun frame(v: WindowFrame): W.Frame = W.Frame(v.mode, bound(v.lower), bound(v.upper))
    private fun bound(v: W.Bound): WindowBound = WindowBound.newBuilder().setKind(v.kind).apply { v.offset?.let(::setOffset); v.unit?.let(::setUnit) }.build()
    private fun bound(v: WindowBound): W.Bound = W.Bound(v.kind, v.offset.takeIf { v.hasOffset() }, v.unit.takeIf { v.hasUnit() })
    fun stage(v: D.ValidatedOperation): Stage = Stage.newBuilder().apply { when (v) {
        is D.JoinInput -> setJoinInput(StageJoin.newBuilder().setInputId(v.inputId).setOccurrenceId(v.occurrenceId).setJoinType(v.joinType).setCondition(SemanticExpressionsProto.result(v.condition)))
        is D.AncestorClosure -> setAncestorClosure(StageAncestors.newBuilder().addAllSourceKeys(v.sourceKeys).addAllParentKeys(v.parentKeys).addAllDescendantAliases(v.descendantAliases).addAllAncestorAliases(v.ancestorAliases).setIncludeSelf(v.includeSelf))
        is D.ExpandCollection -> setExpandCollection(StageExpand.newBuilder().setInput(v.input).setElementAlias(v.elementAlias).setOrdinalAlias(v.ordinalAlias).setMode(v.mode))
        is D.Project -> setProject(StringList.newBuilder().addAllValues(v.symbols))
        is D.Regroup -> setRegroup(grouping(v.grouping))
        is D.PostFilter -> setPostFilter(SemanticExpressionsProto.result(v.expression))
        is D.Sort -> setSort(StageSort.newBuilder().addAllOrderKeys(v.orderKeys.map(::order)))
        is D.TopN -> setTopN(StageTop.newBuilder().addAllOrderKeys(v.orderKeys.map(::order)).setLimit(v.limit))
        is D.Derive -> setDerive(StageDerive.newBuilder().setExpression(SemanticExpressionsProto.result(v.expression)).setOutputAlias(v.outputAlias).setOutputDisplayName(v.outputDisplayName))
        is D.Rank -> setRank(StageRank.newBuilder().setOrderBy(v.orderBy).setDirection(v.direction).addAllPartitionBy(v.partitionBy).setOutputAlias(v.outputAlias).setOutputDisplayName(v.outputDisplayName))
        is D.OrderedRank -> setOrderedRank(StageOrderedRank.newBuilder().setOperationType(v.operationType).addAllPartitionBy(v.partitionBy).setOutputAlias(v.outputAlias).setOutputDisplayName(v.outputDisplayName).addAllOrderKeys(v.orderKeys.map(::order)))
        is D.Cumulative -> setCumulative(StageCumulative.newBuilder().setInput(v.input).setOrderBy(v.orderBy).setDirection(v.direction).addAllPartitionBy(v.partitionBy).setOutputAlias(v.outputAlias).setOutputDisplayName(v.outputDisplayName).setAggregation(aggregation(v.aggregation)))
        is D.PartitionAggregate -> setPartitionAggregate(StagePartition.newBuilder().setInput(v.input).addAllPartitionBy(v.partitionBy).setOutputAlias(v.outputAlias).setOutputDisplayName(v.outputDisplayName).setAggregation(aggregation(v.aggregation)))
        is D.WindowAggregate -> setWindowAggregate(StageWindow.newBuilder().setInput(v.input).addAllPartitionBy(v.partitionBy).setOutputAlias(v.outputAlias).setOutputDisplayName(v.outputDisplayName).addAllOrderKeys(v.orderKeys.map(::order)).setAggregation(aggregation(v.aggregation)).setWindowFrame(frame(v.windowFrame)))
        is D.SelectVersions -> setSelectVersions(StageSelectVersions.newBuilder().addAllPartitionBy(v.partitionBy).addAllOrderKeys(v.orderKeys.map(::order)).setTies(v.ties.name).apply { v.snapshot?.let { snapshot -> setSnapshot(SnapshotSelection.newBuilder().setTimeSymbol(snapshot.timeSymbol).apply { snapshot.asOf?.let { setAsOf(SemanticCalculationProto.binding(it)) } }) } })
        is D.ObserveWindow -> setObserveWindow(StageObserveWindow.newBuilder().addAllInputKeys(v.inputKeys).addAllPartitionBy(v.partitionBy).setOutputAlias(v.outputAlias).setOutputDisplayName(v.outputDisplayName).addAllOrderKeys(v.orderKeys.map(::order)).setAggregation(aggregation(v.aggregation)).setWindowFrame(frame(v.windowFrame)).setDomain(v.domain.name)
            .apply { v.timeSymbol?.let(::setTimeSymbol); v.observationRange?.let { setObservationRange(SemanticCalculationProto.binding(it)) }; v.displayRange?.let { setDisplayRange(SemanticCalculationProto.binding(it)) }; v.calendar?.let { setCalendar(CalendarAxis.newBuilder().setInputId(it.inputId).setDateSymbol(it.dateSymbol)) } })
        is D.OffsetValue -> setOffsetValue(StageOffset.newBuilder().setInput(v.input).addAllPartitionBy(v.partitionBy).setOutputAlias(v.outputAlias).setOutputDisplayName(v.outputDisplayName).addAllOrderKeys(v.orderKeys.map(::order)).setFunction(v.function).setDistance(v.distance))
    } }.build()
    fun stage(v: Stage): D.ValidatedOperation = when (v.kindCase) {
        Stage.KindCase.JOIN_INPUT -> v.joinInput.let { D.JoinInput(it.inputId, it.occurrenceId, it.joinType, SemanticExpressionsProto.result(it.condition)) }
        Stage.KindCase.ANCESTOR_CLOSURE -> v.ancestorClosure.let { D.AncestorClosure(it.sourceKeysList, it.parentKeysList, it.descendantAliasesList, it.ancestorAliasesList, it.includeSelf) }
        Stage.KindCase.EXPAND_COLLECTION -> v.expandCollection.let { D.ExpandCollection(it.input, it.elementAlias, it.ordinalAlias, it.mode) }
        Stage.KindCase.PROJECT -> D.Project(v.project.valuesList)
        Stage.KindCase.REGROUP -> D.Regroup(grouping(v.regroup))
        Stage.KindCase.POST_FILTER -> D.PostFilter(SemanticExpressionsProto.result(v.postFilter))
        Stage.KindCase.SORT -> D.Sort(v.sort.orderKeysList.map(::order))
        Stage.KindCase.TOP_N -> D.TopN(v.topN.orderKeysList.map(::order), v.topN.limit)
        Stage.KindCase.DERIVE -> v.derive.let { D.Derive(SemanticExpressionsProto.result(it.expression), it.outputAlias, it.outputDisplayName) }
        Stage.KindCase.RANK -> v.rank.let { D.Rank(it.orderBy, it.direction, it.partitionByList, it.outputAlias, it.outputDisplayName) }
        Stage.KindCase.ORDERED_RANK -> v.orderedRank.let { D.OrderedRank(it.operationType, it.partitionByList, it.outputAlias, it.outputDisplayName, it.orderKeysList.map(::order)) }
        Stage.KindCase.CUMULATIVE -> v.cumulative.let { D.Cumulative(it.input, it.orderBy, it.direction, it.partitionByList, it.outputAlias, it.outputDisplayName, aggregation(it.aggregation) as? A.Sum ?: error("cumulative requires SUM")) }
        Stage.KindCase.PARTITION_AGGREGATE -> v.partitionAggregate.let { D.PartitionAggregate(it.input, it.partitionByList, it.outputAlias, it.outputDisplayName, aggregation(it.aggregation)) }
        Stage.KindCase.WINDOW_AGGREGATE -> v.windowAggregate.let { D.WindowAggregate(it.input, it.partitionByList, it.outputAlias, it.outputDisplayName, it.orderKeysList.map(::order), aggregation(it.aggregation), frame(it.windowFrame)) }
        Stage.KindCase.SELECT_VERSIONS -> v.selectVersions.let { D.SelectVersions(it.partitionByList, it.orderKeysList.map(::order), D.VersionTies.valueOf(it.ties), it.snapshot.takeIf { _ -> it.hasSnapshot() }?.let { snapshot -> D.SnapshotSelection(snapshot.timeSymbol, snapshot.asOf.takeIf { snapshot.hasAsOf() }?.let(SemanticCalculationProto::binding)) }) }
        Stage.KindCase.OBSERVE_WINDOW -> v.observeWindow.let { D.ObserveWindow(it.inputKeysList, it.partitionByList, it.outputAlias, it.outputDisplayName, it.orderKeysList.map(::order), aggregation(it.aggregation), frame(it.windowFrame), D.ObservationDomain.valueOf(it.domain), it.timeSymbol.takeIf { _ -> it.hasTimeSymbol() }, it.observationRange.takeIf { _ -> it.hasObservationRange() }?.let(SemanticCalculationProto::binding), it.displayRange.takeIf { _ -> it.hasDisplayRange() }?.let(SemanticCalculationProto::binding), it.calendar.takeIf { _ -> it.hasCalendar() }?.let { calendar -> D.CalendarAxis(calendar.inputId, calendar.dateSymbol) }) }
        Stage.KindCase.OFFSET_VALUE -> v.offsetValue.let { D.OffsetValue(it.input, it.partitionByList, it.outputAlias, it.outputDisplayName, it.orderKeysList.map(::order), it.function, it.distance) }
        else -> error("relation stage kind is required")
    }
}
