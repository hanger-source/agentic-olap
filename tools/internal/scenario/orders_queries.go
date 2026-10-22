package scenario

import (
	"path/filepath"
	"reflect"
	"strings"
)

func measure(capability object, code, alias, callID string, arguments object) object {
	metric := one(capability["metricCandidates"], "code", code)
	return object{"callId": callID, "instance": metric["instance"], "outputAlias": alias, "sourceCondition": object{"kind": "TRUE"}, "members": object{}, "relations": object{}, "arguments": arguments}
}
func measures(calls array, grouping array) object {
	ids := array{}
	for _, value := range calls {
		ids = append(ids, obj(value)["callId"])
	}
	return object{"kind": "MEASURES", "calls": calls, "grouping": grouping, "groupingLevels": nil, "sourceTimeRange": nil, "sourceClocks": object{}, "sourceCondition": object{"kind": "TRUE"}, "outputCondition": object{"kind": "TRUE"}, "sourceEventConditions": array{}, "population": object{"mode": "CALLS", "callIds": ids}}
}
func dimension(capability object, code, level string) object {
	candidate := one(capability["analysisDimensionCandidates"], "outputAlias", code)
	return object{"id": candidate["analysisDimensionId"], "version": numberText(candidate["analysisDimensionVersion"]), "levelCode": level}
}
func scope(root object, inputs object, stages array) object {
	if inputs == nil {
		inputs = object{}
	}
	if stages == nil {
		stages = array{}
	}
	return object{"root": root, "inputs": inputs, "stages": stages}
}
func symbol(name string) object { return object{"kind": "SYMBOL", "symbol": name} }
func operation(operator string, operands ...any) object {
	return object{"kind": "OPERATION", "operator": operator, "operands": operands}
}
func scopedIntent(capability, computation object) object {
	return merge(coordinate(capability), object{"formatVersion": "olap-query-intent/v6", "computation": computation})
}

func orderQueries(entries []object) []query {
	require(len(entries) == 3, "订单作者明确需要三个精确发布")
	capabilities := map[string]object{}
	for _, entry := range entries {
		capability := obj(entry["capability"])
		capabilities[str(capability["releaseVersion"])] = capability
	}
	current, prior := period("2026-01-01", "2026-02-01"), period("2025-12-01", "2026-01-01")
	result := []query{}
	for _, entry := range []struct {
		label, code, version string
		window               object
	}{
		{"original_net", "original_total", "1", current}, {"occurrence_net", "occurrence_total", "1", current}, {"gross", "gross_total", "1", current}, {"eligible_current", "fixed_cohort", "1", current}, {"fixed_prior", "fixed_cohort", "1", prior}, {"separately_prior", "separate_cohort", "1", prior}, {"prior_total", "original_total", "1", prior}, {"fixed_growth", "fixed_growth", "1", nil},
		{"order_mean", "order_mean", "1", current}, {"customer_mean", "customer_mean", "1", current}, {"unweighted", "unweighted", "1", current}, {"weighted", "weighted", "1", current}, {"updated_net", "original_total", "2", current}, {"retained_net", "original_total", "3", current},
	} {
		arguments := object{}
		if entry.window != nil {
			arguments["window"] = entry.window
		}
		capability := capabilities[entry.version]
		result = append(result, query{entry.label, intent(capability, measures(array{measure(capability, entry.code, "value", "measure", arguments)}, array{}))})
	}
	return append(result, inlineOrderQueries(capabilities["1"], current, prior)...)
}
func verifyOrders(label string, names []string, rows array) {
	label = strings.TrimPrefix(label, "saved:")
	if label == "inline_two_periods" {
		require(len(rows) == 1 && len(names) == 2 && len(arr(rows[0])) == 2, "固定同群两期间须有一个完整对齐观察")
		seen := map[string]bool{}
		for index, name := range names {
			want, ok := map[string]string{"current": "115", "prior": "80"}[name]
			require(ok && !seen[name], "两期间字段身份不完整")
			seen[name] = true
			verifyExact(name, array{array{arr(rows[0])[index]}}, want)
		}
		return
	}
	expected := map[string]string{"occurrence_net": "210", "original_net": "175", "eligible_current": "115", "fixed_prior": "80", "separately_prior": "150", "prior_total": "230", "fixed_growth": "0.4375", "gross": "240", "order_mean": "80", "customer_mean": "120", "unweighted": "82.5", "weighted": "80", "updated_net": "170", "retained_net": "175", "inline_fixed_growth": "0.4375", "inline_relation_growth": "0.4375"}
	value, ok := expected[label]
	require(ok, "未声明独立预期：%s", label)
	verifyScalar(label, rows, value)
}

func inlineOrderQueries(capability, current, prior object) []query {
	net := one(capability["metricCandidates"], "code", "original_net")
	qualified := one(capability["memberCandidates"], "displayName", "本期合格客户")
	customer := one(capability["analysisDimensionCandidates"], "outputAlias", "customer")
	definition := one(capability["semanticDependencies"], "reference", net["definition"])
	slot := one(obj(obj(definition["definition"])["calculation"])["dimensionSlots"], "name", "customer")
	money := net["valueContract"]
	people := func(name string) object {
		return scope(object{"kind": "MEMBERS", "callId": name, "slot": name, "outputs": array{object{"outputAlias": "customer", "slot": "customer"}}, "range": nil, "clocks": object{}, "condition": object{"kind": "TRUE"}}, nil, nil)
	}
	memberSlot := func(name string) object {
		return object{"name": name, "entity": qualified["entity"], "role": qualified["role"], "shape": qualified["shape"], "outputs": object{"customer": object{"kind": "DIMENSION", "dimensionSlot": "customer", "valueContract": nil}}, "fixedSource": nil}
	}
	measuredTotal := func(window object) object {
		measured := scope(object{"kind": "MEASURES", "calls": array{object{"callId": "net", "target": object{"kind": "MEASUREMENT", "slot": "net"}, "outputAlias": "measured", "dimensionSlots": object{}, "memberSlots": object{}, "measurementSlots": object{}, "relationSlots": object{}, "sourceCondition": object{"kind": "CONDITION", "condition": object{"kind": "TRUE"}}, "arguments": object{"window": window}}}, "groupSlots": array{"customer"}, "groupingArguments": array{}, "timeSlot": nil, "range": nil, "clocks": object{}, "population": object{"callIds": array{"net"}}, "groupingLevels": nil}, nil, nil)
		selected := scope(object{"kind": "INPUT", "inputId": "net_rows"}, object{"net_rows": object{"mode": "ROOT", "computation": measured}, "eligible": object{"mode": "RELATION", "computation": people("eligible")}}, array{object{"opType": "JOIN_INPUT", "arguments": object{"inputId": "eligible", "occurrenceId": "chosen", "joinType": "SEMI", "condition": operation("EQ", symbol("customer"), symbol("chosen__customer"))}}})
		return scope(object{"kind": "OBSERVATION_STATISTICS", "observations": selected, "unit": object{"kind": "OBJECTS", "keys": array{"customer"}}, "outerKeys": array{}, "statistics": array{object{"kind": "REDUCTION", "aggregate": object{"input": "measured", "aggregation": "SUM", "nullPolicy": "IGNORE", "outputAlias": "value", "outputDisplayName": "value", "valueContract": money, "aggregationRounding": "NONE"}}}}, nil, nil)
	}
	aligned := object{}
	for name, window := range map[string]object{"current": current, "prior": prior} {
		aligned[name] = object{"computation": measuredTotal(window), "keys": object{}, "values": array{object{"symbol": "value", "outputAlias": name, "noMatch": "PRESERVE_NULL"}}}
	}
	completed := scope(object{"kind": "ALIGN_MEASUREMENTS", "inputs": aligned, "observationKeys": array{}, "population": object{"kind": "BASE", "inputIds": array{"current"}}}, object{"fixed_people": object{"mode": "ROOT", "computation": people("eligible_source")}}, nil)
	mappingIDs := array{}
	for _, value := range arr(customer["eventMappings"]) {
		mappingIDs = append(mappingIDs, obj(value)["mappingId"])
	}
	computation := scope(object{"kind": "COMPUTE", "calculation": object{"dimensionSlots": array{slot}, "memberSlots": array{memberSlot("eligible_source"), memberSlot("eligible")}, "measurementSlots": array{object{"name": "net", "parameters": object{"window": object{"kind": "RANGE", "valueType": "DATE", "allowedDimensions": array{}, "defaultValue": nil, "valueContract": nil}}, "observationSlots": array{slot}, "groupingArguments": array{}, "valueContract": money}}, "relationSlots": array{}, "parameters": object{}, "computation": completed}, "bindings": object{"dimensions": object{"customer": object{"reference": dimension(capability, "customer", "customer"), "role": "customer", "mappingIds": mappingIDs}}, "members": object{"eligible_source": object{"kind": "SOURCE_CALL", "source": object{"id": qualified["sourceId"], "version": qualified["sourceVersion"]}, "arguments": object{"window": current}, "outputSlots": object{"customer": "customer"}, "members": object{}, "relations": object{}}, "eligible": object{"kind": "LOCAL", "inputId": "fixed_people", "outputSlots": object{"customer": "customer"}}}, "groupingCandidates": object{}, "measurements": object{"net": net["instance"]}, "relations": object{}}, "arguments": object{}, "members": object{}, "relations": object{}}, nil, nil)
	floating := func(expression object) object {
		return object{"kind": "CAST", "expression": expression, "targetType": "DOUBLE", "precision": nil, "scale": nil, "rounding": "NONE"}
	}
	ratio := one(capability["metricCandidates"], "code", "fixed_growth")["valueContract"]
	comparisonStages := array{object{"opType": "DERIVE", "arguments": object{"expression": object{"kind": "NUMERIC", "expression": operation("DIVIDE", floating(operation("SUBTRACT", symbol("current"), symbol("prior"))), floating(symbol("prior"))), "valueContract": ratio, "unitEquation": "ratio"}, "outputAlias": "value", "outputDisplayName": "固定同群增长率"}}, object{"opType": "PROJECT", "arguments": object{"symbols": array{"value"}}}}
	inputs := object{"two_periods": object{"mode": "ROOT", "computation": computation}}
	compared := scope(object{"kind": "INPUT", "inputId": "two_periods"}, inputs, comparisonStages)
	relationCalculation := object{
		"dimensionSlots": array{}, "memberSlots": array{}, "measurementSlots": array{},
		"relationSlots": array{object{"name": "completed", "outputs": array{
			object{"name": "current", "shape": object{"kind": "QUANTITY", "valueContract": money}},
			object{"name": "prior", "shape": object{"kind": "QUANTITY", "valueContract": money}},
		}, "keys": array{}}},
		"parameters": object{}, "computation": scope(object{"kind": "RELATION", "slot": "completed"}, nil, comparisonStages),
	}
	relationCompared := scope(object{"kind": "COMPUTE", "calculation": relationCalculation,
		"bindings":  object{"dimensions": object{}, "members": object{}, "groupingCandidates": object{}, "measurements": object{}, "relations": object{"completed": object{"kind": "PARAMETER"}}},
		"arguments": object{}, "members": object{}, "relations": object{"completed": object{"kind": "LOCAL", "inputId": "two_periods", "outputSlots": object{"current": "current", "prior": "prior"}}},
	}, inputs, nil)
	return []query{{"inline_two_periods", scopedIntent(capability, computation)}, {"inline_fixed_growth", scopedIntent(capability, compared)}, {"inline_relation_growth", scopedIntent(capability, relationCompared)}}
}
func orderCandidates(r *remote, capability object) {
	candidates := array{}
	for _, entry := range arr(capability["filterCandidates"]) {
		candidate := obj(entry)
		types, _ := candidate["filterValueTypes"].(map[string]any)
		if candidate["queryFieldKind"] == "DIMENSION" && types["customer"] == "TEXT" {
			for _, level := range arr(candidate["candidateValueLevelCodes"]) {
				if level == "customer" {
					candidates = append(candidates, candidate)
				}
			}
		}
	}
	require(len(candidates) == 1, "客户候选提供器不唯一")
	request := merge(coordinate(capability), object{"capabilityHash": capability["capabilityHash"], "target": object{"kind": "DIMENSION_PROVIDER", "filterRefId": obj(candidates[0])["filterRefId"], "levelCode": "customer"}, "offset": 0, "limit": 1})
	first := obj(r.call("candidate-values", request))
	second := obj(r.call("candidate-values", merge(request, object{"offset": 1})))
	require(reflect.DeepEqual(first["values"], array{"A"}) && integer(first["nextOffset"]) == 1 && reflect.DeepEqual(second["values"], array{"B"}) && second["nextOffset"] == nil, "候选未保持真实分页和NULL排除")
	r.callStatus("candidate-values", merge(request, object{"capabilityHash": strings.Repeat("0", 64)}), false)
	member := one(capability["memberCandidates"], "displayName", "本期合格客户")
	call := object{"kind": "MEMBERS", "callId": "eligible_values", "source": object{"id": member["sourceId"], "version": member["sourceVersion"]}, "arguments": object{"window": period("2026-01-01", "2026-02-01")}, "outputs": array{object{"kind": "SLOT", "outputAlias": "customer", "slot": "customer"}}, "range": nil, "clocks": object{}, "condition": object{"kind": "TRUE"}, "members": object{}, "relations": object{}}
	qualified := obj(r.call("candidate-values", merge(request, object{"target": object{"kind": "MEMBER_OUTPUT", "call": call}, "limit": 20})))
	require(reflect.DeepEqual(qualified["values"], array{"A"}), "真实资格输出不是客户A")
}
func orderSemantics(r *remote, capability object) {
	page := obj(r.call("search-semantics", object{"coordinate": coordinate(capability), "kind": "metrics", "offset": 0, "limit": 1}))
	require(len(arr(page["items"])) == 1 && integer(page["nextOffset"]) == 1, "语义搜索未按真实资产分页")
	selected := obj(obj(arr(page["items"])[0])["assetReference"])
	r.call("read-semantic-asset", object{"assetReference": selected})
	artifact := obj(r.call("read-semantic-catalog", object{"catalogRef": selected["catalogRef"]}))
	kinds := object{}
	for _, entry := range arr(obj(artifact["catalog"])["semanticDependencies"]) {
		definition := obj(entry)
		kinds[str(definition["kind"])] = definition["reference"]
	}
	for _, kind := range []string{"CALCULATION", "FACT", "INSTANCE", "MEMBER"} {
		require(kinds[kind] != nil, "语义目录缺少真实复用定义：%s", kind)
		r.call("read-semantic-definition", object{"catalogRef": selected["catalogRef"], "kind": kind, "reference": kinds[kind]})
	}
	r.callStatus("read-semantic-catalog", object{"catalogRef": merge(obj(selected["catalogRef"]), object{"sha256": strings.Repeat("0", 64)})}, false)
}
func executeOrders(r *remote, entries []object) object {
	capability := obj(entries[0]["capability"])
	orderSemantics(r, capability)
	orderCandidates(r, capability)
	memberWithoutValues(r, capability)
	bounded := intent(capability, measures(array{measure(capability, "original_net", "value", "measure", object{"window": period("2026-01-01", "2026-02-01")})}, array{dimension(capability, "customer", "customer")}))
	write(filepath.Join(r.output, "bounded-result-query.json"), bounded)
	saved := r.executeQueries(entries[0], orderQueries(entries), "orders-reusable-net", verifyOrders)
	page := obj(r.call("read-query-result", object{"resultReference": saved["resultReference"], "offset": 0, "limit": 1}))
	ids := array{}
	for _, value := range arr(page["columns"]) {
		ids = append(ids, obj(value)["columnId"])
	}
	r.call("profile-query-result", object{"resultReference": saved["resultReference"], "columnIds": ids})
	r.call("read-query-result-metadata", object{"resultReference": saved["resultReference"], "path": array{"queryScope"}, "offset": 0, "limit": 20})
	r.call("search-query-usage", object{"coordinate": coordinate(capability), "offset": 0, "limit": 1})
	return saved
}
func memberWithoutValues(r *remote, approved object) {
	publication := obj(r.call("read-publication", r.report["memberWithoutValues"]))
	capability := obj(publication["capability"])
	member := one(capability["memberCandidates"], "displayName", "本期合格客户")
	allowed := one(approved["memberCandidates"], "displayName", "本期合格客户")
	require(member["sourceId"] == allowed["sourceId"] && member["sourceVersion"] == allowed["sourceVersion"], "values权限试验必须复用同一精确成员")
	permission := obj(member["permission"])
	require(reflect.DeepEqual(permission["outputs"], array{"customer"}) && len(arr(permission["values"])) == 0, "受限场景未形成输出/values授权差异")
	call := object{"kind": "MEMBERS", "callId": "queryable_without_values", "source": object{"id": member["sourceId"], "version": member["sourceVersion"]}, "arguments": object{"window": period("2026-01-01", "2026-02-01")}, "members": object{}, "relations": object{}, "outputs": array{object{"kind": "SLOT", "outputAlias": "customer", "slot": "customer"}}, "range": nil, "clocks": object{}, "condition": object{"kind": "TRUE"}}
	query := intent(capability, call)
	r.call("validate-query", object{"intent": query})
	receipt := obj(r.call("execute-query", object{"intent": query, "saveResult": true, "question": "成员输出独立于候选values授权"}))
	require(receipt["status"] == "SUCCEEDED", "输出获准的同成员无法真实查询")
	_, rows := r.rows(receipt["resultReference"])
	require(reflect.DeepEqual(rows, array{array{object{"type": "TEXT", "value": "A"}}}), "受限场景的真实资格输出不是A")
	rejected := r.callStatus("candidate-values", merge(coordinate(capability), object{"capabilityHash": capability["capabilityHash"], "target": object{"kind": "MEMBER_OUTPUT", "call": call}, "offset": 0, "limit": 20}), false)
	code := obj(rejected["error"])["code"]
	require(code != "AUTHORIZATION_DENIED" && code != "FORBIDDEN", "不能以principal授权失败代替成员values门")
	write(filepath.Join(r.output, "member-without-values.json"), object{"coordinate": coordinate(capability), "sameMember": object{"id": member["sourceId"], "version": member["sourceVersion"]}, "query": receipt, "rows": rows, "candidateRejection": rejected})
}
func changedOrders(r *remote, saved object) {
	r.reopen(saved, verifyOrders)
	receipt := obj(r.call("execute-query", object{"queryReference": saved["queryReference"], "saveResult": true, "question": "已登记原订单新增10元支付"}))
	require(receipt["status"] == "SUCCEEDED" && receipt["runId"] != saved["runId"], "新数据必须产生独立真实运行")
	_, rows := r.rows(receipt["resultReference"])
	verifyExact("新增支付净额", rows, "185")
	r.call("read-query-usage", object{"runId": receipt["runId"], "offset": 0, "limit": 20})
	write(filepath.Join(r.output, "fresh-data-result.json"), object{"oldQueryReference": saved["queryReference"], "receipt": receipt, "rows": rows})
}
